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
class EditPasswordBottomSheetFragment : BottomSheetDialogFragment() {

    private val viewModel: EditPasswordViewModel by viewModels()

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View =
        i.inflate(R.layout.bottom_sheet_edit_password, c, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val currentLayout: TextInputLayout  = view.findViewById(R.id.currentLayout)
        val currentField: TextInputEditText = view.findViewById(R.id.currentField)
        val newLayout: TextInputLayout      = view.findViewById(R.id.newLayout)
        val newField: TextInputEditText     = view.findViewById(R.id.newField)
        val confirmLayout: TextInputLayout  = view.findViewById(R.id.confirmLayout)
        val confirmField: TextInputEditText = view.findViewById(R.id.confirmField)
        val updateButton: MaterialButton    = view.findViewById(R.id.updateButton)
        val errorText: TextView             = view.findViewById(R.id.errorText)
        val spinner: ProgressBar            = view.findViewById(R.id.savingSpinner)

        currentField.addTextChangedListener(simple { viewModel.onCurrentChanged(it) })
        newField.addTextChangedListener(simple { viewModel.onNewChanged(it) })
        confirmField.addTextChangedListener(simple { viewModel.onConfirmChanged(it) })
        updateButton.setOnClickListener { viewModel.submit() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.ui.collect { s ->
                    updateButton.isEnabled = !s.saving
                    spinner.visibility = if (s.saving) View.VISIBLE else View.GONE
                    currentLayout.error = if (s.error == EditPasswordError.WRONG_PASSWORD)
                        getString(R.string.edit_password_wrong_current) else null
                    newLayout.error = if (s.error == EditPasswordError.WEAK_PASSWORD)
                        getString(R.string.edit_password_weak) else null
                    confirmLayout.error = if (s.error == EditPasswordError.PASSWORD_MISMATCH)
                        getString(R.string.edit_password_mismatch) else null
                    if (s.error == EditPasswordError.NETWORK || s.error == EditPasswordError.UNKNOWN) {
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
                    if (it == EditPasswordViewModel.Nav.Done) {
                        Snackbar.make(
                            requireActivity().findViewById(android.R.id.content),
                            R.string.edit_password_updated,
                            Snackbar.LENGTH_LONG,
                        ).show()
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

    companion object { const val TAG = "EditPasswordBottomSheet" }
}
