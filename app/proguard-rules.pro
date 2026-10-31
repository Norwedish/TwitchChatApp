# Keep line numbers for readable release crash reports
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# --- kotlinx.serialization (Twitch API models) ---
-keepattributes *Annotation*, InnerClasses, Signature, AnnotationDefault
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.norwedish.twitcherchat.**$$serializer { *; }
-keepclassmembers class com.norwedish.twitcherchat.** {
    *** Companion;
}
-keepclasseswithmembers class com.norwedish.twitcherchat.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# --- Gson (user profile is serialized with Gson) ---
-keep class com.norwedish.twitcherchat.TwitchUser { *; }
-keepclassmembers class com.norwedish.twitcherchat.TwitchUser { *; }
-keep class com.google.gson.** { *; }
-dontwarn com.google.gson.**

# --- Ktor client ---
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**
-dontwarn org.slf4j.**
-dontwarn reactor.blockhound.**

# --- NanoHTTPD local proxy ---
-keep class fi.iki.elonen.** { *; }
-dontwarn fi.iki.elonen.**

# --- Coil / OkHttp ---
-dontwarn okhttp3.**
-dontwarn okio.**

# --- Google Cast framework ---
-keep class com.google.android.gms.cast.** { *; }
-dontwarn com.google.android.gms.**
