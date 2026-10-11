# Journal d'apprentissage FreeTube

Un récap par jalon : ce qui a été construit, les concepts, les fichiers à lire, des questions pour vérifier.

---

## M0 — Spike des risques (2026-10-10)

### Ce qui a été construit

Un squelette Android (Gradle, Compose, Hilt) et un écran de debug jetable. L'écran télécharge un morceau
YouTube codé en dur avec yt-dlp embarqué, puis le lit en arrière-plan via Media3.

Résultats sur le Samsung A06 (Android 15) :

| Risque | Résultat |
|---|---|
| yt-dlp sur le téléphone, avec les défis JavaScript de YouTube | OK avec QuickJS, **après** mise à jour du moteur (le yt-dlp embarqué donnait un HTTP 403) |
| Lecture de 10 min écran éteint sur un Samsung | OK : morceau entier (13 min 19 s) joué écran éteint, sans réveil, jusqu'à la fin du fichier |
| Notification et contrôles de l'écran de verrouillage | OK |
| Pause quand on débranche le casque | OK |
| Mise à jour de yt-dlp | OK : 2025.11.12 → 2026.08.19 |
| Taille de l'APK debug arm64 | 67 Mio (ffmpeg 34, Python 14, yt-dlp 3, QuickJS 0,9) |
| Fichier obtenu | m4a de 12,4 Mio, sans réencodage |

### Concepts

- **Spike** : un prototype jetable qui sert à lever un risque avant d'investir. On cherche une réponse
  (« est-ce que ça marche sur mon téléphone ? »), pas du code durable. D'où l'écran de debug, qui sera supprimé.
- **Binaire natif embarqué** : Python, ffmpeg et QuickJS sont compilés pour ARM et rangés dans l'APK comme
  des bibliothèques `.so`. Android ne les extrait sur le disque que si on le demande
  (`useLegacyPackaging = true`). Sans ça, impossible de les exécuter.
- **Split par ABI** : l'ABI est le jeu d'instructions du processeur (arm64-v8a sur ton téléphone). Chaque ABI
  a ses propres binaires ; produire un APK par ABI évite d'en embarquer quatre.
- **`MediaSessionService`** : un service Android au premier plan (foreground service) qui possède le lecteur.
  Comme il est déclaré « en cours de lecture », Android ne le tue pas écran éteint. La **MediaSession** décrit
  ce qui joue ; le système en tire seul la notification et les contrôles de l'écran de verrouillage.
- **`MediaController`** : la télécommande de l'UI vers le service. L'UI ne touche jamais le lecteur directement,
  ce qui permet de fermer l'écran sans couper le son.
- **Wake lock** : sans lui, le processeur peut s'endormir écran éteint et le son se couper. ExoPlayer le prend
  pendant la lecture (`setWakeMode`), d'où la permission `WAKE_LOCK`.
- **Focus audio / « becoming noisy »** : une seule app joue à la fois (le focus), et Android prévient quand la
  sortie audio disparaît (casque débranché) pour qu'on se mette en pause au lieu de jouer sur le haut-parleur.
- **Version catalog** (`libs.versions.toml`) : toutes les versions de dépendances au même endroit.

### Fichiers à lire, dans l'ordre

1. `docs/adr/0001-youtube-extraction-on-device.md` : pourquoi youtubedl-android, et ses conséquences.
2. `android/app/build.gradle.kts` : SDK, split ABI, packaging des binaires, dépendances.
3. `android/app/src/main/AndroidManifest.xml` : permissions et déclaration du service de lecture.
4. `android/app/src/main/java/fr/appartfinder/freetube/playback/PlaybackService.kt` : le seul fichier gardé tel quel.
5. `android/app/src/main/java/fr/appartfinder/freetube/debug/DebugScreen.kt` : comment l'UI pilote le service via `MediaController`.

### Questions de compréhension

