# AIMI / OpenApsAIMI — Inventaire de rétention pour une application autonome

**Date :** 2026-07-21  
**Statut :** analyse d’architecture (pas d’implémentation)  
**Baseline code :** flavor `pumpcontrol` + module `:plugins:aps` / `openAPSAIMI`  
**Documents liés :** analyse canvas (session chat) ; `docs/ARCHITECTURE.md`

---

## 1. Objet du document

Ce document répond à la question :

> Si l’on voulait une application Android autonome, sortant d’AAPS « full », en ne conservant que **pompe**, **capteur**, **Nightscout** et **Tidepool** (avec ou sans cerveau AIMI), **que faut-il vraiment garder** ?

Il distingue **deux produits** incompatibles dans le brief initial, puis liste pour chacun :

- modules Gradle (garder / optionnel / jeter / stub) ;
- plugins enregistrés (Hilt) ;
- contrats d’interfaces et glue runtime ;
- découplages préalables ;
- plan de rétention par phases.

---

## 2. Deux produits (ne pas les mélanger)

| | **Produit A — Connectivité** | **Produit B — AIMI dosing** |
|--|------------------------------|-----------------------------|
| **But** | CGM + pompe + NS + Tidepool + bolus / TBR manuel | Même chose **+** boucle fermée AIMI |
| **Cerveau** | Aucun (pas d’APS) | AIMI seul (`OpenAPSAIMIPlugin`) |
| **Loop** | Absent ou stub minimal (deviceStatus) | `LoopPlugin` **obligatoire** |
| **Profils** | Oui (basal pompe + Tidepool) | Oui (critique) |
| **Baseline** | Flavor `pumpcontrol` | Flavor `full` allégé |
| **Verdict** | Faisable (semaines → mois) | Faisable mais = plateforme APS AAPS (mois) |

> **AIMI n’est pas une app.** C’est ~429 fichiers Kotlin sous `plugins/aps/.../openAPSAIMI`, plugin `PluginType.APS`, invoqué par `LoopPlugin`.  
> Produit A = **retirer** AIMI. Produit B = **garder** AIMI **et** la plateforme boucle.

---

## 3. Légende de rétention

| Code | Signification |
|------|----------------|
| **KEEP** | Requis pour un MVP utile |
| **KEEP-MIN** | Garder le module, mais on peut vider une grande partie du contenu |
| **OPT** | Utile, pas bloquant pour le MVP |
| **STUB** | Contrat DI / interface à conserver avec implémentation minimale |
| **DROP** | Peut sortir du graphe Gradle (après découplage) |
| **N/A** | Sans objet pour ce produit |

---

## 4. Flux runtime de référence

```text
CGM (BgSource)
    → PersistenceLayer (Room)
        → CalculationWorkflow / IobCobCalculator
            → [Produit B] LoopPlugin → OpenAPSAIMIPlugin → APSResult
                → CommandQueue → Pump drivers → PumpSync → PersistenceLayer
        → NSClientV3 (entries, treatments, profile, deviceStatus*)
        → TidepoolUploader (chunks BG / bolus / basal / therapy)

* deviceStatus NS est aujourd’hui produit principalement par LoopPlugin.
  Produit A : garder un producteur minimal (stub Loop ou DeviceStatusPublisher).
```

---

## 5. Inventaire Gradle — tous les modules

Sources : `settings.gradle`, `app/build.gradle.kts`.

### 5.1 Shell & tooling

| Module | Produit A | Produit B | Commentaire |
|--------|-----------|-----------|-------------|
| `:app` | **KEEP** | **KEEP** | Ou nouvel `:app-*` ; flavors `pumpcontrol` / `full` / nouveau flavor |
| `:ui` | **KEEP** | **KEEP** | Overview, wizard bolus, dialogs Compose |
| `:wear` | **DROP** / OPT | OPT | Module Wear OS séparé ; pas dep de `:app` aujourd’hui |
| `:benchmark` | **DROP** | **DROP** | Hors produit |

