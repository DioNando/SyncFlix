# Miray — Conception : TMDB/Watchlist & Voice chat

> Doc de conception de deux fonctionnalités à venir. Rien n'est encore codé. Objectif : cadrer le
> schéma DB, les endpoints, les écrans, les dépendances et un **découpage en étapes** pour décider du
> séquençage. Architecture existante réutilisée : Caddy (`/media/*`, `/app/*`, API), canal de
> **présence** Reverb, **Tailscale** (P2P direct), `SettingsStore` (réglages), `prettyTitle` (scan).

---

## 0. Coût : les deux features sont **gratuites** (usage perso à deux)

| Brique | Gratuit ? | Détail / nuance |
|--------|-----------|------------------|
| **TMDB API** | ✅ | Clé gratuite (inscription). Quotas larges (~50 req/s). Images sur le CDN TMDB gratuit. **Attribution requise** (« This product uses the TMDB API but is not endorsed or certified by TMDB »). Licence commerciale séparée si Miray devenait un produit — sans objet ici. |
| **Coil** (images Android) | ✅ | Bibliothèque open-source. |
| **WebRTC** (`stream-webrtc-android`) | ✅ | Open-source. |
| **Signalisation voix** | ✅ | Réutilise Reverb (déjà self-hosted), via `client-*` events → **aucun code/serveur en plus**. |
| **TURN/STUN (relais voix)** | ✅ (non nécessaire) | Le point qui coûte d'habitude. **Tailscale donne une connectivité directe → P2P sans relais.** |
| **Data mobile** | — | Pas une licence : voix ≈ 20-40 kbps (Opus), affiches légères. Nul en WiFi. |

➡️ **Conclusion : 0 € pour ton cas.** Seules contraintes : créer une clé TMDB + afficher l'attribution ;
et pour la voix, rester sur Tailscale (sinon il faudrait un TURN — gratuit en logiciel via coturn sur
un VPS, mais consommant de la bande passante).

---

## 1. TMDB + Watchlist + Synopsis  *(recommandé en premier)*

### 1.1 Concept clé : deux notions distinctes
- **Bibliothèque** = fichiers réellement présents, streamables (`movies` + `movies:scan`).
- **Watchlist** = films **à regarder plus tard**, simples **références TMDB** (titre, année, affiche,
  synopsis), **sans fichier**. Un item devient « regardable » seulement si un fichier correspondant
  existe dans la bibliothèque → badge « disponible / à récupérer ».