1. Pourquoi le premier téléchargement a-t-il échoué en 403, et qu'est-ce que ça impose au design de M3 ?
2. Si on supprimait `PlaybackService` et qu'on créait l'ExoPlayer directement dans le ViewModel, que se
   passerait-il quand tu éteins l'écran ou quittes l'app ? Pourquoi ?
3. Pourquoi l'APK de debug fait-il 67 Mio, et qu'est-ce qui le ferait doubler si on retirait le split par ABI ?

---

## M1 — Infra (2026-10-10)

### Ce qui a été construit

- Un serveur FastAPI minimal (`GET /api/health`) dans une image Docker arm64, lancée sous un utilisateur non-root.
- Le déploiement : Docker Compose avec deux conteneurs, `caddy` (HTTPS, seul exposé) et `api`.
- La config AWS (politique IAM, cycle de vie S3) et deux guides : `infra-setup.md` (console) et `deploy.md`.

Vérifié le 2026-10-10 :

| Critère | Résultat |
|---|---|
| `https://appartfinder.fr/api/health` en HTTPS | `HTTP/2 200 {"status":"ok"}`, certificat obtenu par Caddy |
| Le conteneur liste le bucket via le rôle IAM | `OK, objets : 0`, sans aucune clé AWS sur le serveur |
| Mises à jour de sécurité automatiques | `unattended-upgrades` actif |

Piège rencontré : juste après la bascule DNS, ton poste tombait encore sur l'ancien serveur (`server: gunicorn`),
parce que ton résolveur gardait l'ancienne réponse en cache pendant le TTL (1 h). On l'a prouvé avec
`curl --resolve appartfinder.fr:443:15.237.133.3 …`, qui force l'IP sans passer par le DNS.

### Concepts

- **Reverse proxy** : Caddy reçoit tout le trafic Internet et le relaie à `api`. Lui seul gère le TLS. `api`
  n'a aucun port publié : il est injoignable depuis l'extérieur.
- **ACME / Let's Encrypt** : Caddy prouve qu'il contrôle `appartfinder.fr` en répondant sur le port 80 à un
  défi de l'autorité de certification. C'est pourquoi le DNS doit pointer sur lui avant d'obtenir le certificat.
- **TTL DNS** : la durée pendant laquelle une réponse DNS peut rester en cache. Un changement d'IP n'est vu
  par tous qu'après expiration de l'ancien TTL.
- **Rôle d'instance + IMDSv2** : l'instance obtient des identifiants AWS temporaires auprès d'un service local
  (`169.254.169.254`). boto3 les trouve tout seul. Le *hop limit* à 2 autorise un saut réseau de plus, celui
  du conteneur Docker.
- **Moindre privilège** : la politique `freetube-s3` n'autorise que 4 actions sur un seul bucket.
- **HSTS** : l'en-tête demande au navigateur de n'utiliser que HTTPS pour ce domaine pendant un an.
- **Couches Docker et cache** : le Dockerfile copie `pyproject.toml` et `uv.lock` avant le code. Ainsi, la
  couche des dépendances est réutilisée tant qu'elles ne changent pas.

### Fichiers à lire, dans l'ordre

1. `infra/docker-compose.yml` : les deux services, ce qui est exposé et ce qui ne l'est pas.
2. `infra/Caddyfile` : HTTPS automatique et en-têtes de sécurité.
3. `server/Dockerfile` : construction de l'image, utilisateur non-root, un seul worker.
4. `infra/iam/freetube-s3-policy.json` et `infra/s3/lifecycle.json`.
5. `docs/adr/0002-single-host-docker-compose-deployment.md` : les choix et les alternatives écartées.

### Questions de compréhension

1. Pourquoi `curl https://appartfinder.fr/api/health` a-t-il d'abord renvoyé une page 404 de gunicorn,
   alors que le serveur était correctement déployé ?
2. Le conteneur `api` n'a aucune clé AWS. D'où viennent les identifiants que boto3 utilise, et que se
   passerait-il si le hop limit IMDS était à 1 ?
