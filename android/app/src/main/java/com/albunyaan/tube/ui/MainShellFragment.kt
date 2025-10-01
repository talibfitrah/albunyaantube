package com.albunyaan.tube.ui

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.albunyaan.tube.R
import com.google.android.material.bottomnavigation.BottomNavigationView

class MainShellFragment : Fragment(R.layout.fragment_main_shell) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val navHost = childFragmentManager.findFragmentById(R.id.main_shell_nav_host) as? NavHostFragment
        val navController = navHost?.navController ?: return
        val bottomNav = view.findViewById<BottomNavigationView>(R.id.mainBottomNav)
        bottomNav.setupWithNavController(navController)
    }
}