### 1.2 TMDB
- Compte gratuit → **token v4 (read access)** stocké côté serveur (`.env` `TMDB_TOKEN`).
- Recherche : `GET https://api.themoviedb.org/3/search/movie?query=...&language=fr-FR`.
- Détail : `GET .../3/movie/{id}?language=fr-FR` (overview, runtime, genres…).
- Affiches : `https://image.tmdb.org/t/p/w500{poster_path}`.
- **Le serveur proxifie** (clé jamais dans l'app).

### 1.3 Schéma DB (Laravel)
Nouvelle table `wishlist_items` :

| Colonne | Type | Rôle |
|---------|------|------|
| `id` | bigint PK | |
| `tmdb_id` | int (unique) | film TMDB |
| `title` | string | |
| `year` | string nullable | |
| `poster_path` | string nullable | chemin TMDB (`/abc.jpg`) |
| `overview` | text nullable | synopsis |
| `watched` | bool (défaut false) | coché « vu » |
| `created_at/updated_at` | timestamps | |

Option (enrichissement bibliothèque) : ajouter `tmdb_id` nullable + `poster_path`/`overview` à `movies`,
remplis en matchant `prettyTitle` au 1er résultat TMDB lors du scan → affiches/synopsis dans le picker.

### 1.4 Endpoints (Laravel)
- `GET /api/search?q=` → proxy TMDB search (liste : tmdb_id, title, year, poster_url, overview).
- `GET /api/movies-meta/{tmdbId}` → détail TMDB (synopsis complet…). *(nom distinct de `/movies` existant)*
- `GET /api/wishlist` → liste de la watchlist (+ flag `available` = existe en bibliothèque par titre/tmdb_id).
- `POST /api/wishlist` `{tmdb_id,...}` → ajoute.
- `PATCH /api/wishlist/{id}` `{watched}` → coche vu / pas vu.
- `DELETE /api/wishlist/{id}` → retire.

### 1.5 Android
- **Dépendance** : `io.coil-kt:coil-compose` (chargement d'affiches, avec le header anti-ngrok inutile
  ici — images directes TMDB).
- **Écrans** :
  - **Recherche** (`SearchScreen`) : champ + grille d'affiches (résultats TMDB).
  - **Détail** (`MovieDetailScreen`) : affiche + synopsis + bouton **Ajouter à la watchlist** ;
    si disponible en bibliothèque → bouton **Regarder ensemble** (crée une session sur ce film).
  - **Watchlist** (`WatchlistScreen`) : liste, badge « disponible », marquer vu, retirer.
- **Navigation** : nouvelles routes `search`, `detail/{tmdbId}`, `watchlist` ; entrées depuis l'accueil
  (à côté de l'engrenage réglages) et/ou depuis le picker.
- **Modèles/API** : `data/remote/CatalogApi` (search, detail, wishlist CRUD), `data/model/TmdbMovie`,
  `WishlistItem`.

### 1.6 Découpage (effort indicatif ~1,5–2 j)
1. **Backend** : token TMDB + proxy search/détail + table & endpoints wishlist. *(~½ j)*
2. **Android data** : Coil + `CatalogApi` + modèles. *(~¼ j)*
3. **Écrans** : recherche → détail → watchlist + nav. *(~¾ j)*
4. *(option)* Enrichir la bibliothèque (match TMDB au scan → affiches dans le picker). *(~¼ j)*

---

## 2. Voice chat — WebRTC + Push-to-talk  *(le plus lourd)*

### 2.1 Techno
- **WebRTC** (fork maintenu **`stream-webrtc-android`**) : capture micro, **Opus**, jitter buffer,
  perte de paquets, **AEC** (annulation d'écho) + réduction de bruit.
- **Audio seul** (pas de vidéo).

### 2.2 Connectivité — Tailscale = P2P direct
WebRTC découvre les IP Tailscale (100.x.x.x) comme *host candidates* mutuellement joignables →
**connexion directe, sans TURN ni STUN**. (`PeerConnection` avec `iceServers` vide suffit sur le tailnet.)

### 2.3 Signalisation — sur le canal de présence Reverb
- Activer les **client events** dans Reverb (`config/reverb.php` → app `enable_client_messages = true`).
- Les deux pairs s'échangent **offer / answer / ICE** via des `client-voice-*` events sur
  `presence-movie-session.{code}` → **pas de code serveur**. La présence indique déjà que le partenaire
  est là ; convention simple pour décider qui émet l'offer (ex. plus petit `clientId`).

### 2.4 Push-to-talk (décidé — la partenaire a souvent des soucis de casque)
- **Half-duplex** : micro **coupé par défaut**, ouvert seulement tant qu'on **maintient** le bouton →
  casse la boucle d'écho **sans dépendre d'un casque**.
- La connexion WebRTC reste établie ; on **active/désactive juste la piste micro**
  (`audioTrack.setEnabled(held)`).
- **Ducking auto** : tant qu'une personne parle, baisser le volume du film des deux côtés
  (`ExoPlayer.setVolume(0.3f)`). On signale « parle/arrête » via un `client-voice-talk` event pour
  ducker à distance + afficher **« 🎙️ {pseudo} parle… »** (pseudo des réglages).
- **Réglage** : switch « micro ouvert / push-to-talk » (défaut **PTT**).

### 2.5 Plateforme Android
- **Permission** `RECORD_AUDIO` (runtime).
- **Service de premier plan** (`FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MICROPHONE` sur Android 14+)
  pour garder la **connexion** vivante en arrière-plan.
- **Audio focus** : film + voix partagent la sortie ; gérer le ducking proprement.
- **UI** : gros bouton **maintenir pour parler** dans le lecteur (à placer aussi accessible en paysage,
  contrairement au verrou actuel) + indicateur de transmission.

### 2.6 Découpage (effort indicatif ~2–4 j)
1. **Dépendance + permission + service** de premier plan. *(~½ j)*
2. **PeerConnection** + piste audio + `iceServers` vide. *(~½ j)*
3. **Signalisation** Reverb (`client-voice-*`, activer client messages). *(~½–1 j)*
4. **PTT** : bouton hold, gating de la piste, event talk-start/stop. *(~½ j)*
5. **Ducking + indicateur** + réglage PTT/ouvert. *(~½ j)*

### 2.7 Repli pragmatique
Un appel **WhatsApp/Discord en parallèle** fait le job à coût zéro. Le voice chat intégré ne se
justifie que pour l'expérience « tout dans Miray » (mute lié au lecteur, ducking auto, indicateur).

---

## 3. Séquençage recommandé

1. **TMDB / Watchlist** d'abord : plus simple, valeur immédiate (chercher, garder « à regarder »,
   synopsis), zéro risque technique.
2. **Voice chat** ensuite : plus lourd, mais Tailscale enlève la partie risquée (NAT/TURN). PTT acté.

> Prérequis transverses : rester sur **Tailscale** (URL https `*.ts.net`), Reverb client messages
> activés pour la voix, clé TMDB en `.env` côté serveur.
