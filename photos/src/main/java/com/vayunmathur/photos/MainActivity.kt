package com.vayunmathur.photos

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.fragment.app.FragmentActivity
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.PermissionsChecker
import com.vayunmathur.library.util.DatabaseViewModel
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.NavKey
import com.vayunmathur.library.util.buildDatabase
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.photos.data.Photo
import com.vayunmathur.photos.data.PhotoDatabase
import com.vayunmathur.photos.ui.GalleryPage
import com.vayunmathur.photos.ui.MapPage
import com.vayunmathur.photos.ui.PhotoPage
import com.vayunmathur.photos.ui.SecureFolderPage
import com.vayunmathur.photos.ui.TrashPage
import com.vayunmathur.photos.util.GalleryViewModel
import com.vayunmathur.photos.util.GalleryViewModelFactory
import com.vayunmathur.photos.util.ImageLoader
import com.vayunmathur.photos.util.PhotoMapViewModel
import com.vayunmathur.photos.util.PhotoMapViewModelFactory
import com.vayunmathur.photos.util.SecureFolderViewModel
import com.vayunmathur.photos.util.SecureFolderViewModelFactory
import kotlinx.serialization.Serializable
import com.vayunmathur.library.R as LibraryR

val LocalColumnCount = staticCompositionLocalOf<MutableFloatState> {
    error("No LocalColumnCount provided")
}

class MainActivity : FragmentActivity() {
    private lateinit var viewModel: DatabaseViewModel

    private val galleryViewModel: GalleryViewModel by viewModels {
        GalleryViewModelFactory(application, viewModel)
    }
    private val photoMapViewModel: PhotoMapViewModel by viewModels {
        PhotoMapViewModelFactory(application)
    }
    private val secureFolderViewModel: SecureFolderViewModel by viewModels {
        SecureFolderViewModelFactory(application)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val db = buildDatabase<PhotoDatabase>(PhotoDatabase.ALL_MIGRATIONS)
        viewModel = DatabaseViewModel(db, Photo::class to db.photoDao())
        ImageLoader.init(this)
        setContent {
            DynamicTheme {
                val columnCount = rememberSaveable { mutableFloatStateOf(3f) }
                CompositionLocalProvider(LocalColumnCount provides columnCount) {
                    if (Build.VERSION.SDK_INT >= 33) {
                        PermissionsChecker(
                            arrayOf(
                                Manifest.permission.READ_MEDIA_IMAGES,
                                Manifest.permission.READ_MEDIA_VIDEO,
                                Manifest.permission.ACCESS_MEDIA_LOCATION
                            ), getString(R.string.grant_image_video_permissions)
                        ) {
                            Navigation(viewModel, galleryViewModel, photoMapViewModel, secureFolderViewModel)
                        }
                    } else {
                        PermissionsChecker(
                            arrayOf(
                                Manifest.permission.READ_EXTERNAL_STORAGE
                            ), getString(R.string.grant_storage_permission)
                        ) {
                            Navigation(viewModel, galleryViewModel, photoMapViewModel, secureFolderViewModel)
                        }
                    }
                }
            }
        }
    }
}

@Serializable
sealed interface Route: NavKey {
    @Serializable
    data object Gallery: Route

    @Serializable
    data class PhotoPage(val id: Long, val overridePhotosList: List<Photo>?): Route

    @Serializable
    data object Map: Route

    @Serializable
    data object Trash: Route

    @Serializable
    data object SecureFolder: Route
}

@Composable
fun Navigation(
    viewModel: DatabaseViewModel,
    galleryViewModel: GalleryViewModel,
    photoMapViewModel: PhotoMapViewModel,
    secureFolderViewModel: SecureFolderViewModel,
) {
    val backStack = rememberNavBackStack<Route>(Route.Gallery)
    val vaultViewModel by secureFolderViewModel.vaultViewModel.collectAsState()
    val vaultPassword by secureFolderViewModel.vaultPassword.collectAsState()

    MainNavigation(backStack) {
        entry<Route.Gallery> {
            GalleryPage(backStack, viewModel, galleryViewModel, secureFolderViewModel)
        }

        entry<Route.Map> {
            MapPage(backStack, viewModel, photoMapViewModel)
        }

        entry<Route.PhotoPage> {
            PhotoPage(viewModel, photoMapViewModel, it.id, it.overridePhotosList)
        }

        entry<Route.Trash> {
            TrashPage(backStack, viewModel)
        }

        entry<Route.SecureFolder> {
            SecureFolderEntry(backStack, secureFolderViewModel, vaultViewModel, vaultPassword)
        }
    }
}

@Composable
private fun SecureFolderEntry(
    backStack: NavBackStack<Route>,
    secureFolderViewModel: SecureFolderViewModel,
    vaultViewModel: DatabaseViewModel?,
    vaultPassword: String?,
) {
    val activity = LocalContext.current as FragmentActivity
    if (vaultViewModel == null) {
        LaunchedEffect(Unit) {
            secureFolderViewModel.unlock(
                activity,
                onSuccess = { _, _ -> },
                onFailure = { backStack.pop() },
            )
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    } else {
        SecureFolderPage(backStack, vaultViewModel, vaultPassword!!, secureFolderViewModel)
    }
}

private enum class MainRoute(val route: Route, @StringRes val titleRes: Int, val icon: Int) {
    Gallery(Route.Gallery, R.string.label_gallery, R.drawable.gallery_thumbnail_24px),
    Map(Route.Map, R.string.label_map, R.drawable.map_24px),
    Trash(Route.Trash, R.string.label_trash, LibraryR.drawable.delete_24px),
    SecureFolder(Route.SecureFolder, R.string.label_secure_folder, R.drawable.lock_24px)
}

@Composable
fun NavigationBar(currentRoute: Route, backStack: NavBackStack<Route>) {
    ShortNavigationBar {
        MainRoute.entries.forEach {
            ShortNavigationBarItem(it.route == currentRoute, { backStack.add(it.route) }, {
                Icon(painterResource(it.icon), null)
            }, {
                Text(stringResource(it.titleRes))
            })
        }
    }
}
