package com.albunyaan.tube.ui.download

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.albunyaan.tube.download.DownloadEntry
import com.albunyaan.tube.download.DownloadRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * P3-T3: DownloadViewModel with Hilt DI
 */
@HiltViewModel
class DownloadViewModel @Inject constructor(
    private val repository: DownloadRepository
) : ViewModel() {

    val downloads: StateFlow<List<DownloadEntry>> = repository.downloads
        .map { entries -> entries.sortedBy { it.request.title } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun pause(id: String) = repository.pause(id)

    fun resume(id: String) = repository.resume(id)

    fun cancel(id: String) = repository.cancel(id)

    fun fileFor(entry: DownloadEntry): File? = entry.filePath?.let { File(it) }
}
