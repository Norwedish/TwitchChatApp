# Twitcher Chat

A lightweight Android app for viewing and participating in Twitch chat for your followed streamers.

This repository contains the Android Studio project used to build the app. The README below explains project structure, prerequisites, how to obtain and configure Twitch credentials safely, build and run instructions, and other developer notes.

---

## Table of Contents

- [Project overview](#project-overview)
- [Features](#features)
- [Prerequisites](#prerequisites)
- [Getting Twitch API credentials (Client ID & Secret)](#getting-twitch-api-credentials-client-id--secret)
- [Configuration (secure)](#configuration-secure)
- [Build & Run](#build--run)
- [Development notes](#development-notes)
- [Architecture](#architecture)
- [Testing](#testing)
- [Troubleshooting](#troubleshooting)
- [Security & Secrets](#security--secrets)
- [Contributing](#contributing)
- [License](#license)

---

## Project overview

Twitcher Chat is an Android application that connects to Twitch and allows you to read and participate in chat for streamers you follow. It demonstrates integrating with the Twitch API and chat (IRC or PubSub) and typical Android UI patterns.

Notes and assumptions
- I inferred the project's purpose (Twitch chat client) from the repository name and the small README. If any feature descriptions below are inaccurate, tell me which parts to update.

## Features

- Authenticate with Twitch in-app (OAuth implicit flow in a WebView with a fresh session for every login)
- Home screen with live followed streams (thumbnails, LIVE badge, viewer count, game)
- Full chat: messages with badges, Twitch/BTTV/7TV/FFZ emotes, @mentions, replies, polls,
  URL links, deleted-message handling, chatter list, user profiles, moderation (timeout/ban with confirmation)
- Search for channels and jump straight into their chat
- In-app video player above the chat (Media3 ExoPlayer, HLS) — plays the signed usher stream
  directly over HTTPS; a toggle in the chat top bar shows/hides it
- Chromecast support (local HLS proxy with an allowlisted, LAN-reachable proxy server)
- Real-time "went live" updates via EventSub WebSocket + intelligent polling + background WorkManager
  with notifications
- Chat settings (suppress duplicate room-state NOTICE messages, custom msg-ids)
- Custom dark-first Twitch-inspired design system (Chakra Petch + Lato fonts), light/dark theme
- OAuth token stored encrypted at rest (EncryptedSharedPreferences, hardware-backed key)
- Automatic IRC reconnect with exponential backoff
- UI mockups of the current design live in `docs/ui-mockups/`

## Prerequisites

- JDK 11 or newer (match project's Gradle toolchain). If you use Android Studio, it provides a compatible JDK.
- Android Studio Electric Eel or newer (or compatible with the Android Gradle Plugin used in this project)
- Android SDK (platforms & build-tools matching the project's configuration). Open the project in Android Studio and install any missing SDK components when prompted.
- Gradle is invoked through the included Gradle wrapper (`gradlew` / `gradlew.bat`). You do not need to install Gradle system-wide.

## Getting Twitch API credentials (Client ID & Secret)

The app requires Twitch API credentials to call certain endpoints and to perform OAuth authentication.

1. Go to the Twitch Developer Console: https://dev.twitch.tv/console
2. Create a new application.
   - Name: (your app name)
   - OAuth Redirect URI: add the redirect URI your app uses. For native/mobile apps, this is often a custom scheme (e.g. `twitcherchat://callback`) or `http://localhost` for testing — match the app's implementation. Which is in this case http://localhost:3000 .
   - Category: Application Integration or whatever fits.
3. After creating the application you'll get a Client ID and a Client Secret. Keep these values private.

The authorization page opens in an in-app WebView, not in an external browser. Its cookies and web
storage are cleared before every login, so you can always choose the Twitch account you want to use —
even when another account is already active in your browser. The `http://localhost:3000` redirect is
intercepted inside the WebView, so no local HTTP server or cleartext request is needed.

## Configuration (secure)

This repository previously contained an `app/credentials.txt` file. Do NOT commit secrets into source control.

Recommended secure options:

1) Use `local.properties` (local-only):

- Add the following to the project's top-level `local.properties` (NOT checked into git):

```properties
# local.properties (local only) - DO NOT commit
twitch.clientId=your_client_id_here
```

- `app/build.gradle.kts` reads `twitch.clientId` from `local.properties` and exposes it as
  `BuildConfig.TWITCH_CLIENT_ID` (with an empty-string fallback when the file is missing).

2) Use environment variables (CI-friendly):

- Set environment variables TWITCH_CLIENT_ID on your machine or CI and read them in Gradle or at runtime.

3) Use Android AccountManager / Encrypted SharedPreferences for runtime tokens.

Important: Remove `app/credentials.txt` from the repo and add it to `.gitignore` (this repository already had such a file committed). See the Security section below.

## Build & Run

From Android Studio
1. Open the project (`build.gradle.kts` project root).
2. Let Android Studio import Gradle and install missing SDK components.
3. Configure your Twitch credentials locally (see Configuration above).
4. Run the app on an emulator or a device.

From the command line (Windows PowerShell)

```powershell
# Clean and build the debug APK
.\gradlew.bat clean assembleDebug

# Install to a connected device/emulator
.\gradlew.bat installDebug
```

If you prefer the shorthand on PowerShell (while in repo root):

```powershell
# build and run on the default connected device
.\gradlew.bat installDebug
```

If you run into Gradle daemon or JDK issues, open the project in Android Studio and let it configure the environment.

## Development notes

- The `app` module contains Android sources. The project uses Kotlin (inferred) and Gradle Kotlin DSL build scripts.
- ProGuard / R8 rules live in `app/proguard-rules.pro` for release builds.

## Architecture

- UI: Jetpack Compose (Material 3) with Navigation Compose; single-activity (`MainActivity`)
- Theming: custom design system in `ui/theme/` (colors, Chakra Petch/Lato typography in `res/font/`, shapes)
- Networking: Ktor client (CIO) for the Twitch Helix API and GQL, Ktor client websockets for IRC
- Real-time chat: `ChatService` (foreground service) holding the Twitch IRC websocket,
  with reconnect/backoff; UI observes it through `ChatViewModel` (StateFlow/SharedFlow)
- Live-status: EventSub websocket (`EventSubManager`) + `IntelligentPollingManager` (WorkManager)
  + `LiveStreamWorker` notifications
- Images: Coil with memory/disk cache (configured in `MyApplication`)
- Persistence: EncryptedSharedPreferences (token/user), SharedPreferences (settings, live-stream state)
- Casting: Google Cast framework + `LocalProxyServer` (NanoHTTPD, allowlisted to *.ttvnw.net)
- Playback: `TwitchApi.getStreamUrl()` fetches a PlaybackAccessToken via GQL with the logged-in
  user's OAuth token (so Twitch serves subscriber/Turbo/Prime streams ad-free; on 401/403 it
  retries anonymously), then builds the signed usher HLS URL. The in-app player plays this URL
  directly (native players have no CORS limits); `LocalProxyServer` rewrites playlists/segments
  for the Chromecast receiver and adds CORS headers there.

### Ads

Twitch decides whether a stream gets ad breaks from the PlaybackAccessToken, not from the player.
The app therefore requests the token with the logged-in user's OAuth token: with an active
subscription (or Turbo/Prime) Twitch returns a token that marks the stream as ad-free
(`hide_ads`/`subscriber`/`turbo`), and the HLS playlist then contains no ad segments. The
ad-related flags are logged (never the token itself) so this is verifiable in logcat.

There is no client-side "ad blocker" on top of that: the ads are part of the same HLS stream, so
skipping them in the player would mean skipping live content. Without a subscription the stream
simply contains Twitch's ad breaks.

## Testing

- Unit tests: run with Gradle (if any are present):

```powershell
.\gradlew.bat test
```

- Instrumented Android tests (if present):

```powershell
.\gradlew.bat connectedAndroidTest
```

## Troubleshooting

- Gradle sync fails: Invalidate caches / restart Android Studio and check JDK configuration.
- Missing SDK platforms: Open SDK Manager and install the required API level.
- Twitch auth errors: Ensure your redirect URI and OAuth scopes match the configuration in the Twitch Developer Console.
- Rate-limiting: Twitch will throttle API requests; implement backoff and watch the response headers for limits.

## Security & Secrets

- Never commit Client ID/Secret or access tokens to source control.
- Add any local-only credential files to `.gitignore`.
- The Client ID is read from `local.properties` (not committed) and injected as a BuildConfig field.
- The OAuth access token and user profile are stored in `EncryptedSharedPreferences`
  (AES256-GCM, Android Keystore-backed key) — never in plaintext. Older plaintext sessions
  in DataStore are migrated automatically on first launch and wiped.
- The old `app/src/main/res/creds.txt` resource has been removed from the project.
- The local cast proxy (`LocalProxyServer`) only forwards requests to `*.ttvnw.net` / `*.twitch.tv`
  over HTTPS, so it cannot be abused as an open proxy from the LAN.
- Release builds run R8 (minify + resource shrinking); keep rules for Ktor/serialization/Gson
  live in `app/proguard-rules.pro`.

## Contributing

1. Open an issue describing the feature or bug.
2. Fork, create a topic branch, and open a pull request.
3. Keep secrets out of commits. Add tests for new behavior where applicable.

## License

No license specified. Add a LICENSE file if you want to set a license for this project.

---

If you want, I can also:
- Add a `.gitignore` entry for `app/credentials.txt` (I will add it now),
- Remove the committed `app/credentials.txt` from the repository history (this is a destructive operation and requires care; I can provide steps), or
- Update build scripts to read credentials from `local.properties` and expose them as BuildConfig fields.

Tell me which of the above you'd like me to do next.
