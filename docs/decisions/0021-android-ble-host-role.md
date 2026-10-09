---
id: KYS-0021
title: Hôte Android — connexion validée par le CCCD
date: 2026-10-09
status: proposed
tags: [link, ble, android]
supersedes: []
related: [KYS-0020, KYS-0022]
---

## Contexte

Côté périphérique, `BluetoothGattServerCallback.onConnectionStateChange` signale toute connexion LE au téléphone,
pas seulement celle de l’invité Kysheon (une montre qui se reconnecte, par exemple). Tant que l’invité n’a pas
activé les notifications de TX, l’hôte ne peut rien lui envoyer. Un serveur GATT Android doit déclarer lui-même le
descripteur CCCD 0x2902 et répondre aux lectures et écritures qui le visent.

## Décision

- Service primaire `BleUuids.SERVICE` : RX (`PROPERTY_WRITE_NO_RESPONSE`, `PERMISSION_WRITE`) et TX
  (`PROPERTY_NOTIFY`) avec un CCCD (`PERMISSION_READ | PERMISSION_WRITE`). L’annonce ne démarre que dans
  `onServiceAdded`, une fois la base GATT complète.
- Annonce legacy connectable, `ADVERTISE_MODE_LOW_LATENCY`, puissance moyenne, sans nom ni puissance d’émission :
  drapeaux (3 octets) + UUID 128 bits (18 octets) tiennent dans les 31 octets.
- **Un seul pair** : à la première connexion, l’annonce s’arrête et l’état passe à `Connecting` (appareil
  « candidat »). L’état devient `Connected` quand ce candidat écrit `ENABLE_NOTIFICATION_VALUE` dans le CCCD ; la
  réponse est envoyée avant de passer `Connected`. Si le candidat se déconnecte ou n’écrit pas le CCCD dans les
  10 s, il est oublié et l’annonce reprend. Écart assumé par rapport à « arrêter l’annonce à la connexion » seul :
  sans cette reprise, une connexion parasite bloquerait l’hôte.
- Les écritures d’un appareil qui n’est pas le pair sont refusées (`GATT_WRITE_NOT_PERMITTED` si une réponse est
  attendue). La désactivation des notifications par le pair termine la session (`Lost(PeerDisconnected)`).
- Notifications via `SerialSender` : `notifyCharacteristicChanged(device, tx, false, value)` (API 33+, code
  `BluetoothStatusCodes`) ou l’ancienne signature avec `characteristic.value` en dessous ; attente de
  `onNotificationSent`.
- **Priorité de connexion** : `requestConnectionPriority` n’existe que sur `BluetoothGatt` (client). Le
  périphérique ne peut pas la demander ; c’est l’invité qui demande `CONNECTION_PRIORITY_HIGH`.
- `close()` : arrêt de l’annonce, `cancelConnection(pair)`, `clearServices()`, `close()` du serveur.

## Conséquences

- Pas de `Connected` « à moitié » : dès `Connected`, les trames circulent dans les deux sens.
- Le candidat oublié n’est pas déconnecté (`cancelConnection` pourrait couper une connexion d’une autre
  application) ; s’il s’agissait d’un invité Kysheon bloqué, son propre délai le fera abandonner.
