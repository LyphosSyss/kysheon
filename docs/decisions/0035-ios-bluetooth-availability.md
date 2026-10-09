---
id: KYS-0035
title: Disponibilité Bluetooth iOS pour l’UI
date: 2026-10-09
status: accepted
tags: [link, ble, ios, ui]
supersedes: []
related: [KYS-0030]
---

## Contexte

L’UI doit savoir avant de lancer une partie si le Bluetooth est utilisable, avec quatre valeurs communes à Android
(`BluetoothAvailability` : `Ready`, `Off`, `Unauthorized`, `Unsupported`). Sur iOS, l’état de l’adaptateur n’existe
que via un manager, et créer le premier manager déclenche la demande de permission. iOS ne permet pas à une app
d’allumer le Bluetooth ; le Centre de contrôle ne fait que déconnecter les accessoires.

## Décision

- `IosBluetoothAvailability.start()` crée un `CBCentralManager` dédié avec `CBCentralManagerOptionShowPowerAlertKey =
  false` : l’app explique elle-même un Bluetooth éteint. `start()` est à appeler quand l’utilisateur demande le
  Bluetooth (Héberger / Rejoindre), jamais au lancement, pour que la demande arrive en contexte.
- Avant `start()`, la valeur ne dépend que de `CBManager.authorization` (iOS 13.1+) : autorisé → `Ready`
  (optimiste, alimentation inconnue) ; refusé ou restreint → `Unauthorized` ; **non déterminé → `Unauthorized`**,
  faute de cinquième valeur. `isPermissionDetermined` distingue « demander » (`start()`) de « refusé »
  (`openAppSettings()`).
- Après `start()` : `poweredOn` → `Ready`, `poweredOff` → `Off`, `unauthorized` → `Unauthorized`,
  `unsupported` → `Unsupported` ; `unknown`/`resetting` gardent la valeur issue de l’autorisation.
- `openAppSettings()` ouvre la page de l’app dans Réglages (`UIApplicationOpenSettingsURLString`) : utile pour la
  permission ; pour un Bluetooth éteint, c’est le plus proche qu’iOS autorise.
- Les managers de `IosBleLink` utilisent aussi `ShowPowerAlert = false`. Leur délai d’attente de l’adaptateur est
  suspendu tant que l’autorisation est indéterminée (demande système à l’écran).

## Conséquences

- L’UI ne doit pas proposer « Ouvrir les réglages » sur `Unauthorized` tant que `isPermissionDetermined` est faux.
- Le comportement exact du Centre de contrôle et d’un changement de permission dans Réglages (iOS peut terminer
  l’app) est à relever ([T3, T6](../manual-tests/ios-ble.md)).
