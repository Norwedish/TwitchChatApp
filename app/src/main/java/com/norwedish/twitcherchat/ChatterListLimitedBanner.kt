package com.norwedish.twitcherchat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Inline banner shown at the top of the chatter list when the full Helix chatter list is unavailable
 * for a privileged user (moderator / broadcaster). The ViewModel controls when the hint is set.
 */
@Composable
fun ChatterListLimitedBanner(
    viewModel: ChatViewModel,
    onLearnMore: () -> Unit = {}
) {
    // Providing explicit initial values fixes the "Cannot infer argument for type parameter T" error
    val hint by viewModel.chatterListLimitedHint.collectAsState(initial = null)
    val isVisible by viewModel.isChatterListVisible.collectAsState(initial = false)
    val isModerator by viewModel.isCurrentUserModerator.collectAsState(initial = false)

    if (!isVisible || hint.isNullOrBlank() || !isModerator) return

    val currentOnLearnMore by rememberUpdatedState(onLearnMore)

    LaunchedEffect(hint) {
        // Auto-hide after 8 seconds
        delay(8_000)
        viewModel.dismissChatterListHint()
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 2.dp,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start,
            modifier = Modifier.padding(12.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )

            Spacer(modifier = Modifier.width(8.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = hint ?: "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            TextButton(onClick = { currentOnLearnMore() }) {
                Text(text = "Learn why")
            }

            IconButton(onClick = { viewModel.dismissChatterListHint() }) {
                Icon(imageVector = Icons.Filled.Close, contentDescription = "Dismiss")
            }
        }
    }
}
