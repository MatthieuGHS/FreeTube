# FreeTube — Brief projet pour Claude Code

> À placer dans le dépôt sous `docs/BRIEF.md`.
> Première instruction à donner à Claude Code : « Lis `docs/BRIEF.md` en entier, puis exécute la section 0. »

---

## 0. Démarrage (à faire avant toute ligne de code)

1. Lis ce document en entier. **Les décisions qu'il contient sont actées** : ne les remets pas en cause et ne les contourne pas. Si tu penses qu'une décision pose un vrai problème technique, arrête-toi, explique le problème et propose une alternative, puis attends ma réponse.
2. Vérifie que les skills de la section 2 sont installés. S'il en manque un, donne-moi la commande d'installation et attends.
3. Crée un `CLAUDE.md` à la racine. Il doit rester court : règles de travail, commandes utiles, pointeur vers `docs/BRIEF.md`. Il doit permettre à une nouvelle session de reprendre le projet sans contexte.
4. Pose-moi en une seule fois les questions encore ouvertes :
   - le nom de package Android (proposition : `fr.appartfinder.freetube`) ;
   - le nom du bucket S3 (proposition : `freetube-<suffixe-aléatoire>`) ;
   - où est géré le DNS d'`appartfinder.fr` : Route 53 ou un registrar externe ;
   - la version d'Android de mon téléphone et son ABI (probablement arm64-v8a).
5. Démarre ensuite le jalon **M0** (section 11).

---

## 1. Qui je suis et comment travailler avec moi

- Je suis élève-ingénieur. Ce projet est **vibe codé**, mais je veux **comprendre et maîtriser toute l'architecture**. C'est aussi un projet d'apprentissage en génie logiciel et en architecture.
- Pour chaque décision non triviale, explique le **pourquoi**, les **alternatives écartées** et le **compromis** accepté.
- Langue :
  - échanges et documentation en **français** ;
  - code, identifiants, messages de commit et noms de fichiers en **anglais** ;
  - textes de l'interface (Android et web) en **français**.
- Je préfère des réponses directes et concises, sans remplissage.
- Ne lance **jamais** une action irréversible ou touchant à la prod sans me demander : commande AWS, modification DNS, `ssh freetube` qui modifie le serveur, suppression de données.
- Je fais moi-même les manipulations dans la console AWS. Ton rôle : écrire les fichiers de config et un guide pas à pas précis, puis m'expliquer chaque étape.

---

## 2. Skills à utiliser

| Skill | Rôle | Quand |
|---|---|---|
| **pstack** (plugin, port Claude Code du stack de Lauren Tan, avec `poteto-mode`) | Choisir le bon workflow, planifier, vérifier | Au début de chaque jalon (plan) et à la fin (vérification) |
| **ponytail** | Écrire le minimum de code nécessaire en réutilisant la lib standard, les fonctions natives de la plateforme et le code existant | Pendant toute l'implémentation. **Il ne s'active pas tout seul** : invoque `/ponytail` au début de chaque session d'implémentation |
| **thermo-nuclear-code-quality-review** (cursor-team-kit) | Revue de qualité approfondie | À la fin de chaque jalon, avant de me rendre la main |

Installation, si nécessaire :

```
/plugin marketplace add DietrichGebert/ponytail
/plugin install ponytail@ponytail
/plugin marketplace add michael-denyer/pstack-claude
/plugin install pstack@pstack-claude
```

Le port `pstack-claude` embarque peut-être déjà des skills de la cursor-team-kit. Vérifie si `thermo-nuclear-code-quality-review` est disponible. Sinon, indique-moi comment l'installer depuis `cursor/plugins` (cursor-team-kit).

**Règle d'arbitrage :** ce brief l'emporte sur les skills. ponytail réduit le code, mais ne doit **jamais** supprimer la sécurité, la validation des entrées, la gestion d'erreurs utile ou les tests.

---

## 3. Le produit

**Le problème :** aujourd'hui, pour écouter ma musique, j'utilise trois outils. Je télécharge l'audio d'une vidéo YouTube via notube, je récupère le fichier, puis je l'écoute dans VLC.

