package com.norwedish.twitcherchat

/**
 * Settings screen for chat-related preferences: which Twitch system NOTICE messages
 * should also appear in the chat. Changes are persisted immediately to SharedPreferences,
 * which the ChatService reads (same keys as before).
 *
 * Layout is grouped by chat mode with human-readable labels; the raw msg-id is shown
 * as a caption so power users can still see/verify the technical id.
 */

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

private const val PREFS_NAME = "chat_service_prefs"
private const val PREF_KEY_SUPPRESSED = "suppressed_notice_msgids"

/** A chat-mode group: title string resource + (msg-id, label) pairs. */
private val noticeGroups: List<Pair<Int, List<Pair<String, Int>>>> = listOf(
    R.string.group_slow_mode to listOf(
        "slow_on" to R.string.notice_turned_on,
        "slow_off" to R.string.notice_turned_off
    ),
    R.string.group_emote_only to listOf(
        "emote_only_on" to R.string.notice_turned_on,
        "emote_only_off" to R.string.notice_turned_off
    ),
    R.string.group_subs_only to listOf(
        "subs_on" to R.string.notice_turned_on,
        "subs_off" to R.string.notice_turned_off,
        "subscribers_on" to R.string.notice_turned_on,
        "subscribers_off" to R.string.notice_turned_off
    ),
    R.string.group_r9k to listOf(
        "r9k_on" to R.string.notice_turned_on,
        "r9k_off" to R.string.notice_turned_off
    )
)

private val defaultNoticeIds: List<String> = noticeGroups.flatMap { it.second.map { p -> p.first } }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ChatSettingsScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // Load persisted set into state
    val persistedCsv = remember { prefs.getString(PREF_KEY_SUPPRESSED, null) }
    val initialSet = remember(persistedCsv) {
        if (persistedCsv.isNullOrBlank()) defaultNoticeIds.toSet()
        else persistedCsv.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    var suppressed by remember { mutableStateOf(initialSet.toMutableSet()) }
    var newMsgId by remember { mutableStateOf("") }
    var showResetConfirm by remember { mutableStateOf(false) }

    fun save() {
        prefs.edit().putString(PREF_KEY_SUPPRESSED, suppressed.joinToString(",")).apply()
        Toast.makeText(context, context.getString(R.string.saved), Toast.LENGTH_SHORT).show()
    }

    fun toggle(msgId: String, checked: Boolean) {
        if (checked) suppressed.add(msgId) else suppressed.remove(msgId)
        save()
    }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            icon = { Icon(Icons.Default.Refresh, contentDescription = null) },
            title = { Text(stringResource(R.string.reset_confirm_title)) },
            text = { Text(stringResource(R.string.reset_confirm_text)) },
            confirmButton = {
                TextButton(onClick = {
                    suppressed.clear()
                    suppressed.addAll(defaultNoticeIds)
                    save()
                    showResetConfirm = false
                }) {
                    Text(stringResource(R.string.yes_reset))
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.chat_settings)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                },
                actions = {
                    TextButton(onClick = { showResetConfirm = true }) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Spacer(modifier = Modifier.size(4.dp))
                        Text(stringResource(R.string.reset))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Intro: what this screen is for
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Column {
                        Text(
                            text = stringResource(R.string.settings_intro_title),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.settings_intro_text),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                        )
                    }
                }
            }

            // Grouped switches
            noticeGroups.forEach { (groupTitleRes, items) ->
                Text(
                    text = stringResource(groupTitleRes),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
                )
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(vertical = 4.dp)) {
                        items.forEach { (msgId, labelRes) ->
                            ListItem(
                                headlineContent = { Text(stringResource(labelRes)) },
                                supportingContent = {
                                    Text(
                                        text = stringResource(R.string.msg_id_caption, msgId),
                                        fontFamily = FontFamily.Monospace,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                },
                                trailingContent = {
                                    Switch(
                                        checked = suppressed.contains(msgId),
                                        onCheckedChange = { checked -> toggle(msgId, checked) }
                                    )
                                }
                            )
                        }
                    }
                }
            }

            // Custom msg-ids
            Text(
                text = stringResource(R.string.custom_msg_ids),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = stringResource(R.string.custom_msg_ids_explanation),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            val customIds = suppressed.filter { it !in defaultNoticeIds }
            if (customIds.isEmpty()) {
                Text(
                    text = stringResource(R.string.no_custom_msg_ids),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    customIds.forEach { id ->
                        AssistChip(
                            onClick = { toggle(id, false) },
                            label = { Text(id, fontFamily = FontFamily.Monospace) },
                            trailingIcon = {
                                Icon(
                                    Icons.Default.DeleteOutline,
                                    contentDescription = stringResource(R.string.remove)
                                )
                            }
                        )
                    }
                }
            }

            // Add custom msg-id
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = newMsgId,
                    onValueChange = { newMsgId = it },
                    label = { Text(stringResource(R.string.add_custom_msg_id)) },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                FilledTonalButton(onClick = {
                    val id = newMsgId.trim()
                    if (id.isNotEmpty()) {
                        suppressed.add(id)
                        newMsgId = ""
                        save()
                    } else {
                        Toast.makeText(
                            context,
                            context.getString(R.string.enter_a_msg_id),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(modifier = Modifier.size(4.dp))
                    Text(stringResource(R.string.add))
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}
