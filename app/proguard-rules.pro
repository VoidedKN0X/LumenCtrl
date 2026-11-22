# LiteRT (TensorFlow Lite) calls back into Java from native code; keep its classes intact.
-keep class org.tensorflow.lite.** { *; }
-dontwarn org.tensorflow.lite.**

# No logging at all in release builds: LumenCtrl's own code doesn't log, and any Log call that sneaks in
# later is stripped. This also silences libraries such as LiteRT.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
    public static int println(...);
}