**FreeTube**, c'est :
- **une app Android native** qui :
  - affiche YouTube dans l'app (WebView) avec un bouton de téléchargement, pour un morceau ou une playlist ;
  - accepte le partage depuis l'app YouTube ;
  - télécharge l'audio sur le téléphone et l'envoie sur S3 ;
  - sert de lecteur audio complet (bibliothèque, playlists, lecture en arrière-plan), qui marche hors-ligne ;
- **un site web protégé** (`https://appartfinder.fr`) pour écouter et gérer ma bibliothèque depuis mon PC.

C'est un projet **personnel, privé, non commercial**, avec **un seul utilisateur** (moi) et quelques centaines de morceaux.

### Périmètre v1

- WebView m.youtube.com avec un bouton flottant contextuel (morceau / playlist).
- Intent de partage « YouTube → FreeTube ».
- Téléchargement d'un morceau ou d'une playlist publique entière. Une playlist importée est une **copie figée**.
- Upload sur S3. Bibliothèque. Playlists FreeTube (créer, renommer, réordonner, ajouter ou retirer un morceau, supprimer). Renommer le titre ou l'artiste d'un morceau. Supprimer un morceau.
- Lecture locale si le fichier est sur le téléphone, sinon en streaming depuis S3. Lecture en arrière-plan avec notification et écran de verrouillage.
- Modifications hors-ligne sur le téléphone, synchronisées au retour du réseau. **Le téléphone gagne les conflits.**
- Web : login, bibliothèque, lecteur persistant, gestion des playlists et des morceaux, gestion du token d'appareil.

### Exclu définitivement (ne rien préparer pour ça)

- Accès aux contenus privés YouTube : playlists privées, « J'aime », compte YouTube. **Pas de passage de cookies vers yt-dlp.**
- Téléchargement lancé depuis le web, ou yt-dlp côté serveur.
- Plusieurs utilisateurs : pas de `user_id`, pas de rôles.
- iOS.

### Plus tard, peut-être (ne pas implémenter, ne rien préparer)

Fusion des conflits champ par champ, paroles, égaliseur, statistiques d'écoute.

---

## 4. Architecture

```
┌──────────────────────────────────┐
│ ANDROID : Kotlin / Compose       │
│ WebView YouTube + bouton ⬇       │
│ Partager → FreeTube              │
│ yt-dlp local (youtubedl-android) │
│ WorkManager : télécharge/uploade │
│ Room : vérité locale + outbox    │
│ Media3 : lecture arrière-plan    │
└───────────────┬──────────────────┘
                │ HTTPS + token d'appareil (Bearer)
                ▼
┌──────────────────────────────────┐        ┌─────────────────────┐
│ EC2 « freetube »                 │        │ NAVIGATEUR PC       │
│ appartfinder.fr                  │◄───────│ React (login)       │
│ Caddy (HTTPS auto)               │ cookie │                     │
│ FastAPI + SQLite                 │        └──────────┬──────────┘
│ sert aussi le build React        │                   │
└───────────────┬──────────────────┘                   │
                │ génère des URLs pré-signées          │
                ▼                                      │
┌──────────────────────────────────┐                   │
│ S3 eu-west-3 (privé, versionné)  │◄──────────────────┘
│ audio/{videoId}.{ext}            │  lecture directe via 302
│ thumbs/{videoId}.jpg             │
│ backups/                         │
└──────────────────────────────────┘
```

### Décisions actées, et pourquoi

