package com.vayunmathur.contacts.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import com.vayunmathur.library.ui.LocalContentColor
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp

/**
 * Drop-in replacement for Material3 `ListItem` that lays out its slots with a
 * plain Row/Column instead of ListItem's alignment-line-based measure policy.
 *
 * Material3's ListItem queries its children's baseline alignment lines. That
 * throws a framework NullPointerException (LookaheadDelegate.getAlignmentLinesOwner)
 * when the item is remeasured inside the Navigation3 adaptive lookahead pass on a
 * configuration change (e.g. rotation). This layout never queries alignment
 * lines, so it is safe inside those scenes while matching ListItem's appearance.
 */
@Composable
fun SafeListItem(
    modifier: Modifier = Modifier,
    containerColor: Color = Color.Transparent,
    overlineContent: (@Composable () -> Unit)? = null,
    supportingContent: (@Composable () -> Unit)? = null,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(containerColor)
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingContent != null) {
            CompositionLocalProvider(LocalContentColor provides scheme.onSurfaceVariant) {
                leadingContent()
            }
            Spacer(Modifier.width(16.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            if (overlineContent != null) {
                Styled(MaterialTheme.typography.labelSmall, scheme.onSurfaceVariant, overlineContent)
            }
            Styled(MaterialTheme.typography.bodyLarge, scheme.onSurface, content)
            if (supportingContent != null) {
                Styled(MaterialTheme.typography.bodyMedium, scheme.onSurfaceVariant, supportingContent)
            }
        }
        if (trailingContent != null) {
            Spacer(Modifier.width(16.dp))
            CompositionLocalProvider(LocalContentColor provides scheme.onSurfaceVariant) {
                trailingContent()
            }
        }
    }
}

@Composable
private fun Styled(style: TextStyle, color: Color, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalContentColor provides color) {
        ProvideTextStyle(style, content)
    }
}
