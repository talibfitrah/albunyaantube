package com.albunyaan.tube.ui.me.profile.edit

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.albunyaan.tube.R
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class EditEmailBottomSheetFragment : BottomSheetDialogFragment() {

    private val viewModel: EditEmailViewModel by viewModels()

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View =
        i.inflate(R.layout.bottom_sheet_edit_email, c, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val pwLayout: TextInputLayout     = view.findViewById(R.id.currentPasswordLayout)
        val pwField: TextInputEditText    = view.findViewById(R.id.currentPasswordField)
        val emailLayout: TextInputLayout  = view.findViewById(R.id.newEmailLayout)
        val emailField: TextInputEditText = view.findViewById(R.id.newEmailField)
        val sendButton: MaterialButton    = view.findViewById(R.id.sendButton)
        val errorText: TextView           = view.findViewById(R.id.errorText)
        val spinner: ProgressBar          = view.findViewById(R.id.savingSpinner)

        pwField.addTextChangedListener(simple { viewModel.onCurrentPasswordChanged(it) })
        emailField.addTextChangedListener(simple { viewModel.onNewEmailChanged(it) })
        sendButton.setOnClickListener { viewModel.submit() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.ui.collect { s ->
                    sendButton.isEnabled = !s.saving
                    spinner.visibility = if (s.saving) View.VISIBLE else View.GONE
                    pwLayout.error    = if (s.error == EditEmailError.WRONG_PASSWORD)
                        getString(R.string.edit_email_wrong_password) else null
                    emailLayout.error = when (s.error) {
                        EditEmailError.INVALID_EMAIL -> getString(R.string.edit_email_invalid)
                        EditEmailError.EMAIL_IN_USE  -> getString(R.string.edit_email_in_use)
                        else -> null
                    }
                    if (s.error == EditEmailError.NETWORK || s.error == EditEmailError.UNKNOWN) {
                        errorText.visibility = View.VISIBLE
                        errorText.setText(R.string.profile_error_network)
                    } else {
                        errorText.visibility = View.GONE
                    }
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.nav.collect {
                    if (it == EditEmailViewModel.Nav.Done) {
                        Snackbar.make(
                            requireActivity().findViewById(android.R.id.content),
                            getString(R.string.edit_email_sent, viewModel.ui.value.newEmail),
                            Snackbar.LENGTH_LONG,
                        ).show()
                        viewModel.consumeNav()
                        dismiss()
                    }
                }
            }
        }
    }

    private fun simple(onChange: (String) -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
        override fun afterTextChanged(s: Editable?) { onChange(s?.toString().orEmpty()) }
    }

    companion object { const val TAG = "EditEmailBottomSheet" }
}