| # | Décision | Raison |
|---|---|---|
| D1 | Android natif en **Kotlin + Jetpack Compose** | Lecture en arrière-plan fiable (Media3), intents natifs, yt-dlp embarquable |
| D2 | Le **téléchargement YouTube se fait sur le téléphone**, jamais sur le serveur | YouTube bloque les IP de datacenter (AWS) |
| D3 | **Les fichiers ne transitent jamais par le serveur** : upload et lecture via URLs pré-signées S3 | Serveur léger. Il ne manipule que du JSON |
| D4 | Serveur **FastAPI + SQLite**, un seul processus uvicorn | Simple, suffisant pour un utilisateur. SQLite et le rate-limit en mémoire supposent un seul worker |
| D5 | Le serveur est la **référence pour le web**, **Room est la vérité locale du téléphone**, le téléphone gagne les conflits | Mon choix produit : je veux pouvoir tout faire hors-ligne sur le téléphone |
| D6 | Conflits résolus **par élément entier** (un morceau, ou une playlist avec son nom et ses items) | Simple et suffisant. La fusion champ par champ est hors v1 |
| D7 | Web en **React + TypeScript + Vite**, servi par FastAPI sur la même origine | Lecteur persistant entre les pages (SPA), pas de CORS |
| D8 | **Caddy** en reverse proxy, **Docker Compose** pour le déploiement | HTTPS automatique, déploiement reproductible |
| D9 | **Pas de clés AWS** côté Android ni dans le code : le serveur utilise le rôle IAM de l'instance | Sécurité |
| D10 | Format audio : **flux d'origine, sans réencodage** (m4a de préférence) | Pas de perte de qualité. Android et les navigateurs lisent tous le m4a |
| D11 | Dépôt unique (monorepo) | Un projet, un contrat d'API partagé |

---

## 5. Organisation du dépôt

```
freetube/
├── CLAUDE.md
├── docs/
│   ├── BRIEF.md            # ce document
│   ├── adr/                # une ADR par décision importante (NNNN-titre.md)
│   ├── LEARNING.md         # récaps pédagogiques, un par jalon
│   ├── infra-setup.md      # guide console AWS pas à pas
│   └── deploy.md           # déployer / mettre à jour / restaurer
├── server/                 # FastAPI (uv)
├── web/                    # React + Vite
├── android/                # projet Android Studio
└── infra/
    ├── docker-compose.yml
    ├── Caddyfile
    ├── iam/freetube-s3-policy.json
    ├── s3/lifecycle.json
    └── systemd/            # timers de sauvegarde et de purge
```

Pas de découpage en modules Gradle ni de sur-architecture : un module Android `app`, organisé en **packages par fonctionnalité**.

---

## 6. Modèle de données

### Identifiants

- **Morceau** : identifiant = `videoId` YouTube. C'est une clé naturelle, qui rend les doublons impossibles par construction.
- **Playlist** : UUID **généré par le client** (téléphone ou web). Ça permet de créer une playlist hors-ligne.

### Serveur (SQLite, WAL activé, clés étrangères activées)

| Table | Colonnes principales |
|---|---|
| `tracks` | `video_id` PK, `title`, `artist`, `duration_s`, `audio_ext`, `audio_mime`, `status` (`pending_upload` / `ready`), `rev`, `deleted_at`, `created_at` |
| `playlists` | `id` (UUID) PK, `name`, `source_youtube_id` (nullable, **informatif uniquement**), `rev`, `deleted_at`, `created_at` |
| `playlist_items` | `playlist_id`, `video_id`, `position`. Remplacés en bloc à chaque écriture de la playlist |
| `device_tokens` | `id`, `name`, `token_sha256`, `created_at`, `last_used_at`, `revoked_at` |
| `sessions` | `id_sha256`, `created_at`, `expires_at` |
| `processed_ops` | `op_id` PK, `applied_at`. Sert à l'idempotence de l'outbox |
| `meta` | `key`, `value`. Contient le compteur global `server_rev` |

- `rev` vient d'un **compteur global monotone** (`server_rev`), incrémenté à chaque mutation de morceau ou de playlist, suppressions comprises. On n'utilise pas d'horodatage, pour ne pas dépendre des horloges.
- Une suppression est un **soft delete** : on remplit `deleted_at` et on incrémente `rev`. Ces « pierres tombales » sont transmises par la synchro.
- **Purge différée** : un job quotidien supprime définitivement les morceaux supprimés depuis plus de 30 jours, ainsi que leurs objets S3. Pendant ces 30 jours, une opération venue du téléphone peut « ressusciter » un morceau (le téléphone gagne).

### S3

- `audio/{videoId}.{ext}` et `thumbs/{videoId}.jpg`.
- `backups/freetube-YYYYMMDD.db`.
- Bucket privé, blocage de l'accès public, versioning activé, chiffrement SSE-S3.
- Règles de cycle de vie :
  - versions non courantes expirées après 30 jours ;
  - `backups/` expirés après 30 jours ;
  - uploads multipart incomplets annulés après 7 jours.
