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
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
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

        val progress = view.findViewById<ProgressBar>(R.id.loading)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.rows.collect { rows ->
                        adapter.submitList(rows)
                        view.findViewById<View>(R.id.emptyState)?.visibility =
                            if (rows.isEmpty() && !viewModel.loading.value) View.VISIBLE else View.GONE
                    }
                }
                launch {
                    viewModel.loading.collect {
                        progress.visibility = if (it) View.VISIBLE else View.GONE
                    }
                }
            }
        }

        viewModel.load()
    }
}
