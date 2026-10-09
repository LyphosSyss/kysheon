---
id: KYS-0005
title: Pas de moteur graphique tiers, rendu maison
date: 2026-10-09
status: accepted
tags: [ui, render]
supersedes: []
related: [KYS-0006]
---

## Contexte

Objectif : un jeu agréable à jouer entre amis, et une vitrine de compétences. Options étudiées : KorGE
(remplace Compose), libGDX (pas KMP, iOS via MobiVM), Godot/Unity (plus de Kotlin).

## Décision

Rendu dans le `Canvas` de Compose Multiplatform, déjà accéléré par le GPU (Skia/Metal sur iOS, rendu matériel
sur Android). Une couche maison `:app/render` porte :
- V1 : interpolation entre pas de simulation, état lu en phase de dessin seulement (pas de recomposition par
  image), aucune allocation par image, mesure du temps d’image dans l’overlay de debug ;
- V1.5 : particules, traînées, shaders via `expect`/`actual` (`RuntimeShader` Android 13+, `RuntimeEffect` iOS),
  Baseline Profile Android.

## Conséquences

- Aucune dépendance ajoutée ; la vitrine Compose Multiplatform est conservée.
- La couche `render/` pourra devenir un module si elle grossit.
