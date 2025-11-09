package com.albunyaan.tube.data.filters

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

class FilterManager(
    private val dataStore: DataStore<Preferences>,
    private val scope: CoroutineScope
) {

    private val _state = MutableStateFlow(FilterState())
    val state: StateFlow<FilterState> = _state.asStateFlow()

    init {
        scope.launch {
            dataStore.data
                .catch { emit(emptyPreferences()) }
                .collect { prefs ->
                    _state.value = FilterState(
                        category = prefs[KEY_CATEGORY],
                        videoLength = prefs[KEY_LENGTH]?.let { enumOrNull<VideoLength>(it) } ?: VideoLength.ANY,
                        publishedDate = prefs[KEY_DATE]?.let { enumOrNull<PublishedDate>(it) } ?: PublishedDate.ANY,
                        sortOption = prefs[KEY_SORT]?.let { enumOrNull<SortOption>(it) } ?: SortOption.DEFAULT
                    )
                }
        }
    }

    fun setCategory(category: String?) {
        scope.launch {
            dataStore.edit { prefs ->
                if (category.isNullOrEmpty()) prefs.remove(KEY_CATEGORY) else prefs[KEY_CATEGORY] = category
            }
        }
    }

    fun setVideoLength(length: VideoLength) {
        scope.launch { dataStore.edit { it[KEY_LENGTH] = length.name } }
    }

    fun setPublishedDate(date: PublishedDate) {
        scope.launch { dataStore.edit { it[KEY_DATE] = date.name } }
    }

    fun setSortOption(sort: SortOption) {
        scope.launch { dataStore.edit { it[KEY_SORT] = sort.name } }
    }

    suspend fun clearAll() {
        dataStore.edit { prefs ->
            prefs.remove(KEY_CATEGORY)
            prefs.remove(KEY_LENGTH)
            prefs.remove(KEY_DATE)
            prefs.remove(KEY_SORT)
        }
    }

    companion object {
        private val KEY_CATEGORY = stringPreferencesKey("filter_category")
        private val KEY_LENGTH = stringPreferencesKey("filter_length")
        private val KEY_DATE = stringPreferencesKey("filter_date")
        private val KEY_SORT = stringPreferencesKey("filter_sort")

        private fun <T : Enum<T>> enumOrNull(name: String, values: Array<T>): T? = values.firstOrNull { it.name == name }

        private inline fun <reified T : Enum<T>> enumOrNull(name: String): T? = enumOrNull(name, enumValues<T>())
    }
}

