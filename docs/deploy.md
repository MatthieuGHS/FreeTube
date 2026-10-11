# Déploiement

Prérequis : `docs/infra-setup.md`, étapes 1 à 3 (bucket, rôle IAM attaché, Docker installé).

Le serveur fait tourner deux conteneurs, définis dans `infra/docker-compose.yml` :

- **`caddy`** : seul exposé à Internet (ports 80/443). Il obtient et renouvelle seul le certificat HTTPS,
  puis transmet les requêtes à `api`.
- **`api`** : FastAPI. Il n'est joignable que par Caddy, sur le réseau Docker interne. Sa base SQLite vit
  sur l'hôte dans `/srv/freetube/data`, et elle survit donc aux reconstructions de l'image.
  Il sert aussi le site web : l'image construit `web/` (étape Node), puis copie le résultat à côté du
  serveur. Le contexte de construction est donc la racine du dépôt (`server/Dockerfile`).

## Premier déploiement

```bash
ssh freetube

# 1. Dossier de données, propriété de l'uid 1000 (l'utilisateur du conteneur, voir server/Dockerfile)
sudo mkdir -p /srv/freetube/data
sudo chown 1000:1000 /srv/freetube/data

# 2. Code
git clone https://github.com/MatthieuGHS/FreeTube.git ~/freetube
cd ~/freetube/infra
cp .env.example .env          # .env n'est jamais commité ; vérifie les valeurs

# 3. Construction de l'image, puis le mot de passe du site web
docker compose build
docker compose run --rm -it api python -m freetube.hash_password
#    → colle la ligne FREETUBE_PASSWORD_HASH='…' affichée dans .env (guillemets simples compris)

# 4. Démarrage. Au démarrage, l'API applique les migrations de la base.
docker compose up -d
docker compose ps             # les deux services doivent être « running »
```

