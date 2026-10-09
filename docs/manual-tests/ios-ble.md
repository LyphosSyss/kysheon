# Tests manuels — BLE iOS (`IosBleLink`, `IosBluetoothAvailability`)

**Statut : compilé uniquement, rien n’est vérifié sur appareil.** Le simulateur iOS n’a pas de Bluetooth :
tout ce qui suit demande l’iPhone 16e et un téléphone Android. Chaque résultat est marqué « non mesuré »
tant que l’auteur ne l’a pas rempli (date, version d’iOS, observation, capture de l’overlay de debug).

Décisions liées : [KYS-0030](../decisions/0030-ios-ble-main-queue.md), [KYS-0031](../decisions/0031-ios-ble-guest-first.md),
[KYS-0032](../decisions/0032-ios-ble-flow-control.md), [KYS-0033](../decisions/0033-ios-peripheral-no-disconnect.md),
[KYS-0034](../decisions/0034-ios-ble-objc-signature-override.md), [KYS-0035](../decisions/0035-ios-bluetooth-availability.md),
[KYS-0036](../decisions/0036-ios-ble-lost-reasons.md).

## Prérequis

1. **Info.plist** de l’app iOS : la clé `NSBluetoothAlwaysUsageDescription` est obligatoire. Sans elle, iOS termine
   l’app dès la création du premier `CBCentralManager` / `CBPeripheralManager`. Le texte est localisé dans
   `InfoPlist.strings` (`en.lproj`, `fr.lproj`), seule exception autorisée à `stringResource` (AGENTS.md, règle 4).
   C’est l’intégrateur de l’app qui ajoute la clé, pas le module `:link`.
2. Pas de `UIBackgroundModes` (`bluetooth-central` / `bluetooth-peripheral`) en V1.
3. Build Debug de l’app sur l’iPhone 16e ; noter la version d’iOS.
4. Téléphone Android avec la build du flux Android (`AndroidBleLink`), même version de protocole.
5. Overlay de debug affiché : `LinkState`, `LinkDiagnostics` (`mtu`, compteurs, `maxQueueDepth`, `lastPlatformCode`,
   `platformInfo`) et, pour T8, les statistiques de la sonde de latence.
6. Pour repartir d’une permission « jamais demandée » : supprimer l’app de l’iPhone puis la réinstaller.

Rappel de lecture de l’overlay : `mtu` affiche la **charge utile effective donnée par iOS + 3** (en-tête ATT).
iOS n’expose jamais la MTU elle-même ; la charge utile vient de `maximumWriteValueLength(withoutResponse)` côté
invité et de `CBCentral.maximumUpdateValueLength` côté hôte.

## T1 — Permission au premier lancement

1. Installer l’app (permission jamais demandée). Lancer.
2. Avant toute action Bluetooth : vérifier qu’aucune demande de permission n’apparaît au lancement.
3. Appuyer sur Héberger (ou Rejoindre), ce qui appelle `IosBluetoothAvailability.start()` puis `host()` / `join()`.
4. Accepter la demande système.

Attendu :
- étape 2 : aucune demande ; `availability` = `Unauthorized`, `isPermissionDetermined` = `false` ;
- étape 3 : la demande système s’affiche avec le texte de `NSBluetoothAlwaysUsageDescription` dans la langue du
  téléphone ; tant qu’elle est affichée, le lien n’échoue pas par délai (`adapterTimeout` suspendu tant que
  l’autorisation est indéterminée) ;
- étape 4 : `availability` passe à `Ready`, le lien poursuit (Advertising ou Scanning).

Résultat : non mesuré.

## T2 — Permission refusée

1. Réinstaller l’app, lancer, appuyer sur Rejoindre.
2. Refuser la demande système.
3. Appuyer de nouveau sur Rejoindre.
4. Utiliser l’action « Ouvrir les réglages » (`openAppSettings()`).

Attendu :
- étape 2 : `LinkState.Lost(PermissionDenied)` ; `availability` = `Unauthorized` ; `isPermissionDetermined` = `true` ;
- étape 3 : `Lost(PermissionDenied)` immédiatement, aucune nouvelle demande (iOS ne la repose jamais) ;
- étape 4 : la page de l’app s’ouvre dans Réglages, avec l’interrupteur Bluetooth.

Résultat : non mesuré.

## T3 — Réactivation depuis Réglages

1. Depuis l’état de T2, activer l’interrupteur Bluetooth de l’app dans Réglages.
2. Revenir dans l’app (sélecteur d’apps).
3. Appuyer sur Rejoindre.

