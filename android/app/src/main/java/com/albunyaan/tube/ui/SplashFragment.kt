package com.albunyaan.tube.ui

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.isInvisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.albunyaan.tube.R
import com.albunyaan.tube.preferences.SettingsPreferences
import com.google.firebase.auth.FirebaseAuth
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Splash screen with phased animations:
 * 1. Logo appears immediately
 * 2. After a short delay, app name fades in with a slide-up animation
 * 3. Tagline and spinner fade in shortly after
 * 4. After the full animation completes, navigate to next screen
 *
 * Total splash duration: ~2.7 seconds for a polished experience.
 * Onboarding preference is fetched in parallel with animations to avoid
 * adding latency after animations complete.
 */
@AndroidEntryPoint
class SplashFragment : Fragment(R.layout.fragment_splash) {

    private lateinit var settingsPreferences: SettingsPreferences

    /**
     * Plan B (ANDROID-AUTH-01) T5: route to sign-in if no Firebase user.
     * Injected so future tests can swap in a fake. The same FirebaseAuth
     * singleton lives in [com.albunyaan.tube.auth.di.FirebaseAuthModule].
     */
    @Inject lateinit var firebaseAuth: FirebaseAuth

    /** Track running animators for cleanup on fragment destruction */
    private val runningAnimators = mutableListOf<Animator>()

    companion object {
        private const val LOGO_DISPLAY_DURATION = 600L   // Show logo alone for 600ms
        private const val TEXT_FADE_DURATION = 400L      // Fade-in animation duration
        private const val TAGLINE_DELAY = 150L           // Delay between app name and tagline
        private const val POST_ANIMATION_DELAY = 800L    // Hold after animation before navigating
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        settingsPreferences = SettingsPreferences(requireContext())

        val appName = view.requireViewById<TextView>(R.id.appName)
        val tagline = view.requireViewById<TextView>(R.id.tagline)
        val loadingSpinner = view.requireViewById<ProgressBar>(R.id.loadingSpinner)
        val splashIcon = view.requireViewById<ImageView>(R.id.splashIcon)

        // Get slide distance from design token (already in pixels)
        val slideDistance = resources.getDimension(R.dimen.splash_slide_distance)

        viewLifecycleOwner.lifecycleScope.launch {
            // Start fetching onboarding preference in parallel with animations
            // This way it's already available when animations complete
            val onboardingDeferred: Deferred<Boolean> = async {
                settingsPreferences.onboardingCompleted.first()
            }

            // Check if this is a deep link launch - if so, skip splash entirely
            if (isDeepLinkLaunch()) {
                routeAfterSplash(onboardingDeferred.await())
                return@launch
            }

            // Phase 1: Show logo alone
            delay(LOGO_DISPLAY_DURATION)

            // Make logo visible for the animation
            splashIcon.isInvisible = false
            splashIcon.alpha = 1f

            // Phase 2: Animate app name (fade in + slide up)
            // Set visibility to visible just before animating (accessibility-safe)
            appName.alpha = 0f
            appName.isInvisible = false
            AnimatorSet().apply {
                playTogether(
                    ObjectAnimator.ofFloat(appName, View.ALPHA, 0f, 1f),
                    ObjectAnimator.ofFloat(appName, View.TRANSLATION_Y, slideDistance, 0f)
                )
                duration = TEXT_FADE_DURATION
                interpolator = DecelerateInterpolator()
                start()
                runningAnimators.add(this)
            }
            // Wait for app name animation to complete before starting tagline
            delay(TEXT_FADE_DURATION)

            // Phase 3: Animate tagline (slightly delayed after app name completes)
            delay(TAGLINE_DELAY)
            tagline.alpha = 0f
            tagline.isInvisible = false
            AnimatorSet().apply {
                playTogether(
                    ObjectAnimator.ofFloat(tagline, View.ALPHA, 0f, 1f),
                    ObjectAnimator.ofFloat(tagline, View.TRANSLATION_Y, slideDistance, 0f)
                )
                duration = TEXT_FADE_DURATION
                interpolator = DecelerateInterpolator()
                start()
                runningAnimators.add(this)
            }
            // Wait for tagline animation to complete before starting spinner
            delay(TEXT_FADE_DURATION)

            // Phase 4: Fade in loading spinner
            loadingSpinner.alpha = 0f
            loadingSpinner.isInvisible = false
            ObjectAnimator.ofFloat(loadingSpinner, View.ALPHA, 0f, 1f).apply {
                duration = TEXT_FADE_DURATION
                start()
                runningAnimators.add(this)
            }
            // Wait for spinner animation to complete
            delay(TEXT_FADE_DURATION)

            // Phase 5: Hold for a moment, then navigate
            delay(POST_ANIMATION_DELAY)

            // Await the onboarding preference (should already be available from parallel fetch)
            val onboardingCompleted = onboardingDeferred.await()
            routeAfterSplash(onboardingCompleted)
        }
    }

    /** Routing logic in [SplashRouter] so it's unit-testable in isolation. */
    private fun routeAfterSplash(onboardingCompleted: Boolean) {
        if (findNavController().currentDestination?.id != R.id.splashFragment) return
        val action = SplashRouter.decideSplashRoute(
            onboardingCompleted = onboardingCompleted,
            signedIn = firebaseAuth.currentUser != null,
        )
        findNavController().navigate(action)
    }

    private fun isDeepLinkLaunch(): Boolean {
        val intent = activity?.intent ?: return false
        return intent.action == Intent.ACTION_VIEW && intent.data != null
    }


    override fun onDestroyView() {
        super.onDestroyView()
        // Cancel any running animations to prevent memory leaks
        runningAnimators.forEach { it.cancel() }
        runningAnimators.clear()
    }
}
