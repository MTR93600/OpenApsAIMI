# Écarts — `BleTransport` iOS (lot transport, sans pompe)

> Branche : `cursor/ios-ble-transport-1982`, base `kmp-aimi-migration-study` @ `16029c9587ad2bf76f0c047d9f98e892b3740a0a`.  
> Contrat inchangé : `core/interfaces/src/commonMain/kotlin/app/aaps/core/interfaces/pump/ble/BleTransport.kt`.  
> Code : `core/interfaces/src/commonMain/kotlin/app/aaps/core/interfaces/pump/ble/session/BleSession.kt`, `core/interfaces/src/iosMain/kotlin/app/aaps/core/interfaces/pump/ble/session/CoreBluetoothCentral.kt`, `IosBleTransport.kt`.  
> `IosClientConfig.PUMPDRIVERS` reste `false`. `IosBleTransport` n'est pas `@Inject`, n'est pas dans le graphe Metro, et aucun driver ne le construit.

Ce lot aligne ce qui peut l'être sur la sémantique Android **de Dana** (`pump/danars/src/main/kotlin/app/aaps/pump/danars/services/BleTransportImpl.kt`), parce que c'est l'implémentation qui passe par `BleTransportListener` de bout en bout. Medtrum et Equil divergent déjà entre eux sur Android. Une seule classe iOS ne peut pas reproduire les trois à la fois sans changer le contrat. Ce document dit où l'alignement s'arrête. Rien ici n'est une décision de changer `BleTransport`.

## Ce qui est aligné

- Callbacks du listener invoqués depuis le fil de la radio, avant le retour du callback. Sur iOS ce fil est la file série `app.aaps.ble`. Les méthodes publiques de `IosBleTransport` y entrent par `dispatch_sync` ; un appel déjà sur la file s'exécute en ligne (`dispatch_get_specific`).
- `onConnectionStateChanged(true)` seulement après le lien établi, pas au retour de `connect`.
- `onServicesDiscovered(true)` seulement quand les caractéristiques sont déjà là. CoreBluetooth renvoie les services sans caractéristiques : la session enchaîne `discoverCharacteristics` et ne rappelle le listener qu'à la fin. Un faux qui livre déjà les caractéristiques (style Android) rappelle tout de suite.
- `onCharacteristicWritten` et `onDescriptorWritten` partent aussi quand le statut n'est pas zéro. Le statut non nul est en plus enregistré dans `BleSession.failures` (`BleFailure.Stack`). Il n'est pas avalé.
- Écriture sans réponse : la complétion est postée (`dispatch_async`), elle ne court pas à l'intérieur de `write`.
- `enableNotifications` avant `findCharacteristics` : aucun appel radio, aucun `onDescriptorWritten`.
- Scan : un nom vide ou nul n'est pas émis. Les octets d'annonce sont copiés.
- `close` n'émet pas un second `onConnectionStateChanged(false)`.
- UUID comparés sans casse. Un UUID 16 bits (4 ou 8 caractères hex) est étendu vers `0000xxxx-0000-1000-8000-00805f9b34fb`.
- Statut GATT 0 = succès. `NSError` absent = 0. `NSError` présent avec code 0 est traduit en 1, pour ne pas le lire comme un succès.
- `requestConnectionPriority` : no-op, comme le défaut de l'interface. Aucun échec enregistré. CoreBluetooth n'a pas d'équivalent.

## Écarts, non tranchés dans le contrat

### Pas de clé de restauration

`BleTransport` n'a pas d'identifiant de restauration ni de `willRestoreState`. `CBCentralManager` est créé avec `options = null`. Poser `CBCentralManagerOptionRestoreIdentifierKey` sans lire l'état restauré ferait jeter par iOS les périphériques pour lesquels l'application est relancée. La clé n'est donc pas posée.

### Appairage

`isBonded` renvoie toujours `false`. `createBond` renvoie `false` et enregistre `BleFailure.BondUnsupported`. `removeBond` enregistre la même valeur. CoreBluetooth n'a pas `createBond` / `removeBond` / liste de bonds.

Dana, dans `pump/danars/src/main/kotlin/app/aaps/pump/danars/services/BLEComm.kt`, refuse d'avancer si `isDeviceBonded` est faux, puis appelle `createBond`. Ce lot ne modifie pas `BLEComm` ni le contrat. Sur iOS, Dana ne passera pas cette porte tant qu'un lot ultérieur ne la changera pas, côté pompe. L'émulateur Dana renvoie `true` pour laisser la logique pompe avancer : l'implémentation iOS ne ment pas.

