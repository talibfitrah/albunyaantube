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
import com.albunyaan.tube.auth.AccountRepository
import com.albunyaan.tube.auth.AccountState
import com.albunyaan.tube.auth.AccountStatus
import com.albunyaan.tube.auth.AuthRepository
import com.albunyaan.tube.data.sync.SyncManager
import com.albunyaan.tube.preferences.SettingsPreferences
import com.albunyaan.tube.update.UpdateInfo
import com.albunyaan.tube.update.UpdatePromptFlow
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

    /**
     * Plan C T7: fetch /api/account/me in parallel with the splash animation
     * to determine account status for routing. Retry logic lives in
     * [AccountRepositoryImpl]; SplashFragment just calls fetchMe().
     */
    @Inject lateinit var accountRepository: AccountRepository

    /**
     * D12: needed to sign out when /api/account/me fails after retries, so
     * the user gets a clean re-attempt instead of an infinite signed-in-but-stuck loop.
     */
    @Inject lateinit var authRepository: AuthRepository

    /** Plan D T26: bind is fired in background once uid is confirmed. */
    @Inject lateinit var syncManager: SyncManager

    @Inject lateinit var updatePromptFlow: UpdatePromptFlow

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

            // Plan C T7: fetch /api/account/me in parallel. If signed out or fetch
            // fails, status is null — SplashRouter treats null as "route to sign-in".
            // Plan D T26: on success, fire syncManager.bind(uid) in background so
            // the merge/pull/push cycle starts without blocking the route decision.
            val accountStatusDeferred: Deferred<AccountStatus?> = async {
                if (firebaseAuth.currentUser == null) null
                else {
                    // Cubic R7 P1 — splash uses a 1-attempt budget so it
                    // doesn't stall on the full retry window. If this one
                    // attempt fails, SplashRouter routes per the null status
                    // and the downstream screen retries with the full budget.
                    val loaded = accountRepository.fetchMe(maxAttempts = 1).getOrNull()
                    if (loaded != null) {
                        // Fire bind in a separate coroutine — don't block routing.
                        launch { syncManager.bind(loaded.uid) }
                    }
                    loaded?.status
                }
            }

            // GitHub update probe — runs in parallel with the splash animation, bounded by
            // [UpdatePromptFlow.checkForUpdate]'s own timeout so a slow network can't stall
            // cold start. Null result means "no update / failed / timed out" — splash continues
            // routing unchanged. Non-null result triggers the gating dialog before routing
            // so the user sees the prompt *before* the sign-in screen.
            val updateInfoDeferred: Deferred<UpdateInfo?> = async {
                updatePromptFlow.checkForUpdate()
            }

            // Check if this is a deep link launch - if so, skip splash entirely.
            // Deep-link launches deliberately skip the update prompt — the user tapped
            // a link expecting content, not a "new version available" dialog. Cancelling
            // the deferred frees the OkHttp connection; the prompt fires on the next
            // non-deep-link cold start. (code-reviewer I2.)
            if (isDeepLinkLaunch()) {
                updateInfoDeferred.cancel()
                routeAfterSplash(onboardingDeferred.await(), accountStatusDeferred.await())
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

            // Await update first so a slow accountStatusDeferred (auth retry against a slow
             // backend) cannot stall the gating dialog — the prompt should front the sign-in
             // screen as soon as the network probe returns (cubic round-4 P2).
            awaitUpdatePromptIfAvailable(updateInfoDeferred.await())
            val onboardingCompleted = onboardingDeferred.await()
            val accountStatus = accountStatusDeferred.await()
            routeAfterSplash(onboardingCompleted, accountStatus)
        }
    }

    /**
     * Block routing on the update dialog when a newer release is available. The dialog
     * is shown on the activity (not the fragment view) and the download/install coroutine
     * is bound to the activity lifecycle — both must survive the splash→signIn navigation
     * that fires once the user dismisses the dialog. Passing viewLifecycleOwner here would
     * cancel the in-flight APK download the moment splash gets popped.
     */
    private suspend fun awaitUpdatePromptIfAvailable(info: UpdateInfo?) {
        if (info == null || !isAdded) return
        val host = activity ?: return
        updatePromptFlow.showUpdateDialogAndAwait(host, host, info)
    }

    /** Routing logic in [SplashRouter] so it's unit-testable in isolation. */
    private fun routeAfterSplash(onboardingCompleted: Boolean, accountStatus: AccountStatus?) {
        // Cubic R7 P2 — fragment may have detached between the parallel-fetch
        // launch (line 94, viewLifecycleOwner-scoped) and resumption here if
        // the user backed out of the app while the /api/account/me retry was
        // in flight. `requireContext()` / `findNavController()` would then
        // throw IllegalStateException ("Fragment … not attached to a context")
        // and crash the dispatcher. Bail early on detach instead.
        if (!isAdded) return
        if (findNavController().currentDestination?.id != R.id.splashFragment) return
        val signedIn = firebaseAuth.currentUser != null
        if (signedIn && accountStatus == null) {
            // D12: /api/account/me fetch failed after retries. Surface to user
            // and sign out so they get a clean re-attempt instead of an infinite
            // signed-in-but-stuck loop.
            val ctx = context ?: return
            android.widget.Toast.makeText(
                ctx,
                getString(R.string.splash_couldnt_connect),
                android.widget.Toast.LENGTH_LONG
            ).show()
            // Sign-out uses the activity scope so it completes even if the
            // user pops the splash fragment immediately after seeing the
            // Toast — fire-and-forget cleanup must not be killed by view
            // teardown.
            requireActivity().lifecycleScope.launch {
                authRepository.signOut()
            }
        }
        // Cubic R-final5 P2 — emit AccountStatusEvent.Deleted/Blocked when the
        // cold-start splash detects a terminal account state, so the UI layer
        // (MainActivity terminal-dialog observer) can show the user *why*
        // they were bounced back to sign-in. Pre-fix the splash silently
        // routed to sign-in for both BLOCKED and DELETED with no signal —
        // the warm-path AccountStatusInterceptor only fires during an active
        // session, leaving cold-start launches into a deleted account with
        // no UX feedback.
        if (signedIn && (accountStatus == AccountStatus.DELETED || accountStatus == AccountStatus.BLOCKED)) {
            // Cubic R-final7 P2 — fail loud if AuthRepository doesn't implement
            // AccountStatusEmitter. Pre-fix `authRepository as? Emitter`
            // returned null silently on Hilt/test setups missing the emitter
            // wiring, so the cold-start terminal-dialog UX never fired. In
            // production AuthRepositoryImpl always implements the emitter;
            // a missing implementation here is a test/DI wiring bug that
            // should surface immediately.
            val emitter = authRepository as? com.albunyaan.tube.auth.AccountStatusEmitter
            if (emitter == null) {
                android.util.Log.e("SplashFragment",
                    "AuthRepository does not implement AccountStatusEmitter; " +
                    "cold-start terminal-dialog event WILL NOT fire. Check the DI graph.")
            } else {
                emitter.emit(
                    if (accountStatus == AccountStatus.DELETED)
                        com.albunyaan.tube.auth.AccountStatusEvent.Deleted
                    else
                        com.albunyaan.tube.auth.AccountStatusEvent.Blocked
                )
            }
        }
        val action = SplashRouter.decideSplashRoute(
            onboardingCompleted = onboardingCompleted,
            signedIn = signedIn,
            accountStatus = accountStatus,
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
