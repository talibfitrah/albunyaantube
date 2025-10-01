package com.albunyaan.tube.download

import androidx.lifecycle.asFlow
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.albunyaan.tube.download.DownloadScheduler.Companion.KEY_DOWNLOAD_ID
import com.albunyaan.tube.download.DownloadScheduler.Companion.KEY_PROGRESS
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

interface DownloadRepository {
    val downloads: StateFlow<List<DownloadEntry>>

    fun enqueue(request: DownloadRequest)
    fun pause(requestId: String)
    fun resume(requestId: String)
    fun cancel(requestId: String)
}

class DefaultDownloadRepository(
    private val workManager: WorkManager,
    private val scheduler: DownloadScheduler,
    private val scope: CoroutineScope
) : DownloadRepository {

    private val entries = MutableStateFlow<List<DownloadEntry>>(emptyList())
    private val workIds = ConcurrentHashMap<String, UUID>()
    private val paused = ConcurrentHashMap.newKeySet<String>()

    override val downloads: StateFlow<List<DownloadEntry>> = entries.asStateFlow()

    override fun enqueue(request: DownloadRequest) {
        val workId = scheduler.schedule(request)
        workIds[request.id] = workId
        updateEntry(request) { it.copy(status = DownloadStatus.QUEUED, progress = 0, message = null) }
        observeWork(workId, request)
    }

    override fun pause(requestId: String) {
        val workId = workIds[requestId] ?: return
        paused += requestId
        workManager.cancelWorkById(workId)
        updateEntry(requestId) { it.copy(status = DownloadStatus.PAUSED) }
    }

    override fun resume(requestId: String) {
        val entry = entries.value.firstOrNull { it.request.id == requestId } ?: return
        paused -= requestId
        enqueue(entry.request)
    }

    override fun cancel(requestId: String) {
        val workId = workIds[requestId]
        if (workId != null) {
            workManager.cancelWorkById(workId)
            workIds.remove(requestId)
        }
        paused -= requestId
        updateEntry(requestId) { it.copy(status = DownloadStatus.CANCELLED, progress = 0) }
    }

    private fun observeWork(workId: UUID, request: DownloadRequest) {
        scope.launch {
            workManager.workInfoFlow(workId)
                .map { info -> info to info.progress.getInt(KEY_PROGRESS, 0) }
                .collectLatest { (info, progress) ->
                    when (info.state) {
                        WorkInfo.State.ENQUEUED -> updateEntry(request) {
                            it.copy(status = DownloadStatus.QUEUED, progress = progress)
                        }
                        WorkInfo.State.RUNNING -> updateEntry(request) {
                            it.copy(status = DownloadStatus.RUNNING, progress = progress)
                        }
                        WorkInfo.State.SUCCEEDED -> {
                            workIds.remove(request.id)
                            updateEntry(request) {
                                it.copy(status = DownloadStatus.COMPLETED, progress = 100)
                            }
                        }
                        WorkInfo.State.FAILED -> {
                            workIds.remove(request.id)
                            updateEntry(request) {
                                it.copy(status = DownloadStatus.FAILED, message = null)
                            }
                        }
                        WorkInfo.State.CANCELLED -> {
                            workIds.remove(request.id)
                            if (paused.contains(request.id)) {
                                updateEntry(request) { it.copy(status = DownloadStatus.PAUSED) }
                            } else {
                                updateEntry(request) { it.copy(status = DownloadStatus.CANCELLED) }
                            }
                        }
                        WorkInfo.State.BLOCKED -> {
                            updateEntry(request) { it.copy(status = DownloadStatus.QUEUED) }
                        }
                    }
                }
        }
    }

    private fun updateEntry(requestId: String, transform: (DownloadEntry) -> DownloadEntry) {
        val current = entries.value.toMutableList()
        val index = current.indexOfFirst { it.request.id == requestId }
        if (index >= 0) {
            current[index] = transform(current[index])
        }
        entries.value = current
    }

    private fun updateEntry(request: DownloadRequest, transform: (DownloadEntry) -> DownloadEntry) {
        val current = entries.value.toMutableList()
        val index = current.indexOfFirst { it.request.id == request.id }
        if (index >= 0) {
            current[index] = transform(current[index])
        } else {
            current += transform(DownloadEntry(request, DownloadStatus.QUEUED))
        }
        entries.value = current
    }

    private operator fun MutableCollection<String>.plusAssign(value: String) {
        add(value)
    }

    private operator fun MutableCollection<String>.minusAssign(value: String) {
        remove(value)
    }
}

private fun WorkManager.workInfoFlow(id: UUID) =
    getWorkInfoByIdLiveData(id).asFlow().filterNotNull()
