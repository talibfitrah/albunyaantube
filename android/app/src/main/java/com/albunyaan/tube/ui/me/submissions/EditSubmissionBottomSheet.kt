package com.albunyaan.tube.ui.me.submissions

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.setFragmentResult
import androidx.lifecycle.lifecycleScope
import com.albunyaan.tube.R
import com.albunyaan.tube.data.approvals.MySubmissionsRepository
import com.albunyaan.tube.databinding.BottomSheetEditSubmissionBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class EditSubmissionBottomSheet : BottomSheetDialogFragment() {

    @Inject lateinit var repo: MySubmissionsRepository

    private var _binding: BottomSheetEditSubmissionBinding? = null
    private val binding get() = _binding!!

    // Local in-flight flag: at most one PATCH can be inflight at a time. Lives on
    // the fragment instance so it survives configuration changes within the same
    // sheet lifetime; on full destroy a new sheet is launched anyway.
    private var saving = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = BottomSheetEditSubmissionBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Defensive: newInstance() always sets both args, but on process-recreation
        // edge cases the system may restore the sheet without them. Dismiss quietly
        // instead of throwing IllegalStateException (which would crash the activity).
        val id = requireArguments().getString(ARG_ID)
        val type = requireArguments().getString(ARG_TYPE)
        if (id == null || type == null) {
            dismissAllowingStateLoss()
            return
        }
        // Pre-fill only on the initial bind so the user's edits survive rotation.
        if (savedInstanceState == null) {
            binding.noteInput.setText(requireArguments().getString(ARG_NOTE).orEmpty())
        }
        renderBusyState()

        binding.saveButton.setOnClickListener {
            if (saving) return@setOnClickListener
            saving = true
            renderBusyState()
            val raw = binding.noteInput.text?.toString()
            // Use the fragment's lifecycleScope so the call survives the dialog window
            // being recreated on a rotation; the snackbar/dismiss runs against the
            // current view if it's still attached, otherwise the parent will pick up
            // the FragmentResult on its next bind.
            lifecycleScope.launch {
                repo.editSubmitterNote(type = type, id = id, submitterNote = raw).fold(
                    onSuccess = {
                        setFragmentResult(RESULT_KEY, Bundle().apply {
                            putString(RESULT_OUTCOME, OUTCOME_EDIT_SUCCESS)
                        })
                        dismissAllowingStateLoss()
                    },
                    onFailure = { e ->
                        if (e is com.albunyaan.tube.data.approvals.AlreadyReviewedError) {
                            // The row has been adjudicated since the sheet opened. Dismiss
                            // and tell the parent to refresh so the stale PENDING row + its
                            // kebab disappear; the parent shows the snackbar.
                            setFragmentResult(RESULT_KEY, Bundle().apply {
                                putString(RESULT_OUTCOME, OUTCOME_EDIT_ALREADY_REVIEWED)
                            })
                            dismissAllowingStateLoss()
                            return@fold
                        }
                        saving = false
                        // The fragment captured `view` from onViewCreated at click time;
                        // after a rotation it points at the detached pre-rotation view and
                        // Snackbar.make would crash / no-op. Use the live `_binding.root`
                        // (nulled in onDestroyView, re-bound in onCreateView) so we either
                        // anchor to the current view or skip silently when in transit.
                        val anchor = _binding?.root ?: return@fold
                        Snackbar.make(anchor, R.string.my_submissions_action_failed, Snackbar.LENGTH_LONG).show()
                        renderBusyState()
                    },
                )
            }
        }
    }

    private fun renderBusyState() {
        _binding?.let { b ->
            b.saveButton.isEnabled = !saving
            b.saveProgress.visibility = if (saving) View.VISIBLE else View.GONE
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "EditSubmissionBottomSheet"

        const val RESULT_KEY = "edit_submission_result"
        const val RESULT_OUTCOME = "outcome"
        const val OUTCOME_EDIT_SUCCESS = "edit_success"
        const val OUTCOME_EDIT_ALREADY_REVIEWED = "edit_already_reviewed"

        private const val ARG_ID = "id"
        private const val ARG_TYPE = "type"
        private const val ARG_NOTE = "note"

        fun newInstance(id: String, type: String, currentNote: String?): EditSubmissionBottomSheet {
            return EditSubmissionBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_ID, id)
                    putString(ARG_TYPE, type)
                    if (currentNote != null) putString(ARG_NOTE, currentNote)
                }
            }
        }
    }
}