5. Installe les tâches quotidiennes (voir [Tâches quotidiennes](#tâches-quotidiennes)).

6. Fais la bascule DNS (`docs/infra-setup.md`, étape 4), puis suis l'obtention du certificat :

   ```bash
   docker compose logs -f caddy   # attendre « certificate obtained successfully » ; Ctrl+C pour quitter
   ```

## Vérifications (critères de M1)

Depuis ton poste :

```bash
curl -i https://appartfinder.fr/api/health
```

Attendu : `HTTP/2 200`, l'en-tête `strict-transport-security`, et le corps `{"status":"ok"}`.

Depuis le serveur : le conteneur doit lister le bucket grâce au rôle IAM, sans aucune clé AWS :

```bash
ssh freetube 'cd ~/freetube/infra && docker compose exec api python -c "
import os, boto3
r = boto3.client(\"s3\").list_objects_v2(Bucket=os.environ[\"FREETUBE_S3_BUCKET\"], MaxKeys=5)
print(\"OK, objets :\", r[\"KeyCount\"])
"'
```

Attendu : `OK, objets : 0` (le bucket est vide pour l'instant).

En cas d'erreur `Unable to locate credentials` : soit le rôle n'est pas attaché (infra-setup, 2c), soit le
hop limit IMDS n'est pas à 2.

## Mettre à jour

```bash
ssh freetube
cd ~/freetube && git pull
cd infra && docker compose up -d --build
docker image prune -f         # supprime les anciennes images, sinon le disque se remplit au fil des déploiements
```

`up -d --build` ne recrée que les conteneurs qui ont changé. La coupure dure quelques secondes.
La construction inclut celle du site web (`npm ci` puis `npm run build`) : la première après M6 est donc plus
longue, ensuite le cache Docker la saute tant que `web/` ne change pas.
Les migrations de la base s'appliquent seules au démarrage de `api`.
Si `infra/systemd/` a changé, réinstalle les unités (section suivante).

### Passage de M1 à M2 (une seule fois)

M2 exige `FREETUBE_PASSWORD_HASH` : sans elle, `api` refuse de démarrer (message clair dans les logs).

```bash
cd ~/freetube && git pull && cd infra
docker compose build
docker compose run --rm -it api python -m freetube.hash_password   # colle la ligne dans .env
docker compose up -d
```

Puis installe les tâches quotidiennes ci-dessous.

## Tâches quotidiennes

Deux timers systemd sur l'hôte. Chacun lance une commande **dans** le conteneur `api` (`docker compose exec`) :

| Timer | Heure (UTC) | Commande | Rôle |
|---|---|---|---|
| `freetube-backup` | 02:30 | `python -m freetube.backup` | Copie à chaud de la base, contrôle d'intégrité, envoi vers `s3://freetube-7k3q9x/backups/freetube-AAAAMMJJ.db` |
| `freetube-purge` | 03:30 | `python -m freetube.purge` | Supprime définitivement les morceaux supprimés depuis plus de 30 jours (ligne + fichiers S3), les sessions expirées et l'ancien journal d'idempotence |

La sauvegarde passe avant la purge : la dernière sauvegarde contient encore les morceaux purgés le jour même.

Installation, ou réinstallation après une modification de `infra/systemd/` :

```bash
sudo cp ~/freetube/infra/systemd/freetube-* /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now freetube-backup.timer freetube-purge.timer
systemctl list-timers 'freetube-*'            # prochaine exécution de chaque timer
```

Test immédiat, sans attendre la nuit :

```bash
sudo systemctl start freetube-backup.service
journalctl -u freetube-backup.service -n 20   # attendu : « backup done key=backups/freetube-… »
```

`Persistent=true` : si le serveur était éteint à l'heure prévue, la tâche tourne au démarrage suivant.

## Lire les logs

```bash
docker compose logs --tail 100 api      # dernières lignes
docker compose logs -f api caddy        # en direct
```

Les logs utilisent le pilote `local` de Docker, avec une rotation automatique : ils ne peuvent pas remplir le disque.

## Restaurer la base depuis S3

À tester une fois pour de vrai (M7). La restauration **ne supprime jamais** la base courante : elle la
renomme en `freetube.db.before-restore`.

1. Choisis la sauvegarde : console S3 → `freetube-7k3q9x` → dossier `backups/`. Note la clé, par exemple
   `backups/freetube-20261010.db`. Les versions plus anciennes du même jour restent accessibles 30 jours
   (onglet **Versions**).
2. Restaure, l'API arrêtée (personne n'écrit pendant le remplacement) :

   ```bash
   ssh freetube
   cd ~/freetube/infra
   docker compose stop api
   docker compose run --rm api python -m freetube.backup --restore backups/freetube-20261010.db
   docker compose start api
   ```

   La commande télécharge la sauvegarde, vérifie son intégrité (elle refuse un fichier corrompu), met
   l'ancienne base de côté, supprime son journal WAL, puis installe la sauvegarde.
3. Vérifie : `curl https://appartfinder.fr/api/health`, puis connecte-toi au site.
4. Le compteur `server_rev` ne recule pas : la restauration garde le plus grand des deux (ancienne base,
   sauvegarde), pour que les curseurs de synchro des clients restent valides.
   Limite : les modifications que le serveur avait reçues **après** la sauvegarde sont perdues côté
   serveur, même si le téléphone les a encore. Le téléphone ne les renverra que s'il les modifie de
   nouveau. Si l'ancienne base est perdue elle aussi (disque mort), il faut remettre à zéro le curseur
   de synchro du téléphone : Réglages, « Tout resynchroniser ». Le prochain pull relit tout depuis le
   début ; les modifications encore en attente sur le téléphone l'emportent toujours.
5. Quand tout va bien, supprime `/srv/freetube/data/freetube.db.before-restore`.

## Créer un token d'appareil

1. Connecte-toi sur https://appartfinder.fr, puis ouvre **Appareils**.
2. Saisis un nom (par exemple `A06`), puis **Créer un token**.
3. Le token n'est affiché qu'une fois : **Copier**, puis colle-le dans l'app FreeTube, onglet **Réglages** →
   **Token d'appareil**. Il disparaît dès que tu quittes la page ; en cas d'oubli, révoque-le et recrée-en un.

Révoquer un appareil (téléphone perdu) : même page, **Révoquer**. Il ne peut plus ni lire ni synchroniser.

Sans le web : `POST /api/devices` avec le cookie de session, comme à l'étape 1 de la section suivante.

## Modifier une playlist en ligne de commande

Sert au test de conflit de M5 : modifier côté serveur une playlist que le téléphone modifie aussi.
Le serveur applique le verrouillage optimiste du web : chaque écriture envoie le `rev` attendu dans
l'en-tête `If-Match` et un `PUT` remplace **tout** l'état de la playlist (nom, morceaux, source).

1. Se connecter (le mot de passe est lu sans être affiché ni gardé dans l'historique ; le cookie reste dans `/tmp/ft.cookies`) :

   ```bash
   read -rs PW
   python3 -c 'import json,sys; print(json.dumps({"password": sys.argv[1]}))' "$PW" > /tmp/ft.login; unset PW
   curl https://appartfinder.fr/api/auth/login -s -c /tmp/ft.cookies -o /dev/null -w '%{http_code}\n' -H 'content-type: application/json' -d @/tmp/ft.login
   rm /tmp/ft.login
   ```

   Le login doit répondre `204`.

2. Lister les playlists et noter l'`id`, le `rev` et les `items` de celle à modifier :

   ```bash
   curl https://appartfinder.fr/api/playlists -s -b /tmp/ft.cookies | python3 -m json.tool
   ```

3. Écrire l'état complet voulu dans `/tmp/ft.playlist` (ici : renommer et retirer le dernier morceau).
   Remplace `ID` par l'`id` noté :

   ```bash
   curl https://appartfinder.fr/api/playlists -s -b /tmp/ft.cookies | python3 -c '
   import json, sys
   p = next(p for p in json.load(sys.stdin) if p["id"] == sys.argv[1])
   print(json.dumps({"name": p["name"] + " (web)", "items": p["items"][:-1], "source_youtube_id": p["source_youtube_id"]}))
   ' ID > /tmp/ft.playlist
   ```

4. Envoyer, avec le `rev` noté à l'étape 2 à la place de `REV` :

   ```bash
   curl -X PUT https://appartfinder.fr/api/playlists/ID -s -b /tmp/ft.cookies -w '\n%{http_code}\n' -H 'content-type: application/json' -H 'If-Match: REV' -d @/tmp/ft.playlist
   rm /tmp/ft.playlist /tmp/ft.cookies
   ```

   `200` renvoie la playlist avec son nouveau `rev`. `409` : la playlist a changé depuis l'étape 2
   (une synchro du téléphone, par exemple), recommence à l'étape 2. `428` : l'en-tête `If-Match` manque.
