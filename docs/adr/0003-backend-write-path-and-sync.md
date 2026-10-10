# 0003 — Backend : un seul chemin d'écriture, synchro par compteur de révisions

- Statut : accepté (M2, 2026-10-10)
- Décisions liées : D3, D4, D5, D6 ; brief §6, §7, §10
- Méthode : arena pstack (`architect`). 4 concepteurs indépendants (Opus, Fable, Sonnet, Haiku ;
  Fable abandonné faute de crédits), un juge Sonnet. Base retenue : le design Opus, avec greffes.

## Contexte

Deux écrivains modifient les mêmes morceaux et playlists avec des règles opposées. Le web fait du
verrouillage optimiste (409). Le téléphone gagne toujours, peut ressusciter un élément supprimé et
référence des morceaux pas encore uploadés. Un seul processus uvicorn, des endpoints concurrents dans
un threadpool, SQLite. Un pull ne doit jamais rater un changement.

## Décisions

| Sujet | Décision | Alternative écartée |
|---|---|---|
| Règles d'écriture | `library.py` est le **seul** module qui écrit morceaux et playlists. Chaque mutation prend une garde : `IfRev(rev)` (web), `Create()` (POST web), `PHONE_WINS` (synchro) | Deux services web/synchro : les règles (rev, résurrection, items) divergeraient |
| Accès base | `sqlite3` de la bibliothèque standard, SQL écrit à la main, tables `STRICT` + `CHECK`. Alembic ne sert qu'à exécuter les migrations | ORM SQLAlchemy : transactions implicites, `BEGIN IMMEDIATE` difficile à obtenir, deux descriptions du schéma |
| Transactions | `BEGIN IMMEDIATE` pour toute écriture ; `server_rev` incrémenté dans la transaction ; pull = curseur + lignes dans un même instantané | Verrou Python : ne couvre pas les processus purge/sauvegarde, et double le verrou de SQLite |
| Items de playlist | Pas de clé étrangère `video_id → tracks` ; les clients masquent ce qu'ils ne peuvent pas lire ; la purge ne réécrit jamais une playlist | Clé étrangère : rejetterait les playlists poussées avant l'upload de leurs morceaux |
| Morceaux en cours d'upload | Invisibles pour le web et la synchro ; ils apparaissent en passant `ready` (ce qui incrémente leur `rev`) | Les exposer : chaque client devrait filtrer les morceaux illisibles |
| Opération sur un morceau inconnu | Acquittée sans effet ; l'upload portera le titre courant | La rejeter : bloquerait l'outbox du téléphone |
| Upload sur un morceau supprimé | Résurrection en `pending_upload` : le téléphone renvoie les fichiers, `complete` les revérifie | Résurrection directe en `ready` : risque d'un morceau sans audio après une purge partielle |
| Purge | Revérifie la pierre tombale et supprime S3 **dans** la transaction (verrou ~100 ms par morceau, une fois par jour) | S3 hors transaction : course avec une résurrection → morceau `ready` sans audio |
| Conflits | Champ optionnel `cursor` dans `POST /sync/ops` ; conflit = élément avec `rev > cursor` pas encore touché par le lot ; journalisé, jamais bloquant | Colonne `last_pull_rev` par appareil : faux positifs, écriture à chaque pull |
| Réponse du push | `{acked: n}` : le lot est tout ou rien | Résultat par opération : plus de contrat pour aucun besoin |
| Verrou optimiste web | En-tête `If-Match: <rev>` (428 si absent, 409 si différent) | `expected_rev` dans le corps : impossible proprement pour `DELETE` |
| Sauvegarde | Dans le conteneur : `sqlite3.Connection.backup()` + contrôle d'intégrité + boto3, lancée par un timer systemd via `docker compose exec` | CLI AWS sur l'hôte (brief §10) : composant de plus à installer et maintenir |
| Restauration | Garde l'ancienne base de côté et conserve le plus grand `server_rev` | Écraser : perte de données, et curseurs clients invalidés |
| Téléphone hors ligne > 30 jours | Limite acceptée : il peut garder un morceau supprimé ailleurs puis purgé | Drapeau `reset` dans `GET /sync` : branche délicate en plus dans la fusion Android |

## Conséquences

- Ajouter une mutation = une fonction dans `library.py` ; les routes et `apply_ops` ne font que traduire.
- Le contrat §7 est étendu par un champ optionnel (`cursor`) ; la réponse du push est fixée (`{acked}`).
- Risque résiduel accepté : si S3 échoue entre les deux suppressions d'une purge et que le téléphone
  renomme ensuite ce morceau supprimé, le morceau revient `ready` avec un audio manquant (stream 404)
  jusqu'au prochain upload.
- Un PUT pré-signé ne limite pas la taille du fichier : acceptable, seul un appareil authentifié en obtient.
- Les morceaux restés `pending_upload` ne sont jamais nettoyés (cas rare, coût négligeable).
