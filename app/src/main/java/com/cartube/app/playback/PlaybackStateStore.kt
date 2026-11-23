package com.cartube.app.playback

import com.cartube.app.data.VideoItem
import com.liskovsoft.mediaserviceinterfaces.data.SponsorSegment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PlaybackUiState(
    val currentVideo: VideoItem? = null,
    val queue: List<VideoItem> = emptyList(),
    val currentIndex: Int = -1,
    val relatedVideos: List<VideoItem> = emptyList(),
    val sponsorSegments: List<SponsorSegment> = emptyList(),
    val isLoading: Boolean = false,
    val isPlaying: Boolean = false,
    val isAudioOnly: Boolean = false,
    val actualVideoHeight: Int = 0,
    val statusNotice: String? = null,
    val errorMessage: String? = null
)

object PlaybackStateStore {
    private val _state = MutableStateFlow(PlaybackUiState())
    val state: StateFlow<PlaybackUiState> = _state.asStateFlow()

    fun update(transform: (PlaybackUiState) -> PlaybackUiState) {
        _state.value = transform(_state.value)
    }

    fun clearNotice() {
        _state.value = _state.value.copy(statusNotice = null)
    }
}
