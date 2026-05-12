package com.albunyaan.tube.ui.me.submissions

import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.albunyaan.tube.R
import com.albunyaan.tube.data.approvals.dto.PendingApprovalDto
import com.albunyaan.tube.databinding.ItemMySubmissionBinding

class MySubmissionAdapter : ListAdapter<PendingApprovalDto, MySubmissionAdapter.VH>(Diff) {

    class VH(val binding: ItemMySubmissionBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: PendingApprovalDto) {
            val ctx = binding.root.context
            binding.title.text = item.title ?: item.entityId
            binding.typeAndCategory.text = listOfNotNull(
                item.type.replaceFirstChar { it.uppercase() },
                item.category,
            ).joinToString(" · ")
            binding.submittedAt.text = item.submittedAt?.let {
                DateUtils.getRelativeTimeSpanString(
                    it,
                    System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS,
                )
            } ?: ""
            val (statusLabel, statusColorRes) = when (item.status) {
                "PENDING"          -> ctx.getString(R.string.my_submissions_status_pending)         to R.color.my_submission_status_pending
                "APPROVED"         -> ctx.getString(R.string.my_submissions_status_approved)        to R.color.my_submission_status_approved
                "REJECTED"         -> ctx.getString(R.string.my_submissions_status_rejected)        to R.color.my_submission_status_rejected
                "REQUEST_CHANGES"  -> ctx.getString(R.string.my_submissions_status_request_changes) to R.color.my_submission_status_request_changes
                else               -> item.status                                                    to R.color.my_submission_status_pending
            }
            binding.statusBadge.text = statusLabel
            binding.statusStrip.setBackgroundColor(ContextCompat.getColor(ctx, statusColorRes))
            val showNote = item.status == "REQUEST_CHANGES" && !item.reviewNotes.isNullOrBlank()
            binding.reviewNote.visibility = if (showNote) View.VISIBLE else View.GONE
            binding.reviewNote.text = item.reviewNotes
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemMySubmissionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    object Diff : DiffUtil.ItemCallback<PendingApprovalDto>() {
        override fun areItemsTheSame(a: PendingApprovalDto, b: PendingApprovalDto) = a.id == b.id
        override fun areContentsTheSame(a: PendingApprovalDto, b: PendingApprovalDto) = a == b
    }
}