### 5.2 Core

| Module | Produit A | Produit B | Commentaire |
|--------|-----------|-----------|-------------|
| `:core:interfaces` | **KEEP** | **KEEP** | Contrats Pump, BgSource, Loop, NsClient, Tidepool, PersistenceLayer… |
| `:core:data` | **KEEP** | **KEEP** | Modèles GV, BS, TE, DS, PluginType… |
| `:core:keys` | **KEEP** | **KEEP** | Clés préférences |
| `:core:objects` | **KEEP** | **KEEP** | ProfileSealed, BolusWizard, ConstraintObject… |
| `:core:utils` | **KEEP** | **KEEP** | Utilitaires |
| `:core:nssdk` | **KEEP** | **KEEP** | Client HTTP Nightscout |
| `:core:ui` | **KEEP** | **KEEP** | Thème Compose, composants prefs |
| `:core:libraries` | **KEEP** | **KEEP** | Libs partagées (selon usages) |
| `:core:graph` | **KEEP-MIN** | **KEEP** | Graphes overview |
| `:core:graphview` | **KEEP-MIN** | **KEEP** | Vue graphe legacy / support |

### 5.3 Données & runtime

| Module | Produit A | Produit B | Commentaire |
|--------|-----------|-----------|-------------|
| `:database:impl` | **KEEP** | **KEEP** | Room |
| `:database:persistence` | **KEEP** | **KEEP** | `PersistenceLayer` |
| `:implementation` | **KEEP** | **KEEP** | CommandQueue, PumpSync, ProfileFunction, KeepAlive, PluginStore… |
| `:shared:impl` | **KEEP** | **KEEP** | Logger, RxBus |
| `:shared:tests` | OPT | OPT | Tests uniquement |
| `:workflow` | **KEEP** | **KEEP** | Chaîne calcul IOB/COB / post-BG ; B déclenche Loop |

### 5.4 Plugins fonctionnels

| Module | Produit A | Produit B | Commentaire |
|--------|-----------|-----------|-------------|
| `:plugins:main` | **KEEP** | **KEEP** | Overview, IobCob, notification persistante |
| `:plugins:source` | **KEEP** | **KEEP** | Tous les BgSource (trim possible des sources inutiles) |
| `:plugins:eversense` | OPT | OPT | Uniquement si Eversense natif |
| `:plugins:dexcom_oneplus` | OPT | OPT | Uniquement si Dexcom OnePlus |
| `:plugins:libkeks` | OPT | OPT | Crypto support sources dérivées xDrip |
| `:plugins:sync` | **KEEP-MIN** | **KEEP-MIN** | NS + Tidepool ; trim SMS / Wear / Garmin / OpenHumans / Tizen |
| `:plugins:constraints` | **KEEP-MIN** | **KEEP** | Safety obligatoire ; Objectives/Signature surtout B (`@APS`) |
| `:plugins:configuration` | **KEEP** | **KEEP** | Setup wizard (`swDefinitionPumpControl` existe) |
| `:plugins:sensitivity` | **KEEP** | **KEEP** | Requis par IOB/COB même sans boucle |
| `:plugins:smoothing` | **KEEP** | **KEEP** | Chaîne BG (au moins NoSmoothing) |
| `:plugins:calibration` | **KEEP** | **KEEP** | Au moins NoCalibration |
| `:plugins:automation` | **DROP** / OPT | OPT | Pas un PluginBase ; runtime toujours sur classpath aujourd’hui |
| `:plugins:aps` | **STUB** / **DROP*** | **KEEP-MIN** | *A : retirer après stub Loop/deviceStatus + découplage sync. B : AIMI + Loop ; DROP AMA/SMB/AutoISF/Autotune |

### 5.5 Pompes

