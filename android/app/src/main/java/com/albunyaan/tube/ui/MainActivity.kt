package com.albunyaan.tube.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import com.albunyaan.tube.R

class MainActivity : AppCompatActivity() {

    private val navController: NavController by lazy {
        val host = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        host.navController
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // navController available for future toolbar setup if needed.
    }
}
