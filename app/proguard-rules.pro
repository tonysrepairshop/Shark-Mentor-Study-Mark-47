# Project-specific ProGuard rules.
# By default, the flags in this file are appended to flags specified
# in C:\Users\hp\AppData\Local\Android\Sdk\tools\proguard\proguard-android.txt
# You can edit the include path and order by changing the proguardFiles
# directive in build.gradle.

# Preserve all Compose annotations and models
-keepattributes *Annotation*
-keepclassmembers class * {
    @androidx.compose.runtime.Composable *;
    @androidx.compose.runtime.Stable *;
    @androidx.compose.runtime.Immutable *;
}

# Preserve viewmodels
-keepclassmembers public class * extends androidx.lifecycle.ViewModel {
    public <init>(...);
}

# Preserve Room entities and DAOs
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keep @androidx.room.Dao class *

# Preserve Generative AI models
-keep class com.google.ai.client.generativeai.** { *; }

# Preserve CoreComponentFactory and AppComponentFactory for runtime reflection by Android OS
-keep class androidx.core.app.CoreComponentFactory { *; }
-keep public class * extends android.app.AppComponentFactory

# Preserve Glance AppWidget receivers and ActionCallback implementations
-keep class * extends androidx.glance.appwidget.action.ActionCallback {
    public <init>(...);
}
-keep class * extends androidx.glance.appwidget.GlanceAppWidgetReceiver {
    public <init>(...);
}

# Preserve WebView @JavascriptInterface bridges
-keepattributes JavascriptInterface
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.example.sharkmentor.ui.screens.pacekeep.PaceKeepBridge { *; }

# Allow obfuscation of the rest
