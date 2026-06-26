package com.vayunmathur.music.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.ListPage
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.music.util.AlbumArt
import com.vayunmathur.music.util.MusicViewModel
import com.vayunmathur.music.R
import com.vayunmathur.music.Route
import com.vayunmathur.music.data.Playlist
import com.vayunmathur.music.ui.dialogs.CreatePlaylistDialog

@Composable
fun PlaylistsTabContent(backStack: NavBackStack<Route>, musicViewModel: MusicViewModel) {
    val playlists by musicViewModel.playlists.collectAsState()

    ListPage<Playlist, Route, Route.Song>(backStack, playlists, stringResource(R.string.page_title_playlists), { Text(it.name) }, {
    }, {
        Route.PlaylistDetail(it)
    }, leadingContent = { playlist ->
        val songIds by musicViewModel.matchedMusicForPlaylist(playlist.id)
        val allMusic by musicViewModel.music.collectAsState()
        val musicUris = allMusic.filter { it.id in songIds }.map { it.uri.toUri() }
        AlbumArt(musicUris, Modifier.size(40.dp))
    }, searchEnabled = true, fab = {
        NewPlaylistFab(musicViewModel)
        ShufflePlayFab(musicViewModel)
    }, sortOrder = Comparator.comparing { it.name })
}

@Composable
fun NewPlaylistFab(musicViewModel: MusicViewModel) {
    var showDialog by remember { mutableStateOf(false) }

    FloatingActionButton(onClick = { showDialog = true }) {
        IconAdd()
    }

    if (showDialog) {
        CreatePlaylistDialog(
            onDismiss = { showDialog = false },
            onCreate = { name ->
                musicViewModel.createPlaylist(name)
                showDialog = false
            }
        )
    }
}
