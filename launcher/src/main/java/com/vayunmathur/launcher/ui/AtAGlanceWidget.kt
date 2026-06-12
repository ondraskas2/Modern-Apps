package com.vayunmathur.launcher.ui

import android.Manifest
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun AtAGlanceWidget(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val now = remember { LocalDate.now() }

    val dateString = remember {
        now.format(DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.getDefault()))
    }

    val nextEvent = remember {
        try {
            if (context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED) {
                val nowMillis = System.currentTimeMillis()
                val cursor = context.contentResolver.query(
                    CalendarContract.Events.CONTENT_URI,
                    arrayOf(CalendarContract.Events.TITLE, CalendarContract.Events.DTSTART),
                    "${CalendarContract.Events.DTSTART} >= ?",
                    arrayOf(nowMillis.toString()),
                    "${CalendarContract.Events.DTSTART} ASC LIMIT 1"
                )
                cursor?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                }
            } else null
        } catch (_: Exception) { null }
    }

    val shadowStyle = TextStyle(
        shadow = Shadow(
            color = Color.Black.copy(alpha = 0.8f),
            offset = Offset(1f, 1.5f),
            blurRadius = 6f
        )
    )

    Column(modifier = modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
        Text(
            text = dateString,
            style = MaterialTheme.typography.headlineSmall.merge(shadowStyle),
            color = Color.White,
            fontWeight = FontWeight.Bold
        )
        nextEvent?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium.merge(shadowStyle),
                color = Color.White.copy(alpha = 0.9f),
                fontWeight = FontWeight.Medium,
                maxLines = 1
            )
        }
    }
}
