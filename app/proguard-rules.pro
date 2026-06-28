# Règles R8 / ProGuard de SyncFlix (minification + shrinkResources activés en release).
#
# Media3, OkHttp et les libs AndroidX embarquent leurs propres « consumer rules » : on n'ajoute
# ici que les garde-fous propres à l'app, là où R8 ne peut pas deviner un usage par réflexion.

# --- Enums (de)sérialisés par nom (payload WebSocket / état de session) ---------------------------
-keepclassmembers enum com.syncflix.app.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# --- WebRTC (voice chat) : JNI + réflexion → ne pas obfusquer/élaguer le pont natif --------------
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**
