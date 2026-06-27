# Miray — Exposer le serveur sans ngrok (analyse des options)

> Tu as atteint le quota du plan gratuit ngrok. Ce doc compare les façons de rendre le serveur
> joignable par les deux téléphones, **adaptées au profil de Miray** (2 personnes, streaming vidéo
> volumineux, usage occasionnel). À lire pour décider — rien n'est encore implémenté.

---

## 1. D'abord, lever une confusion : 3 rôles différents

| Rôle | Ce que ça fait | Outils | On l'a déjà ? |
|------|----------------|--------|----------------|
| **Reverse proxy** | Fusionne plusieurs services locaux (Laravel, Reverb, vidéos) sur **un port** | **Caddy** (actuel), nginx, Traefik | ✅ oui (Caddy :8088) |
| **Ingress / tunnel** | Rend une machine **derrière un NAT** joignable depuis Internet | ngrok, Cloudflare Tunnel, Tailscale, frp… | ❌ c'est **ça** le problème |
| **Hébergement** | Faire tourner le serveur sur une machine **déjà publique** | VPS (Hetzner, Oracle…) | ❌ option à évaluer |

➡️ **« nginx sur Docker » ne résout PAS ton problème** : ça remplacerait Caddy (même rôle), mais ta
machine resterait injoignable derrière la box. Sauf si tu mets nginx **sur un VPS** — là c'est de
l'hébergement (section 4).

---

## 2. Les contraintes spécifiques de Miray

Toute solution doit gérer :

1. **HTTP REST** (`/api/...`) — trivial, tout le monde le fait.
2. **WebSocket** (Reverb / protocole Pusher, `/app/*`) — la plupart le gèrent, à vérifier.
3. **Streaming vidéo volumineux avec `Range`** (le `.mkv` de 5 Go) — **le point critique** : beaucoup
   de tunnels gratuits bloquent, bufferisent ou facturent lourdement le gros trafic non-HTML.
