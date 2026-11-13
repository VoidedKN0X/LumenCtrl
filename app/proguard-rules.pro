# LiteRT (TensorFlow Lite) calls back into Java from native code; keep its classes intact.
-keep class org.tensorflow.lite.** { *; }
-dontwarn org.tensorflow.lite.**

# No debug, verbose or info logging in release builds (this also silences libraries such as LiteRT).
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
