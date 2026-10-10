---
id: KYS-0042
title: Écran Réglages et persistance des préférences
date: 2026-10-10
status: accepted
tags: [ui, settings]
supersedes: []
related: [KYS-0040, KYS-0041]
---

## Contexte

L’auteur a demandé un écran de réglages : son (activation, volume), vibrations, affichage du debug. Le cahier des
charges repoussait la persistance à la V2 (SQLDelight), mais pour les **données de jeu** (créatures, œufs, pas).

## Décision

- Écran « Réglages » depuis l’accueil : sons du jeu, volume (curseur avec un son d’aperçu au relâchement),
  vibrations, bouton Debug en partie, overlay de debug ouvert au début d’une partie. Retour système sur Android.
- Préférences mémorisées entre deux lancements par un stockage clé-valeur minimal en `expect`/`actual` :
  `SharedPreferences` sur Android, `NSUserDefaults` sur iOS. Clés préfixées `v1.` pour faire évoluer le format.
  Aucune dépendance ajoutée ; la persistance des données de jeu reste prévue en V2 avec SQLDelight.
- Interrupteur et curseur dessinés avec Compose foundation seulement, exposés aux services d’accessibilité
  (rôle Switch, plage de progression et action « régler »).

## Conséquences

- Vérifié sur le simulateur iOS : affichage, persistance après relancement.
- Non vérifié sur téléphone : volume réel des sons, retour système Android.
