package com.albunyaan.tube.ui.categories

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.albunyaan.tube.R
import com.albunyaan.tube.databinding.FragmentSubcategoriesBinding

class SubcategoriesFragment : Fragment(R.layout.fragment_subcategories) {

    private var binding: FragmentSubcategoriesBinding? = null
    private lateinit var adapter: CategoryAdapter

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
            // TODO: Navigate to filtered content
        }

        binding?.subcategoriesRecyclerView?.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@SubcategoriesFragment.adapter
        }
    }

    private fun loadSubcategories() {
        // Mock subcategories based on category
        val subcategories = when (categoryId) {
            "1" -> listOf( // Quran
                Category("1_1", "Quran Recitation", false),
                Category("1_2", "Islamic Lectures", false),
                Category("1_3", "Nasheeds", false),
                Category("1_4", "Documentaries", false),
                Category("1_5", "Kids Content", false),
                Category("1_6", "Cooking", false),
                Category("1_7", "Travel", false),
                Category("1_8", "Lifestyle", false)
            )
            "10" -> listOf( // Islamic Lectures
                Category("10_1", "Quran Recitation", false),
                Category("10_2", "Islamic Lectures", false),
                Category("10_3", "Nasheeds", false),
                Category("10_4", "Documentaries", false),
                Category("10_5", "Kids Content", false),
                Category("10_6", "Cooking", false),
                Category("10_7", "Travel", false),
                Category("10_8", "Lifestyle", false)
            )
            else -> emptyList()
        }
        adapter.submitList(subcategories)
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    companion object {
        const val ARG_CATEGORY_ID = "categoryId"
        const val ARG_CATEGORY_NAME = "categoryName"
    }
}
