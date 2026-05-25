package com.albunyaan.tube.ui.auth

import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.albunyaan.tube.R
import com.google.android.material.button.MaterialButton
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class EmailVerificationFragment : Fragment(R.layout.fragment_email_verification) {

    private val viewModel: EmailVerificationViewModel by viewModels()

    private lateinit var bodyText: TextView
    private lateinit var checkNowButton: MaterialButton
    private lateinit var resendButton: MaterialButton
    private lateinit var signOutButton: MaterialButton
    private lateinit var lastSentText: TextView
    private lateinit var errorText: TextView
    private lateinit var spinner: ProgressBar

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bind(view)
        wire()

        // Back-press = sign out (avoid trap loop, matches ProfileBootstrap pattern)
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() { viewModel.signOut() }
            }
        )

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.ui.collect(::render)
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.nav.collect { nav ->
                    when (nav) {
                        EmailVerificationViewModel.Nav.Idle -> Unit
                        EmailVerificationViewModel.Nav.NavigateToSplash -> {
                            findNavController().navigate(R.id.action_emailVerification_to_splash)
                            viewModel.consumeNav()
                        }
                        EmailVerificationViewModel.Nav.NavigateToSignIn -> {
                            findNavController().navigate(R.id.action_emailVerification_to_signIn)
                            viewModel.consumeNav()
                        }
                    }
                }
            }
        }
    }

    private fun bind(v: View) {
        bodyText       = v.findViewById(R.id.bodyText)
        checkNowButton = v.findViewById(R.id.checkNowButton)
        resendButton   = v.findViewById(R.id.resendButton)
        signOutButton  = v.findViewById(R.id.signOutButton)
        lastSentText   = v.findViewById(R.id.lastSentText)
        errorText      = v.findViewById(R.id.errorText)
        spinner        = v.findViewById(R.id.spinner)
    }

    private fun wire() {
        checkNowButton.setOnClickListener { viewModel.checkNow() }
        resendButton.setOnClickListener { viewModel.resend() }
        signOutButton.setOnClickListener { viewModel.signOut() }
    }

    private fun render(state: EmailVerificationViewModel.UiState) {
        bodyText.text = getString(R.string.email_verification_body, state.email)

        val busy = state.isChecking || state.isResending
        spinner.visibility = if (busy) View.VISIBLE else View.GONE
        checkNowButton.isEnabled = !busy
        resendButton.isEnabled = !busy

        state.lastSentAtMs?.let { ms ->
            val elapsedSec = ((System.currentTimeMillis() - ms) / 1000).coerceAtLeast(0)
            lastSentText.visibility = View.VISIBLE
            lastSentText.text = getString(R.string.email_verification_last_sent, elapsedSec)
        } ?: run { lastSentText.visibility = View.GONE }

        val errorRes = when (state.error) {
            EmailVerifyError.NOT_YET_VERIFIED -> R.string.email_verification_not_yet
            EmailVerifyError.RATE_LIMITED     -> R.string.email_verification_rate_limited
            EmailVerifyError.NETWORK,
            EmailVerifyError.UNKNOWN          -> R.string.email_verification_network_error
            null -> null
        }
        if (errorRes != null) {
            errorText.visibility = View.VISIBLE
            errorText.setText(errorRes)
        } else {
            errorText.visibility = View.GONE
        }
    }
}
