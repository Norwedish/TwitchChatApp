package com.norwedish.twitchchatapp

/**
 * Custom Application class where global init (UserManager, CastManager) occurs.
 *
 * Responsibilities:
 * - Initialize UserManager and other global singletons
 * - Create notification channels used by background services
 * - Provide a Coil ImageLoader with memory and disk caching for the app
 */

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.ImageDecoderDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache

class MyApplication : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        // Initialiseer de UserManager zodra de app start
        UserManager.init(this)
        createNotificationChannel()
        // Initialize CastManager so it's ready to handle casting requests
        CastManager.initialize(this)
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }

            .diskCache {
                DiskCache.Builder()
                    .directory(this.cacheDir.resolve("image_cache"))
                    .maxSizePercent(0.02)
                    .build()
            }
            .components {
                if (Build.VERSION.SDK_INT >= 28) {
                    add(ImageDecoderDecoder.Factory())
                } else {
                    // Fallback for older API levels if needed
                }
            }
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Twitch Chat Service"
            val descriptionText = "Notification channel for the chat service"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(ChatService.CHANNEL_ID, name, importance).apply {
                description = descriptionText
                // Ensure chat channel is silent by default
                setSound(null, null)
                enableVibration(false)
            }
            val notificationManager:
                    NotificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
}