| Module | Produit A | Produit B | Commentaire |
|--------|-----------|-----------|-------------|
| `:pump:virtual` | **KEEP** | **KEEP** | Toujours utile (dev / fallback) |
| `:pump:common` | **KEEP** | **KEEP** | Si ≥1 driver réel |
| `:pump:rileylink` | OPT | OPT | Medtronic / Omnipod Eros |
| `:pump:omnipod:common` | OPT | OPT | Si Eros ou Dash |
| `:pump:danar` | OPT | OPT | Sélection utilisateur |
| `:pump:dana` | OPT | OPT | Partagé Dana |
| `:pump:danars` | OPT | OPT | |
| `:pump:insight` | OPT | OPT | |
| `:pump:combov2` | OPT | OPT | + transitive `:pump:combov2:comboctl` |
| `:pump:omnipod:eros` | OPT | OPT | |
| `:pump:omnipod:dash` | OPT | OPT | |
| `:pump:medtronic` | OPT | OPT | |
| `:pump:diaconn` | OPT | OPT | |
| `:pump:eopatch` | OPT | OPT | |
| `:pump:medtrum` | OPT | OPT | |
| `:pump:equil` | OPT | OPT | |
| `*-emulator` | **DROP** | OPT | Hors prod |
| `:pump:apex` | **DROP** | **DROP** | Dans settings, **non branché** dans `:app` |

**Règle pratique :** pour un APK ciblé, ne garder que les drivers réellement supportés + dépendances (`common`, `rileylink`, `omnipod/common`, `dana`).

---

## 6. Plugins Hilt — rétention fine

Logique actuelle (`AppModule.providesPlugins` + `ConfigImpl`) :

- toujours : `@AllConfigs`
- `full` \| `pumpcontrol` : `+= @PumpDriver`
- `full` seulement : `+= @APS`
- non-client : `+= @NotNSClient`

### 6.1 À conserver (Produit A et B)

| IntKey | Plugin | Type map | Rôle |
|--------|--------|----------|------|
| 0 | `PersistentNotificationPlugin` | AllConfigs | Service / notif persistante |
| 10 | `IobCobCalculatorPlugin` | AllConfigs | IOB/COB (UI, NS, wizard) |
| 20 | `OverviewPlugin` | AllConfigs | Écran principal |
| 100–120 | Sensitivity (AAPS / WA / Oref1) | AllConfigs | Au moins **un** actif |
| 310 | `NSClientV3Plugin` | AllConfigs | Nightscout |
| 400–550 | BgSource (sélection) | AllConfigs | Au moins **un** capteur utile |
| 600–630 | Smoothing | AllConfigs | Au moins NoSmoothing |
| 700–710 | Calibration | AllConfigs | Au moins NoCalibration |
| 800 | `SafetyPlugin` | AllConfigs | Contraintes bolus / basal |
| 850 | `DstHelperPlugin` | AllConfigs | DST |
| 860 | `BgQualityCheckPlugin` | AllConfigs | Qualité BG |
| 1000 | `VirtualPumpPlugin` | AllConfigs | Pompe virtuelle |
| 1010–1130 | Drivers `@PumpDriver` (sélection) | PumpDriver | Pompes réelles |
| 320 | `TidepoolPlugin` | NotNSClient | Tidepool |
| 810 | `VersionCheckerPlugin` | NotNSClient | Optionnel mais présent |

### 6.2 Produit A — DROP ou désactiver

| IntKey | Plugin | Action |
|--------|--------|--------|
| 200 | `LoopPlugin` | **STUB** deviceStatus **ou** DROP module après extraction publisher |
| 210–240 | AMA / SMB / AIMI / AutoISF / Autotune | **DROP** (aujourd’hui `@AllConfigs` → à passer hors map A) |
| 225 | `OpenAPSAIMIPlugin` | **DROP** |
| 315 | `RemoteControlPlugin` | **DROP** ou découpler (dépend `ContextManager` AIMI) |
| 300 | `SmsCommunicatorPlugin` | **DROP** MVP |
| 330–370 | Xdrip upload / Wear / Tizen / Garmin | **DROP** MVP (sauf besoin explicite) |
| 340 | `OpenHumansUploaderPlugin` | **DROP** |
| 820–840 | Storage / Signature / Objectives | **DROP** (déjà hors pumpcontrol via `@APS`) |

