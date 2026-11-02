package com.norwedish.twitcherchat

/**
 * In-app Twitch login.
 *
 * The OAuth page is shown in a WebView whose cookies and web storage are wiped before every
 * login. That has three advantages over a Custom Tab:
 *  - no dependency on an up-to-date external browser (the emulator's old Chrome refused Twitch),
 *  - the browser's currently signed-in Twitch account never leaks into the app, so the user can
 *    always choose the account they want,
 *  - the flow is identical on every device.
 *
 * Twitch redirects to [redirectUri] with the token in the URL fragment. That navigation is
 * intercepted in [WebViewClient.shouldOverrideUrlLoading] and the token is read directly, so the
 * app never opens a local HTTP server or makes a cleartext request.
 */

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun TwitchLoginWebView(
    authUrl: String,
    redirectUri: String,
    onTokenReceived: (String) -> Unit,
    onLoginError: (String) -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    var isLoading by remember { mutableStateOf(true) }
    val redirectHandled = remember { AtomicBoolean(false) }
    val currentOnTokenReceived by rememberUpdatedState(onTokenReceived)
    val currentOnLoginError by rememberUpdatedState(onLoginError)

    /**
     * Returns true when [url] is the OAuth redirect. The token lives in the fragment, so the
     * redirect is parsed and cancelled before the WebView tries to load localhost.
     */
    fun consumeRedirect(url: String?): Boolean {
        if (url == null || !url.startsWith(redirectUri, ignoreCase = true)) return false
        if (!redirectHandled.compareAndSet(false, true)) return true

        val uri = Uri.parse(url)
        val rawParams = (uri.fragment ?: uri.query).orEmpty()
        val params = rawParams
            .split("&")
            .mapNotNull { part ->
                val separator = part.indexOf('=')
                if (separator <= 0) null
                else URLDecoder.decode(part.substring(0, separator), "UTF-8") to
                    URLDecoder.decode(part.substring(separator + 1), "UTF-8")
            }
            .toMap()

        val token = params["access_token"]
        if (!token.isNullOrBlank()) {
            currentOnTokenReceived(token)
        } else {
            currentOnLoginError(
                params["error_description"]
                    ?: params["error"]
                    ?: context.getString(R.string.login_cancelled)
            )
        }
        return true
    }

    BackHandler(onBack = onClose)

    val webView = remember(authUrl) {
        WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(0xFF0E0E10.toInt())
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.setSupportZoom(false)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean = consumeRedirect(request?.url?.toString())

                @Deprecated("Deprecated in Java")
                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean =
                    consumeRedirect(url)

                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    // Backup in case a redirect bypasses shouldOverrideUrlLoading on an older
                    // WebView: consume the token and stop the navigation.
                    if (consumeRedirect(url)) {
                        view?.stopLoading()
                        return
                    }
                    isLoading = true
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    isLoading = false
                }
            }
        }
    }

    // Fresh session for every login: this is what lets the user pick a different account.
    LaunchedEffect(webView) {
        CookieManager.getInstance().removeAllCookies {
            CookieManager.getInstance().flush()
            WebStorage.getInstance().deleteAllData()
            webView.loadUrl(authUrl)
        }
    }

    DisposableEffect(webView) {
        onDispose {
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.destroy()
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            TopAppBar(
                title = { Text(stringResource(R.string.login_in_app_title)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.close)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )

            Text(
                text = stringResource(R.string.login_in_app_explanation),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp)
            )
            Text(
                text = stringResource(R.string.login_two_factor_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 10.dp)
            )

            Box(modifier = Modifier.fillMaxSize()) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { webView }
                )

                if (isLoading) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}
