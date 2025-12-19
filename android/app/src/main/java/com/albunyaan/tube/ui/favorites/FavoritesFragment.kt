package com.albunyaan.tube.ui.favorites

import android.os.Bundle
import android.view.View
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import com.albunyaan.tube.R
import com.albunyaan.tube.data.local.FavoriteVideo
import com.albunyaan.tube.databinding.FragmentFavoritesBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Fragment displaying the user's favorite videos.
 * Favorites are stored locally using Room database.
 */
@AndroidEntryPoint
class FavoritesFragment : Fragment(R.layout.fragment_favorites) {

    private var binding: FragmentFavoritesBinding? = null
    private val viewModel: FavoritesViewModel by viewModels()
    private val adapter = FavoritesAdapter(
        onItemClick = ::onFavoriteClicked,
        onRemoveClick = ::onRemoveClicked
    )

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = FragmentFavoritesBinding.bind(view).also { binding = it }

        setupToolbar(binding)
        setupRecyclerView(binding)
        observeFavorites(binding)
    }

    private fun setupToolbar(binding: FragmentFavoritesBinding) {
        binding.toolbar.setNavigationOnClickListener {
            findNavController().navigateUp()
        }

        binding.toolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.action_clear_all -> {
                    showClearAllConfirmation()
                    true
                }
                else -> false
            }
        }
    }

    private fun setupRecyclerView(binding: FragmentFavoritesBinding) {
        binding.favoritesRecyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            addItemDecoration(
                DividerItemDecoration(requireContext(), DividerItemDecoration.VERTICAL)
            )
            adapter = this@FavoritesFragment.adapter
        }
    }

    private fun observeFavorites(binding: FragmentFavoritesBinding) {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.favorites.collectLatest { favorites ->
                    adapter.submitList(favorites)

                    // Update empty state visibility
                    val isEmpty = favorites.isEmpty()
                    binding.emptyFavorites.visibility = if (isEmpty) View.VISIBLE else View.GONE
                    binding.favoritesRecyclerView.visibility = if (isEmpty) View.GONE else View.VISIBLE

                    // Update toolbar menu (hide clear all if empty)
                    binding.toolbar.menu.findItem(R.id.action_clear_all)?.isVisible = !isEmpty
                }
            }
        }
    }

    private fun onFavoriteClicked(favorite: FavoriteVideo) {
        // Navigate to player with the video
        val bundle = bundleOf(
            "videoId" to favorite.videoId,
            "title" to favorite.title,
            "channelName" to favorite.channelName,
            "thumbnailUrl" to favorite.thumbnailUrl,
            "durationSeconds" to favorite.durationSeconds
        )
        findNavController().navigate(R.id.action_global_playerFragment, bundle)
    }

    private fun onRemoveClicked(favorite: FavoriteVideo) {
        viewModel.removeFavorite(favorite.videoId)
    }

    private fun showClearAllConfirmation() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.favorites_clear_all_title)
            .setMessage(R.string.favorites_clear_all_message)
            .setPositiveButton(R.string.favorites_clear_all_confirm) { _, _ ->
                viewModel.clearAllFavorites()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }
}
