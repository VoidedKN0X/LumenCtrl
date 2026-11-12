# LiteRT (TensorFlow Lite) calls back into Java from native code; keep its classes intact.
-keep class org.tensorflow.lite.** { *; }
-dontwarn org.tensorflow.lite.**
