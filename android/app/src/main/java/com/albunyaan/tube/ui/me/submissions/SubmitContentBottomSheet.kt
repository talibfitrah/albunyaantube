package com.albunyaan.tube.ui.me.submissions

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.albunyaan.tube.R
import com.albunyaan.tube.data.me.YouTubeVideoIdRegex
import com.albunyaan.tube.databinding.BottomSheetSubmitContentBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.util.regex.Pattern

@AndroidEntryPoint
class SubmitContentBottomSheet : BottomSheetDialogFragment() {

    private val viewModel: SubmitContentViewModel by viewModels()
    private var _binding: BottomSheetSubmitContentBinding? = null
    private val binding get() = _binding!!

    // Cubic R7 P1 — read-through helpers to the ViewModel's hoisted state.
    // The previous local fields reset on rotation and lost the user's
    // pasted URL + category pick; the ViewModel-backed state survives.
    private var parsedUrl: ParsedYouTubeUrl?
        get() = viewModel.parsedUrl.value
        set(value) { viewModel.setParsedUrl(value) }
    private var selectedCategoryId: String?
        get() = viewModel.selectedCategoryId.value
        set(value) { viewModel.setSelectedCategoryId(value) }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = BottomSheetSubmitContentBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // URL field — parse on every keystroke
        binding.urlInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val raw = s?.toString().orEmpty()
                parsedUrl = parseYouTubeUrl(raw)
                if (raw.isEmpty()) {
                    binding.detectedType.visibility = View.GONE
                } else {
                    binding.detectedType.visibility = View.VISIBLE
                    binding.detectedType.setText(
                        when (parsedUrl?.type) {
                            DetectedContentType.CHANNEL -> R.string.submit_content_detected_channel
                            DetectedContentType.PLAYLIST -> R.string.submit_content_detected_playlist
                            DetectedContentType.VIDEO -> R.string.submit_content_detected_video
                            null -> R.string.submit_content_invalid_url
                        }
                    )
                }
                updateSubmitEnabled()
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        // Category dropdown
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.categories.collect { cats ->
                    val labels = cats.map { it.name }
                    val adapter = ArrayAdapter(
                        requireContext(),
                        android.R.layout.simple_list_item_1,
                        labels,
                    )
                    binding.categoryDropdown.setAdapter(adapter)
                    binding.categoryDropdown.setOnItemClickListener { _, _, pos, _ ->
                        selectedCategoryId = cats.getOrNull(pos)?.id
                        updateSubmitEnabled()
                    }
                }
            }
        }

        // Busy state → button enable + progress bar
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.submitting.collect { busy ->
                    binding.submitButton.isEnabled =
                        !busy && parsedUrl != null && selectedCategoryId != null
                    binding.submitProgress.visibility =
                        if (busy) View.VISIBLE else View.GONE
                }
            }
        }

        // One-shot events
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.events.collect { event ->
                    if (event == null) return@collect
                    val anchor = requireView()
                    when (event) {
                        SubmitContentEvent.Success -> {
                            Snackbar.make(anchor, R.string.submit_content_success, Snackbar.LENGTH_SHORT).show()
                            dismiss()
                        }
                        is SubmitContentEvent.RateLimited -> {
                            val hours = (event.retryAfterSeconds + 3599) / 3600
                            Snackbar.make(
                                anchor,
                                getString(R.string.submit_content_rate_limited, "${hours}h"),
                                Snackbar.LENGTH_LONG,
                            ).show()
                        }
                        SubmitContentEvent.Conflict -> {
                            Snackbar.make(anchor, R.string.submit_content_conflict, Snackbar.LENGTH_LONG).show()
                        }
                        is SubmitContentEvent.Error -> {
                            Snackbar.make(anchor, event.message, Snackbar.LENGTH_LONG).show()
                        }
                    }
                    viewModel.consumeEvent()
                }
            }
        }

        binding.submitButton.setOnClickListener {
            val parsed = parsedUrl ?: return@setOnClickListener
            val catId = selectedCategoryId ?: return@setOnClickListener
            // When launched from SuggestContentFragment we already have the YouTube
            // title + thumbnail from the search result — pass them through so the
            // record is enriched at write-time and the moderator queue / My
            // Submissions screen can render real metadata immediately.
            viewModel.submit(
                parsed = parsed,
                categoryId = catId,
                prefetchedName = arguments?.getString(ARG_PREFILL_NAME),
                prefetchedThumbnailUrl = arguments?.getString(ARG_PREFILL_THUMB),
                submitterNote = binding.submitterNoteInput.text?.toString(),
            )
        }

        // Prefill URL if launched from SuggestContentFragment
        arguments?.getString(ARG_PREFILL_URL)?.let { prefill ->
            binding.urlInput.setText(prefill)
        }
    }

    private fun updateSubmitEnabled() {
        binding.submitButton.isEnabled =
            parsedUrl != null && selectedCategoryId != null && !viewModel.submitting.value
    }

    /**
     * Recognises the most common YouTube URL shapes.
     * @handle / /c/ form is deferred — only UCxxx channel IDs are supported for now.
     */
    private fun parseYouTubeUrl(input: String): ParsedYouTubeUrl? {
        val url = input.trim()
        if (url.isEmpty()) return null

        // Video: youtu.be/<id>, ?v=<id>, /shorts/<id>, /embed/<id>, /watch/<id>
        val video = YouTubeVideoIdRegex.VIDEO_ID_REGEX.find(url)
        if (video != null) return ParsedYouTubeUrl(DetectedContentType.VIDEO, video.groupValues[1])

        // youtube.com/playlist?list=<id> → playlist
        val list = Pattern.compile("""[?&]list=([A-Za-z0-9_-]{10,})""").matcher(url)
        if (list.find()) return ParsedYouTubeUrl(DetectedContentType.PLAYLIST, list.group(1)!!)

        // youtube.com/channel/<UCxxx> → channel (handle / /c/ deferred)
        val ch = Pattern.compile("""youtube\.com/channel/(UC[A-Za-z0-9_-]{20,})""").matcher(url)
        if (ch.find()) return ParsedYouTubeUrl(DetectedContentType.CHANNEL, ch.group(1)!!)

        return null
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "SubmitContentBottomSheet"

        private const val ARG_PREFILL_URL = "prefill_url"
        private const val ARG_PREFILL_NAME = "prefill_name"
        private const val ARG_PREFILL_THUMB = "prefill_thumb"

        fun newInstance(
            prefillUrl: String? = null,
            prefillName: String? = null,
            prefillThumbnailUrl: String? = null,
        ): SubmitContentBottomSheet {
            return SubmitContentBottomSheet().apply {
                arguments = android.os.Bundle().apply {
                    if (prefillUrl != null) putString(ARG_PREFILL_URL, prefillUrl)
                    if (prefillName != null) putString(ARG_PREFILL_NAME, prefillName)
                    if (prefillThumbnailUrl != null) putString(ARG_PREFILL_THUMB, prefillThumbnailUrl)
                }
            }
        }
    }
}
