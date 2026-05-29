package com.vayunmathur.music.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.DatabaseViewModel
import com.vayunmathur.music.Route
import com.vayunmathur.music.data.Album
import com.vayunmathur.music.data.Music
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
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.ui.IconPlay
import com.vayunmathur.music.util.AlbumArt
import com.vayunmathur.music.util.MusicViewModel
import com.vayunmathur.music.util.AddToPlaylistButton
import com.vayunmathur.music.R
import com.vayunmathur.music.util.formatDuration

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumDetailScreen(backStack: NavBackStack<Route>, viewModel: DatabaseViewModel, musicViewModel: MusicViewModel, albumId: Long) {
    val album by viewModel.getState<Album>(albumId)
    val allMusic by viewModel.data<Music>().collectAsState()
    val musicInAlbum = remember(allMusic, albumId) {
        allMusic.filter { it.albumId == albumId }.sortedBy { it.trackNumber }
    }
    val totalDurationMs = remember(musicInAlbum) {
        musicInAlbum.sumOf { it.duration }
    }

    val albumYear = remember(musicInAlbum) {
        val extractedYear = musicInAlbum.firstOrNull()?.year ?: 0
        if (extractedYear > 0) extractedYear.toString() else "Unknown Year"
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
            // Header: Album Art
            item {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    AlbumArt(album.uri.toUri(), Modifier
                        .size(260.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color.DarkGray))

                    Spacer(modifier = Modifier.height(24.dp))

                    ListItem({
                        Text(album.name, style = MaterialTheme.typography.titleLarge)
                    }, Modifier, {Text(stringResource(R.string.label_album))}, {
                        Text(stringResource(R.string.album_info_format, album.artistString(viewModel), albumYear, musicInAlbum.size, formatDuration(totalDurationMs)))
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
                            musicViewModel.playSong(musicInAlbum, 0, sourceId = "album_$albumId", sourceName = album.name)
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
                            musicViewModel.playShuffled(musicInAlbum, sourceId = "album_$albumId", sourceName = album.name)
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

            // Track List Header
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.label_songs),
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            // Track Items
            itemsIndexed(musicInAlbum) { idx, music ->
                val isPlaying = currentMediaItem?.mediaId == music.id.toString() && currentSource == "album_$albumId"
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
                            musicViewModel.playSong(musicInAlbum, idx, sourceId = "album_$albumId", sourceName = album.name)
                        },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(formatDuration(music.duration))
                            AddToPlaylistButton(backStack, music)
                        }
                    },
                    leadingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (isPlaying) {
                                Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) {
                                    Icon(
                                        painter = painterResource(com.vayunmathur.library.R.drawable.outline_play_arrow_24),
                                        contentDescription = "Playing",
                                        modifier = Modifier.size(20.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            } else {
                                Text(
                                    text = music.trackNumber.toString(),
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 16.sp,
                                    modifier = Modifier.width(28.dp),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                            }

                            Spacer(modifier = Modifier.width(8.dp))

                            AlbumArt(music.uri.toUri(), Modifier.size(48.dp))
                        }
                    }
                )
            }
        }
    }
}