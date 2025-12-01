import java.util.Properties
import java.io.FileInputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val localProperties = Properties() // You can now use the shortened 'Properties'
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localProperties.load(FileInputStream(localPropertiesFile)) // You can now use the shortened 'FileInputStream'
}

android {
    namespace = "com.norwedish.twitcherchat"
    compileSdk = 36

    packaging {
        resources {
            excludes += "META-INF/INDEX.LIST"
            resources.excludes.add("META-INF/io.netty.versions.properties")
        }
    }

    defaultConfig {
        applicationId = "com.norwedish.twitcherchat"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Haal de waarde op uit local.properties
        val twitchClientId = localProperties.getProperty("twitch.clientId")
        buildConfigField("String", "TWITCH_CLIENT_ID", "$twitchClientId")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {

    // Voor netwerkverzoeken (API calls)
    implementation("io.ktor:ktor-client-android:2.3.12")
    implementation("io.ktor:ktor-client-content-negotiation:2.3.12")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.12")
    implementation("io.ktor:ktor-server-core:2.3.12")
    implementation("io.ktor:ktor-server-netty:2.3.12")
    implementation("io.ktor:ktor-client-websockets:2.3.12")
    implementation("io.ktor:ktor-client-cio:2.3.12")

    // Embedded lightweight HTTP server for in-app local proxy (device-local)
    implementation("org.nanohttpd:nanohttpd:2.3.1")

    // Voor het openen van de Twitch login pagina in een browser tab
    implementation("androidx.browser:browser:1.8.0")

    // WorkManager
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    implementation("com.google.code.gson:gson:2.10.1")

    implementation("io.coil-kt:coil-compose:2.6.0")
    implementation("io.coil-kt:coil-gif:2.6.0")

    // Accompanist for Swipe-to-Refresh
    implementation("com.google.accompanist:accompanist-swiperefresh:0.34.0")


    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    // Lifecycle ViewModel integration for Jetpack Compose
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.6.1")
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation("androidx.compose.material:material-icons-extended") // Add this line
    implementation("androidx.compose.material:material")
    // Navigation for Jetpack Compose
    implementation("androidx.navigation:navigation-compose:2.7.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    // MediaRouter for Chromecast UI (MediaRouteButton)
    implementation("androidx.mediarouter:mediarouter:1.2.3")
    // Google Cast framework
    implementation("com.google.android.gms:play-services-cast-framework:21.2.0")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}