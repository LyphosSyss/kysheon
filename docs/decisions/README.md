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
| [KYS-0020](0020-android-ble-event-loop.md) | BLE Android : boucle d’événements, sessions, file d’écriture | proposed |
| [KYS-0021](0021-android-ble-host-role.md) | BLE Android : rôle hôte | proposed |
| [KYS-0022](0022-android-ble-guest-role.md) | BLE Android : rôle invité | proposed |
| [KYS-0023](0023-link-android-lint.md) | Lint du module `:link` | proposed |
| [KYS-0024](0024-android-ble-device-findings.md) | BLE Android : corrections du premier essai sur téléphones | accepted |
| [KYS-0030](0030-ios-ble-main-queue.md) | BLE iOS : file principale | accepted |
| [KYS-0031](0031-ios-ble-guest-first.md) | BLE iOS : invité d’abord, repli si l’hôte est instable | accepted |
| [KYS-0032](0032-ios-ble-flow-control.md) | BLE iOS : contrôle de flux des écritures | accepted |
| [KYS-0033](0033-ios-peripheral-no-disconnect.md) | BLE iOS : hôte sans callback de déconnexion | accepted |
| [KYS-0034](0034-ios-ble-objc-signature-override.md) | BLE iOS : interop des délégués | accepted |
| [KYS-0035](0035-ios-bluetooth-availability.md) | BLE iOS : disponibilité du Bluetooth | accepted |
| [KYS-0036](0036-ios-ble-lost-reasons.md) | BLE iOS : correspondance des erreurs | accepted |
| [KYS-0040](0040-procedural-audio.md) | Sons synthétisés dans le code | accepted |
| [KYS-0041](0041-sacred-direction-and-game-feel.md) | Direction « sacrée » et ressenti de jeu (V1.5) | accepted |
| [KYS-0042](0042-settings-persistence.md) | Écran Réglages et persistance des préférences | accepted |
| [KYS-0043](0043-maps-and-obstacles.md) | Maps à obstacles : architecture et budget prévus | proposed |
