# Keep Room entities and DAOs
-keep class com.example.appblocker.data.model.** { *; }
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# Keep system-bound components referenced only from the manifest
-keep class com.example.appblocker.service.BlockerAccessibilityService { *; }
-keep class com.example.appblocker.service.BlockDnsVpnService { *; }
-keep class com.example.appblocker.admin.BlockerDeviceAdminReceiver { *; }

# Kotlin coroutines
-dontwarn kotlinx.coroutines.**
