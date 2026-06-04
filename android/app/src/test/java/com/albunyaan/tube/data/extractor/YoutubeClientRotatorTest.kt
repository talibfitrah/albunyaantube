package com.albunyaan.tube.data.extractor

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class YoutubeClientRotatorTest {

    private lateinit var rotator: YoutubeClientRotator
    private var fakeTime = 0L

    @Before
    fun setup() {
        rotator = YoutubeClientRotator()
        rotator.clock = { fakeTime }
    }

    @Test
    fun initialClient_iosEnabled_returnsIos() {
        assertEquals(YoutubeClientRotator.Client.IOS, rotator.initialClient(true))
    }

    @Test
    fun initialClient_iosDisabled_returnsAndroid() {
        assertEquals(YoutubeClientRotator.Client.ANDROID, rotator.initialClient(false))
    }

    @Test
    fun nextClient_firstCall_returnsAndroid() {
        // ROTATION_ORDER = [IOS(0), ANDROID(1)]; first nextClient starts from index 1
        assertEquals(YoutubeClientRotator.Client.ANDROID, rotator.nextClient("v1"))
    }

    @Test
    fun nextClient_secondCall_returnsNull() {
        rotator.nextClient("v1") // advances to index 1 (ANDROID)
        assertNull(rotator.nextClient("v1")) // nextIndex would be 2, which is >= size
    }

    @Test
    fun nextClient_afterReset_returnsAndroid() {
        rotator.nextClient("v1") // advances to ANDROID
        rotator.reset("v1")
        // After reset, state is cleared → nextClient starts fresh at index 1 (ANDROID)
        assertEquals(YoutubeClientRotator.Client.ANDROID, rotator.nextClient("v1"))
    }

    @Test
    fun nextClient_differentVideos_independentState() {
        // Exhaust v1
        rotator.nextClient("v1")
        assertNull(rotator.nextClient("v1"))

        // v2 is independent and still returns ANDROID on first call
        assertEquals(YoutubeClientRotator.Client.ANDROID, rotator.nextClient("v2"))
    }

    @Test
    fun evictExpired_staleStateRemoved() {
        fakeTime = 0L
        rotator.nextClient("v1") // sets state at t=0

        // Advance time past TTL (30 min + 1 ms)
        fakeTime = 30L * 60 * 1000 + 1

        // nextClient triggers evictExpired; v1 state is evicted → starts fresh → returns ANDROID
        assertEquals(YoutubeClientRotator.Client.ANDROID, rotator.nextClient("v1"))
    }

    @Test
    fun evictExpired_freshStateKept() {
        fakeTime = 0L
        rotator.nextClient("v1") // sets state at t=0, index=1 (ANDROID)

        // Advance time to just under TTL
        fakeTime = 30L * 60 * 1000 - 1

        // State is still valid → nextIndex would be 2 → null (exhausted)
        assertNull(rotator.nextClient("v1"))
    }

    @Test
    fun evictExpired_exactTtlBoundary_stateKept() {
        fakeTime = 0L
        rotator.nextClient("v1")           // state written at t=0
        fakeTime = 30L * 60 * 1000        // exactly at TTL (= not yet expired under strict >)
        assertNull(rotator.nextClient("v1"))  // state kept → already exhausted → null
    }

    @Test
    fun reset_nonExistentKey_noOp() {
        rotator.reset("nonExistentVideo")   // should not throw
        // nextClient for that video still starts fresh
        assertEquals(YoutubeClientRotator.Client.ANDROID, rotator.nextClient("nonExistentVideo"))
    }

    // ── currentClient (ANDROID-PLAYBACK-02: failure-driven, not refresh-driven) ──────

    @Test
    fun currentClient_noState_iosEnabled_returnsIos() {
        // The seek-403 fix: with no prior failure, a refresh stays on IOS (full ladder),
        // never rotating to the muxed-only ANDROID client.
        assertEquals(YoutubeClientRotator.Client.IOS, rotator.currentClient("v1", isIosEnabled = true))
    }

    @Test
    fun currentClient_noState_iosDisabled_returnsAndroid() {
        assertEquals(YoutubeClientRotator.Client.ANDROID, rotator.currentClient("v1", isIosEnabled = false))
    }

    @Test
    fun currentClient_doesNotAdvanceState_repeatedCallsStayIos() {
        // Repeated refreshes (no failure) must NOT rotate — this is the core of the fix.
        repeat(5) { assertEquals(YoutubeClientRotator.Client.IOS, rotator.currentClient("v1", isIosEnabled = true)) }
    }

    @Test
    fun currentClient_afterFailureArmed_returnsAndroid() {
        rotator.nextClient("v1") // simulate an extraction failure arming the fallback
        assertEquals(YoutubeClientRotator.Client.ANDROID, rotator.currentClient("v1", isIosEnabled = true))
        // ...and stays armed without advancing further on repeat reads
        assertEquals(YoutubeClientRotator.Client.ANDROID, rotator.currentClient("v1", isIosEnabled = true))
    }

    @Test
    fun currentClient_afterReset_returnsInitialIos() {
        rotator.nextClient("v1")
        rotator.reset("v1")
        assertEquals(YoutubeClientRotator.Client.IOS, rotator.currentClient("v1", isIosEnabled = true))
    }
}
