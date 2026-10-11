# 0007 — Web : SPA servie par FastAPI, politique d'erreurs centralisée, CSP stricte

- Statut : accepté (M6, 2026-10-12)
- Décisions liées : D3, D7, D8 ; brief §9, §10, §13 ; ADR 0003, 0006
- Méthode : plan pstack validé par l'utilisateur, implémentation déléguée (Opus, worktree isolé), revue
  thermo-nuclear par l'agent principal.

## Contexte

Le web doit lire et modifier la bibliothèque depuis un PC, avec un lecteur qui survit à la navigation,
sur la même origine que l'API (D7), derrière Caddy (D8). Les titres affichés viennent de YouTube, donc
de n'importe qui, et la page Appareils crée des tokens d'accès permanents.

## Décisions

| Sujet | Décision | Alternative écartée |
|---|---|---|
| Service du build | Route catch-all FastAPI déclarée après l'API ; `StaticFiles.get_response` résout le chemin, repli sur `index.html` ; `/api/*` reste un 404 JSON ; `assets/` immuable, `index.html` en `no-cache` | `app.mount("/")` : avale les 404 de l'API et n'a pas de repli SPA |
| Image | Étape Node en `--platform=$BUILDPLATFORM`, contexte = racine du dépôt, `.dockerignore` en liste blanche | Builder le web sous émulation arm64 : lent, pour un JS identique |
| Appels API | `fetch` + types `openapi-typescript`, une `ApiError` typée | `openapi-fetch` : une dépendance pour une dizaine d'appels |
| Erreurs | 401 → `/login` et 409/404 → rechargement + message, une seule fois dans le `QueryClient` | Un `if` par page : divergerait |
| Lecteur | Un `<audio>` hors de l'`Outlet`, file en reducer pur, Media Session ; rechargement de `/stream` si l'URL S3 a expiré | Garder l'URL redirigée : la reprise après 1 h de pause échoue |
| Playlists | Items sans morceau lisible masqués mais renvoyés dans chaque `PUT` (même règle qu'Android) | Éditer la liste visible : efface des items en silence |
| Token d'appareil | Uniquement dans l'état du composant, effacé du cache de mutation | Cache de requêtes ou stockage : survivrait à la page |
| CSP | `script-src 'self'`, `connect-src 'self'`, médias et images limités au bucket S3 | Pas de CSP : un échappement raté exécuterait du HTML venu de YouTube |
| Tests web | `node --test` sur les modules purs (Node 26 lit le TS) | Vitest : une dépendance de plus |

## Conséquences

- Un `GET` sur une route `/api` réservée au `POST` répond 404 au lieu de 405.
- TypeScript reste en 5.9 tant que typescript-eslint et openapi-typescript n'acceptent pas la 6+.
- Ajouter une page = une route dans `main.tsx` ; les erreurs réseau sont déjà gérées.
- Toute nouvelle origine (CDN, police externe) doit être ajoutée à la CSP dans `infra/Caddyfile`.
