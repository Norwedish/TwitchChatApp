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

/**
 * Inline banner shown at the top of the chatter list when the full Helix chatter list is unavailable
 * for a privileged user (moderator / broadcaster). The ViewModel controls when the hint is set.
 *
 * Behavior:
 *  - Shows when ViewModel.chatterListLimitedHint is non-null and the chatter list is visible.
 *  - Auto-hides after 8 seconds (and asks VM to clear the hint).
 *  - Has a dismiss (X) button which clears the hint immediately via the ViewModel.
 *  - Optionally supports a "Learn more" action via onLearnMore.
 *
 * Note: the banner is now restricted to moderators only (do not show to general viewers).
 */
@Composable
fun ChatterListLimitedBanner(
    viewModel: ChatViewModel,
    onLearnMore: () -> Unit = {}
) {
    val hint by viewModel.chatterListLimitedHint.collectAsState()
    val isVisible by viewModel.isChatterListVisible.collectAsState()
    // Only show banner to moderators (do not show to general viewers)
    val isModerator by viewModel.isCurrentUserModerator.collectAsState()

    if (!isVisible || hint.isNullOrBlank() || !isModerator) return

    // Keep latest onLearnMore lambda stable inside LaunchedEffect
    val currentOnLearnMore by rememberUpdatedState(onLearnMore)

    // Auto-hide after 8 seconds; clear the VM hint when the timeout fires
    LaunchedEffect(hint) {
        // wait, then clear hint (if still the same)
        kotlinx.coroutines.delay(8_000)
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
