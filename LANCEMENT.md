# Miray — Lancer l'environnement

Procédure pour démarrer le serveur et tester l'app à deux. Cible : **dev local sur Windows**,
exposé via **Tailscale** (pas de ngrok).

---

## Architecture des services

| Service | Port | Rôle | Lancé par |
|---------|------|------|-----------|
| **Laravel API** | 8000 | REST (`/api/...`), auth présence, état de lecture | `composer dev` |
| **Reverb** | 8080 | WebSocket temps réel (synchro, présence, réactions, signalisation voix) | `composer dev` |
| **Caddy** | 8088 | Reverse proxy : fusionne API + Reverb (`/app/*`) + vidéos (`/media/*`) sur **un** port | `composer dev` |
| **Tailscale Serve** | — | Expose Caddy en **HTTPS** sur `*.ts.net` (TLS valide) | persistant (config sauvegardée) |
| **coturn (TURN)** | 3478 + 49160-49200 | Relais audio WebRTC du **voice chat** (indispensable si les 2 tél. sont sur des réseaux différents) | Docker |

> URL serveur utilisée dans l'app : **`https://msi-fernando.tailac1cc5.ts.net`**
> (l'app dérive `wss://` et l'hôte TURN de cette URL).

---

## Prérequis (une seule fois)

1. **Outils** dans le PATH : `php` 8.3+, `composer`, `caddy`, `node`/`npm`, **Docker Desktop**, **Tailscale**.
2. **Dépendances + base** : depuis `server/` →
   ```
   composer setup        # install, .env, key:generate, migrate, npm build
   ```
3. **`.env`** (dans `server/`) — vérifier :
   - `REVERB_APP_*` (clés Reverb), `REVERB_SCHEME=http` en local
   - `TMDB_TOKEN=` (token v4 Bearer) pour la recherche/watchlist
   - `TMDB_CA_BUNDLE=` (chemin d'un cacert.pem) — **requis sur Windows** (sinon cURL error 60)
4. **Bibliothèque de films** : poser les fichiers dans le dossier scanné (`config/movies.php`) puis →
   ```
   php artisan movies:scan
   ```
5. **Tailscale** : `tailscale up` sur le PC + installer Tailscale sur les **2 téléphones** (même tailnet).
   Exposer Caddy en HTTPS (config persistante, à refaire seulement si elle disparaît) :
   ```
   tailscale serve --bg https / http://localhost:8088
   ```
   Vérifier : `tailscale serve status` doit montrer `https://<pc>.ts.net → http://localhost:8088`.

---

## Lancement quotidien

Dans cet ordre :

1. **Tailscale actif** sur le PC et les 2 téléphones (icône connectée). Rien à relancer si déjà up.

2. **coturn (voice chat)** — depuis `server/turn/` :
   ```
   docker compose up -d
   ```
   > A `restart: unless-stopped` → repart seul quand Docker Desktop démarre. Inutile de le relancer
   > à chaque fois s'il tourne déjà (`docker compose ps`).

3. **Serveur (API + Reverb + Caddy)** — depuis `server/` :
   ```
   composer dev
   ```
   Lance les 3 process en parallèle (concurrently). `Ctrl+C` les arrête tous (`--kill-others`).

4. **Dans l'app** (les 2 téléphones) : saisir l'URL `https://msi-fernando.tailac1cc5.ts.net`,
   créer/rejoindre une session avec le même code.

---

## Vérifications rapides

- **Serveur joignable** : ouvrir `https://msi-fernando.tailac1cc5.ts.net/api/time` dans un navigateur
  du tailnet → doit renvoyer un JSON d'horloge.
- **coturn up** : `docker compose ps` (dans `server/turn/`) → `miray-coturn ... Up`.
- **coturn fonctionnel** (test allocation+relais) :
  ```
  docker exec miray-coturn turnutils_uclient -y -u miray -w miray-turn-2026 -p 3478 -n 2 100.104.159.28
  ```
  → doit finir par `Total lost packets 0`.
- **Voice chat OK** (via `adb logcat`, filtre `VoiceChat`) : on doit voir
  `createPeerConnection iceServers=3` puis `ICE state -> CONNECTED`.

---

## Arrêt

- Serveur : `Ctrl+C` dans le terminal `composer dev`.
- coturn : `docker compose down` (dans `server/turn/`) — ou le laisser tourner.
- Tailscale Serve : reste configuré (le retirer : `tailscale serve --https=443 off`).

---

## Dépannage

| Symptôme | Cause probable / action |
|----------|--------------------------|
| App ne joint pas le serveur | Tailscale déconnecté (PC ou tél.) ; `composer dev` pas lancé ; mauvaise URL |
| Vidéo ne charge pas / 404 | `php artisan movies:scan` pas relancé après ajout de films ; Caddy non démarré |
| **Voice chat muet** | coturn arrêté ; **pare-feu Windows** bloque l'UDP ; build app pas à jour sur **les 2** tél. |
| Pare-feu (UDP voix) | en PowerShell **admin** : `New-NetFirewallRule -DisplayName "Miray TURN" -Direction Inbound -Protocol UDP -LocalPort 3478,49160-49200 -Action Allow` |
| coturn annonce une mauvaise IP | l'IP Tailscale du PC a changé → mettre à jour `external-ip` dans `server/turn/turnserver.conf` (et la même IP côté test) puis `docker compose up -d --force-recreate` |
| TMDB cURL error 60 | renseigner `TMDB_CA_BUNDLE` dans `.env` puis `php artisan config:clear` |
| Erreur clé Reverb | `REVERB_APP_KEY` du `.env` doit matcher la clé codée dans `PlayerScreen` |

---

## Améliorations

- ✅ **Pic de synchro au Play** (était ~200-300 ms → ~110 ms) : alignement par saut immédiat à la
  transition pause→play (`PlaybackSyncManager`, `PLAY_ALIGN_MS`). Le pic doit retomber sous la deadband.
- ✅ **Barre de lecture** : portion **bufferisée** rendue bien visible (couleurs charte sur la
  `DefaultTimeBar` native, `PlayerScreen`).
- ✅ **Gestes luminosité/volume** : glisser verticalement à **gauche = luminosité**, à **droite =
  volume**, avec indicateur centré. Actifs quand les contrôles natifs sont masqués (un tap les
  réaffiche). La luminosité est restaurée au système en quittant le lecteur.
