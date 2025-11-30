package com.norwedish.twitchchatapp

import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhisperScreen(
    viewModel: WhisperViewModel,
    onNavigateBack: () -> Unit
) {
    val conversations by viewModel.conversations.collectAsState()
    val selectedConversation by viewModel.selectedConversation.collectAsState()
    val inputMessage by viewModel.inputMessage.collectAsState()
    val isSending by viewModel.isSending.collectAsState()

    // For devices Android 12+ we can apply a RenderEffect blur to the root view while
    // the whisper overlay is visible. We host the overlay in a Dialog so it isn't
    // affected by the blur. On older devices we fall back to a dim scrim.
    val view = LocalView.current

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        DisposableEffect(view) {
            val blur = RenderEffect.createBlurEffect(22f, 22f, Shader.TileMode.CLAMP)
            view.setRenderEffect(blur)
            onDispose { view.setRenderEffect(null) }
        }

        Dialog(onDismissRequest = { onNavigateBack() }) {
            // Keep the whisper UI as a Card inside the dialog window so the blur remains
            // applied to the underlying activity content while the dialog stays sharp.
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.94f)
                    .padding(12.dp),
                shape = RoundedCornerShape(12.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                // ...existing whisper UI placed inside the card...
                Column(modifier = Modifier.fillMaxSize()) {
                    // Adaptive top app bar: show conversation title + back when a conversation is selected,
                    // otherwise show the app-level back navigation.
                    TopAppBar(
                        title = { Text(selectedConversation?.otherDisplayName ?: "Direct Messages") },
                        navigationIcon = {
                            IconButton(onClick = {
                                if (selectedConversation == null) {
                                    onNavigateBack()
                                } else {
                                    viewModel.selectConversation("")
                                }
                            }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                            }
                        }
                    )

                    // Make the search bar always visible at the top of the messages screen
                    var globalSearchQuery by remember { mutableStateOf("") }

                    OutlinedTextField(
                        value = globalSearchQuery,
                        onValueChange = {
                            globalSearchQuery = it
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp)
                            .clip(RoundedCornerShape(20.dp)),
                        placeholder = { Text("Search users...", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                        leadingIcon = {
                            Icon(Icons.Default.Search, contentDescription = "Search", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        },
                        trailingIcon = {
                            Row {
                                if (globalSearchQuery.isNotEmpty()) {
                                    IconButton(onClick = { globalSearchQuery = "" }) {
                                        Icon(Icons.Default.Clear, contentDescription = "Clear", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                IconButton(onClick = {
                                    if (globalSearchQuery.isNotBlank()) {
                                        viewModel.lookupUser(globalSearchQuery) { user ->
                                            if (user != null) viewModel.startConversationWithUser(user)
                                        }
                                    }
                                }) {
                                    Icon(Icons.Default.Search, contentDescription = "Search", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        },
                        singleLine = true,
                        textStyle = LocalTextStyle.current.copy(color = MaterialTheme.colorScheme.onSurface),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            focusedIndicatorColor = MaterialTheme.colorScheme.primary,
                            unfocusedIndicatorColor = Color.Transparent,
                            focusedTextColor = MaterialTheme.colorScheme.onSurface,
                            unfocusedTextColor = MaterialTheme.colorScheme.onSurface
                        ),
                    )

                    if (selectedConversation == null) {
                        // Conversation List View
                        WhisperConversationList(
                            conversations = conversations,
                            onSelectConversation = { userLogin ->
                                viewModel.selectConversation(userLogin)
                            },
                            viewModel = viewModel
                        )
                    } else {
                        // Conversation Detail View
                        WhisperConversationDetail(
                            conversation = selectedConversation!!,
                            inputMessage = inputMessage,
                            isSending = isSending,
                            onInputChanged = { viewModel.onInputChanged(it) },
                            onSendClick = { viewModel.sendWhisper() }
                        )
                    }
                }
            }
        }

        // Done — the Dialog handles display; return early so we don't render the fallback below.
        return
    }

    // Fallback for devices older than Android 12: keep the dim scrim + card overlay approach.
    Box(modifier = Modifier.fillMaxSize()) {
        // Scrim to dim the content underneath
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(Color.Black.copy(alpha = 0.45f))
        )

        // Centered card overlay for the whisper content; not fully edge-to-edge so the
        // scrim remains visible and emphasizes this as an overlay. Adjust maxHeight
        // so it feels like a full-screen panel but visually separated.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.94f),
                shape = RoundedCornerShape(12.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                // ...existing whisper UI placed inside the card...
                Column(modifier = Modifier.fillMaxSize()) {
                    // Adaptive top app bar: show conversation title + back when a conversation is selected,
                    // otherwise show the app-level back navigation.
                    TopAppBar(
                        title = { Text(selectedConversation?.otherDisplayName ?: "Direct Messages") },
                        navigationIcon = {
                            IconButton(onClick = {
                                if (selectedConversation == null) {
                                    onNavigateBack()
                                } else {
                                    viewModel.selectConversation("")
                                }
                            }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                            }
                        }
                    )

                    // Make the search bar always visible at the top of the messages screen
                    var globalSearchQuery by remember { mutableStateOf("") }

                    OutlinedTextField(
                        value = globalSearchQuery,
                        onValueChange = {
                            globalSearchQuery = it
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp)
                            .clip(RoundedCornerShape(20.dp)),
                        placeholder = { Text("Search users...", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                        leadingIcon = {
                            Icon(Icons.Default.Search, contentDescription = "Search", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        },
                        trailingIcon = {
                            Row {
                                if (globalSearchQuery.isNotEmpty()) {
                                    IconButton(onClick = { globalSearchQuery = "" }) {
                                        Icon(Icons.Default.Clear, contentDescription = "Clear", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                IconButton(onClick = {
                                    if (globalSearchQuery.isNotBlank()) {
                                        viewModel.lookupUser(globalSearchQuery) { user ->
                                            // If found, start the conversation immediately
                                            if (user != null) {
                                                viewModel.startConversationWithUser(user)
                                            }
                                        }
                                    }
                                }) {
                                    Icon(Icons.Default.Search, contentDescription = "Search", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        },
                        singleLine = true,
                        textStyle = LocalTextStyle.current.copy(color = MaterialTheme.colorScheme.onSurface),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            focusedIndicatorColor = MaterialTheme.colorScheme.primary,
                            unfocusedIndicatorColor = Color.Transparent,
                            focusedTextColor = MaterialTheme.colorScheme.onSurface,
                            unfocusedTextColor = MaterialTheme.colorScheme.onSurface
                        ),
                    )

                    if (selectedConversation == null) {
                        // Conversation List View
                        WhisperConversationList(
                            conversations = conversations,
                            onSelectConversation = { userLogin ->
                                viewModel.selectConversation(userLogin)
                            },
                            viewModel = viewModel
                        )
                    } else {
                        // Conversation Detail View
                        WhisperConversationDetail(
                            conversation = selectedConversation!!,
                            inputMessage = inputMessage,
                            isSending = isSending,
                            onInputChanged = { viewModel.onInputChanged(it) },
                            onSendClick = { viewModel.sendWhisper() }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun WhisperConversationList(
    conversations: List<WhisperConversation>,
    onSelectConversation: (String) -> Unit,
    viewModel: WhisperViewModel
) {
    var searchQuery by remember { mutableStateOf("") }
    var lookupResult by remember { mutableStateOf<WhisperUser?>(null) }
    var isLookingUp by remember { mutableStateOf(false) }

    val filteredConversations = conversations.filter { conversation ->
        conversation.otherDisplayName.contains(searchQuery, ignoreCase = true) ||
        conversation.otherUserLogin.contains(searchQuery, ignoreCase = true)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Search Bar
        OutlinedTextField(
            value = searchQuery,
            onValueChange = {
                searchQuery = it
                lookupResult = null
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
                .clip(RoundedCornerShape(20.dp)),
            placeholder = { Text("Search users...", color = MaterialTheme.colorScheme.onSurfaceVariant) },
            leadingIcon = {
                Icon(Icons.Default.Search, contentDescription = "Search", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            },
            // Add clear + search icons in trailing area so user can trigger lookup
            trailingIcon = {
                Row {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    IconButton(onClick = {
                        if (searchQuery.isNotBlank()) {
                            isLookingUp = true
                            viewModel.lookupUser(searchQuery) { user ->
                                lookupResult = user
                                isLookingUp = false
                            }
                        }
                    }) {
                        Icon(Icons.Default.Search, contentDescription = "Search", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            },
            singleLine = true,
            textStyle = androidx.compose.material3.LocalTextStyle.current.copy(color = MaterialTheme.colorScheme.onSurface),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                focusedIndicatorColor = MaterialTheme.colorScheme.primary,
                unfocusedIndicatorColor = Color.Transparent,
                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                unfocusedTextColor = MaterialTheme.colorScheme.onSurface
            ),
        )

        if (filteredConversations.isEmpty() && searchQuery.isNotBlank()) {
            Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                if (isLookingUp) {
                    Text("Looking up user...", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else if (lookupResult != null) {
                    // Show found user with button to create conversation
                    Card(modifier = Modifier.fillMaxWidth().clickable {
                        viewModel.startConversationWithUser(lookupResult!!)
                    }) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            AsyncImage(
                                model = lookupResult!!.profileImageUrl,
                                contentDescription = "avatar",
                                modifier = Modifier.size(40.dp).clip(CircleShape),
                                contentScale = ContentScale.Crop
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(lookupResult!!.displayName, fontWeight = FontWeight.Bold)
                                Text("@${lookupResult!!.userLogin}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text("Start", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                } else {
                    // No existing conversation and no result
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("No users found")
                        TextButton(onClick = {
                            // allow creating a conversation with the typed username even if lookup failed
                            viewModel.startNewConversation(searchQuery)
                        }) {
                            Text("Create")
                        }
                    }
                }
            }
        }

        if (filteredConversations.isEmpty() && searchQuery.isBlank()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No direct messages yet",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(filteredConversations) { conversation ->
                    WhisperConversationListItem(
                        conversation = conversation,
                        onClick = { onSelectConversation(conversation.otherUserLogin) },
                        onDelete = { viewModel.deleteConversation(conversation.otherUserLogin) }
                    )
                }
            }
        }
    }
}

@Composable
fun WhisperConversationListItem(
    conversation: WhisperConversation,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    var showDeleteDialog by remember { mutableStateOf(false) }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete conversation") },
            text = { Text("Are you sure you want to delete this conversation with ${conversation.otherDisplayName}?") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete()
                    showDeleteDialog = false
                }) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp)
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            AsyncImage(
                model = conversation.otherProfileImageUrl,
                contentDescription = "User avatar",
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape),
                contentScale = ContentScale.Crop
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = conversation.otherDisplayName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = conversation.getLastMessagePreview(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }

            if (conversation.unreadCount > 0) {
                Badge(
                    modifier = Modifier.align(Alignment.CenterVertically),
                    containerColor = MaterialTheme.colorScheme.error
                ) {
                    Text(
                        text = conversation.unreadCount.toString(),
                        color = MaterialTheme.colorScheme.onError,
                        fontSize = 12.sp
                    )
                }
            }

            IconButton(
                onClick = { showDeleteDialog = true },
                modifier = Modifier.size(24.dp)
            ) {
                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhisperConversationDetail(
    conversation: WhisperConversation,
    inputMessage: TextFieldValue,
    isSending: Boolean,
    onInputChanged: (TextFieldValue) -> Unit,
    onSendClick: () -> Unit
) {
    val listState = rememberLazyListState()

    Column(modifier = Modifier.fillMaxSize()) {

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(16.dp),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(conversation.messages) { message ->
                WhisperMessageBubble(message = message)
            }
        }

        HorizontalDivider()

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TextField(
                value = inputMessage,
                onValueChange = onInputChanged,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(20.dp)),
                placeholder = { Text("Message...") },
                singleLine = false,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                )
            )

            IconButton(
                onClick = onSendClick,
                enabled = !isSending && inputMessage.text.isNotBlank()
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send",
                    tint = if (!isSending && inputMessage.text.isNotBlank())
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    LaunchedEffect(conversation.messages.size) {
        if (conversation.messages.isNotEmpty()) {
            listState.animateScrollToItem(conversation.messages.size - 1)
        }
    }
}

@Composable
fun WhisperMessageBubble(message: WhisperMessage) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = if (message.isOutgoing) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .clip(RoundedCornerShape(12.dp)),
            color = if (message.isOutgoing)
                MaterialTheme.colorScheme.primary
            else
                MaterialTheme.colorScheme.surfaceVariant
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = message.message,
                    color = if (message.isOutgoing)
                        MaterialTheme.colorScheme.onPrimary
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = message.getFormattedTime(),
                    color = if (message.isOutgoing)
                        MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 10.sp
                )
            }
        }
    }
}
