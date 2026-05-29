package com.vayunmathur.music.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.ui.IconPlay
import com.vayunmathur.library.util.DatabaseViewModel
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.music.util.AlbumArt
import com.vayunmathur.music.util.MusicViewModel
import com.vayunmathur.music.R
import com.vayunmathur.music.Route
import com.vayunmathur.music.data.Music
import com.vayunmathur.music.data.Playlist
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistDetailScreen(backStack: NavBackStack<Route>, viewModel: DatabaseViewModel, musicViewModel: MusicViewModel, playlistId: Long) {
    val playlist by viewModel.getState<Playlist>(playlistId)
    val allMusic by viewModel.data<Music>().collectAsState()
    var musicInPlaylist by remember { mutableStateOf(emptyList<Music>()) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(allMusic, playlistId) {
        val musicIds = viewModel.getMatches<Playlist, Music>(playlistId)
        musicInPlaylist = allMusic.filter { musicIds.contains(it.id) }
    }

    val context = LocalContext.current
    val currentMediaItem by musicViewModel.currentMediaItem.collectAsState()
    val currentSource by musicViewModel.currentSource.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { },
                navigationIcon = { IconNavigation(backStack) }
            )
        },
        bottomBar = { PlayingBottomBar(musicViewModel, backStack) },
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
        ) {
            item {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(Modifier
                        .size(260.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color.DarkGray),
                        contentAlignment = Alignment.Center
                    ) {
                        if (musicInPlaylist.isEmpty()) {
                            Icon(painterResource(com.vayunmathur.music.R.drawable.baseline_library_music_24), null, Modifier.size(100.dp))
                        } else {
                            AlbumArt(musicInPlaylist.map { it.uri.toUri() }, Modifier.fillMaxSize())
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    ListItem({
                        var showRenameDialog by remember { mutableStateOf(false) }
                        var newName by remember(playlist.name) { mutableStateOf(playlist.name) }
                        Text(playlist.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.clickable {
                            showRenameDialog = true
                        })

                        if (showRenameDialog) {
                            AlertDialog(
                                onDismissRequest = { showRenameDialog = false },
                                title = { Text(stringResource(R.string.dialog_rename_playlist)) },
                                text = {
                                    TextField(value = newName, onValueChange = { newName = it })
                                },
                                confirmButton = {
                                    TextButton(onClick = {
                                        musicViewModel.renamePlaylist(playlist, newName)
                                        showRenameDialog = false
                                    }) {
                                        Text(stringResource(R.string.dialog_rename))
                                    }
                                },
                                dismissButton = {
                                    TextButton(onClick = { showRenameDialog = false }) {
                                        Text(stringResource(R.string.dialog_cancel))
                                    }
                                }
                            )
                        }
                    }, Modifier, {Text(stringResource(R.string.label_playlist))}, {
                        Text(stringResource(R.string.num_songs_format, musicInPlaylist.size))
                    })
                }
            }

            // Action Buttons
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = {
                            musicViewModel.playSong(musicInPlaylist, 0, sourceId = "playlist_$playlistId", sourceName = playlist.name)
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.15f)),
                        shape = RoundedCornerShape(50.dp),
                        contentPadding = PaddingValues(vertical = 12.dp)
                    ) {
                        IconPlay(tint = Color.White)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.label_play), color = Color.White)
                    }

                    Button(
                        onClick = {
                            musicViewModel.playShuffled(musicInPlaylist, sourceId = "playlist_$playlistId", sourceName = playlist.name)
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                        shape = RoundedCornerShape(50.dp),
                        contentPadding = PaddingValues(vertical = 12.dp)
                    ) {
                        Icon(painterResource(com.vayunmathur.music.R.drawable.ic_shuffle), contentDescription = null, tint = Color.Black)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.label_shuffle), color = Color.Black)
                    }
                }
            }

            // Track List
            itemsIndexed(musicInPlaylist) { idx, music ->
                val isPlaying = currentMediaItem?.mediaId == music.id.toString() && currentSource == "playlist_$playlistId"
                ListItem(
                    headlineContent = {
                        Text(
                            text = music.title,
                            color = if (isPlaying) MaterialTheme.colorScheme.primary else Color.Unspecified,
                            fontWeight = if (isPlaying) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isPlaying) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                        .clickable {
                            musicViewModel.playSong(musicInPlaylist, idx, sourceId = "playlist_$playlistId", sourceName = playlist.name)
                        },
                    trailingContent = {
                        IconButton({
                            scope.launch {
                                viewModel.unmatch<Playlist, Music>(playlistId, music.id)
                                val musicIds = viewModel.getMatches<Playlist, Music>(playlistId)
                                musicInPlaylist = allMusic.filter { musicIds.contains(it.id) }
                            }
                        }) {
                            IconClose()
                        }
                    },
                    leadingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (isPlaying) {
                                Icon(
                                    painter = painterResource(com.vayunmathur.library.R.drawable.outline_play_arrow_24),
                                    contentDescription = "Playing",
                                    modifier = Modifier
                                        .size(24.dp)
                                        .padding(end = 8.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                            AlbumArt(music.uri.toUri(), Modifier.size(48.dp))
                        }
                    }
                )
            }
        }
    }
}
