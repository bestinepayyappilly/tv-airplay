# Keep JNI callback interface (called from native code)
-keep class com.castbay.bridge.RaopCallbackHandler { *; }
-keep class * implements com.castbay.bridge.RaopCallbackHandler { *; }
-keep class com.castbay.bridge.LogListener { *; }
-keep class * implements com.castbay.bridge.LogListener { *; }

# Keep NativeBridge native methods
-keep class com.castbay.bridge.NativeBridge { *; }
