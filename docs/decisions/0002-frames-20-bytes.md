---
id: KYS-0002
title: Trames de 20 octets maximum
date: 2026-10-09
status: accepted
tags: [protocol, ble]
supersedes: []
related: []
---

## Contexte

Le contrat `Link` impose `taille ≤ MTU − 3` mais n’expose pas la MTU. La MTU ATT par défaut vaut 23
(20 octets utiles) tant que la négociation n’a pas abouti ou si elle échoue.

## Décision

Toute trame du protocole v1 tient en 20 octets (`MAX_FRAME_BYTES`). Pour y parvenir, HANDOFF transporte
des valeurs en virgule fixe : `y` en u16 (0..65535 ↦ 0..1), `vx`/`vy` en i16 (millièmes d’unité par seconde).
Les PONG ne renvoient pas `t0` : l’émetteur le retrouve par le numéro de séquence.
On demande quand même une MTU de 247 côté Android.

## Conséquences

- Le protocole fonctionne même sans négociation de MTU.
- Décodage exact : `decode(encode(m)) == m` octet par octet, ce qui rend les tests stricts.
- L’équipement échangé en V2 ne tiendra pas dans HELLO : il faudra un message dédié (protocole v2).
