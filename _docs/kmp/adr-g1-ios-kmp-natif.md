# ADR G1 — drivers iOS en KMP natif

> **Statut :** accepté le 2026-10-03, décision du propriétaire.  
> **Remplace, sur ce point seulement :** l'hôte iOS Trio / LoopKit (D1) et l'interdiction de pile BLE/NFC dans `iosMain`, dans [ADR G0](adr-g0-defaults.md) (accepté le 2026-08-26). La même phrase vaut pour les deux rappels de [ADR G0-D2b](adr-g0-d2-ios-pump-medtrum.md) : hôte Trio, pompe iOS via `MedtrumKit`, « do not put `:pump:medtrum` in `iosMain` ».  
> **Branche lue :** `kmp-aimi-migration-study` @ `7c13732990b338969c5c54f832492efb2bf8de57` (merge de #144, P5). La première rédaction lisait `16029c9587`. Le paragraphe P5 ci-dessous est la relecture.  
> **Règle :** identique à G0. Un changement de comportement thérapeutique exige en plus un replay avant/après. Cette ADR n'en est pas un : elle ne branche aucun appareil.

## Contexte

Sur ce tip, iOS est un client suiveur. `IosClientConfig` fixe `APS = false`, `PUMPCONTROL = false` et `PUMPDRIVERS = false` (`ios/shell/src/iosMain/kotlin/app/aaps/ios/shell/config/IosClientConfig.kt`). La seule source de glycémie en `commonMain` est Nightscout (`plugins/source/src/commonMain/kotlin/app/aaps/plugins/source/NSClientSourcePlugin.kt`). Les sources par broadcast sont répondues désactivées dans `ios/shell/src/iosMain/kotlin/app/aaps/ios/shell/platform/IosBgSourceIntegrations.kt` : pas de canal de broadcast sur iOS. La pompe virtuelle est le seul module pompe déjà multiplateforme (`kotlin("multiplatform")` dans `pump/virtual/build.gradle.kts`, sept fichiers sous `pump/virtual/src/commonMain/`). Elle n'est pas proposée : `PUMPDRIVERS` reste faux.

`BleTransport`, `BleAdapter`, `BleScanner`, `BleGatt`, `BleTransportListener` et `PairingState` sont déjà dans `core/interfaces/src/commonMain/kotlin/app/aaps/core/interfaces/pump/ble/BleTransport.kt`. Trois pompes Android s'en servent, chacune avec sa propre implémentation :

- Dana RS / Dana-i : `pump/danars/src/main/kotlin/app/aaps/pump/danars/services/BleTransportImpl.kt`
- Medtrum : `pump/medtrum/src/main/kotlin/app/aaps/pump/medtrum/ble/MedtrumBleTransport.kt` (et `MedtrumBleTransportImpl.kt`)
- Equil : `pump/equil/src/main/kotlin/app/aaps/pump/equil/ble/EquilBleTransport.kt` (et `EquilBleTransportImpl.kt`)

Les émulateurs `pump/danars-emulator` (`EmulatorBleTransport.kt`) et `pump/equil-emulator` (`EquilEmulatorBleTransport.kt`) implémentent le même contrat sans radio. Diaconn parle à `android.bluetooth` dans `pump/diaconn/src/main/kotlin/app/aaps/pump/diaconn/service/BLECommonService.kt` et n'importe pas `BleTransport`. Le Bluetooth Classic est un autre contrat, `core/interfaces/src/androidMain/kotlin/app/aaps/core/interfaces/pump/rfcomm/RfcommTransport.kt`, sans `iosMain`. La permission Bluetooth iOS existe déjà et renvoie `null` (`core/interfaces/src/iosMain/kotlin/app/aaps/core/interfaces/pump/BluetoothPermission.ios.kt`) : le système demande via `Info.plist`.

`GlucosePort` et `AimiKit` ne sont que des noms dans les ADR et le plan. Un recherche sur ce tip ne trouve aucune définition dans le code.

G0 (D1 et la conséquence qui suit le tableau) interdit précisément ce que cette ADR ouvre : pas de pile BLE/NFC Android dans `iosMain`, capteurs iOS via LoopKit derrière `GlucosePort`. G0-D2b ajoute : première pompe iOS = Medtrum via le `MedtrumKit` de Trio, et interdit de mettre `:pump:medtrum` dans `iosMain`.

## Décision

Pour iOS, le chemin est le KMP natif. Les drivers de pompes et de CGM sont portés en Kotlin Multiplatform : le protocole en `commonMain`, le transport en `iosMain`, via `platform.CoreBluetooth` et `platform.CoreNFC` (Kotlin/Native). Pas d'hôte Trio, pas de kit LoopKit vendored dans cet arbre.

Périmètre de la décision :

- Elle autorise une implémentation `iosMain` de `BleTransport`, puis le portage des protocoles qui s'appuient dessus.
- Elle ne retourne aucun drapeau. `PUMPDRIVERS`, `PUMPCONTROL` et `APS` restent `false` dans `IosClientConfig`.
- Elle ne copie aucun dépôt Trio. Les dépôts sans licence se lisent, ils ne s'importent pas (voir Risques).
- Elle ne change pas le moteur de dose, le modèle TFLite (D4), l'entraînement (D8), la parité (D9), l'interdiction de copier les appairages Android (D12), ni la règle de reproduire les défauts du gel (M0.6).

## Ce que G0 cède, et ce qu'il garde

Remplacé sur ce point par ADR G1 :

| Texte de G0 ou G0-D2b | Devenu |
|---|---|
| D1 : hôte iOS = Trio / LoopKit, AIMI en XCFramework `AimiKit` | Les drivers iOS vivent dans ce dépôt. `commonMain` pour le protocole, `iosMain` pour CoreBluetooth et CoreNFC. |
| Conséquence : « iOS does not get the Android BLE/NFC stack in `iosMain` » | Le transport BLE (et plus tard NFC) a sa place dans `iosMain`. Le code Android `android.bluetooth` / `android.nfc` n'est pas déplacé tel quel : il est réécrit derrière le contrat déjà commun, ou un contrat de la même forme. |
| G0-D2b : pompe iOS = Trio `MedtrumKit` ; « Do not put `:pump:medtrum` in `iosMain` » | Medtrum reste la première pompe réelle de la file (après le transport et la crypto), portée depuis `:pump:medtrum`, pas depuis `MedtrumKit`. |

Inchangé, et relu ici pour qu'on ne le rouvre pas en passant :

- Pas de pompe Bluetooth Classic (G0 D2 pompe, G0-D2b).
- Pas de Dana-i comme première pompe iOS. Dana RS / Dana-i vient après Medtrum.
- D12 : on ne copie pas les appairages, secrets ou commandes en attente. On ré-appaire sur l'iPhone.
- Le moteur ne commande pas une pompe depuis `evaluate`.
- Stratégie S2 : pas de rebase de `dev_OAPSAIMI` sur `kmp`.

`docs/kmp-migration/DELTA-remaining.md` (ligne GATT/NFC et piste T1) décrit encore l'ancienne règle. Une mention y renvoie ici. Le tableau T1 n'est pas réécrit.

## Conséquences

**Appareils impossibles sur iOS.** CoreBluetooth ne parle pas Bluetooth Classic, et iOS n'ouvre pas de RFCOMM/SPP vers un accessoire non MFi.

- Dana R, R Korean, RV2 : `pump/danar/src/main/kotlin/app/aaps/pump/danar/services/RealRfcommTransport.kt` et `pump/danar/src/main/kotlin/app/aaps/pump/danar/SerialIOThread.kt`.
- Combo : `pump/combov2/comboctl/src/androidMain/kotlin/info/nightscout/comboctl/android/AndroidBluetoothInterface.kt`. `comboctl` a des dossiers `commonMain` / `androidMain` mais son plugin est `alias(libs.plugins.android.library)` (`pump/combov2/comboctl/build.gradle.kts`).
- Insight : `pump/insight/src/main/kotlin/app/aaps/pump/insight/utils/ConnectionEstablisher.kt` et `connection_service/InsightConnectionService.kt`.

Les sources qui n'existent que par un canal Android restent hors iOS : broadcast ou notification (xDrip, Dexcom BYODA `DexcomPlugin`, Tomato, Glimp, Poctech, MM640g, Aidex, Syai, Si, Sino, Instara, Notification Reader — tous sous `plugins/source/src/androidMain/`), et ContentProvider (Glunovo, Intelligo). Une API cloud fabricant n'est pas étudiée ici. Nightscout, déjà en `commonMain`, reste la source active.

**Ce que le transport iOS devra respecter, sans que cette ADR ne le code.** Les trois implémentations Android du même contrat ne font pas la même chose : Dana écrit en `WRITE_TYPE_NO_RESPONSE`, Medtrum force `WRITE_TYPE_DEFAULT`, Equil laisse le type de la caractéristique. `findCharacteristics()` ne reçoit pas d'UUID. `createBond` / `removeBond` n'ont pas d'équivalent CoreBluetooth, et `BLEComm.connect` refuse d'avancer tant que `isDeviceBonded` est faux. L'adresse Android est une MAC ; iOS n'expose qu'un `NSUUID` par appareil. Ces écarts se documentent dans le lot transport. Ils ne s'effacent pas en modifiant `BleTransport` dans cette ADR.

**Ordre des lots.** Chacun reste inactif tant que la règle de sécurité ci-dessous n'est pas satisfaite.

1. `BleTransport` sur iOS (CoreBluetooth). Aucun driver branché. `PUMPDRIVERS` reste `false`.
2. Couche crypto commune manquante pour la suite (AES-CCM, AES-CMAC, X25519, ECDH, ECDSA P-256). Aujourd'hui `IosCryptoPrimitives` (`core/objects/src/iosMain/kotlin/app/aaps/core/objects/crypto/IosCryptoPrimitives.kt`) expose SHA-256, HMAC, PBKDF2, AES-GCM et l'aléa, via cryptography-kotlin 0.6.0 (`gradle/libs.versions.toml`). Pas CCM, CMAC, X25519, ECDH ni ECDSA dans cette classe.
3. Medtrum. `comm/` compte 35 fichiers Kotlin ; aucun n'importe `android.*`. `encryption/Crypt.kt` non plus. `MedtrumService.kt` fait 1 200 lignes et reste le gros morceau plateforme.
4. Dana RS / Dana-i. `services/BLEComm.kt` fait 828 lignes. Neuf des 46 fichiers de `comm/` importent Joda ou Android.
5. Equil, puis Diaconn. Diaconn doit d'abord passer par `BleTransport` : il ne l'utilise pas.
6. Chantiers XL, après les cinq précédents : Dexcom ONE+ / G7, Omnipod Dash, Libre 3, RileyLink, Medtronic, Omnipod Eros, EOPatch. L'ordre à l'intérieur de ce paquet n'est pas tranché ici. P5 n'en avance aucun sur iOS (voir plus bas).

La pompe virtuelle n'est pas un lot driver. L'allumer serait quitter le mode suiveur, ce que la règle de sécurité interdit tant qu'aucun appareil réel n'a été testé — et elle n'est de toute façon pas un appareil BLE.

## Risques

**P5 (Libre 3, ONE+), relu sur `7c13732990`.** Le merge de #144 ajoute du code Android. Il ne change pas cette décision.

- `:plugins:libre3` et `:plugins:dexcom_oneplus` restent `alias(libs.plugins.android.library)`. Aucun `iosMain`. `ios/shell` ne les cite pas.
- Libre 3 : `Libre3BooleanKey.UseRealSkeleton` (`libre3_use_real_skeleton`) est à `false`, réservé à l'engineering. Le pré-soak (`libre3_presoak_enabled`) et le service de session (`libre3_keep_session_alive`) sont aussi à `false`. `Libre3CgmDrivers` documente le stub comme défaut.
- ONE+ : l'envoi de calibration, la réparation de `SENSOR_CHANGE`, la correction de date d'insertion et l'ancre manuelle sont à `false`, engineering seulement. `dexcom_oneplus_use_real_skeleton` vaut `true` mais reste engineering, et son commentaire dit que le squelette échoue fermé au GATT et à l'authentification. Ce n'est pas une claim BLE de production, et ce n'est pas iOS.
- `PUMPDRIVERS` est toujours `false` dans `IosClientConfig`.

**Propriété intellectuelle Abbott (Libre 3).** Les tables binaires sont toujours dans `plugins/libre3/src/main/resources/libre3/`, y compris `phone_cert_162b.bin` et les programmes `firstpair_*.bin`. `plugins/libre3/NOTICE` les rattache à un portage de LibreCRKit. La licence MIT du code ne couvre pas ces tables. On ne copie rien depuis LibreCRKit. P5 ne lève pas ce risque : Libre 3 reste dans le paquet XL et ne se porte pas sur iOS tant que ce risque n'est pas traité à part.

**Alarmes sonores.** `_docs/ios_blockers.md` dit encore que `setAudibleAlarm` se contente de journaliser. C'est périmé sur ce tip : `IosSystemNotificationPlatform.setAudibleAlarm` appelle `IosAlarmSoundPlayer.play` (`implementation/src/iosMain/kotlin/app/aaps/implementation/notifications/IosAlarmSoundPlayer.kt`), un `AVAudioPlayer` en catégorie `playback`, donc audible téléphone en silencieux **tant que le processus vit**. Il n'y a toujours pas de son si l'app n'est pas lancée (pas d'équivalent du service de premier plan, entitlement Critical Alerts non décidé — question laissée ouverte par G0). Aucun driver ne pilote une boucle réelle tant que ce trou-là n'est pas assumé.

**BLE en arrière-plan et restauration d'état.** iOS réveille l'app pour une notification GATT d'un périphérique déjà connecté si `bluetooth-central` est dans `Info.plist`. Un scan en arrière-plan doit filtrer par UUID de service. `CBCentralManagerOptionRestoreIdentifierKey` et `willRestoreState` n'ont pas de place dans `BleTransport` aujourd'hui. Le lot transport les ajoute seulement si le contrat les porte ; sinon il documente l'écart. On ne pose pas la clé de restauration sans endroit où ranger l'état rendu par le système.

**Dépôts Trio sans licence.** Le 2026-10-03, l'API GitHub répond 404 pour `LICENSE` et pour la licence détectée de `loopandlearn/OmnipodKit` et `loopandlearn/MinimedKit`. `loopandlearn/MedtrumKit`, `loopandlearn/DanaKit` et `LoopKit/OmniBLE` répondent MIT. OmnipodKit et MinimedKit se consultent en lecture seule. On n'en copie pas le code dans cet arbre. On ne vendor aucun kit, MIT compris : le chemin retenu est le portage du protocole déjà dans ce dépôt.

**Crypto et clinique.** Omnipod (AES-CCM, CMAC, X25519, Milenage) et Libre 3 (ECDH P-256, ECDSA, AES-CCM) ne se portent pas sur la foi de cryptography-kotlin 0.6.0. Chaque essai Medtrum, Dana, Equil ou Diaconn consomme du matériel. Ce n'est pas un argument pour activer plus tôt.

## Règle de sécurité

Rien d'actif par défaut sur iOS tant qu'un appareil n'a pas été testé sur matériel réel. Concrètement : pas de bascule de `PUMPDRIVERS`, `PUMPCONTROL` ou `APS`, pas d'enregistrement Metro d'un driver de pompe ou de CGM iOS, pas de caractéristique qui écrit vers un appareil, tant que le lot de cet appareil n'a pas de preuve sur le matériel. Un transport qui compile et dont la machine à états est testée par un faux n'est pas cette preuve.

## Corrections à l'inventaire du 2026-10-03

L'inventaire joint a été relu contre `16029c9587`. Les chemins cités plus haut existent. Trois affirmations sont fausses ou trop vieilles, et cette ADR ne les reprend pas :

- `pump/medtrum/.../comm/` : 35 fichiers, zéro `import android.`. Le fichier qui sort du lot est `comm/enums/MedtrumPumpState.kt`, qui importe `androidx.annotation.StringRes`, pas `android.*`. L'inventaire disait « un seul importe Android ».
- `SerialIOThread` n'est pas sous `services/`. Le chemin est `pump/danar/src/main/kotlin/app/aaps/pump/danar/SerialIOThread.kt`.
- L'alarme iOS ne se limite plus à un journal. Voir Risques. La phrase de `_docs/ios_blockers.md` est marquée périmée au même endroit.

Le décompte « sept fichiers » de `pump/virtual/src/commonMain/` est juste. Les trois noms cités par l'inventaire n'épuisent pas la liste : il y a aussi `VirtualPumpSerial.kt`, `extensions/PumpEnactResultExtension.kt`, et deux clés sous `keys/`.

## Encore ouvert

- Le lot transport doit dire, sans changer le contrat si ce n'est pas indispensable, comment il aligne écriture avec et sans réponse, identifiant opaque, `createBond`, et la restauration d'état. S'il ne peut pas s'aligner sur Android, il s'arrête et le décrit.
- Ordre interne du paquet XL.
- Critical Alerts, et donc le son quand l'app est tuée.
- Tables Abbott : décision juridique séparée, avant tout portage Libre 3.
