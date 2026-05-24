package com.albunyaan.tube.ui.settings.availableversions

import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.albunyaan.tube.R
import com.albunyaan.tube.update.UpdatePromptFlow
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.card.MaterialCardView
import com.google.android.material.divider.MaterialDividerItemDecoration
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class AvailableVersionsFragment : Fragment(R.layout.fragment_available_versions) {

    private val viewModel: AvailableVersionsViewModel by viewModels()

    @Inject lateinit var updatePromptFlow: UpdatePromptFlow

    private lateinit var adapter: AvailableVersionsAdapter

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener {
            findNavController().navigateUp()
        }

        adapter = AvailableVersionsAdapter(
            onInstallClick = { row ->
                val activity = activity ?: return@AvailableVersionsAdapter
                viewLifecycleOwner.lifecycleScope.launch {
                    updatePromptFlow.showUpdateDialogAndAwait(activity, viewLifecycleOwner, row.info)
                }
            },
            onOlderClick = {
                Snackbar.make(
                    view,
                    R.string.available_versions_downgrade_snackbar,
                    Snackbar.LENGTH_LONG
                ).show()
            },
        )

        val recycler = view.findViewById<RecyclerView>(R.id.versionsList)
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter
        // Inline divider between rows so the card looks identical to multi-row
        // Settings cards (Language/Theme, Support/Update). Inset start matches
        // Settings (spacing_lg from the start edge) so the line aligns with the
        // text column rather than running under the row icon.
        recycler.addItemDecoration(
            MaterialDividerItemDecoration(requireContext(), LinearLayoutManager.VERTICAL).apply {
                dividerInsetStart = resources.getDimensionPixelSize(R.dimen.spacing_lg)
                isLastItemDecorated = false
            }
        )

        val progress = view.findViewById<ProgressBar>(R.id.loading)
        val card = view.findViewById<MaterialCardView>(R.id.versionsCard)
        val emptyState = view.findViewById<View>(R.id.emptyState)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Combine rows + loading so any change to EITHER triggers a
                // visibility re-evaluation. Pre-fix the empty-state visibility
                // was only computed inside rows.collect, using a snapshot read
                // of loading.value. StateFlow dedupes identical emissions, so
                // when load() returned the still-empty list after flipping
                // loading off, the rows collector did NOT re-fire and the
                // empty-state stayed hidden forever (cubic R3 P2).
                viewModel.rows.combine(viewModel.loading) { rows, loading -> rows to loading }
                    .collect { (rows, loading) ->
                        adapter.submitList(rows)
                        // Hide the card entirely when there are no rows — an
                        // empty card with rounded corners and no content reads
                        // as a bug, and the empty-state text reads cleaner on
                        // the background_gray surface.
                        card.visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE
                        emptyState.visibility = if (rows.isEmpty() && !loading) View.VISIBLE else View.GONE
                        progress.visibility = if (loading) View.VISIBLE else View.GONE
                    }
            }
        }

        viewModel.load()
    }
}
