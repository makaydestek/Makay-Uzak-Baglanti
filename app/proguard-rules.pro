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

# ---- Guncelleme denetimi modelleri ----
# `UpdateChecker$Release` alanlari YALNIZCA Gson tarafindan yansima ile yazilir;
# kod icinde hicbir atama olmadigi icin R8 "bu alan hep null" sonucuna varip
# fetch() icindeki `tagName`-sonrasi tum koku (surum karsilastirmasi ve
# `UpdateInfo(...)` kurulumu) olumsuz dead-code olarak siliyordu. Sonuc:
# guncelleme dialogu release APK'da hic gorunmuyordu ve guncelleme denetimi
# her zaman null donuyordu. Ayrica Gson, constructor'u reflection ile cagirir.
# `UpdateInfo` ise UI akisinin (RemoteApp -> SessionViewModel.updateInfo)
# degerini olusturur; R8 tarafindan silinirse `update?.let { ... }` blogu ve
# yalnizca onun cagirdigi `dismissUpdate()` de birlikte silinir.
-keep class com.makay.remote.core.UpdateChecker$Release { *; }
-keep class com.makay.remote.core.UpdateChecker$UpdateInfo { *; }

# ---- Compose ----
-dontwarn androidx.compose.**