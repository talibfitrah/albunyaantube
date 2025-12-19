package com.albunyaan.tube.ui.favorites

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.albunyaan.tube.data.local.FavoriteVideo
import com.albunyaan.tube.data.local.FavoritesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the Favorites screen.
 * Provides reactive access to the user's favorite videos.
 */
@HiltViewModel
class FavoritesViewModel @Inject constructor(
    private val repository: FavoritesRepository
) : ViewModel() {

    /**
     * UI events for user feedback (errors, confirmations).
     */
    sealed class UiEvent {
        data class Error(val message: String) : UiEvent()
        object FavoriteRemoved : UiEvent()
        object AllFavoritesCleared : UiEvent()
    }

    private val _uiEvents = MutableSharedFlow<UiEvent>()
    val uiEvents: SharedFlow<UiEvent> = _uiEvents.asSharedFlow()

    /**
     * All favorite videos as a StateFlow.
     * Updates automatically when favorites change.
     */
    val favorites: StateFlow<List<FavoriteVideo>> = repository.getAllFavorites()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    /**
     * Remove a video from favorites.
     */
    fun removeFavorite(videoId: String) {
        viewModelScope.launch {
            try {
                repository.removeFavorite(videoId)
                _uiEvents.emit(UiEvent.FavoriteRemoved)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove favorite: $videoId", e)
                _uiEvents.emit(UiEvent.Error("Failed to remove favorite"))
            }
        }
    }

    /**
     * Clear all favorites.
     */
    fun clearAllFavorites() {
        viewModelScope.launch {
            try {
                repository.clearAll()
                _uiEvents.emit(UiEvent.AllFavoritesCleared)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to clear favorites", e)
                _uiEvents.emit(UiEvent.Error("Failed to clear favorites"))
            }
        }
    }

    companion object {
        private const val TAG = "FavoritesViewModel"
    }
}
