package com.norwedish.twitchchatapp

/**
 * A small composable that shows the Cast button and handles opening the Cast dialog when available.
 */

import android.content.Context
import android.view.View
import android.widget.ImageButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.cast.framework.CastButtonFactory

@Composable
fun CastButton(modifier: Modifier = Modifier, onFallback: () -> Unit = {}) {
    AndroidView<View>(factory = { context: Context ->
        try {
            val mrButton = androidx.mediarouter.app.MediaRouteButton(context)
            try {
                CastButtonFactory.setUpMediaRouteButton(context.applicationContext, mrButton)
            } catch (_: Exception) {
                // ignore setup; button will still open system chooser in many cases
            }
            mrButton
        } catch (_: Throwable) {
            // Fallback: create a simple ImageButton that triggers onFallback
            ImageButton(context).apply {
                setOnClickListener { onFallback() }
                setImageResource(android.R.drawable.ic_menu_share)
                contentDescription = "Cast"
            }
        }
    }, modifier = modifier)
}
