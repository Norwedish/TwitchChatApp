package com.norwedish.twitcherchat

/**
 * App entry point Activity which hosts the Compose navigation graph.
 * Responsible for wiring top-level UI, handling deep links, and managing Cast initialization.
 */

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.norwedish.twitcherchat.ui.theme.LiveRed
import com.norwedish.twitcherchat.ui.theme.TwitcherChatTheme
import kotlinx.coroutines.launch
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Simple object that centralizes route names used by the app's NavHost.
 */
object AppRoutes {
    const val LOGIN = "login"
    const val MAIN = "main"
    const val SEARCH = "search"
    const val SETTINGS = "settings"
    const val CHAT = "chat/{channelName}/{twitchUserId}/{displayName}/{profileImageUrl}"

    fun chatRoute(channelName: String, twitchUserId: String, displayName: String, profileImageUrl: String): String {
        val encodedUrl = URLEncoder.encode(profileImageUrl, "UTF-8")
        return "chat/$channelName/$twitchUserId/$displayName/$encodedUrl"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
/**
 * The Activity that starts the Compose UI, registers background receivers, and schedules periodic work.
 * It wires up navigation and handles permission requests and lifecycle events for global app concerns.
 */
class MainActivity : AppCompatActivity() {
    private val mainViewModel: MainViewModel by viewModels()

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Handle the permission result if needed
    }

    private val streamUpReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == LiveStreamWorker.ACTION_STREAM_UP) {
                mainViewModel.loadFollowedStreams()
            }
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge-to-edge: draw behind system bars; Compose handles insets.
        WindowCompat.setDecorFitsSystemWindows(window, false)

        askNotificationPermission()

        if (UserManager.accessToken != null) {
            scheduleLiveStreamWorker()
        }

        val intentFilter = IntentFilter(LiveStreamWorker.ACTION_STREAM_UP)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(streamUpReceiver, intentFilter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(streamUpReceiver, intentFilter)
        }

