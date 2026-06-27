# 🏗️ SyncFlix — Architecture

Document de conception technique. Complète le [README.md](README.md) (vision & feuille de route MVP).

---

## 🎯 Décisions structurantes

| Décision | Choix retenu | Pourquoi |
|---|---|---|
| **Modèle de synchro** | État partagé autoritatif (le serveur détient la vérité) | Auto-réparant : un téléphone qui se reconnecte interroge l'état courant et se recale. Résiste aux messages perdus. |
| **Anti-dérive** | `server_timestamp` dans le payload + offset d'horloge + correction douce de vitesse | Seul moyen de tenir l'objectif Δt < 200 ms malgré la latence réseau. |
| **Appairage** | Code de session court (ex. `ABC123`) | Simple pour un usage à deux ; sert aussi de token d'auth pour le canal privé Reverb. |

---

## 📡 Contrat WebSocket (affiné)

> 🔒 **Canal de _présence_** `presence-movie-session.{code}`. L'app n'ayant pas de comptes, on
> n'utilise pas l'auth web/cookie de Laravel : `POST /api/broadcasting/auth`
> (`SessionController@authChannel`) signe l'abonnement (HMAC-SHA256 `socket_id:channel:channel_data`
> avec le secret Reverb) **uniquement si le code de session existe**, et renvoie un `channel_data`
> anonyme (`user_id` = identité opaque du client). Le secret reste côté serveur ; le code court reste
> le secret d'accès ; l'anti-boucle passe par `triggered_by`. **Un seul canal** porte l'état de
> lecture (`VideoStateUpdated`), le **compteur de spectateurs** (salon d'attente : `member_added` /
> `member_removed`) et les **réactions** (`ReactionSent`). Exposition : **reverse proxy Caddy**
> (`server/Caddyfile`, :8088) → `/media/*` servi en **file_server** (vidéos, Range natif, multi-thread),
> `/app/*` vers Reverb, le reste vers Laravel (un seul tunnel ngrok).
>
> ⚠️ **Streaming hors PHP** : `php artisan serve` est mono-thread ; faire transiter un gros `.mkv`
> par PHP bloque l'unique worker et empêche un 2e spectateur de **rejoindre** (join/auth en attente
> derrière le flux). Les vidéos passent donc par Caddy (`/media/{stream_path}`), pas par la route PHP
> `/api/movies/{id}/stream` (gardée en repli). Les sous-titres (petits) restent sur PHP.

**Canal :** `presence-movie-session.{code}` (présence ; auth via `POST /api/broadcasting/auth`)

```json
{
    "event": "VideoStateUpdated",
    "data": {
        "is_playing": true,
        "position_ms": 451200,
        "server_timestamp_ms": 1719500000000,
        "triggered_by": "phoneA",
        "seq": 42
    }
}
```

- **`server_timestamp_ms`** : horloge serveur au moment du broadcast. Permet à chaque téléphone de calculer la position *réelle* à la réception (compense le temps de transit du message).
- **`seq`** : numéro de séquence croissant émis par le serveur. Un téléphone ignore tout état dont le `seq` est ≤ au dernier appliqué → protège contre les messages désordonnés ou en doublon.
- **`triggered_by`** : identifiant du téléphone à l'origine de l'action (anti-boucle de premier niveau).

⚠️ Le `server_timestamp_ms` est **toujours estampillé par le serveur**, jamais par le client → une seule horloge de référence.

---

## 🌐 Contrat HTTP (streaming)

- **Endpoint :** `GET /api/movies/{id}/stream`
- **Header requis :** `Range: bytes=start-end` (géré automatiquement par ExoPlayer pour le saut dans la timeline).
- **Réponse :** `BinaryFileResponse` avec support des requêtes `Range` (status `206 Partial Content`).

---

## 🕐 Synchronisation d'horloge

Au démarrage de la session, chaque téléphone estime son offset par rapport au serveur :

1. Le téléphone envoie un ping avec son horloge locale `t0`.
2. Le serveur répond avec son horloge `t_server`.
3. À réception (`t1`), offset ≈ `t_server - (t0 + t1) / 2` (correction du RTT).

Cet offset sert à convertir `server_timestamp_ms` en temps local pour le calcul de la position.

---

## 🔁 Logique de réconciliation (cœur de la synchro)

À chaque `VideoStateUpdated` reçu, côté Android :

