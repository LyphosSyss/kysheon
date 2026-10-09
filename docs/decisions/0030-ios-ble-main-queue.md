---
id: KYS-0030
title: BLE iOS confiné à la file principale
date: 2026-10-09
status: accepted
tags: [link, ble, ios]
supersedes: []
related: [KYS-0031, KYS-0034]
---

## Contexte

CoreBluetooth appelle ses délégués sur la file passée à la création du manager. La session et Compose tournent sur
`Dispatchers.Main`. Partager l’état du lien entre une file CoreBluetooth dédiée et des coroutines imposerait des
verrous, et Kotlin/Native ne protège pas les données partagées entre threads.

## Décision

- Les `CBCentralManager` et `CBPeripheralManager` de `IosBleLink` sont créés avec `dispatch_get_main_queue()`.
- Chaque méthode publique de `IosBleLink` passe par `withContext(Dispatchers.Main.immediate)` ; minuteries et pompe
  d’écriture sont lancées dans le `scope` fourni, sur `Dispatchers.Main`. Tout l’état mutable vit donc sur le thread
  principal, sans verrou.
- Les callbacks transmettent au monde coroutine uniquement par des appels non suspendants : `trySend` (trames reçues,
  signal « prêt à écrire »), `CompletableDeferred.complete` (issue de `host()`/`join()`), `MutableStateFlow`.
- Chaque `host()`/`join()` crée une session neuve (`CentralGuest` ou `PeripheralHost`) avec ses propres managers ;
  `close()` ou un échec la libère (délégués détachés, managers relâchés). Une callback tardive ne peut atteindre
  qu’une session libérée, qui l’ignore. C’est ce qui rend l’instance réutilisable.
- `close()` s’exécute en `NonCancellable` : il fonctionne depuis un `finally` d’une coroutine annulée.
- Si l’appelant de `host()`/`join()` est annulé avant l’issue, la session est libérée et l’état revient à `Idle`
  (pas de scan ni d’annonce orphelins).

## Conséquences

- À 60 trames/s, le coût de la file principale est négligeable ; un travail lourd sur Main retarderait en revanche
  les callbacks BLE (à surveiller avec la sonde de latence).
- Les tests Kotlin/Native ne peuvent pas exercer ce code : l’exécuteur de tests n’a ni Bluetooth ni boucle principale.
  Vérification par compilation et par [les tests manuels](../manual-tests/ios-ble.md).
