package com.albunyaan.tube.player

import android.util.Log
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope

class PredictivePrefetchController(
    private val prefetchService: StreamPrefetchService,
    private val scope: CoroutineScope,
    private val videoIdResolver: (adapterPosition: Int) -> String?,
    private val positionExtractor: (RecyclerView, View) -> Int = { rv, view ->
        rv.getChildAdapterPosition(view)
    }
) {
    companion object {
        private const val TAG = "PredictivePrefetch"
    }

    private var recyclerView: RecyclerView? = null

    private val listener = object : RecyclerView.OnChildAttachStateChangeListener {
        override fun onChildViewAttachedToWindow(view: View) {
            val rv = recyclerView ?: return
            val position = positionExtractor(rv, view)
            if (position == RecyclerView.NO_POSITION) return
            val videoId = videoIdResolver(position) ?: return
            Log.d(TAG, "Predictive prefetch for pos=$position videoId=$videoId")
            prefetchService.triggerPrefetch(videoId, scope)
        }

        override fun onChildViewDetachedFromWindow(view: View) = Unit
    }

    fun attach(recyclerView: RecyclerView) {
        this.recyclerView = recyclerView
        recyclerView.addOnChildAttachStateChangeListener(listener)
    }

    fun detach() {
        recyclerView?.removeOnChildAttachStateChangeListener(listener)
        recyclerView = null
    }
}
