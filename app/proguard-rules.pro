# MAKAY Uzaktan Baglanti - R8 / ProGuard

# ---- WebRTC (libwebrtc) ----
-keep class org.webrtc.** { *; }
-keep class org.freedesktop.** { *; }
-dontwarn org.webrtc.**

# ---- Paho MQTT (reflection tabanli baglanti) ----
-keep class org.eclipse.paho.** { *; }
-dontwarn org.eclipse.paho.**
-keep class javax.net.ssl.** { *; }
-dontwarn javax.net.ssl.**

# ---- Gson (JSON zarf/sinyal) ----
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }
-dontwarn com.google.gson.**

# ---- Compose ----
-dontwarn androidx.compose.**