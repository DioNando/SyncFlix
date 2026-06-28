# Miray — Suivi de sprint

> Visionnage vidéo synchrone à deux (Δt < 200 ms). Android natif (Compose / ExoPlayer-Media3) +
> backend Laravel 13 / Reverb. Nom affiché **Miray** ; projet/package internes restent `com.syncflix.app`.
> Voir aussi : [`README.md`](README.md), [`ARCHITECTURE.md`](ARCHITECTURE.md),
> [`EXPOSITION.md`](EXPOSITION.md) (mise en ligne), [`CONCEPTION.md`](CONCEPTION.md) (TMDB + voice chat).

**Dernière mise à jour :** 2026-06-27

---

## 🎯 État global

| Étape | Périmètre | Code | Validation device |
|-------|-----------|:----:|-------------------|
| **1. Socle** | App Compose M3 Expressive + backend Laravel/SQLite | ✅ | ✅ |
| **2. Streaming** | Vidéo Range/206 (via Caddy `/media`), sous-titres | ✅ | ✅ (lecture + seek) |
| **3. Temps réel** | Reverb + Caddy, **canal de présence** `presence-movie-session.{code}` | ✅ | ✅ **2 téléphones (Tailscale)** |
| **4. Synchro** | `PlaybackSyncManager` (Play/Pause/Seek + correction de dérive ±8 %) | ✅ | ✅ **2 téléphones (Tailscale)** |
| **Polish/UX** | Identité Miray, lecteur natif, réglages, salon, réactions, verrou… | ✅ | partiel |

**🎉 MVP validé bout-en-bout sur 2 appareils réels (Tailscale).** Le visionnage synchrone à deux
fonctionne. Reste : du confort/UX et les fonctionnalités futures (TMDB, voice chat).

---

## ✅ Fait (état réel)

