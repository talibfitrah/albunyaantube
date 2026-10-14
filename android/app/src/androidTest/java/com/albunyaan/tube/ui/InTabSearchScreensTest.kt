package com.albunyaan.tube.ui

import android.app.LocaleManager
import android.os.Build
import android.os.LocaleList
import android.view.View
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.Visibility
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.media3.datasource.cache.SimpleCache
import com.albunyaan.tube.HiltTestActivity
import com.albunyaan.tube.R
import com.albunyaan.tube.data.model.api.models.PageInfo
import com.albunyaan.tube.data.source.api.CursorPage
import com.albunyaan.tube.di.TestNetworkModule
import com.albunyaan.tube.launchFragmentInHiltContainer
import com.albunyaan.tube.util.eventually
import com.albunyaan.tube.util.screenshot
import com.albunyaan.tube.util.searchVideo
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
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

/**
 * In-tab search on the real VideosFragmentNew + ContentListViewModel: while a newer query loads, the
 * previous list stays on screen under the top spinner (no skeleton, no empty/error view).
 * Backend answers are scripted through [TestNetworkModule.fakeContentApi]. Each run saves
 * android-{phone|tablet}-{lang}-tab-loading.
 */
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
abstract class InTabSearchScreensBase(private val lang: String) {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    // Each test builds its own Hilt component, and SimpleCache allows one instance per folder per process.
    @Inject
    lateinit var simpleCache: SimpleCache

    private var scenario: ActivityScenario<HiltTestActivity>? = null
    private val api get() = TestNetworkModule.fakeContentApi

