package com.albunyaan.tube.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import com.albunyaan.tube.R
import com.albunyaan.tube.ServiceLocator
import com.albunyaan.tube.databinding.ActivityMainBinding
import com.albunyaan.tube.locale.LocaleManager

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val navController: NavController by lazy {
        val host = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        host.navController
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Apply stored locale before super.onCreate to ensure proper locale is applied
        LocaleManager.applyStoredLocale(this)

        super.onCreate(savedInstanceState)
        ServiceLocator.init(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        navController // trigger lazy init if toolbar needed later
    }
}