Attendu :
- iOS peut terminer l’app lors d’un changement de permission : **noter si l’app a redémarré** (retour à l’accueil) ;
- après retour (ou relance) puis `start()`, `availability` = `Ready` ;
- étape 3 : le lien passe à Scanning sans nouvelle demande.

Résultat : non mesuré (app redémarrée : non mesuré).

## T4 — iPhone 16e invité d’un hôte Android

1. Android : Héberger → `Advertising`.
2. iPhone : Rejoindre.
3. Laisser la session atteindre le lobby, lancer une partie ou la sonde de latence pendant 60 s.
4. Android : quitter la partie (`close()`).
5. Variante : iPhone Rejoindre **sans** hôte allumé.
6. Variante : deux hôtes Android à des distances différentes.

Attendu :
- étape 2 : `Scanning` → `Connecting` → `Connected` ; le choix intervient environ 1,5 s (`scanWindow`) après le
  premier résultat ; `platformInfo` = `iOS <version> · central (guest) · write without response` ;
- étape 3 : `mtu` renseigné (valeur à relever), `framesSent` et `framesReceived` croissent, `framesDropped` = 0,
  `maxQueueDepth` faible (à relever) ;
- étape 4 : `Lost(PeerDisconnected, code)` côté iPhone, code `CBError` à relever ; à défaut, la session passe en pause
  après 3 s de silence puis abandonne 5 s plus tard ;
- étape 5 : `Lost(PeerNotFound)` après environ 10 s (`scanTimeout`) ;
- étape 6 : connexion à l’hôte le plus proche (RSSI le plus fort).

| Mesure | Valeur |
|---|---|
| Temps Rejoindre → Connected | non mesuré |
| `mtu` affiché (charge utile + 3) | non mesuré |
| `maxQueueDepth` après 60 s | non mesuré |
| Code à la fermeture côté Android | non mesuré |
| Étape 5 (`PeerNotFound`) | non mesuré |
| Étape 6 (choix par RSSI) | non mesuré |

## T5 — iPhone 16e hôte d’un invité Android

1. iPhone : Héberger → `Advertising` (`host()` retourne).
2. Android : Rejoindre.
3. Sonde de latence ou partie pendant 60 s.
4. Android : quitter la partie (`close()`).
5. Recommencer 1 à 3, puis cette fois **iPhone** : quitter la partie.
6. Recommencer 1 à 3, puis éteindre brutalement le Bluetooth du téléphone Android.

Attendu :
- étape 2 : `Connected` côté iPhone quand l’invité s’abonne à TX ; l’annonce s’arrête (un second téléphone ne voit
  plus l’hôte) ; `platformInfo` = `iOS <version> · peripheral (host) · notify` ;
- étape 3 : compteurs comme en T4 ; `mtu` = `maximumUpdateValueLength` + 3 ;
- étape 4 : soit `Lost(PeerDisconnected)` (iOS a signalé le désabonnement), soit rien côté lien et la session passe en
  pause après 3 s puis abandonne 5 s plus tard. **Un peripheral iOS ne reçoit aucune callback de déconnexion** :
  noter lequel des deux cas se produit ;
- étape 5 : l’hôte iPhone retire son service ; l’invité Android le détecte (service modifié ou déconnexion) ou, à
  défaut, par le heartbeat ;
- étape 6 : côté iPhone, détection par le heartbeat uniquement (pause puis abandon).

| Mesure | Valeur |
|---|---|
| Temps Rejoindre (Android) → Connected (iPhone) | non mesuré |
| `mtu` affiché | non mesuré |
| Étape 4 : désabonnement signalé ou heartbeat ? | non mesuré |
| Étape 5 : réaction de l’invité Android | non mesuré |
| Étape 6 : délai jusqu’à l’abandon | non mesuré |

## T6 — Bluetooth coupé en pleine session sur l’iPhone

Faire le test dans les deux rôles (iPhone invité, puis iPhone hôte).

1. Session connectée (lobby ou partie).
2. **Réglages › Bluetooth** : désactiver.
3. Réactiver, relancer une session.
4. **Centre de contrôle** : toucher l’icône Bluetooth.

Attendu :
- étape 2 : `Lost(BluetoothOff)` immédiat sur l’iPhone, ressources libérées ; `availability` = `Off` ; côté Android,
  `Lost(PeerDisconnected)` après le délai de supervision, ou pause puis abandon par le heartbeat ;
