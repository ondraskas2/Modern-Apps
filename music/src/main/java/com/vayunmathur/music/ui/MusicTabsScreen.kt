package com.vayunmathur.music.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FlexibleBottomAppBar
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.util.DatabaseViewModel
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.music.R
import com.vayunmathur.music.Route
import com.vayunmathur.music.util.MusicViewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Hosts the four main tabs (Songs / Albums / Artists / Playlists) inside a
 * single HorizontalPager so the user can swipe between them, and tapping a
 * bottom-nav item smoothly slides to the destination instead of replacing the
 * screen.
 *
 * Tab selection lives in [pagerState] (local Compose state), NOT in the nav
 * backstack — so deep navigation (e.g. tap an album → AlbumDetail → back)
 * returns the user to whatever tab they were on, with the pager's scroll
 * position preserved.
 *
 * The now-playing controls and the tab bar are hoisted to this screen's
 * Scaffold so they remain visible across all four tabs.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun MusicTabsScreen(
    backStack: NavBackStack<Route>,
    viewModel: DatabaseViewModel,
    musicViewModel: MusicViewModel,
) {
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { 4 })
    val scope = rememberCoroutineScope()

    val tabs = listOf(
        Triple(stringResource(R.string.nav_home), R.drawable.baseline_library_music_24, 0),
        Triple(stringResource(R.string.nav_albums), R.drawable.baseline_album_24, 1),
        Triple(stringResource(R.string.nav_artists), R.drawable.outline_person_24, 2),
        Triple(stringResource(R.string.nav_playlists), R.drawable.baseline_library_music_24, 3),
    )

    Scaffold(
        bottomBar = {
            Column(Modifier.fillMaxWidth()) {
                PlayingBottomBar(musicViewModel, backStack)
                FlexibleBottomAppBar {
                    tabs.forEach { (name, icon, index) ->
                        NavigationBarItem(
                            selected = pagerState.currentPage == index,
                            onClick = {
                                if (pagerState.currentPage != index) {
                                    scope.launch { pagerState.animateScrollToPage(index) }
                                }
                            },
                            icon = { Icon(painterResource(icon), null) },
                            label = { Text(name) },
                        )
                    }
                }
            }
        }
    ) { padding ->
        // Inner pages each have their own Scaffold (ListPage owns a TopAppBar
        // and consumes the top system inset). Only the BOTTOM space taken by
        // this Scaffold's bottomBar needs to be forwarded — passing the full
        // PaddingValues here was adding the status-bar inset twice and pushing
        // the TopAppBar down.
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.padding(bottom = padding.calculateBottomPadding()),
        ) { page ->
            when (page) {
                0 -> HomeTabContent(backStack, viewModel, musicViewModel)
                1 -> AlbumsTabContent(backStack, viewModel, musicViewModel)
                2 -> ArtistsTabContent(backStack, viewModel, musicViewModel)
                3 -> PlaylistsTabContent(backStack, viewModel, musicViewModel)
            }
        }
    }
}
