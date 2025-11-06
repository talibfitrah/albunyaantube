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
import com.albunyaan.tube.ServiceLocator
import com.albunyaan.tube.databinding.FragmentCategoriesBinding
import kotlinx.coroutines.launch

class CategoriesFragment : Fragment(R.layout.fragment_categories) {

    private var binding: FragmentCategoriesBinding? = null
    private lateinit var adapter: CategoryAdapter
    private val contentService by lazy { ServiceLocator.provideContentService() }

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
                // Navigate to subcategories
                val args = bundleOf(
                    SubcategoriesFragment.ARG_CATEGORY_ID to category.id,
                    SubcategoriesFragment.ARG_CATEGORY_NAME to category.name
                )
                try {
                    android.util.Log.d("CategoriesFragment", "Attempting navigation to subcategories...")
                    findNavController().navigate(R.id.action_categoriesFragment_to_subcategoriesFragment, args)
                    android.util.Log.d("CategoriesFragment", "Navigation successful")
                } catch (e: Exception) {
                    android.util.Log.e("CategoriesFragment", "Navigation failed", e)
                }
            } else {
                android.util.Log.d("CategoriesFragment", "Category has no subcategories")
                // Show feedback that category was selected
                // TODO: Navigate to filtered content list (Videos tab with category filter)
                android.widget.Toast.makeText(
                    requireContext(),
                    "Category: ${category.name}\n(Filtering not yet implemented)",
                    android.widget.Toast.LENGTH_SHORT
                ).show()

                // Navigate back
                findNavController().navigateUp()
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

data class Category(
    val id: String,
    val name: String,
    val hasSubcategories: Boolean = false,
    val icon: String? = null // Emoji or icon identifier
)
