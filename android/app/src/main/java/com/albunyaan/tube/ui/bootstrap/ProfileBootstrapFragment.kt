package com.albunyaan.tube.ui.bootstrap

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.albunyaan.tube.R
import com.albunyaan.tube.auth.AuthRepository
import com.google.android.material.button.MaterialButton
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.firebase.auth.FirebaseAuth
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/**
 * Plan C T8: Profile-bootstrap form.
 *
 * Shown after first sign-in when the user's account status is PENDING_PROFILE.
 * Collects displayName + date-of-birth, submits to AccountRepository which
 * calls POST /api/account/profile. On success, navigates to main shell. On
 * 422 AGE_INELIGIBLE, navigates to AgeIneligibleFragment (T9). On network
 * error, surfaces an inline error via TextInputLayout.
 *
 * Navigation: see app_nav_graph.xml — profileBootstrapFragment node (T8 stub,
 * finalised in T10).
 */
@AndroidEntryPoint
class ProfileBootstrapFragment : Fragment(R.layout.fragment_profile_bootstrap) {

    @Inject lateinit var firebaseAuth: FirebaseAuth
    @Inject lateinit var authRepository: AuthRepository
    private val viewModel: ProfileBootstrapViewModel by viewModels()

    private lateinit var displayNameLayout: TextInputLayout
    private lateinit var displayNameField: TextInputEditText
    private lateinit var dobLayout: TextInputLayout
    private lateinit var dobField: TextInputEditText
    private lateinit var submitButton: MaterialButton

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindViews(view)
        wireListeners()

        // D11: back-nav cancels sign-in. Sign out and route to signIn so the user
        // isn't trapped in a splash → /me → PENDING_PROFILE → bootstrap → back loop.
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    viewLifecycleOwner.lifecycleScope.launch {
                        authRepository.signOut()
                        findNavController().navigate(R.id.action_bootstrap_to_signIn)
                    }
                }
            }
        )

        viewModel.seedDisplayName(firebaseAuth.currentUser?.displayName.orEmpty())

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.ui.collect(::render)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.nav.collect { nav ->
                    when (nav) {
                        BootstrapNav.Idle -> Unit
                        BootstrapNav.NavigateToMain -> {
                            findNavController().navigate(R.id.action_bootstrap_to_main)
                            viewModel.consumeNav()
                        }
                        BootstrapNav.NavigateToAgeIneligible -> {
                            findNavController().navigate(R.id.action_bootstrap_to_ageIneligible)
                            viewModel.consumeNav()
                        }
                    }
                }
            }
        }
    }

    private fun bindViews(v: View) {
        displayNameLayout = v.findViewById(R.id.displayNameLayout)
        displayNameField = v.findViewById(R.id.displayNameField)
        dobLayout = v.findViewById(R.id.dobLayout)
        dobField = v.findViewById(R.id.dobField)
        submitButton = v.findViewById(R.id.submitButton)
    }

    private fun wireListeners() {
        displayNameField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                viewModel.onDisplayNameChanged(s?.toString().orEmpty())
            }
        })
        dobField.setOnClickListener { openDatePicker() }
        dobField.isFocusable = false
        submitButton.setOnClickListener { viewModel.submit() }
    }

    private fun openDatePicker() {
        // Cubic R7 P1 — constrain DOB to past dates within a plausible
        // human-lifetime window.
        //
        // Pre-fix MaterialDatePicker.Builder.datePicker() accepted any date
        // including the future. A user picking DOB="2099-01-01" hit a server
        // 422 from validateDateOfBirth, but ate a round-trip the picker
        // could have refused locally. CalendarConstraints.before(today)
        // disables future days in the UI; the 120-year lower bound rejects
        // obviously-bogus 1800s dates that no live user could legitimately
        // claim.
        val nowUtcMs = System.currentTimeMillis()
        val constraints = com.google.android.material.datepicker.CalendarConstraints.Builder()
            .setEnd(nowUtcMs)
            .setStart(nowUtcMs - 120L * 365 * 24 * 60 * 60 * 1000)
            .setValidator(com.google.android.material.datepicker.DateValidatorPointBackward.before(nowUtcMs))
            .build()
        val picker = MaterialDatePicker.Builder.datePicker()
            .setTitleText(getString(R.string.bootstrap_dob_label))
            .setCalendarConstraints(constraints)
            .build()
        picker.addOnPositiveButtonClickListener { utcMillis ->
            val date = Instant.ofEpochMilli(utcMillis).atOffset(ZoneOffset.UTC).toLocalDate()
            viewModel.onDobChanged(date)
        }
        picker.show(parentFragmentManager, "dob-picker")
    }

    private fun render(state: ProfileBootstrapViewModel.UiState) {
        if (displayNameField.text?.toString() != state.displayName) {
            displayNameField.setText(state.displayName)
            displayNameField.setSelection(state.displayName.length)
        }
        dobField.setText(state.dateOfBirth?.format(DateTimeFormatter.ISO_LOCAL_DATE).orEmpty())
        submitButton.isEnabled = !state.isLoading
        displayNameLayout.error = state.error?.takeIf { it == BootstrapError.INVALID_NAME }
            ?.let { getString(R.string.bootstrap_error_invalid_name) }
        dobLayout.error = state.error?.takeIf { it == BootstrapError.INVALID_DOB }
            ?.let { getString(R.string.bootstrap_error_invalid_dob) }
        // SAVE_FAILED: shown by dobLayout clearing both field errors so the user
        // understands the problem isn't their input. A future pass can add a Snackbar.
        if (state.error == BootstrapError.SAVE_FAILED) {
            displayNameLayout.error = null
            dobLayout.error = getString(R.string.bootstrap_error_save_failed)
        }
    }

    companion object { private const val TAG = "ProfileBootstrapFragment" }
}
