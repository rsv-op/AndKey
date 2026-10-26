# Add project specific ProGuard rules here.
# Keep POI, PDFBox, ONNX Runtime from being stripped

-keep class org.apache.poi.** { *; }
-keep class org.apache.xmlbeans.** { *; }
-keep class com.tom_roush.pdfbox.** { *; }
-keep class ai.onnxruntime.** { *; }
-keep class org.tensorflow.lite.** { *; }

# Keep our own model classes
-keep class com.buildgenie.** { *; }

# WorkManager
-keep class androidx.work.** { *; }

# Remove logging in release
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}