1. **Filtre séquence** : si `seq` ≤ dernier `seq` appliqué → ignorer.
2. **Position cible** :
   - `targetPos = position_ms + (now_local + offset − server_timestamp_ms)` si `is_playing`
   - `targetPos = position_ms` si en pause
3. **Comparaison à la position locale d'ExoPlayer** :
   | Écart | Action |
   |---|---|
   | < 500 ms | Ne rien faire (imperceptible) |
   | 500 ms – 2 s | Ajuster la vitesse de lecture (0.97× / 1.03×) jusqu'à rattrapage, puis revenir à 1.0× |
   | > 2 s | `seekTo(targetPos)` (saut franc) |
4. **Aligner** `playWhenReady` sur `is_playing`.
5. Tout ceci sous le flag **`isApplyingRemoteState = true`**.

> 🔧 **Implémenté (`PlaybackSyncManager`)** : alignement play/pause ; **pause** = saut à l'image près
> (> 150 ms) ; **boucle de contrôle** continue (toutes les 500 ms) en lecture — zone morte 120 ms,
> **correction douce de la vitesse** proportionnelle plafonnée à ±8 % (rattrape/ralentit sans saut),
> et **saut franc** au-delà de 1,5 s (seek volontaire ou écart irrattrapable). Anti-boucle = **fenêtre
> de suppression** temporelle (plus robuste que le seul flag, vu la livraison différée des callbacks
> ExoPlayer). Offset d'horloge via `ClockSync` (HTTP `/api/time`).

### Anti-boucle d'événements

- Un flag `isApplyingRemoteState` entoure chaque modification d'ExoPlayer appliquée depuis le réseau.
- Les listeners ExoPlayer (`onIsPlayingChanged`, etc.) **ignorent** tout changement tant que ce flag est levé.
- On n'émet sur le WebSocket **que** les changements initiés par l'utilisateur local.

---

## 🗄️ Backend Laravel — découpage

> ⚠️ La table de session s'appelle **`watch_sessions`** (modèle `WatchSession`), pas `sessions` :
> ce nom est déjà pris par le driver de session HTTP de Laravel (`SESSION_DRIVER=database`).
> Les routes API sont activées via `api: routes/api.php` dans `bootstrap/app.php`.

```
app/Models/
  Movie                 id, title, path
  WatchSession          id, code, movie_id, current_position_ms, is_playing, seq
app/Http/Controllers/
  StreamController       GET  /api/movies/{id}/stream         BinaryFileResponse + Range
  SessionController      POST /api/sessions                   crée une session + code
                         POST /api/sessions/{code}/join
                         GET  /api/sessions/{code}/state      état courant (resync à la reconnexion)
                         POST /api/sessions/{code}/state      reçoit l'intention → maj DB → broadcast
app/Events/
  VideoStateUpdated      ShouldBroadcastNow sur presence-movie-session.{code}
  ReactionSent           ShouldBroadcastNow sur presence-movie-session.{code} (réactions emoji)
                         POST /api/broadcasting/auth  signe l'abonnement (présence) si le code existe (authChannel)
```

Point clé : c'est **`POST /state`** qui met à jour la DB *et* déclenche le broadcast, en estampillant `server_timestamp_ms` et en incrémentant `seq`.

---

## 📱 App Android — découpage

```
data/
  StreamApi (Retrofit)        create / join / getState / postState
  SyncSocket (OkHttp WS)      connexion Reverb, parse VideoStateUpdated
  ClockSync                   ping/pong → offset horloge téléphone ↔ serveur
domain/
  PlaybackSyncManager         applique l'état distant, calcule la position réelle,
                              décide laisser-couler / vitesse / seek franc,
                              flag isApplyingRemoteState (anti-boucle)
ui/
  PairingScreen (Compose)     saisie / affichage du code de session
  PlayerScreen (Compose)      AndroidView(ExoPlayer) + overlay de contrôles
```

> UI conforme à `CHARTE_GRAPHIQUE.md` et `MATERIAL3_EXPRESSIVE.md` (Material 3 Expressive, a11y + i18n + animations = étape obligatoire de polish).

---

## 🗺️ Ordre de construction

1. **Laravel** — route de streaming + `Range`, validée au navigateur. *(README étape 1)*
2. **Android** — ExoPlayer lit l'URL via le tunnel. *(README étape 2)*
3. **Reverb** — canal privé + auth par code + ping/pong horloge. *(README étape 3)*
4. **Synchro** — `PlaybackSyncManager` complet. *(README étape 4)*
