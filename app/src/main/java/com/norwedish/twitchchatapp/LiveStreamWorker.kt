package com.norwedish.twitchchatapp

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class LiveStreamWorker(private val appContext: Context, workerParams: WorkerParameters) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val WORK_NAME = "LiveStreamWorker"
        const val ACTION_STREAM_UP = "com.norwedish.twitchchatapp.STREAM_UP"
        const val CHANNEL_ID = "StreamLiveNotificationChannel"
        private const val TAG = "LiveStreamWorker"
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "Worker starting its periodic check.")

        val token = UserManager.accessToken
        val user = UserManager.currentUser

        if (token == null || user == null) {
            Log.d(TAG, "No user logged in, stopping worker.")
            return Result.success() // Succeed so it doesn't retry unnecessarily
        }

        return try {
            // Directly fetch live followed streams (simpler and fewer API calls)
            val liveStreams = TwitchApi.getFollowedStreams(user.id, token, UserManager.CLIENT_ID)
            val liveStreamIds = liveStreams.map { it.userId }.toSet()

            val knownLiveStreams = LiveStreamStateManager.getKnownLiveStreams(appContext)

            // On the very first run, just save the current state without notifying
            if (knownLiveStreams.isEmpty() && liveStreams.isNotEmpty()) {
                Log.d(TAG, "First poll run with live streams. Saving initial state.")
                LiveStreamStateManager.updateKnownLiveStreams(appContext, liveStreamIds)
                return Result.success()
            }

            val newLiveStreams = liveStreams.filter { it.userId !in knownLiveStreams }

            if (newLiveStreams.isNotEmpty()) {
                Log.d(TAG, "CHANGES DETECTED: Found ${newLiveStreams.size} new live stream(s).")
                for (stream in newLiveStreams) {
                    showStreamLiveNotification(stream)
                    sendBroadcast()
                }
            } else {
                Log.d(TAG, "NO CHANGES: No new live streams found.")
            }

            // Always save the latest set of live streams for the next comparison
            LiveStreamStateManager.updateKnownLiveStreams(appContext, liveStreamIds)

            Log.d(TAG, "Worker finished successfully.")
            Result.success()
        } catch (e: Exception) {
            if (e.message?.contains("401") == true) {
                // If token is invalid, log out the user
                CoroutineScope(Dispatchers.IO).launch {
                    UserManager.logout()
                }
                return Result.success()
            }
            Log.e(TAG, "Error during polling", e)
            Result.retry()
        }
    }

    private fun sendBroadcast() {
        val intent = Intent(ACTION_STREAM_UP).apply {
            setPackage(appContext.packageName)
        }
        appContext.sendBroadcast(intent)
    }

    private fun showStreamLiveNotification(stream: FollowedStream) {
        createNotificationChannel()
        val intent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("channelName", stream.userLogin)
            putExtra("twitchUserId", stream.userId)
            putExtra("displayName", stream.userName)
            putExtra("profileImageUrl", stream.thumbnailUrl)
        }

        val pendingIntent = PendingIntent.getActivity(appContext, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setContentTitle("${stream.userName} is now live!")
            .setContentText(stream.title)
            .setSmallIcon(R.drawable.transparentlogo)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        val notificationManager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(stream.userId.hashCode(), notification)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Stream Live Notifications",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Notifications for when a followed streamer goes live."
        }
        val notificationManager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }
}
