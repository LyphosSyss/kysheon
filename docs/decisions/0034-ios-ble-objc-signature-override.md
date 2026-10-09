---
id: KYS-0034
title: Délégués CoreBluetooth en Kotlin/Native
date: 2026-10-09
status: accepted
tags: [link, ble, ios, build]
supersedes: []
related: [KYS-0030]
---

## Contexte

Un protocole Objective-C s’implémente en Kotlin/Native par une sous-classe de `NSObject`. Kotlin nomme une méthode
d’après la première partie de son sélecteur ; les autres parties deviennent des noms de paramètres, qui ne comptent
pas dans une signature Kotlin. Ainsi `peripheral:didUpdateValueForCharacteristic:error:` et
`peripheral:didWriteValueForCharacteristic:error:` sont toutes deux `peripheral(CBPeripheral, CBCharacteristic,
NSError?)` : les surcharger est un conflit. CoreBluetooth garde ses délégués en référence faible. Une exception
Kotlin qui remonte dans un appelant Objective-C termine l’app.

## Décision

- `@ObjCSignatureOverride` (Kotlin 2.0+) sur chaque surcharge en conflit : `didConnect`/`didUpdateANCSAuthorization`,
  `didFailToConnect`/`didDisconnect`, `didDiscoverCharacteristics`/`didDiscoverIncludedServices`,
  `didUpdateNotificationState`/`didUpdateValue`/`didWriteValue`/`didDiscoverDescriptors`,
  `didSubscribe`/`didUnsubscribe`. Le compilateur dérive alors le sélecteur des noms de paramètres, qui doivent
  reprendre exactement ceux du protocole.
- La session garde en référence forte son délégué, ses managers et le `CBPeripheral` choisi (sinon la connexion est
  annulée à sa désallocation).
- Chaque callback passe par `guarded { }` : toute exception devient `Lost(ConnectionFailed)` (ou `PeerDisconnected`
  une fois connecté) et la mention « internal error » dans `platformInfo`.
- `NSData` ↔ `ByteArray` : `NSData.create(bytes:length:)` copie depuis un tableau épinglé par `usePinned` ;
  la lecture copie par `memcpy` dans un tableau neuf. Aucune mémoire native à libérer.
- Seule l’ancienne callback `centralManager:didDisconnectPeripheral:error:` est implémentée, pas sa variante iOS 17
  (`timestamp:isReconnecting:`).

## Conséquences

- La correspondance sélecteurs ↔ surcharges a été vérifiée une fois par un test jetable sur simulateur
  (`respondsToSelector:` sur 24 sélecteurs, dont les variantes non implémentées qui doivent répondre `false`) puis
  retirée, le périmètre du flux D excluant `link/src/iosTest`. Les délégués sont `internal` pour permettre de
  réintroduire ce test.
- Un nom de paramètre erroné peut désigner une autre méthode du protocole (par ex. `didWriteValueForCharacteristic`
  au lieu de `didUpdateValueForCharacteristic`) sans erreur de compilation : ne pas renommer ces paramètres.
