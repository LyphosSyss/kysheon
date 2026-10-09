# Flux C — BLE Android

**Périmètre** : `link/src/androidMain`. Écrit depuis la documentation officielle Android uniquement.

- Host : `BluetoothGattServer` + `BluetoothLeAdvertiser` (UUID de service seul dans l’annonce), caractéristique RX
  (write without response, guest → host) et TX (notify + descripteur CCCD 0x2902, host → guest).
- Guest : scan filtré sur l’UUID de service, choix du meilleur RSSI, `connectGatt(TRANSPORT_LE)`,
  `requestConnectionPriority(HIGH)`, `requestMtu(247)`, découverte, activation des notifications.
- Toutes les opérations GATT passent par une file sérialisée (une opération en vol à la fois).
- API 33+ (nouvelles signatures) avec repli sur les anciennes. Permissions 12+ et `ACCESS_FINE_LOCATION` en dessous.
- Bluetooth coupé, permission retirée, erreur 133 : jamais d’exception non gérée, toujours `LinkState.Lost(reason, code)`.
- Non testable par un agent : fournir les étapes manuelles dans `docs/MANUAL-TESTS.md`.