3. Pourquoi le service `api` ne publie-t-il aucun port, et quel risque créerait `ports: ["8000:8000"]`
   avec l'option `--forwarded-allow-ips *` ?

---

## M2 — Backend (2026-10-10)

### Ce qui a été construit

Tout le serveur du brief §7 :
- authentification web (mot de passe argon2, session en cookie) et authentification des appareils (token) ;
- upload et lecture par URLs pré-signées S3 ;
- synchro dans les deux sens (push des opérations du téléphone, pull des changements) ;
- écritures web avec verrouillage optimiste ;
- purge, sauvegarde et restauration, avec deux timers systemd ;
- contrat OpenAPI exporté et testé.

Le design a été choisi par une arena pstack : 3 designs indépendants (Opus, Sonnet, Haiku ; Fable a
abandonné), un juge, puis une synthèse. Le résultat est documenté dans l'ADR 0003.

Vérifié le 2026-10-10 :

| Critère | Résultat |
|---|---|
| Tests pytest (S3 simulé par moto) | 54 tests verts : auth, upload idempotent, synchro, téléphone gagnant, 409/428, résurrection, purge, sauvegarde/restauration, concurrence, logs sans secrets, OpenAPI à jour |
| Déployé | Migration au démarrage, `/api/health` OK, mauvais mot de passe 401, bon mot de passe 204 |
| Sauvegarde réelle | `backups/freetube-20261010.db` envoyé sur S3 par le timer systemd, via le rôle IAM |

### Concepts

- **URL pré-signée** : une URL S3 signée par le serveur, valable 15 min (PUT) ou 1 h (GET). Celui qui la
  détient peut faire exactement cette opération sur exactement cet objet. Les fichiers ne passent jamais
  par le serveur (D3).
- **Idempotence** : rejouer une opération ne change rien de plus. Ici : `upload` deux fois donne le même
  état ; un `op_id` déjà traité est ignoré (table `processed_ops`, écrite dans la même transaction).
- **Outbox (côté téléphone, M5)** : la liste des modifications locales à envoyer. Le serveur applique un
  lot en entier ou pas du tout, puis répond `{acked: n}` ; le téléphone peut alors vider son outbox.
- **Compteur de révisions (`server_rev`)** : chaque modification reçoit le numéro suivant, attribué dans
  la transaction qui écrit. Un client demande « tout ce qui a `rev > curseur` » et ne rate jamais rien,
  sans dépendre des horloges.
- **Pierre tombale (soft delete)** : une suppression remplit `deleted_at` au lieu d'effacer la ligne, pour
  que les autres clients l'apprennent par la synchro. La purge efface vraiment après 30 jours.
- **Verrouillage optimiste** : le web envoie le `rev` qu'il a affiché (`If-Match`). Si quelqu'un a modifié
  l'élément entre-temps, le serveur répond 409 au lieu d'écraser.
- **« Le téléphone gagne »** : ses opérations s'appliquent toujours, même sur un élément modifié ou
  supprimé ailleurs. Le conflit est seulement journalisé, grâce au `cursor` qu'il envoie.
- **`BEGIN IMMEDIATE`** : SQLite n'a qu'un écrivain à la fois. Prendre le verrou d'écriture dès le début
  fait patienter les écritures concurrentes au lieu de les faire échouer.
- **Une seule source de vérité pour les règles** : `library.py` est le seul module qui modifie morceaux et
  playlists. La « garde » (`IfRev`, `Create`, `PHONE_WINS`) dit qui gagne.

### Fichiers à lire, dans l'ordre

1. `docs/adr/0003-backend-write-path-and-sync.md` : les décisions et ce qui a été écarté.
2. `server/src/freetube/migrations/versions/0001_initial.py` : le schéma.
3. `server/src/freetube/db.py` : transactions et compteur de révisions.
4. `server/src/freetube/library.py` : toutes les règles d'écriture et la synchro.
5. `server/tests/test_sync.py` : les règles de synchro écrites comme des scénarios.

### Questions de compréhension

