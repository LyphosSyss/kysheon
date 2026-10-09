---
id: KYS-0024
title: BLE Android, corrections issues du premier essai sur téléphones
date: 2026-10-09
status: accepted
tags: [ble, android, test]
supersedes: []
related: [KYS-0020, KYS-0021, KYS-0022]
---

## Contexte

Premier essai réel le 2026-10-09 : Nothing Phone (1) (A063, Android 15) en hôte, CMF Phone 1 (A015, Android 16)
en invité. La connexion GATT s’établissait (MTU 517, intervalle 15 ms, PHY 2M), mais l’invité restait bloqué
après l’échange de MTU et l’hôte ignorait le CMF.

## Constats

1. **Récursion dans les callbacks de l’invité.** Dans l’objet `BluetoothGattCallback`, la lambda postée par
   `onMtuChanged` appelait `onMtuChanged(gatt, mtu, status)`. Ce nom désigne, dans cet objet, la méthode surchargée
   elle-même, pas le gestionnaire privé de la session : la lambda se repostait indéfiniment (plus de 200 000
   événements en 10 s, relevés par journalisation de l’origine de chaque événement). Même défaut pour
   `onServicesDiscovered` et `onDescriptorWrite`. Aucun test JVM ne pouvait le voir : il faut une vraie pile GATT.
2. **Connexions parasites côté hôte.** À l’ouverture du serveur GATT, Android signale toutes les liaisons LE du
   téléphone (écouteurs, montre…). Le Nothing a signalé un tel appareil avant même l’arrivée du CMF ; l’hôte le
   prenait pour candidat et refusait le CMF.

## Décision

1. Les gestionnaires privés de l’invité s’appellent `handle*` ; un commentaire explique pourquoi.
2. L’hôte ne réagit plus à une simple connexion : **le pair est le premier central qui active les notifications
   TX** (écriture du CCCD). L’annonce continue jusque-là. La MTU est retenue par appareil et attribuée au pair.
   Le réglage `hostPeerSetupTimeout` disparaît.

## Conséquences

- Essai suivant : connexion complète, sonde fonctionnelle, 0 ping perdu sur 300.
- Les événements de la boucle BLE peuvent être tracés avec `adb shell setprop log.tag.KysheonBle VERBOSE`.
