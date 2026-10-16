package com.albunyaan.tube.ui

import android.app.LocaleManager
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import android.os.ParcelFileDescriptor
import android.text.Spanned
import android.text.style.URLSpan
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.core.widget.ImageViewCompat
import androidx.core.widget.NestedScrollView
import androidx.media3.datasource.cache.SimpleCache
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.albunyaan.tube.HiltTestActivity
import com.albunyaan.tube.R
import com.albunyaan.tube.data.channel.ChannelLink
import com.albunyaan.tube.data.channel.FakeChannelDetailRepository
import com.albunyaan.tube.di.TestChannelDetailModule
import com.albunyaan.tube.launchFragmentInHiltContainer
import com.albunyaan.tube.ui.detail.ChannelDetailFragment
import com.albunyaan.tube.ui.player.PlayerDescriptions
import com.albunyaan.tube.ui.player.PlayerFragment
import com.albunyaan.tube.util.device
import com.albunyaan.tube.util.eventually
import com.albunyaan.tube.util.screenshot
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.tabs.TabLayout
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

/**
 * Owner rule: no hand-off to YouTube. A YouTube link in a video description or in a channel's About
 * links is shown as text but is not a tap target; any other link still opens. Each run saves
 * android-{phone|tablet}-{lang}-description-links and -channel-links.
 */
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
abstract class NoYouTubeLinksScreensBase(private val lang: String) {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    // Each test builds its own Hilt component, and SimpleCache allows one instance per folder per process.
    @Inject
    lateinit var simpleCache: SimpleCache

    private var scenario: ActivityScenario<HiltTestActivity>? = null

    // The per-app language (see SearchScreensBase): LocaleTestRule alone does not reach an AppCompatActivity.
    @Before
    fun setUp() {
        hiltRule.inject()
        TestChannelDetailModule.fakeRepository.reset()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(lang)
        eventually { assertEquals(lang, context.resources.configuration.locales[0].language) }
    }

    @After
    fun tearDown() {
        TestChannelDetailModule.fakeRepository.reset()
        InstrumentationRegistry.getInstrumentation().targetContext
            .getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.getEmptyLocaleList()
        scenario?.close()
        simpleCache.release()
    }

    /** The colour the row's link icon is drawn in. */
    private fun iconTint(row: View) =
        ImageViewCompat.getImageTintList(row.findViewById<ImageView>(R.id.linkIcon))?.defaultColor

    /** The unit tests run the link rule on the JVM's IDN and Uri; this runs the IDNA-dependent vectors on Android's. */
    @Test
    fun theLinkRuleHoldsOnTheDevice() {
        val notOpenable = listOf(
            "https://ｙｏｕｔｕｂｅ.com/a", "https://youtube\u3002com/a", "https://you\u00ADtube.com/a", "https://%79outube.com/a",
            "https://YOUTUBE.COM./a", "https://youtube.com\\@example.org/a", "https://\\youtube.com/a", "https://www.youtube.co.uk/x",
            "https://y\u1D52utube.com/a", "https://you\uD82F\uDCA0tube.com/a", " https://example.org/a",
            "https://yt.be/x", "https://youtubego.com/",
            "https://\uD83C\uDD48\uD83C\uDD3E\uD83C\uDD44\uD83C\uDD43\uD83C\uDD44\uD83C\uDD31\uD83C\uDD34.com/a" // 🅈🄾🅄🅃🅄🄱🄴
        )
        for (url in notOpenable) assertFalse("$url opens", PlayerDescriptions.isOpenableLink(url))
        for (url in listOf("https://مثال.مصر/", "https://bücher.de/", "https://youtube.com@example.org/a", "https://myyoutube.org/x")) {
            assertTrue("$url does not open", PlayerDescriptions.isOpenableLink(url))
        }
        // The tap must open: intent matching is case-sensitive, and no browser takes `Https`. Resolved as the shell, like
        // the tap's startActivity: this app's own resolveActivity sees no browser (package visibility, no <queries>).
        val text = PlayerDescriptions.render("<a href=\"Https://example.org/a\">a</a>") as Spanned
        val url = text.getSpans(0, text.length, URLSpan::class.java).single().url
        val resolved = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("cmd package resolve-activity --brief -a android.intent.action.VIEW -d $url")
            .let { ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText().trim() }
        assertTrue("$url resolves no activity: $resolved", resolved.lines().last().matches(Regex("""[\w.]+/[\w.$]+""")))
    }

