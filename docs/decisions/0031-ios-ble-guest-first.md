---
id: KYS-0031
title: iOS invité d’abord, hôte en second
date: 2026-10-09
status: accepted
tags: [link, ble, ios]
supersedes: []
related: [KYS-0030, KYS-0033]
---

## Contexte

Le rôle peripheral d’iOS est plus contraint que le rôle central : pas de callback de déconnexion
([KYS-0033](0033-ios-peripheral-no-disconnect.md)), annonce limitée par l’OS, intervalle de connexion décidé par le
central. Il n’y a pas d’iPhone disponible pendant le développement : tout est écrit d’après la documentation
CoreBluetooth et vérifié par compilation seulement.

## Décision

- Le rôle invité (`CentralGuest`) est le chemin robuste et le repli : si le rôle hôte se révèle instable sur
  l’iPhone 16e, l’app peut imposer « l’iPhone rejoint, l’Android héberge » sans toucher au contrat `Link`.
- Invité : attente de `poweredOn`, `scanForPeripheralsWithServices([SERVICE])`, fenêtre de collecte de 1,5 s à partir
  du premier résultat puis connexion au RSSI le plus fort (127 = inconnu, classé dernier), délai global de scan 10 s →
  `Lost(PeerNotFound)`. CoreBluetooth n’abandonne jamais seul une tentative de connexion : `connectTimeout` (10 s)
  couvre connexion, découverte et abonnement.
- Hôte (`PeripheralHost`) : service primaire RX (write without response, writeable) + TX (notify, valeur `nil`,
  readable), annonce de l’UUID de service seul (aucun nom local), `Connected` à l’abonnement à TX, puis arrêt de
  l’annonce et un seul central servi.
- Tous les délais sont des paramètres du constructeur de `IosBleLink`, avec ces valeurs par défaut.

## Conséquences

- Les valeurs par défaut sont des estimations, à ajuster après les mesures de
  [T4/T5](../manual-tests/ios-ble.md).
- Le repli « iPhone toujours invité » reste une décision d’UI, à prendre seulement sur mesure.
