# Keep JNI bridge classes; native code looks them up by name.
-keepclasseswithmembernames class com.pixellab.core.nativelib.** { native <methods>; }
