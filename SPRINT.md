# SyncFlix — Suivi de sprint

> Visionnage vidéo synchrone à deux (Δt < 200 ms). Android natif (Compose / ExoPlayer-Media3) + backend Laravel 13 / Reverb.
> Voir aussi : [`README.md`](README.md) (vision) · [`ARCHITECTURE.md`](ARCHITECTURE.md) (conception).

**Dernière mise à jour :** 2026-06-27 · **Commit :** `11c55e1` · **Working tree :** propre

---

## 🎯 État global

| Étape | Périmètre | Code | Validation |
|-------|-----------|:----:|------------|
| **1. Socle** | App Android M3 Expressive (repris de WorkSync), backend Laravel + SQLite | ✅ | ✅ |
| **2. Streaming** | `StreamController` → `BinaryFileResponse` (Range / 206), lecture ExoPlayer | ✅ | ✅ **device** (lecture + seek) |
| **3. Temps réel** | Reverb + Caddy (:8088) + ngrok, canal `movie-session.{code}`, `VideoStateUpdated` | ✅ | ⚠️ **serveur seulement** (client WS Node) |
| **4. Synchro effective** | `PlaybackSyncManager` (émet/applique Play/Pause/Seek, correction douce ±8%) | ✅ | ⏳ **à tester sur 2 téléphones** |
| **Polish** | ActionCard, bandeau SyncStatus, reconnexion auto, logo, i18n FR | ✅ | partiel |

**Phase actuelle : validation terrain + durcissement.** Le développement des 4 étapes est terminé ; il ne reste quasi plus de code applicatif, mais de la validation sur appareils réels et la sécurisation du canal.

---

## ✅ Fait

### Backend (`server/`, Laravel 13)
- `StreamController@stream` — `GET /api/movies/{movie}/stream`, `BinaryFileResponse` (Range / 206 natif).
- `SessionController` — `create`, `join(code)`, `show(code)`, `update(code)`, `time`. Serveur = état autoritatif, `seq` croissant.
- `VideoStateUpdated` (`ShouldBroadcastNow`, diffusion synchrone) sur canal **public** `movie-session.{code}`. Payload : `is_playing`, `position_ms`, `seq`, `server_timestamp_ms`, `triggered_by`.
- `Movie` (title / path), film de test seedé (`sintel.mp4`, id=1).
- `Caddyfile` (:8088) — reverse proxy : `/app/*` → Reverb (:8080), reste → Laravel (:8000). 1 seule URL ngrok.
- Reverb configuré dans `.env` (`REVERB_APP_KEY=9dfaadf61b6ac53c4a66`, scheme=http local).

### Android (`app/`, `com.syncflix.app`)
- `PlaybackSyncManager` (domain/sync) — émet Play/Pause/Seek, applique l'état distant (filtre `seq` + anti-écho `triggered_by`), position extrapolée via offset `ClockSync`.
  - Réconciliation : pause = saut si > 150 ms (`PAUSE_SEEK_MS`) ; lecture = boucle 500 ms (`SYNC_TICK_MS`).
  - ✅ **Correction douce de vitesse** : `(diff / CATCHUP_MS).coerceIn(±MAX_RATE)`, `MAX_RATE = 0.08f` (±8 %), zone morte 120 ms, saut franc si > 1500 ms (`BIG_SEEK_MS`).
  - Anti-boucle : fenêtre temporelle `suppressEmitUntil` (~800 ms, 1500 ms au bootstrap).
