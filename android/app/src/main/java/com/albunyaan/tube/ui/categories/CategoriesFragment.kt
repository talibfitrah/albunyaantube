package com.albunyaan.tube.ui.categories

import android.os.Bundle
import android.view.View
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.albunyaan.tube.R
import com.albunyaan.tube.databinding.FragmentCategoriesBinding

class CategoriesFragment : Fragment(R.layout.fragment_categories) {

    private var binding: FragmentCategoriesBinding? = null
    private lateinit var adapter: CategoryAdapter

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
                // TODO: Navigate to filtered content for this category
            }
        }

        binding?.categoriesRecyclerView?.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@CategoriesFragment.adapter
        }
    }

    private fun loadCategories() {
        // Main categories based on the design mockup
        val categories = listOf(
            Category("1", "Quran", hasSubcategories = true),
            Category("2", "Hadith", hasSubcategories = false),
            Category("3", "Islamic History", hasSubcategories = true),
            Category("4", "Fiqh", hasSubcategories = false),
            Category("5", "Aqeedah", hasSubcategories = true),
            Category("6", "Seerah", hasSubcategories = false),
            Category("7", "Tafsir", hasSubcategories = true),
            Category("8", "Islamic Manners", hasSubcategories = false),
            Category("9", "Islamic Stories", hasSubcategories = false),
            Category("10", "Islamic Lectures", hasSubcategories = true)
        )
        adapter.submitList(categories)
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
    val hasSubcategories: Boolean = false
)
