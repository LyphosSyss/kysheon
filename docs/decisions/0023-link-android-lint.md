---
id: KYS-0023
title: Lint Android du module :link
date: 2026-10-09
status: proposed
tags: [build, link, android]
supersedes: []
related: [KYS-0020]
---

## Contexte

Avec le plugin `com.android.kotlin.multiplatform.library` (AGP 9.3.1), le module `:link` n’expose aucune tâche
`lint*`. Le code BLE appelle des API annotées `@RequiresPermission` ; on veut que lint les contrôle.

## Décision

Le plugin `com.android.lint` (même artefact AGP, aucune dépendance nouvelle) crée les tâches `lint`,
`lintAndroidMain` et `lintJvm`. Il a été appliqué **temporairement** pour vérifier le flux C
(`./gradlew :link:lintAndroidMain` : « No issues found ») puis retiré : l’ajouter durablement fait entrer lint
dans `check` pour tous les flux, ce qui relève de l’intégrateur.

`@SuppressLint("MissingPermission")` est posé sur `HostSession` et `GuestSession` seulement, avec un commentaire :
les permissions sont vérifiées avant tout appel (`requireReady`) et chaque appel s’exécute dans `runGuarded`, qui
convertit une `SecurityException` en `Lost(PermissionDenied)`. Sans cette annotation, lint signale 12 erreurs
`MissingPermission` dans `GuestSession` (vérifié), ce qui prouve que l’analyse couvre bien `androidMain`.

## Conséquences

- Pour relancer la vérification : ajouter `id("com.android.lint")` dans `plugins {}` de `link/build.gradle.kts`,
  lancer `./gradlew :link:lintAndroidMain`, retirer la ligne (ou la garder si l’intégrateur l’accepte).
