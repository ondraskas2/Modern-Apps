package com.vayunmathur.music.util

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import com.vayunmathur.library.util.DatabaseViewModel
import com.vayunmathur.music.data.Music
import com.vayunmathur.music.data.Playlist
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for the Music app.
 *
 * Owns:
 *  - playlist editing actions (create, rename, add track) routed through [DatabaseViewModel]
 *
 * Mirrors (does not duplicate the source of truth):
 *  - [PlaybackManager] state and playback actions. The PlaybackManager remains the
 *    sole owner of player state; this VM just re-exposes its StateFlows and delegates
 *    actions so composables collect a single VM instead of grabbing the singleton.
 *
 * [PlaybackService] is intentionally not wrapped — it's an Android Service and stays
 * separate from the composable layer.
 */
class MusicViewModel(
    application: Application,
    private val databaseViewModel: DatabaseViewModel,
    private val playbackManager: PlaybackManager,
) : AndroidViewModel(application) {

    // --- PlaybackManager state mirror (read-only StateFlow pass-through) ---
    val isPlaying: StateFlow<Boolean> = playbackManager.isPlaying
    val currentPosition: StateFlow<Long> = playbackManager.currentPosition
    val duration: StateFlow<Long> = playbackManager.duration
    val shuffleMode: StateFlow<Boolean> = playbackManager.shuffleMode
    val repeatMode: StateFlow<Int> = playbackManager.repeatMode
    val currentMediaItem: StateFlow<MediaItem?> = playbackManager.currentMediaItem
    val currentSource: StateFlow<String?> = playbackManager.currentSource
    val currentSourceName: StateFlow<String?> = playbackManager.currentSourceName

    // --- PlaybackManager action delegates ---
    fun playSong(songs: List<Music>, startWithIndex: Int, sourceId: String? = null, sourceName: String? = null) =
        playbackManager.playSong(songs, startWithIndex, sourceId, sourceName)

    fun playShuffled(songs: List<Music>, sourceId: String? = null, sourceName: String? = null) =
        playbackManager.playShuffled(songs, sourceId, sourceName)

    fun togglePlayPause() = playbackManager.togglePlayPause()
    fun seekTo(pos: Long) = playbackManager.seekTo(pos)
    fun skipNext() = playbackManager.skipNext()
    fun skipPrevious() = playbackManager.skipPrevious()
    fun toggleShuffle() = playbackManager.toggleShuffle()
    fun toggleRepeat() = playbackManager.toggleRepeat()

    // --- Playlist editing actions ---
    /** Creates a new playlist with [name] off the main thread. */
    fun createPlaylist(name: String) {
        viewModelScope.launch {
            databaseViewModel.upsert(Playlist(name = name))
        }
    }

    /** Persists a renamed [playlist] off the main thread. */
    fun renamePlaylist(playlist: Playlist, newName: String) {
        viewModelScope.launch {
            databaseViewModel.upsert(playlist.copy(name = newName))
        }
    }

    /** Adds [musicId] to [playlistId] off the main thread, invoking [onDone] on completion. */
    fun addMusicToPlaylist(playlistId: Long, musicId: Long, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            databaseViewModel.match<Playlist, Music>(playlistId, musicId)
            onDone()
        }
    }
}

/** Factory for constructing [MusicViewModel] with shared dependencies. */
class MusicViewModelFactory(
    private val application: Application,
    private val databaseViewModel: DatabaseViewModel,
    private val playbackManager: PlaybackManager,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(MusicViewModel::class.java)) {
            "Unexpected ViewModel class: $modelClass"
        }
        return MusicViewModel(application, databaseViewModel, playbackManager) as T
    }
}
