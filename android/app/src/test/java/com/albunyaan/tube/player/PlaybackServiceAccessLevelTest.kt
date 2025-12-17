package com.albunyaan.tube.player

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for [PlaybackService.determineAccessLevel].
 *
 * Verifies the controller access level logic is correct:
 * - FULL access for own app and legacy controller
 * - RESTRICTED access for all other controllers
 */
class PlaybackServiceAccessLevelTest {

    private val ownPackage = "com.albunyaan.tube"

    // --- Full Access Tests ---

    @Test
    fun `own app gets full access`() {
        val result = PlaybackService.determineAccessLevel(
            controllerPackage = ownPackage,
            ownPackage = ownPackage
        )
        assertEquals(PlaybackService.ControllerAccessLevel.FULL, result)
    }

    @Test
    fun `legacy controller gets full access`() {
        // LEGACY_CONTROLLER_PACKAGE_NAME is used by system notification, Bluetooth, lockscreen
        val result = PlaybackService.determineAccessLevel(
            controllerPackage = "android.media.session.MediaController",
            ownPackage = ownPackage
        )
        assertEquals(PlaybackService.ControllerAccessLevel.FULL, result)
    }

    // --- Restricted Access Tests ---

    @Test
    fun `Google apps get restricted access`() {
        val googleApps = listOf(
            "com.google.android.googlequicksearchbox", // Google Assistant
            "com.google.android.projection.gearhead", // Android Auto
            "com.google.android.wearable.app", // Wear OS
            "com.google.android.apps.youtube.music" // YouTube Music
        )

        googleApps.forEach { packageName ->
            val result = PlaybackService.determineAccessLevel(
                controllerPackage = packageName,
                ownPackage = ownPackage
            )
            assertEquals("$packageName should get restricted access",
                PlaybackService.ControllerAccessLevel.RESTRICTED, result)
        }
    }

    @Test
    fun `Android system apps get restricted access`() {
        val systemApps = listOf(
            "com.android.systemui", // System UI
            "com.android.bluetooth", // Bluetooth
            "com.android.settings" // Settings
        )

        systemApps.forEach { packageName ->
            val result = PlaybackService.determineAccessLevel(
                controllerPackage = packageName,
                ownPackage = ownPackage
            )
            assertEquals("$packageName should get restricted access",
                PlaybackService.ControllerAccessLevel.RESTRICTED, result)
        }
    }

    @Test
    fun `third-party media controllers get restricted access`() {
        val thirdPartyApps = listOf(
            "com.spotify.music",
            "com.samsung.android.app.soundpicker",
            "de.stohelit.folderplayer",
            "com.some.random.app"
        )

        thirdPartyApps.forEach { packageName ->
            val result = PlaybackService.determineAccessLevel(
                controllerPackage = packageName,
                ownPackage = ownPackage
            )
            assertEquals("$packageName should get restricted access",
                PlaybackService.ControllerAccessLevel.RESTRICTED, result)
        }
    }

    @Test
    fun `unknown package gets restricted access`() {
        val result = PlaybackService.determineAccessLevel(
            controllerPackage = "com.unknown.package",
            ownPackage = ownPackage
        )
        assertEquals(PlaybackService.ControllerAccessLevel.RESTRICTED, result)
    }

    @Test
    fun `empty package name gets restricted access`() {
        val result = PlaybackService.determineAccessLevel(
            controllerPackage = "",
            ownPackage = ownPackage
        )
        assertEquals(PlaybackService.ControllerAccessLevel.RESTRICTED, result)
    }

    // --- Edge Cases ---

    @Test
    fun `package name containing own package but not matching gets restricted`() {
        val result = PlaybackService.determineAccessLevel(
            controllerPackage = "com.albunyaan.tube.malicious",
            ownPackage = ownPackage
        )
        assertEquals(PlaybackService.ControllerAccessLevel.RESTRICTED, result)
    }

    @Test
    fun `different own package name works correctly`() {
        val differentOwnPackage = "com.other.app"
        val result = PlaybackService.determineAccessLevel(
            controllerPackage = differentOwnPackage,
            ownPackage = differentOwnPackage
        )
        assertEquals(PlaybackService.ControllerAccessLevel.FULL, result)
    }
}