### 6.3 Produit B — KEEP additionnel + trim APS

| IntKey | Plugin | Action |
|--------|--------|--------|
| 200 | `LoopPlugin` | **KEEP** |
| 225 | `OpenAPSAIMIPlugin` | **KEEP** (seul APS) |
| 210 / 220 / 230 / 240 | AMA / SMB / AutoISF / Autotune | **DROP** de la map (idéalement annoter `@APS` puis ne pas les binder) |
| 315 | `RemoteControlPlugin` | **KEEP** si remote AIMI voulu ; sinon DROP |
| 820–840 | Storage / Signature / Objectives | **KEEP** ou KEEP-MIN selon politique sécurité / onboarding |

### 6.4 Capteurs — matrice de trim (Produit A/B)

Tout est `@AllConfigs` aujourd’hui. Pour un APK mince, ne **compiler** (ou n’enregistrer) que les sources voulues :

| Priorité typique | Plugins |
|------------------|---------|
| **KEEP fréquents** | `XdripSourcePlugin`, `DexcomPlugin`, `NSClientSourcePlugin`, `DexcomOnePlusPlugin`, `EversensePlugin` |
| **OPT** | Aidex, Ottai, Glimp, Tomato, Glunovo, Intelligo, Syai, Poctech, MM640g, Patched*, Instara, NotificationReader |
| **DROP prod** | `RandomBgPlugin` (sauf debug) |

---

## 7. Glue `:implementation` — à conserver

Ces classes sont le « système d’exploitation » AAPS. **Ne pas jeter** pour A ni B.

| Domaine | Classes clés | Produit A | Produit B |
|---------|--------------|-----------|-----------|
| File pompe | `CommandQueueImplementation`, `QueueWorker`, `Command*` | KEEP | KEEP |
| Sync pompe→DB | `PumpSyncImplementation`, storages bolus/TBR | KEEP | KEEP |
| Profils | `ProfileFunctionImpl`, `ProfileUtilImpl`, `ProfileRepositoryImpl` | KEEP | KEEP |
| Plugins actifs | `PluginStore` (`ActivePlugin`) | KEEP | KEEP |
| Keepalive | `KeepAliveWorker`, receivers BT/réseau/charge | KEEP | KEEP |
| Bolus UI | `WizardBolusExecutorImpl`, `WizardExecutorImpl` | KEEP | KEEP |
| Insuline | `InsulinImpl`, `ConcentrationHelperImpl` | KEEP | KEEP |
| Glucose status | `GlucoseStatusProviderImpl` | KEEP | KEEP |
| Prefs / sécurité | `PreferencesImpl`, `ProtectionCheckImpl`, `SecureEncryptImpl` | KEEP | KEEP |
| Maintenance | `ImportExportPrefsImpl`, `FileListProviderImpl` | KEEP | KEEP |
| Notifs / alertes | `NotificationHolderImpl`, `LocalAlertUtilsImpl` | KEEP | KEEP |
| Device status pompe | `PumpStatusProviderImpl` | KEEP | KEEP |
| APS result types | `DetermineBasalResult` | STUB / allégé | KEEP |

---

## 8. Contrats `core:interfaces` critiques

À conserver (interfaces + au moins une impl) :

