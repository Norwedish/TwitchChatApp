# TwitchChatApp

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

TwitchChatApp is an Android application that connects to Twitch and allows you to read and participate in chat for streamers you follow. It demonstrates integrating with the Twitch API and chat (IRC or PubSub) and typical Android UI patterns.

Notes and assumptions
- I inferred the project's purpose (Twitch chat client) from the repository name and the small README. If any feature descriptions below are inaccurate, tell me which parts to update.

## Features

- Authenticate with Twitch (OAuth flow) to access follow list and chat privileges
- Show followed streamers and open their live chat
- Send messages to chat (requires OAuth scopes)
- Basic message rendering (usernames, emotes, badges)
- Background reconnection and basic rate-limit handling

(If the app has additional features such as message filtering, moderation tools, or notifications, mention them here or open an issue to request README updates.)

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
   - OAuth Redirect URI: add the redirect URI your app uses. For native/mobile apps, this is often a custom scheme (e.g. `twitchchatapp://callback`) or `http://localhost` for testing — match the app's implementation.
   - Category: Application Integration or whatever fits.
3. After creating the application you'll get a Client ID and a Client Secret. Keep these values private.

## Configuration (secure)

This repository previously contained an `app/credentials.txt` file. Do NOT commit secrets into source control.

Recommended secure options:

1) Use `local.properties` (local-only):

- Add the following to the project's top-level `local.properties` (NOT checked into git):

```properties
# local.properties (local only) - DO NOT commit
TWITCH_CLIENT_ID=your_client_id_here
TWITCH_CLIENT_SECRET=your_client_secret_here
```

- In your app's `build.gradle` or code, read them from `local.properties`. Example Gradle snippet (Kotlin DSL):

```kotlin
// in app/build.gradle.kts (example)
val localProps = java.util.Properties().apply {
    rootProject.file("local.properties").inputStream().use { load(it) }
}
val twitchClientId: String? = localProps.getProperty("TWITCH_CLIENT_ID")
val twitchClientSecret: String? = localProps.getProperty("TWITCH_CLIENT_SECRET")

android {
    defaultConfig {
        // ...
        buildConfigField("String", "TWITCH_CLIENT_ID", "\"${twitchClientId}\"")
        buildConfigField("String", "TWITCH_CLIENT_SECRET", "\"${twitchClientSecret}\"")
    }
}
```

2) Use environment variables (CI-friendly):

- Set environment variables TWITCH_CLIENT_ID and TWITCH_CLIENT_SECRET on your machine or CI and read them in Gradle or at runtime.

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
- There is a `credentials.txt` in `app/` containing example or developer credentials. Remove this before sharing the repo publicly.

## Architecture

A brief, inferred architecture (adapt to actual project):

- UI: Android Activities / Fragments + Jetpack components for lifecycle and navigation
- Networking: Retrofit / OkHttp or Twitch SDK for REST API calls
- Real-time chat: IRC client or WebSocket / PubSub connection
- Persistence: optional local cache using Room or simple in-memory cache

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
- Consider rotating your Client Secret if it was accidentally committed (the repo currently contains `app/credentials.txt` with values — rotate the secret if those are valid credentials).

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
