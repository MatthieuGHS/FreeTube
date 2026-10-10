# Déploiement

Prérequis : `docs/infra-setup.md`, étapes 1 à 3 (bucket, rôle IAM attaché, Docker installé).

Le serveur fait tourner deux conteneurs, définis dans `infra/docker-compose.yml` :

- **`caddy`** : seul exposé à Internet (ports 80/443). Il obtient et renouvelle seul le certificat HTTPS,
  puis transmet les requêtes à `api`.
- **`api`** : FastAPI. Il n'est joignable que par Caddy, sur le réseau Docker interne. Sa base SQLite vit
  sur l'hôte dans `/srv/freetube/data`, et elle survit donc aux reconstructions de l'image.

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

# 3. Démarrage (la première construction prend quelques minutes)
docker compose up -d --build
docker compose ps             # les deux services doivent être « running »
```

4. Fais la bascule DNS (`docs/infra-setup.md`, étape 4), puis suis l'obtention du certificat :

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

## Lire les logs

```bash
docker compose logs --tail 100 api      # dernières lignes
docker compose logs -f api caddy        # en direct
```

Les logs utilisent le pilote `local` de Docker, avec une rotation automatique : ils ne peuvent pas remplir le disque.

## Restaurer la base depuis S3

Cette section sera écrite en M2, avec la base et le script de sauvegarde, et testée en M7.
