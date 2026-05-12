package com.albunyaan.tube.ui.me.submissions

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.albunyaan.tube.R
import com.albunyaan.tube.databinding.FragmentMySubmissionsBinding
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

        val adapter = MySubmissionAdapter()
        binding.recycler.layoutManager = LinearLayoutManager(requireContext())
        binding.recycler.adapter = adapter

        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }
        binding.swipeRefresh.setOnRefreshListener { viewModel.refresh() }
        binding.fabSubmit.setOnClickListener {
            SubmitContentBottomSheet().show(parentFragmentManager, "submit-content")
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