- **Pas de CORS** : le téléphone n'est pas un navigateur, et le web ne lit l'audio et les images que via `<audio>`/`<img>` avec une redirection.

---

## 7. API (FastAPI, préfixe `/api`)

Le **contrat OpenAPI généré par FastAPI fait référence** :
- `server/openapi.json` est exporté par un script et commité ;
- un test échoue si le fichier commité n'est plus à jour ;
- le web génère ses types avec `openapi-typescript` ;
- Android écrit ses DTO à la main en kotlinx.serialization, conformes à ce contrat.

### Authentification

- **Web** :
  - `POST /auth/login` avec `{password}`. Le mot de passe est comparé au hash argon2 de la variable `FREETUBE_PASSWORD_HASH`. Fournis un script CLI qui génère ce hash.
  - La session est stockée côté serveur : un ID aléatoire de 32 octets, enregistré haché en base. Elle est portée par un cookie `httpOnly`, `Secure`, `SameSite=Strict`, valable 30 jours, avec renouvellement glissant.
  - Rate-limit en mémoire sur le login (par exemple 5 tentatives par minute et par IP).
  - Endpoints : `POST /auth/logout`, `GET /auth/me`.
- **Appareil** :
  - en-tête `Authorization: Bearer <token>` ;
  - le token est généré depuis le web, affiché **une seule fois**, et stocké en SHA-256 ;
  - endpoints `GET/POST/DELETE /devices` (session web requise).
- Trois dépendances FastAPI : `require_session`, `require_device`, `require_any`.

### Upload (appareil uniquement)

- `POST /tracks/{video_id}/upload`
  - corps : `{title, artist, duration_s, audio_ext, audio_mime}` ;
  - crée le morceau en `pending_upload`, ou réutilise l'entrée existante ;
  - renvoie `{status, audio_put_url, thumb_put_url}`, avec des URLs pré-signées PUT valables 15 minutes ;
  - si le morceau est déjà `ready`, renvoie `{status: "ready"}` sans URL. L'appel est **idempotent**.
- `POST /tracks/{video_id}/complete`
  - vérifie avec `HeadObject` que les deux objets existent bien sur S3 ;
  - passe le morceau en `ready` et incrémente `rev`.

### Lecture (session ou appareil)

- `GET /tracks/{video_id}/stream` : **302** vers une URL pré-signée GET valable 1 heure.
- `GET /tracks/{video_id}/thumb` : **302** vers une URL pré-signée, avec un en-tête de cache côté client.

### Synchro

- `GET /sync?since={rev}` : renvoie `{tracks: [...], playlists: [... avec leurs items], cursor}`, c'est-à-dire tout ce qui a changé depuis `rev`, pierres tombales comprises.
- `POST /sync/ops` (appareil) : reçoit `{ops: [{op_id, type, payload}]}` et les applique **dans l'ordre**. Types :
  - `upsert_playlist` : `{id, name, source_youtube_id, items: [video_id…]}`, c'est l'état complet ;
  - `delete_playlist` : `{id}` ;
  - `update_track` : `{video_id, title, artist}` ;
  - `delete_track` : `{video_id}`.

  Chaque `op_id` déjà traité est ignoré (idempotence). **Le téléphone gagne** : l'opération est appliquée même si l'élément a été modifié depuis côté serveur, et le conflit est seulement journalisé. Un `update_track` ou un `upsert_playlist` sur un élément supprimé le ressuscite.

### Écritures du web (session)

- `GET /tracks`, `PATCH /tracks/{video_id}`, `DELETE /tracks/{video_id}`.
- `GET /playlists`, `POST /playlists`, `PUT /playlists/{id}` (état complet), `DELETE /playlists/{id}`.
- **Verrouillage optimiste** : chaque écriture du web transmet le `rev` attendu (`If-Match` ou champ `expected_rev`). Si le `rev` en base est différent, le serveur répond **409**, et le web recharge puis prévient l'utilisateur.

---

## 8. App Android

### Stack

