---
id: KYS-0040
title: Effets sonores synthétisés au démarrage, sans asset audio
date: 2026-10-10
status: accepted
tags: [ui, audio, android, ios, licence]
supersedes: []
related: [KYS-0005, KYS-0006]
---

## Contexte

La V1.5 ajoute du « game feel » : neuf effets sonores (tir, entrée d’un projectile, coup porté, coup reçu, tic du
compte à rebours, départ, victoire, défaite, égalité). Direction artistique sacrée et mythologique : cloches de
temple, gongs, lumière divine.

Contraintes :

- Licences : assets CC0 uniquement, chacun listé dans `ASSETS.md`, et aucun son d’un jeu existant (règle 7 d’`AGENTS.md`).
- Taille : le dépôt et l’application restent légers ; pas de moteur audio tiers (même esprit que
  [KYS-0005](0005-no-graphics-engine.md)), aucune nouvelle dépendance.
- Cibles : Android et iOS uniquement ([KYS-0006](0006-mobile-only.md)).
- Latence : le son d’un tir ou d’un coup doit partir dans la même image que l’événement.

## Décision

### Synthèse plutôt qu’assets

Aucun fichier audio n’est livré. `Synth` (`app/src/commonMain/.../audio/Synth.kt`, Kotlin pur, sans API de plateforme)
calcule chaque son au démarrage :

- synthèse additive de partiels inharmoniques (barre libre pour le carillon, cloche avec bourdon, tierce mineure,
  quinte et nominale, gong à spectre dense), chacun avec une enveloppe `(1 − e^(−t/montée)) × e^(−t/déclin)`,
  les partiels aigus s’éteignant plus vite ;
- un jumeau légèrement désaccordé par partiel pour le battement lent d’une vraie cloche ;
- un peu de bruit blanc (xorshift32 à graine fixe) filtré passe-bande pour l’attaque des frappes ;
- oscillateurs récursifs (`y[n+1] = 2 cos(w) y[n] − y[n−1]`) : une multiplication-addition par échantillon et par
  partiel, arrêt sous −100 dB. Seules opérations : `+ − × /`, `sin`, `exp`.

Chaque tampon : mono, 16 bits, 44,1 kHz, montée de 2 ms depuis un zéro exact, descente linéaire (10 % de la durée,
entre 5 et 60 ms) jusqu’à un dernier échantillon exactement nul, crête normalisée par son (0,28 pour l’entrée d’un
projectile, 0,78 pour le coup reçu, jamais plus de 0,8).

| Son | Conception | Durée |
|---|---|---|
| `Fire` | carillon bref et brillant (barre libre, Mi6) | 150 ms |
| `ProjectileEnter` | souffle aérien et deux partiels aigus qui battent, le plus discret | 250 ms |
| `HitDealt` | cloche claire et aiguë (Sol6) | 400 ms |
| `HitTaken` | gong grave (Do3) avec bruit sourd d’impact, le plus fort | 500 ms |
| `Countdown` | tic de wood-block doux | 80 ms |
| `Start` | gong frotté qui enfle (Ré3) avec un halo aigu | 900 ms |
| `Victory` | arpège de cloches ascendant, pentatonique majeure de Do, résolu sur Do6 | 1 400 ms |
| `Defeat` | cloches descendantes en La mineur (Mi5, Do5, La4) | 1 450 ms |
| `Draw` | accord suspendu de deux cloches (Ré5, Sol5) | 1 150 ms |

Total : 6,28 s, soit 276 948 échantillons (environ 554 Ko de PCM 16 bits). Le calcul est déterministe : un test vérifie
que deux rendus sont identiques.

Pourquoi : chaque son est original par construction (aucune question de licence, rien à lister dans `ASSETS.md`,
aucun risque de ressembler à un son connu), l’APK et le framework iOS ne grossissent que de quelques kilo-octets de
code, et le timbre se règle dans le code, revu comme le reste.

