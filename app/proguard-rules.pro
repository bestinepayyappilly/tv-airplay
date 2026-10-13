# Keep JNI callback interface (called from native code)
-keep class io.github.bestinepayyappilly.tvairplay.bridge.RaopCallbackHandler { *; }
-keep class * implements io.github.bestinepayyappilly.tvairplay.bridge.RaopCallbackHandler { *; }
-keep class io.github.bestinepayyappilly.tvairplay.bridge.LogListener { *; }
-keep class * implements io.github.bestinepayyappilly.tvairplay.bridge.LogListener { *; }

# Keep NativeBridge native methods
-keep class io.github.bestinepayyappilly.tvairplay.bridge.NativeBridge { *; }
