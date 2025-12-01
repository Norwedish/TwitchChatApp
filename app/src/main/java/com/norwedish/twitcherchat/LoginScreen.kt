package com.norwedish.twitcherchat

/**
 * Login UI composable that triggers the OAuth flow and returns the token on success via callback.
 */

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun LoginScreen(
    authViewModel: AuthViewModel = viewModel(),
    onLoginSuccess: (String) -> Unit
) {
    val context = LocalContext.current

    DisposableEffect(authViewModel) {
        onDispose {
            authViewModel.stopLoginFlow()
        }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Welkom!", style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = {
            // Start de hele flow vanuit de ViewModel
            authViewModel.startLoginFlow(context) { token ->
                // Deze code wordt uitgevoerd zodra de server het token heeft ontvangen
                onLoginSuccess(token)
            }
        }) {
            Text("Login met Twitch")
        }
    }
}