| Interface | Module typique d’impl | Notes |
|-----------|----------------------|-------|
| `Pump` / `PumpSync` / `CommandQueue` | pump:* / implementation | Cœur hardware |
| `BgSource` | plugins:source | Capteur |
| `NsClient` / `DataSyncSelector` | plugins:sync | Nightscout |
| `Tidepool` | plugins:sync | Tidepool |
| `PersistenceLayer` | database:persistence | DB |
| `ActivePlugin` | PluginStore | Sélection plugins |
| `ProfileFunction` / `ProfileUtil` | implementation | Profils |
| `IobCobCalculator` | plugins:main | Calculs |
| `ConstraintsChecker` / `PluginConstraints` | plugins:constraints | Sécurité |
| `Preferences` / `SP` | implementation | Config |
| `RxBus` | shared:impl | Events |
| `Loop` | plugins:aps | **B : réel ; A : stub deviceStatus** |
| `APS` | plugins:aps | **B : AIMI ; A : absent ou stub inerte** |
| `Config` | ConfigImpl | Flavors |

---

## 9. Ce qu’on croit pouvoir retirer mais qui **bloque**

| Élément | Pourquoi on croit le jeter | Pourquoi le garder (ou stubber) |
|---------|----------------------------|----------------------------------|
| **Profils** | « Pas d’APS » | Pompe (set profile / basal) + Tidepool (reconstruction basal) + wizard |
| **IOB/COB + sensitivity** | « Pas de boucle » | Overview, wizard, NS, sécurité manuelle |
| **Loop** | « Pas d’APS » | Seul producteur riche de `DeviceStatus` pour NS ; KeepAlive / PostCalculation y appellent |
| **`:plugins:aps` entier** | « Produit A » | Aujourd’hui `sync` importe `ContextManager` AIMI ; Hilt bind `Loop` |
| **Constraints Safety** | « Manuel simple » | Bolus wizard / limites hard — non négociable médicalement |
| **Overview + `:ui`** | « App mince » | Sans UI traitements, l’app n’est pas utilisable |

---

## 10. Découplages préalables (avant DROP réel)

Sans ces travaux, retirer `:plugins:aps` **ne compile pas**.

### 10.1 Couper `plugins/sync` → `plugins/aps`

Fichiers concernés (liste non exhaustive) :

- `plugins/sync/.../nsclientV3/RemoteControlFragment.kt` → `ContextManager`
- `plugins/sync/.../nsclientV3/NsIncomingDataProcessor.kt` → `ContextManager`

**Actions possibles :**

1. Extraire une interface `RemoteActivityIntentHandler` dans `:core:interfaces` ;
2. Impl AIMI dans `:plugins:aps` ;
3. No-op dans Produit A ;
4. Retirer `implementation(project(":plugins:aps"))` de `:plugins:sync`.

### 10.2 Extraire le producteur `DeviceStatus`

Aujourd’hui : `LoopPlugin.buildAndStoreDeviceStatus` → `PersistenceLayer.insertDeviceStatus`.

**Produit A :** créer `DeviceStatusPublisher` (pump JSON + batterie + openaps vide) appelé par `KeepAliveWorker`, sans `APS.invoke`.

### 10.3 Assouplir `ActivePlugin` / maps Hilt

- Passer AMA/SMB/AIMI/AutoISF de `@AllConfigs` → `@APS` (aligné mental model flavors).
- Produit A : ne pas inclure la map `@APS` (déjà le cas `pumpcontrol` pour Loop) **et** ne plus enregistrer les APS en AllConfigs.
- Garantir un default Sensitivity / Smoothing / Calibration / BgSource / Pump.

### 10.4 Trim Gradle `:app`

- Ne plus `implementation` tous les `:plugins:*` aveuglément.
- `pumpcontrolImplementation` / nouveau flavor : liste blanche pumps + sources + sync allégé.
- Exclure `:plugins:aps` du Produit A une fois 10.1–10.3 faits.

---

## 11. Contenu AIMI à conserver (Produit B uniquement)

Package : `app.aaps.plugins.aps.openAPSAIMI` (~429 `.kt`).

### 11.1 KEEP (cœur dosing)