- étape 3 : le même objet `IosBleLink` redémarre sans relancer l’app ;
- étape 4 : d’après Apple, le Centre de contrôle **ne fait que déconnecter les accessoires** et laisse le Bluetooth
  allumé ; on s’attend donc à **ne pas** voir `BluetoothOff`. À observer : la liaison de jeu est-elle coupée
  (`Lost(PeerDisconnected)` côté invité, heartbeat côté hôte) ou maintenue ? Que vaut `availability` ?

| Mesure | Invité | Hôte |
|---|---|---|
| Réglages : état et délai | non mesuré | non mesuré |
| Centre de contrôle : liaison coupée ? | non mesuré | non mesuré |
| Centre de contrôle : `availability` | non mesuré | non mesuré |

## T7 — App en arrière-plan (hors périmètre V1)

1. Session en partie, iPhone dans un rôle quelconque.
2. Revenir à l’écran d’accueil d’iOS pendant 15 s, puis revenir dans l’app.

Attendu (V1, sans mode d’arrière-plan) : l’app suspendue n’envoie plus rien ; le pair passe en pause après 3 s de
silence puis abandonne 5 s plus tard (`Aborted(PeerSilent)`) ; au retour, l’iPhone constate aussi l’abandon (ou un
`Lost`). Aucune reprise n’est attendue en V1.

Résultat : non mesuré.

## T8 — Intervalle de connexion, lu par la sonde de latence

iOS choisit l’intervalle de connexion ; une app ne peut pas le fixer. Côté hôte, `IosBleLink` demande seulement
`CBPeripheralManagerConnectionLatencyLow`, une indication qu’iOS et le central peuvent ignorer.

Méthode indirecte : un aller-retour PING → PONG demande au minimum un à deux événements de connexion (la réponse part
au même événement ou au suivant). Donc **RTT min ≈ 1 à 2 intervalles**, et l’intervalle est compris entre
`rttMin / 2` et `rttMin`. Les valeurs hautes (p95, max) mesurent surtout la gigue de planification des deux OS.

1. Connecter les deux téléphones (T4 puis T5), lancer la sonde de latence 60 s, téléphones posés côte à côte.
2. Relever `rttMin`, `rttP50`, `rttP95`, gigue.
3. En déduire la fourchette de l’intervalle.

| Configuration | rttMin | p50 | p95 | Gigue | Intervalle estimé |
|---|---|---|---|---|---|
| iPhone invité, Android hôte | non mesuré | non mesuré | non mesuré | non mesuré | non mesuré |
| iPhone hôte, Android invité | non mesuré | non mesuré | non mesuré | non mesuré | non mesuré |

## T9 — Réutilisation après `close()`

1. iPhone : Héberger, puis quitter avant toute connexion.
2. Rejoindre un hôte Android, puis quitter.
3. Héberger de nouveau.

Attendu : chaque étape repart d’`Idle`, aucune trame de la session précédente n’arrive dans la suivante, les compteurs
de diagnostic repartent de zéro.

Résultat : non mesuré.

## Journal des essais

- **2026-10-09** (iPhone 16e, iOS 27.0, profil développeur personnel) :
  - permission Bluetooth : demande système affichée au premier appui sur « Autoriser le Bluetooth », puis « Bluetooth prêt » ;
  - **iPhone invité** d’un Nothing Phone (1) hôte : connexion OK, sonde 67 s, RTT médian 54,9 ms, 0 perte, charge utile 512 ;
  - **iPhone hôte** (peripheral), Nothing invité : connexion OK, sonde 92 s côté Nothing, RTT médian 32,4 ms, 0 perte.
    Le repli « iPhone invité uniquement » (KYS-0031) n’est pas nécessaire.
  - **Partie** iPhone hôte, Nothing invité : jeu fluide (Nothing à 120 Hz : image p50 8,3 ms, 0 pas abandonné),
    handoffs dans les deux sens, touches et PV cohérents ; décalage d’horloge lissé sans saut (écart cible 4 ms).
  - **Bluetooth coupé dans Réglages sur l’iPhone** en pleine partie : iPhone → accueil « Le Bluetooth a été
    désactivé. » ; Nothing → accueil « L’autre téléphone ne répond plus. » (détecté par le heartbeat de session,
    Android n’a signalé aucune déconnexion) ; aucun crash.
  - Non testé : arrière-plan, Centre de contrôle, CMF Phone 1 ↔ iPhone.
