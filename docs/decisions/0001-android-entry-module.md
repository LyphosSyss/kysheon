---
id: KYS-0001
title: Module :androidApp séparé (AGP 9)
date: 2026-10-09
status: accepted
tags: [build, android]
supersedes: []
related: [KYS-0003, KYS-0004]
---

## Contexte

Le cahier des charges prévoit trois modules (`:game-core`, `:link`, `:app`). Depuis AGP 9, le plugin
`org.jetbrains.kotlin.multiplatform` n’est plus compatible avec `com.android.application` dans le même module :
les bibliothèques KMP utilisent `com.android.kotlin.multiplatform.library`.

## Décision

`:app` est une bibliothèque KMP qui porte toute l’UI Compose. `:androidApp` est un point d’entrée minimal
(`MainActivity`, manifeste), symétrique de `iosApp/` côté Xcode. Aucune logique n’y vit.

## Conséquences

- Un module de plus, sans logique.
- Voir aussi [KYS-0003](0003-match-module.md) (autre module ajouté) et [KYS-0004](0004-toolchain-versions.md).
