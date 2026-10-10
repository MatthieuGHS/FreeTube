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