Kotlin (dernière version stable), Jetpack Compose + Material 3, Navigation Compose, **Hilt**, **Retrofit + OkHttp + kotlinx.serialization**, **Room**, **DataStore**, **Coil**, **Media3** (ExoPlayer + `MediaSessionService` + OkHttp DataSource), **WorkManager**, **youtubedl-android** avec son module ffmpeg.

- Vérifie les coordonnées Maven **actuellement maintenues** de youtubedl-android avant de choisir.
- `minSdk 26`, `targetSdk` = dernière version stable.
- Dépendances déclarées dans le version catalog (`libs.versions.toml`).
- Découpage de l'APK par ABI (au minimum `arm64-v8a`), parce que youtubedl-android embarque Python et alourdit beaucoup l'APK.

Architecture **MVVM** avec flux de données unidirectionnel : l'UI observe le `StateFlow` du ViewModel, le ViewModel s'appuie sur des repositories, et les repositories sur Room, l'API, le downloader et l'uploader. Une seule Activity.

### Écrans

- **YouTube** : WebView m.youtube.com.
  - JavaScript et DOM storage activés ;
  - le bouton retour navigue dans l'historique de la WebView ;
  - on reste sur les domaines YouTube.
- **Bibliothèque**, **Playlists**, **Détail d'une playlist** (réordonner, ajouter ou retirer un morceau).
- **Téléchargements** : progression, erreurs, bouton pour relancer.
- **Réglages** :
  - URL du serveur (par défaut `https://appartfinder.fr`) ;
  - saisie du token d'appareil ;
  - bouton « Mettre à jour le moteur » ;
  - date de la dernière synchro.
- **Mini-lecteur** persistant et **lecteur plein écran** : file d'attente, aléatoire, répétition.

### Bouton de téléchargement contextuel

m.youtube.com est une SPA : l'URL change sans rechargement de page. Suis-la via `WebViewClient.doUpdateVisitedHistory`, et pas seulement via `onPageFinished`.

| URL | Bouton |
|---|---|
| `watch?v=X` (sans `list`) | « Télécharger ce morceau » |
| `playlist?list=Y` | « Télécharger la playlist » |
| `watch?v=X&list=Y` (Y ne commence pas par `RD`) | Menu « Ce morceau / Toute la playlist » |
| `list=RD…` (Mix générés par YouTube) | « Télécharger ce morceau » uniquement |
| Autre page | Bouton masqué |

Ce classifieur est une **fonction pure**, couverte par des tests unitaires exhaustifs (URLs `youtu.be`, `m.`, `www.`, `music.youtube.com`, paramètres dans un ordre quelconque, `si=`, `t=`…). Le **partage entrant** (`ACTION_SEND`, `text/plain`) passe par le même classifieur.

### Pipeline de téléchargement (WorkManager)

1. **`ResolvePlaylistWorker`** (playlists seulement) :
   - lance yt-dlp avec `--flat-playlist -J` ;
   - crée en local la playlist FreeTube (copie figée, `source_youtube_id` renseigné) ;
   - met dans l'outbox une opération `upsert_playlist` ;
   - planifie un job par morceau absent de la bibliothèque. Les morceaux déjà présents sont seulement ajoutés à la playlist.