        setContent {
            TwitcherChatTheme {
                val navController = rememberNavController()
                val scope = rememberCoroutineScope()

                if (UserManager.isLoading) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                } else {
                    NavHost(
                        navController = navController,
                        startDestination = if (UserManager.accessToken == null) AppRoutes.LOGIN else AppRoutes.MAIN
                    ) {
                        composable(AppRoutes.LOGIN) {
                            LoginScreen(onLoginSuccess = { token ->
                                try {
                                    val user = TwitchApi.getCurrentUser(token, UserManager.CLIENT_ID)
                                    if (user == null) {
                                        getString(R.string.login_failed)
                                    } else {
                                        UserManager.login(token, user)
                                        scheduleLiveStreamWorker()
                                        navController.navigate(AppRoutes.MAIN) {
                                            popUpTo(AppRoutes.LOGIN) { inclusive = true }
                                        }
                                        null
                                    }
                                } catch (e: TwitchApiException) {
                                    Log.e(
                                        "MainActivity",
                                        "Login failed: HTTP ${e.status}",
                                        e
                                    )
                                    if (e.status == 401) {
                                        getString(R.string.login_failed_unauthorized)
                                    } else {
                                        getString(
                                            R.string.login_failed_reason,
                                            e.message ?: getString(R.string.login_failed)
                                        )
                                    }
                                } catch (e: Exception) {
                                    Log.e("MainActivity", "Login failed", e)
                                    if (e is java.io.IOException) {
                                        getString(R.string.login_failed_network)
                                    } else {
                                        getString(
                                            R.string.login_failed_reason,
                                            e.message ?: getString(R.string.login_failed)
                                        )
                                    }
                                }
                            })
                        }

                        composable(AppRoutes.MAIN) {
                            // Run once per login session (not on every navigation back to home),
                            // otherwise a screen re-entry could log the user out again.
                            LaunchedEffect(UserManager.accessToken) {
                                // A token that Twitch no longer accepts (expired/revoked) makes every
                                // authenticated call fail with 401 while the app still looks logged
                                // in. Send the user to the login screen instead of showing an app
                                // that silently can't do anything.
                                if (UserManager.accessToken != null && !UserManager.isTokenValid()) {
                                    Log.w("MainActivity", "Stored Twitch token is no longer valid; logging out")
                                    UserManager.logout()
                                    cancelLiveStreamWorker()
                                    navController.navigate(AppRoutes.LOGIN) {
                                        popUpTo(AppRoutes.MAIN) { inclusive = true }
                                    }
                                    return@LaunchedEffect
                                }
                                mainViewModel.loadFollowedStreams()
                                // Diagnostics: show which client issued the token and which scopes
                                // it has, so channel points failures can be diagnosed from logcat.
                                UserManager.accessToken?.let { token ->
                                    val tokenClient = TwitchApi.validateOAuthToken(token)
                                    Log.d(
                                        "MainActivity",
                                        "Token client_id=$tokenClient, app client_id=${UserManager.CLIENT_ID}, " +
                                            "gql client_id=$GQL_CLIENT_ID, gql accepted=${TwitchApi.isGqlTokenValid(token)}"
                                    )
                                }
                            }
                            MainScreen(
                                viewModel = mainViewModel,
                                onStreamClick = { enrichedStream ->
                                    navController.navigate(
                                        AppRoutes.chatRoute(
                                            channelName = enrichedStream.stream.userLogin,
                                            twitchUserId = enrichedStream.stream.userId,
                                            displayName = enrichedStream.user.displayName,
                                            profileImageUrl = enrichedStream.user.profileImageUrl
                                        )
                                    )
                                },
                                onSearchClick = { navController.navigate(AppRoutes.SEARCH) },
                                onLogoutClick = {
                                    scope.launch {
                                        UserManager.logout()
                                        cancelLiveStreamWorker()
                                        navController.navigate(AppRoutes.LOGIN) {
                                            popUpTo(AppRoutes.MAIN) { inclusive = true }
                                        }
                                    }
                                },
                                onSettingsClick = { navController.navigate(AppRoutes.SETTINGS) }
                            )
                        }
                        composable(AppRoutes.SETTINGS) {
                            ChatSettingsScreen(onNavigateBack = { navController.popBackStack() })
                        }

                        composable(
                            route = AppRoutes.CHAT,
                            arguments = listOf(
                                navArgument("channelName") { type = NavType.StringType },
                                navArgument("twitchUserId") { type = NavType.StringType },
                                navArgument("displayName") { type = NavType.StringType },
                                navArgument("profileImageUrl") { type = NavType.StringType }
                            )
                        ) { backStackEntry ->
                            val channelName = backStackEntry.arguments?.getString("channelName") ?: ""
                            val twitchUserId = backStackEntry.arguments?.getString("twitchUserId") ?: ""
                            val displayName = backStackEntry.arguments?.getString("displayName") ?: ""
                            val profileImageUrl = backStackEntry.arguments?.getString("profileImageUrl")?.let {
                                URLDecoder.decode(it, "UTF-8")
                            } ?: ""

                            ChatScreen(
                                channelName = channelName,
                                twitchUserId = twitchUserId,
                                displayName = displayName,
                                profileImageUrl = profileImageUrl,
                                viewModel = viewModel(),
                                onNavigateBack = { navController.popBackStack() }
                            )
                        }

                        composable(AppRoutes.SEARCH) {
                            SearchScreen(
                                onNavigateToChat = { channel ->
                                    navController.navigate(
                                        AppRoutes.chatRoute(
                                            channelName = channel.broadcasterLogin,
                                            twitchUserId = channel.id,
                                            displayName = channel.displayName,
                                            profileImageUrl = channel.thumbnailUrl
                                        )
                                    )
                                },
                                onNavigateBack = { navController.popBackStack() }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(streamUpReceiver)
    }

    private fun scheduleLiveStreamWorker() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        // Shared callback for stream status changes: refresh the home list
        val onStreamStatusChange: () -> Unit = {
            lifecycleScope.launch {
                mainViewModel.refreshFollowedStreams()
            }
        }

        // Initialize EventSub manager for near-instant notifications
        val user = UserManager.currentUser
        val token = UserManager.accessToken
        if (user != null && token != null) {
            EventSubManager.initialize(this,
                onOnline = { userId, broadcasterName ->
                    onStreamStatusChange()
                },
                onOffline = { userId ->
                    onStreamStatusChange()
                }
            )
            EventSubManager.start(user.id, token, UserManager.CLIENT_ID)

            // Register all followed channels: EventSub for the first N (near-instant
            // notifications), intelligent polling for the rest.
            lifecycleScope.launch {
                try {
                    val followed = TwitchApi.getFollowedChannels(user.id, token, UserManager.CLIENT_ID)
                    followed.forEachIndexed { index, channel ->
                        if (index < EventSubManager.MAX_EVENTSUB_SUBSCRIPTIONS) {
                            EventSubManager.subscribeToStreamer(channel.broadcasterId, isHighPriority = index < 10)
                        } else {
                            IntelligentPollingManager.addStreamerForPolling(
                                channel.broadcasterId,
                                channel.broadcasterLogin,
                                channel.broadcasterName
                            )
                        }
                    }
                    Log.d("MainActivity", "Registered ${followed.size} followed channels (EventSub + polling)")
                } catch (e: Exception) {
                    Log.w("MainActivity", "Failed to register followed channels: ${e.message}")
                }
            }
        }

        // Initialize Intelligent Polling for streamers beyond EventSub limit
        IntelligentPollingManager.initialize(
            onOnline = { userId, broadcasterName ->
                onStreamStatusChange()
            },
            onOffline = { userId ->
                onStreamStatusChange()
            }
        )

        // Schedule intelligent polling worker (10 seconds for quick batch checks)
        val pollingConstraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val pollingWorkRequest = PeriodicWorkRequestBuilder<IntelligentPollingWorker>(
            10, TimeUnit.SECONDS
        )
            .setConstraints(pollingConstraints)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            IntelligentPollingWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.REPLACE,
            pollingWorkRequest
        )

        // Keep old periodic work but with reduced frequency (fallback for edge cases)
        val periodicWorkRequest = PeriodicWorkRequestBuilder<LiveStreamWorker>(
            15, TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            LiveStreamWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.REPLACE,
            periodicWorkRequest
        )
    }

    private fun cancelLiveStreamWorker() {
        EventSubManager.stop()
        IntelligentPollingManager.clear()
        WorkManager.getInstance(this).cancelUniqueWork(LiveStreamWorker.WORK_NAME)
        WorkManager.getInstance(this).cancelUniqueWork(IntelligentPollingWorker.WORK_NAME)
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
/**
 * The main screen composable that shows the list of live followed channels, the app bar
 * and navigation actions. State comes from the provided MainViewModel.
 */
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onStreamClick: (EnrichedStream) -> Unit,
    onSearchClick: () -> Unit,
    onLogoutClick: () -> Unit,
    onSettingsClick: () -> Unit
) {
    val streams by viewModel.followedStreams.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val followedChannelCount by viewModel.followedChannelCount.collectAsState()
    val currentUser = UserManager.currentUser
    var menuExpanded by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            painter = painterResource(id = R.drawable.transparentlogo),
                            contentDescription = null,
                            modifier = Modifier.size(34.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = stringResource(R.string.app_name),
                            style = MaterialTheme.typography.titleLarge
                        )
                    }
                },
                actions = {
                    if (currentUser != null) {
                        Box {
                            IconButton(onClick = { menuExpanded = true }) {
                                AsyncImage(
                                    model = currentUser.profileImageUrl,
                                    contentDescription = currentUser.displayName,
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(CircleShape),
                                    contentScale = ContentScale.Crop
                                )
                            }
                            DropdownMenu(
                                expanded = menuExpanded,
                                onDismissRequest = { menuExpanded = false }
                            ) {
                                // Show which account is signed in: a wrong account is the
                                // number one reason for "I don't see my followed channels".
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(
                                                text = currentUser.displayName,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Text(
                                                text = "@${currentUser.login}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    },
                                    onClick = {},
                                    enabled = false
                                )
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.log_out)) },
                                    leadingIcon = {
                                        Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null)
                                    },
                                    onClick = {
                                        menuExpanded = false
                                        onLogoutClick()
                                    }
                                )
                            }
                        }
                    }
                    IconButton(onClick = onSettingsClick) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = stringResource(R.string.settings)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onSearchClick,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                Icon(
                    Icons.Default.Search,
                    contentDescription = stringResource(R.string.search_streamers)
                )
            }
        }
    ) { paddingValues ->
        PullToRefreshBox(
            isRefreshing = isLoading,
            onRefresh = { viewModel.loadFollowedStreams() },
            modifier = Modifier.padding(paddingValues)
        ) {
            when {
                isLoading && streams.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }

                errorMessage != null && streams.isEmpty() -> {
                    EmptyState(
                        icon = Icons.Default.ErrorOutline,
                        title = stringResource(R.string.error_loading),
                        subtitle = errorMessage,
                        actionLabel = stringResource(R.string.retry),
                        onAction = { viewModel.loadFollowedStreams() }
                    )
                }

                streams.isEmpty() -> {
                    EmptyState(
                        icon = Icons.Default.VideocamOff,
                        title = stringResource(R.string.no_live_streams),
                        subtitle = followedChannelCount?.let { count ->
                            if (count == 0) stringResource(R.string.follows_empty)
                            else stringResource(R.string.follows_none_live, count)
                        } ?: stringResource(R.string.pull_to_refresh_hint),
                        actionLabel = stringResource(R.string.refresh),
                        onAction = { viewModel.loadFollowedStreams() }
                    )
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        errorMessage?.let { message ->
                            item {
                                RefreshErrorBanner(
                                    message = message,
                                    onRetry = { viewModel.loadFollowedStreams() }
                                )
                            }
                        }
                        item {
                            Text(
                                text = stringResource(R.string.live_now_count, streams.size),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(bottom = 2.dp)
                            )
                        }
                        items(streams, key = { it.stream.id }) { stream ->
                            StreamCard(
                                stream = stream,
                                onClick = { onStreamClick(stream) }
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Card for a live followed stream: 16:9 thumbnail with LIVE badge and viewer count overlay,
 * plus avatar, display name, stream title and game chip below.
 */
@Composable
fun StreamCard(stream: EnrichedStream, onClick: () -> Unit) {
    val thumbnailUrl = remember(stream.stream.thumbnailUrl) {
        stream.stream.thumbnailUrl
            .replace("{width}", "640")
            .replace("{height}", "360")
    }

    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription =
                    "${stream.user.displayName}, live, ${stream.stream.title}, ${formatViewerCount(stream.stream.viewerCount)} viewers"
            },
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Box {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(thumbnailUrl)
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f),
                contentScale = ContentScale.Crop
            )
            // Gradient scrim so the overlays stay readable
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.25f),
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.45f)
                            )
                        )
                    )
            )
            // LIVE badge
            Surface(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(10.dp),
                color = LiveRed,
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(Color.White)
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = stringResource(R.string.live),
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    )
                }
            }
            // Viewer count
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(10.dp),
                color = Color.Black.copy(alpha = 0.65f),
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Visibility,
                        contentDescription = stringResource(R.string.viewers_content_description),
                        modifier = Modifier.size(13.dp),
                        tint = Color.White
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = formatViewerCount(stream.stream.viewerCount),
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (stream.user.profileImageUrl.isBlank()) {
                // Fallback avatar when the profile request failed (e.g. rate limit)
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stream.user.displayName.take(1).uppercase(),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            } else {
                AsyncImage(
                    model = stream.user.profileImageUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape),
                    contentScale = ContentScale.Crop
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stream.user.displayName,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stream.stream.title,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        if (stream.stream.gameName.isNotBlank()) {
            Surface(
                modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = stream.stream.gameName,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * Compact banner shown above the list when a refresh failed but we still have channels to show.
 * Without this, a failed refresh would silently look like "nothing changed".
 */
@Composable
private fun RefreshErrorBanner(message: String, onRetry: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                Icons.Default.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onRetry) {
                Text(stringResource(R.string.retry))
            }
        }
    }
}
