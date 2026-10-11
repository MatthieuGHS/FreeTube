# 0005 — Lecture : la source est résolue par le service, un seul point d'authentification

- Statut : accepté (M4, 2026-10-11)
- Décisions liées : D3, brief §8 (Lecture, Media3)
- Méthode : démarche pstack `architect` suivie sans arena (périmètre petit, forme imposée par Media3).

## Contexte

Lire un morceau depuis le fichier local s'il existe, sinon depuis le serveur, en arrière-plan, avec
notification et écran de verrouillage. Un `MediaController` n'envoie au `MediaSessionService` que
l'identifiant d'un morceau : l'URI ne traverse pas la frontière entre l'interface et le service.
Le streaming passe par `/stream`, qui redirige (302) vers une URL S3 pré-signée.

## Décisions

| Sujet | Décision | Alternative écartée |
|---|---|---|
| Résolution de la source | `PlaybackService` (`onAddMediaItems`) lit Room et choisit : fichier local, sinon `{serveur}/api/tracks/{id}/stream`. Règle pure `playbackSource()` testée | Construire l'URI dans l'interface : elle n'arrive pas jusqu'au service |
| Authentification | Un seul `AuthInterceptor` qui n'ajoute le Bearer que pour l'hôte du serveur ; client OkHttp `@Authed` partagé par Retrofit, ExoPlayer (`OkHttpDataSource`) et Coil | Un ajout du token par usage (Retrofit, lecteur, images) : trois règles à garder alignées |
| Redirection vers S3 | Le token ne suit pas : intercepteur limité à l'hôte + OkHttp retire l'authentification sur un changement d'hôte. Prouvé par un test à deux serveurs | — |
| URL S3 expirée | Aucun traitement : ExoPlayer rappelle `/stream` à chaque ouverture et reçoit une URL neuve | Rafraîchir l'URL à la main |
| Côté interface | `PlayerConnection`, singleton : l'unique `MediaController`, un `StateFlow<PlayerState>` et les commandes | Un ViewModel partagé au niveau de l'Activity |
| File, aléatoire, répétition | Ceux d'ExoPlayer, donc aussi pilotés depuis la notification | Logique de file maison |

## Conséquences

- Un morceau sans fichier local (M5 : morceaux venus du web) se lit sans aucun code en plus.
- Les API Media3 utilisées pour brancher OkHttp sont marquées « instables » : une mise à jour de Media3
  peut demander de petites adaptations dans `PlaybackService`.
- `PlayerConnection` reste connecté tant que le processus vit (raccourci assumé, noté dans le code).
