package com.albunyaan.tube.policy

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Stores whether the user has accepted the offline downloads EULA policy.
 */
class EulaManager(
    private val dataStore: DataStore<Preferences>
) {

    val isAccepted: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_ACCEPTED] ?: false
    }

    suspend fun setAccepted(accepted: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_ACCEPTED] = accepted
        }
    }

    private companion object {
        val KEY_ACCEPTED = booleanPreferencesKey("downloads_eula_accepted")
    }
}
