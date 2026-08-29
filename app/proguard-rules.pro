# TensorFlow Lite — keep all TFLite classes from being stripped by R8/ProGuard
-keep class org.tensorflow.** { *; }
-keep class org.tensorflow.lite.** { *; }
-keep class org.tensorflow.lite.gpu.** { *; }
-keep class org.tensorflow.lite.nnapi.** { *; }

# Keep the TFLite GPU delegate plugin
-keep class com.google.android.gms.tflite.** { *; }

# Standard Android rules
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
