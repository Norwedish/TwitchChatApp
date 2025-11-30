package com.norwedish.twitchchatapp

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Locale
import androidx.core.graphics.toColorInt
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener

// Define sealed classes for our message tokens at the top level
sealed class MessageToken
data class TextToken(val text: String) : MessageToken()
data class EmoteToken(val code: String, val url: String) : MessageToken()
data class UrlToken(val url: String) : MessageToken()


@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalMaterialApi::class, androidx.compose.ui.text.ExperimentalTextApi::class)
@Composable
fun ChatScreen(
    channelName: String,
    twitchUserId: String,
    displayName: String,
    profileImageUrl: String,
    viewModel: ChatViewModel,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val whisperViewModel: WhisperViewModel = viewModel()
    var showWhisperScreen by remember { mutableStateOf(false) }
    var showCastDialog by remember { mutableStateOf(false) } // state for cast dialog

    // Read the streamTitle from the ViewModel early so listeners can reference it
    val streamTitle by viewModel.streamTitle.collectAsState()

    // make a coroutine scope available for the session listener
    val coroutineScope = rememberCoroutineScope()

    // Initialize Cast SDK and register a session listener scoped to this ChatScreen
    DisposableEffect(Unit) {
        // Ensure CastManager is initialized with application context
        CastManager.initialize(context.applicationContext)

        val sessionListener = object : SessionManagerListener<CastSession> {
            override fun onSessionEnded(session: CastSession, error: Int) {
                CastSessionCallbacks.onSessionEnded()
            }

            override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
                CastManager.setCurrentSession(session)
                CastSessionCallbacks.onSessionStarted()
//                 If we resumed/connected here and there's no pending URL, try to fetch stream URL and start casting
                coroutineScope.launch {
                    try {
                        val streamUrl = TwitchApi.getStreamUrl(channelName)
                        if (!streamUrl.isNullOrEmpty()) {
                            CastManager.startCasting(url = streamUrl, displayName = displayName, streamTitle = streamTitle, poster = profileImageUrl)
                        }
                    } catch (_: Exception) {
                        // ignore
                    }
                }
            }

            override fun onSessionResumeFailed(session: CastSession, error: Int) {
                CastSessionCallbacks.onSessionEnded()
            }

            override fun onSessionStarted(session: CastSession, sessionId: String) {
                CastManager.setCurrentSession(session)
                CastSessionCallbacks.onSessionStarted()
                // Session started after entering chat: fetch URL and start casting
                coroutineScope.launch {
                    try {
                        val streamUrl = TwitchApi.getStreamUrl(channelName)
                        if (!streamUrl.isNullOrEmpty()) {
                            CastManager.startCasting(url = streamUrl, displayName = displayName, streamTitle = streamTitle, poster = profileImageUrl)
                        }
                    } catch (_: Exception) {
                        // ignore
                    }
                }
            }

            override fun onSessionStartFailed(session: CastSession, error: Int) {
                CastSessionCallbacks.onSessionEnded()
            }

            override fun onSessionStarting(session: CastSession) {}
            override fun onSessionEnding(session: CastSession) {}
            override fun onSessionResuming(session: CastSession, sessionId: String) {}
            override fun onSessionSuspended(session: CastSession, reason: Int) {}
        }

        // Try to get shared CastContext and register the listener when available
        try {
            @Suppress("DEPRECATION")
            CastContext.getSharedInstance(context, java.util.concurrent.Executors.newSingleThreadExecutor())
                .addOnSuccessListener { castContext ->
                    try {
                        castContext.sessionManager.addSessionManagerListener(sessionListener, CastSession::class.java)
                    } catch (_: Exception) {}
                }
        } catch (_: Throwable) {
            // Cast SDK not available or initialization failed; ignore silently
        }

        onDispose {
            try {
                CastContext.getSharedInstance(context, java.util.concurrent.Executors.newSingleThreadExecutor())
                    .addOnSuccessListener { castContext ->
                        try {
                            castContext.sessionManager.removeSessionManagerListener(sessionListener, CastSession::class.java)
                        } catch (_: Exception) {}
                    }
            } catch (_: Throwable) {
                // ignore
            }
        }
    }

    // Helper functions to clear local UI state - read the value first to avoid analyzer "assigned value is never read" warnings
    fun closeWhisperScreen() {
        // Read current value and only clear if true
        if (showWhisperScreen) showWhisperScreen = false
    }

    fun closeCastDialog() {
        if (showCastDialog) showCastDialog = false
    }

    // Initialize whisper view model and service
    DisposableEffect(Unit) {
        val prefManager = WhisperPreferenceManager(context)
        val currentUser = UserManager.currentUser
        if (currentUser != null) {
            whisperViewModel.initialize(prefManager, currentUser.id, currentUser.login)
        }

        val whisperServiceIntent = Intent(context, WhisperService::class.java)
        context.startService(whisperServiceIntent)

        val whisperServiceConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                val binder = service as WhisperService.WhisperBinder
                whisperViewModel.setWhisperService(binder.getService())
            }

            override fun onServiceDisconnected(name: ComponentName?) {}
        }

        context.bindService(whisperServiceIntent, whisperServiceConnection, Context.BIND_AUTO_CREATE)

        onDispose {
            context.unbindService(whisperServiceConnection)
        }
    }

    DisposableEffect(channelName, twitchUserId) {
        viewModel.prepareForChannel(channelName, twitchUserId)

        val serviceIntent = Intent(context, ChatService::class.java).apply {
            putExtra("channelName", channelName)
            putExtra("twitchUserId", twitchUserId)
        }
        context.startService(serviceIntent)

        val serviceConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                val binder = service as ChatService.ChatBinder
                viewModel.setChatService(binder.getService())
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                // Handle disconnection
            }
        }

        context.bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)

        onDispose {
            context.unbindService(serviceConnection)
            context.stopService(serviceIntent)
        }
    }

    val connectionState by viewModel.connectionState.collectAsState()
    val messages by viewModel.messages.collectAsState()
    val inputMessage by viewModel.inputMessage.collectAsState()
    val isEmoteMenuVisible by viewModel.isEmoteMenuVisible.collectAsState()
    val availableEmotes by viewModel.availableEmotes.collectAsState()
    val listState = rememberLazyListState()
    // Local tracking of whether the user is at the bottom. We mirror the ViewModel's state but
    // keep a local copy to trigger immediate UI-side auto-scrolling when messages change.
    val isAtBottomLocal = remember { mutableStateOf(true) }
    val isKeyboardVisible = WindowInsets.isImeVisible
    val keyboardController = LocalSoftwareKeyboardController.current
    val isCurrentUserModerator by viewModel.isCurrentUserModerator.collectAsState()
    val selectedUserForProfile by viewModel.selectedUserForProfile.collectAsState()
    val viewerCount by viewModel.viewerCount.collectAsState()
    val poll by viewModel.poll.collectAsState()
    val isChatterListVisible by viewModel.isChatterListVisible.collectAsState()
    val chatters by viewModel.chatters.collectAsState()
    val isChattersLoading by viewModel.isChattersLoading.collectAsState()
    val userSuggestions by viewModel.userSuggestions.collectAsState()
    val showUserSuggestions by viewModel.showUserSuggestions.collectAsState()
    val roomState by viewModel.roomState.collectAsState()
    val replyToMessage by viewModel.replyToMessage.collectAsState()
    val followRelationship by viewModel.followRelationship.collectAsState()
    val isSubscriber by viewModel.isSubscriber.collectAsState()
    val unreadMessageCount by viewModel.unreadMessageCount.collectAsState()

    val uriHandler = LocalUriHandler.current // uri handler for opening external links
    // Observe Cast debug state to surface errors and provide retry/fallback actions
    val castDebugState by CastManager.debugState.collectAsState()

    if (isChatterListVisible) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { viewModel.onChatterListDismissed() }, sheetState = sheetState) {
            ChatterListScreen(
                chatters = chatters,
                isLoading = isChattersLoading,
                onRefresh = { viewModel.fetchChatters() },
                profileImageUrl = profileImageUrl,
                displayName = displayName,
                streamTitle = streamTitle,
                viewerCount = viewerCount
            )
        }
    }

    if (selectedUserForProfile != null) {
        val userMessages = messages.filter { it.author == selectedUserForProfile?.author }
        UserInfoDialog(
            user = selectedUserForProfile!!,
            messages = userMessages,
            onDismiss = { viewModel.onDismissUserProfile() },
            onSendWhisper = { userLogin ->
                whisperViewModel.startNewConversation(
                    userLogin = userLogin,
                    displayName = selectedUserForProfile?.author ?: userLogin,
                    profileImageUrl = selectedUserForProfile?.tags?.get("profile-image-url") ?: ""
                )
                showWhisperScreen = true
                viewModel.onDismissUserProfile()
            }
        )
    }

    BackHandler(enabled = isEmoteMenuVisible) {
        viewModel.onEmoteMenuToggled()
    }

    LaunchedEffect(isKeyboardVisible) {
        if (isKeyboardVisible && isEmoteMenuVisible) {
            viewModel.onEmoteMenuToggled()
        }
    }

    // If the user navigated here from MainScreen (clicking a stream) and a cast session
    // is already active, start casting automatically (mimics previous behavior in MainActivity).
    LaunchedEffect(channelName) {
        try {
            @Suppress("DEPRECATION")
            com.google.android.gms.cast.framework.CastContext.getSharedInstance(context, java.util.concurrent.Executors.newSingleThreadExecutor())
                .addOnSuccessListener { castContext ->
                    try {
                        val currentSession = castContext.sessionManager.currentCastSession
                        if (currentSession != null && currentSession.isConnected) {
                            // Launch a coroutine to fetch the stream URL and request CastManager to start casting
                            coroutineScope.launch {
                                try {
                                    val streamUrl = TwitchApi.getStreamUrl(channelName)
                                    Log.d("ChatScreen", "Stream URL: $streamUrl")
                                    if (streamUrl != null) {
                                        CastManager.startCasting(
                                            url = streamUrl,
                                            displayName = displayName,
                                            streamTitle = streamTitle,
                                            poster = profileImageUrl
                                        )
                                    }
                                } catch (_: Exception) {
                                    // ignore fetch/cast failures here
                                }
                            }
                        }
                    } catch (_: Exception) {
                        // ignore
                    }
                }
        } catch (_: Throwable) {
            // ignore
        }
    }

    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.firstOrNull()?.index == 0 }
            .distinctUntilChanged()
            .collect { isAtBottom ->
                // Update both local UI state and the ViewModel's state
                isAtBottomLocal.value = isAtBottom
                viewModel.onScrollStateChanged(isAtBottom)
            }
    }

    // Auto-scroll whenever the message list changes, but only if the user is currently at the bottom.
    LaunchedEffect(messages) {
        if (isAtBottomLocal.value) {
            coroutineScope.launch {
                // Slightly larger delay to ensure the LazyColumn has recomposed and measured
                kotlinx.coroutines.delay(150)
                try {
                    // Use direct jump which is more reliable immediately after recomposition
                    listState.scrollToItem(0)
                } catch (_: Exception) {
                    // if scrollToItem fails, try animate as a fallback
                    try {
                        listState.animateScrollToItem(0)
                    } catch (_: Exception) {
                        // give up silently
                    }
                }
            }
        }
    }

    // Also listen to the ViewModel's scroll-to-bottom events (emitted when new messages arrive and the VM thinks we're at bottom)
    LaunchedEffect(Unit) {
        viewModel.scrollToBottom.collect {
            coroutineScope.launch {
                try {
                    listState.animateScrollToItem(0)
                } catch (_: Exception) {
                    try { listState.scrollToItem(0) } catch (_: Exception) {}
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { viewModel.onChatterListRequested() }
                    ) {
                        AsyncImage(
                            model = profileImageUrl,
                            contentDescription = "Streamer avatar",
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(text = displayName)
                                viewerCount?.let {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.Person,
                                            contentDescription = "Viewers",
                                            modifier = Modifier.size(14.dp),
                                            tint = Color.Red
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = formatViewerCount(it),
                                            fontSize = 12.sp,
                                            color = Color.Red
                                        )
                                    }
                                }
                            }
                            streamTitle?.let {
                                Text(
                                    text = it,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    val totalUnreadWhispers by whisperViewModel.totalUnreadCount.collectAsState()

                    // Chromecast / Cast button - show native MediaRouteButton when available
                    CastButton(modifier = Modifier.size(36.dp))
                    IconButton(onClick = {
                        // Open the full whisper screen directly
                        showWhisperScreen = true
                    }) {
                        Box {
                            Icon(Icons.Default.ChatBubble, contentDescription = "Messages")
                            if (totalUnreadWhispers > 0) {
                                Badge(
                                    modifier = Modifier.align(Alignment.TopEnd),
                                    containerColor = MaterialTheme.colorScheme.error
                                ) {
                                    Text(
                                        totalUnreadWhispers.toString(),
                                        color = MaterialTheme.colorScheme.onError,
                                        fontSize = 10.sp
                                    )
                                }
                            }
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .navigationBarsPadding()
                .imePadding()
        ) {
            poll?.let {
                PollCard(poll = it, onVote = { pollId, choiceId ->
                    viewModel.voteOnPoll(pollId, choiceId)
                })
            }
            Box(modifier = Modifier.weight(1f)) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    reverseLayout = true
                ) {
                    items(messages.reversed(), key = { it.id }) { message ->
                        ChatMessageItem(
                            message = message,
                            isCurrentUserModerator = isCurrentUserModerator,
                            onShowProfile = { viewModel.onShowUserProfile(message) },
                            onTimeout = { message.author?.let { viewModel.onTimeout(it) } },
                            onBan = { message.author?.let { viewModel.onBan(it) } },
                            onReply = { viewModel.onReply(message) },
                            broadcasterId = twitchUserId // pass the channel's broadcaster id so badges prefer channel-specific ones
                        )
                    }
                }

                if (messages.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = when (connectionState) {
                                ConnectionState.CONNECTING -> "Connecting to chat..."
                                ConnectionState.CONNECTED -> "Connected, waiting for messages..."
                                ConnectionState.ERROR -> "Connection error"
                                ConnectionState.DISCONNECTED -> "Disconnected"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = when (connectionState) {
                                ConnectionState.CONNECTED -> Color.Green
                                ConnectionState.ERROR -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            }
                        )
                    }
                }
            }
            // Room State
            if (roomState != null) {
                val roomStatusText = buildString {
                    if (roomState!!.emoteOnly) append("Emote-only ")
                    // Subscribers-only text: show only if room is subsOnly AND we know user is NOT a subscriber.
                    if (roomState!!.subsOnly) {
                        val subs = isSubscriber
                        // Treat unknown (null) as non-subscriber as well: show restriction unless subs is explicitly true.
                        if (subs != true) {
                            append("Subscribers-only ")
                        }
                    }
                    roomState!!.followersOnly?.let { followersOnlyDuration ->
                        // Determine whether to show followers-only to the current user.
                        val followedAtStr = followRelationship?.followedAt
                        val now = Instant.now()

                        val shouldShowFollowersOnly = when {
                            followersOnlyDuration == 0 -> {
                                // Followers-only without a duration means any follower; show if user is NOT a follower
                                followedAtStr == null
                            }
                            followersOnlyDuration > 0 -> {
                                if (followedAtStr == null) {
                                    // User is not a follower -> show
                                    true
                                } else {
                                    // Parse followedAt and compare minutes
                                    try {
                                        val followedAt = Instant.parse(followedAtStr)
                                        val userFollowMinutes = ChronoUnit.MINUTES.between(followedAt, now)
                                        userFollowMinutes < followersOnlyDuration
                                    } catch (_: Exception) {
                                        // If parsing fails, err on the side of showing the restriction
                                        true
                                    }
                                }
                            }
                            else -> false
                        }

                        if (shouldShowFollowersOnly) {
                            if (followersOnlyDuration > 0) append("Followers-only ($followersOnlyDuration min) ") else append("Followers-only ")
                        }
                    }
                    if (roomState!!.r9k) append("R9K ")
                    roomState!!.slowMode?.let { if (it > 0) append("Slow mode ($it s) ") }
                }.trim()

                if (roomStatusText.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = roomStatusText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            AnimatedVisibility(visible = unreadMessageCount > 0) {
                Box(modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        shadowElevation = 6.dp,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.align(Alignment.CenterEnd)
                    ) {
                        Row(
                            modifier = Modifier
                                .clickable {
                                    // Scroll immediately and tell the ViewModel to reset unread count
                                    coroutineScope.launch {
                                        try {
                                            listState.animateScrollToItem(0)
                                        } catch (_: Exception) {
                                            try { listState.scrollToItem(0) } catch (_: Exception) {}
                                        }
                                    }
                                    viewModel.jumpToBottom()
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                Icons.Default.ArrowDownward,
                                contentDescription = "Scroll to bottom",
                                modifier = Modifier.size(16.dp)
                            )
                            Text(text = "$unreadMessageCount new messages", fontSize = 12.sp)
                        }
                    }
                }
            }
            
            // Removed the `currentMessageCount` display block
            if (showUserSuggestions) {
                LazyColumn(
                    modifier = Modifier
                        .heightIn(max = 150.dp) // Made the list a bit shorter
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp) // Add some padding
                        .clip(RoundedCornerShape(8.dp)) // Give it rounded corners
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    reverseLayout = true
                ) {
                    items(userSuggestions.reversed()) { username ->
                        Text(
                            text = username,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.onUserSuggestionSelected(username) }
                                .padding(12.dp)
                        )
                    }
                }
            }

            if (connectionState == ConnectionState.CONNECTED) {
                Column {
                    AnimatedVisibility(visible = replyToMessage != null) {
                        replyToMessage?.let { reply ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.AutoMirrored.Filled.Reply, contentDescription = "Replying to", modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Replying to ${reply.authorLogin ?: reply.author}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = reply.message.take(50) + if (reply.message.length > 50) "..." else "",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                    )
                                }
                                IconButton(onClick = { viewModel.clearReply() }) {
                                    Icon(Icons.Default.Close, contentDescription = "Clear reply")
                                }
                            }
                        }
                    }

                    val isBanned by viewModel.isBanned.collectAsState()
                    Column(modifier = Modifier.fillMaxWidth()) {
                        if(isBanned) {
                            Box(modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.errorContainer)
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                                .heightIn(min = 32.dp)) {
                            Text(
                                text = "You are banned from chat",
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.align(Alignment.CenterStart)
                            )
                        }
                    }

                     ChatInputBar(
                            text = inputMessage,
                            onTextChanged = { viewModel.onInputChanged(it) },
                            onSendClick = { viewModel.sendMessage() },
                            onEmoteClick = {
                                keyboardController?.hide()
                                viewModel.onEmoteMenuToggled()
                            },
                            enabled = !isBanned
                        )
                    }
                }
            }

            if (isEmoteMenuVisible) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(250.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 48.dp),
                        contentPadding = PaddingValues(8.dp)
                    ) {
                        items(availableEmotes, key = { it.id }) { emote ->
                            IconButton(onClick = { viewModel.onEmoteSelected(emote.code) }) {
                                AsyncImage(
                                    model = emote.url,
                                    contentDescription = emote.code,
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Whisper Screen (full screen view)
    if (showWhisperScreen) {
        WhisperScreen(
            viewModel = whisperViewModel,
            onNavigateBack = { closeWhisperScreen() }
        )
    }

    // Chromecast / Cast dialog - safe fallback to open the stream in a browser
    if (showCastDialog) {
        AlertDialog(
            onDismissRequest = { closeCastDialog() },
            title = { Text("Cast to Device") },
            text = { Text("Select a device to cast the stream to.") },
            confirmButton = {
                TextButton(onClick = {
                    // Fallback action: open the stream URL in a browser
                    uriHandler.openUri("https://www.twitch.tv/$channelName")
                    closeCastDialog()
                }) {
                    Text("Open in Browser")
                }
            },
            dismissButton = {
                TextButton(onClick = { closeCastDialog() }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun HighlightedTextToken(token: TextToken, isDeleted: Boolean, modifier: Modifier = Modifier) {
    val currentUserDisplayName = UserManager.currentUser?.displayName ?: ""
    if (currentUserDisplayName.isBlank() || isDeleted) {
        Text(
            text = token.text,
            modifier = modifier,
            fontSize = 14.sp,
            color = if (isDeleted) Color.Gray else MaterialTheme.colorScheme.onSurface
        )
        return
    }

    val annotatedString = buildAnnotatedString {
        val mentionPattern = "\\b@?${Regex.escape(currentUserDisplayName)}\\b".toRegex(RegexOption.IGNORE_CASE)
        var lastIndex = 0
        mentionPattern.findAll(token.text).forEach { matchResult ->
            val startIndex = matchResult.range.first
            val endIndex = matchResult.range.last + 1

            if (startIndex > lastIndex) {
                append(token.text.substring(lastIndex, startIndex))
            }

            withStyle(style = SpanStyle(
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                background = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
            )) {
                append(token.text.substring(startIndex, endIndex))
            }
            lastIndex = endIndex
        }
        if (lastIndex < token.text.length) {
            append(token.text.substring(lastIndex))
        }
    }

    Text(
        text = annotatedString,
        modifier = modifier,
        fontSize = 14.sp,
        color = MaterialTheme.colorScheme.onSurface
    )
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun ChatMessageItem(
    message: ChatMessage,
    isCurrentUserModerator: Boolean,
    onShowProfile: () -> Unit,
    onTimeout: () -> Unit,
    onBan: () -> Unit,
    onReply: (ChatMessage) -> Unit,
    broadcasterId: String? = null // added: optional broadcaster id for channel-specific badges
) {
    // Determine if this message is deleted
    val isDeleted = message.type == MessageType.DELETED || message.type == MessageType.SYSTEM && (message.message.contains("deleted", ignoreCase = true) || message.message.contains("was removed", ignoreCase = true))

    val authorColor = remember(message.authorColor) {
        try {
            Color((message.authorColor ?: "#8A2BE2").toColorInt())
        } catch (_: Exception) {
            Color(0xFF8A2BE2.toInt())
        }
    }

    val uriHandler = LocalUriHandler.current
    var showMenu by remember { mutableStateOf(false) }

    // 1. Tokenize the message
    // Note: For USERNOTICE (subscription/system messages), emote indices are invalid because the final message
    // includes system-msg text prepended. We skip tokenization for those and render them as plain text instead.
    val tokens = remember(message.message, message.emotes, message.type) {
        if (message.type == MessageType.SUBSCRIPTION || message.type == MessageType.SYSTEM) {
            // Skip tokenization for subscription notices and system messages; they'll be rendered as plain text
            emptyList<MessageToken>()
        } else {
            val tokenList = mutableListOf<MessageToken>()
            val urlRegex = "(https?://\\S+)".toRegex()

            val specialParts = mutableListOf<Pair<IntRange, MessageToken>>()

            // Add emotes from message tags (only valid for PRIVMSG, not USERNOTICE)
            message.emotes.forEach { emoteInfo ->
                val range = emoteInfo.startIndex..emoteInfo.endIndex
                // Validate range is within bounds before adding
                if (range.last < message.message.length) {
                    specialParts.add(range to EmoteToken(emoteInfo.emote.code, emoteInfo.emote.url))
                }
            }

            // Find and add URLs, avoiding overlap with emotes
            urlRegex.findAll(message.message).forEach { matchResult ->
                val urlRange = matchResult.range
                val overlapsWithEmote = specialParts.any { (emoteRange, token) ->
                    token is EmoteToken && urlRange.first <= emoteRange.last && urlRange.last >= emoteRange.first
                }
                if (!overlapsWithEmote) {
                    specialParts.add(urlRange to UrlToken(matchResult.value))
                }
            }

            // Sort by start index to process the message in order
            specialParts.sortBy { it.first.first }

            var lastIndex = 0
            specialParts.forEach { (range, token) ->
                // Add plain text part before the current special part
                if (range.first > lastIndex && range.first <= message.message.length) {
                    val endIndex = minOf(range.first, message.message.length)
                    if (lastIndex < endIndex) {
                        tokenList.add(TextToken(message.message.substring(lastIndex, endIndex)))
                    }
                }
                // Add the special part
                tokenList.add(token)
                lastIndex = minOf(range.last + 1, message.message.length)
            }

            // Add any remaining plain text at the end of the message
            if (lastIndex < message.message.length) {
                tokenList.add(TextToken(message.message.substring(lastIndex)))
            }
            tokenList
        }
    }

    val currentUserDisplayName = UserManager.currentUser?.displayName ?: ""
    val isMentioned = remember(message.message, currentUserDisplayName) {
        if (currentUserDisplayName.isNotBlank()) {
            "\\b@?${Regex.escape(currentUserDisplayName)}\\b".toRegex(RegexOption.IGNORE_CASE).containsMatchIn(message.message)
        } else {
            false
        }
    }

    val highlightColor = when {
        isDeleted -> MaterialTheme.colorScheme.error.copy(alpha = 0.2f)
        isMentioned -> MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
        message.type == MessageType.SUBSCRIPTION -> MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
        message.type == MessageType.RAID -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.1f)
        message.type == MessageType.ANNOUNCEMENT -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.1f)
        message.type == MessageType.SYSTEM -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        else -> Color.Transparent
    }

    val isLongClickEnabled = message.author != null && message.author != UserManager.currentUser?.displayName
    var offsetX by remember { mutableStateOf(0f) }
    val swipeThreshold = 150f
    val scope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        scope.launch {
                            // animate back
                            // simple assignment instead of lower-level animate function to avoid unused import warnings
                            offsetX = 0f
                        }
                    }
                ) { change, dragAmount ->
                    change.consume()
                    val newOffsetX = (offsetX + dragAmount).coerceIn(-swipeThreshold, swipeThreshold)
                    if (kotlin.math.abs(newOffsetX) >= swipeThreshold) {
                        onReply(message)
                        offsetX = 0f // Reset after triggering
                    } else {
                        offsetX = newOffsetX
                    }
                }
            }
            .graphicsLayer(translationX = offsetX)
    ) {
        if (kotlin.math.abs(offsetX) > 0) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Reply,
                contentDescription = "Reply",
                modifier = Modifier
                    .align(if (offsetX > 0) Alignment.CenterStart else Alignment.CenterEnd)
                    .padding(horizontal = 16.dp)
                    .graphicsLayer(alpha = (kotlin.math.abs(offsetX) / swipeThreshold).coerceIn(0f, 1f))
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    enabled = isLongClickEnabled,
                    onClick = {},
                    onLongClick = { showMenu = true }
                )
                .background(highlightColor, shape = MaterialTheme.shapes.small)
                .padding(vertical = 4.dp, horizontal = 8.dp)
        ) {
            message.replyParentMsgBody?.let { parentMessageBody ->
                val parentAuthor = message.replyParentUserLogin ?: "Unknown User"
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.AutoMirrored.Filled.Reply, contentDescription = "Reply to", modifier = Modifier.size(14.dp), tint = Color.Gray)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "$parentAuthor: ${parentMessageBody.take(40)}${if (parentMessageBody.length > 40) "..." else ""}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray,
                        maxLines = 1
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.Top,
            ) {
                // Timestamp
                Text(
                    text = message.timestamp,
                    color = Color.Gray,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 2.dp) // Align baseline a bit better
                )
                Spacer(Modifier.width(8.dp))

                // Using FlowRow to handle line wrapping for the entire message content
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    // System message icon
                    when (message.type) {
                        MessageType.SUBSCRIPTION -> {
                            Icon(
                                imageVector = Icons.Default.Star,
                                contentDescription = "Subscription",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .size(18.dp)
                                    .align(Alignment.CenterVertically)
                            )
                        }

                        MessageType.RAID -> {
                            Icon(
                                imageVector = Icons.Default.People,
                                contentDescription = "Raid",
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier
                                    .size(18.dp)
                                    .align(Alignment.CenterVertically)
                            )
                        }

                        MessageType.ANNOUNCEMENT -> {
                            Icon(
                                imageVector = Icons.Default.Campaign,
                                contentDescription = "Announcement",
                                tint = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier
                                    .size(18.dp)
                                    .align(Alignment.CenterVertically)
                            )
                        }
                        MessageType.SYSTEM -> {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = "System Message",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .size(18.dp)
                                    .align(Alignment.CenterVertically)
                            )
                        }

                        else -> {}
                    }

                    // Badges
                    message.badges.forEach { badgeName ->
                        BadgeManager.getBadgeUrl(badgeName, broadcasterId)?.let { url ->
                            AsyncImage(
                                model = url,
                                contentDescription = badgeName,
                                modifier = Modifier
                                    .size(18.dp)
                                    .align(Alignment.CenterVertically)
                            )
                        }
                    }

                    // Author
                    if (message.author != null) {
                        Text(
                            text = "${message.author}:",
                            fontWeight = FontWeight.Bold,
                            color = authorColor,
                            modifier = Modifier
                                .align(Alignment.CenterVertically),
                            fontSize = 14.sp
                        )
                    }

                    // Render the tokenized message content
                    if (message.type == MessageType.SUBSCRIPTION) {
                        // Subscription/usernotice: the server supplies a "system-msg" which is the human-readable text.
                        // Render it plainly to avoid tokenization mismatches and to ensure clarity for subs/gifts.
                        Text(
                            text = message.message,
                            modifier = Modifier.align(Alignment.CenterVertically),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        tokens.forEach { token ->
                            when (token) {
                                is TextToken -> HighlightedTextToken(
                                    token = token,
                                    isDeleted = isDeleted,
                                    modifier = Modifier.align(Alignment.CenterVertically)
                                )

                                is EmoteToken -> AsyncImage(
                                    model = token.url,
                                    contentDescription = token.code,
                                    modifier = Modifier
                                        .height(24.dp)
                                        .align(Alignment.CenterVertically)
                                )

                                is UrlToken -> {
                                    val annotatedString = buildAnnotatedString {
                                        withStyle(
                                            style = SpanStyle(
                                                color = MaterialTheme.colorScheme.primary,
                                                textDecoration = TextDecoration.Underline
                                            )
                                        ) {
                                            append(token.url)
                                        }
                                    }
                                    Text(
                                        text = annotatedString,
                                        modifier = Modifier
                                            .align(Alignment.CenterVertically)
                                            .clickable { uriHandler.openUri(token.url) },
                                        style = LocalTextStyle.current.copy(fontSize = 14.sp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false }
        ) {
            DropdownMenuItem(
                text = { Text("Reply") },
                onClick = {
                    onReply(message)
                    showMenu = false
                }
            )
            DropdownMenuItem(text = { Text("Profile") }, onClick = {
                onShowProfile()
                showMenu = false
            })
            if (isCurrentUserModerator) {
                DropdownMenuItem(text = { Text("Time out (10m)") }, onClick = {
                    onTimeout()
                    showMenu = false
                })
                DropdownMenuItem(text = { Text("Ban") }, onClick = {
                    onBan()
                    showMenu = false
                })
            }
        }
    }
}


@OptIn(ExperimentalMaterialApi::class)
@Composable
fun ChatterListScreen(
    chatters: Map<String, List<String>>,
    isLoading: Boolean,
    onRefresh: () -> Unit,
    profileImageUrl: String,
    displayName: String,
    streamTitle: String?,
    viewerCount: Int?
) {
    val pullRefreshState = rememberPullRefreshState(refreshing = isLoading, onRefresh = onRefresh)

    Box(Modifier.pullRefresh(pullRefreshState)) {
        Surface(modifier = Modifier.fillMaxSize()) {
            if (isLoading && chatters.values.all { it.isEmpty() }) {
                Box(modifier = Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (!isLoading && chatters.values.all { it.isEmpty() }) {
                Box(modifier = Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                    Text("Could not load chatters or the chat is empty.", textAlign = TextAlign.Center)
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            AsyncImage(
                                model = profileImageUrl,
                                contentDescription = "Streamer avatar",
                                modifier = Modifier
                                    .size(64.dp)
                                    .clip(CircleShape)
                            )
                            Text(
                                text = displayName,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            streamTitle?.let {
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.bodyMedium,
                                    textAlign = TextAlign.Center
                                )
                            }
                            viewerCount?.let {
                                Text(
                                    text = "${formatViewerCount(it)} viewers",
                                    color = Color.Red,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                    item { // Add a title to the bottom sheet
                        Text(
                            text = "Chatters",
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                    chatters.forEach { (role, chatterList) ->
                        if (chatterList.isNotEmpty()) {
                            item {
                                Text(
                                    text = role,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(vertical = 8.dp, horizontal = 16.dp)
                                )
                            }
                            items(chatterList) { chatter ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(vertical = 4.dp, horizontal = 16.dp)
                                ) {
                                    Icon(Icons.Default.Person, contentDescription = role, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(chatter)
                                }
                            }
                        }
                    }
                }
            }
        }

        PullRefreshIndicator(
            refreshing = isLoading,
            state = pullRefreshState,
            modifier = Modifier.align(Alignment.TopCenter)
        )
    }
}


@Composable
fun UserInfoDialog(
    user: ChatMessage,
    messages: List<ChatMessage>,
    onDismiss: () -> Unit,
    onSendWhisper: (String) -> Unit = {}
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(
                    model = user.tags["profile-image-url"],
                    contentDescription = "User Avatar",
                    modifier = Modifier.size(40.dp).clip(CircleShape)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(user.author ?: "Unknown User")
            }
        },
        text = {
            LazyColumn {
                items(messages) { message ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = message.timestamp,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Text(message.message, style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                user.authorLogin?.let { onSendWhisper(it) }
            }) {
                Text("Send Whisper")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@Composable
fun PollCard(poll: Poll, onVote: (String, String) -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = poll.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(16.dp))

            poll.choices.forEach {
                PollChoiceItem(choice = it, totalVotes = poll.totalVotes, onVote = {
                    onVote(poll.id, it.id)
                })
                Spacer(modifier = Modifier.height(8.dp))
            }

            if (poll.status == "COMPLETED") {
                Text("Poll has ended", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
        }
    }
}

@Composable
fun PollChoiceItem(choice: PollChoice, totalVotes: Int, onVote: () -> Unit) {
    val percentage = if (totalVotes > 0) choice.votes.toFloat() / totalVotes else 0f
    val animatedPercentage by animateFloatAsState(targetValue = percentage, label = "")

    Column(modifier = Modifier.clickable(onClick = onVote)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = choice.title, modifier = Modifier.weight(1f))
            Text(text = "${(animatedPercentage * 100).toInt()}%", fontWeight = FontWeight.Bold)
        }
        Spacer(modifier = Modifier.height(4.dp))
        LinearProgressIndicator(progress = { animatedPercentage }, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
fun ChatInputBar(
    text: TextFieldValue,
    onTextChanged: (TextFieldValue) -> Unit,
    onSendClick: () -> Unit,
    onEmoteClick: () -> Unit,
    enabled: Boolean = true
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp)
            .height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = onTextChanged,
            modifier = Modifier.weight(1f),
            placeholder = { Text("Send a message") },
            singleLine = true,
            keyboardOptions = KeyboardOptions.Default.copy(
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = ImeAction.Send
            ),
            keyboardActions = KeyboardActions(
                onSend = {
                    if (text.text.isNotBlank()) {
                        onSendClick()
                    }
                }
            ),
            leadingIcon = {
                IconButton(onClick = onEmoteClick) {
                    Icon(Icons.Filled.Mood, contentDescription = "Open emote menu")
                }
            },
            enabled = enabled
        )

        Spacer(modifier = Modifier.width(8.dp))

        Surface(
            shape = CircleShape,
            color = if (text.text.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(
                alpha = 0.12f
            ),
            modifier = Modifier
                .fillMaxHeight()
                .aspectRatio(1f)
                .clickable(enabled = text.text.isNotBlank(), onClick = onSendClick)
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send",
                    tint = if (text.text.isNotBlank()) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface.copy(
                        alpha = 0.38f
                    )
                )
            }
        }
    }
}

private fun formatViewerCount(count: Int): String {
    return when {
        count >= 1_000_000 -> String.format(Locale.ROOT, "%.1fM", count / 1_000_000f)
        count >= 1_000 -> String.format(Locale.ROOT, "%.1fK", count / 1_000f)
        else -> count.toString()
    }
}
