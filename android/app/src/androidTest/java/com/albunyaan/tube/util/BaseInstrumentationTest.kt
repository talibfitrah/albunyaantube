package com.albunyaan.tube.util

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Before
import org.junit.runner.RunWith

/**
 * Base class for instrumentation tests with common setup.
 *
 * Usage:
 * ```
 * class MyTest : BaseInstrumentationTest() {
 *     @Test
 *     fun testSomething() {
 *         // appContext is available
 *     }
 * }
 * ```
 */
@RunWith(AndroidJUnit4::class)
abstract class BaseInstrumentationTest {

    protected val appContext by lazy {
        InstrumentationRegistry.getInstrumentation().targetContext
    }

    protected val testContext by lazy {
        InstrumentationRegistry.getInstrumentation().context
    }

    @Before
    open fun setUp() {
        // Override in subclasses for additional setup
    }
}
