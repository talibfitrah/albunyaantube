package com.albunyaan.tube.ui.me.profile

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.albunyaan.tube.R
import com.albunyaan.tube.databinding.FragmentProfileBinding
import com.google.android.material.datepicker.CalendarConstraints
import com.google.android.material.datepicker.DateValidatorPointBackward
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@AndroidEntryPoint
class ProfileFragment : Fragment(R.layout.fragment_profile) {

    private var _binding: FragmentProfileBinding? = null
    private val binding get() = _binding!!

    private val vm: ProfileViewModel by viewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentProfileBinding.bind(view)

        binding.displayNameInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                vm.onDisplayNameChange(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.dobRow.setOnClickListener { showDobPicker() }
        binding.saveButton.setOnClickListener { vm.save() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.uiState.collect { render(it) }
            }
        }
    }

    private fun render(state: ProfileUiState) {
        when (state) {
            ProfileUiState.Loading -> {
                binding.saveButton.isEnabled = false
                binding.savingSpinner.visibility = View.GONE
            }
            is ProfileUiState.Editing -> {
                // Only push text into the field when it actually differs — avoids cursor-jump
                // while the user is typing (TextWatcher fires → VM updates → collect fires).
                val current = binding.displayNameInput.text?.toString()
                if (current != state.draft.displayName) {
                    binding.displayNameInput.setText(state.draft.displayName)
                    binding.displayNameInput.setSelection(state.draft.displayName.length)
                }
                binding.dobValue.text =
                    state.draft.dateOfBirth ?: getString(R.string.profile_dob_pick)
                binding.emailLabel.text = state.draft.emailReadOnly
                binding.saveButton.isEnabled = state.isDirty && !state.saving
                binding.savingSpinner.visibility =
                    if (state.saving) View.VISIBLE else View.GONE
                // Plan G review-fix (reviewer Important #4): clear any
                // lingering inline validation error when the VM clears it
                // (e.g. user typed after a failed Save). showError() sets
                // displayNameLayout.error on Validation errors but never
                // resets it when error becomes null on the next state, so
                // the red label persists until the fragment is recreated.
                if (state.error == null) {
                    binding.displayNameLayout.error = null
                }
                state.error?.let { showError(it) }
            }
            ProfileUiState.SignedOut -> {
                // AccountStatusInterceptor will redirect to sign-in on the next network call.
                // Pop back to wherever the nav graph landed before profileFragment.
                findNavController().popBackStack()
            }
        }
    }

    private fun showError(error: ProfileError) {
        when (error) {
            ProfileError.Network ->
                snack(R.string.profile_error_network)
            is ProfileError.RateLimited -> {
                val minutes = (error.retryAfterSec / 60).coerceAtLeast(1)
                Snackbar.make(
                    binding.root,
                    getString(R.string.profile_error_rate_limited, minutes.toInt()),
                    Snackbar.LENGTH_LONG,
                ).show()
            }
            ProfileError.AgeIneligible -> showAgeDialog()
            is ProfileError.Validation ->
                binding.displayNameLayout.error = error.message
            ProfileError.Unknown ->
                snack(R.string.profile_error_network)
        }
    }

    private fun showAgeDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.profile_error_age_dialog_title)
            .setMessage(R.string.profile_error_age_dialog_message)
            .setCancelable(false)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun snack(resId: Int) =
        Snackbar.make(binding.root, resId, Snackbar.LENGTH_SHORT).show()

    private fun showDobPicker() {
        // Plan G review-fix (codex P1): cap picker at today so a future date
        // never reaches the backend. The age-gate treats negative ages as
        // under-13 and would soft-delete a legitimate account that
        // fat-fingered the picker. Backend also validates as defence-in-depth.
        val todayMs = MaterialDatePicker.todayInUtcMilliseconds()
        val constraints = CalendarConstraints.Builder()
            .setEnd(todayMs)
            .setValidator(DateValidatorPointBackward.before(todayMs + 1L))
            .build()
        val picker = MaterialDatePicker.Builder.datePicker()
            .setTitleText(R.string.profile_date_of_birth)
            .setCalendarConstraints(constraints)
            .setSelection(todayMs)
            .build()
        picker.addOnPositiveButtonClickListener { selectionMs ->
            val local =
                LocalDate.ofInstant(Instant.ofEpochMilli(selectionMs), ZoneOffset.UTC)
            vm.onDateOfBirthChange(local.toString()) // "YYYY-MM-DD"
        }
        picker.show(parentFragmentManager, "dob_picker")
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
