---
id: KYS-0010
title: Simulation sans allocation et entrée de projectile déterministe
date: 2026-10-09
status: accepted
tags: [game-core, performance, determinism, protocol]
supersedes: []
related: [KYS-0002]
---

## Contexte

`:game-core` simule une moitié de carte à 60 Hz, au même rythme que le rendu. Le moteur de rendu lit l’état à
chaque image, et la boucle tourne sur un téléphone : une allocation par pas (objets d’événement, listes de
projectiles, vecteurs) crée une pression GC sur Android et des comptages de références sur Kotlin/Native.
Par ailleurs, un projectile qui franchit la frontière arrive en retard sur l’autre appareil (latence BLE) et
doit y être rattrapé sans traverser le combattant, avec le même résultat quelle que soit la cadence d’images.

## Décision

- **Projectiles** : deux pools de capacité fixe (`ProjectilePool`), en tableaux parallèles
  (x, y, x et y précédents, vx, vy, id, actif). Un pool pour nos projectiles, un pour les projectiles entrants
  (les identifiants des deux camps peuvent coïncider). Allocation d’un emplacement : le plus petit libre.
- **Événements** : `EventBuffer`, tableaux parallèles (`kind`, `id`, `yNormalized`, `vx`, `vy`) remplis pendant
  `step()`, lus par index puis vidés par l’appelant (`clear()`). Les événements s’accumulent entre deux vidages,
  donc l’appelant qui exécute plusieurs pas par image ne vide qu’une fois. Si l’appelant ne vide jamais, le
  tampon double de taille plutôt que de perdre un événement (une sortie perdue = un projectile perdu).
  `toList()` produit des `GameEvent` immuables, réservé aux tests et aux journaux.
- **Collisions** : balayage segment contre cercle dans le repère du combattant (mouvement relatif sur le pas,
  rayons additionnés), découpé aux rebonds sur les murs haut et bas. Seuls `+ - * /` et `sqrt` sont utilisés.
- **HANDOFF** : `receiveProjectile` place le projectile sur la frontière, puis l’avance de `elapsed` borné à
  `maxCatchUpMicros` (200 ms) en pas entiers puis un pas partiel pour le reste, avec la même routine que `step()`.
  Le combattant est figé à sa position courante pendant ce rattrapage. Les impacts éventuels sont émis tout de suite.
- **Pas fixe** : `FixedStepLoop` compte en microsecondes entières (aucune dérive), plafonne le rattrapage à
  5 pas par appel et compte les pas abandonnés.

## Conséquences

- `step()` n’alloue pas ; `advanceTo` est `inline`, donc la lambda de pas n’alloue pas non plus.
- La simulation est une fonction de la suite d’appels indexée par pas : tests de déterminisme stricts
  (égalité exacte des états et des événements pour des découpages d’images différents).
- Le rattrapage ignore les mouvements passés du combattant (au plus 200 ms) : un tir peut toucher alors que le
  combattant venait de s’écarter, ou l’inverse. Acceptable pour la V1, à mesurer sur appareil.
- L’instant de sortie n’est connu qu’au pas près (16,7 ms) côté émetteur : erreur de position ≤ 1 pas de vol à
  l’arrivée. Une fraction de pas pourrait être ajoutée à l’événement de sortie si la mesure le justifie.
- Capacités fixes (`maxLocalProjectiles` = 8, `maxIncomingProjectiles` = 16) : `fire()` et `receiveProjectile()`
  renvoient `false` quand le pool est plein ; `:match` doit traiter ce refus.
