package com.albunyaan.tube.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [OrientationPolicy] — verifies the sw600dp / API 36 boundary of
 * Android 16's "Ignore orientation, resizability, and aspect ratio restrictions"
 * behavior change.
 *
 * https://developer.android.com/about/versions/16/behavior-changes-16
 * "For apps targeting Android 16 (API level 36), orientation, resizability, and
 *  aspect ratio restrictions no longer apply on displays with smallest width >= 600dp."
 */
class OrientationPolicyTest {

    @Test
    fun `phone on Android 16 - request is honored (below sw600dp)`() {
        assertTrue(
            OrientationPolicy.honorsRequestedOrientation(
                sdkInt = 36,
                smallestScreenWidthDp = 411
            )
        )
    }

    @Test
    fun `tablet on Android 16 - request is ignored`() {
        assertFalse(
            OrientationPolicy.honorsRequestedOrientation(
                sdkInt = 36,
                smallestScreenWidthDp = 800
            )
        )
    }

    @Test
    fun `tablet on Android 15 - request is still honored (pre-API-36 device)`() {
        assertTrue(
            OrientationPolicy.honorsRequestedOrientation(
                sdkInt = 35,
                smallestScreenWidthDp = 800
            )
        )
    }

    @Test
    fun `exactly sw600dp on Android 16 - request is ignored (boundary is inclusive)`() {
        assertFalse(
            OrientationPolicy.honorsRequestedOrientation(
                sdkInt = 36,
                smallestScreenWidthDp = 600
            )
        )
    }

    @Test
    fun `sw599dp on Android 16 - request is honored (just below boundary)`() {
        assertTrue(
            OrientationPolicy.honorsRequestedOrientation(
                sdkInt = 36,
                smallestScreenWidthDp = 599
            )
        )
    }
}
