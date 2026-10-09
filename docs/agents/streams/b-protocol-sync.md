# Flux B — protocole, synchro d’horloge, session

**Périmètre** : `link/src/commonMain` et `link/src/commonTest`, hors `ble/` des plateformes.

- Synchro : rafale de 8 PING/PONG à l’appairage, échantillon au plus petit RTT. offset = ((t1 − t0) + (t2 − t3)) / 2,
  RTT = (t3 − t0) − (t2 − t1). Recalcul toutes les 5 s. L’horloge de jeu rejoint la cible à 5 % de vitesse au plus (monotone, sans saut).
- Session : HELLO (version incompatible → refus propre), READY, START, HEARTBEAT chaque seconde,
  3 s sans trame → pause, 5 s de plus → abandon. `LinkState.Lost` → abandon immédiat.
- Tests sur `MemoryLink` en temps virtuel (`runTest`) : offset sous gigue (erreur ≤ RTTmin / 2), machine d’état, expiration du heartbeat.
