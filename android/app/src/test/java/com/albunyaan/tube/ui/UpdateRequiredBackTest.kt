package com.albunyaan.tube.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.addCallback
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.navigation.NavDestination
import androidx.navigation.NavHostController
import androidx.navigation.Navigator
import com.albunyaan.tube.R
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/**
 * Cubic P2 + P1: Back on the update-required screen relaunched the app at splash (MainActivity's navigateUp() from
 * a lone non-start screen), which landed on the same screen; and after a stop/start MainActivity's fallback was
 * re-added above the screen's own callback. Drives a real fragment view lifecycle through the real activity one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class UpdateRequiredBackTest {

    /** UpdateRequiredFragment's Back wiring without its Hilt injection. */
    class Screen : Fragment() {
        override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?) = View(requireContext())
        override fun onViewCreated(view: View, state: Bundle?) = leaveAppOnBack()
    }

    private fun launch(taskRoot: Boolean): ActivityController<FragmentActivity> {
        val controller = Robolectric.buildActivity(FragmentActivity::class.java).create()
        val activity = controller.get()
        shadowOf(activity).setIsTaskRoot(taskRoot)
        val nav = NavHostController(activity).apply {
            navigatorProvider.addNavigator("fragment", object : Navigator<NavDestination>() {
                override fun createDestination() = NavDestination("fragment")
            })
            setGraph(R.navigation.app_nav_graph)
            navigate(R.id.action_splash_to_updateRequired) // the real action: pops the graph, so it is alone
        }
        // MainActivity's fallback as it registers it in onCreate, before any screen: bound to the activity.
        activity.onBackPressedDispatcher.addCallback(activity) { if (!nav.navigateUp()) activity.finish() }
        controller.start().resume()
        activity.supportFragmentManager.beginTransaction().add(android.R.id.content, Screen()).commitNow()
        return controller
    }

    private fun FragmentActivity.pressBack() {
        onBackPressedDispatcher.onBackPressed()
        assertNull("Back relaunched the app", shadowOf(this).nextStartedActivity)
    }

    @Test fun `Back on the update-required screen leaves the app instead of relaunching it`() {
        val activity = launch(taskRoot = true).get()
        activity.pressBack()
        assertFalse(activity.isFinishing)
        assertTrue(shadowOf(activity).isTaskMovedToBack)
    }

    // Back from the store after tapping Update, or Home and back: the activity stops and starts again.
    @Test fun `Back still leaves the app after the activity stops and starts again`() {
        val controller = launch(taskRoot = true)
        controller.pause().stop().restart().start().resume()
        controller.get().pressBack()
        assertTrue(shadowOf(controller.get()).isTaskMovedToBack)
    }

    // The callback is the screen's: once its view is gone, Back is MainActivity's again.
    @Test fun `Back no longer leaves the app once the screen is gone`() {
        val activity = launch(taskRoot = true).get()
        val fm = activity.supportFragmentManager
        fm.beginTransaction().remove(fm.fragments.single()).commitNow()
        activity.onBackPressedDispatcher.onBackPressed()
        assertFalse(shadowOf(activity).isTaskMovedToBack)
    }

    // Opened by a deep link inside another app's task: Back returns to that app, it does not send it away too.
    @Test fun `Back from inside another app's task finishes this screen only`() {
        val activity = launch(taskRoot = false).get()
        activity.pressBack()
        assertTrue(activity.isFinishing)
        assertFalse(shadowOf(activity).isTaskMovedToBack)
    }
}