1. Le téléphone envoie un lot, le serveur l'applique, mais la réponse se perd. Le téléphone renvoie le
   même lot. Que se passe-t-il, et grâce à quoi ?
2. Pourquoi le serveur incrémente-t-il `server_rev` **dans** la transaction d'écriture, et quel bug
   apparaîtrait si on l'incrémentait juste avant, dans une transaction séparée ?
3. Pourquoi une restauration de sauvegarde ne doit-elle jamais faire reculer `server_rev` ?

---

## M3 — Cœur Android (2026-10-11)

### Ce qui a été construit

L'app Android complète pour télécharger : une WebView YouTube avec un bouton qui suit la page, le partage
depuis l'app YouTube, la chaîne Téléchargement → Envoi en tâches de fond, la base locale Room, les écrans
Bibliothèque, Téléchargements et Réglages (token chiffré). Design choisi par une arena pstack (ADR 0004).

Vérifié le 2026-10-11 sur l'A06 : un morceau depuis la WebView et un depuis le partage sont arrivés dans
la bibliothèque du téléphone **et** sur le serveur (`ready`, audio et miniature dans S3) ; 21 tests JVM
verts, dont la table complète du classifieur. Trois bugs attrapés en route par les tests et lint :
message de blocage géographique non reconnu, appel qui exigeait Android 13, sauvegarde Android des données ;
et un crash au démarrage (icônes système incompatibles avec Compose sur ce Samsung).

### Concepts

- **WorkManager** : exécute des tâches qui survivent à la fermeture de l'app et au redémarrage. Ici, une
  chaîne unique par morceau (`track-{id}`) : téléchargement puis envoi, avec réessais espacés (backoff).
- **Travail unique + KEEP** : redemander le même morceau pendant qu'il est en cours ne fait rien ; après un
  échec, cela relance une chaîne neuve. « Relancer » est donc le même appel que le premier téléchargement.
- **Faits contre tâches** : Room ne note que ce qui est vrai et durable (le fichier est là, il est envoyé).
  Ce qui est en train de se passer appartient à WorkManager. L'écran combine les deux au moment de
  l'affichage : rien ne peut rester « bloqué » après que Samsung a tué l'app.
- **Service de premier plan (foreground service)** : le téléchargement affiche une notification, ce qui
  dit à Android que l'utilisateur sait qu'une tâche tourne et qu'il ne faut pas la tuer.
- **Exclusion mutuelle (`Mutex`)** : un seul accès à la fois au moteur yt-dlp, pour qu'une mise à jour ne
  remplace jamais ses fichiers pendant qu'un téléchargement les utilise.
- **Chiffrement avec clé matérielle** : Tink chiffre le token ; sa clé est elle-même protégée par le
  Keystore d'Android, qui ne la laisse jamais sortir.
- **Test de contrat** : un test compare les classes Kotlin au fichier `openapi.json` du serveur ; si l'un
  change sans l'autre, le build échoue.

### Fichiers à lire, dans l'ordre

1. `docs/adr/0004-android-pipeline-room-facts-workmanager-jobs.md`
2. `android/app/src/main/java/fr/appartfinder/freetube/youtube/YoutubeUrl.kt` (et son test)
3. `android/app/src/main/java/fr/appartfinder/freetube/download/TrackPipeline.kt`
4. `android/app/src/main/java/fr/appartfinder/freetube/download/DownloadWorker.kt` puis `UploadWorker.kt`
5. `android/app/src/main/java/fr/appartfinder/freetube/download/YtDlp.kt`

### Questions de compréhension

1. Samsung tue l'app pendant un téléchargement. Que voit l'écran Téléchargements, et que se passe-t-il
   ensuite ? Pourquoi aucun état « bloqué » n'est possible ?
2. Pourquoi les envois vers S3 utilisent-ils un client HTTP différent de celui qui parle au serveur ?
3. Tu appuies deux fois de suite sur « Télécharger ce morceau ». Combien de téléchargements ont lieu, et
   grâce à quoi ?
