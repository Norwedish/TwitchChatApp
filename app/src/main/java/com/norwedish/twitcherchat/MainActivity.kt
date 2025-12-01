package com.norwedish.twitcherchat

/**
 * App entry point Activity which hosts the Compose navigation graph.
 * Responsible for wiring top-level UI, handling deep links, and managing Cast initialization.
 */

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import androidx.work.*
import coil.compose.AsyncImage
import com.norwedish.twitcherchat.ui.theme.TwitcherChatTheme
import kotlinx.coroutines.launch
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import java.util.Locale

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
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

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
                                lifecycleScope.launch {
                                    val user = TwitchApi.getCurrentUser(token, UserManager.CLIENT_ID)
                                    if (user != null) {
                                        UserManager.login(token, user)
                                        scheduleLiveStreamWorker()
                                        navController.navigate(AppRoutes.MAIN) {
                                            popUpTo(AppRoutes.LOGIN) { inclusive = true }
                                        }
                                    }
                                }
                            })
                        }

                        composable(AppRoutes.MAIN) {
                            LaunchedEffect(Unit) {
                                mainViewModel.loadFollowedStreams()
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
        WorkManager.getInstance(this).cancelUniqueWork(LiveStreamWorker.WORK_NAME)
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
 * The main screen composable that aggregates the home UI: stream list, app bar and navigation actions.
 * It obtains state from the provided MainViewModel and exposes callbacks for navigation and actions.
 */
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onStreamClick: (EnrichedStream) -> Unit,
    onSearchClick: () -> Unit,
    onLogoutClick: () -> Unit,
    onSettingsClick: () -> Unit
) {
    val context = LocalContext.current
    val streams by viewModel.followedStreams
    val isLoading by viewModel.isLoading
    val currentUser = UserManager.currentUser
    var menuExpanded by remember { mutableStateOf(false) }

    // Whisper system integration
    val whisperViewModel: WhisperViewModel = viewModel()
    var showWhisperScreen by remember { mutableStateOf(false) }

    // Initialize whisper view model and service
    DisposableEffect(Unit) {
        val prefManager = WhisperPreferenceManager(context)
        val user = UserManager.currentUser
        if (user != null) {
            whisperViewModel.initialize(prefManager, user.id, user.login)
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            painter = painterResource(id = R.drawable.logo),
                            contentDescription = "App Logo",
                            modifier = Modifier.size(40.dp),
                            contentScale = ContentScale.Fit
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(text = "Twitcher Chat", style = MaterialTheme.typography.titleLarge)
                            if (currentUser != null) {
                                Row(
                                    modifier = Modifier.clickable { menuExpanded = true },
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    AsyncImage(
                                        model = currentUser.profileImageUrl,
                                        contentDescription = "User Avatar",
                                        modifier = Modifier.size(24.dp).clip(CircleShape),
                                        contentScale = ContentScale.Crop
                                    )
                                    Text(
                                        text = currentUser.displayName,
                                        style = MaterialTheme.typography.titleSmall
                                    )
                                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                                        DropdownMenuItem(
                                            text = { Text("Log Out") },
                                            onClick = {
                                                menuExpanded = false
                                                onLogoutClick()
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                },
                actions = {
                    val totalUnreadWhispers by whisperViewModel.totalUnreadCount.collectAsState()
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
                    IconButton(onClick = onSettingsClick) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onSearchClick) {
                Icon(Icons.Default.Search, contentDescription = "Search for a streamer")
            }
        }
    ) { paddingValues ->
        PullToRefreshBox(
            isRefreshing = isLoading,
            onRefresh = { viewModel.loadFollowedStreams() },
            modifier = Modifier.padding(paddingValues)
        ) {
            if (streams.isEmpty() && !isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                        .verticalScroll(rememberScrollState()),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No followed channels are currently live. Pull down to refresh.",
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
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

    // Whisper Screen (full screen view)
    if (showWhisperScreen) {
        WhisperScreen(
            viewModel = whisperViewModel,
            onNavigateBack = { showWhisperScreen = false }
        )
    }
}

private fun formatViewerCount(count: Int): String {
    return when {
        count >= 1_000_000 -> String.format(Locale.ROOT, "%.1fM", count / 1_000_000f)
        count >= 1_000 -> String.format(Locale.ROOT, "%.1fK", count / 1_000f)
        else -> count.toString()
    }
}

@Composable
fun StreamCard(stream: EnrichedStream, onClick: () -> Unit) {
    Card(
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                AsyncImage(
                    model = stream.user.profileImageUrl,
                    contentDescription = "Streamer avatar",
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape),
                    contentScale = ContentScale.Crop
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = "Viewers",
                        modifier = Modifier.size(16.dp),
                        tint = Color.Red
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = formatViewerCount(stream.stream.viewerCount),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(
                    text = stream.user.displayName,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stream.stream.title,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stream.stream.gameName,
                    fontSize = 14.sp
                )
            }
        }
    }
}
