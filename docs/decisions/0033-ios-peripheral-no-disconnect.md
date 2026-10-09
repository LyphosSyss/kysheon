---
id: KYS-0033
title: Hôte iOS sans callback de déconnexion
date: 2026-10-09
status: accepted
tags: [link, ble, ios]
supersedes: []
related: [KYS-0031]
---

## Contexte

`CBPeripheralManagerDelegate` n’a aucune callback de connexion ni de déconnexion d’un central. Seuls existent
l’abonnement et le désabonnement à une caractéristique. Un central qui s’éloigne, plante ou coupe son Bluetooth peut
disparaître sans rien signaler à l’hôte iOS. De même, un peripheral ne peut pas couper la connexion d’un central.
Enfin l’intervalle de connexion est choisi par le central et l’OS.

## Décision

- `Connected` à `didSubscribeToCharacteristic` sur TX ; `Lost(PeerDisconnected)` à `didUnsubscribeFromCharacteristic`
  du même central, quand iOS le signale.
- **Le heartbeat de session (silence 3 s → pause, puis 5 s → abandon) est le seul détecteur fiable d’un invité
  disparu.** Aucun délai de détection supplémentaire n’est ajouté dans le lien.
- À `close()`, l’hôte arrête l’annonce et retire ses services : c’est le signal le plus fort possible vers l’invité.
  L’invité iOS écoute `peripheral:didModifyServices:` et passe `Lost(PeerDisconnected)` si notre service est invalidé.
- À l’abonnement, l’hôte appelle `setDesiredConnectionLatency(Low)` : simple indication, sans garantie.
- Les centraux sont comparés par `identifier` (valeur `NSUUID`), jamais exposé hors du paquet.

## Conséquences

- Côté hôte iOS, la perte d’un invité se voit en 3 à 8 s par la session, pas instantanément.
- Le comportement réel (désabonnement signalé ou non à la déconnexion) est à relever en
  [T5](../manual-tests/ios-ble.md).
