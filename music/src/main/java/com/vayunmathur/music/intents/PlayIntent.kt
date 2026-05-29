package com.vayunmathur.music.intents

import com.vayunmathur.library.intents.music.PlayMusicData
import com.vayunmathur.library.util.AssistantIntent
import com.vayunmathur.library.util.buildDatabase
import com.vayunmathur.music.data.MusicDatabase
import com.vayunmathur.music.data.TYPE_MUSIC_PLAYLIST
import com.vayunmathur.music.util.PlaybackManager
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.serializer

@OptIn(InternalSerializationApi::class)
class PlayIntent: AssistantIntent<PlayMusicData, Unit>(serializer<PlayMusicData>(), serializer<Unit>()) {

    override suspend fun performCalculation(input: PlayMusicData) {
        val db = buildDatabase<MusicDatabase>()
        val pm = PlaybackManager.getInstance(this)

        val allMusic = db.musicDao().getAll()

        val songsToPlay = when (input.type) {
            "song" -> allMusic.filter { it.id == input.id }
            "album" -> allMusic.filter { it.albumId == input.id }
            "artist" -> allMusic.filter { it.artistId == input.id }
            "playlist" -> {
                // Playlist > Music in the type-code ordering → playlist is right.
                val songIds = db.matchingDao().getFromRight(input.id, TYPE_MUSIC_PLAYLIST)
                allMusic.filter { songIds.contains(it.id) }
            }
            else -> emptyList()
        }

        if (songsToPlay.isNotEmpty()) {
            pm.playSong(songsToPlay, 0)
        }
    }
}
