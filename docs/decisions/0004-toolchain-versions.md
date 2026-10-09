---
id: KYS-0004
title: Versions de la chaîne d’outils, compileSdk 37
date: 2026-10-09
status: accepted
tags: [build]
supersedes: []
related: [KYS-0001]
---

## Contexte

Versions relevées le 2026-10-09 sur Maven Central, Google Maven et la table de compatibilité officielle KMP
(KGP 2.4.20–2.4.21 testé avec Gradle ≤ 9.7.0 et AGP ≤ 9.3.1). AGP 9.4.1 et Gradle 9.8.1 existent mais sortent
de cette plage.

## Décision

Kotlin 2.4.21, AGP 9.3.1, Gradle 9.7.0, Compose Multiplatform 1.12.1, coroutines 1.11.0, JDK 21.
`compileSdk = 37` : exigé par Compose 1.12.1 et lifecycle 2.11.0 (échec du premier build avec 36).
`targetSdk` reste à 36 tant que les changements de comportement d’Android 17 n’ont pas été vérifiés sur appareil.
`minSdk = 26`.

## Conséquences

- La plateforme Android 37 est installée automatiquement par AGP (licence SDK déjà acceptée sur le poste).
- Xcode local 26.6 alors que la table cite 26.4 : à surveiller.
