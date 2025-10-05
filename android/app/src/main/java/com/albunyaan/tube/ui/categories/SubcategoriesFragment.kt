package com.albunyaan.tube.ui.categories

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.albunyaan.tube.R
import com.albunyaan.tube.ServiceLocator
import com.albunyaan.tube.databinding.FragmentSubcategoriesBinding
import kotlinx.coroutines.launch

class SubcategoriesFragment : Fragment(R.layout.fragment_subcategories) {

    private var binding: FragmentSubcategoriesBinding? = null
    private lateinit var adapter: CategoryAdapter
    private val contentService by lazy { ServiceLocator.provideContentService() }

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
            android.util.Log.d(TAG, "Subcategory clicked: ${subcategory.name}")
            // For now, show a toast indicating the category was selected
            // TODO: Navigate to filtered content list (Videos tab with category filter)
            android.widget.Toast.makeText(
                requireContext(),
                "Category: ${subcategory.name}\n(Filtering not yet implemented)",
                android.widget.Toast.LENGTH_SHORT
            ).show()

            // Navigate back to categories
            findNavController().navigateUp()
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
