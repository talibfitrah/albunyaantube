package com.albunyaan.tube.ui.download

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.albunyaan.tube.download.DownloadEntry
import com.albunyaan.tube.download.DownloadRepository
import java.io.File
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class DownloadViewModel(
    private val repository: DownloadRepository
) : ViewModel() {

    val downloads: StateFlow<List<DownloadEntry>> = repository.downloads
        .map { entries -> entries.sortedBy { it.request.title } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun pause(id: String) = repository.pause(id)

    fun resume(id: String) = repository.resume(id)

    fun cancel(id: String) = repository.cancel(id)

    fun fileFor(entry: DownloadEntry): File? = entry.filePath?.let { File(it) }

    class Factory(
        private val repository: DownloadRepository
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(DownloadViewModel::class.java)) {
                @Suppress("UNCHECKED_CAST")
                return DownloadViewModel(repository) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}
