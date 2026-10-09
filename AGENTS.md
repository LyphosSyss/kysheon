# AGENTS.md — Kysheon

Guide de travail pour tout agent de code (Claude Code, Codex, Cursor, Copilot…) et pour les humains.
Ce fichier est la source unique : les fichiers propres à un outil (`CLAUDE.md`, etc.) ne font qu’y renvoyer.
Les conventions transverses au portfolio sont dans `../../AGENTS.md` (Epheis).

## Produit en bref

Jeu temps réel sur deux téléphones posés côte à côte formant une seule carte, reliés en Bluetooth Low Energy (GATT), sans internet.
Le jeu sert de preuve technique : KMP / Compose Multiplatform, BLE central **et** peripheral sur Android et iOS,
synchronisation d’horloge, protocole binaire testé, dépôt propre.

Cibles : **Android et iOS uniquement**. La cible `jvm` n’existe que pour exécuter les tests sans appareil.

## Langues

- Documentation, README, décisions : **français**.
- Code, identifiants, messages de commit : **anglais** (Conventional Commits : `feat(link): …`).

## Modules

| Module | Rôle | Peut dépendre de |
|---|---|---|
| `:game-core` | Simulation d’une moitié, pas fixe 60 Hz, collisions. Kotlin pur, zéro dépendance. | rien |
| `:link` | Contrat `Link`, protocole binaire, synchro d’horloge, session, `MemoryLink`, BLE Android/iOS. | coroutines |
| `:match` | Orchestration d’une partie : relie `:game-core` et la session `:link`. Kotlin pur. | `:game-core`, `:link` |
| `:app` | UI Compose Multiplatform (accueil, partie, résultat, debug, `render/`). | `:match` |
| `:androidApp` | Point d’entrée Android minimal (AGP 9 interdit `com.android.application` dans un module KMP). | `:app` |
| `iosApp/` | Projet Xcode, point d’entrée iOS. | framework `KysheonApp` |

`:game-core` ne connaît ni le BLE ni l’UI. Aucune logique dans les points d’entrée.

## Commandes

```bash
./gradlew jvmTest                                   # tests rapides (game-core, link, match)
./gradlew iosSimulatorArm64Test                     # mêmes tests en Kotlin/Native
./gradlew :androidApp:assembleDebug                 # APK de debug
./gradlew :app:linkDebugFrameworkIosArm64           # framework iOS appareil
```

La CI ([`.github/workflows/ci.yml`](.github/workflows/ci.yml)) exécute exactement ces commandes, plus le lint Android
et un build Xcode du simulateur. Avant de proposer un commit, lancer au minimum `./gradlew jvmTest`.

## Règles non négociables

1. **Aucune API de plateforme dans `commonMain`** : `expect`/`actual` ou interface injectée.
2. **Temps** : jamais `Clock.System` dans le code commun. Horloge monotone injectée (`MonotonicClock`), contrôlable en test.
3. **Regex** : jamais `\p{L}` ni `\p{N}` (plantent en Kotlin/Native).
4. **Textes visibles** : toujours `stringResource`, EN + FR. Apostrophe typographique `’` (U+2019) dans les ressources.
   Placeholders positionnels `%1$s`, jamais `%s` nu. Seule exception : textes système iOS (`InfoPlist.strings`).
5. **Icônes** : aucune emoji dans les Composables.
6. **Dépendances** : vérifier la compatibilité KMP (Android + iOS), justifier dans le README. Zéro dépendance pour le protocole et `:game-core`.
7. **Licences** : code MIT ; assets CC0 uniquement, chacun listé dans `ASSETS.md` (source + licence).
   Aucun nom, asset, son ou visuel d’un jeu existant ni d’une licence tierce.
8. **Secrets** : aucune clé, aucun token, aucun keystore dans le dépôt. Team ID Apple dans `iosApp/Configuration/Local.xcconfig` (ignoré).
9. **Publication** : rien n’est poussé ni publié sans demande explicite de l’auteur.
10. **Contrat `Link`** (`link/src/commonMain/.../Link.kt`) : figé. Toute modification passe par une décision écrite.
11. **Périmètre** : tout ajout hors MVP V1 est refusé tant que la V1 n’est pas livrée.

## Honnêteté

- Ne jamais écrire « fonctionne sur appareil » sans preuve (mesure, capture, retour de l’auteur).
- Ce qui n’est vérifié que par compilation est marqué comme tel dans le README et `docs/MANUAL-TESTS.md`.
- Un test qui échoue est rapporté avec sa sortie. Un contournement est documenté dans `docs/decisions/`.

## Méthode de travail

- **Flux** : un flux = une branche `stream/<lettre>-<nom>` = un worktree = un seul agent écrivain.
  Briefs des flux : [`docs/agents/streams/`](docs/agents/streams/).
- **Commits atomiques**, un par étape cohérente.
- **Définition de terminé** : compile pour Android et iOS, `jvmTest` vert, README à jour.
- **Décisions** : toute décision d’architecture ou tout contournement → une fiche dans [`docs/decisions/`](docs/decisions/)
  (format décrit dans `docs/decisions/README.md`). Les fiches se lient entre elles pour former un graphe.
