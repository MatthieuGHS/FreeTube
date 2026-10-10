# 0001 — Extraction YouTube sur le téléphone avec youtubedl-android

- Statut : accepté (M0, 2026-10-10)
- Décisions liées : D2 (téléchargement sur le téléphone), D10 (pas de réencodage)

## Contexte

D2 impose que le téléchargement YouTube se fasse sur le téléphone. Depuis yt-dlp 2025.11, YouTube oblige
yt-dlp à exécuter du JavaScript (résolution des signatures) via un moteur JS externe. Un téléphone Android
n'a ni Python ni moteur JS : il faut les embarquer dans l'APK.

## Options

1. **youtubedl-android** (`io.github.junkfood02.youtubedl-android`, 0.18.1) : embarque Python 3.12, yt-dlp,
   ffmpeg et, depuis 0.18.1, **QuickJS**, branché automatiquement (`--js-runtimes quickjs:…`).
   Utilisé par Seal et YTDLnis.
2. **NewPipeExtractor** : extracteur Java pur, sans Python. Léger, mais il réimplémente YouTube à part :
   corrections plus lentes que yt-dlp, et pas de mise à jour à chaud sans republier l'app.
3. Construire soi-même Python + yt-dlp pour Android : hors de proportion pour un projet perso.

## Décision

youtubedl-android 0.18.1, modules `library` et `ffmpeg`, APK découpé par ABI (arm64-v8a seulement).
`jniLibs.useLegacyPackaging = true`, car les binaires sont exécutés depuis le disque.
yt-dlp est mis à jour depuis GitHub par la librairie (`updateYoutubeDL`).

## Conséquences

- **Le yt-dlp embarqué est périmé dès l'installation** : constaté en M0, il donne un HTTP 403 tant que
  le moteur n'est pas mis à jour (2025.11.12 embarqué → 2026.08.19 après mise à jour). La mise à jour au lancement (une fois par 24 h) est donc obligatoire,
  et un téléchargement doit attendre qu'elle soit faite au premier lancement.
- APK debug arm64 : 67 Mio (ffmpeg 34 Mio, Python 14 Mio, yt-dlp 3 Mio, QuickJS 0,9 Mio).
- La librairie publie peu (dernière version : novembre 2025). Ça reste acceptable tant que yt-dlp se met à
  jour seul. Point de vigilance : quand yt-dlp abandonnera Python 3.12 (fin de vie octobre 2028), il faudra
  une nouvelle version de la librairie.
- Risque résiduel : YouTube peut un jour exiger des « PO tokens ». Non exigés au 2026-10-10.
