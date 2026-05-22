package com.albunyaan.tube.ui.me.submissions

import android.graphics.Color
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.ImageViewCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.CircleCropTransformation
import com.albunyaan.tube.R
import com.albunyaan.tube.data.approvals.dto.PendingApprovalDto
import com.albunyaan.tube.databinding.ItemMySubmissionBinding
import com.google.android.material.shape.ShapeAppearanceModel

class MySubmissionAdapter(
    private val onOverflowClick: (View, PendingApprovalDto) -> Unit,
) : ListAdapter<PendingApprovalDto, MySubmissionAdapter.VH>(Diff) {

    class VH(
        val binding: ItemMySubmissionBinding,
        private val onOverflowClick: (View, PendingApprovalDto) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: PendingApprovalDto) {
            val ctx = binding.root.context

            // Channels show a circular avatar; videos/playlists show an 8dp-rounded thumbnail.
            // Material's ShapeableImageView lets us toggle without separate layouts.
            val cornerSize = if (item.type.equals("CHANNEL", ignoreCase = true)) {
                ctx.resources.displayMetrics.density * 28f // half of 56dp -> circle
            } else {
                ctx.resources.displayMetrics.density * 8f
            }
            binding.thumbnail.shapeAppearanceModel = ShapeAppearanceModel.builder()
                .setAllCornerSizes(cornerSize)
                .build()
            binding.thumbnail.load(item.thumbnailUrl) {
                crossfade(true)
                placeholder(R.drawable.ic_launcher_movie)
                error(R.drawable.ic_launcher_movie)
                if (item.type.equals("CHANNEL", ignoreCase = true)) {
                    transformations(CircleCropTransformation())
                }
            }

            // Big title — fall back to a placeholder if backend enrichment hasn't
            // populated the name yet (first refresh after submission can be empty).
            binding.title.text = item.title?.takeIf { it.isNotBlank() }
                ?: ctx.getString(R.string.my_submissions_title_placeholder)

            binding.typeAndCategory.text = listOfNotNull(
                item.type.lowercase().replaceFirstChar { it.uppercase() },
                item.category,
            ).joinToString(" · ")

            // Identifiers row: YouTube ID only — the user cares about which YouTube
            // entity this is, not the internal Firestore document id (which was just
            // UX noise per cubic review).
            binding.identifiers.text = item.youtubeId?.takeIf { it.isNotBlank() }
                ?.let { "yt:$it" }.orEmpty()
            binding.identifiers.isVisible = binding.identifiers.text.isNotEmpty()

            binding.submittedAt.text = item.submittedAt?.let {
                DateUtils.getRelativeTimeSpanString(
                    it,
                    System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS,
                )
            } ?: ""

            // Status pill — colored background + status icon + bold label.
            // Each status maps to (label, color, icon); we tint the same shape drawable
            // and swap the start-compound icon. Existing project icons (ic_chip_check
            // 18dp, ic_close 24dp) are reused at a forced 16dp so the pill height
            // stays uniform across all four statuses.
            val (statusLabelRes, statusColorRes, statusIconRes) = when (item.status) {
                "PENDING"         -> Triple(R.string.my_submissions_status_pending,         R.color.my_submission_status_pending,         R.drawable.ic_status_clock)
                "APPROVED"        -> Triple(R.string.my_submissions_status_approved,        R.color.my_submission_status_approved,        R.drawable.ic_chip_check)
                "REJECTED"        -> Triple(R.string.my_submissions_status_rejected,        R.color.my_submission_status_rejected,        R.drawable.ic_close)
                "REQUEST_CHANGES" -> Triple(R.string.my_submissions_status_request_changes, R.color.my_submission_status_request_changes, R.drawable.ic_status_alert)
                else              -> Triple(R.string.my_submissions_status_pending,         R.color.my_submission_status_pending,         R.drawable.ic_status_clock)
            }
            binding.statusBadge.text = ctx.getString(statusLabelRes)
            binding.statusBadge.backgroundTintList = ContextCompat.getColorStateList(ctx, statusColorRes)
            val iconPx = (ctx.resources.displayMetrics.density * 16f).toInt()
            // `ic_close.xml` carries vector-level `?attr/colorControlNormal` which would
            // resolve to gray on the REJECTED red pill (invisible). Force white so the
            // glyph always reads against the status-tinted pill background.
            val icon = ContextCompat.getDrawable(ctx, statusIconRes)?.mutate()?.apply {
                setBounds(0, 0, iconPx, iconPx)
                setTint(Color.WHITE)
            }
            binding.statusBadge.setCompoundDrawablesRelative(icon, null, null, null)

            // Submitter's own "why I'm suggesting this" message — only shown when present.
            val showSubmitterNote = !item.submitterNote.isNullOrBlank()
            binding.submitterNoteLabel.isVisible = showSubmitterNote
            binding.submitterNote.isVisible = showSubmitterNote
            binding.submitterNote.text = item.submitterNote

            // Admin's review note — only meaningful on REQUEST_CHANGES rows for now.
            val showReviewNote = item.status == "REQUEST_CHANGES" && !item.reviewNotes.isNullOrBlank()
            binding.reviewNote.isVisible = showReviewNote
            binding.reviewNote.text = item.reviewNotes

            // Overflow is only useful while the row is still submitter-owned.
            val canManage = item.status == "PENDING" || item.status == "REQUEST_CHANGES"
            binding.overflowButton.isVisible = canManage
            if (canManage) {
                binding.overflowButton.setOnClickListener { v -> onOverflowClick(v, item) }
            } else {
                binding.overflowButton.setOnClickListener(null)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemMySubmissionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b, onOverflowClick)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    object Diff : DiffUtil.ItemCallback<PendingApprovalDto>() {
        override fun areItemsTheSame(a: PendingApprovalDto, b: PendingApprovalDto) = a.id == b.id
        override fun areContentsTheSame(a: PendingApprovalDto, b: PendingApprovalDto) = a == b
    }
}
