package com.albunyaan.tube.ui.player

import androidx.lifecycle.ViewModel
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.video.VideoSize
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class PlayerViewModel : ViewModel() {

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state

    val playerListener = object : Player.Listener {
        override fun onVideoSizeChanged(videoSize: VideoSize) {
            _state.value = _state.value.copy(hasVideoTrack = videoSize != VideoSize.UNKNOWN)
        }
    }

    fun setAudioOnly(audioOnly: Boolean) {
        _state.value = _state.value.copy(audioOnly = audioOnly)
    }
}

data class PlayerState(
    val audioOnly: Boolean = false,
    val hasVideoTrack: Boolean = true
)
