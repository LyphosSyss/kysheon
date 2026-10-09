# Tests manuels

Ce qui ne peut pas être vérifié par un agent ni par les tests automatiques : tout ce qui demande une vraie radio.
Tant qu’une ligne de résultat indique « non mesuré », le comportement correspondant n’est vérifié **qu’à la
compilation**.

## Android ↔ Android (Nothing Phone 1 / CMF Phone 1)

Implémentation testée : `AndroidBleLink` (`link/src/androidMain/.../ble/`), décisions
[KYS-0020](decisions/0020-android-ble-event-loop.md), [KYS-0021](decisions/0021-android-ble-host-role.md),
[KYS-0022](decisions/0022-android-ble-guest-role.md).

### Préparation

1. Installer la même version de debug sur les deux téléphones : `./gradlew :androidApp:installDebug`
   (un téléphone branché à la fois, ou `ANDROID_SERIAL=<numéro>`).
2. Relever la version d’Android de chaque téléphone (Paramètres → À propos) et la noter dans les résultats :
   les chemins API 33+ (`writeCharacteristic(…, value, writeType)`, `notifyCharacteristicChanged(…, value)`,
   `onCharacteristicChanged(…, value)`) ne sont exercés que si le téléphone est en Android 13 ou plus ; un
   téléphone resté en Android 12 exerce les anciennes signatures.
3. Ouvrir un terminal par téléphone : `adb -s <numéro> logcat -s KysheonBle` (journal de l’implémentation).
4. Activer l’overlay de debug de l’application (MTU, trames émises / reçues / perdues, profondeur maximale de file,
   dernier code plateforme, rôle et empreinte courte de l’adresse du pair).
5. Téléphones posés côte à côte, à moins de 10 cm, écrans allumés, Bluetooth activé, mode économie d’énergie
   désactivé (le noter s’il est actif).

Dans ce document, **A** = Nothing Phone 1, **B** = CMF Phone 1.

### 0. Sonde de latence (à faire en premier)

L’APK de mesure ouvre directement l’écran « Sonde de latence » après la connexion (le jeu n’est pas encore branché).
Elle exécute le vrai protocole (HELLO, PING/PONG toutes les 100 ms, HEARTBEAT chaque seconde) et affiche :
RTT min / médian / p95 / max, gigue, décalage d’horloge et sa borne d’erreur, dérive en ppm, pings perdus, MTU,
trames émises / reçues, version du protocole du pair.

1. Installer l’APK sur les deux téléphones (voir « Préparation »).
2. A : « Héberger ». B : « Rejoindre ». Attendre l’écran « Sonde de latence » des deux côtés.
3. Laisser tourner **au moins 30 s** (60 s si possible), téléphones côte à côte, sans y toucher.
4. Faire une capture d’écran **sur les deux téléphones** (l’appareil et le rôle sont affichés en haut).
5. « Terminer » des deux côtés, puis recommencer en inversant les rôles (B héberge, A rejoint).
6. Reporter les valeurs dans le tableau « Latences mesurées » du README (ou envoyer les 4 captures).

Attendu : pings perdus à 0 ou presque ; RTT min de l’ordre de 1 à 2 intervalles de connexion (souvent 15–60 ms) ;
borne d’erreur du décalage ≈ RTT min / 2 ; dérive de quelques dizaines de ppm au plus.

### 1. Permissions

**1.1 Android 12+ (les deux téléphones)**

1. Désinstaller l’application, la réinstaller, la lancer.
2. Toucher « Héberger » (ou « Rejoindre »).
   - Attendu : une seule demande système groupée « Appareils à proximité » (BLUETOOTH_SCAN, BLUETOOTH_CONNECT,
     BLUETOOTH_ADVERTISE). Aucune demande de localisation.
3. Refuser.
   - Attendu : `AndroidBluetooth.availability()` vaut `Unauthorized` ; l’UI l’explique ; si `host()` / `join()` est
     appelé malgré tout, état `Lost(PermissionDenied)`, aucun plantage.
4. Recommencer et accepter.
   - Attendu : `availability()` vaut `Ready` ; l’hébergement ou la recherche démarre.
5. Paramètres → Applications → Kysheon → Autorisations → retirer « Appareils à proximité » pendant une partie.
   - Attendu : Android arrête le processus de l’application (comportement système lors d’un retrait de
     permission). Au relancement : retour au point 2. Côté pair : `Lost(PeerDisconnected)` en quelques secondes.

**1.2 Android 8 à 11 (repli ACCESS_FINE_LOCATION)**

Non testable avec ces deux téléphones (tous deux en Android 12 ou plus). À faire sur un autre appareil si
disponible : la demande doit porter sur la localisation précise ; le scan exige en plus que la localisation système
soit activée (sinon aucun résultat → `Lost(PeerNotFound)`), ce que `availability()` ne vérifie pas.

**1.3 Bluetooth éteint au lancement**

1. Couper le Bluetooth, lancer l’application.
   - Attendu : `availability()` vaut `Off` ; si `host()` / `join()` est appelé : `Lost(BluetoothOff)`.

### 2. Connexion, A hôte, B invité

1. A : « Héberger ».
   - Attendu : état `Advertising` en moins de 1 s ; journal sans erreur.
2. B : « Rejoindre ».
   - Attendu : `Scanning`, puis environ 1,5 s après la première détection `Connecting`, puis `Connected` des deux
     côtés. A passe par `Connecting` (candidat) avant `Connected`.
3. Mesurer le délai entre le toucher de « Rejoindre » et `Connected` sur B (chronomètre ou horodatage logcat).
4. Lire la MTU dans l’overlay des deux côtés.
   - Attendu : 247 ou une valeur négociée plus petite ; 23 si l’échange a échoué (la partie doit fonctionner quand
     même, trames ≤ 20 octets).
