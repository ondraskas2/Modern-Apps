package com.vayunmathur.music.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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

@Composable
fun AlbumsTabContent(backStack: NavBackStack<Route>, viewModel: DatabaseViewModel, musicViewModel: MusicViewModel) {
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        SyncWorker.runOnce(context)
        SyncWorker.enqueue(context)
    }

    ListPage<Album, Route, Route.Song>(backStack, viewModel, stringResource(R.string.page_title_music), { Text(it.name) }, {
        Text(it.artistString(viewModel))
    }, {
        Route.AlbumDetail(it)
    }, leadingContent = { music ->
        AlbumArt(music.uri.toUri(), Modifier.size(40.dp))
    }, searchEnabled = true, fab = {
        ShufflePlayFab(viewModel, musicViewModel)
    }, sortOrder = Comparator.comparing { it.name })
}
