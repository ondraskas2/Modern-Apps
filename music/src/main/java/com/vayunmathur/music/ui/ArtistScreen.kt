package com.vayunmathur.music.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.ui.ListPage
import com.vayunmathur.library.util.DatabaseViewModel
import com.vayunmathur.music.util.AlbumArt
import com.vayunmathur.music.util.MusicViewModel
import com.vayunmathur.music.util.SyncWorker
import com.vayunmathur.music.R
import com.vayunmathur.music.Route
import com.vayunmathur.music.data.Album
import com.vayunmathur.music.data.Artist

@Composable
fun ArtistsTabContent(backStack: NavBackStack<Route>, viewModel: DatabaseViewModel, musicViewModel: MusicViewModel) {
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        SyncWorker.runOnce(context)
        SyncWorker.enqueue(context)
    }

    ListPage<Artist, Route, Route.Song>(backStack, viewModel, stringResource(R.string.page_title_music), { Text(it.name) }, {
    }, {
        Route.ArtistDetail(it)
    }, leadingContent = { artist ->
        val albums by viewModel.getMatchesState<Artist, Album>(artist.id)
        val allAlbums by viewModel.data<Album>().collectAsState()
        val albumsUris by remember { derivedStateOf { allAlbums.filter { it.id in albums }.map { it.uri.toUri() } } }
        AlbumArt(albumsUris, Modifier.size(40.dp))
    }, searchEnabled = true, fab = {
        ShufflePlayFab(viewModel, musicViewModel)
    }, sortOrder = Comparator.comparing { it.name })
}
