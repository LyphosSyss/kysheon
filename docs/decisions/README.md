# Journal des décisions

Une fiche par décision. Les fiches sont liées entre elles par des liens Markdown relatifs, ce qui permet de les
parcourir comme un graphe (GitHub, Obsidian ou tout outil qui suit les liens Markdown).

## Nommage

`NNNN-titre-court.md`, numérotation continue, jamais réutilisée. Identifiant : `KYS-NNNN`.

## Format

```markdown
---
id: KYS-0000
title: Titre court
date: AAAA-MM-JJ
status: proposed | accepted | superseded
tags: [build, link, ble, ui, protocol, …]
supersedes: []        # identifiants remplacés
related: []           # identifiants liés
---

## Contexte
## Décision
## Conséquences
```

Le corps cite les fiches liées avec des liens relatifs, par ex. `[KYS-0002](0002-frames-20-bytes.md)`.

## Index

| Id | Titre | Statut |
|---|---|---|
| [KYS-0001](0001-android-entry-module.md) | Module `:androidApp` séparé (AGP 9) | accepted |
| [KYS-0002](0002-frames-20-bytes.md) | Trames de 20 octets maximum | accepted |
| [KYS-0003](0003-match-module.md) | Module `:match` pour l’orchestration | accepted |
| [KYS-0004](0004-toolchain-versions.md) | Versions de la chaîne d’outils, `compileSdk 37` | accepted |
| [KYS-0005](0005-no-graphics-engine.md) | Pas de moteur graphique tiers, rendu maison | accepted |
| [KYS-0006](0006-mobile-only.md) | Android et iOS uniquement | accepted |
| [KYS-0010](0010-game-core-no-alloc-handoff.md) | Simulation sans allocation et entrée déterministe des projectiles | accepted |