| Zone | Rôle |
|------|------|
| `OpenAPSAIMIPlugin` | Entrée APS |
| `DetermineBasalAIMI2` / `DetermineBasalCoordinator` | Décision basale / SMB |
| `safety/` | Garde-fous algorithmiques |
| `smb/` | Domaine SMB |
| `pkpd/`, `prediction/`, `trajectory/` | Prédiction / PKPD |
| `context/`, `patient/` | Contexte patient |
| `basal/`, `control/` | Politique basale |

### 11.2 KEEP-MIN / OPT (fork intelligence)

| Zone | Notes |
|------|-------|
| `ml/`, `learning/`, `aimiNeuralNetwork` | TFLite / ONNX — ASYNC IMPACT |
| `autodrive/`, `physio/`, `wcycle/`, `hormonitor/` | Engines async / physio |
| `advisor/`, `llm/`, `orchestration/`, `tpo/` | Advisor / orchestration |
| `compose/` | UI AIMI dédiée |
| `steps/`, `activity/` | Health Connect / steps |

### 11.3 DROP (même en Produit B)

| Zone | Notes |
|------|-------|
| Autres APS du module | `OpenAPSAMAPlugin`, `OpenAPSSMBPlugin`, `OpenAPSAutoISFPlugin`, `AutotunePlugin` |
| Emulators pumps | hors prod |
| Sync extras | SMS, OpenHumans, Tizen, Garmin (sauf besoin) |

⚠️ **ASYNC IMPACT (Produit B) :** Autodrive, SMB trainer, physio, auditor, TPO, Workflow et `CommandQueue` partagent la file pompe et le déclenchement post-BG. Ne pas « simplifier » ces chemins sans revue async dédiée.

---

## 12. UI minimale à conserver

| Écran / capacité | Produit A | Produit B |
|------------------|-----------|-----------|
| Overview (BG, IOB, pompe, graphe) | KEEP | KEEP |
| Entrée bolus / glucides (wizard) | KEEP | KEEP |
| Profils (édition / switch) | KEEP | KEEP |
| Config pompe (pair / status) | KEEP | KEEP |
| Config source CGM | KEEP | KEEP |
| Prefs NS + Tidepool (auth) | KEEP | KEEP |
| Setup wizard | KEEP (`pumpcontrol`) | KEEP (`full`) |
| Écrans AIMI / advisor / physio | DROP | KEEP-MIN |
| Objectives / autotune UI | DROP | OPT |

---

## 13. Set minimal recommandé (listes blanches)

### 13.1 Produit A — MVP connectivité

```text
:app (flavor type pumpcontrol)
:ui
:core:data :core:interfaces :core:keys :core:objects :core:utils
:core:ui :core:nssdk :core:libraries :core:graph :core:graphview
:database:impl :database:persistence
:implementation :shared:impl :workflow
:plugins:main :plugins:configuration :plugins:constraints
:plugins:sensitivity :plugins:smoothing :plugins:calibration
:plugins:source (+ eversense/dexcom_oneplus/libkeks SI besoin)
:plugins:sync (NS + Tidepool seulement, après découplage APS)
:pump:virtual + drivers choisis (+ common/rileylink/omnipod/common/dana SI besoin)

STUB: Loop/DeviceStatusPublisher (sans :plugins:aps AIMI)
DROP: :plugins:aps (après découplage), automation (si possible), wear, benchmark
```

### 13.2 Produit B — MVP AIMI-only

```text
Tout le set A
+ :plugins:aps avec UNIQUEMENT:
    - LoopPlugin
    - OpenAPSAIMIPlugin
    - (optionnel) contraintes @APS Storage/Signature/Objectives
+ flavor config.APS = true
+ deps natives AIMI (TFLite / ONNX / Health Connect) déjà dans plugins/aps/build.gradle.kts
```

---

## 14. Plan de rétention par phases

