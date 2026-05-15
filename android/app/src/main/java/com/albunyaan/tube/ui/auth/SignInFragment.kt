package com.albunyaan.tube.ui.auth

import android.app.Activity
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.albunyaan.tube.R
import com.albunyaan.tube.auth.AuthErrorCode
import com.albunyaan.tube.auth.AuthRepository
import com.albunyaan.tube.auth.AuthState
import com.albunyaan.tube.auth.toAuthErrorCode
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.OAuthProvider
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import javax.inject.Inject

/**
 * Plan B (ANDROID-AUTH-01) T4: Sign-in / sign-up screen.
 *
 * Two active sign-in paths:
 * 1. Email + password: form submit → [SignInViewModel.submit].
 * 2. Google: GoogleSignIn Activity → Google ID token → GoogleAuthProvider
 *    credential → [SignInViewModel.onCredential].
 *
 * A Microsoft path was wired (Firebase OAuthProvider) but is hidden across
 * all layouts pending ANDROID-AUTH-02; the MSA consumer backend has not
 * synced with the Azure AD `AzureADandPersonalMicrosoftAccount` audience
 * setting. The dead code is kept for fast re-enable when MSA is fixed.
 *
 * Post-sign-in routing is driven locally by the [AuthRepository.authState]
 * observer in [onViewCreated] — first [AuthState.SignedIn] pops this
 * fragment off the back stack into MainShell. MainActivity only observes
 * the 403 account-status stream, not the auth state.
 */
@AndroidEntryPoint
class SignInFragment : Fragment(R.layout.fragment_sign_in) {

    private val viewModel: SignInViewModel by viewModels()

    @Inject lateinit var firebaseAuth: FirebaseAuth
    @Inject lateinit var authRepository: AuthRepository

    private lateinit var emailField: TextInputEditText
    private lateinit var passwordField: TextInputEditText
    private lateinit var emailLayout: TextInputLayout
    private lateinit var submitButton: MaterialButton
    private lateinit var toggleModeLink: TextView
    private lateinit var forgotPasswordLink: TextView
    private lateinit var googleButton: MaterialButton
    private lateinit var microsoftButton: MaterialButton
    private lateinit var microsoftUnavailableTv: TextView
    private lateinit var errorText: TextView
    private lateinit var loadingSpinner: ProgressBar
    private lateinit var titleText: TextView

