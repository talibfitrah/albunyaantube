package com.albunyaan.tube.ui

import android.app.LocaleManager
import android.os.Build
import android.os.LocaleList
import android.view.View
import android.widget.EditText
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.test.core.app.ActivityScenario
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
import com.albunyaan.tube.util.device
import com.albunyaan.tube.util.eventually
import com.albunyaan.tube.util.gone
import com.albunyaan.tube.util.onResumedActivity
import com.albunyaan.tube.util.screenshot
import com.albunyaan.tube.util.searchVideo
import com.albunyaan.tube.util.shown
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

    // Views are read through onResumedActivity, not Espresso or ActivityScenario (see onResumedActivity).
    private fun page(vararg ids: String) = CursorPage(
        data = ids.map { searchVideo(it, "Quran lesson $it") },
        pageInfo = PageInfo(hasNext = false, nextCursor = null)
    )
    private fun <T> list(read: (RecyclerView) -> T) = onResumedActivity { read(it.findViewById(R.id.recyclerView)) }
    private fun rows() = list { it.adapter!!.itemCount }
    private fun firstVisible() = list { (it.layoutManager as GridLayoutManager).findFirstVisibleItemPosition() }
    /** Where the list sits: the first visible row and that row's on-screen position. */
    private fun place() = list { rv ->
        (rv.layoutManager as GridLayoutManager).findFirstVisibleItemPosition() to
            rv.getChildAt(0)?.let { c -> IntArray(2).also(c::getLocationOnScreen).toList() }
    }
    private fun refreshing() = onResumedActivity { it.findViewById<SwipeRefreshLayout>(R.id.swipeRefresh).isRefreshing }
    /** What Espresso's replaceText does (setText), without its idle wait: "quran" may be loading under the spinner. */
    private fun type(text: String) = onResumedActivity { it.findViewById<EditText>(R.id.searchEditText).setText(text) }

    @Test
    fun newQueryKeepsTheListUnderTheSpinner() {
        hiltRule.inject()
        val slowStarted = CountDownLatch(1)
        val slowGate = CompletableDeferred<Unit>()
        val quranCalls = AtomicInteger()
        api.onContent = { q ->
            when (q) {
                null -> page("v1", "v2", "v3", "v9")
                "quran" -> page("v1", "v2", "v3").also { quranCalls.incrementAndGet() }
                "quran tv" -> {
                    slowStarted.countDown()
                    slowGate.await()
                    page("v4", "v1")
                }
                else -> page()
            }
        }
        scenario = launchFragmentInHiltContainer<VideosFragmentNew>(themeResId = R.style.Theme_Albunyaan)

        // 1. The plain list (4 rows), then a first search whose 3 rows land.
        eventually { assertEquals(4, rows()) }
        type("quran")
        eventually {
            assertEquals(1, quranCalls.get())
            assertEquals(3, rows())
            shown(R.id.recyclerView)
        }
        val placeBefore = place()

        // 2. A newer query in flight: the 3 rows stay, in place, under the spinner; no skeleton, no empty view.
        type("quran tv")
        eventually { assertEquals(0L, slowStarted.count) }
        eventually {
            onResumedActivity {
                val swipe = it.findViewById<SwipeRefreshLayout>(R.id.swipeRefresh)
                assertTrue("the top spinner is refreshing", swipe.isRefreshing)
                // The spinner is the one SwipeRefreshLayout child that is not the list.
                val spinner = (0 until swipe.childCount).map(swipe::getChildAt).single { c -> c !is RecyclerView }
                assertEquals("the spinner circle is visible", View.VISIBLE, spinner.visibility)
            }
        }
        shown(R.id.recyclerView)
        assertEquals(3, rows())
        assertEquals("the list moved under the spinner", placeBefore, place())
        gone(R.id.listSkeleton)
        gone(R.id.emptyState)
        screenshot("android-${device()}-$lang-tab-loading")

        // 2b. The view is recreated mid-search (rotation): the 3 rows come back under the spinner, never blank.
        // Activity.recreate(), not scenario.recreate(): that one waits for an idle looper too.
        val before = onResumedActivity { it }
        onResumedActivity { it.recreate() }
        eventually {
            assertTrue("the activity was not recreated", onResumedActivity { it } !== before)
            assertEquals("the recreated view lost the kept rows", 3, rows())
            assertTrue("the recreated view dropped the spinner", refreshing())
        }
        shown(R.id.recyclerView)
        gone(R.id.listSkeleton)

        // 3. It lands: the spinner stops and the new rows replace the old.
        slowGate.complete(Unit)
        eventually {
            assertTrue(!refreshing())
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
        // An emptied box loads at once (no debounce) and the fake answers inline, so every load the click causes has
        // run when performClick returns. ContentListViewModelTest pins that nothing follows once the 300 ms pass.
        scenario!!.onActivity { it.findViewById<View>(R.id.searchClearButton).performClick() }

        assertEquals("the clear button loaded the list more than once", 2, plainLoads.get())
    }

    @Test
    fun aPageAppendedBelowKeepsThePlace() {
        hiltRule.inject()
        val calls = AtomicInteger()
        val pageTwoGate = CompletableDeferred<Unit>()
        api.onContent = {
            if (calls.incrementAndGet() == 1) {
                CursorPage(
                    data = (0 until 40).map { searchVideo("v$it", "Quran lesson $it") },
                    pageInfo = PageInfo(hasNext = true, nextCursor = "c1")
                )
            } else {
                pageTwoGate.await()
                page(*Array(40) { "w$it" })
            }
        }
        scenario = launchFragmentInHiltContainer<VideosFragmentNew>(themeResId = R.style.Theme_Albunyaan)
        eventually { assertEquals(40, rows()) }

        // Scrolling near the end asks for page 2; it is held until the place is noted.
        onResumedActivity { it.findViewById<RecyclerView>(R.id.recyclerView).smoothScrollToPosition(39) }
        eventually { assertEquals(2, calls.get()) }
        eventually { assertEquals(RecyclerView.SCROLL_STATE_IDLE, list { it.scrollState }) }
        val placeBefore = place()
        assertTrue("the list did not scroll", placeBefore.first > 0)

        pageTwoGate.complete(Unit)
        eventually {
            assertEquals(80, rows())
            assertTrue("the list has not laid out the new page yet", !list { it.isLayoutRequested })
        }

        assertEquals("appending a page moved the list", placeBefore, place())
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
