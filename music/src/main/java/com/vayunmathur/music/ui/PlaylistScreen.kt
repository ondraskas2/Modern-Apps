package com.vayunmathur.music.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.ListPage
import com.vayunmathur.library.util.DatabaseViewModel
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.music.util.AlbumArt
import com.vayunmathur.music.util.MusicViewModel
import com.vayunmathur.music.util.SyncWorker
import com.vayunmathur.music.R
import com.vayunmathur.music.Route
import com.vayunmathur.music.data.Music
import com.vayunmathur.music.data.Playlist

@Composable
fun PlaylistsTabContent(backStack: NavBackStack<Route>, viewModel: DatabaseViewModel, musicViewModel: MusicViewModel) {
    val context = LocalContext.current
    val resources = LocalResources.current

    LaunchedEffect(Unit) {
        SyncWorker.runOnce(context)
        SyncWorker.enqueue(context)
    }

    ListPage<Playlist, Route, Route.Song>(backStack, viewModel, stringResource(R.string.page_title_playlists), { Text(it.name) }, {
    }, {
        Route.PlaylistDetail(it)
    }, leadingContent = { playlist ->
        val songIds by viewModel.getMatchesState<Playlist, Music>(playlist.id)
        val allMusic by viewModel.data<Music>().collectAsState()
        val musicUris = allMusic.filter { it.id in songIds }.map { it.uri.toUri() }
        AlbumArt(musicUris, Modifier.size(40.dp))
    }, searchEnabled = true, fab = {
        ShufflePlayFab(viewModel, musicViewModel)
    }, sortOrder = Comparator.comparing { it.name }, otherActions = {
        IconButton(onClick = {
            musicViewModel.createPlaylist(resources.getString(R.string.new_playlist))
        }) {
            IconAdd()
        }
    })
}