    /**
     * Registered at property-init time (before the fragment reaches CREATED) per
     * the ActivityResult API contract. Calling [registerForActivityResult] in
     * [onViewCreated] would throw `IllegalStateException("Fragment is attempting
     * to registerForActivityResult after being created.")` on first launch.
     */
    private val googleSignInLauncher: ActivityResultLauncher<android.content.Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != Activity.RESULT_OK) {
                viewModel.setLoading(false)
                return@registerForActivityResult
            }
            try {
                val account = GoogleSignIn.getSignedInAccountFromIntent(result.data)
                    .getResult(ApiException::class.java)
                val idToken = account.idToken
                if (idToken.isNullOrBlank()) {
                    viewModel.surfaceError(AuthErrorCode.GOOGLE_SIGN_IN_FAILED)
                    return@registerForActivityResult
                }
                val credential = GoogleAuthProvider.getCredential(idToken, null)
                viewModel.onCredential(credential, AuthErrorCode.GOOGLE_SIGN_IN_FAILED)
            } catch (e: ApiException) {
                Log.w(TAG, "Google sign-in returned ApiException: ${e.statusCode}")
                viewModel.surfaceError(AuthErrorCode.GOOGLE_SIGN_IN_FAILED)
            } catch (e: Exception) {
                Log.w(TAG, "Google sign-in failed", e)
                viewModel.surfaceError(AuthErrorCode.GOOGLE_SIGN_IN_FAILED)
            }
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindViews(view)
        wireListeners()
        adjustForFormFactor()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.ui.collect { render(it) }
            }
        }

        // Post-sign-in routing. Plan C T10: route via splashFragment so SplashRouter
        // makes the final routing decision based on /api/account/me status (ACTIVE → main,
        // PENDING_PROFILE → bootstrap, BLOCKED/DELETED → signIn). This is the single
        // routing decision point — keeps PENDING_PROFILE/AGE_INELIGIBLE logic out of
        // SignInFragment.
        //
        // Cubic R7 P0 — drop the repeatOnLifecycle wrapper.
        //
        // Pre-fix the collector lived inside `repeatOnLifecycle(STARTED)` which
        // re-runs the inner block on every STARTED transition. A rotation
        // mid-navigation would tear down the previous collector while the
        // popUpTo nav was still in flight, and the next STARTED rebuilt the
        // collector, observed the stale SignedIn emission, and fired
        // `nav.navigate(...)` a second time. The currentDestination guard
        // helped but races on the dispatch boundary.
        //
        // Plain `lifecycleScope.launch` (bound to viewLifecycleOwner) is the
        // right shape: cancelled on view destroy, never restarted on STARTED,
        // and `.first()` completes the flow as soon as the first SignedIn
        // arrives. A `hasNavigated` field gates the navigate call so a stale
        // emission that races process-side cannot re-fire.
        viewLifecycleOwner.lifecycleScope.launch {
            authRepository.authState
                .filterIsInstance<AuthState.SignedIn>()
                .first()
            if (!hasNavigatedFromSignIn) {
                hasNavigatedFromSignIn = true
                val nav = findNavController()
                if (nav.currentDestination?.id == R.id.signInFragment) {
                    nav.navigate(R.id.action_signIn_to_splash)
                }
            }
        }
    }

    private var hasNavigatedFromSignIn: Boolean = false

    private fun bindViews(root: View) {
        emailField = root.findViewById(R.id.emailField)
        passwordField = root.findViewById(R.id.passwordField)
        emailLayout = root.findViewById(R.id.emailLayout)
        submitButton = root.findViewById(R.id.submitButton)
        toggleModeLink = root.findViewById(R.id.toggleModeLink)
        forgotPasswordLink = root.findViewById(R.id.forgotPasswordLink)
        googleButton = root.findViewById(R.id.googleButton)
        microsoftButton = root.findViewById(R.id.microsoftButton)
        microsoftUnavailableTv = root.findViewById(R.id.microsoftUnavailableTv)
        errorText = root.findViewById(R.id.errorText)
        loadingSpinner = root.findViewById(R.id.loadingSpinner)
        titleText = root.findViewById(R.id.title)
    }

    private fun wireListeners() {
        emailField.addTextChangedListener(textWatcher { viewModel.onEmailChanged(it) })
        passwordField.addTextChangedListener(textWatcher { viewModel.onPasswordChanged(it) })

        submitButton.setOnClickListener { viewModel.submit() }
        toggleModeLink.setOnClickListener { viewModel.toggleMode() }
        forgotPasswordLink.setOnClickListener { viewModel.forgotPassword() }

        googleButton.setOnClickListener { launchGoogleSignIn() }
        microsoftButton.setOnClickListener { launchMicrosoftSignIn() }
    }

    private fun adjustForFormFactor() {
        // Microsoft sign-in is disabled pending ANDROID-AUTH-02: Microsoft MSA
        // (consumer accounts) backend has not synced with the Azure AD app's
        // AzureADandPersonalMicrosoftAccount audience setting, so consumers
        // hit "unauthorized_client" in the OAuth handler. Hidden defensively
        // at both the XML and code layers — re-enable by reverting this
        // method and removing android:visibility="gone" from the three
        // fragment_sign_in.xml variants. The TV "unavailable" placeholder is
        // also hidden because it would imply Microsoft works elsewhere.
        microsoftButton.visibility = View.GONE
        microsoftUnavailableTv.visibility = View.GONE
    }

    private fun launchGoogleSignIn() {
        // Re-entrancy guard: if any other auth flow is mid-flight (email submit,
        // Microsoft) ignore this tap. Prevents the 1-frame window between
        // setLoading(true) and the button being disabled by render().
        if (viewModel.ui.value.isLoading) return
        // `default_web_client_id` is generated by the google-services plugin only
        // when an OAuth client (client_type: 3) is configured in the Firebase
        // console. If Google sign-in hasn't been enabled yet, the resource is
        // missing — gracefully surface a config error instead of crashing.
        val resId = resources.getIdentifier("default_web_client_id", "string", requireContext().packageName)
        if (resId == 0) {
            Log.w(TAG, "default_web_client_id missing — enable Google sign-in in Firebase console")
            viewModel.surfaceError(AuthErrorCode.GOOGLE_SIGN_IN_FAILED)
            return
        }
        viewModel.setLoading(true)
        val webClientId = getString(resId)
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(webClientId)
            .requestEmail()
            .build()
        val client = GoogleSignIn.getClient(requireContext(), gso)
        // Best-effort: clear cached Google account to force the picker each time.
        // Without this, signing in after sign-out can silently re-use the prior account.
        client.signOut().addOnCompleteListener {
            googleSignInLauncher.launch(client.signInIntent)
        }
    }

    private fun launchMicrosoftSignIn() {
        if (viewModel.ui.value.isLoading) return
        viewModel.setLoading(true)
        val provider = OAuthProvider.newBuilder("microsoft.com").build()
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                firebaseAuth.startActivityForSignInWithProvider(requireActivity(), provider).await()
                // AuthStateListener (in AuthRepositoryImpl) flips state to SignedIn.
                // Nav transition driven by the observer set up in MainActivity (T5/T6).
                viewModel.setLoading(false)
            } catch (e: Exception) {
                Log.w(TAG, "Microsoft sign-in failed", e)
                val code = e.toAuthErrorCode().takeIf { it != AuthErrorCode.UNKNOWN }
                    ?: AuthErrorCode.MICROSOFT_SIGN_IN_FAILED
                viewModel.surfaceError(code)
            }
        }
    }

    private fun render(state: SignInViewModel.UiState) {
        titleText.setText(
            if (state.mode == SignInViewModel.Mode.SIGN_IN) R.string.auth_sign_in_title
            else R.string.auth_sign_up_title
        )
        submitButton.setText(
            if (state.mode == SignInViewModel.Mode.SIGN_IN) R.string.auth_sign_in_button
            else R.string.auth_sign_up_button
        )
        toggleModeLink.setText(
            if (state.mode == SignInViewModel.Mode.SIGN_IN) R.string.auth_create_account_link
            else R.string.auth_have_account_link
        )

        // Loading state disables interactive controls and shows the spinner.
        val interactive = !state.isLoading
        submitButton.isEnabled = interactive
        googleButton.isEnabled = interactive
        microsoftButton.isEnabled = interactive
        toggleModeLink.isEnabled = interactive
        forgotPasswordLink.isEnabled = interactive
        loadingSpinner.visibility = if (state.isLoading) View.VISIBLE else View.GONE

        // Error / success messaging — at most one shown at a time.
        when {
            state.error != null -> {
                errorText.setText(errorStringFor(state.error))
                errorText.visibility = View.VISIBLE
            }
            state.passwordResetSent -> {
                errorText.setText(R.string.auth_password_reset_sent)
                errorText.visibility = View.VISIBLE
            }
            else -> errorText.visibility = View.GONE
        }
    }

    private fun errorStringFor(code: AuthErrorCode): Int = when (code) {
        AuthErrorCode.INVALID_EMAIL -> R.string.auth_error_invalid_email
        AuthErrorCode.WRONG_PASSWORD -> R.string.auth_error_wrong_password
        AuthErrorCode.USER_NOT_FOUND -> R.string.auth_error_user_not_found
        AuthErrorCode.USER_DISABLED -> R.string.auth_error_user_disabled
        AuthErrorCode.EMAIL_ALREADY_IN_USE -> R.string.auth_error_email_in_use
        AuthErrorCode.WEAK_PASSWORD -> R.string.auth_error_weak_password
        AuthErrorCode.NETWORK -> R.string.auth_error_network
        AuthErrorCode.TOO_MANY_REQUESTS -> R.string.auth_error_too_many_requests
        AuthErrorCode.INVALID_CREDENTIAL -> R.string.auth_error_invalid_credential
        AuthErrorCode.GOOGLE_SIGN_IN_FAILED -> R.string.auth_error_google
        AuthErrorCode.MICROSOFT_SIGN_IN_FAILED -> R.string.auth_error_microsoft
        AuthErrorCode.PASSWORD_RESET_FAILED -> R.string.auth_error_password_reset_failed
        AuthErrorCode.UNKNOWN -> R.string.auth_error_generic
    }

    private fun textWatcher(onChange: (String) -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) { onChange(s?.toString().orEmpty()) }
    }

    companion object { private const val TAG = "SignInFragment" }
}
