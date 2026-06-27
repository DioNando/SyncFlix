# 🎬 SyncFlix — Visionnage synchrone à distance

**SyncFlix** permet à deux personnes éloignées de **regarder un film ensemble, en parfaite synchronisation**, à partir des vidéos stockées sur un PC — sans cloud, sans service tiers, sans abonnement. Le PC diffuse le film ; les deux téléphones Android le lisent ; chaque action (Play, Pause, avance/recul) est répercutée instantanément sur l'autre appareil.

> 📐 La conception technique détaillée vit dans **[ARCHITECTURE.md](ARCHITECTURE.md)**. Ce README est la vue d'ensemble.

---

## 🎯 Principe

Le cœur du projet, c'est la **synchronisation**. SyncFlix repose sur un modèle d'**état partagé autoritatif** :

- Le **serveur détient la vérité** : pour chaque session, il connaît l'état de lecture (`is_playing`, position, numéro de séquence). Un téléphone qui se (re)connecte interroge cet état et se cale dessus — la synchro est **auto-réparante**.
- Quand un téléphone agit (pause, seek…), il **pousse son intention** au serveur, qui la persiste et la **diffuse en temps réel** (WebSocket) à l'autre téléphone.
- Chaque message porte un **horodatage serveur** : à la réception, le téléphone calcule la **position réelle attendue** (en tenant compte du temps de transit et de l'offset d'horloge), puis se recale.

Trois mécanismes garantissent une lecture « collée » :

| Situation | Comportement |
|---|---|
| Petit écart (< ~120 ms) | rien — considéré synchronisé |
| Écart modéré | **correction douce de la vitesse** (±8 % max) : rattrape/ralentit sans à‑coup |
| Gros écart / seek volontaire | **saut franc** à la position cible |
| Pause | alignement **à l'image près** |

Un système **anti-boucle** (`triggered_by` + fenêtre de suppression) évite qu'appliquer un état distant ne redéclenche une émission en cascade.

---

## ⚙️ Fonctionnement

```
        ┌─────────────────────── PC (serveur local) ───────────────────────┐
        │                                                                   │
        │   Laravel (API + streaming :8000)      Reverb (WebSocket :8080)    │
        │              ▲                                   ▲                 │
        │              └───────────── Caddy :8088 ─────────┘                 │
        │                         (reverse proxy)                           │
        └──────────────────────────────▲────────────────────────────────────┘
                                        │  un seul tunnel (ngrok :8088)
                          ┌─────────────┴──────────────┐
                          │                            │
                   ┌──────┴───────┐            ┌───────┴──────┐
                   │  Téléphone A │            │  Téléphone B │
                   │  (ExoPlayer) │            │  (ExoPlayer) │
                   └──────────────┘            └──────────────┘
              HTTP : streaming vidéo (Range/206)   ·   WS : Play/Pause/Seek
```

**Parcours utilisateur :**
1. Une personne **crée une session** → le serveur génère un **code court** (ex. `ABC123`).
2. Elle partage ce code ; l'autre **rejoint** la session avec le même code.
3. Les deux téléphones streament le même film depuis le PC et restent **synchronisés** : toute action de l'un est appliquée chez l'autre.

**Reverse proxy Caddy** : Laravel (HTTP) et Reverb (WebSocket) tournent sur deux ports. Caddy les **fusionne sur un seul port** (`/app/*` → Reverb, le reste → Laravel), ce qui permet de n'exposer **qu'un seul tunnel** et de ne saisir **qu'une seule URL** côté app.

---

## 🛠️ Stack technique

**Application mobile (Android natif)**
- Kotlin · Jetpack Compose (Material 3 **Expressive**)
- Media3 **ExoPlayer** (lecture + requêtes `Range`)
- OkHttp (REST + WebSocket protocole Pusher), `org.json`

**Backend (PC)**
- **Laravel 13** · base **SQLite**
- **Laravel Reverb** (serveur WebSocket, protocole Pusher)
- **Caddy** (reverse proxy HTTP + WS sur un port unique)
- **ngrok** (tunnel public pour le dev à distance)

---

## 📋 Prérequis

