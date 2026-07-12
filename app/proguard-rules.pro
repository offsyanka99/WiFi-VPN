# WireGuard
-keep class com.wireguard.** { *; }
-keep class com.wireguard.android.backend.** { *; }

# EncryptedSharedPreferences / Tink
-keep class androidx.security.crypto.** { *; }
-keep class com.google.crypto.tink.** { *; }

# Optional Tink / annotation deps not on the classpath
-dontwarn com.google.api.client.http.**
-dontwarn com.google.api.client.http.javanet.**
-dontwarn javax.annotation.**
-dontwarn javax.annotation.concurrent.**
-dontwarn javax.annotation.meta.**
-dontwarn org.joda.time.Instant

# Keep crash / diagnostic stack frames readable enough for support
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
