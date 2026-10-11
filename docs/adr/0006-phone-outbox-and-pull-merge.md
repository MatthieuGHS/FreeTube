# 0006 — Android : une outbox qui se remplace, un pull qui respecte l'attente

- Statut : accepté (M5, 2026-10-11)
- Décisions liées : D5, D6 ; brief §6, §8 (Synchro côté téléphone) ; ADR 0003, 0004
- Méthode : plan pstack validé par l'utilisateur, implémentation déléguée (Opus, worktree isolé), revue
  thermo-nuclear par l'agent principal. Pas d'arena : les structures étaient fixées par le plan.

## Contexte

Le téléphone modifie playlists et morceaux hors ligne et doit gagner les conflits (D5), élément par
élément (D6), sans écraser ce qui n'était pas en conflit. Deux tâches de fond (synchro et
téléchargement) et l'interface écrivent dans la même base Room, à n'importe quel moment.

## Décisions

| Sujet | Décision | Alternative écartée |
|---|---|---|
| Chemin d'écriture | `LibraryWriter` est le seul à écrire une modif utilisateur : Room + op d'outbox dans une transaction, puis demande de synchro après le commit | Écrire depuis chaque ViewModel : l'outbox serait oubliée un jour |
| Fusion de l'outbox | Une règle : une nouvelle op remplace toutes les ops en attente sur le même élément, avec un **nouvel** `opId` | Mettre à jour l'op en place : un push en vol l'acquitterait et effacerait la version plus récente |
| Acquittement | Le push supprime exactement les `opId` envoyés ; lots de 500 (limite serveur) | Vider l'outbox après un 200 : perd les modifs faites pendant le push |
| Pull | Fonction pure ; les éléments avec une op en attente sont ignorés ; ensemble lu **dans** la transaction qui applique, curseur enregistré dans la même | Lire l'attente avant l'appel réseau : une modif faite pendant l'appel serait écrasée |
| Suppression de morceau | La ligne et les fichiers disparaissent tout de suite ; l'op en attente empêche la résurrection. Pas de colonnes `deleted` ni `serverRev` (écart au brief §8, accepté) | Pierre tombale locale : un filtre `WHERE deleted = 0` à ne jamais oublier, pour rien |
| Items invisibles | Un item sans morceau local reste stocké et masqué à l'affichage ; toute édition porte sur la liste complète | Éditer la liste visible : l'upsert complet supprimerait des items hors conflit |
| Import de playlist | UUID tiré au clic, travail unique `playlist-{listId}` KEEP ; doublons retirés en gardant le premier | UUID tiré par le worker : une relance après crash créerait une deuxième playlist |
| Déclenchement | Périodique 15 min + tâche unique `APPEND_OR_REPLACE` au lancement, après chaque modif, après un envoi et après l'enregistrement du token ; un `Mutex` empêche deux synchros simultanées | `KEEP` : un déclenchement pendant une synchro serait perdu jusqu'à 15 min |
| Retéléchargement | Télécharger à nouveau un morceau annule son `delete_track` encore en attente | Laisser l'op : le serveur supprimerait le morceau après son envoi, et le pull effacerait le fichier neuf |
| Restauration serveur | Bouton « Tout resynchroniser » (curseur à zéro) | Effacer les données de l'app : perd la bibliothèque locale |

## Conséquences

- Ajouter un type de modif = une méthode dans `LibraryWriter` et un `OpType`.
- Les bornes du serveur (200/300 caractères, 5000 items) sont appliquées côté téléphone : une op hors
  bornes recevrait un 422 et bloquerait l'outbox pour toujours.
- Une série de modifs hors ligne enchaîne une synchro par modif au retour du réseau (un POST et un GET
  chacune) : accepté pour un seul utilisateur.
- Les requêtes SQL des DAO ne sont pas couvertes par un test JVM (pas de Robolectric) ; la logique
  testée est dans les fonctions pures.