2. **`DownloadWorker`** (worker foreground avec une notification de progression, type `dataSync`) :
   - lance yt-dlp avec `-f "bestaudio[ext=m4a]/bestaudio"` et `--no-playlist`, **sans conversion** ;
   - récupère les métadonnées : titre, chaîne (qui devient l'artiste), durée ;
   - télécharge la miniature depuis `https://i.ytimg.com/vi/{id}/maxresdefault.jpg`, avec repli sur `hqdefault.jpg` ;
   - range les fichiers dans `filesDir/audio/` et `filesDir/thumbs/` ;
   - enregistre le morceau dans Room.
3. **`UploadWorker`** (contrainte : réseau disponible) : appelle `upload`, envoie les deux fichiers en PUT vers les URLs pré-signées, puis appelle `complete`.
4. Chaque morceau forme une chaîne Download → Upload, en **travail unique nommé `track-{videoId}`** avec la politique `KEEP`, ce qui garantit l'idempotence. Relances avec backoff exponentiel.
5. **Mise à jour de yt-dlp** : automatique au lancement de l'app (au maximum une fois par 24 h), plus le bouton manuel dans les Réglages.

Permissions : `INTERNET`, `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `FOREGROUND_SERVICE_DATA_SYNC`.

### Room

- **`TrackEntity`** : `videoId`, `title`, `artist`, `durationS`, `audioExt`, `localAudioPath?`, `localThumbPath?`, `uploadState`, `deleted`, `serverRev`.
- **`PlaylistEntity`**, **`PlaylistItemEntity`**.
- **`OutboxOpEntity`** : `opId` (UUID), `type`, `entityId`, `payloadJson`, `createdAt`.
- **`SyncStateEntity`** : `cursor`.

### Synchro côté téléphone

- **Toute modification utilisateur** s'écrit immédiatement dans Room, et une opération est ajoutée à l'outbox **dans la même transaction**.
- **Fusion de l'outbox** : plusieurs `upsert_playlist` sur la même playlist donnent une seule opération (la dernière). Un `delete` remplace les opérations précédentes sur le même élément.
- **`SyncWorker`** : unique, périodique toutes les 15 minutes, et déclenché aussi au lancement de l'app et après chaque modification. Il fait :
  1. **Push** : envoie l'outbox à `POST /sync/ops`, puis supprime les opérations confirmées.
  2. **Pull** : appelle `GET /sync?since=cursor` et applique les changements, **sauf** sur les éléments qui ont encore des opérations en attente dans l'outbox (le téléphone gagne).
  3. Une pierre tombale de morceau supprime aussi le fichier local.
- La logique de fusion du pull et la fusion de l'outbox sont des **fonctions pures, testées unitairement**.

### Lecture (Media3)

- `MediaSessionService` + ExoPlayer, contrôlé depuis l'UI via un `MediaController`.
- Gestion du focus audio et de la coupure quand on débranche le casque (`setHandleAudioBecomingNoisy`).
- Résolution de la source :
  - si `localAudioPath` existe, lecture du fichier local ;
  - sinon, lecture de `{server}/api/tracks/{id}/stream` via une OkHttp DataSource qui ajoute le Bearer. Le header ne doit pas être renvoyé à S3 lors de la redirection : vérifie le comportement d'OkHttp sur une redirection vers un autre hôte.
- Les miniatures sont chargées par Coil, avec un intercepteur OkHttp qui ajoute le Bearer. Le fichier local est prioritaire.

### Stockage du token

DataStore, avec chiffrement via **Tink**, dont la clé est conservée dans l'Android Keystore. `EncryptedSharedPreferences` (security-crypto) est déprécié : ne l'utilise pas.

---

## 9. Web (React)

- React + TypeScript (mode `strict`) + Vite, React Router, TanStack Query.
- Types d'API générés par `openapi-typescript` (`npm run gen:api`).
- `fetch` avec `credentials: "same-origin"`.
- En dev, Vite redirige `/api` vers `localhost:8000`.
- Pages :
  - `/login` ;
  - `/`, la bibliothèque, avec recherche et filtre côté client ;
  - `/playlists` et `/playlists/:id` ;
  - `/settings/devices`, pour générer (affichage unique) ou révoquer un token.
- **Lecteur persistant** dans le layout, en dehors de l'outlet du routeur :
  - un élément `<audio src="/api/tracks/{id}/stream">` ;
  - une file d'attente ;
  - la **Media Session API** (métadonnées, touches multimédia).
- **Gestion du 409** : rechargement des données, puis un message « Modifié ailleurs, données rechargées ».
- Réordonnancement : boutons monter/descendre ou drag & drop HTML natif. Pas de bibliothèque sans justification.
- **CSS simple**, sans framework d'UI. Responsive minimal.
- Le build `web/dist` est copié dans l'image du serveur et servi par FastAPI (`StaticFiles`, avec un repli SPA vers `index.html`).

---

## 10. Infra et déploiement

### Ce qui existe déjà (fait à la main)

| Élément | Valeur |
|---|---|
| Instance EC2 | `freetube`, **t4g.small (ARM64)**, Ubuntu 26.04 LTS, région **eu-west-3 (Paris)** |
| Elastic IP | `15.237.133.3` |
| Disque | 20 Gio gp3 chiffré |
| Métadonnées | IMDSv2 obligatoire, **hop limit 2** (nécessaire pour que les conteneurs Docker obtiennent les identifiants du rôle) |
| Instance | Protection contre la résiliation activée, crédits standard |
| Groupe de sécurité | `launch-wizard-2` : 80/443 ouverts à tous, 22 limité à mon IP |
| Accès | `ssh freetube` depuis mon poste (utilisateur `ubuntu`). Système à jour |
| Domaine | `appartfinder.fr`. Il pointe encore vers l'**ancienne** instance (un site appelé à disparaître) |

### À produire

- **`docs/infra-setup.md`**, un guide console AWS pas à pas que **je** suis :
  1. Bucket S3 : réglages de la section 6, et `infra/s3/lifecycle.json`.
  2. Rôle IAM **`freetube-ec2`** : la politique `infra/iam/freetube-s3-policy.json`, limitée au bucket (`s3:PutObject`, `s3:GetObject`, `s3:DeleteObject` sur `bucket/*`, `s3:ListBucket` sur le bucket), plus la politique gérée `AmazonSSMManagedInstanceCore`. Attachement à l'instance.
  3. Installation de Docker et du plugin Compose sur l'instance.
  4. DNS : enregistrement A `appartfinder.fr` → `15.237.133.3`, avec la marche à suivre selon que le DNS est dans Route 53 ou chez un registrar. **Préviens-moi** : cette étape coupe l'ancien site.
  5. Politique Data Lifecycle Manager pour des snapshots EBS quotidiens, conservés 7 jours.
  6. Vérification que `unattended-upgrades` est actif.
  7. Alerte de budget AWS.
  8. Ménage : suppression de l'ancienne instance appartfinder, puis du rôle `appartfinder-ec2` et de sa politique Bedrock. Seulement une fois FreeTube validé en prod.
- **`infra/docker-compose.yml`** :
  - service `api` : image multi-étapes (étape Node qui build le web, puis étape Python qui installe le serveur et copie `web/dist`), volume `/data` pour SQLite, variables d'environnement via un `.env` **non commité** (fournis un `.env.example`) ;
  - service `caddy` (`caddy:2`) : volumes `caddy_data` et `caddy_config` ;
  - les images doivent fonctionner sur **arm64**.
- **`infra/Caddyfile`** : `appartfinder.fr { reverse_proxy api:8000 }`, plus des en-têtes de sécurité raisonnables.
- **Sauvegarde** : script plus timer systemd quotidien sur l'hôte. Il fait un `sqlite3 .backup` de la base (cohérent même pendant l'écriture), puis `aws s3 cp` vers `backups/`, en s'authentifiant via le rôle d'instance.
- **Purge** : commande `python -m freetube.purge` (section 6), lancée chaque jour par un timer via `docker compose exec`.
- **`docs/deploy.md`** : premier déploiement, mise à jour (`git pull` puis `docker compose up -d --build`), lecture des logs, **restauration de la base depuis S3** (à tester une fois).

---

## 11. Jalons

À la fin de chaque jalon : critères vérifiés, revue faite, récap envoyé, puis **tu t'arrêtes et tu attends ma validation**.

| # | Jalon | Critères de fin |
|---|---|---|
| **M0** | **Spike des risques** : squelette Android (Gradle, Compose, Hilt) et un écran de debug qui télécharge une URL YouTube codée en dur avec youtubedl-android, puis la lit via un `MediaSessionService` | Le morceau se télécharge sur mon téléphone. Il joue **10 minutes écran éteint**. La notification et les contrôles de l'écran de verrouillage fonctionnent. La mise à jour de yt-dlp marche. Tu notes la taille de l'APK. L'écran de debug sera supprimé plus tard |
| **M1** | **Infra** : fichiers de la section 10, guide d'installation, un FastAPI minimal avec `GET /api/health` | `https://appartfinder.fr/api/health` répond en HTTPS (certificat Caddy). Le conteneur liste le bucket via le rôle IAM |
| **M2** | **Backend** : modèle, migrations Alembic, auth, devices, upload/complete, stream/thumb, synchro (ops + pull), écritures web avec 409, purge, export OpenAPI | Tests pytest verts (moto pour S3) : auth, idempotence de l'upload, push/pull de la synchro, téléphone gagnant, 409 côté web, soft delete puis résurrection, purge, OpenAPI à jour. Déployé |
| **M3** | **Cœur Android** : WebView, classifieur, bouton, partage, pipeline Download → Upload, Room, Bibliothèque, Réglages (token), écran Téléchargements | Depuis la WebView et depuis le partage, un morceau arrive dans la bibliothèque locale **et** sur S3 (`ready` côté serveur). Les tests du classifieur passent |
| **M4** | **Lecture** : Media3 branché sur la bibliothèque, mini-lecteur et plein écran, lecture locale ou en streaming | Lecture locale hors-ligne. Streaming d'un morceau sans fichier local. File d'attente, aléatoire, répétition |
| **M5** | **Playlists et synchro** : CRUD des playlists, import d'une playlist YouTube (copie figée, sans doublons), outbox, `SyncWorker`, conflits | Scénario manuel : je modifie la playlist P en mode avion sur le téléphone et je la modifie aussi sur le web, puis je repasse en ligne. **C'est la version du téléphone qui est conservée**, et tout ce qui n'était pas en conflit est préservé. Les tests unitaires de fusion passent |
| **M6** | **Web** : login, bibliothèque, lecteur persistant, playlists, renommage et suppression, page Appareils | Je génère un token sur le web, je le colle dans l'app, et la synchro fonctionne dans les deux sens. Le 409 est géré. Déployé |
| **M7** | **Durcissement** : sauvegardes et restauration testée, snapshots, vérification des mises à jour automatiques, ménage de l'ancienne instance, suppression de l'écran de debug, revue finale complète | `docs/deploy.md` est validé en restaurant réellement une sauvegarde. Plus aucun TODO bloquant |

---

## 12. Déroulé de chaque jalon

1. **Planification avec pstack** : un plan court (fichiers touchés, étapes, risques, tests). Tu me le montres avant d'écrire du code si le jalon touche l'architecture ou la prod.
2. **`/ponytail`**, puis implémentation par petites étapes.
3. **Vérification** :
   - tests automatiques ;
   - lint : `ruff check` et `ruff format --check` pour le serveur, `tsc --noEmit` et `eslint` pour le web, `./gradlew lint test` pour Android ;
   - vérification manuelle décrite pas à pas quand il faut mon téléphone ou la console AWS.
4. **thermo-nuclear-code-quality-review** sur le diff du jalon. Corrige ce qui doit l'être.
5. **Documentation** :
   - une **ADR** courte par décision structurante (contexte, options, décision, conséquences) dans `docs/adr/` ;
   - un **récap pédagogique** ajouté à `docs/LEARNING.md` et envoyé dans le chat. Il contient :
     - ce qui a été construit ;
     - les concepts mis en jeu, expliqués simplement (par exemple : URL pré-signée, idempotence, outbox, verrouillage optimiste, pierres tombales, foreground service) ;
     - les **3 à 5 fichiers à lire** pour comprendre, dans l'ordre ;
     - **2 ou 3 questions de compréhension** pour que je vérifie que j'ai suivi.
6. **Commits** : petits, format *conventional commits*, en anglais. **Jamais de secrets** commités : `.env`, clés, tokens, `.pem`.
7. **Stop** : attente de ma validation avant le jalon suivant.

---

## 13. Garde-fous

- Pas de dépendance ajoutée sans une ligne de justification dans le plan ou une ADR.
- Pas d'abstraction spéculative : pas d'interface à une seule implémentation « au cas où », rien pour les fonctionnalités exclues (section 3).
- Les entrées externes sont validées partout : URLs YouTube, payloads d'API, `videoId` (format `[A-Za-z0-9_-]{11}`).
- Gestion d'erreurs explicite pour le réseau, yt-dlp (vidéo indisponible, géo-bloquée, moteur obsolète) et S3. L'utilisateur voit un message compréhensible et peut relancer.
- Logs serveur structurés, sans jamais y écrire de token, de cookie ou d'URL pré-signée complète.
- Si une hypothèse de ce brief se révèle fausse (version d'une lib, API dépréciée, comportement Android), dis-le-moi au lieu de la contourner en silence.