    // The per-app language (see SearchScreensBase): LocaleTestRule alone does not reach an AppCompatActivity.
    @Before
    fun useLanguage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(lang)
        eventually { assertEquals(lang, context.resources.configuration.locales[0].language) }
    }

    @After
    fun tearDown() {
        api.resetContent() // first: the shared fake must be reset even if a later step throws
        InstrumentationRegistry.getInstrumentation().targetContext
            .getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.getEmptyLocaleList()
        scenario?.close()
        simpleCache.release()
    }

    private fun shown(id: Int) = onView(withId(id)).check(matches(isDisplayed()))
    private fun gone(id: Int) = onView(withId(id)).check(matches(withEffectiveVisibility(Visibility.GONE)))
    private fun page(vararg ids: String) = CursorPage(
        data = ids.map { searchVideo(it, "Quran lesson $it") },
        pageInfo = PageInfo(hasNext = false, nextCursor = null)
    )
    private fun list(): RecyclerView {
        lateinit var rv: RecyclerView
        scenario!!.onActivity { rv = it.findViewById(R.id.recyclerView) }
        return rv
    }
    private fun rows() = list().adapter!!.itemCount
    private fun firstVisible() = (list().layoutManager as GridLayoutManager).findFirstVisibleItemPosition()
    private fun type(text: String) = onView(withId(R.id.searchEditText)).perform(replaceText(text))

    @Test
    fun newQueryKeepsTheListUnderTheSpinner() {
        hiltRule.inject()
        val slowStarted = CountDownLatch(1)
        val slowGate = CompletableDeferred<Unit>()
        api.onContent = { q ->
            when (q) {
                null, "quran" -> page("v1", "v2", "v3")
                "quran tv" -> {
                    slowStarted.countDown()
                    slowGate.await()
                    page("v4", "v1")
                }
                else -> page()
            }
        }
        scenario = launchFragmentInHiltContainer<VideosFragmentNew>(themeResId = R.style.Theme_Albunyaan)
        val device = if (InstrumentationRegistry.getInstrumentation().targetContext.resources
                .configuration.smallestScreenWidthDp >= 600) "tablet" else "phone"

        // 1. The plain list, then a first search whose answer lands at once.
        eventually { assertEquals(3, rows()) }
        onView(withId(R.id.searchEditText)).perform(replaceText("quran"))
        eventually { shown(R.id.recyclerView) }

        // 2. A newer query in flight: the 3 rows stay under the spinner; no skeleton, no empty view.
        onView(withId(R.id.searchEditText)).perform(replaceText("quran tv"))
        eventually { assertEquals(0L, slowStarted.count) }
        eventually {
            scenario!!.onActivity {
                val swipe = it.findViewById<SwipeRefreshLayout>(R.id.swipeRefresh)
                assertTrue("the top spinner is refreshing", swipe.isRefreshing)
                // The spinner is the one SwipeRefreshLayout child that is not the list.
                val spinner = (0 until swipe.childCount).map(swipe::getChildAt).single { c -> c !is RecyclerView }
                assertEquals("the spinner circle is visible", View.VISIBLE, spinner.visibility)
            }
        }
        shown(R.id.recyclerView)
        assertEquals(3, rows())
        gone(R.id.listSkeleton)
        gone(R.id.emptyState)
        screenshot("android-$device-$lang-tab-loading")

        // 2b. The view is recreated mid-search (rotation): the 3 rows come back under the spinner, never blank.
        scenario!!.recreate()
        eventually {
            assertEquals("the recreated view lost the kept rows", 3, rows())
            scenario!!.onActivity {
                assertTrue("the recreated view dropped the spinner", it.findViewById<SwipeRefreshLayout>(R.id.swipeRefresh).isRefreshing)
            }
        }
        shown(R.id.recyclerView)
        gone(R.id.listSkeleton)

        // 3. It lands: the spinner stops and the new rows replace the old.
        slowGate.complete(Unit)
        eventually {
            scenario!!.onActivity {
                assertTrue(!it.findViewById<SwipeRefreshLayout>(R.id.swipeRefresh).isRefreshing)
            }
            assertEquals(2, rows())
        }
        shown(R.id.recyclerView)
    }

    @Test
    fun typingOnDropsTheInFlightSearchAtOnce() {
        hiltRule.inject()
        val slowStarted = CountDownLatch(1)
        val slowGate = CompletableDeferred<Unit>()
        val nextStarted = CountDownLatch(1)
        val nextGate = CompletableDeferred<Unit>()
        api.onContent = { q ->
            when (q) {
                null -> page("v1", "v2", "v3")
                "quran" -> {
                    slowStarted.countDown()
                    slowGate.await()
                    page("v4")
                }
                else -> {
                    nextStarted.countDown()
                    nextGate.await()
                    page("v5", "v6")
                }
            }
        }
        scenario = launchFragmentInHiltContainer<VideosFragmentNew>(themeResId = R.style.Theme_Albunyaan)
        eventually { assertEquals(3, rows()) }
        type("quran")
        assertTrue(slowStarted.await(5, TimeUnit.SECONDS))

        type("quran tafsir") // the user types on while "quran" is in flight
        slowGate.complete(Unit) // "quran" answers late, inside the debounce
        assertTrue(nextStarted.await(5, TimeUnit.SECONDS)) // the debounce is over: a late answer would have landed

        assertEquals("the late answer of the query the user left landed", 3, rows())
        nextGate.complete(Unit)
        eventually { assertEquals(2, rows()) }
    }

    @Test
    fun aNewQueryOpensItsResultsAtTheTop() {
        hiltRule.inject()
        val all = Array(60) { "v$it" }
        api.onContent = { q -> if (q == null) page(*all) else page(*all.copyOfRange(20, 60)) } // results the user scrolled past
        scenario = launchFragmentInHiltContainer<VideosFragmentNew>(themeResId = R.style.Theme_Albunyaan)
        eventually { assertEquals(60, rows()) }
        scenario!!.onActivity { it.findViewById<RecyclerView>(R.id.recyclerView).scrollToPosition(59) }
        eventually { assertTrue("the list did not scroll", firstVisible() > 0) }

        type("quran")

        eventually { assertEquals(40, rows()) }
        eventually { assertEquals("the new results opened part way down", 0, firstVisible()) }
    }

    @Test
    fun clearingAfterAFailedSearchLoadsTheListOnce() {
        hiltRule.inject()
        val plainLoads = AtomicInteger()
        val searches = AtomicInteger()
        api.onContent = { q ->
            if (q == null && plainLoads.incrementAndGet() == 1) page("v1", "v2", "v3")
            else {
                if (q != null) searches.incrementAndGet()
                throw IOException("offline")
            }
        }
        scenario = launchFragmentInHiltContainer<VideosFragmentNew>(themeResId = R.style.Theme_Albunyaan)
        eventually { assertEquals(3, rows()) }
        type("quran")
        eventually { assertEquals(1, searches.get()) } // it failed: the 3 rows stay with an error

        // The bare test host draws the search bar under the status bar, so a real tap there opens the shade.
        scenario!!.onActivity { it.findViewById<View>(R.id.searchClearButton).performClick() }
        Thread.sleep(700) // past the debounce

        assertEquals("the clear button loaded the list more than once", 2, plainLoads.get())
    }
}

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@LargeTest
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
class InTabSearchScreensEnTest : InTabSearchScreensBase("en")

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@LargeTest
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
class InTabSearchScreensArTest : InTabSearchScreensBase("ar")
