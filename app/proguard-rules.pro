# MAKAY Uzaktan Baglanti - R8 / ProGuard

# ---- WebRTC (libwebrtc) ----
-keep class org.webrtc.** { *; }
-keep class org.freedesktop.** { *; }
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
-keepattributes InnerClasses,EnclosingMethod,Signature,*Annotation*
-dontwarn org.webrtc.**
-dontwarn org.chromium.**

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

# ---- MAKAY wire model siniflari ----
# Gson bu siniflarin alanlarina YANSIMA ile erisir; dogrudan kod referansi
# olmayan alanlari R8 kaldirabilir ya da siniflari birlestirebilir. Bu da JSON
# anahtarlarinin kaybolmasi / bozulmasi anlamina gelir ve protokol uyumunu
# kirar (masaustu `engine.js` birebir ayni anahtarlari bekler).
# Bu yuzden yapici + alanlar + sinif tamamen korunur.
-keep class com.makay.remote.rtc.WireEnvelope { *; }
-keep class com.makay.remote.rtc.ControlPayload { *; }
-keep class com.makay.remote.signal.SignalEnvelope { *; }
-keep class com.makay.remote.core.SdpCode$Payload { *; }
-keep class com.makay.remote.core.SdpCode$Decoded { *; }

# ---- Guncelleme denetimi ----
# (Onceki Gson tabanli `UpdateChecker` kaldirildi; yerine org.json ile
#  AppUpdateRepository geldi. org.json platform API'dir ve yansima gerektirmez,
#  bu yuzden burada -keep kurali gerekmiyor. Indirilen APK'nin dogrulanmasi
#  ApkIntegrityVerifier tarafindan imza + SHA-256 ile yapilir.)

# ---- Compose ----
-dontwarn androidx.compose.**