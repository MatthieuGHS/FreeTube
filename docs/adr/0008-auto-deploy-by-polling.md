# 0008 — Déploiement automatique : le serveur interroge GitHub

- Statut : accepté (2026-10-12, demandé pendant M6)
- Décisions liées : D8 ; ADR 0002

## Contexte

Chaque déploiement demandait un `ssh freetube` à la main. L'utilisateur veut qu'un `git push` sur `main`
suffise. Le dépôt GitHub est public, l'instance n'expose que 80/443, et 22 est limité à son IP.

## Décisions

| Sujet | Décision | Alternative écartée |
|---|---|---|
| Déclencheur | Timer systemd toutes les 2 min, `git fetch` du dépôt public | Webhook GitHub : un endpoint public de plus et un secret à gérer. GitHub Actions + SSH : ouvrir 22 à GitHub et stocker une clé privée chez eux |
| Suivi | `.git/freetube-deployed` = dernier commit **déployé** ; `.git/freetube-failed` = commit dont le build a échoué, pas retenté | Comparer à `HEAD` : un build raté passerait pour déployé |
| Échec | `docker compose build` avant `up -d` : un build raté laisse tourner l'ancienne version | `up -d --build` en une commande : même effet, mais moins lisible dans les logs |
| Caddy | `caddy reload` après chaque déploiement | `up -d` seul : ne voit pas un Caddyfile modifié (montage) |
| Script modifié par le merge | Corps dans une fonction appelée en dernière ligne | Script lu au fil de l'eau : le merge pourrait le casser en pleine exécution |
| Disque | `image prune` et `builder prune` (> 7 jours) après chaque déploiement | Rien : 20 Gio remplis en quelques mois |

## Conséquences

- Tout push sur `main` part en prod sans tests côté serveur : les tests se lancent avant de pousser.
- Le compte GitHub devient une clé d'exécution sur l'instance : 2FA indispensable.
- Les unités systemd modifiées ne se réinstallent pas seules (`docs/deploy.md`).
- Délai maximal entre push et prod : environ 2 minutes plus la durée du build.
