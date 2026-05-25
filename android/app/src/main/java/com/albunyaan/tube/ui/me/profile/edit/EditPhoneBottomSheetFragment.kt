package com.albunyaan.tube.ui.me.profile.edit

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.albunyaan.tube.R
import com.albunyaan.tube.util.PhoneFormat
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class EditPhoneBottomSheetFragment : BottomSheetDialogFragment() {

    private val viewModel: EditPhoneViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.bottom_sheet_edit_phone, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val countryLayout: TextInputLayout = view.findViewById(R.id.countryLayout)
        val countryField: AutoCompleteTextView = view.findViewById(R.id.countryField)
        val numberLayout: TextInputLayout = view.findViewById(R.id.numberLayout)
        val numberField: TextInputEditText = view.findViewById(R.id.numberField)
        val saveButton: MaterialButton = view.findViewById(R.id.saveButton)
        val errorText: TextView = view.findViewById(R.id.errorText)
        val spinner: ProgressBar = view.findViewById(R.id.savingSpinner)

        val seedCountry = arguments?.getString(ARG_COUNTRY)
        val seedNumber  = arguments?.getString(ARG_NUMBER)
        viewModel.seed(seedCountry, seedNumber)

        val rows = PhoneFormat.countryRows(requireContext())
        val adapter = ArrayAdapter(
            requireContext(), android.R.layout.simple_list_item_1,
            rows.map { it.second },
        )
        countryField.setAdapter(adapter)
        countryField.setOnItemClickListener { _, _, position, _ ->
            viewModel.onCountryChanged(rows[position].first)
        }
        seedCountry?.let { iso ->
            val display = rows.firstOrNull { it.first == iso }?.second
            if (display != null) countryField.setText(display, /* filter */ false)
        }
        seedNumber?.let { numberField.setText(it) }

        numberField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                viewModel.onNumberChanged(s?.toString().orEmpty())
            }
        })

        saveButton.setOnClickListener { viewModel.submit() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.ui.collect { s ->
                    saveButton.isEnabled = !s.saving
                    spinner.visibility = if (s.saving) View.VISIBLE else View.GONE
                    val msgRes = when (s.error) {
                        EditPhoneError.INVALID_COUNTRY -> R.string.bootstrap_error_invalid_phone_country
                        EditPhoneError.INVALID_PHONE   -> R.string.bootstrap_error_invalid_phone
                        EditPhoneError.NETWORK         -> R.string.profile_error_network
                        EditPhoneError.RATE_LIMITED    -> R.string.profile_error_rate_limited_short
                        EditPhoneError.UNKNOWN, null   -> null
                    }
                    errorText.visibility = if (msgRes != null) View.VISIBLE else View.GONE
                    msgRes?.let { errorText.setText(it) }
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.nav.collect { nav ->
                    if (nav == EditPhoneViewModel.Nav.Done) {
                        Snackbar.make(
                            requireActivity().findViewById(android.R.id.content),
                            R.string.edit_phone_updated,
                            Snackbar.LENGTH_SHORT,
                        ).show()
                        dismiss()
                    }
                }
            }
        }
    }

    companion object {
        const val TAG = "EditPhoneBottomSheet"
        private const val ARG_COUNTRY = "country"
        private const val ARG_NUMBER  = "number"

        fun newInstance(country: String?, number: String?): EditPhoneBottomSheetFragment =
            EditPhoneBottomSheetFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_COUNTRY, country)
                    putString(ARG_NUMBER, number)
                }
            }
    }
}