5. Jouer 2 minutes.
   - Attendu : compteurs « émises » et « reçues » qui progressent des deux côtés ; « perdues » à 0 ou presque ;
     profondeur maximale de file faible (≤ 3) ; aucune trame reçue hors ordre (la couche protocole le signalerait).

### 3. Connexion, B hôte, A invité

Reprendre la section 2 en inversant les rôles.

### 4. Bluetooth coupé en pleine partie

1. Connexion établie (A hôte, B invité). Sur **A**, couper le Bluetooth depuis le volet rapide.
   - Attendu sur A : `Lost(BluetoothOff)` immédiatement, aucun plantage, aucune exception dans logcat
     (seulement des lignes `KysheonBle` de niveau W ou D).
   - Attendu sur B : `Lost(PeerDisconnected, code)` ; noter le code (souvent 8, délai de supervision, ou 19).
2. Rallumer le Bluetooth sur A, relancer « Héberger » puis « Rejoindre » sur B.
   - Attendu : nouvelle connexion sans redémarrer l’application (réutilisation de l’instance après `Lost`).
3. Recommencer en coupant le Bluetooth sur **B** (invité), puis avec les rôles inversés (B hôte, A invité),
   en coupant de chaque côté.

### 5. Hors de portée

1. Connexion établie. Éloigner B progressivement (autre pièce, ou B dans une boîte métallique).
   - Attendu : `Lost(PeerDisconnected, code)` des deux côtés après le délai de supervision (quelques secondes) ;
     noter le délai et le code.
2. Pendant la recherche (`Scanning`) sans hôte à portée.
   - Attendu : `Lost(PeerNotFound)` après 15 s.

### 6. Application en arrière-plan

1. Connexion établie. Sur A, appuyer sur Accueil pendant 10 s, puis revenir.
   - Attendu (à décrire) : la connexion survit-elle ? Les trames continuent-elles en arrière-plan ? L’intégrateur
     décide si l’UI appelle `close()` à la mise en arrière-plan ; noter le comportement observé.
2. Même chose sur B.
3. Écran verrouillé 30 s pendant une partie, sur chaque téléphone.

### 7. Cycles connexion / fermeture

1. Répéter 10 fois : A « Héberger », B « Rejoindre », attendre `Connected`, fermer la partie des deux côtés
   (`close()` → `Idle`).
   - Attendu : 10 connexions réussies ; aucune fuite visible (le journal ne montre pas d’échec d’ouverture du
     serveur GATT ni `ADVERTISE_FAILED_TOO_MANY_ADVERTISERS`).
2. Répéter très vite (moins de 5 s par cycle) côté invité.
   - Attendu possible : Android limite les démarrages de scan (environ 5 par 30 s par application) ; un
     `Lost(PeerNotFound)` peut alors apparaître. Noter à partir de quel cycle.
3. Fermer côté hôte pendant que l’invité est `Connected`, puis l’inverse.
   - Attendu : le côté restant passe à `Lost(PeerDisconnected)`.

### 8. Erreur 133 et nouvelle tentative

L’erreur 133 (`GATT_ERROR`) ne se provoque pas à la demande. Méthodes possibles :

1. Lancer « Rejoindre » sur B, puis couper le Bluetooth de A juste après le passage de B à `Connecting`.
   - Attendu : dans logcat de B, un premier échec, une nouvelle tentative 600 ms plus tard, puis
     `Lost(ConnectionFailed, code)` (ou `PeerDisconnected`) ; jamais de blocage en `Connecting` au-delà de 20 s.
2. Pendant toutes les autres sections, relever chaque apparition de 133 dans logcat (`code 133`) et noter si la
   nouvelle tentative a abouti.

### Résultats

| Section | A (Nothing Phone 1) | B (CMF Phone 1) | Remarques |
|---|---|---|---|
| Versions d’Android | Android 15 (API 35) | Android 16 (API 36) | 2026-10-09 |
| 1.1 Permissions Android 12+ | OK | OK | demande système, puis « Bluetooth prêt » |
| 1.2 Repli Android 8–11 | non testable | non testable | aucun appareil < 12 |
| 1.3 Bluetooth éteint au lancement | non mesuré | non mesuré | |
| 2. A hôte, B invité : délai jusqu’à `Connected` | — | ≈ 2,5 s après « Rejoindre » | après correctifs KYS-0024 |
| 2. MTU observée | 517 | 517 | demandée : 247 |
| 2. Trames perdues / profondeur max de file | 0 ping perdu | 0 ping perdu | profondeur non relevée |
| 3. B hôte, A invité | OK | OK | 0 ping perdu, RTT médian ≈ 34 ms |
| 4. Bluetooth coupé (côté hôte / côté invité) | non mesuré | non mesuré | codes : |
| 5. Hors de portée (délai, code) | non mesuré | non mesuré | |
| 6. Arrière-plan / écran verrouillé | non mesuré | non mesuré | |
| 7. 10 cycles connexion / fermeture | non mesuré | non mesuré | |
| 7. Limite de scans rapprochés | non mesuré | non mesuré | |
| 8. Erreur 133 et nouvelle tentative | non mesuré | non mesuré | |

### Journal des essais

- **2026-10-09, essai 1** (Nothing hôte, CMF invité) : connexion GATT établie mais invité bloqué après la MTU,
  hôte qui retient un appareil parasite. Deux bugs corrigés, voir [KYS-0024](decisions/0024-android-ble-device-findings.md).
- **2026-10-09, essai 2** : sonde dans les deux sens, 0 perte (mesures dans le README) ; partie complète jouée,
  « Victoire » sur le Nothing et « Défaite » sur le CMF, PV cohérents des deux côtés ; revanche OK.
