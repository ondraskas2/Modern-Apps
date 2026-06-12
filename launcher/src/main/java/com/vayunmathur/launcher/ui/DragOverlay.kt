package com.vayunmathur.launcher.ui

import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

@Composable
fun DragOverlay(
    icon: Drawable,
    offset: Offset,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current

    val bitmap = androidx.compose.runtime.remember(icon) {
        val w = icon.intrinsicWidth.coerceAtLeast(1)
        val h = icon.intrinsicHeight.coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        icon.setBounds(0, 0, w, h)
        icon.draw(canvas)
        bmp.asImageBitmap()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .zIndex(100f)
    ) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            modifier = Modifier
                .size(64.dp)
                .offset(
                    x = with(density) { (offset.x - 32.dp.toPx()).toDp() },
                    y = with(density) { (offset.y - 32.dp.toPx()).toDp() }
                ),
            alpha = 0.9f
        )
    }
}
