package com.vayunmathur.library.util

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.mimeTypes
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.platform.LocalContext

/**
 * A modifier that handles external drag and drop of files.
 */
@Composable
fun Modifier.onFileDrop(
    onFilesDropped: (List<Uri>) -> Unit
): Modifier {
    val context = LocalContext.current
    val target = remember(context, onFilesDropped) {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                val dragEvent = event.toAndroidDragEvent()
                val activity = context.findActivity<ComponentActivity>()

                // Request permissions for cross-app drag and drop URIs
                activity?.requestDragAndDropPermissions(dragEvent)

                val clipData = dragEvent.clipData ?: return false
                val uris = mutableListOf<Uri>()
                for (i in 0 until clipData.itemCount) {
                    clipData.getItemAt(i).uri?.let { uris.add(it) }
                }
                if (uris.isNotEmpty()) {
                    onFilesDropped(uris)
                    return true
                }
                return false
            }
        }
    }
    return this.dragAndDropTarget(
        shouldStartDragAndDrop = { event -> event.mimeTypes().isNotEmpty() },
        target = target
    )
}
