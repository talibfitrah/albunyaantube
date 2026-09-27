package com.albunyaan.tube.update

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verifyNoInteractions

/**
 * The Google Play flavor MUST NOT ship a self-updater: Play's Device and Network
 * Abuse policy forbids an app that downloads and installs an APK on its own.
 *
 * These tests only exist in the `play` variant, and they only compile if the play
 * source set supplies an [UpdateGateway] that never reaches the network and never
 * touches the host Activity. If someone ever binds the real sideload updater into
 * the play flavor, this file stops compiling (UpdatePromptFlow is not in the play
 * source set) or `hasUpdater` flips and the first test fails.
 */
class NoUpdateGatewayTest {

    @Test
    fun `play build reports that it has no updater`() {
        assertFalse(NoUpdateGateway().hasUpdater)
    }

    @Test
    fun `play build never reports an available update`() = runTest {
        assertNull(NoUpdateGateway().checkForUpdate())
    }

    @Test
    fun `runCheck never touches the host activity`() {
        // The sideload implementation shows dialogs and toasts on this Activity.
        // The play implementation must be inert — no UI, no download, no install.
        val activity = mock<android.app.Activity>()
        val owner = mock<androidx.lifecycle.LifecycleOwner>()

        NoUpdateGateway().runCheck(activity, owner)

        verifyNoInteractions(activity)
        verifyNoInteractions(owner)
    }
}