### Lecture par plateforme

API commune : `SoundPlayer` (`play(sfx)`, `release()`) et `@Composable rememberSoundPlayer(enabled)`. Avec
`enabled = false`, rien n’est alloué (ni session audio, ni piste) et `SilentSoundPlayer` est rendu. Sinon,
`BankedSoundPlayer` calcule les tampons et crée les ressources sur `Dispatchers.Default`, puis les installe sur le
thread principal, seul à y toucher ensuite : pas de verrou. Un son demandé avant la fin du chargement est ignoré.
`play` n’alloue rien et ne lève jamais d’exception ; tout est libéré par `release()`, appelé par le `onDispose` de la
composition.

- **Android** : une `AudioTrack` en `MODE_STATIC` par (son, voix), `USAGE_GAME` / `CONTENT_TYPE_SONIFICATION`,
  `PERFORMANCE_MODE_LOW_LATENCY` (API 26 = `minSdk`). Deux voix en tourniquet pour les sons de partie (tir, entrée,
  coups), une pour les sons de cérémonie (compte à rebours, départ, résultat) qui ne se chevauchent jamais eux-mêmes :
  13 pistes au lieu de 18, pour ménager les emplacements du mixeur rapide. Rejouer = `stop()`, `reloadStaticData()`,
  `play()`. Une voix refusée par le système est simplement omise ; toute exception est interceptée.
- **iOS** : session `AVAudioSessionCategoryAmbient` (respecte le bouton silencieux, se mélange à la musique des autres
  apps), un `AVAudioEngine` avec 6 `AVAudioPlayerNode` reliés au mixeur principal, un `AVAudioPCMBuffer` float32 mono
  44,1 kHz par son. `play` prend le nœud suivant en tourniquet, `stop()`, `scheduleBuffer`, `play()`. Si une
  interruption (appel, Siri, alarme) ou un changement de sortie a arrêté le moteur, `play` réactive la session et le
  redémarre ; en cas d’échec, le son est ignoré. Les exceptions Objective-C ne sont pas interceptables en Kotlin : les
  appels qui en lèvent (jouer un nœud moteur arrêté) sont évités par des vérifications préalables.

### Latence attendue

- Android : la piste « rapide » n’est accordée que si le format correspond à la sortie native (souvent 48 kHz) ; à
  44,1 kHz le système peut rééchantillonner et retomber sur le mixeur normal, soit de l’ordre de 20 à 50 ms au lieu
  d’une dizaine. Acceptable pour des effets de jeu.
- iOS : `AVAudioEngine` avec la taille de tampon par défaut de la session, de l’ordre de 10 à 25 ms.

## Conséquences

- Aucun fichier audio dans le dépôt ni dans `ASSETS.md`. Modifier un son = modifier `Synth.kt` ; les tests
  (`SynthTest`) vérifient durées, déterminisme, zéros aux bords, crête ≤ 0,8, son non muet, et que l’entrée d’un
  projectile est plus discrète que le coup reçu.
- Rendu des neuf sons : 38 ms mesurées dans le test du simulateur iOS (binaire de test debug, Mac Apple Silicon) ;
  non mesuré sur téléphone.
- Mémoire : environ 0,7 Mo côté Android (13 pistes statiques), 1,1 Mo côté iOS (tampons float32).
- **Non vérifié** (rien d’audible n’a été écouté) : le rendu réel des timbres, le volume relatif et la latence sur
  Nothing Phone (1), CMF Phone 1 et iPhone 16e. Les fondamentales graves du gong (131 et 147 Hz) passent mal sur un
  haut-parleur de téléphone ; ce sont les partiels supérieurs qui portent le son, à confirmer à l’écoute.
- Risques : particularités des `AudioTrack` statiques selon les constructeurs (relecture après la fin du tampon,
  nombre de pistes rapides) ; interruptions et changements de route iOS, couverts par le redémarrage à la demande
  mais non testés sur appareil.
