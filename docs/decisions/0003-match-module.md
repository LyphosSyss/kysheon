---
id: KYS-0003
title: Module :match pour l’orchestration
date: 2026-10-09
status: accepted
tags: [architecture, test]
supersedes: []
related: [KYS-0001]
---

## Contexte

Le test d’acceptation « partie complète via `MemoryLink` » exige du code qui relie la simulation (`:game-core`)
et la session réseau (`:link`), hors de toute UI.

## Décision

Module Kotlin pur `:match` (cibles jvm, iOS) contenant `MatchController`. Le test de partie complète y vit et
s’exécute en JVM, sans Compose ni appareil. L’alternative (cible `jvm` dans `:app`) mélangeait UI et orchestration.

## Conséquences

- `:app` ne dépend que de `:match`, qui expose `:game-core` et `:link`.
- Voir [KYS-0001](0001-android-entry-module.md) pour l’autre écart au découpage initial.
