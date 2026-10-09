---
id: KYS-0020
title: AndroidBleLink — boucle d’événements série et sessions
date: 2026-10-09
status: proposed
tags: [link, ble, android]
supersedes: []
related: [KYS-0002, KYS-0021, KYS-0022, KYS-0023]
---

## Contexte

Les rappels Bluetooth d’Android arrivent sur des threads binder (GATT client et serveur), sur le thread principal
(scan, annonce, broadcasts) et dans un ordre que l’application ne maîtrise pas. Le contrat `Link` exige qu’aucune
exception ne s’échappe et que tout échec devienne `LinkState.Lost`. La même instance doit pouvoir être hôte, invitée,
puis réutilisée après `close()`. Android n’exécute qu’une opération GATT à la fois par connexion : une écriture
lancée pendant qu’une autre est en vol est refusée.

## Décision

- **Une boucle d’événements** : chaque rappel est transformé en lambda postée (`trySend`) dans un canal illimité,
  consommé par une seule coroutine sur `dispatcher.limitedParallelism(1)`. Tout l’état de session n’est lu et écrit
  que là, sans verrou. Les fonctions publiques `host()`, `join()`, `close()` basculent sur ce même dispatcher.
  `send()` ne change pas de thread : il lit une référence `@Volatile` et met en file.
- **Sessions** : chaque `host()` / `join()` crée un objet `HostSession` ou `GuestSession` avec ses propres
  rappels, son `SerialSender`, ses minuteries et son récepteur d’état de l’adaptateur. Un événement n’est exécuté
  que si sa session est encore la session active : les rappels tardifs d’une session close sont ignorés, ce qui rend
  l’instance réutilisable.
- **Échecs** : chaque événement s’exécute dans `runGuarded`. `LinkFailure(raison, code)` (levée volontairement),
  `SecurityException` (→ `PermissionDenied`) et toute autre exception (→ `ConnectionFailed`) terminent la session
  par `Lost`. Si l’adaptateur n’est plus `STATE_ON` au moment de l’échec, la raison devient `BluetoothOff`.
  La libération (`releaseRadio`) ignore les exceptions de chaque appel (`stopScan` lève `IllegalStateException`
  quand le Bluetooth est coupé).
- **File d’écriture commune** (`SerialSender`, `commonMain`) : une écriture plateforme à la fois, attente du rappel
  de confirmation (`onCharacteristicWrite`, `onNotificationSent`) ou d’un délai (1 s), puis la suivante. Les
  confirmations n’ont pas d’identifiant : une confirmation reçue avant le début d’une écriture est jetée. Capacité
  bornée (256 trames) pour qu’une pile bloquée ne fasse pas grossir la file sans fin. Réutilisable côté iOS.
- **Bluetooth coupé** : `BroadcastReceiver` sur `BluetoothAdapter.ACTION_STATE_CHANGED`, enregistré à chaque
  session et désenregistré à sa fin. Sur Android 13+, il est enregistré avec `RECEIVER_EXPORTED` : la documentation
  précise que les broadcasts d’applications privilégiées comme le Bluetooth, qui ne tournent pas sous l’UID système,
  ne parviennent pas à un récepteur `RECEIVER_NOT_EXPORTED`. L’action étant protégée, aucune application tierce ne
  peut la forger.
- **Annulation** : si l’appelant de `host()` / `join()` est annulé pendant l’attente, la session est libérée
  (état `Idle`). Si le `scope` fourni est annulé sans `close()`, les ressources radio sont libérées quand même.

## Conséquences

- Pas de verrou ni de course sur l’état ; le coût est un saut de dispatcher par rappel (quelques microsecondes).
- Les trames reçues passent aussi par la boucle, ce qui garantit leur ordre et l’absence de trame après `Lost`.
- Le comportement réel (ordre des rappels, délais, codes) n’est vérifié qu’à la compilation : voir
  `docs/MANUAL-TESTS.md`.
