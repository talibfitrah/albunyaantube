package com.albunyaan.tube.ui.me.submissions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.albunyaan.tube.data.approvals.MySubmissionsRepository
import com.albunyaan.tube.data.approvals.RateLimitError
import com.albunyaan.tube.data.model.Category
import com.albunyaan.tube.data.source.ContentService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Named

enum class DetectedContentType { CHANNEL, PLAYLIST, VIDEO }

data class ParsedYouTubeUrl(val type: DetectedContentType, val youtubeId: String)

sealed interface SubmitContentEvent {
    data object Success : SubmitContentEvent
    data class RateLimited(val retryAfterSeconds: Long) : SubmitContentEvent
    data object Conflict : SubmitContentEvent
    data class Error(val message: String) : SubmitContentEvent
}

@HiltViewModel
class SubmitContentViewModel @Inject constructor(
    private val repo: MySubmissionsRepository,
    @Named("real") private val contentService: ContentService,
) : ViewModel() {

    private val _categories = MutableStateFlow<List<Category>>(emptyList())
    val categories: StateFlow<List<Category>> = _categories

    private val _events = MutableStateFlow<SubmitContentEvent?>(null)
    val events: StateFlow<SubmitContentEvent?> = _events

    private val _submitting = MutableStateFlow(false)
    val submitting: StateFlow<Boolean> = _submitting

    // Cubic R7 P1 — hoist rotation-sensitive form state into the VM.
    //
    // Pre-fix SubmitContentBottomSheet held parsedUrl + selectedCategoryId
    // as fragment fields; both reset on onCreateView, so a configuration
    // change (rotation, dark-mode toggle, keyboard locale change) emptied
    // the form and the user had to re-paste the URL + re-pick the category.
    // ViewModel scope survives configuration changes; the fragment seeds
    // these on bind and reads them back on next bind.
    private val _parsedUrl = MutableStateFlow<ParsedYouTubeUrl?>(null)
    val parsedUrl: StateFlow<ParsedYouTubeUrl?> = _parsedUrl

    private val _selectedCategoryId = MutableStateFlow<String?>(null)
    val selectedCategoryId: StateFlow<String?> = _selectedCategoryId

    /** Cubic R7 P1 — fragment writes through these on user input. */
    fun setParsedUrl(p: ParsedYouTubeUrl?) { _parsedUrl.value = p }
    fun setSelectedCategoryId(id: String?) { _selectedCategoryId.value = id }

    init {
        viewModelScope.launch {
            runCatching { contentService.fetchCategories() }
                .onSuccess { _categories.value = it }
                .onFailure { _categories.value = emptyList() }
        }
    }

    fun submit(
        parsed: ParsedYouTubeUrl,
        categoryId: String,
        prefetchedName: String? = null,
        prefetchedThumbnailUrl: String? = null,
        submitterNote: String? = null,
    ) {
        if (_submitting.value) return
        _submitting.value = true
        val trimmedNote = submitterNote?.trim()?.takeIf { it.isNotEmpty() }
        viewModelScope.launch {
            val result = when (parsed.type) {
                DetectedContentType.CHANNEL -> repo.submitChannel(
                    parsed.youtubeId, listOf(categoryId), prefetchedName, prefetchedThumbnailUrl, trimmedNote,
                )
                DetectedContentType.PLAYLIST -> repo.submitPlaylist(
                    parsed.youtubeId, listOf(categoryId), prefetchedName, prefetchedThumbnailUrl, trimmedNote,
                )
                DetectedContentType.VIDEO -> repo.submitVideo(
                    parsed.youtubeId, listOf(categoryId), prefetchedName, prefetchedThumbnailUrl, trimmedNote,
                )
            }
            _submitting.value = false
            result.fold(
                onSuccess = { _events.value = SubmitContentEvent.Success },
                onFailure = { e ->
                    _events.value = when (e) {
                        is RateLimitError -> SubmitContentEvent.RateLimited(e.retryAfterSeconds)
                        else -> if (e.message?.contains("Already exists") == true) SubmitContentEvent.Conflict
                                else SubmitContentEvent.Error(e.message ?: "submit failed")
                    }
                }
            )
        }
    }

    fun consumeEvent() { _events.value = null }
}
