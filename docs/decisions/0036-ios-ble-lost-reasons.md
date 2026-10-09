---
id: KYS-0036
title: Correspondance des erreurs CoreBluetooth vers LostReason
date: 2026-10-09
status: accepted
tags: [link, ble, ios]
supersedes: []
related: [KYS-0031, KYS-0033]
---

## Contexte

`LostReason` est un enum fermé, partagé avec Android et traduit par l’UI. Plusieurs situations CoreBluetooth n’ont
pas d’équivalent direct.

## Décision

| Situation CoreBluetooth | `LinkState` |
|---|---|
| `poweredOff`, à tout moment | `Lost(BluetoothOff)` et libération des ressources |
| `unauthorized` | `Lost(PermissionDenied)` |
| `unsupported` | `Lost(Unsupported)` |
| Adaptateur pas `poweredOn` après `adapterTimeout` (hors demande de permission affichée) | `Lost(BluetoothOff)` |
| `resetting` avant connexion / une fois connecté | `Lost(ConnectionFailed)` / `Lost(PeerDisconnected)` |
| Aucun hôte trouvé dans `scanTimeout` | `Lost(PeerNotFound)` |
| `didFailToConnectPeripheral`, délai de connexion, service ou caractéristiques absents, erreur de découverte ou d’abonnement | `Lost(ConnectionFailed, code)` |
| `didDisconnectPeripheral` une fois connecté | `Lost(PeerDisconnected, code)` |
| Hôte : échec de `addService` ou de `startAdvertising` | `Lost(ConnectionFailed, code)` |
| Service invalidé (`didModifyServices`) / désabonnement du central | `Lost(PeerDisconnected)` |

`code` est le `NSError.code` brut (domaine `CBErrorDomain` ou `CBATTErrorDomain`), aussi copié dans
`LinkDiagnostics.lastPlatformCode`. Une erreur sur une notification isolée ne ferme pas le lien : elle est seulement
enregistrée dans `lastPlatformCode`.

## Conséquences

- Un échec d’annonce s’affiche comme un échec de connexion : acceptable en V1, la cause exacte reste dans l’overlay.
- `resetting` est rare ; le traiter comme une perte évite un état incohérent après la réinitialisation du service
  Bluetooth système.