- `SyncSocket` (data/remote) — client WS OkHttp / protocole Pusher brut, **reconnexion auto** (2 s) sur coupure non volontaire, resync `getState` à chaque (ré)abonnement, répond aux `pusher:ping`.
- `ClockSync` — `GET /api/time`, offset d'horloge (garde le meilleur RTT sur 5 mesures).
- `SessionApi` — `create` / `join` / `getState` / `updateState`.
- `PlayerScreen` — ExoPlayer via `AndroidView` + `PlayerView`, bandeau (titre + code + `SyncStatus`), retour visuel transitoire (2.5 s) sur action distante (`onRemoteApplied`). Flag `USE_TEST_STREAM = false`. **movieId dynamique** (`SessionState.movieId`).
- `ActionCard` (ui/components) — pastille d'icône tonale + libellé + chevron, press-scale + haptique + a11y.
- `CodeReadyPanel` (PairingScreen) — code de session + bouton copier.
- ✅ **Logo** : `ic_launcher_foreground.xml` = bouton play corail (#FF5A5F) + fond adaptatif.
- i18n : app **forcée en français** (`MainActivity.attachBaseContext`, `Locale.FRENCH`).

---

## ⏳ Reste à faire

### 🔴 Bloquant / prioritaire
- [ ] **Valider WS + synchro sur 2 téléphones réels** — pipeline temps réel jamais confirmé sur device (seulement client WS Node côté serveur). C'est le jalon qui « valide » l'étape 4.
- [ ] **Mesurer le Δt réel** en conditions réseau (objectif < 200 ms) et ajuster les seuils si besoin.

### 🟠 Durcissement MVP
- [x] **Canal public → privé** ✅ — `private-movie-session.{code}`, auth `POST /api/broadcasting/auth` (`SessionController@authChannel`) qui signe (HMAC-SHA256) uniquement si le code existe. Secret Reverb gardé côté serveur. Câblé côté Android (`SyncSocket.authorize` → `SessionApi.authChannel`). *(à valider sur device)*

### 🟡 Confort / dette
- [x] **Choix du film dans l'app** ✅ — `GET /api/movies` + `MoviePickerScreen` ; « Créer » → choix du film → session sur `movie_id`. Bibliothèque alimentée par **scan d'un dossier** (`php artisan movies:scan`, cf. `config/movies.php`).
- [x] **Pistes audio multiples + sous-titres** ✅ — sélection **locale** via Media3 (bouton CC + menu réglages), non synchronisée. Sous-titres sidecar (`film.fr.vtt`) découverts au scan, servis par `GET /api/movies/{id}/subtitles/{index}`.
- [x] **Salon d'attente** ✅ — gate minimal anonyme via **canal de présence** Reverb : voile affichant le code à partager tant que `< 2` spectateurs connectés ; bouton « entrer seul·e » pour tester.
- [x] **Réactions emoji** ✅ — barre d'emojis → `POST /sessions/{code}/reaction` → `ReactionSent` diffusé ; animation « flottante » à l'écran.
- [x] **Lancement serveur en une commande** ✅ — `composer dev` (via `concurrently`) démarre serve + reverb + caddy + ngrok.
- [ ] Gestion d'erreurs réseau plus fine côté UI (URL ngrok expirée en cours de session).

---

## 🚀 Lancement (1 commande)

```bash
# depuis SyncFlix/server — démarre serve + reverb + caddy + ngrok via concurrently
composer dev
```
> Prérequis sur le PATH : `php`, `node`/`npx`, `caddy`, `ngrok`. `caddy run` lit `server/Caddyfile`.
> Détail des process : API Laravel :8000 · Reverb :8080 · Caddy :8088 (reverse proxy) · ngrok → 8088.
> L'app dérive l'URL WS de l'URL serveur (https → wss, même hôte). Reporter l'URL ngrok dans l'app à chaque redémarrage (plan gratuit).

## 🎬 Bibliothèque de films

```bash
# Déposer les vidéos dans storage/app/private/movies/ (+ sous-titres sidecar film.fr.vtt / film.en.srt)
php artisan movies:scan        # (re)peuple la table movies ; idempotent
```
> Dossier/extensions configurables dans `config/movies.php`. Sous-titres : `<base>.<lang>.<vtt|srt>`.

---

## 📌 Décisions d'archi (rappel)

- **Synchro** = état partagé autoritatif (serveur = vérité, auto-réparant à la reconnexion).
- **Anti-dérive** = `server_timestamp` + offset d'horloge + correction douce de vitesse (saut franc si > 1,5 s).
- **Appairage** = code de session court (`ABC123`), sert aussi de token Reverb.
- **Identité** = « cinéma sombre + corail » (primary #FF5A5F, secondary violet #B388FF), dark-first.
- **Canal public** = choix MVP assumé (pas de comptes → auth canal privé pénible en mobile).

---

## 🗒️ Journal

| Date | Évènement |
|------|-----------|
| 2026-06-27 | Étapes 1-4 codées + polish. Correction douce de vitesse, logo et movieId dynamique finalisés (au-delà du MVP prévu). Commit `11c55e1`. |
| 2026-06-27 | Étape 2 validée bout-en-bout sur téléphone (streaming Range/206). Pipeline temps réel validé côté serveur. |
| 2026-06-27 | Canal Reverb passé en **privé** (`private-movie-session.{code}`) + endpoint d'auth `POST /api/broadcasting/auth`. À valider sur device. |
| 2026-06-27 | **Dette UI** : choix du film (scan dossier + `movies:scan`), sous-titres/pistes audio (Media3 local), salon d'attente (canal de **présence**), réactions emoji, `composer dev`. Canal privé → **présence** (un seul canal pour état + présence + réactions). À valider sur 2 devices. |
| 2026-06-27 | **Revue de code** (effort high) + 10 correctifs appliqués (purge scan sûre, garde film nul, salon non re-voilé, `rememberSaveable`, sous-titres à points, socket périmé, refactor `SessionApi`, chaînes orphelines, doc). |
| 2026-06-27 | **Fix join sur gros `.mkv`** : `php artisan serve` mono-thread saturé par le flux vidéo → vidéos servies par **Caddy `/media/*` (file_server)**, plus par PHP. **UI** : statut synchro → **pastille** (vert/ambre/rouge) au lieu du texte vertical ; **salon d'attente** redessiné (icône + carte « ticket » du code + bouton tonal). |
| 2026-06-27 | **Revue captures** : salon d'attente rendu **opaque** + contrôles natifs masqués (`useController=false`/`hideController`) + taps interceptés (transparaissaient et restaient cliquables) ; **barre de réactions** détourée pour être visible sur fond noir. Paysage déjà OK (manifest `configChanges`). |
| 2026-06-27 | **Nouvelle identité** : primary **#004071** (bleu) + secondary **#FFF2A1** (or pâle), remplace corail+violet (`Color.kt`/`Theme.kt`, tons dérivés par thème ; logo teinté `primary` ; icône launcher bleu+or). **Mode immersif paysage** : barres système + bandeau masqués en `ORIENTATION_LANDSCAPE` (plein écran cinéma, retour au portrait restaure les barres). |
| 2026-06-27 | **Titres nettoyés** dans `movies:scan` (`prettyTitle`) : coupe aux tags techniques (1080p/WEB/H264/MULTI…) + garde `SxxExx` → « House Of The Dragon S03E01 ». Re-lancer `php artisan movies:scan` pour réindexer les titres existants. |
| 2026-06-27 | **Accueil v2** : boutons d'accueil **pleins** (« Rejoindre » bleu `primary`, « Créer » or pâle réel **#FFF2A1** via `secondaryContainer` figé dans les 2 thèmes) ; **logo au-dessus du titre** ; champs sans icône ; **code en 6 cases** (`CodeField` OTP, MAJ alphanum, case active surlignée). |
| 2026-06-27 | **Logo final + renommage** : nom de l'app → **« Miray »** (`app_name`) ; logo SVG (`logo/`) converti en vector drawables — monogramme « M » bleu/or (in-app, non teinté), icône adaptative = fond **dégradé** or→bleu + marque + monochrome. |
