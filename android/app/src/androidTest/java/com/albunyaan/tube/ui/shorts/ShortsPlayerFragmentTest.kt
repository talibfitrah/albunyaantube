package com.albunyaan.tube.ui.shorts

/**
 * Instrumented tests for ShortsPlayerFragment. Requires a connected device or emulator.
 * Run with: ./gradlew :app:connectedDebugAndroidTest --tests "com.albunyaan.tube.ui.shorts.*"
 *
 * NOTE: These tests were authored without an emulator available to the author.
 * Expected to pass on-device; minor assertion adjustments may be needed at first run.
 *
 * ## What this test covers
 *
 *  - `shortsPlayerFragment_rendersFirstShortTitle()`  — launches with a fake
 *    channel feed of one short, asserts the title view displays the item title.
 *  - `shareButton_clickInvokesShareCompat()`          — clicks the share action
 *    and asserts it does not crash. Full `ACTION_SEND` intent interception
 *    would require `androidx.test.espresso:espresso-intents`, which is not on
 *    the `androidTestImplementation` classpath today; adding it is out of
 *    scope for this task. See the docstring on that test.
 *  - `likeButton_togglesFavorite()`                   — clicks the like button
 *    and asserts the production [FavoritesRepository] (Room-backed) now has a
 *    favorite entry for the bound video id.
 *  - `subscribeButton_togglesFollow()`                — clicks subscribe and
 *    asserts the production [FollowedChannelsRepository] now has a follow
 *    entry for the bound channel id.
 *  - `backButton_popsBackStack()`                     — wires a
 *    [androidx.navigation.testing.TestNavHostController], clicks the back
 *    button, and asserts the fragment popped. (Excluded — requires
 *    `androidx.navigation:navigation-testing` which is not currently on the
 *    classpath. The test is retained as a documented skipped case; see the
 *    `@Ignore` block below.)
 *
 * ## DI / fakes approach
 *
 * The existing project pattern (see `TestChannelDetailModule`,
 * `TestPlaylistDetailModule`, `TestNetworkModule`, `TestDownloadModule`) uses
 * `@TestInstallIn` modules rather than `@BindValue`. The only interface-backed
 * repository in the shorts graph that is easy to swap without replicating
 * whole DI modules is `ChannelDetailRepository`, which is already swapped
 * globally by `TestChannelDetailModule`. Driving the ShortsPlayerFragment in
 * **channel mode** (`channelId` arg non-null) therefore lets us control the
 * feed via `TestChannelDetailModule.fakeRepository.shortsToReturn` *and*
 * `headerToReturn`, while `FavoritesRepository` / `FollowedChannelsRepository`
 * remain the real Room-backed implementations (scoped per instrumentation
 * process, in-memory when the emulator is freshly wiped) and are inspected
 * directly through Hilt injection.
 *
 * `PlayerRepository` is **not** swapped. In the test path the real
 * `resolveStreams` will either succeed (network present on-device) or fail
 * fast; either outcome is acceptable — the assertions below only touch UI
 * state and repositories, not the Media3 player pipeline.
 */
@dagger.hilt.android.testing.HiltAndroidTest
@org.junit.runner.RunWith(androidx.test.ext.junit.runners.AndroidJUnit4::class)
@androidx.test.filters.LargeTest
class ShortsPlayerFragmentTest {

    @get:org.junit.Rule
    var hiltRule = dagger.hilt.android.testing.HiltAndroidRule(this)

    @javax.inject.Inject
    lateinit var favoritesRepository: com.albunyaan.tube.data.local.FavoritesRepository

    @javax.inject.Inject
    lateinit var followedChannelsRepository: com.albunyaan.tube.data.local.FollowedChannelsRepository

    private val channelDetailFake: com.albunyaan.tube.data.channel.FakeChannelDetailRepository
        get() = com.albunyaan.tube.di.TestChannelDetailModule.fakeRepository

    private var scenario: androidx.test.core.app.ActivityScenario<com.albunyaan.tube.HiltTestActivity>? = null

    private val testChannelId: String = "UC_test_channel"
    private val testVideoId: String = "vid_test_001"
    private val testVideoTitle: String = "Bismillah Shorts Test Title"

