---
id: KYS-0032
title: Contrôle de flux BLE iOS et file d’envoi bornée
date: 2026-10-09
status: accepted
tags: [link, ble, ios, protocol]
supersedes: []
related: [KYS-0002, KYS-0030]
---

## Contexte

En write without response (invité) comme en notification (hôte), iOS a une file d’émission finie. Écrire quand elle
est pleine perd la trame sans erreur (invité) ou fait renvoyer `false` à `updateValue` (hôte). Le contrat `Link`
demande des écritures sérialisées, et `send()` ne doit pas bloquer la boucle de session (qui répond aux PING).

## Décision

- `send()` copie la trame dans une file bornée (`maxQueuedFrames`, 64 par défaut) et rend la main. Une seule pompe
  par session vide la file, une trame à la fois.
- Invité : écriture seulement si `canSendWriteWithoutResponse`, sinon attente de
  `peripheralIsReadyToSendWriteWithoutResponse:`. Hôte : si `updateValue` renvoie `false`, attente de
  `peripheralManagerIsReadyToUpdateSubscribers:` et nouvel essai de la même trame. Le signal « prêt » est un canal
  `CONFLATED` : un signal périmé ne provoque qu’une nouvelle vérification.
- File pleine, trame trop longue pour la charge utile négociée, ou lien non connecté : la trame est jetée et comptée
  dans `framesDropped`. `maxQueueDepth` mesure la pire profondeur observée.
- Charge utile : `maximumWriteValueLength(withoutResponse)` (invité) ou `CBCentral.maximumUpdateValueLength` (hôte),
  relue à chaque écriture car l’échange de MTU peut finir après l’abonnement. Elle est publiée dans
  `LinkDiagnostics.mtu` sous la forme **charge utile + 3** (en-tête ATT), pour rester comparable à la MTU ATT
  d’Android ; iOS n’expose jamais la MTU elle-même.
- Côté hôte, RX ne déclare que « write without response » : chaque requête acceptée est une ATT Write Command, sans
  réponse attendue, et `CBATTRequest` n’expose aucun indicateur contraire. `respondToRequest` n’est donc jamais
  appelé.

## Conséquences

- Les trames du protocole v1 font au plus 20 octets ([KYS-0002](0002-frames-20-bytes.md)) : la limite de charge utile
  ne devrait jamais jeter de trame. Un `framesDropped` non nul en partie signale un pair trop lent ou un lien dégradé.
- Jeter plutôt que suspendre peut perdre un HANDOFF si la file déborde : le choix privilégie une session réactive ;
  à réévaluer si les mesures montrent des débordements.
- L’absence de réponse aux écritures est à confirmer sur appareil ([T5](../manual-tests/ios-ble.md)).
