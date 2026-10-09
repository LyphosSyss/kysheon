# Flux D — BLE iOS

**Périmètre** : `link/src/iosMain`. Écrit depuis la documentation officielle CoreBluetooth uniquement.

- Guest d’abord (repli prévu si le rôle peripheral iOS est instable) : `CBCentralManager`, scan sur l’UUID de service,
  écriture sans réponse avec `canSendWriteWithoutResponse` / `peripheralIsReadyToSendWriteWithoutResponse`.
- Host : `CBPeripheralManager`, `updateValue` → si `false`, attendre `peripheralManagerIsReadyToUpdateSubscribers`.
  Aucune callback de déconnexion côté peripheral : seul le heartbeat détecte la perte.
- États de l’adaptateur (éteint, non autorisé, non supporté) → `LinkState.Lost`.
- Délégués : sous-classes `NSObject`, `@ObjCSignatureOverride` pour les surcharges en conflit.
- Intervalle de connexion non réglable : le mesurer (overlay) et le documenter.
