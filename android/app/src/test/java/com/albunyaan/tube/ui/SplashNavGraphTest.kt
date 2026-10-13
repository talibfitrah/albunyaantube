package com.albunyaan.tube.ui

import androidx.navigation.NavDestination
import androidx.navigation.NavGraphNavigator
import androidx.navigation.NavInflater
import androidx.navigation.Navigator
import androidx.navigation.NavigatorProvider
import androidx.test.core.app.ApplicationProvider
import com.albunyaan.tube.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The splash's cold-start route to verification must not mail again; sign-in's route still does. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class SplashNavGraphTest {

    @Test fun `only the splash route to email verification turns the auto-send off`() {
        val provider = NavigatorProvider().apply {
            addNavigator(NavGraphNavigator(this))
            addNavigator("fragment", object : Navigator<NavDestination>() {
                override fun createDestination() = NavDestination("fragment")
            })
        }
        val graph = NavInflater(ApplicationProvider.getApplicationContext(), provider).inflate(R.navigation.app_nav_graph)
        fun autoSend(from: Int, action: Int) = graph.findNode(from)!!.getAction(action)!!.defaultArguments?.get("autoSend")

        assertEquals(false, autoSend(R.id.splashFragment, R.id.action_splash_to_emailVerification))
        assertNull(autoSend(R.id.signInFragment, R.id.action_signIn_to_emailVerification))
    }
}
