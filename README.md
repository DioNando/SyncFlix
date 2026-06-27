
# 🎬 SyncFlix - Application de Visionnage Synchrone à Distance

**SyncFlix** est une application mobile Android native connectée à un serveur local Laravel, conçue pour permettre à un couple à distance de regarder des films en parfaite synchronisation, sans dépendre de services tiers ou de stockages cloud coûteux.

---

## 🚀 Fonctionnalités Principales

*   **Streaming Privé Direct :** Diffusion des vidéos stockées sur le PC (serveur) directement sur les smartphones Android.
*   **Synchronisation en Temps Réel :** Les actions de lecture (Play, Pause, Avance/Retour sur la timeline) sont répercutées instantanément sur les deux appareils ($\Delta t < 200\text{ms}$).
*   **Contrôle de la Latence :** Ajustement automatique de la timeline pour compenser les variations de connexion réseau.
*   **Session Unique :** Système d'appairage simple entre les deux téléphones pour contrôler le même flux vidéo.

---

## 🛠️ Stack Technique

### Backend & Serveur (PC)
*   **Framework :** Laravel 12+
*   **Serveur WebSockets :** Laravel Reverb (Temps réel natif)
*   **Gestion des flux :** Réponses binaires HTTP avec support des requêtes `Range` (Streaming)
*   **Tunneling (Dev) :** BeyondCode Expose ou ngrok (pour rendre le PC accessible depuis l'extérieur)

### Application Mobile (Android Native)
*   **Langage :** Kotlin
*   **Interface UI :** Jetpack Compose (Material Design 3)
*   **Lecteur Vidéo :** Media3 ExoPlayer (Lecteur natif Google)
*   **Client WebSockets :** OkHttp / Scarlet (Gestion des flux de données en temps réel)

---

## 📐 Architecture du Projet

+---------------------------------------+
           |          PC (Serveur Local)           |
           |                                       |
           |   [ Laravel API ]  [ Laravel Reverb ] |
           +--------+------------------+-----------+
                    ^                  |
         HTTP Stream|                  | WebSockets
         (Chunks)   |                  | (Play/Pause/Seek)
                    |                  v
 +------------------+--+            +--+------------------+
 |   Téléphone Android |            |   Téléphone Android |
 |      (Utilisateur)  |            |     (Copine)        |
 +---------------------+            +---------------------+

 ---

## 📂 Contrats d'API & WebSocket

### 1. HTTP API (Streaming vidéo)
*   **Endpoint :** `GET /api/movies/{id}/stream`
*   **Headers requis :** `Range: bytes=bytes_start-bytes_end` (Géré automatiquement par ExoPlayer pour permettre le saut dans la timeline).

### 2. Événements WebSocket (Laravel Reverb)
**Canal privé :** `private-movie-session.{sessionId}`

*   **Événement envoyé/reçu lors d'une action :**
    ```json
    {
        "event": "VideoStateUpdated",
        "data": {
            "is_playing": false,
            "position_ms": 451200,
            "triggered_by": "user_id"
        }
    }
    ```

---

## 📋 Base de Données (Concepts Clés)

La structure minimale pour faire tourner le MVP repose sur trois entités :

*   **Movies :** ID, titre, chemin local du fichier (`path`).
*   **Sessions :** ID, `movie_id`, `current_position_ms`, `is_playing`.
*   **Users :** ID, nom (Utilisateur / Copine), `session_id`.

---

## 🗺️ Feuille de Route du MVP (Minimum Viable Product)

### Étape 1 : Le Flux Vidéo (Laravel)
- [ ] Configurer un projet Laravel propre.
- [ ] Créer une route retournant une `BinaryFileResponse` pour un fichier vidéo test.
- [ ] Valider la lecture et le saut dans la timeline depuis un navigateur web.

### Étape 2 : Le Lecteur Mobile (Android)
- [ ] Créer le projet Android avec Jetpack Compose.
- [ ] Intégrer ExoPlayer via un `AndroidView`.
- [ ] Charger l'URL de streaming générée par Laravel (via le tunnel Expose) et valider la lecture sur le téléphone.

### Étape 3 : Le Temps Réel (Reverb)
- [ ] Installer et configurer Laravel Reverb sur le backend.
- [ ] Connecter l'application Android au WebSocket.
- [ ] Valider un système d'échange basique (ex: Envoyer un "Ping" depuis Android, recevoir un "Pong").

### Étape 4 : La Synchronisation
- [ ] Intercepter les états d'ExoPlayer (Play/Pause) pour envoyer les coordonnées sur le WebSocket.
- [ ] Écouter les événements du WebSocket pour mettre à jour l'état d'ExoPlayer sans créer de boucle infinie d'événements.