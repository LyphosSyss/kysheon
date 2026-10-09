---
id: KYS-0022
title: Invité Android — choix du meilleur RSSI et une seule nouvelle tentative
date: 2026-10-09
status: proposed
tags: [link, ble, android]
supersedes: []
related: [KYS-0020, KYS-0021, KYS-0002]
---

## Contexte

Les deux téléphones sont posés côte à côte, mais d’autres hôtes Kysheon peuvent annoncer à proximité. Les
connexions GATT Android échouent parfois au premier essai (statut 133 `GATT_ERROR`, 147
`GATT_CONNECTION_TIMEOUT`), sans cause exploitable côté application.

## Décision

- Scan `SCAN_MODE_LOW_LATENCY` filtré sur l’UUID de service. Au premier résultat, les résultats sont collectés
  pendant 1,5 s et l’appareil au **RSSI le plus fort** est choisi. Sans aucun résultat en 15 s :
  `Lost(PeerNotFound)`.
- `connectGatt(context, false, callback, TRANSPORT_LE)` (connexion directe). Séquence, chaque étape attendant son
  rappel : connecté → `requestConnectionPriority(HIGH)` (pas de rappel public) → `requestMtu(247)` →
  `discoverServices` → `setCharacteristicNotification(TX)` + écriture du CCCD → `Connected`. Un échec de MTU n’est
  pas bloquant (trames ≤ 20 octets, [KYS-0002](0002-frames-20-bytes.md)).
- Délais par tentative : 10 s jusqu’à la connexion, 10 s pour la mise en place.
- **Une seule nouvelle tentative** : tout échec avant `Connected` (statut d’erreur, délai, étape refusée) ferme le
  `BluetoothGatt`, attend 600 ms et recommence ; au second échec, `Lost(ConnectionFailed, dernier code)`.
  Chaque `BluetoothGatt` est fermé (`disconnect()` puis `close()`), y compris entre les deux tentatives.
- Écritures `WRITE_TYPE_NO_RESPONSE` via `SerialSender` : `writeCharacteristic(c, value, writeType)` (API 33+) ou
  l’ancienne signature, attente de `onCharacteristicWrite`.
- `onCharacteristicChanged(gatt, c, value)` sur API 33+ ; l’ancienne surcharge ne sert qu’en dessous de 33 et lit
  `characteristic.value` immédiatement sur le thread binder.

## Conséquences

- Le choix au RSSI suppose que le bon hôte est le plus proche ; à vérifier sur appareil.
- Android limite le nombre de démarrages de scan (de l’ordre de 5 par 30 s par application) : des cycles
  `join()` / `close()` très rapides peuvent produire un `PeerNotFound` sans cause visible.
- Pas d’`autoConnect` : une connexion perdue n’est pas rétablie automatiquement, la couche session décide.
