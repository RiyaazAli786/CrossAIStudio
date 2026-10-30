# Proguard / R8 Optimization & Obfuscation Rules for CrossAIStudio

# Keep data models so JSON parsing and SQLite queries retain field names
-keep class com.pintoinsta.app.data.model.** { *; }
-keepclassmembers class com.pintoinsta.app.data.model.** { *; }

# Keep WorkManager Workers for background jobs
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keep class com.pintoinsta.app.worker.** { *; }

# Keep Android Components (Services, Receivers, Activities)
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver

# Keep ViewBinding generated classes
-keep class com.pintoinsta.app.databinding.** { *; }

# Keep Coil image loader
-keep class coil.** { *; }

# Keep OkHttp & Okio internals
-dontwarn okhttp3.**
-dontwarn okio.**
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod

# Keep AndroidX Biometric
-keep class androidx.biometric.** { *; }

# Strip logging calls in release APK for maximum privacy and security
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
}
