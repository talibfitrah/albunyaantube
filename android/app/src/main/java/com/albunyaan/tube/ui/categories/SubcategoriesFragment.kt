package com.albunyaan.tube.ui.categories

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.albunyaan.tube.R
import com.albunyaan.tube.data.filters.FilterManager
import com.albunyaan.tube.data.source.ContentService
import com.albunyaan.tube.databinding.FragmentSubcategoriesBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Named

@AndroidEntryPoint
class SubcategoriesFragment : Fragment(R.layout.fragment_subcategories) {

    private var binding: FragmentSubcategoriesBinding? = null
    private lateinit var adapter: CategoryAdapter

    @Inject
    @Named("real")
    lateinit var contentService: ContentService

    @Inject
    lateinit var filterManager: FilterManager

    private val categoryId: String by lazy { requireArguments().getString(ARG_CATEGORY_ID).orEmpty() }
    private val categoryName: String by lazy { requireArguments().getString(ARG_CATEGORY_NAME).orEmpty() }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = FragmentSubcategoriesBinding.bind(view)

        // Set toolbar title and back button
        binding?.toolbar?.apply {
            title = categoryName
            setNavigationOnClickListener {
                findNavController().navigateUp()
            }
        }

        setupRecyclerView()
        loadSubcategories()
    }

    private fun setupRecyclerView() {
        adapter = CategoryAdapter { subcategory ->
            android.util.Log.d(TAG, "Subcategory clicked: ${subcategory.name}, applying filter")

            // Set the category filter to the subcategory
            val filterApplied = try {
                filterManager.setCategory(subcategory.id)
                true
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to apply category filter", e)
                context?.let { ctx ->
                    android.widget.Toast.makeText(
                        ctx,
                        getString(R.string.category_filter_error),
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
                false
            }

            if (filterApplied) {
                // Show feedback before navigation while context is guaranteed valid
                android.widget.Toast.makeText(
                    requireContext(),
                    getString(R.string.category_filter_applied, subcategory.name),
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                // Navigate to Videos tab with proper back stack management
                try {
                    findNavController().navigate(
                        R.id.videosFragment,
                        null,
                        androidx.navigation.NavOptions.Builder()
                            .setPopUpTo(R.id.homeFragment, false)
                            .build()
                    )
                } catch (e: Exception) {
                    android.util.Log.e(TAG, "Navigation to videos failed", e)
                    context?.let { ctx ->
                        android.widget.Toast.makeText(
                            ctx,
                            getString(R.string.navigation_error),
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }

        binding?.subcategoriesRecyclerView?.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@SubcategoriesFragment.adapter
        }
    }

    private fun loadSubcategories() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                android.util.Log.d(TAG, "Fetching subcategories for parentId=$categoryId...")
                val subcategories = contentService.fetchSubcategories(categoryId)
                android.util.Log.d(TAG, "Loaded ${subcategories.size} subcategories from backend")
                adapter.submitList(subcategories)
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to load subcategories", e)
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
        private const val TAG = "SubcategoriesFragment"
        const val ARG_CATEGORY_ID = "categoryId"
        const val ARG_CATEGORY_NAME = "categoryName"
    }
}
