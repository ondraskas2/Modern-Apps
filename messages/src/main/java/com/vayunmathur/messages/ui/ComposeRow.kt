package com.vayunmathur.messages.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.IconAttachment
import com.vayunmathur.messages.R

/**
 * Shared compose row used by both [ConversationScreen] and
 * [ComposeScreen]. Extracted so the two screens stay in lockstep on
 * compose-affordance behavior (attach button, send disabled while in
 * flight, etc.) without one drifting from the other.
 *
 * [sendEnabled] is the call-site's gate (e.g. "draft non-blank" for
 * conversation replies, "recipient picked AND (draft non-blank OR media
 * attached)" for new conversations). The row also unconditionally
 * disables Send while [sending] is true so the user can't double-tap.
 */
@Composable
internal fun ComposeRow(
    draft: String,
    onDraftChange: (String) -> Unit,
    sending: Boolean,
    onSend: () -> Unit,
    onAttach: () -> Unit,
    sendEnabled: Boolean = draft.isNotBlank(),
) {
    Surface(
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 8.dp, vertical = 8.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onAttach, enabled = !sending) {
                IconAttachment()
            }
            OutlinedTextField(
                value = draft,
                onValueChange = onDraftChange,
                modifier = Modifier
                    .weight(1f, fill = true)
                    .padding(horizontal = 4.dp),
                placeholder = { Text(stringResource(R.string.conversation_compose_placeholder)) },
                maxLines = 5,
                enabled = !sending,
                shape = RoundedCornerShape(24.dp),
            )
            IconButton(onClick = onSend, enabled = sendEnabled && !sending) {
                if (sending) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Text(
                        stringResource(R.string.conversation_send),
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}
