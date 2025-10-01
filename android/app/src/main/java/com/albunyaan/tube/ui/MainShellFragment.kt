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
        binding?.mainBottomNav?.setupWithNavController(navController)
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }
}
