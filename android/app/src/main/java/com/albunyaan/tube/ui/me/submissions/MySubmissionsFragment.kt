package com.albunyaan.tube.ui.me.submissions

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.albunyaan.tube.R
import com.albunyaan.tube.data.approvals.dto.PendingApprovalDto
import com.albunyaan.tube.databinding.FragmentMySubmissionsBinding
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MySubmissionsFragment : Fragment(R.layout.fragment_my_submissions) {

    private val viewModel: MySubmissionsViewModel by viewModels()
    private var _binding: FragmentMySubmissionsBinding? = null
    private val binding get() = _binding!!

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentMySubmissionsBinding.bind(view)

        val adapter = MySubmissionAdapter(
            onOverflowClick = { anchor, item -> showOverflowMenu(anchor, item) },
        )
        binding.recycler.layoutManager = LinearLayoutManager(requireContext())
        binding.recycler.adapter = adapter

        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }
        binding.swipeRefresh.setOnRefreshListener { viewModel.refresh() }
        binding.fabSubmit.setOnClickListener {
            SubmitContentBottomSheet().show(parentFragmentManager, "submit-content")
        }

        // EditSubmissionBottomSheet signals back via fragment result so the parent can refresh.
        parentFragmentManager.setFragmentResultListener(
            EditSubmissionBottomSheet.RESULT_KEY,
            viewLifecycleOwner,
        ) { _, bundle ->
            when (bundle.getString(EditSubmissionBottomSheet.RESULT_OUTCOME)) {
                EditSubmissionBottomSheet.OUTCOME_EDIT_SUCCESS -> {
                    Snackbar.make(requireView(), R.string.my_submissions_edit_success, Snackbar.LENGTH_SHORT).show()
                    viewModel.refresh()
                }
                EditSubmissionBottomSheet.OUTCOME_EDIT_ALREADY_REVIEWED -> {
                    Snackbar.make(requireView(), R.string.my_submissions_already_reviewed, Snackbar.LENGTH_LONG).show()
                    viewModel.refresh()
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    binding.swipeRefresh.isRefreshing = state is MySubmissionsUiState.Loading
                    binding.emptyState.visibility =
                        if (state is MySubmissionsUiState.Empty) View.VISIBLE else View.GONE
                    when (state) {
                        is MySubmissionsUiState.Loaded  -> adapter.submitList(state.items)
                        is MySubmissionsUiState.Empty   -> adapter.submitList(emptyList())
                        is MySubmissionsUiState.Error   -> { /* TODO snackbar in T12 */ }
                        MySubmissionsUiState.Loading    -> { /* swipe-refresh spinner handles this */ }
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.actionEvents.collect { event ->
                    val msgRes = when (event) {
                        MySubmissionsActionEvent.DeleteSuccess          -> R.string.my_submissions_delete_success
                        MySubmissionsActionEvent.DeleteAlreadyReviewed  -> R.string.my_submissions_already_reviewed
                        MySubmissionsActionEvent.DeleteFailed           -> R.string.my_submissions_action_failed
                    }
                    Snackbar.make(requireView(), msgRes, Snackbar.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showOverflowMenu(anchor: View, item: PendingApprovalDto) {
        PopupMenu(requireContext(), anchor).apply {
            menuInflater.inflate(R.menu.menu_my_submission_overflow, menu)
            setOnMenuItemClickListener { menuItem ->
                when (menuItem.itemId) {
                    R.id.action_edit_note -> {
                        EditSubmissionBottomSheet
                            .newInstance(id = item.id, type = item.type, currentNote = item.submitterNote)
                            .show(parentFragmentManager, EditSubmissionBottomSheet.TAG)
                        true
                    }
                    R.id.action_delete_submission -> {
                        confirmDelete(item)
                        true
                    }
                    else -> false
                }
            }
            show()
        }
    }

    private fun confirmDelete(item: PendingApprovalDto) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.my_submissions_delete_confirm_title)
            .setMessage(R.string.my_submissions_delete_confirm_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.my_submissions_delete_confirm_button) { _, _ ->
                viewModel.deleteSubmission(id = item.id, type = item.type)
            }
            .show()
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
