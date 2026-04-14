package com.albunyaan.tube.ui

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.albunyaan.tube.R
import com.albunyaan.tube.util.NetworkMonitor
import com.google.android.material.navigation.NavigationBarView
import androidx.coordinatorlayout.widget.CoordinatorLayout
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainShellFragment : Fragment(R.layout.fragment_main_shell) {

    @Inject
    lateinit var networkMonitor: NetworkMonitor

    private var navigationView: NavigationBarView? = null
    private var navHostFragment: View? = null
    private var offlineBanner: TextView? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Find navigation view by ID (works for both BottomNavigationView and NavigationRailView)
        navigationView = view.findViewById(R.id.mainBottomNav)
        navHostFragment = view.findViewById(R.id.main_shell_nav_host)
        offlineBanner = view.findViewById(R.id.offlineBanner)

        // Defensive inset listener on the banner itself: the parent CoordinatorLayout's
        // `fitsSystemWindows=true` should dispatch insets here, but if a sibling
        // NavigationBarView's inset listener ever consumes them (Material3 upstream
        // behavior has churned), we would draw the banner under the status bar on
        // Android 15 edge-to-edge. Explicit top-inset padding on the banner itself
        // survives any future dispatch change. Flagged by code-reviewer (I5).
        offlineBanner?.let { banner ->
            ViewCompat.setOnApplyWindowInsetsListener(banner) { v, insets ->
                val sysBars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                v.setPadding(v.paddingLeft, sysBars.top, v.paddingRight, v.paddingBottom)
                insets
            }
        }

        // Observe connectivity and toggle the offline banner. Non-blocking — the banner
        // overlays the top edge so the user can still interact with any content below.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                networkMonitor.isOnline.collect { online ->
                    offlineBanner?.visibility = if (online) View.GONE else View.VISIBLE
                }
            }
        }

        // Prevent Material3's internal WindowInsets listener from adding bottom padding.
        // The parent CoordinatorLayout's fitsSystemWindows="true" already positions the nav
        // view above the system navigation bar. Without this, on Android 15+ (mandatory
        // edge-to-edge), Material3 adds ADDITIONAL bottom padding, compressing the icons/labels.
        navigationView?.let { nav ->
            ViewCompat.setOnApplyWindowInsetsListener(nav) { _, insets -> insets }
        }

        val navHost = childFragmentManager.findFragmentById(R.id.main_shell_nav_host) as? NavHostFragment
        val navController = navHost?.navController ?: return

        // Use setupWithNavController for automatic navigation
        navigationView?.setupWithNavController(navController)

        // Override to handle back stack properly
        navigationView?.setOnItemSelectedListener { item ->
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
        navigationView?.setOnItemReselectedListener { item ->
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
        navigationView = null
        navHostFragment = null
        offlineBanner = null
        super.onDestroyView()
    }

    /**
     * Show or hide the navigation bar (called from MainActivity for fullscreen mode).
     * Works for both BottomNavigationView and NavigationRailView.
     * On tablets, also adjusts the content margin to fill the space.
     */
    fun setBottomNavVisibility(visible: Boolean) {
        val nav = navigationView ?: return
        nav.visibility = if (visible) View.VISIBLE else View.GONE

        // On tablets (NavigationRail), adjust content margin when hiding/showing
        val isTablet = resources.getBoolean(R.bool.is_tablet)
        val zeroDimen = resources.getDimensionPixelSize(R.dimen.spacing_none)
        if (isTablet) {
            navHostFragment?.let { host ->
                val params = host.layoutParams as? CoordinatorLayout.LayoutParams
                params?.marginStart = if (visible) {
                    resources.getDimensionPixelSize(R.dimen.navigation_rail_width)
                } else {
                    zeroDimen
                }
                host.layoutParams = params
            }
        }

        // When showing navigation after fullscreen exit, reset any padding that
        // may have accumulated and force a clean layout pass.
        if (visible) {
            nav.post {
                if (!isAdded || view == null) return@post
                nav.setPaddingRelative(zeroDimen, zeroDimen, zeroDimen, zeroDimen)
                nav.requestLayout()
                view?.requestLayout()
            }
        }
    }
}
