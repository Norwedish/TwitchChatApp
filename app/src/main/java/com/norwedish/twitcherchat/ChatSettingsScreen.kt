package com.norwedish.twitcherchat

/**
 * Settings screen composable for chat-related preferences (notice suppression, appearance, etc.).
 */

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private const val PREFS_NAME = "chat_service_prefs"
private const val PREF_KEY_SUPPRESSED = "suppressed_notice_msgids"

private val defaultNoticeIds = listOf(
    "slow_on", "slow_off",
    "emote_only_on", "emote_only_off",
    "subs_on", "subs_off", "subscribers_on", "subscribers_off",
    "r9k_on", "r9k_off"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatSettingsScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // Load persisted set into state
    val persistedCsv = remember { prefs.getString(PREF_KEY_SUPPRESSED, null) }
    val initialSet = remember(persistedCsv) {
        if (persistedCsv.isNullOrBlank()) defaultNoticeIds.toSet() else persistedCsv.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    var suppressed by remember { mutableStateOf(initialSet.toMutableSet()) }
    var newMsgId by remember { mutableStateOf("") }

    fun save() {
        prefs.edit().putString(PREF_KEY_SUPPRESSED, suppressed.joinToString(",")).apply()
        Toast.makeText(context, "Saved", Toast.LENGTH_SHORT).show()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Chat Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Suppress NOTICE message IDs that duplicate room state (these are saved and used by the chat service).", style = MaterialTheme.typography.bodyMedium)

            // Defaults list with switches
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    defaultNoticeIds.forEach { id ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Text(text = id, modifier = Modifier.weight(1f))
                            Switch(checked = suppressed.contains(id), onCheckedChange = { checked ->
                                if (checked) suppressed.add(id) else suppressed.remove(id)
                                save()
                            })
                        }
                    }
                }
            }

            // Custom entries list
            Text("Custom suppressed msg-ids", style = MaterialTheme.typography.titleMedium)
            if (suppressed.any { it !in defaultNoticeIds }) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        suppressed.filter { it !in defaultNoticeIds }.forEach { id ->
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Text(text = id, modifier = Modifier.weight(1f))
                                IconButton(onClick = {
                                    suppressed.remove(id)
                                    save()
                                }) {
                                    Text("Remove")
                                }
                            }
                        }
                    }
                }
            } else {
                Text("No custom suppressed msg-ids.")
            }

            // Add custom msg-id
            OutlinedTextField(
                value = newMsgId,
                onValueChange = { newMsgId = it },
                label = { Text("Add custom msg-id") },
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val id = newMsgId.trim()
                    if (id.isNotEmpty()) {
                        suppressed.add(id)
                        newMsgId = ""
                        save()
                    } else {
                        Toast.makeText(context, "Enter a msg-id", Toast.LENGTH_SHORT).show()
                    }
                }) {
                    Text("Add")
                }
                Button(onClick = { suppressed.clear(); suppressed.addAll(defaultNoticeIds); save() }) {
                    Text("Reset to defaults")
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = {
                // ensure saved and navigate back
                save()
                onNavigateBack()
            }, modifier = Modifier.align(Alignment.End)) {
                Text("Done")
            }
        }
    }
}