- **PHP 8.4+**, **Composer**
- **Caddy** (`choco install caddy`)
- **ngrok** (compte gratuit suffisant — jusqu'à 3 endpoints)
- **Android Studio** / SDK Android (compileSdk 37), un appareil ou émulateur

---

## 🚀 Lancement du backend

Le backend vit dans le dossier [`server/`](server/).

### 1. Installation (première fois)
```bash
cd server
composer install
cp .env.example .env          # si .env absent
php artisan key:generate
php artisan migrate --seed    # crée la base + enregistre le film de test
```
> 🎞️ Le seed référence un fichier vidéo de test dans `storage/app/private/movies/sintel.mp4`.
> Dépose-y un `.mp4` (ou récupère le trailer Sintel : `https://media.w3.org/2010/05/sintel/trailer.mp4`).
> Pour ajouter tes propres films : place le fichier dans `storage/app/private/movies/` et ajoute une
> ligne dans la table `movies` (`title`, `path` relatif).

### 2. Démarrer les services (4 terminaux)
```bash
# 1 — API + streaming
php artisan serve --port=8000

# 2 — WebSocket temps réel
php artisan reverb:start --port=8080

# 3 — reverse proxy (depuis server/, lit le Caddyfile)
caddy run

# 4 — tunnel public (pointe sur Caddy, pas sur 8000 !)
ngrok http 8088
```
ngrok affiche une URL type `https://xxxx.ngrok-free.app` : c'est **l'unique URL** à saisir dans l'app.

> ⚠️ **Clé Reverb** : la clé d'app (`REVERB_APP_KEY` dans `server/.env`) doit correspondre à la
> constante `REVERB_APP_KEY` côté Android (`PlayerScreen.kt`).
> L'URL ngrok **change à chaque redémarrage** (plan gratuit) — il faut la re-saisir dans l'app.

### Vérifier le streaming
```bash
# doit répondre "206 Partial Content"
curl -I -H "Range: bytes=0-1023" http://127.0.0.1:8000/api/movies/1/stream
```

---

## 📱 Lancement de l'application

1. Ouvrir le projet (racine `SyncFlix/`) dans Android Studio, **Run** sur les deux téléphones.
2. Saisir l'**URL ngrok** comme adresse du serveur.
3. **Téléphone A** : « Créer une session » → noter/partager le **code**.
4. **Téléphone B** : « Rejoindre la session » avec ce code.

> 🔧 Pour tester le lecteur **sans backend**, passer `USE_TEST_STREAM = true` dans `PlayerScreen.kt`
> (lit un flux MP4 public).

---

## 📡 API (résumé)

| Méthode | Endpoint | Rôle |
|---|---|---|
| `GET`  | `/api/movies/{id}/stream` | Streaming vidéo (`Range`/206) |
| `GET`  | `/api/time` | Horloge serveur (offset d'horloge mobile) |
| `POST` | `/api/sessions` | Crée une session, renvoie un code |
| `POST` | `/api/sessions/{code}/join` | Rejoint une session |
| `GET`  | `/api/sessions/{code}/state` | État courant (resync) |
| `POST` | `/api/sessions/{code}/state` | Pousse une action (Play/Pause/Seek) → diffuse |

**WebSocket** : canal `movie-session.{code}`, événement `VideoStateUpdated`.

---

## ✅ État d'avancement

- [x] **Étape 1** — Streaming vidéo Laravel avec `Range`
- [x] **Étape 2** — Lecteur mobile ExoPlayer (lecture + seek)
- [x] **Étape 3** — Temps réel (Reverb + WebSocket + offset d'horloge)
- [x] **Étape 4** — Synchronisation effective (émission + application + correction de dérive)
- [x] Polish initial : style ActionCard, reconnexion WS, retour visuel, titre du film, i18n FR

---

## 🚧 Évolutions envisageables

### Bibliothèque & films
- Catalogue de films côté serveur (scan automatique d'un dossier, métadonnées, affiches)
- **Choix du film dans l'app** (au lieu du film unique codé en dur) ; changement de film en cours de session
- Sous-titres (`.srt`/`.vtt`), pistes audio multiples
- Reprise à la dernière position vue ; « continuer à regarder »
- Transcodage / qualité adaptative (HLS/DASH) pour les connexions lentes

### Session & social
- **Durcissement** : canal privé Reverb + authentification par code (au lieu du canal public)
- Expiration / nettoyage automatique des sessions inactives
- Plus de deux participants ; noms / avatars / « qui est en ligne »
- **Salon d'attente** (lobby) avant de lancer le film ensemble
- **Chat** texte et **réactions** (emojis) en surimpression
- **Appel audio/vidéo** simultané (se voir/s'entendre en regardant)

### Lecteur
- Overlay de contrôles **Compose maison** (au lieu des contrôles natifs)
- Vitesse de lecture partagée, saut ±10 s synchronisé
- **Picture-in-Picture**, lecture audio en arrière-plan
- Gestion fine de la latence par participant (compensation individuelle)

### Robustesse & réseau
- File d'attente d'événements hors-ligne + rejeu à la reconnexion
- Reconnexion exponentielle, indicateur de qualité réseau
- Resynchronisation périodique plus fine

### Infrastructure & déploiement
- **Déploiement réel** : VPS + domaine fixe + HTTPS automatique via Caddy (Let's Encrypt) → **remplacer ngrok**
- Base **MySQL/PostgreSQL** en production (au lieu de SQLite)
- Comptes utilisateurs, espaces privés, historique
- Mise à l'échelle de Reverb, monitoring

### Mobile & distribution
- **Logo & icône** définitifs (cinéma / corail)
- Partage du code par **lien profond** ou **QR code**
- Notifications (l'autre a rejoint / lancé la lecture)
- Onboarding, réglages, thèmes
- App shortcuts, widget
- i18n anglais réel (réactiver la langue selon l'appareil)
- **Tests** (unitaires, instrumentation) + CI

### Autres plateformes
- Version **iOS**
- Version **Web / TV** (Chromecast, Android TV)
