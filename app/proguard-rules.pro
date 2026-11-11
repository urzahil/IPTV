# Loaded by reflection or JNI.
-keep class org.videolan.** { *; }
-keep class androidx.media3.decoder.ffmpeg.** { *; }
-keep class androidx.media3.datasource.rtmp.** { *; }

# Strip verbose logging in release.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