4. **HTTPS / WSS** — l'app dérive `wss://` de l'URL `https://` (cf. `PlayerScreen`). Il faut donc une
   URL **https** (ou adapter l'app pour accepter http/ws en clair — voir §5).

⚠️ **Le vrai goulot d'étranglement = ton débit d'UPLOAD domestique.** Diffuser du 1080p depuis chez
toi demande ~10–25 Mbit/s d'**upload** soutenu, **par spectateur distant**. Quelle que soit la
techno de tunnel, si ta box plafonne en upload, ça saccadera. Si **un** des deux est sur ton WiFi
local, seul l'autre consomme de l'upload. → À mesurer avant de choisir (test sur fast.com / Speedtest,
regarder la valeur **upload**).

---

## 3. Garder le serveur chez toi → un autre tunnel

### 3.a — Tailscale ⭐ (recommandé pour 2 personnes)
VPN maillé (WireGuard). Les 2 téléphones **et** le PC rejoignent le même « tailnet » ; les téléphones
joignent le PC par son IP Tailscale, **en direct (P2P chiffré)**, sans rien exposer publiquement.

- **Prix** : gratuit (plan Personal : jusqu'à 100 appareils / 3 utilisateurs).
- **Quota trafic** : **aucun** (le flux vidéo passe en P2P WireGuard, pas par un relais facturé).
- **Vidéo lourde** : ✅ parfait, pas de limite applicative (seul ton upload compte si distant).
- **HTTPS/WSS** : `tailscale serve` fournit un certificat TLS valide sur le nom `*.ts.net` →
  `https://mon-pc.<tailnet>.ts.net` et `wss://...` fonctionnent sans bidouille.
- **Contrainte** : chaque téléphone doit **installer l'app Tailscale et se connecter** au tailnet
  (acceptable pour un usage à deux ; tu invites l'autre personne).
- **Effort** : faible. `tailscale up` sur le PC, puis `tailscale serve https / http://localhost:8088`.

**Pourquoi c'est le meilleur fit** : gratuit, sans quota, sécurisé, gère le gros streaming, et tu
gardes les fichiers chez toi. Le seul « coût » est l'installation de Tailscale sur les 2 téléphones.

### 3.b — Cloudflare Tunnel (`cloudflared`)
Tunnel sortant vers le réseau Cloudflare → URL publique stable, **comme ngrok mais gratuit** et sans
install côté téléphones.

- **Prix** : gratuit. Nécessite un **domaine géré par Cloudflare** (un domaine ~10 €/an, ou un
  domaine gratuit).
- **WebSocket** : ✅ supporté.
- **HTTPS** : ✅ fourni par Cloudflare.
- **⚠️ Gros risque pour Miray** : les CGU self-serve de Cloudflare (§2.8) **interdisent de servir une
  part disproportionnée de contenu non-HTML** (vidéo, gros fichiers) via leur CDN gratuit. Streamer
  un `.mkv` de 5 Go en boucle est exactement le cas visé → throttling voire blocage du compte.
  → Bon pour l'API + le WebSocket, **déconseillé pour le flux vidéo**.

### 3.c — frp (Fast Reverse Proxy) auto-hébergé
`frps` sur un petit VPS public + `frpc` chez toi → le VPS relaie vers ta machine. Tu contrôles tout,
pas de CGU « anti-vidéo ». **Mais** tout le trafic vidéo transite par le VPS → tu paies sa bande
passante (comme un hébergement, sans y stocker les fichiers). Plus de setup. Intermédiaire entre
tunnel et VPS.

### 3.d — Autres tunnels (dépannage rapide)
Pinggy, bore.pub, localhost.run, serveo… gratuits mais **sessions courtes / URL changeantes / débit
limité**. OK pour un test ponctuel, pas pour des soirées film régulières.

---

## 4. Héberger sur un VPS (serveur toujours en ligne, IP publique)

On déplace **tout** (Laravel + Reverb + Caddy + **les fichiers vidéo**) sur une machine publique. Plus
de NAT, plus de tunnel. Contrepartie : il faut **téléverser les films** sur le VPS (stockage) et la
**bande passante de sortie** est consommée côté VPS.

| Fournisseur | Prix indicatif | RAM/CPU | Disque | Trafic/mois | Note |
|-------------|----------------|---------|--------|-------------|------|
| **Oracle Cloud — Always Free** | **0 €** | ARM Ampere, jusqu'à 4 vCPU / 24 Go | ~50–200 Go | **10 To** | Gratuit à vie ; ARM (PHP/Reverb OK) ; création de compte parfois capricieuse ; VM inactives parfois récupérées |
| **Hetzner CX22** | **~4 €/mois** | 2 vCPU / 4 Go | 40 Go SSD | **20 To** | Excellent rapport qualité/prix ; 40 Go = quelques films |
| **Contabo VPS S** | ~5–6 €/mois | 4 vCPU / 8 Go | 100+ Go | « illimité » (fair use) | Plus de disque pour stocker des films |
| **DigitalOcean / Scaleway** | ~5–6 €/mois | 1 vCPU / 1–2 Go | 25 Go | 1–2 To | Connu, doc abondante ; trafic plus limité |

- **HTTPS/WSS** : Caddy sur le VPS obtient un certificat Let's Encrypt **automatiquement** (avec un
  nom de domaine pointant sur l'IP). Sans domaine, on peut utiliser l'IP + certificat auto-signé
  (pénible sur mobile) → **prends un domaine** (~10 €/an, ou sous-domaine gratuit type DuckDNS).
- **Stockage des films** : `scp`/`rsync` les `.mkv` vers le VPS. Surveille la taille du disque.
- **Avantages** : toujours dispo, indépendant de ton PC/box, débit serveur généralement bon.
- **Inconvénients** : coût (sauf Oracle), upload initial des films, un peu d'administration.

---

## 5. Notes d'intégration Miray (quel que soit le choix)

- **URL https obligatoire** : l'app fait `https→wss` / `http→ws` (`PlayerScreen`). Donne-lui une URL
  `https://…` (Tailscale Serve, Cloudflare et Caddy/Let's Encrypt en fournissent).
  - Si tu veux passer en **http/ws en clair** (ex. IP Tailscale brute sans Serve), il faudra
    autoriser le trafic en clair côté Android (`android:usesCleartextTraffic="true"` ou une
    `network-security-config`) — sinon ExoPlayer/OkHttp refusent le http.
- **Caddy reste utile** dans les options « chez toi » (3.a/3.b) **et** sur le VPS : il fusionne
  `/media/*` (vidéo), `/app/*` (Reverb) et l'API sur un seul port/origine. On ne le remplace pas.
- **Reverb** : vérifier `REVERB_HOST` / `REVERB_SCHEME` dans `.env` selon l'URL publique. La clé
  publique est aussi codée côté app (`PlayerScreen`) — garder en phase.
- **Le `/media/*` en `file_server` Caddy** (fix du bug de join) reste valable partout.

---

## 6. Recommandation

| Ton besoin | Choix conseillé |
|------------|-----------------|
| **Le plus simple, gratuit, sans quota, à deux** | **Tailscale** (§3.a) — les 2 installent l'app |
| URL publique « comme ngrok », sans toucher aux téléphones | Cloudflare Tunnel pour l'API/WS **mais** vidéo à surveiller (§3.b) |
| Serveur **toujours en ligne**, indépendant de ton PC | **VPS Hetzner ~4 €/mois** (ou **Oracle Always Free**) + domaine (§4) |
| Juste dépanner ce soir | Repasser sur un tunnel éphémère (Pinggy/bore) ou **ngrok payant** (~8 €/mois) |

**Mon conseil par défaut** : commence par **Tailscale** (zéro coût, zéro quota, gère le gros
streaming, 30 min de mise en place). Si plus tard tu veux que ça marche **sans rien installer sur les
téléphones** et en permanence → bascule sur un **VPS Hetzner/Oracle + domaine + Caddy**.

### Prochaines étapes si tu choisis Tailscale
1. Créer un compte Tailscale, installer le client sur le **PC** (`tailscale up`).
2. Installer Tailscale sur les **2 téléphones**, les connecter au même compte/tailnet.
3. Sur le PC : `tailscale serve https / http://localhost:8088` (expose Caddy en https sur `*.ts.net`).
4. Dans l'app, saisir l'URL `https://<nom-pc>.<tailnet>.ts.net`. Lancer `composer dev` (sans ngrok).

> Prix et limites indicatifs (à revérifier sur les sites officiels — ils évoluent).
