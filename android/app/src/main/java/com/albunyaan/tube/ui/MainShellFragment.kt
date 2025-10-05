package com.albunyaan.tube.ui

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.albunyaan.tube.R
import com.albunyaan.tube.databinding.FragmentMainShellBinding

class MainShellFragment : Fragment(R.layout.fragment_main_shell) {

    private var binding: FragmentMainShellBinding? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = FragmentMainShellBinding.bind(view)
        val navHost = childFragmentManager.findFragmentById(R.id.main_shell_nav_host) as? NavHostFragment
        val navController = navHost?.navController ?: return

        // Use setupWithNavController for automatic navigation
        binding?.mainBottomNav?.setupWithNavController(navController)

        // Override to handle back stack properly
        binding?.mainBottomNav?.setOnItemSelectedListener { item ->
            android.util.Log.d("MainShellFragment", "Tab selected: ${item.itemId}, current: ${navController.currentDestination?.id}")

            when {
                // If clicking the same tab, do nothing (let reselected handle it)
                navController.currentDestination?.id == item.itemId -> {
                    android.util.Log.d("MainShellFragment", "Same tab clicked")
                    true
                }
                // Try to pop back stack to the destination
                else -> {
                    val popped = navController.popBackStack(item.itemId, false)
                    android.util.Log.d("MainShellFragment", "Pop to ${item.itemId}: $popped")
                    if (!popped) {
                        // If not in back stack, navigate normally
                        try {
                            android.util.Log.d("MainShellFragment", "Navigating to ${item.itemId}")
                            navController.navigate(item.itemId)
                        } catch (e: Exception) {
                            android.util.Log.e("MainShellFragment", "Navigation failed", e)
                        }
                    }
                    true
                }
            }
        }

        // Re-click same tab to scroll to top OR navigate back if on a sub-screen
        binding?.mainBottomNav?.setOnItemReselectedListener { item ->
            android.util.Log.d("MainShellFragment", "⚠️ Tab reselected: ${item.itemId}, current dest: ${navController.currentDestination?.id}")

            // If current destination is different from the tab, navigate back to the tab
            if (navController.currentDestination?.id != item.itemId) {
                android.util.Log.d("MainShellFragment", "Navigating back to tab from sub-screen")
                navController.popBackStack(item.itemId, false)
            } else {
                // Same screen, scroll to top
                android.util.Log.e("MainShellFragment", "🔝 SCROLLING TO TOP - Tab reselected while on same screen")
                val currentFragment = navHost.childFragmentManager.primaryNavigationFragment
                val recyclerView = currentFragment?.view?.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.recyclerView)
                if (recyclerView != null) {
                    android.util.Log.e("MainShellFragment", "Found RecyclerView, scrolling to position 0")
                    recyclerView.smoothScrollToPosition(0)
                } else {
                    android.util.Log.e("MainShellFragment", "RecyclerView not found!")
                }
            }
        }
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }
}
