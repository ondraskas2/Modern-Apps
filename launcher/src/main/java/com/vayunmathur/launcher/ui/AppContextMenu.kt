package com.vayunmathur.launcher.ui

import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Process
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties

@Composable
fun AppContextMenu(
    expanded: Boolean,
    packageName: String,
    onDismiss: () -> Unit,
    onRemove: () -> Unit
) {
    if (!expanded) return

    val context = LocalContext.current
    val launcherApps = remember {
        context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
    }

    val shortcuts = remember(packageName) {
        try {
            val query = LauncherApps.ShortcutQuery()
                .setPackage(packageName)
                .setQueryFlags(
                    LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or
                    LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC
                )
            launcherApps.getShortcuts(query, Process.myUserHandle())
                ?.take(4) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    Popup(
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true)
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 8.dp,
            shadowElevation = 8.dp,
            modifier = Modifier.width(216.dp)
        ) {
            Column(modifier = Modifier.padding(vertical = 8.dp)) {
                if (shortcuts.isNotEmpty()) {
                    shortcuts.forEach { shortcut ->
                        ShortcutRow(
                            shortcut = shortcut,
                            launcherApps = launcherApps,
                            context = context,
                            onDismiss = onDismiss
                        )
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                }

                MenuActionRow(
                    text = "App Info",
                    icon = {
                        Icon(Icons.Outlined.Info, contentDescription = null,
                            modifier = Modifier.size(20.dp))
                    },
                    onClick = {
                        onDismiss()
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.parse("package:$packageName")
                            }
                        )
                    }
                )
                MenuActionRow(
                    text = "Uninstall",
                    icon = {
                        Icon(Icons.Outlined.Delete, contentDescription = null,
                            modifier = Modifier.size(20.dp))
                    },
                    onClick = {
                        onDismiss()
                        context.startActivity(
                            Intent(Intent.ACTION_DELETE).apply {
                                data = Uri.parse("package:$packageName")
                            }
                        )
                    }
                )
                MenuActionRow(
                    text = "Remove",
                    icon = {
                        Icon(Icons.Outlined.Close, contentDescription = null,
                            modifier = Modifier.size(20.dp))
                    },
                    onClick = {
                        onDismiss()
                        onRemove()
                    }
                )
            }
        }
    }
}

@Composable
private fun ShortcutRow(
    shortcut: ShortcutInfo,
    launcherApps: LauncherApps,
    context: Context,
    onDismiss: () -> Unit
) {
    val icon: Drawable? = remember(shortcut) {
        try {
            launcherApps.getShortcutIconDrawable(
                shortcut, context.resources.displayMetrics.densityDpi
            )
        } catch (_: Exception) { null }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                onDismiss()
                try { launcherApps.startShortcut(shortcut, null, null) }
                catch (_: Exception) { }
            }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            val bmp = remember(icon) {
                val w = icon.intrinsicWidth.coerceAtLeast(1)
                val h = icon.intrinsicHeight.coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(bitmap)
                icon.setBounds(0, 0, w, h)
                icon.draw(canvas)
                bitmap.asImageBitmap()
            }
            Image(bitmap = bmp, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
        }
        Text(
            text = shortcut.shortLabel?.toString() ?: "",
            style = MaterialTheme.typography.bodyMedium,
            fontSize = 14.sp
        )
    }
}

@Composable
private fun MenuActionRow(
    text: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        icon()
        Spacer(Modifier.width(16.dp))
        Text(text = text, fontSize = 14.sp)
    }
}
