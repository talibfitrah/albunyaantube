package com.albunyaan.tube.player

import android.view.View
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class PredictivePrefetchControllerTest {

    private val context = RuntimeEnvironment.getApplication()
    private lateinit var mockPrefetchService: StreamPrefetchService
    private lateinit var testScope: TestScope

    @Before
    fun setUp() {
        mockPrefetchService = mock()
        testScope = TestScope()
    }

    // Uses a stub positionExtractor that always returns `stubPosition`.
    private fun makeController(
        idResolver: (Int) -> String?,
        stubPosition: Int = 0
    ) = PredictivePrefetchController(
        mockPrefetchService,
        testScope,
        idResolver,
        positionExtractor = { _, _ -> stubPosition }
    )

    @Test
    fun `triggerPrefetch is called when child attaches and has valid videoId`() {
        val rv = RecyclerView(context)
        val controller = makeController(idResolver = { pos -> if (pos == 0) "vid-123" else null })
        controller.attach(rv)

        val listeners = getAttachListeners(rv)
        assertEquals(1, listeners.size)

        listeners[0].onChildViewAttachedToWindow(View(context))

        verify(mockPrefetchService, times(1)).triggerPrefetch(any(), any())
    }

    @Test
    fun `triggerPrefetch is NOT called when videoId resolver returns null`() {
        val rv = RecyclerView(context)
        val controller = makeController(idResolver = { null })
        controller.attach(rv)

        val listeners = getAttachListeners(rv)
        listeners[0].onChildViewAttachedToWindow(View(context))

        verify(mockPrefetchService, never()).triggerPrefetch(any(), any())
    }

    @Test
    fun `triggerPrefetch is NOT called when position is NO_POSITION`() {
        val rv = RecyclerView(context)
        val controller = makeController(
            idResolver = { "vid-123" },
            stubPosition = RecyclerView.NO_POSITION
        )
        controller.attach(rv)

        val listeners = getAttachListeners(rv)
        listeners[0].onChildViewAttachedToWindow(View(context))

        verify(mockPrefetchService, never()).triggerPrefetch(any(), any())
    }

    @Test
    fun `detach removes the listener`() {
        val rv = RecyclerView(context)
        val controller = makeController(idResolver = { "vid-123" })
        controller.attach(rv)
        controller.detach()

        assertEquals(0, getAttachListeners(rv).size)
    }

    @Suppress("UNCHECKED_CAST")
    private fun getAttachListeners(rv: RecyclerView): List<RecyclerView.OnChildAttachStateChangeListener> {
        val field = RecyclerView::class.java.getDeclaredField("mOnChildAttachStateListeners")
        field.isAccessible = true
        return (field.get(rv) as? List<*>)
            ?.filterIsInstance<RecyclerView.OnChildAttachStateChangeListener>()
            ?: emptyList()
    }
}