    @Test
    fun aDescriptionYouTubeLinkIsTextNotALink() {
        val html = "Lesson notes: <a href=\"https://example.org/notes\">example.org/notes</a><br>" +
            "Channel: <a href=\"https://www.youtube.com/@channel\">youtube.com/@channel</a>"
        scenario = launchFragmentInHiltContainer<PlayerFragment>(
            fragmentArgs = Bundle().apply {
                putString("videoId", "vid_test_001")
                putString("title", "Lesson")
                putString("description", html)
            },
            themeResId = R.style.Theme_Albunyaan
        )
        lateinit var text: CharSequence
        eventually {
            scenario!!.onActivity { activity ->
                val description = activity.findViewById<TextView>(R.id.videoDescription)
                if (!description.isVisible) activity.findViewById<View>(R.id.descriptionHeader).performClick()
                text = description.text
                assertTrue("the description card is not open", description.isShown)
                assertTrue("the YouTube link text is not shown: $text", text.contains("youtube.com/@channel"))
            }
        }
        val links = (text as Spanned).getSpans(0, text.length, URLSpan::class.java).map { it.url }
        assertEquals("only the other link is tappable", listOf("https://example.org/notes"), links)
        scenario!!.onActivity { activity ->
            val description = activity.findViewById<View>(R.id.videoDescription)
            description.requestRectangleOnScreen(Rect(0, 0, description.width, description.height), true)
        }
        screenshot("android-${device()}-$lang-description-links")
    }

    @Test
    fun aChannelYouTubeLinkIsShownButNotTappable() {
        val youTube = "https://www.youtube.com/@channel"
        val other = "https://example.org/donate"
        TestChannelDetailModule.fakeRepository.headerToReturn = FakeChannelDetailRepository.createDefaultHeader(
            links = listOf(ChannelLink("youtube.com", youTube), ChannelLink("example.org", other))
        )
        scenario = launchFragmentInHiltContainer<ChannelDetailFragment>(
            fragmentArgs = Bundle().apply { putString(ChannelDetailFragment.ARG_CHANNEL_ID, "UC_test_channel") },
            themeResId = R.style.Theme_Albunyaan
        )
        eventually {
            scenario!!.onActivity { it.findViewById<TabLayout>(R.id.tabLayout).getTabAt(4)!!.select() }
        }
        lateinit var rows: List<View>
        eventually {
            scenario!!.onActivity { activity ->
                val container = activity.findViewById<ViewGroup>(R.id.linksContainer)
                assertNotNull("the About tab has not bound its links", container)
                rows = (0 until container.childCount).map(container::getChildAt)
                assertEquals(2, rows.size)
            }
        }
        scenario!!.onActivity { activity ->
            val (yt, site) = rows
            assertEquals("the YouTube link text stays visible", youTube, yt.findViewById<TextView>(R.id.linkUrl).text.toString())
            assertFalse("a tap on the YouTube link opened something", yt.performClick())
            assertFalse(yt.isClickable)
            assertFalse(yt.isFocusable)
            assertNull("the YouTube link still has a ripple", yt.background)
            assertEquals(View.GONE, yt.findViewById<View>(R.id.linkChevron).visibility)
            assertNotEquals(
                "the YouTube link is still coloured as a link",
                site.findViewById<TextView>(R.id.linkUrl).currentTextColor,
                yt.findViewById<TextView>(R.id.linkUrl).currentTextColor
            )

            assertNotEquals(
                "the YouTube link's icon is still tinted as a link",
                iconTint(site),
                iconTint(yt)
            )

            assertTrue("the other link lost its click", site.hasOnClickListeners() && site.isClickable)
            assertNotNull(site.background)
            assertEquals(View.VISIBLE, site.findViewById<View>(R.id.linkChevron).visibility)

            // Bring the links into the screenshot: collapse the channel header, then scroll the tab.
            activity.findViewById<AppBarLayout>(R.id.appBarLayout).setExpanded(false, false)
            activity.findViewById<NestedScrollView>(R.id.aboutScrollView)
                .scrollTo(0, activity.findViewById<View>(R.id.linksSectionTitle).top)
        }
        screenshot("android-${device()}-$lang-channel-links")
    }
}

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@LargeTest
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
class NoYouTubeLinksScreensEnTest : NoYouTubeLinksScreensBase("en")

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@LargeTest
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
class NoYouTubeLinksScreensArTest : NoYouTubeLinksScreensBase("ar")
