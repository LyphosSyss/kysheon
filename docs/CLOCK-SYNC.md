# Synchronisation d’horloge

Chaque téléphone a sa propre horloge monotone, avec une origine arbitraire (souvent le démarrage). Pour qu’un
projectile passe d’un écran à l’autre au bon endroit, les deux moitiés doivent partager une même base de temps :
**celle du host**. Code : [`ClockSync.kt`](../link/src/commonMain/kotlin/epheis/lyphossyss/kysheon/link/sync/ClockSync.kt),
[`SlewedClock.kt`](../link/src/commonMain/kotlin/epheis/lyphossyss/kysheon/link/sync/SlewedClock.kt),
[`Session.kt`](../link/src/commonMain/kotlin/epheis/lyphossyss/kysheon/link/session/Session.kt).

## Mesure

Le guest envoie un PING à `t0` (son horloge). Le host le reçoit à `t1` et répond à `t2` (horloge host).
Le guest reçoit le PONG à `t3`.

```
RTT    = (t3 − t0) − (t2 − t1)
offset = ((t1 − t0) + (t2 − t3)) / 2        // horloge host − horloge guest
```

L’offset est exact si l’aller et le retour durent autant. Sinon l’erreur vaut la moitié de leur différence, donc
**au plus RTT / 2**. C’est pourquoi on garde l’échantillon au **plus petit RTT** : c’est celui dont la borne d’erreur
est la plus serrée.

## Procédure

1. À l’appairage : rafale de 8 PING/PONG, un à la fois (timeout 500 ms chacun). L’offset du meilleur échantillon
   est appliqué d’un coup (le jeu n’a pas commencé).
2. Toutes les 5 s : nouvelle rafale. Le nouvel offset devient une **cible**.
3. L’horloge de jeu rejoint la cible à **5 % de vitesse au plus** : 10 ms d’écart sont rattrapés en 200 ms, l’horloge
   tourne entre 0,95× et 1,05×. Elle ne saute jamais et ne recule jamais (vérifié par `SlewedClockTest`).

La dérive des quartz (quelques dizaines de ppm, soit moins de 0,3 ms toutes les 5 s) est couverte par le recalcul.
La sonde de latence l’affiche en ppm.

## Garanties testées (temps virtuel, `MemoryLink`)

| Test | Conditions | Résultat attendu |
|---|---|---|
| `ClockSyncTest.burstOfEightUnderJitterBoundsTheError` | 1 000 rafales, 50 ms + gigue 20 ms | erreur ≤ RTT/2 et ≤ 10 ms |
| `SessionTest.guestGameClockConvergesOnTheHostClock` | 50 ms + 20 ms | horloge guest à ± 10 ms de l’horloge host |
| `SessionTest.bothHalvesStartTogether` | 50 ms + 20 ms | les deux moitiés démarrent à ± 12 ms |
| `LatencyProbeTest.measuresClockDrift` | dérive de 50 ppm | mesurée à ± 2 ppm |

## Usage dans le jeu

- START porte l’instant de départ sur l’horloge host : les deux moitiés démarrent ensemble.
- HANDOFF porte l’instant simulé de sortie. La moitié qui reçoit fait entrer le projectile sur la frontière, puis
  l’avance de `temps simulé local − instant de sortie` (borné à 200 ms). Il apparaît ainsi là où il serait s’il
  n’avait jamais quitté l’écran.
