package com.albunyaan.tube.util

import androidx.test.espresso.IdlingResource
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * An Espresso IdlingResource that monitors a StateFlow and becomes idle
 * when the state matches a given predicate.
 *
 * Usage:
 * ```
 * val idlingResource = ViewStateIdlingResource(
 *     name = "HeaderLoaded",
 *     stateFlow = viewModel.headerState,
 *     isIdleWhen = { it !is HeaderState.Loading }
 * )
 * IdlingRegistry.getInstance().register(idlingResource)
 * // ... perform assertions ...
 * IdlingRegistry.getInstance().unregister(idlingResource)
 * ```
 *
 * @param name A unique name for this idling resource
 * @param stateFlow The StateFlow to monitor
 * @param isIdleWhen Predicate that returns true when the resource should be considered idle
 */
class ViewStateIdlingResource<T>(
    private val name: String,
    private val stateFlow: StateFlow<T>,
    private val isIdleWhen: (T) -> Boolean
) : IdlingResource {

    @Volatile
    private var callback: IdlingResource.ResourceCallback? = null
    private val isIdle = AtomicBoolean(false)

    override fun getName(): String = name

    override fun isIdleNow(): Boolean {
        val idle = isIdleWhen(stateFlow.value)
        if (idle) {
            if (isIdle.compareAndSet(false, true)) {
                callback?.onTransitionToIdle()
            }
        } else {
            // Reset idle state so we can detect future idle transitions
            isIdle.set(false)
        }
        return idle
    }

    override fun registerIdleTransitionCallback(callback: IdlingResource.ResourceCallback?) {
        this.callback = callback
    }
}

/**
 * A simple countdown idling resource that waits for a specified number of conditions
 * to be met before becoming idle.
 *
 * Thread-safe: uses AtomicInteger for count operations.
 *
 * @param name A unique name for this idling resource
 * @param count The initial count (resource is idle when count reaches 0)
 */
class CountingIdlingResource(
    private val name: String,
    count: Int = 1
) : IdlingResource {

    private val atomicCount = AtomicInteger(count)
    private val isIdle = AtomicBoolean(count <= 0)

    @Volatile
    private var callback: IdlingResource.ResourceCallback? = null

    override fun getName(): String = name

    override fun isIdleNow(): Boolean = atomicCount.get() <= 0

    override fun registerIdleTransitionCallback(callback: IdlingResource.ResourceCallback?) {
        this.callback = callback
    }

    /**
     * Decrement the count. When count reaches 0, the resource becomes idle.
     */
    fun decrement() {
        if (atomicCount.decrementAndGet() <= 0) {
            if (isIdle.compareAndSet(false, true)) {
                callback?.onTransitionToIdle()
            }
        }
    }

    /**
     * Increment the count (resource becomes busy again).
     */
    fun increment() {
        atomicCount.incrementAndGet()
        isIdle.set(false)
    }
}