| Phase | Objectif | Livrable | Risque |
|-------|----------|----------|--------|
| **0** | Clarifier produit A vs B | Décision produit | — |
| **1** | Baseline `pumpcontrol` buildable | APK sans Loop enregistré | Faible |
| **2** | Découpler sync ↔ aps | `:plugins:sync` sans dep `:plugins:aps` | Moyen |
| **3** | DeviceStatus sans Loop | `DeviceStatusPublisher` | Moyen |
| **4** | APS hors `@AllConfigs` | AMA/SMB/AIMI/AutoISF `@APS` only | Moyen |
| **5a** | Produit A : exclure `:plugins:aps` | APK connectivité | Moyen |
| **5b** | Produit B : map AIMI-only | APK boucle AIMI | Élevé (async/ML) |
| **6** | Trim pumps/sources/sync | APK size / surface | Faible–moyen |
| **7** | (Optionnel) nouvel `:app` | Branding / package indépendant | Élevé merge |

---

## 15. Critères d’acceptation (validation)

### Produit A

- [ ] BG arrive depuis au moins une source et s’affiche
- [ ] Bolus / TBR manuel via `CommandQueue` sur pompe réelle ou virtuelle
- [ ] Traitements + BG upload NS
- [ ] deviceStatus NS non vide (au moins pump + batterie)
- [ ] Upload Tidepool (BG + bolus + basal)
- [ ] Aucun `APS.invoke` / aucun SMB automatique
- [ ] Safety constraints toujours appliquées au wizard

### Produit B (en plus)

- [ ] `LoopPlugin` invoque uniquement AIMI
- [ ] Enactment TBR/SMB via queue après contraintes
- [ ] Pas d’AMA/SMB OpenAPS/AutoISF actifs
- [ ] RemoteControl AIMI (si conservé) fonctionnel sans casser NS

---

## 16. Synthèse exécutive

1. **Conserver absolument (A et B)** : core + database + implementation + workflow + main + source + sync(NS/Tidepool) + constraints(Safety) + configuration + sensitivity/smoothing/calibration + pumps choisis + UI + **profils**.
2. **Ne pas se mentir sur AIMI** : le conserver = conserver Loop + APS platform (Produit B).
3. **Le plus gros mensonge d’extraction** : croire qu’on peut jeter profils, IOB, Safety ou PersistenceLayer.
4. **Le plus gros bloqueur technique** : `plugins/sync` → `plugins/aps` + deviceStatus dans Loop.
5. **Chemin recommandé** : phases 0→4 puis **5a** (connectivité) ; n’ouvrir **5b** (AIMI-only) que si le produit est bien une boucle, pas une « app légère ».

---

## 17. Annexes

### 17.1 Flavors `ConfigImpl` (rappel)

| Flavor | `APS` | `PUMPCONTROL` | `PUMPDRIVERS` | `AAPSCLIENT` |
|--------|-------|---------------|---------------|--------------|
| `full` | true | false | true | false |
| `pumpcontrol` | false | true | true | false |
| `aapsclient*` | false | false | false | true |

### 17.2 Références code

- `app/src/main/kotlin/app/aaps/implementations/ConfigImpl.kt`
- `app/src/main/kotlin/app/aaps/di/AppModule.kt` (`providesPlugins`)
- `plugins/aps/.../ApsPluginsListModule.kt`
- `plugins/sync/.../SyncPluginsListModule.kt`
- `plugins/source/.../SourcePluginsListModule.kt`
- `plugins/aps/.../openAPSAIMI/OpenAPSAIMIPlugin.kt`
- `settings.gradle`, `app/build.gradle.kts`

### 17.3 Hors scope de ce document

- Aspects réglementaires / marquage CE / responsabilité médicale
- Licence / redistribution AndroidAPS upstream
- Migration utilisateurs / import prefs depuis AAPS full

---

*Document généré pour cadrage architecture. Aucune garantie de « feature complete » tant que les critères §15 n’ont pas été validés sur device.*
