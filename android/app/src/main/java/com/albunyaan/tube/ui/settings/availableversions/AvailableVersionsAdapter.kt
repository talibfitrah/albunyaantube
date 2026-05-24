package com.albunyaan.tube.ui.settings.availableversions

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.albunyaan.tube.R
import com.albunyaan.tube.update.ReleaseRow
import com.albunyaan.tube.update.RowState
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Renders ReleaseRows with a state-aware right-side affordance:
 *  - Newer  → "Install" tonal button → onInstallClick
 *  - Current → "Installed" chip, no action
 *  - Older  → no affordance; clicking the row → onOlderClick (snackbar)
 *
 * Date is rendered with the device locale's long format. Locale-aware
 * format means Arabic renders Arabic numerals + month names, Dutch
 * renders Dutch month names, etc.
 */
class AvailableVersionsAdapter(
    private val onInstallClick: (ReleaseRow) -> Unit,
    private val onOlderClick: (ReleaseRow) -> Unit,
) : ListAdapter<ReleaseRow, AvailableVersionsAdapter.RowVH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowVH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_available_version, parent, false)
        return RowVH(view)
    }

    override fun onBindViewHolder(holder: RowVH, position: Int) {
        holder.bind(getItem(position))
    }

    inner class RowVH(view: View) : RecyclerView.ViewHolder(view) {
        private val version: TextView = view.findViewById(R.id.version)
        private val dateLine: TextView = view.findViewById(R.id.dateLine)
        private val summary: TextView = view.findViewById(R.id.summary)
        private val action: MaterialButton = view.findViewById(R.id.action)
        private val installedChip: Chip = view.findViewById(R.id.installedChip)

        fun bind(row: ReleaseRow) {
            version.text = "v${row.info.versionName}"

            dateLine.text = row.publishedAt?.let {
                DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG)
                    .withLocale(Locale.getDefault())
                    .withZone(ZoneId.systemDefault())
                    .format(it)
            }.orEmpty()
            dateLine.visibility = if (dateLine.text.isNullOrBlank()) View.GONE else View.VISIBLE

            summary.text = row.localizedSummary.orEmpty()
            summary.visibility = if (row.localizedSummary.isNullOrBlank()) View.GONE else View.VISIBLE

            when (row.state) {
                RowState.Newer -> {
                    action.visibility = View.VISIBLE
                    installedChip.visibility = View.GONE
                    action.text = itemView.context.getString(R.string.available_versions_install)
                    action.isEnabled = true
                    action.setOnClickListener { onInstallClick(row) }
                    itemView.setOnClickListener(null)
                    itemView.isClickable = false
                }
                RowState.Current -> {
                    action.visibility = View.GONE
                    installedChip.visibility = View.VISIBLE
                    itemView.setOnClickListener(null)
                    itemView.isClickable = false
                }
                RowState.Older -> {
                    action.visibility = View.GONE
                    installedChip.visibility = View.GONE
                    itemView.isClickable = true
                    itemView.setOnClickListener { onOlderClick(row) }
                }
            }
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<ReleaseRow>() {
            override fun areItemsTheSame(o: ReleaseRow, n: ReleaseRow) =
                o.info.versionName == n.info.versionName
            override fun areContentsTheSame(o: ReleaseRow, n: ReleaseRow) = o == n
        }
    }
}
