package com.albunyaan.tube.ui

import android.app.LocaleManager
import android.os.Build
import android.os.LocaleList
import android.view.View
import androidx.annotation.RequiresApi
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
import java.util.concurrent.CountDownLatch
import javax.inject.Inject

/**
 * In-tab search on the real VideosFragmentNew + ContentListViewModel: while a newer query loads, the
 * previous list stays on screen under the top spinner (no skeleton, no empty/error view).
 * Backend answers are scripted through [TestNetworkModule.fakeContentApi]. Each run saves
 * android-{phone|tablet}-{lang}-tab-loading.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
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

    @Test
    fun newQueryKeepsTheListUnderTheSpinner() {
        hiltRule.inject()
        val slowStarted = CountDownLatch(1)
        val slowGate = CompletableDeferred<Unit>()
        fun page(vararg ids: String) = CursorPage(
            data = ids.map { searchVideo(it, "Quran lesson $it") },
            pageInfo = PageInfo(hasNext = false, nextCursor = null)
        )
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
        fun rows() = run {
            var n = 0
            scenario!!.onActivity { n = it.findViewById<RecyclerView>(R.id.recyclerView).adapter!!.itemCount }
            n
        }

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
