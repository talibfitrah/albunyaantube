package com.albunyaan.tube.ui.categories

import android.os.Bundle
import android.view.View
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.albunyaan.tube.R
import com.albunyaan.tube.data.filters.FilterManager
import com.albunyaan.tube.data.model.Category
import com.albunyaan.tube.data.source.ContentService
import com.albunyaan.tube.locale.LocaleManager
import com.albunyaan.tube.databinding.FragmentCategoriesBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Named

@AndroidEntryPoint
class CategoriesFragment : Fragment(R.layout.fragment_categories) {

    private var binding: FragmentCategoriesBinding? = null
    private lateinit var adapter: CategoryAdapter

    @Inject
    @Named("real")
    internal lateinit var contentService: ContentService

    @Inject
    internal lateinit var filterManager: FilterManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        android.util.Log.d(TAG, "onCreate called")
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        android.util.Log.d(TAG, "onViewCreated called")
        binding = FragmentCategoriesBinding.bind(view)

        // Setup toolbar back button
        binding?.toolbar?.setNavigationOnClickListener {
            findNavController().navigateUp()
        }

        setupRecyclerView()
        loadCategories()
    }

    private fun setupRecyclerView() {
        adapter = CategoryAdapter { category ->
            android.util.Log.d("CategoriesFragment", "Category clicked: ${category.name}, hasSubcategories: ${category.hasSubcategories}")
            if (category.hasSubcategories) {
                // Navigate to subcategories with localized name
                val currentLocale = LocaleManager.getCurrentLocale(requireContext()).language
                val localizedName = category.localizedNames?.get(currentLocale) ?: category.name
                val args = bundleOf(
                    SubcategoriesFragment.ARG_CATEGORY_ID to category.id,
                    SubcategoriesFragment.ARG_CATEGORY_NAME to localizedName
                )
                try {
                    android.util.Log.d("CategoriesFragment", "Attempting navigation to subcategories...")
                    findNavController().navigate(R.id.action_categoriesFragment_to_subcategoriesFragment, args)
                    android.util.Log.d("CategoriesFragment", "Navigation successful")
                } catch (e: Exception) {
                    android.util.Log.e("CategoriesFragment", "Navigation failed", e)
                }
            } else {
                android.util.Log.d("CategoriesFragment", "Category has no subcategories, applying filter")

                // Step 1: Set the category filter
                val filterApplied = try {
                    filterManager.setCategory(category.id)
                    true
                } catch (e: Exception) {
                    android.util.Log.e("CategoriesFragment", "Failed to set category filter", e)
                    android.widget.Toast.makeText(
                        requireContext(),
                        getString(R.string.category_filter_error),
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                    false
                }

                // Step 2: Navigate to Videos tab (only if filter was applied successfully)
                if (filterApplied) {
                    val currentLocale = LocaleManager.getCurrentLocale(requireContext()).language
                    val localizedName = category.localizedNames?.get(currentLocale) ?: category.name
                    // Show feedback before navigation while context is guaranteed valid
                    android.widget.Toast.makeText(
                        requireContext(),
                        getString(R.string.category_filter_applied, localizedName),
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                    try {
                        // Use NavOptions to pop and navigate atomically, avoiding flash
                        findNavController().navigate(
                            R.id.videosFragment,
                            null,
                            androidx.navigation.NavOptions.Builder()
                                .setPopUpTo(R.id.homeFragment, false)
                                .build()
                        )
                    } catch (e: Exception) {
                        android.util.Log.e("CategoriesFragment", "Navigation to videos failed", e)
                        // Clear filter to avoid inconsistent state (filter applied but not on Videos tab)
                        try {
                            filterManager.setCategory(null)
                        } catch (clearError: Exception) {
                            android.util.Log.e("CategoriesFragment", "Failed to clear filter", clearError)
                        }
                        context?.let { ctx ->
                            android.widget.Toast.makeText(
                                ctx,
                                getString(R.string.category_filter_error),
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            }
        }

        binding?.categoriesRecyclerView?.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@CategoriesFragment.adapter
        }
    }

    private fun loadCategories() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                android.util.Log.d(TAG, "Fetching categories from backend...")
                val categories = contentService.fetchCategories()
                android.util.Log.d(TAG, "Loaded ${categories.size} categories from backend")
                adapter.submitList(categories)
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to load categories", e)
                // Show error state or fallback to empty list
                adapter.submitList(emptyList())
            }
        }
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    companion object {
        private const val TAG = "CategoriesFragment"
    }
}
