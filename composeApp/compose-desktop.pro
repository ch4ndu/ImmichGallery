# Desktop ProGuard does not automatically consume dependencies' Android rules.
# These implementations are discovered through META-INF/services, not direct calls.
-keep interface io.ktor.client.HttpClientEngineContainer { *; }
-keep interface io.ktor.serialization.kotlinx.KotlinxSerializationExtensionProvider { *; }
-keep interface coil3.util.FetcherServiceLoaderTarget { *; }
-keep interface org.openani.mediamp.MediampPlayerFactory { *; }
-keep interface org.openani.mediamp.compose.MediampPlayerSurfaceProvider { *; }
-keep class * implements io.ktor.client.HttpClientEngineContainer { public <init>(); }
-keep class * implements io.ktor.serialization.kotlinx.KotlinxSerializationExtensionProvider { public <init>(); }
-keep class * implements coil3.util.FetcherServiceLoaderTarget { public <init>(); }
-keep class * implements org.openani.mediamp.MediampPlayerFactory { public <init>(); }
-keep class * implements org.openani.mediamp.compose.MediampPlayerSurfaceProvider { public <init>(); }

# Ktor's upstream rules: AtomicFU updates these fields reflectively.
-keepclassmembers class io.ktor.** { volatile <fields>; }

# Like Compose's **Kt__* workaround, prevent return-type specialization of
# Kotlin multifile implementation classes. Okio uses a custom facade name;
# specializing buffer() otherwise produces an invalid BufferedSource return.
-keep,allowshrinking,allowobfuscation class okio.**__* { *; }

# Room constructors and SQLite entry points are reached through reflection/JNI.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keepclasseswithmembers class androidx.sqlite.driver.bundled.** { native <methods>; }

# MediaMP's JNI bridge resolves these callback types and methods by name.
-keepclasseswithmembers class org.openani.mediamp.mpv.** { native <methods>; }
-keep interface org.openani.mediamp.mpv.EventListener { *; }
-keep interface org.openani.mediamp.mpv.RenderUpdateListener { *; }
-keep class org.openani.mediamp.mpv.MPVLogKt { public static void onNativeLog(...); }
-keep interface org.openani.mediamp.io.SeekableInput { *; }
