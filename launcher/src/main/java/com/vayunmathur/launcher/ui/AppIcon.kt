package com.vayunmathur.launcher.ui

import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppIcon(
    name: String,
    icon: Drawable,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    modifier: Modifier = Modifier,
    labelColor: Color = Color.White,
    badgeCount: Int = 0
) {
    val bitmap = remember(icon) {
        val w = icon.intrinsicWidth.coerceAtLeast(1)
        val h = icon.intrinsicHeight.coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        icon.setBounds(0, 0, w, h)
        icon.draw(canvas)
        bmp.asImageBitmap()
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        Box {
            Image(
                bitmap = bitmap,
                contentDescription = name,
                modifier = Modifier.size(56.dp)
            )
            if (badgeCount > 0) {
                val dotColor = MaterialTheme.colorScheme.primaryContainer
                Canvas(
                    modifier = Modifier
                        .size(8.dp)
                        .align(Alignment.TopEnd)
                ) {
                    drawCircle(color = dotColor)
                }
            }
        }
        Spacer(Modifier.height(7.dp))
        Text(
            text = name,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            color = labelColor,
            style = TextStyle(
                shadow = Shadow(
                    color = Color(0xB0000000),
                    offset = Offset(0.5f, 0.5f),
                    blurRadius = 2f
                )
            ),
            modifier = Modifier.fillMaxWidth()
        )
    }
}
