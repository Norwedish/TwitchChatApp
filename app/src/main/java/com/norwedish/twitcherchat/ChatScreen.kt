package com.norwedish.twitcherchat

/**
 * UI level composables for displaying chat and related screens (chat messages, chatter list,
 * input bar, emote grid, and casting integration).
 *
 * This file contains composables and helper UI types used by the chat screen.
 */

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.os.IBinder
import android.os.Build
import android.util.Log
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
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
import coil.compose.AsyncImagePainter
import coil.request.ImageRequest
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.shape.RoundedCornerShape
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Locale
import androidx.core.graphics.toColorInt
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.norwedish.twitcherchat.ui.theme.LiveRed

// Define sealed classes for our message tokens at the top level
sealed class MessageToken
data class TextToken(val text: String) : MessageToken()
data class EmoteToken(val code: String, val url: String) : MessageToken()
data class UrlToken(val url: String) : MessageToken()


@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, androidx.compose.ui.text.ExperimentalTextApi::class)
/**
 * Primary composable that shows a chat for a given channel. Manages cast listeners, binds to the
 * ChatService and observes ChatViewModel state to render messages and input controls.
 */
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
    var showCastDialog by remember { mutableStateOf(false) } // state for cast dialog

    // Listen for logout events (token expiration, etc.) and navigate back to login
    DisposableEffect(Unit) {
        val logoutReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == UserManager.ACTION_USER_LOGGED_OUT) {
                    Log.d("ChatScreen", "Detected user logout (token expired). Navigating to login.")
                    onNavigateBack()
                }
            }
        }

        val intentFilter = IntentFilter(UserManager.ACTION_USER_LOGGED_OUT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(logoutReceiver, intentFilter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(logoutReceiver, intentFilter)
        }

        onDispose {
            context.unregisterReceiver(logoutReceiver)
        }
    }
    // Keep the screen on while chatting (cleared when leaving this screen)
    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

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

    // Tell the ViewModel which channel is active so it can start polling stream metadata (title/viewerCount)
    DisposableEffect(key1 = twitchUserId, key2 = channelName) {
        try {
            viewModel.setCurrentChannel(twitchUserId, channelName)
        } catch (_: Exception) {}
        onDispose {
            try {
                // Clear the current channel to stop polling
                viewModel.setCurrentChannel("", "")
            } catch (_: Exception) {}
        }
    }

    // Helper functions to clear local UI state - read the value first to avoid analyzer "assigned value is never read" warnings
    fun closeCastDialog() {
        if (showCastDialog) showCastDialog = false
    }

    // Initialize whisper view model and service
    DisposableEffect(Unit) {
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
    val visibleEmotes by viewModel.visibleEmotes.collectAsState()
    val selectedEmoteTab by viewModel.selectedEmoteTab.collectAsState()
    val availableEmoteTabs by viewModel.availableEmoteTabs.collectAsState()
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

    // Pending destructive mod action awaiting confirmation (ban / timeout)
    var pendingModMessage by remember { mutableStateOf<ChatMessage?>(null) }
    var pendingModAction by remember { mutableStateOf<String?>(null) }

    val uriHandler = LocalUriHandler.current // uri handler for opening external links
    // Observe Cast debug state to surface errors and provide retry/fallback actions
    // (currently unused; keep the state in CastManager)

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
            onDismiss = { viewModel.onDismissUserProfile() }
        )
    }

    // Confirmation dialog for destructive moderation actions
    if (pendingModMessage != null && pendingModAction != null) {
        val msg = pendingModMessage!!
        val action = pendingModAction!!
        val isBan = action == "ban"
        AlertDialog(
            onDismissRequest = { pendingModMessage = null; pendingModAction = null },
            title = {
                Text(
                    if (isBan) stringResource(R.string.confirm_ban_title, msg.author ?: "")
                    else stringResource(R.string.confirm_timeout_title, msg.author ?: "")
                )
            },
            text = {
                Text(
                    if (isBan) stringResource(R.string.confirm_ban_text)
                    else stringResource(R.string.confirm_timeout_text)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    msg.author?.let { author ->
                        if (isBan) viewModel.onBan(author) else viewModel.onTimeout(author)
                    }
                    pendingModMessage = null
                    pendingModAction = null
                }) {
                    Text(if (isBan) stringResource(R.string.ban) else stringResource(R.string.timeout_10m))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingModMessage = null; pendingModAction = null }) {
                    Text(stringResource(R.string.cancel))
                }
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
                        Box {
                            AsyncImage(
                                model = profileImageUrl,
                                contentDescription = stringResource(R.string.chatters),
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape),
                                contentScale = ContentScale.Crop
                            )
                            // Live indicator dot on the avatar
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .clip(CircleShape)
                                    .background(LiveRed)
                                    .align(Alignment.BottomEnd)
                                    .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = displayName,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                viewerCount?.let {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Surface(
                                        color = LiveRed.copy(alpha = 0.15f),
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Visibility,
                                                contentDescription = stringResource(R.string.viewers_content_description),
                                                modifier = Modifier.size(12.dp),
                                                tint = LiveRed
                                            )
                                            Spacer(modifier = Modifier.width(3.dp))
                                            Text(
                                                text = formatViewerCount(it),
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = LiveRed
                                            )
                                        }
                                    }
                                }
                            }
                            streamTitle?.let {
                                Text(
                                    text = it,
                                    fontSize = 11.sp,
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
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                },
                actions = {
                    // Chromecast / Cast button - show native MediaRouteButton when available
                    // (connection state lives in a slim banner above the message list instead)
                    CastButton(modifier = Modifier.size(48.dp))
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) // end TopAppBar
        } // end topBar lambda
    ) { paddingValues ->
         Column(
             Modifier
                 .fillMaxSize()
                 .padding(paddingValues)
                 .navigationBarsPadding()
                 .imePadding()
        ) {
            // Slim connection banner above the messages, only shown when NOT connected
            AnimatedVisibility(visible = connectionState != ConnectionState.CONNECTED) {
                ConnectionStateBanner(state = connectionState)
            }
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
                            // Destructive actions ask for confirmation first (see dialog above)
                            onTimeout = { pendingModMessage = message; pendingModAction = "timeout" },
                            onBan = { pendingModMessage = message; pendingModAction = "ban" },
                            onReply = { viewModel.onReply(message) },
                            broadcasterId = twitchUserId // pass the channel's broadcaster id so badges prefer channel-specific ones
                        )
                    }
                }

                if (messages.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = when (connectionState) {
                                    ConnectionState.CONNECTING -> Icons.Default.Sync
                                    ConnectionState.CONNECTED -> Icons.Default.ChatBubbleOutline
                                    ConnectionState.ERROR -> Icons.Default.ErrorOutline
                                    ConnectionState.DISCONNECTED -> Icons.Default.WifiOff
                                },
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = when (connectionState) {
                                    ConnectionState.ERROR -> MaterialTheme.colorScheme.error
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = when (connectionState) {
                                    ConnectionState.CONNECTING -> stringResource(R.string.connecting)
                                    ConnectionState.CONNECTED -> stringResource(R.string.waiting_for_messages)
                                    ConnectionState.ERROR -> stringResource(R.string.connection_error)
                                    ConnectionState.DISCONNECTED -> stringResource(R.string.disconnected)
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = when (connectionState) {
                                    ConnectionState.CONNECTED -> MaterialTheme.colorScheme.tertiary
                                    ConnectionState.ERROR -> MaterialTheme.colorScheme.error
                                    else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                }
                            )
                        }
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
                        color = MaterialTheme.colorScheme.primary,
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
                                .padding(horizontal = 14.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                Icons.Default.ArrowDownward,
                                contentDescription = stringResource(R.string.scroll_to_bottom),
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onPrimary
                            )
                            Text(
                                text = stringResource(R.string.new_messages, unreadMessageCount),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
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
                                Icon(
                                    Icons.AutoMirrored.Filled.Reply,
                                    contentDescription = stringResource(R.string.replying_to, reply.author ?: reply.authorLogin ?: ""),
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(R.string.replying_to, reply.author ?: reply.authorLogin ?: ""),
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
                                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.clear_reply))
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
                                text = stringResource(R.string.you_are_banned),
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
                val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
                var emoteQuery by remember { mutableStateOf("") }
                val filteredEmotes = remember(visibleEmotes, emoteQuery) {
                    if (emoteQuery.isBlank()) visibleEmotes
                    else visibleEmotes.filter { it.code.contains(emoteQuery, ignoreCase = true) }
                }
                ModalBottomSheet(
                    onDismissRequest = { viewModel.onEmoteMenuToggled() },
                    sheetState = sheetState,
                    containerColor = MaterialTheme.colorScheme.surface
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 440.dp)
                    ) {
                        // Header
                        Text(
                            stringResource(R.string.select_an_emote),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp),
                            fontWeight = FontWeight.Bold
                        )

                        // Search field to filter emotes by code
                        OutlinedTextField(
                            value = emoteQuery,
                            onValueChange = { emoteQuery = it },
                            placeholder = { Text(stringResource(R.string.emote_search_hint)) },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                            trailingIcon = {
                                if (emoteQuery.isNotEmpty()) {
                                    IconButton(onClick = { emoteQuery = "" }) {
                                        Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.clear_search))
                                    }
                                }
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(24.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        )

                        // Log available emotes for debugging
                        LaunchedEffect(visibleEmotes, availableEmoteTabs) {
                            Log.d("EmoteMenu", "Visible emotes count: ${visibleEmotes.size}")
                            Log.d("EmoteMenu", "Available tabs: ${availableEmoteTabs.map { it.label }}")
                            Log.d("EmoteMenu", "Selected tab: ${selectedEmoteTab.label}")
                        }

                        val tabs = availableEmoteTabs

                        // Emote grid - takes most of the space
                        Box(modifier = Modifier.weight(1f)) {
                            if (filteredEmotes.isEmpty()) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier.padding(16.dp)
                                    ) {
                                        if (tabs.isEmpty()) {
                                            Icon(Icons.Default.Error, contentDescription = stringResource(R.string.no_emotes_loaded), modifier = Modifier.size(48.dp))
                                            Text(stringResource(R.string.no_emotes_loaded), style = MaterialTheme.typography.bodyMedium)
                                        } else if (emoteQuery.isNotBlank()) {
                                            Icon(Icons.Default.SearchOff, contentDescription = null, modifier = Modifier.size(48.dp))
                                            Text(
                                                stringResource(R.string.no_results, emoteQuery),
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                        } else {
                                            Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(48.dp))
                                            Text(
                                                stringResource(R.string.no_emotes_for_tab, selectedEmoteTab.label),
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                        }
                                    }
                                }
                            } else {
                                LazyVerticalGrid(
                                    columns = GridCells.Adaptive(minSize = 56.dp),
                                    contentPadding = PaddingValues(8.dp),
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    items(filteredEmotes, key = { it.provider.name + ":" + it.id }) { emote ->
                                        Column(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(4.dp))
                                                .clickable { viewModel.onEmoteSelected(emote.code) }
                                                .padding(4.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            AsyncImage(
                                                model = emote.url,
                                                contentDescription = emote.code,
                                                modifier = Modifier.size(40.dp),
                                                contentScale = ContentScale.Fit
                                            )
                                            Text(
                                                emote.code,
                                                style = MaterialTheme.typography.labelSmall,
                                                fontSize = 10.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.padding(top = 2.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Tab row at the bottom for platform selection
                        if (tabs.isNotEmpty()) {
                            HorizontalDivider(modifier = Modifier.fillMaxWidth())
                            ScrollableTabRow(
                                selectedTabIndex = tabs.indexOf(selectedEmoteTab).coerceAtLeast(0),
                                containerColor = MaterialTheme.colorScheme.surface,
                                contentColor = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                tabs.forEach { tab ->
                                    Tab(
                                        selected = selectedEmoteTab == tab,
                                        onClick = { viewModel.onEmoteTabSelected(tab) },
                                        text = { Text(tab.label, fontSize = 11.sp) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Chromecast / Cast dialog - safe fallback to open the stream in a browser
    if (showCastDialog) {
        AlertDialog(
            onDismissRequest = { closeCastDialog() },
            title = { Text(stringResource(R.string.cast_to_device)) },
            text = { Text(stringResource(R.string.cast_select_device)) },
            confirmButton = {
                TextButton(onClick = {
                    // Fallback action: open the stream URL in a browser
                    uriHandler.openUri("https://www.twitch.tv/$channelName")
                    closeCastDialog()
                }) {
                    Text(stringResource(R.string.open_in_browser))
                }
            },
            dismissButton = {
                TextButton(onClick = { closeCastDialog() }) {
                    Text(stringResource(R.string.cancel))
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
            color = if (isDeleted) Color.Gray else MaterialTheme.colorScheme.onSurface,
            textDecoration = if (isDeleted) TextDecoration.LineThrough else null
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
        color = if (isDeleted) Color.Gray else MaterialTheme.colorScheme.onSurface,
        textDecoration = if (isDeleted) TextDecoration.LineThrough else null
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

    // Check if someone is replying to the current user's own message (red highlight for replies TO our messages only)
    val currentUserLogin = UserManager.currentUser?.login ?: ""
    val isReplyToOwnMessage = remember(message.replyParentMsgId, message.tags) {
        // Only highlight if: this is a reply AND the replied-to user is the current user.
        // Note: the correct tag is "reply-parent-user-login"; "reply-parent-user-id" holds the numeric user ID.
        message.replyParentMsgId != null &&
        !message.tags.isNullOrEmpty() &&
        message.tags["reply-parent-user-login"]?.equals(currentUserLogin, ignoreCase = true) == true
    }

    val highlightColor = when {
        isDeleted -> MaterialTheme.colorScheme.error.copy(alpha = 0.2f)
        isMentioned -> Color(0xFFE53E3E).copy(alpha = 0.15f) // Red highlight for mentions (text stays blue in HighlightedTextToken)
        isReplyToOwnMessage -> Color(0xFFE53E3E).copy(alpha = 0.15f) // Red highlight for replies to own messages
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
                contentDescription = stringResource(R.string.cd_reply),
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
                .semantics(mergeDescendants = true) {
                    // Single TalkBack node per chat message: "author: message"
                    contentDescription = buildString {
                        message.replyParentMsgBody?.let { append("Reply to: $it. ") }
                        if (message.author != null) append("${message.author}: ")
                        append(message.message)
                    }
                }
                .background(highlightColor, shape = MaterialTheme.shapes.medium)
                .padding(vertical = 6.dp, horizontal = 10.dp)
        ) {
            message.replyParentMsgBody?.let { parentMessageBody ->
                // Use replyParentDisplayName if available, otherwise try to resolve from known users or fallback to login
                val parentAuthor = message.replyParentDisplayName ?: run {
                    // Try to match the login against current user's display name
                    val currentLogin = UserManager.currentUser?.login?.lowercase()
                    val currentDisplayName = UserManager.currentUser?.displayName
                    if (currentLogin != null && currentDisplayName != null && message.replyParentUserLogin?.lowercase() == currentLogin) {
                        currentDisplayName
                    } else {
                        message.replyParentUserLogin ?: "Unknown User"
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.AutoMirrored.Filled.Reply, contentDescription = stringResource(R.string.cd_reply_to), modifier = Modifier.size(14.dp), tint = Color.Gray)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "$parentAuthor: ${parentMessageBody.take(40)}${if (parentMessageBody.length > 40) "…" else ""}",
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
                                contentDescription = stringResource(R.string.cd_subscription),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .size(18.dp)
                                    .align(Alignment.CenterVertically)
                            )
                        }

                        MessageType.RAID -> {
                            Icon(
                                imageVector = Icons.Default.People,
                                contentDescription = stringResource(R.string.cd_raid),
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier
                                    .size(18.dp)
                                    .align(Alignment.CenterVertically)
                            )
                        }

                        MessageType.ANNOUNCEMENT -> {
                            Icon(
                                imageVector = Icons.Default.Campaign,
                                contentDescription = stringResource(R.string.cd_announcement),
                                tint = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier
                                    .size(18.dp)
                                    .align(Alignment.CenterVertically)
                            )
                        }
                        MessageType.SYSTEM -> {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = stringResource(R.string.cd_system_message),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .size(18.dp)
                                    .align(Alignment.CenterVertically)
                            )
                        }

                        else -> {}
                    }

                    // Display all user badges and icons (Twitch official, channel custom, and global custom badges)
                    // Unified through UserIconManager which handles all badge types sorted by priority
                    val userIconUrls = UserIconManager.getUserIconUrls(
                        badges = message.badges,
                        messageType = message.type,
                        broadcasterId = broadcasterId,
                        authorId = message.tags["user-id"],
                        currentBroadcasterId = broadcasterId
                    )

                    userIconUrls.forEach { userIcon ->
                        AsyncImage(
                            model = userIcon.url,
                            contentDescription = userIcon.contentDescription,
                            modifier = Modifier
                                .size(18.dp)
                                .align(Alignment.CenterVertically)
                        )
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
                    if (message.type == MessageType.SUBSCRIPTION || message.type == MessageType.SYSTEM) {
                        // Render system/sub messages plainly
                        Text(
                            text = message.message,
                            modifier = Modifier.align(Alignment.CenterVertically),
                            fontSize = 14.sp,
                            fontWeight = if (message.type == MessageType.SUBSCRIPTION) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (message.type == MessageType.SYSTEM) {
                                if (message.tags["client"]?.contains("failed") == true || message.tags["client"]?.contains("unconfirmed") == true)
                                    MaterialTheme.colorScheme.error
                                else if (message.tags["client"] == "pending_send")
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
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
                text = { Text(stringResource(R.string.reply)) },
                onClick = {
                    onReply(message)
                    showMenu = false
                }
            )
            DropdownMenuItem(text = { Text(stringResource(R.string.profile)) }, onClick = {
                onShowProfile()
                showMenu = false
            })
            if (isCurrentUserModerator) {
                DropdownMenuItem(text = { Text(stringResource(R.string.timeout_10m)) }, onClick = {
                    onTimeout()
                    showMenu = false
                })
                DropdownMenuItem(text = { Text(stringResource(R.string.ban)) }, onClick = {
                    onBan()
                    showMenu = false
                })
            }
        }
    }
}


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
    PullToRefreshBox(
        isRefreshing = isLoading,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize()
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            if (isLoading && chatters.values.all { it.isEmpty() }) {
                Box(modifier = Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (!isLoading && chatters.values.all { it.isEmpty() }) {
                Box(modifier = Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.could_not_load_chatters), textAlign = TextAlign.Center)
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
                                contentDescription = null,
                                modifier = Modifier
                                    .size(64.dp)
                                    .clip(CircleShape),
                                contentScale = ContentScale.Crop
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
                                Surface(
                                    color = LiveRed.copy(alpha = 0.15f),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            Icons.Default.Visibility,
                                            contentDescription = null,
                                            modifier = Modifier.size(14.dp),
                                            tint = LiveRed
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = stringResource(R.string.viewers_count, formatViewerCount(it)),
                                            color = LiveRed,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }
                    item { // Title of the bottom sheet
                        Text(
                            text = stringResource(R.string.chatters),
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                    chatters.forEach { (role, chatterList) ->
                        if (chatterList.isNotEmpty()) {
                            item {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(vertical = 8.dp, horizontal = 16.dp)
                                ) {
                                    Icon(
                                        imageVector = roleIcon(role),
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "$role (${chatterList.size})",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                            items(chatterList) { chatter ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(vertical = 4.dp, horizontal = 16.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Person,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(chatter)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun roleIcon(role: String): ImageVector = when (role) {
    "Broadcaster" -> Icons.Default.Star
    "Moderators" -> Icons.Default.Shield
    "VIPs" -> Icons.Default.WorkspacePremium
    else -> Icons.Default.People
}


@Composable
fun UserInfoDialog(
    user: ChatMessage,
    messages: List<ChatMessage>,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { /* no-op confirm button to satisfy Material3 overload */ },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(
                    model = user.tags["profile-image-url"],
                    contentDescription = null,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape),
                    contentScale = ContentScale.Crop
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(user.author ?: "Unknown User", fontWeight = FontWeight.Bold)
                    Text(
                        text = stringResource(R.string.messages_in_chat, messages.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
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
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
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
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Poll,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = poll.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.votes_count, poll.totalVotes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))

            poll.choices.forEach {
                PollChoiceItem(choice = it, totalVotes = poll.totalVotes, onVote = {
                    onVote(poll.id, it.id)
                })
                Spacer(modifier = Modifier.height(8.dp))
            }

            if (poll.status == "COMPLETED") {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    stringResource(R.string.poll_ended),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
            }
        }
    }
}

@Composable
fun PollChoiceItem(choice: PollChoice, totalVotes: Int, onVote: () -> Unit) {
    val percentage = if (totalVotes > 0) choice.votes.toFloat() / totalVotes else 0f
    val animatedPercentage by animateFloatAsState(targetValue = percentage, label = "pollProgress")

    Column(modifier = Modifier.clickable(onClick = onVote)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = choice.title,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(text = "${(animatedPercentage * 100).toInt()}%", fontWeight = FontWeight.Bold)
        }
        Spacer(modifier = Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { animatedPercentage },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp)),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surface
        )
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
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 2.dp
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 4.dp, end = 8.dp)
        ) {
            IconButton(
                onClick = onEmoteClick,
                enabled = enabled,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    Icons.Filled.Mood,
                    contentDescription = stringResource(R.string.emote_menu)
                )
            }
            OutlinedTextField(
                value = text,
                onValueChange = onTextChanged,
                modifier = Modifier
                    .weight(1f)
                    .defaultMinSize(minHeight = 48.dp),
                placeholder = { Text(stringResource(R.string.send_a_message)) },
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
                maxLines = 4,
                shape = RoundedCornerShape(24.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent
                ),
                enabled = enabled
            )
            Box(
                modifier = Modifier.size(48.dp),
                contentAlignment = Alignment.Center
            ) {
                FilledIconButton(
                    onClick = onSendClick,
                    enabled = enabled && text.text.isNotBlank(),
                    modifier = Modifier.size(40.dp),
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = if (text.text.isNotBlank()) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                        contentColor = if (text.text.isNotBlank()) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    )
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = stringResource(R.string.send),
                        tint = if (text.text.isNotBlank()) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    )
                }
            }
        }
    }
}

/**
 * Slim full-width banner above the chat message list showing the IRC connection state.
 * Only rendered when the chat is NOT connected, so the top bar stays uncluttered.
 */
@Composable
private fun ConnectionStateBanner(state: ConnectionState) {
    val color = when (state) {
        ConnectionState.CONNECTED -> MaterialTheme.colorScheme.tertiary
        ConnectionState.CONNECTING -> Color(0xFFFFB300)
        ConnectionState.ERROR -> MaterialTheme.colorScheme.error
        ConnectionState.DISCONNECTED -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val label = when (state) {
        ConnectionState.CONNECTED -> stringResource(R.string.connected)
        ConnectionState.CONNECTING -> stringResource(R.string.connecting)
        ConnectionState.ERROR -> stringResource(R.string.connection_error)
        ConnectionState.DISCONNECTED -> stringResource(R.string.disconnected)
    }
    Surface(
        color = color.copy(alpha = 0.12f),
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = label
                liveRegion = LiveRegionMode.Polite // announce connection changes to TalkBack
            }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (state == ConnectionState.CONNECTING) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = color
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(color)
                )
            }
            Text(
                text = label,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = color,
                maxLines = 1
            )
        }
    }
}

// formatViewerCount is shared via CommonUi.kt
