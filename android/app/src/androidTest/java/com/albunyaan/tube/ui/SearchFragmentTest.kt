package com.albunyaan.tube.ui

import android.os.SystemClock
import androidx.appcompat.widget.SearchView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.espresso.matcher.ViewMatchers.Visibility
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.albunyaan.tube.HiltTestActivity
import com.albunyaan.tube.R
import com.albunyaan.tube.di.TestNetworkModule
import com.albunyaan.tube.launchFragmentInHiltContainer
import com.albunyaan.tube.util.searchAnswer
import com.albunyaan.tube.util.searchVideo
import com.albunyaan.tube.util.typeQuery
import androidx.media3.datasource.cache.SimpleCache
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Instrumentation tests for SearchFragment functionality.
 * Tests search input, history management, and accessibility.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@LargeTest
class SearchFragmentTest {

    @get:Rule
    var hiltRule = HiltAndroidRule(this)

    // Each test builds its own Hilt component, and SimpleCache allows one instance per folder per process.
    @Inject
    lateinit var simpleCache: SimpleCache

    private var scenario: ActivityScenario<HiltTestActivity>? = null

    private val api get() = TestNetworkModule.fakeContentApi

    @Before
    fun setUp() {
        hiltRule.inject()
        scenario = launchFragmentInHiltContainer<SearchFragment>(
            themeResId = R.style.Theme_Albunyaan
        )
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    @After
    fun tearDown() {
        api.resetSearch()
        scenario?.close()
        scenario = null
        simpleCache.release()
    }

    @Test
    fun searchView_isDisplayed() {
        onView(withId(R.id.searchView))
            .check(matches(isDisplayed()))
    }

    @Test
    fun searchView_isFocusable() {
        onView(withId(R.id.searchView))
            .check(matches(isFocusable()))
    }

    @Test
    fun searchHistorySection_isInitiallyHidden() {
        // Search history section is hidden when there's no history
        onView(withId(R.id.searchHistorySection))
            .check(matches(withEffectiveVisibility(Visibility.GONE)))
    }

    @Test
    fun emptyState_isInitiallyHidden() {
        // Empty state is hidden initially (no search has been performed)
        onView(withId(R.id.emptyState))
            .check(matches(withEffectiveVisibility(Visibility.GONE)))
    }

    @Test
    fun loadingState_isInitiallyHidden() {
        // Loading state is hidden initially
        onView(withId(R.id.loadingState))
            .check(matches(withEffectiveVisibility(Visibility.GONE)))
    }

    @Test
    fun toolbar_isDisplayed() {
        // Toolbar with search view should be visible
        onView(withId(R.id.toolbar))
            .check(matches(isDisplayed()))
    }

    @Test
    fun typing_searchesAfterThreeHundredMs() {
        val started = CountDownLatch(1)
        val calledAt = AtomicLong()
        api.onSearch = {
            calledAt.set(SystemClock.elapsedRealtime())
            started.countDown()
            searchAnswer("exact", searchVideo("v1", "Quran for beginners"))
        }

        var typedAt = 0L // stamped on the UI thread right at the keystroke: Espresso's own overhead is not debounce
        scenario!!.onActivity {
            typedAt = SystemClock.elapsedRealtime()
            it.findViewById<SearchView>(R.id.searchView).setQuery("quran", false)
        }
        assertTrue(started.await(5, TimeUnit.SECONDS))

        val waited = calledAt.get() - typedAt
        assertTrue("search fired after ${waited}ms, expected the 300 ms debounce", waited in 290..450)
    }

    @Test
    fun queryDroppingBelowTwoChars_cancelsTheInFlightSearch() {
        val started = CountDownLatch(1)
        val gate = CompletableDeferred<Unit>()
        api.onSearch = {
            started.countDown()
            gate.await()
            searchAnswer("exact", searchVideo("v1", "Quran for beginners"))
        }

        typeQuery("qu")
        assertTrue(started.await(5, TimeUnit.SECONDS))
        typeQuery("q") // the "qu" request is still in flight
        gate.complete(Unit)
        Thread.sleep(700) // a late "qu" result would land long before this

        onView(withId(R.id.searchResultsList)).check(matches(withEffectiveVisibility(Visibility.GONE)))
        onView(withId(R.id.emptyState)).check(matches(withEffectiveVisibility(Visibility.GONE)))
    }

    @Test
    fun submittingAQuery_searchesOnceNotAgainWhenTheDebounceFires() {
        val calls = AtomicInteger()
        api.onSearch = {
            calls.incrementAndGet()
            searchAnswer("exact", searchVideo("v1", "Quran for beginners"))
        }

        // What tapping a history row does: setQuery(query, submit = true) fires the change and the submit.
        scenario!!.onActivity { it.findViewById<SearchView>(R.id.searchView).setQuery("quran", true) }
        Thread.sleep(900) // past the debounce

        assertEquals(1, calls.get())
    }
}