### `enable` n'allume pas la radio

Si l'adaptateur n'est pas `PoweredOn`, `enable` enregistre `BleFailure.AdapterNotReady`. Il n'appelle pas le central. Un scan radio éteinte n'appelle pas `startScan` et n'émet rien : pas de `catch` sur `IllegalStateException` (Dana avale cette exception ; ce lot ne la copie pas). `connect` radio éteinte renvoie `false` sans callback.

### `scanRecordBytes` n'est pas l'annonce Android brute

Le central passe les données constructeur CoreBluetooth (`CBAdvertisementDataManufacturerDataKey` : identifiant de compagnie puis charge). Ce n'est pas la structure AD brute d'Android.

- Equil lit l'index 23–24 de `scanRecordBytes` (`pump/equil/src/main/kotlin/app/aaps/pump/equil/ble/EquilBLE.kt`).
- Medtrum `ManufacturerData` attend la tranche `getManufacturerSpecificData` (identifiant 4 octets + type + version), pas l'enregistrement complet et pas les 2 octets de compagnie.

Aucune des deux pompes n'est branchée. Ne pas leur donner ces octets tels quels.

L'adresse émise est la chaîne `NSUUID`, pas une MAC. Une reconnexion d'un identifiant déjà vu passe par `retrievePeripheralsWithIdentifiers`. Un identifiant jamais vu et jamais scanné fait renvoyer `false` à `connect` : iOS ne fabrique pas un périphérique à partir d'une adresse, contrairement à `BluetoothAdapter.getRemoteDevice`.

`getDeviceName` renvoie le dernier nom de scan non vide, sinon le nom en cache du périphérique.

### Pas de sommeil bloquant dans `connect`

Dana ferme le GATT existant avec un `sleep` de 200 ms sur le fil appelant. La file CoreBluetooth ne doit pas être bloquée. Il n'y a pas de sommeil.

### Timeouts

`timeoutMillis` vaut `null` par défaut, comme les transports Android qui ne temporisent pas dans `BleTransport`. `IosBleTransport` n'arme l'horloge que si une valeur est fournie.

Si une valeur est fournie : timeout de connexion → `onConnectionStateChanged(false)` ; timeout de découverte → `onServicesDiscovered(false)` seulement ; timeout d'écriture avec réponse ou de descripteur → `onConnectionStateChanged(false)` et **pas** `onCharacteristicWritten` (Dana lirait ça comme un succès). L'écriture sans réponse se termine par le `post`, pas par l'attente.

### Mode d'écriture

Le contrat n'a pas de paramètre write-type. `BleProfile.writeMode` :

- `AndroidDefault` (défaut) : sans réponse si la propriété `WRITE_NO_RESPONSE` est présente, sinon avec réponse. C'est le constructeur AOSP de `BluetoothGattCharacteristic`.
- Dana force le sans-réponse, Medtrum force l'avec-réponse. Ces profils restent non branchés. Le profil peut forcer l'un ou l'autre plus tard.

Equil, sur écriture hors connexion, appelle `onConnectionStateChanged(false)`. Dana n'appelle rien. La session suit Dana : `BleFailure.NotConnected`, pas de callback.

### Notifications et indications

`indicate` est calculé comme Medtrum : indication seulement si `Notify` est absent et `Indicate` est présent. `setNotifyValue(true)` laisse CoreBluetooth choisir selon les propriétés. Le drapeau n'est pas écrit en octets CCCD. Une seule complétion, `didUpdateNotificationStateForCharacteristic` → `onDescriptorWritten`. `didWriteValueForDescriptor` n'est pas aussi relayé (Dana verrait une seconde inscription).

### Erreur de notification

`didUpdateValueForCharacteristic` avec `NSError` n'appelle pas `onCharacteristicChanged`. L'échec est enregistré (`BleFailure.CentralRejected("notify", …)`). Le contrat n'a pas de callback d'erreur de lecture.

## Preuve et ce qui n'a pas tourné

La machine à états et la traduction d'erreurs sont testées en `commonTest` (`BleSessionTest`), exécuté sur la JVM. Les tests simulateur iOS ne tournent pas sur cette VM Linux. Le job CI `ios` (`.github/workflows/ios-ci.yml`, `macos-latest`) compile et lance `iosSimulatorArm64Test` pour `:core:data`, `:implementation` et `:ios:shell`. Il ne lance pas `:core:interfaces:iosSimulatorArm64Test`. La compilation `iosSimulatorArm64` et `iosArm64` de `:core:interfaces` est la preuve de compilation de ce module. Elle ne remplace pas une exécution sur appareil.
