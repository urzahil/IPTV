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

# Media3 loads these by name (DefaultRenderersFactory, DefaultMediaSourceFactory, DefaultDataSource).
-keep class androidx.media3.exoplayer.rtsp.RtspMediaSource { *; }
-keep class androidx.media3.exoplayer.rtsp.RtspMediaSource$Factory { *; }
-keep class androidx.media3.exoplayer.hls.HlsMediaSource$Factory { *; }
-keep class androidx.media3.exoplayer.dash.DashMediaSource$Factory { *; }
-dontwarn org.videolan.**
