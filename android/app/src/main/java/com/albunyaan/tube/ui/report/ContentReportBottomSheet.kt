package com.albunyaan.tube.ui.report

import android.os.Bundle
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.albunyaan.tube.data.report.ReportContentSubType
import com.albunyaan.tube.data.report.ReportReason
import com.albunyaan.tube.data.report.ReportTargetType
import com.albunyaan.tube.databinding.BottomSheetContentReportBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class ContentReportBottomSheet : BottomSheetDialogFragment() {

    private var binding: BottomSheetContentReportBinding? = null

    private val viewModel: ReportViewModel by viewModels()

    private val targetType: ReportTargetType by lazy {
        ReportTargetType.valueOf(
            arguments?.getString(ARG_TARGET_TYPE) ?: ReportTargetType.VIDEO.name
        )
    }
    private val targetId: String by lazy {
        arguments?.getString(ARG_TARGET_ID).orEmpty()
    }
    private val parentType: ReportTargetType? by lazy {
        arguments?.getString(ARG_PARENT_TYPE)?.let {
            runCatching { ReportTargetType.valueOf(it) }.getOrNull()
        }
    }
    private val parentId: String? by lazy {
        arguments?.getString(ARG_PARENT_ID)?.takeIf { it.isNotBlank() }
    }
    private val contentSubType: ReportContentSubType? by lazy {
        arguments?.getString(ARG_CONTENT_SUBTYPE)?.let {
            runCatching { ReportContentSubType.valueOf(it) }.getOrNull()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        binding = BottomSheetContentReportBinding.inflate(inflater, container, false)
        return binding!!.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupOtherToggle()
        setupButtons()
        observeUiState()
    }

    private fun setupOtherToggle() {
        binding?.checkOther?.setOnCheckedChangeListener { _, isChecked ->
            val layout = binding?.otherInputLayout ?: return@setOnCheckedChangeListener
            TransitionManager.beginDelayedTransition(
                binding!!.root as ViewGroup,
                AutoTransition().apply { duration = 200 }
            )
            layout.isVisible = isChecked
            if (!isChecked) binding?.otherEditText?.text?.clear()
        }
    }

    private fun setupButtons() {
        binding?.cancelButton?.setOnClickListener { dismissAllowingStateLoss() }

        binding?.submitButton?.setOnClickListener {
            val reasons = collectCheckedReasons()
            val otherText = binding?.otherEditText?.text?.toString()?.takeIf { it.isNotBlank() }
            viewModel.submitReport(
                targetType = targetType,
                targetId = targetId,
                reasons = reasons,
                otherDescription = otherText,
                parentType = parentType,
                parentId = parentId,
                contentSubType = contentSubType,
            )
        }
    }

    private fun collectCheckedReasons(): List<ReportReason> {
        val b = binding ?: return emptyList()
        return buildList {
            if (b.checkMusic.isChecked) add(ReportReason.MUSIC)
            if (b.checkNudity.isChecked) add(ReportReason.NUDITY)
            if (b.checkBadLanguage.isChecked) add(ReportReason.BAD_LANGUAGE)
            if (b.checkFlirting.isChecked) add(ReportReason.FLIRTING)
            if (b.checkRomance.isChecked) add(ReportReason.ROMANCE)
            if (b.checkAwrah.isChecked) add(ReportReason.AWRAH)
            if (b.checkShirk.isChecked) add(ReportReason.SHIRK)
            if (b.checkBidah.isChecked) add(ReportReason.BIDAH)
            if (b.checkViolence.isChecked) add(ReportReason.VIOLENCE)
            if (b.checkMisinformation.isChecked) add(ReportReason.MISINFORMATION)
            if (b.checkOther.isChecked) add(ReportReason.OTHER)
        }
    }

    private fun observeUiState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    val b = binding ?: return@collect
                    when (state) {
                        is ReportUiState.Idle -> {
                            b.submitButton.isEnabled = true
                        }
                        is ReportUiState.Loading -> {
                            b.submitButton.isEnabled = false
                        }
                        is ReportUiState.Success -> {
                            showSnackbar(getString(com.albunyaan.tube.R.string.report_success))
                            dismissAllowingStateLoss()
                        }
                        is ReportUiState.RateLimited -> {
                            b.submitButton.isEnabled = true
                            showSnackbar(getString(com.albunyaan.tube.R.string.report_rate_limited))
                            dismissAllowingStateLoss()
                        }
                        is ReportUiState.Error -> {
                            b.submitButton.isEnabled = true
                            showSnackbar(state.message)
                        }
                    }
                }
            }
        }
    }

    private fun showSnackbar(message: String) {
        val anchor = view
            ?: activity?.window?.decorView?.findViewById<View>(android.R.id.content)
            ?: return
        Snackbar.make(anchor, message, Snackbar.LENGTH_LONG).show()
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    companion object {
        const val TAG = "ContentReportBottomSheet"
        private const val ARG_TARGET_TYPE = "targetType"
        private const val ARG_TARGET_ID = "targetId"
        private const val ARG_PARENT_TYPE = "parentType"
        private const val ARG_PARENT_ID = "parentId"
        private const val ARG_CONTENT_SUBTYPE = "contentSubType"

        fun newInstance(targetType: ReportTargetType, targetId: String): ContentReportBottomSheet =
            newInstance(targetType, targetId, null, null, null)

        /**
         * Construct a report sheet that carries parent context to the
         * backend. When [parentType] + [parentId] are set, resolving the
         * report on the admin side adds [targetId] to that parent's
         * exclusion list (rather than archiving the content app-wide).
         * [contentSubType] narrows VIDEO targets into SHORT / LIVESTREAM
         * / POST so the exclusion lands in the correct bucket on the
         * channel.
         */
        fun newInstance(
            targetType: ReportTargetType,
            targetId: String,
            parentType: ReportTargetType?,
            parentId: String?,
            contentSubType: ReportContentSubType?,
        ): ContentReportBottomSheet {
            return ContentReportBottomSheet().apply {
                arguments = bundleOf(
                    ARG_TARGET_TYPE to targetType.name,
                    ARG_TARGET_ID to targetId,
                    ARG_PARENT_TYPE to parentType?.name,
                    ARG_PARENT_ID to parentId,
                    ARG_CONTENT_SUBTYPE to contentSubType?.name,
                )
            }
        }
    }
}