    @org.junit.Before
    fun setUp() {
        hiltRule.inject()
        channelDetailFake.reset()

        // Seed one channel short + a channel header so the ViewModel's
        // channel-mode feed returns exactly one hydrated ShortsItem.
        channelDetailFake.headerToReturn =
            com.albunyaan.tube.data.channel.FakeChannelDetailRepository.createDefaultHeader(
                id = testChannelId,
                title = "Test Channel"
            )
        channelDetailFake.shortsToReturn = listOf(
            com.albunyaan.tube.data.channel.ChannelShort(
                id = testVideoId,
                title = testVideoTitle,
                thumbnailUrl = null,
                viewCount = 0L,
                durationSeconds = 30,
                publishedTime = null
            )
        )

        // Clear any lingering Room state from prior tests.
        kotlinx.coroutines.runBlocking {
            runCatching { favoritesRepository.clearAll() }
        }
    }

    @org.junit.After
    fun tearDown() {
        scenario?.close()
        scenario = null
        channelDetailFake.reset()
        kotlinx.coroutines.runBlocking {
            runCatching { favoritesRepository.clearAll() }
        }
    }

    private fun launchFragment() {
        val args = androidx.core.os.bundleOf(
            "initialShortId" to testVideoId,
            "channelId" to testChannelId
        )
        scenario = com.albunyaan.tube.launchFragmentInHiltContainer<ShortsPlayerFragment>(
            fragmentArgs = args,
            themeResId = com.albunyaan.tube.R.style.Theme_Albunyaan
        )
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    // ---------- Tests ----------

    @org.junit.Test
    fun shortsPlayerFragment_rendersFirstShortTitle() {
        launchFragment()

        androidx.test.espresso.Espresso.onView(
            androidx.test.espresso.matcher.ViewMatchers.withId(
                com.albunyaan.tube.R.id.shortTitle
            )
        ).check(
            androidx.test.espresso.assertion.ViewAssertions.matches(
                androidx.test.espresso.matcher.ViewMatchers.withText(testVideoTitle)
            )
        )
    }

    /**
     * Verifies the share button is clickable and its click handler does not crash.
     *
     * The task description asked for `Intents.intended(hasAction(ACTION_SEND))`
     * with `EXTRA_TEXT == canonicalShareUrl`, but that requires the
     * `androidx.test.espresso:espresso-intents` artifact which is not on the
     * `androidTestImplementation` classpath for this module (see
     * `app/build.gradle.kts`). Since the task prohibits adding dependencies,
     * this test is reduced to a smoke-test click; the full intent assertion
     * can be added in a follow-up once the dep is in place.
     */
    @org.junit.Test
    fun shareButton_clickInvokesShareCompat() {
        launchFragment()

        androidx.test.espresso.Espresso.onView(
            androidx.test.espresso.matcher.ViewMatchers.withId(
                com.albunyaan.tube.R.id.shortShareBtn
            )
        ).perform(androidx.test.espresso.action.ViewActions.click())
        // No crash == pass. Returning control to the launching activity from the
        // chooser is managed by the system.
    }

    @org.junit.Test
    fun likeButton_togglesFavorite() {
        launchFragment()

        androidx.test.espresso.Espresso.onView(
            androidx.test.espresso.matcher.ViewMatchers.withId(
                com.albunyaan.tube.R.id.shortLikeBtn
            )
        ).perform(androidx.test.espresso.action.ViewActions.click())

        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().waitForIdleSync()

        val liked = kotlinx.coroutines.runBlocking {
            favoritesRepository.isFavoriteOnce(testVideoId)
        }
        org.junit.Assert.assertTrue(
            "Expected video $testVideoId to be favorited after clicking like",
            liked
        )
    }

    @org.junit.Test
    fun subscribeButton_togglesFollow() {
        launchFragment()

        androidx.test.espresso.Espresso.onView(
            androidx.test.espresso.matcher.ViewMatchers.withId(
                com.albunyaan.tube.R.id.shortSubscribeBtn
            )
        ).perform(androidx.test.espresso.action.ViewActions.click())

        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().waitForIdleSync()

        val followed = kotlinx.coroutines.runBlocking {
            followedChannelsRepository.isFollowedOnce(testChannelId)
        }
        org.junit.Assert.assertTrue(
            "Expected channel $testChannelId to be followed after clicking subscribe",
            followed
        )
    }

    /**
     * Back-button test requires `androidx.navigation:navigation-testing` for
     * `TestNavHostController`, which is not on the `androidTestImplementation`
     * classpath (see `app/build.gradle.kts`). Since the task prohibits adding
     * dependencies, this test is skipped; retained here so it is trivial to
     * enable once the dep is added.
     */
    @org.junit.Ignore("Requires androidx.navigation:navigation-testing (not on classpath)")
    @org.junit.Test
    fun backButton_popsBackStack() {
        // Intentionally empty — see @Ignore reason above.
    }
}