**Backend (Laravel 13)**
- Sessions = **état partagé autoritatif** : `SessionController` (`create`/`join`/`show`/`update`/`react`/`authChannel`/`time`), `seq` croissant.
- Temps réel : `VideoStateUpdated` + `ReactionSent` (`ShouldBroadcastNow`) sur **canal de présence** ; auth `POST /api/broadcasting/auth` (signe HMAC si le code existe ; secret côté serveur).
- **Vidéo servie par Caddy** (`/media/*` file_server, multi-thread/Range) — pas par PHP (mono-thread) ; route PHP `/stream` en repli. Sous-titres sidecar via `/api/movies/{id}/subtitles/{index}`.
- Bibliothèque : `movies:scan` (scan d'un dossier, `prettyTitle` nettoie les noms, sous-titres `film.fr.vtt`).
- Caddy `:8088` : `/media`→fichiers, `/app`→Reverb, reste→Laravel.

**Android (`com.syncflix.app`)**
- Synchro : `PlaybackSyncManager` (anti-écho `triggered_by`, offset `ClockSync`, correction de dérive), `SyncSocket` (Pusher brut, reconnexion auto, présence + réactions), `SessionApi`.
- Lecteur : **contrôleur natif Media3** en **TextureView**, **vitesse masquée** via `ForwardingPlayer` (synchro intacte), bouton CC (sous-titres), **bouton verrou**, **confirmation avant de quitter**, **immersif en paysage**, header + réactions qui suivent les contrôles.
- Appairage : logo Miray au-dessus du titre, boutons pleins (bleu/or), **code en 6 cases animées** (`CodeField`).
- Choix du film : `MoviePickerScreen` (+ voile de chargement) → session sur `movie_id`.
- Salon d'attente : voile présence (code + **copier/partager** + `WaitingDots`), **2 colonnes en paysage**, « entrer seul·e ».
- Réactions : set perso, **press-scale**, emojis flottants.
- Réglages : `SettingsStore` (SharedPreferences) + `SettingsScreen` — serveur par défaut, pseudo partenaire, masquer titre/réactions, 6 emojis.
- Identité **bleu #004071 + or #FFF2A1**, dark-first ; i18n fr/en ; orientation via taille de fenêtre (cf. piège `attachBaseContext`).

---

## ⏳ Reste à faire

### 🔴 Prioritaire
- [x] **Validé sur 2 téléphones réels (Tailscale)** ✅ — WS + synchro + présence fonctionnent bout-en-bout.
- [ ] (Optionnel) **Mesurer le Δt** réel (< 200 ms) et affiner les seuils si la synchro doit être plus serrée.

### 🟡 Confort
- [x] **Overlay de dérive (debug)** ✅ — `PlaybackSyncManager.onDrift` → état dans PlayerScreen, affiché « Δ X ms » si réglage `showDebug` activé.
- [x] **Bannière « connexion perdue, reconnexion… »** ✅ — affichée quand le statut WS passe Offline (reconnexion auto déjà en place).
- [ ] (Optionnel) Nettoyer les chaînes a11y inutilisées (`cd_play`/`cd_pause`/`cd_seek_*`).

### 🔵 Fonctionnalités futures (cf. `CONCEPTION.md`)
- [x] **TMDB + watchlist + synopsis** ✅ — **backend** (config `services.tmdb` + repli CA bundle, table `wishlist_items`, `CatalogController` proxy search/détail + CRUD watchlist, routes) **et UI Android** (Coil ; `Catalog.kt`/`CatalogApi.kt`, `SearchScreen` recherche debounce + grille + fiche bottom-sheet « Ajouter », `WatchlistScreen` liste + badge disponibilité + « vu »/retrait ; nav `search`/`watchlist`, accès depuis l'icône loupe de l'appairage). Prérequis serveur : `TMDB_TOKEN` dans `.env` + `php artisan migrate` + `php artisan config:clear`.
- [ ] **Voice chat** WebRTC + **push-to-talk** (Tailscale → P2P direct, pas de TURN).

---

## 🚀 Lancement

```bash
# depuis SyncFlix/server — serve + reverb + caddy (concurrently)
composer dev
# exposition : Tailscale (plus de ngrok) — tailscale serve https / http://localhost:8088
```
> Prérequis PATH : `php`, `node`/`npx`, `caddy`. L'app saisit l'URL `https://<machine>.<tailnet>.ts.net`.
> Films : déposer les vidéos dans `storage/app/private/movies/` (+ sous-titres `film.fr.vtt`) puis
> `php artisan movies:scan`. Migration : `php artisan migrate` (colonne `movies.subtitles`).

---

## 📌 Décisions d'archi (à jour)

- **Synchro** = état autoritatif serveur, auto-réparant ; anti-dérive par offset d'horloge + correction douce de vitesse (saut franc > 1,5 s).
- **Canal de présence** `presence-movie-session.{code}` : un seul canal pour état + compteur de spectateurs + réactions ; auth maison (code = secret, pas de comptes).
- **Streaming hors PHP** (Caddy file_server) car `php artisan serve` est mono-thread.
- **Exposition = Tailscale** (ngrok abandonné : quota + URL changeante).
- **Identité** = bleu nuit #004071 + or pâle #FFF2A1, dark-first.
- **Pas de réglage de vitesse** côté UI (réservé à la correction de dérive).

---

## 🗒️ Journal (condensé)

| Date | Jalon |
|------|-------|
| 2026-06-27 | **Étapes 1-4 codées** + polish (correction de dérive, movieId dynamique). Étape 2 validée sur device ; temps réel validé côté serveur. |
| 2026-06-27 | **Canal public → privé → présence** (un seul canal état+présence+réactions) + endpoint d'auth. |
| 2026-06-27 | **Dette UI** : choix du film (scan dossier), sous-titres/audio (Media3), salon d'attente, réactions, `composer dev`. + **revue de code** (10 correctifs). |
| 2026-06-27 | **Fix join gros `.mkv`** : streaming offloadé sur Caddy `/media`. Statut → pastille ; salon redessiné, opaque, taps interceptés. |
| 2026-06-27 | **Identité Miray** : bleu+or, logo SVG→vectors, renommage `app_name`. Immersif paysage. Titres `prettyTitle`. Accueil v2 (boutons pleins, code 6 cases). |
| 2026-06-27 | **Réglages** (`SettingsStore`/`SettingsScreen`) : serveur défaut, pseudo, masquer titre/réactions, emojis perso (6 cases). **Verrou**, **confirmation de sortie**. |
| 2026-06-27 | **Exposition** : quota ngrok → **Tailscale** (`EXPOSITION.md`). |
| 2026-06-27 | **Lecteur** : essai Compose custom **abandonné** → **natif** ; vitesse masquée (`ForwardingPlayer`), **TextureView** (fin du flash noir). Salon 2 colonnes + **partager** ; fix orientation (taille fenêtre). |
| 2026-06-27 | Quick-win : voile de chargement sur le picker. Revue de cohérence (compile-safe). Doc `CONCEPTION.md` (TMDB + voice PTT). |
| 2026-06-27 | 🎉 **MVP validé sur 2 téléphones réels via Tailscale** : visionnage synchrone à deux fonctionnel bout-en-bout. |
| 2026-06-28 | **TMDB de bout en bout** : backend proxy (search/détail + CRUD watchlist, repli CA bundle SSL Windows) + **UI Android** (Coil, recherche debounce → grille d'affiches → fiche « Ajouter », watchlist avec badge disponibilité/« vu »/retrait, nav + accès loupe à l'appairage). |
| 2026-06-28 | **Retours d'usage** : (1) vidéos à crochets/parenthèses en **404 Caddy** → `movies:scan` crée des **liens `media/{id}.{ext}`**, Caddy sert depuis `media/` (URL sans caractères spéciaux). (2) **Indicateur de présence du partenaire** (icône bandeau + notice « X déconnecté·e / de retour ») + **ping WS 20 s** (moins de « kick »). (3) **Purge du scan corrigée** : supprime les sessions caduques puis les films retirés du dossier. |
