---
id: KYS-0006
title: Android et iOS uniquement
date: 2026-10-09
status: accepted
tags: [scope]
supersedes: []
related: [KYS-0005]
---

## Contexte

Une cible web (Kotlin/Wasm) a été envisagée puis écartée par l’auteur.

## Décision

Cibles livrées : Android et iOS. La cible `jvm` des modules non UI sert uniquement aux tests.

## Conséquences

Pas de `wasmJs`, pas de démo navigateur.
