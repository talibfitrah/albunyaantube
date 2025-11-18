package com.albunyaan.tube

import androidx.appcompat.app.AppCompatActivity
import dagger.hilt.android.AndroidEntryPoint

/**
 * An empty activity annotated with @AndroidEntryPoint for Hilt fragment testing.
 * This activity is used by launchFragmentInHiltContainer to host fragments that
 * require Hilt dependency injection.
 */
@AndroidEntryPoint
class HiltTestActivity : AppCompatActivity()
