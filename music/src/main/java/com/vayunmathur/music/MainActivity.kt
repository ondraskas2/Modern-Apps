package com.vayunmathur.music

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.media3.session.MediaController
import androidx.room.migration.Migration
import com.vayunmathur.library.util.NavKey
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.PermissionsChecker
import com.vayunmathur.library.util.DatabaseViewModel
import com.vayunmathur.library.util.DialogPage
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.buildDatabase
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.music.data.Album
import com.vayunmathur.music.data.Artist
import com.vayunmathur.music.data.Music
import com.vayunmathur.music.data.MusicDatabase
import com.vayunmathur.music.data.Playlist
import com.vayunmathur.music.R
import com.vayunmathur.music.data.MIGRATION_1_2
import com.vayunmathur.music.data.MIGRATION_2_3
import com.vayunmathur.music.ui.AlbumDetailScreen
import com.vayunmathur.music.ui.AlbumScreen
import com.vayunmathur.music.ui.ArtistDetailScreen
import com.vayunmathur.music.ui.ArtistScreen
import com.vayunmathur.music.ui.HomeScreen
import com.vayunmathur.music.ui.PlaylistDetailScreen
import com.vayunmathur.music.ui.PlaylistScreen
import com.vayunmathur.music.ui.SongScreen
import com.vayunmathur.music.ui.dialogs.AddToPlaylistDialog
import kotlinx.serialization.Serializable
import com.vayunmathur.music.util.MusicViewModel
import com.vayunmathur.music.util.MusicViewModelFactory
import com.vayunmathur.music.util.PlaybackManager

class MainActivity : ComponentActivity() {
    var controller: MediaController? = null
    private lateinit var viewModel: DatabaseViewModel
    private val musicViewModel: MusicViewModel by viewModels {
        MusicViewModelFactory(application, viewModel, PlaybackManager.getInstance(this))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val db = buildDatabase<MusicDatabase>(listOf(MIGRATION_1_2, MIGRATION_2_3))
        viewModel = DatabaseViewModel(db,Music::class to db.musicDao(), Album::class to db.albumDao(), Artist::class to db.artistDao(), Playlist::class to db.playlistDao(), matchingDao = db.matchingDao())
        setContent {
            DynamicTheme {
                if(Build.VERSION.SDK_INT >= 33) {
                    PermissionsChecker(
                        arrayOf(Manifest.permission.READ_MEDIA_AUDIO),
                        getString(R.string.grant_audio_permissions)
                    ) {
                        Navigation(viewModel, musicViewModel)
                    }
                } else {
                    PermissionsChecker(
                        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
                        getString(R.string.grant_storage_permissions)
                    ) {
                        Navigation(viewModel, musicViewModel)
                    }
                }
            }
        }
    }
}

@Serializable
sealed interface Route: NavKey {
    @Serializable
    data object Home: Route
    @Serializable
    data object Albums: Route
    @Serializable
    data object Artists: Route
    @Serializable
    data object Playlists: Route
    @Serializable
    data object Song: Route

    @Serializable
    data class AlbumDetail(val albumId: Long): Route

    @Serializable
    data class ArtistDetail(val artistId: Long): Route

    @Serializable
    data class PlaylistDetail(val playlistId: Long): Route

    @Serializable
    data class AddToPlaylistDialog(val musicId: Long): Route
}

@Composable
fun Navigation(viewModel: DatabaseViewModel, musicViewModel: MusicViewModel) {
    val backStack = rememberNavBackStack<Route>(Route.Home)
    MainNavigation(backStack) {
        entry<Route.Home> {
            HomeScreen(backStack, viewModel, musicViewModel)
        }
        entry<Route.Song> {
            SongScreen(backStack, musicViewModel)
        }
        entry<Route.Albums> {
            AlbumScreen(backStack, viewModel, musicViewModel)
        }
        entry<Route.Artists> {
            ArtistScreen(backStack, viewModel, musicViewModel)
        }
        entry<Route.Playlists> {
            PlaylistScreen(backStack, viewModel, musicViewModel)
        }
        entry<Route.AlbumDetail> {
            AlbumDetailScreen(backStack, viewModel, musicViewModel, it.albumId)
        }
        entry<Route.ArtistDetail> {
            ArtistDetailScreen(backStack, viewModel, musicViewModel, it.artistId)
        }
        entry<Route.PlaylistDetail> {
            PlaylistDetailScreen(backStack, viewModel, musicViewModel, it.playlistId)
        }
        entry<Route.AddToPlaylistDialog>(metadata = DialogPage()) {
            AddToPlaylistDialog(backStack, viewModel, musicViewModel, it.musicId)
        }
    }
}
