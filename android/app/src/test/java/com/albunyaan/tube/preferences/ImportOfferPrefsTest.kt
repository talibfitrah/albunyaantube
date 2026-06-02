package com.albunyaan.tube.preferences

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * B13: Unit tests for the [SettingsPreferences] import-offer gate.
 *
 * Uses the same Robolectric + temp-DataStore pattern as [LastInstallAttemptTest].
 * The SettingsPreferences instance is constructed with a real ApplicationContext
 * (Robolectric) so the `preferencesDataStore` delegate resolves correctly —
 * BUT we need a separate DataStore scope to cancel before TemporaryFolder
 * deletes the backing file.
 *
 * NOTE: SettingsPreferences uses a Context extension property for its DataStore
 * (`private val Context.dataStore by preferencesDataStore(name = "settings")`).
 * That delegate is process-wide and backed by Robolectric's context, so
 * [shouldShowImportOffer] reads and writes the same store as [importOfferShown].
 * No mock needed — we test the real SettingsPreferences with an in-process store.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class ImportOfferPrefsTest {

    private lateinit var subject: SettingsPreferences

    @Before
    fun setUp() {
        // SettingsPreferences uses the Context extension delegate which ties the
        // DataStore to the Robolectric ApplicationContext by name "settings".
        subject = SettingsPreferences(RuntimeEnvironment.getApplication())
        // Reset the import-offer flag before each test so tests are independent
        // of execution order. The DataStore extension is process-wide in Robolectric
        // so state persists across tests in the same process.
        runBlocking { subject.setImportOfferShown(false) }
    }

    @After
    fun tearDown() {
        // Leave the store clean after each test (belt-and-suspenders with setUp reset).
        runBlocking { subject.setImportOfferShown(false) }
    }

    // -------------------------------------------------------------------------
    // shouldShowImportOffer — gate logic
    // -------------------------------------------------------------------------

    @Test
    fun `shouldShowImportOffer returns true on a fresh store`() = runTest {
        assertTrue(subject.shouldShowImportOffer())
    }

    @Test
    fun `shouldShowImportOffer returns false after setImportOfferShown`() = runTest {
        subject.setImportOfferShown()
        assertFalse(subject.shouldShowImportOffer())
    }

    @Test
    fun `setImportOfferShown true then false re-enables offer`() = runTest {
        subject.setImportOfferShown(true)
        subject.setImportOfferShown(false)
        assertTrue(subject.shouldShowImportOffer())
    }

    @Test
    fun `shouldShowImportOffer idempotent — second call after shown still false`() = runTest {
        subject.setImportOfferShown()
        assertFalse(subject.shouldShowImportOffer())
        assertFalse(subject.shouldShowImportOffer()) // second read must not flip
    }

    // -------------------------------------------------------------------------
    // IMPORT_OFFER_SHOWN_KEY key correctness
    // -------------------------------------------------------------------------

    @Test
    fun `IMPORT_OFFER_SHOWN_KEY constant has expected name`() {
        // Guards against accidental rename that would lose persisted state for
        // existing users (DataStore key names are the migration identity).
        val key = SettingsPreferences.IMPORT_OFFER_SHOWN_KEY
        assertTrue(
            "Key name must be 'import_offer_shown' for DataStore migration safety",
            key.name == "import_offer_shown",
        )
    }
}
