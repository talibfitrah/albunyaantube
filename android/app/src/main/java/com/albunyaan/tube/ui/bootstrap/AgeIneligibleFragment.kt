package com.albunyaan.tube.ui.bootstrap

import android.os.Bundle
import android.view.View
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

/**
 * Plan C T9: Terminal screen for users who entered a DOB below the 13-year age gate.
 *
 * Shows a message explaining they are ineligible. The OK button (and back-button,
 * per D11) both call acknowledge(), which:
 *   1. Calls FirebaseAuth.currentUser?.delete() to clean up the Auth record.
 *   2. Calls authRepository.signOut() to clear local session state.
 *   3. Emits NavigateToSignIn so the user lands on the sign-in screen.
 *
 * Back-button is captured so the user cannot bounce back to ProfileBootstrapFragment
 * and retry with a different date of birth.
 */
@AndroidEntryPoint
class AgeIneligibleFragment : Fragment(R.layout.fragment_age_ineligible) {

    private val viewModel: AgeIneligibleViewModel by viewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<MaterialButton>(R.id.okButton).setOnClickListener {
            viewModel.acknowledge()
        }

        // Back-nav from this fragment is identical to OK — don't let the user
        // bounce back to bootstrap with a different DOB.
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() { viewModel.acknowledge() }
            }
        )

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.nav.collect { nav ->
                    if (nav == AgeIneligibleNav.NavigateToSignIn) {
                        findNavController().navigate(R.id.action_ageIneligible_to_signIn)
                        viewModel.consumeNav()
                    }
                }
            }
        }
    }

    companion object { private const val TAG = "AgeIneligibleFragment" }
}
