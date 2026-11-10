# Plan d’exécution multi-agents — Plugin natif Dexcom ONE+

**Statut :** plan d’exécution (pas de code tant que Phase 0 non GO)  
**Date :** 2026-07-18  
**Fiche produit :** [DEXCOM_ONEPLUS_NATIVE_PLUGIN_PRODUCT.md](DEXCOM_ONEPLUS_NATIVE_PLUGIN_PRODUCT.md)  
**Modèle structurel :** Eversense (`:plugins:eversense` + `BgSource`)  
**Règle fork :** NE PAS `cd &&` dans les agents Bash ; confirmation user avant edits code AIMI/async ; pas d’install auto ; anglais strings only  

---

## 1. Vue d’ensemble des agents

| ID | Agent | Mission | Peut coder ? | Dépend de |
|----|-------|---------|--------------|-----------|
| **A0** | Product / Architecture Lead | Figer Q1–Q12, GO/NO-GO, arbitrages | Docs only (sauf spike coord) | — |
| **A1** | Licence & provenance | Audit xDrip/Juggluco, NOTICE, risques GPL | Docs + NOTICE | A0 (Q5/Q6) |
| **A2** | Follower One+ (Phase A) | Tags `SourceSensor`, notif `d1plus`, AIMI fast | Oui (faible risque) | A0 soft (peut démarrer tôt) |
| **A3** | Spike BLE | Preuve connect + 1 BG + warm-up clock sur 1 device | Oui (branche jetable OK) | A0 Q5 + A1 préliminaire |
| **A4** | Socle Gradle / module | `:plugins:dexcom_oneplus` + DI skeleton | Oui | GO Phase 0 |
| **A5** | Enums / DB / prefs | `SourceSensor`, converters, keys | Oui | A4 (ou parallèle après nommage) |
| **A6** | Protocole BLE / session | Port Direct, J-PAKE, GATT, reconnect | Oui (cœur) | A3 GO + A4 |
| **A7** | Ingest BgSource | Watcher → PersistenceLayer | Oui | A5 + A6 partiel |
| **A8** | UX init / warm-up 30 min | Activities Compose/Views, countdown | Oui | A6 états session |
| **A9** | OEM profiles | DeviceProfileRegistry + checklist | Oui | A6 + Q9 |
| **A10** | AIMI / GlucoseStatus | sourceSensor wire, no G6 lead | Oui | A5 + A7 |
| **A11** | Tests & QA device | Unit + matrices 24 h Q9 | Tests + scripts | A6–A9 |
| **A12** | Docs merge / user | MERGE_CONSTRAINT, guide, checklist | Docs | A4+ (maj continue) |
| **A13** | Integration Captain | Intégration branches, non-régression BYODA/Eversense | Oui (intégration) | Tous lots prêts |

**Principe :** un agent = un lot avec **entrée / sortie / DoD** ; pas de « faire tout le plugin ».

---

## 2. Gantt logique (dépendances)

```
A0 (Q1–Q12)
 ├── A1 licence ──────────────┐
 ├── A2 Phase A (parallèle, tout de suite) 
 └── A3 spike BLE ◄── A1 OK préliminaire
         │
      GO / NO-GO
         │
      ┌──┴──────────────────┐
      A4 module          A5 enums/DB/prefs
      └──┬──────────────────┘
         │
         A6 protocole BLE (long)
         │
    ┌────┼────────────┐
    A7 ingest     A8 UX warm-up
    │             A9 OEM
    └────┬────────────┘
         A10 AIMI
         A11 QA
         A12 docs (continu)
         A13 intégration finale
```

---

## 3. Lots détaillés par agent

### A0 — Product / Architecture Lead

**Objectif :** décisions figées + arbitrage continu.

| Étape | Action détaillée | Livrable |
|-------|------------------|----------|
| A0.1 | Remplir tableau §0 fiche produit (Q1–Q12) avec dates | Cases cochées |
| A0.2 | Écrire « Definition of Ready » pour GO | Paragraphe GO |
| A0.3 | Nommer module (`:plugins:dexcom_oneplus` vs autre) + `@IntKey` libre | Décision écrite |
| A0.4 | Valider promesse start natif + warm-up UI 30 min (Q11=A/C, Q12=A) | Confirmé |
| A0.5 | Après A3 : GO / NO-GO écrit | Journal fiche §15 |
| A0.6 | Suivi hebdo des agents (bloquants) | Notes courtes |

**DoD A0 :** Q1–Q12 non vides + GO/NO-GO explicite.  
**Interdit :** coder le BLE.

---

### A1 — Licence & provenance

**Objectif :** pouvoir porter du code sans explosion juridique.

| Étape | Action détaillée | Livrable |
|-------|------------------|----------|
| A1.1 | Identifier dépôt/commit source (xDrip Direct / Juggluco) selon Q5 | URL + hash |
| A1.2 | Lire LICENSE (GPL-3, etc.) et implications pour le fork AAPS | Memo 1–2 pages |
| A1.3 | Lister fichiers à porter vs réécrire (API surface) | Tableau fichiers |
| A1.4 | Draft `NOTICE` / `THIRD_PARTY` | Fichier draft |
| A1.5 | Recommandation : port OK / wrapper / abandon | Verdict |

**DoD A1 :** A0 peut signer Q6.  
**Bloque :** A6 (port massif) si KO.

---

### A2 — Phase A follower (valeur immédiate, parallèle)

**Objectif :** One+ correctement taggé **sans** BLE natif.

| Étape | Action détaillée | Fichiers probables | Livrable |
|-------|------------------|--------------------|----------|
| A2.1 | Ajouter `SourceSensor.DEXCOM_ONEPLUS_*` (nom figé avec A0/A5) | `core/data/.../SourceSensor.kt` | Enum |
| A2.2 | Converters DB + tests | `database/**` | Tests verts |
| A2.3 | Mapper `com.dexcom.d1plus` (et `dexcomone` si besoin) | `notification_reader_packages.json` | Mapping |
| A2.4 | AIMI : fast sensor pour ce tag (pas lead G6) | `DetermineBasalAIMI2`, estimateur | Log `One+/…` cohérent |
| A2.5 | Propager `sourceSensor` dans `GlucoseStatusAIMI` si gap | `GlucoseStatusCalculatorAimi` | Champ non null quand connu |
| A2.6 | Doc courte « One+ aujourd’hui = xDrip Direct » | `docs/` | Doc |

**DoD A2 :** JSONL/log montre One+ distinct de `NATIVE_UNKNOWN` ; tests converters OK.  
**⚠️ ASYNC :** aucun.  
**Ne touche pas :** module BLE natif.

---

### A3 — Spike BLE (go/no-go technique)

**Objectif :** prouver sur **1 téléphone Q9** : pair code → session → warm-up clock → ≥1 BG.

| Étape | Action détaillée | Livrable |
|-------|------------------|----------|
| A3.1 | Branche jetable `spike/oneplus-ble` | Branche |
| A3.2 | Minimal activity / service de test **ou** app debug dans module | APK debug |
| A3.3 | Porter le **minimum** session Direct (scan+pair+notify) | Code spike |
| A3.4 | Logger remaining warm-up si exposé par protocole | Mesure |
| A3.5 | Capture : countdown ≈30 min puis 1ʳᵉ BG | Rapport + logcat |
| A3.6 | Rapport risques OEM / master BLE / licence runtime | `docs/spikes/ONEPLUS_BLE_SPIKE.md` |
| A3.7 | Recommandation GO/NO-GO à A0 | 1 page |

**DoD A3 :** preuve device **ou** échec documenté avec cause.  
**Règle :** ne pas merger le spike tel quel dans `dev` ; A6 reprend proprement.

---

### A4 — Socle module Gradle / DI

**Objectif :** squelette compilant, vide fonctionnellement.

| Étape | Action détaillée | Fichiers |
|-------|------------------|----------|
| A4.1 | `include` module dans `settings.gradle` | settings |
| A4.2 | `build.gradle.kts` module (deps `:core:interfaces` only au début) | gradle |
| A4.3 | Package namespace + `AndroidManifest` module si besoin | manifest |
| A4.4 | Interfaces `OnePlusCgmDriver`, `OnePlusGlucoseWatcher` | api kotlin |
| A4.5 | Stub singleton driver (no-op) | driver stub |
| A4.6 | Wiring app auto `:plugins:*` vérifié | compile |
| A4.7 | `MERGE_CONSTRAINT_DEXCOM_ONEPLUS.md` v0 | docs |

**DoD A4 :** `./gradlew :plugins:dexcom_oneplus:compileFullDebugKotlin` OK (ou flavor Q7).

---

### A5 — Enums, DB, prefs, registration BgSource

**Objectif :** le plugin apparaît dans Config Builder (même stub).

| Étape | Action détaillée | Fichiers |
|-------|------------------|----------|
| A5.1 | `SourceSensor.DEXCOM_ONEPLUS_NATIVE` (+ Sources) | core/data |
| A5.2 | Converters persistence + tests | database |
| A5.3 | Prefs `BooleanKey` / `StringKey` | core/keys |
| A5.4 | Strings EN plugin description | source res |
| A5.5 | `DexcomOnePlusPlugin` extends AbstractBgSourcePlugin | source |
| A5.6 | `@IntKey(N)` dans `SourcePluginsListModule` — **≠ 440/445** | DI |
| A5.7 | Activities permission stub dans `SourceModule` | DI |
| A5.8 | Ne pas casser `DexcomPlugin` BYODA | vérif |

**DoD A5 :** plugin listé ; enable/disable sans crash ; tests converters verts.  
**Coord A2 :** si A2 a déjà un enum « notif », A5 aligne les noms (éviter doublons).

---

### A6 — Protocole BLE / session (lot le plus long)

**Objectif :** session native production-ready (hors UI polish).

| Étape | Action détaillée | Sortie |
|-------|------------------|--------|
| A6.1 | Cartographier code source (xDrip/Juggluco) → packages cibles | Map port |
| A6.2 | Scanner + filtre ADV One+/G7 | `Scanner` |
| A6.3 | Connexion GATT + discovery | `GattCallback` |
| A6.4 | Auth / J-PAKE / session keys | `SessionAuth` |
| A6.5 | Start session depuis **pairing code** (Q11 A) | `SessionStart` |
| A6.6 | Lire / estimer **warm-up remaining** (pour UI 30 min) | API `WarmupState` |
| A6.7 | Subscribe glucose + parse + bornes 20–600 | `GlucoseParser` |
| A6.8 | Backfill court | `Backfill` |
| A6.9 | Reconnect + backoff + persist identity | `ReconnectPolicy` |
| A6.10 | Stop propre on plugin disable | `shutdown()` |
| A6.11 | Markers log `DEXCOM_ONEPLUS_*` | logs |
| A6.12 | Unit tests paquets/crypto mockables | tests |
| A6.13 | Intégration driver ↔ watcher callbacks | API stable pour A7/A8 |

**DoD A6 :** sur device Q9 — start code → warm-up state → BG sans xDrip.  
**⚠️ ASYNC :** callbacks BLE ; documenter thread (executor) ; pas de coroutines dose AIMI.  
**Interdit :** UI Compose complexe (A8) ; logique AIMI (A10).

**API minimale à publier pour les autres agents :**

```text
WarmupState { phase: WARMING|READY|FAILED; remainingMs: Long?; endsAtEpochMs: Long? }
GlucoseSample { mgdl; timestampMs; trend?; sequence? }
DriverEvents: onWarmup, onGlucose, onSession, onError
```

---

### A7 — Ingest BgSource → PersistenceLayer

**Objectif :** les BG arrivent dans le store AAPS.

| Étape | Action détaillée |
|-------|------------------|
| A7.1 | Implémenter watcher → `GV` + `SourceSensor.DEXCOM_ONEPLUS_NATIVE` |
| A7.2 | `insertCgmSourceData` / pattern EversensePlugin |
| A7.3 | Battery level si dispo (`sensorBatteryLevel`) |
| A7.4 | Ignorer BG pendant warm-up (pas de placeholder loop) |
| A7.5 | Dédup timestamps / out-of-order |
| A7.6 | Tests unitaires mapping GV |

**DoD A7 :** BG visibles overview AAPS après warm-up ; UE source correcte.

---

### A8 — UX init + warm-up 30 minutes

**Objectif :** parcours utilisateur F2/F2b/F2c.

| Étape | Action détaillée |
|-------|------------------|
| A8.1 | Écran permissions (Bluetooth, notif si FGS) |
| A8.2 | Écran scan + liste devices |
| A8.3 | Saisie code capteur (validation format) |
| A8.4 | **Écran warm-up** : countdown, fin estimée, état BLE, copy « pas de BG boucle » |
| A8.5 | Bind `WarmupState.remainingMs` (fallback timer local **uniquement** si protocole n’expose pas remaining — documenter) |
| A8.6 | Transition auto `WARMUP_DONE` → statut « Active » |
| A8.7 | Écran statut quotidien (dernière BG, âge, reconnect) |
| A8.8 | Flow « nouveau capteur » |
| A8.9 | Message conflit master BLE (F11) |
| A8.10 | Strings EN + `comment=` si placeholders |
| A8.11 | Theme AAPS Compose (pas d’attrs Android hardcodés) |

**DoD A8 :** captures parcours complet ; countdown visible ~30 min ; D1b.  
**Dépend :** A6.6 `WarmupState`.

---

### A9 — OEM profiles & checklist

**Objectif :** réduire les « params téléphone » à des profils.

| Étape | Action détaillée |
|-------|------------------|
| A9.1 | Détection `Build.MANUFACTURER` / `MODEL` |
| A9.2 | Profils pour chaque entrée Q9 + `GenericFallback` |
| A9.3 | Paramètres : connect timeout, retry, MTU, FGS oui/non |
| A9.4 | Écran checklist premier lancement (batterie non restreinte, etc.) |
| A9.5 | Pref override debug `OemProfile` |
| A9.6 | Marker `DEXCOM_ONEPLUS_OEM_PROFILE` |
| A9.7 | Doc troubleshooting par OEM |

**DoD A9 :** 2 téléphones Q9 passent 24 h lab sans toggle manuel exotique.

---

### A10 — AIMI / GlucoseStatus

**Objectif :** la boucle traite One+ natif correctement.

| Étape | Action détaillée |
|-------|------------------|
| A10.1 | Vérifier chaîne bucketed ads → `GlucoseStatusAIMI.sourceSensor` |
| A10.2 | Fast path One+ (pas `+30%` lead G6) |
| A10.3 | JSONL `cgm_source=DEXCOM_ONEPLUS_NATIVE` |
| A10.4 | Non-régression : G6 BYODA lead **inchangé** |
| A10.5 | Trous CGM : comportement existant (pas de nouveau kill cascade) |
| A10.6 | Tests unitaires estimateur / branches sensor si purs |

**DoD A10 :** log AIMI distingue One+ natif ; G6 inchangé.  
**⚠️ ASYNC IMPACT :** uniquement si touche flows AIMI — signaler ; préférer lecture sync du `sourceSensor`.

---

### A11 — Tests & QA device

**Objectif :** preuves §9 fiche produit.

| Étape | Action détaillée |
|-------|------------------|
| A11.1 | Suite unit `:plugins:dexcom_oneplus:testFullDebugUnitTest` |
| A11.2 | Tests converters / plugin registration |
| A11.3 | Matrice device Q9 (24 h) : uptime BG %, reconnect airplane |
| A11.4 | Cas : disable plugin mid-session → GATT down |
| A11.5 | Cas : start nouveau capteur |
| A11.6 | Non-régression Eversense smoke + BYODA G6/G7 si dispos |
| A11.7 | Checklist sign-off user (pas « fully working » sans confirm) |

**DoD A11 :** tableau résultats + D1–D8 cochés user.

---

### A12 — Documentation

**Objectif :** exploitabilité + merges futurs.

| Étape | Action détaillée |
|-------|------------------|
| A12.1 | Finaliser `MERGE_CONSTRAINT_DEXCOM_ONEPLUS.md` |
| A12.2 | Guide user EN (pair, warm-up 30 min, OEM, troubleshooting) |
| A12.3 | Update `NON_REGRESSION_CHECKLIST.md` |
| A12.4 | Pointer depuis fiche produit § journal |
| A12.5 | NOTICE / THIRD_PARTY (avec A1) |
| A12.6 | Changelog fork AIMI |

**DoD A12 :** un nouvel arrivant peut pairer sans lire le code.

---

### A13 — Integration Captain

**Objectif :** une branche intégrée, compilable, sans régressions.

| Étape | Action détaillée |
|-------|------------------|
| A13.1 | Merger lots A4–A10 dans branche feature | 
| A13.2 | Résoudre conflits DI / enums / strings |
| A13.3 | Compile full + tests agrégés |
| A13.4 | Vérifier IntKeys uniques |
| A13.5 | Smoke script markers logcat |
| A13.6 | Préparer notes pour user install (user lance install) |
| A13.7 | Gate final avec A0 + A11 |

**DoD A13 :** branche prête pour validation device user (D5-like).

---

## 4. Ordre de lancement recommandé (sprints)

### Sprint 0 — Cadrage (série)

1. **A0** Q1–Q12  
2. **A1** + **A2** en parallèle  
3. **A3** spike  
4. **A0** GO/NO-GO  

Si NO-GO → **stop natif** ; garder A2 + xDrip Direct.

### Sprint 1 — Socle (parallèle après GO)

- **A4** + **A5** + **A12.1** ensemble  

### Sprint 2 — Radio (série dominante)

- **A6** (critique path)  
- **A12** docs au fil de l’eau  

### Sprint 3 — Produit (parallèle)

- **A7** + **A8** + **A9** dès que `WarmupState` + glucose callbacks stables  

### Sprint 4 — Boucle & preuve

- **A10** puis **A11** puis **A13**  

---

## 5. Contrats entre agents (anti-collision)

| Ressource | Owner | Autres |
|-----------|-------|--------|
| `:plugins:dexcom_oneplus` protocole | **A6** | A4 crée squelette ; A7/A8 consomment API |
| `DexcomOnePlusPlugin` / activities | **A7/A8** | A5 crée stub |
| `SourceSensor` enums | **A5** (coord A2) | Personne d’autre n’invente de nom |
| AIMI `DetermineBasalAIMI2` | **A10** | A2 peut faire Phase A d’abord ; A10 rebase |
| BYODA `DexcomPlugin` | **Personne** (touch only si bug) | — |
| Eversense | **Personne** | A11 smoke only |
| Docs merge constraint | **A12** | A4 seed v0 |

**Règle PR :** 1 agent = 1 branche `feature/oneplus-aN-*` ; A13 intègre.

---

## 6. Prompts d’invocation (modèles)

Utiliser tels quels (ou via Task) après GO.

### A2 (exemple)

```text
Lot A2 Phase A One+ follower — fiche docs/DEXCOM_ONEPLUS_NATIVE_PLUGIN_PRODUCT.md
et plan docs/DEXCOM_ONEPLUS_NATIVE_AGENT_PLAN.md §A2.
Ne pas toucher BLE natif. Enums + notification_reader d1plus + AIMI fast path +
GlucoseStatus sourceSensor. Strings EN only. Tests converters. Pas d'install.
```

### A6 (exemple)

```text
Lot A6 protocole BLE One+ — après spike GO. Port selon Q5 figé.
Exposer WarmupState (remainingMs) + GlucoseSample callbacks.
Start session par pairing code (Q11). Markers DEXCOM_ONEPLUS_*.
Ne pas faire l'UI warm-up (A8) ni AIMI (A10). Module :plugins:dexcom_oneplus only.
Bash: never cd &&. Tests unitaires paquets.
```

### A8 (exemple)

```text
Lot A8 UX warm-up 30 min — consommer WarmupState de A6.
Écran countdown + fin estimée + pas de BG loop pendant WARMING.
Theme AAPS Compose, stringResource, EN only.
Ne pas modifier le protocole GATT.
```

---

## 7. Definition of Done globale (rappel)

Alignée fiche §9 + warm-up :

- [ ] Start natif AAPS (code) sans app officielle obligatoire  
- [ ] UI compte à rebours ~30 min puis 1ʳᵉ BG  
- [ ] 24 h lab ≥ 2 phones Q9  
- [ ] AIMI tag native One+  
- [ ] BYODA + Eversense OK  
- [ ] MERGE_CONSTRAINT + NOTICE  
- [ ] **Confirmation user device** — pas de « fully working » sans ça  

---

## 8. Si NO-GO après A3

| Agent | Action |
|-------|--------|
| A0 | Documenter NO-GO dans fiche §15 |
| A2 | **Continuer / finir** Phase A |
| A4–A11 | Annulés ou mis en ⏸ |
| A12 | Doc « rester xDrip Direct » |

---

## 9. Journal du plan

| Date | Note |
|------|------|
| 2026-07-18 | Plan multi-agents créé ; lié à la fiche produit |
| 2026-07-18 | Branch `feature/dexcom-oneplus-native` : scaffold A4/A5 + agents A1/A2/A3/A6/A7/A8/A9/A12 lancés ; Q1–Q12 déjà GO |
| 2026-07-18 | A3 : spike package in `docs/spikes/ONEPLUS_BLE_SPIKE.md` — awaits Pixel/Samsung Q9 device proof (no BLE claim) |
| 2026-07-18 | A12 : MERGE_CONSTRAINT polish (IntKey 446 / notif packages) ; `DEXCOM_ONEPLUS_USER_GUIDE.md` ; pointeur NON_REGRESSION |
| 2026-07-18 | A1 relaunch: licence memo + `plugins/dexcom_oneplus/NOTICE` draft — verdict **port OK** (xDrip GPL-3 @ `1e86d9a2a525` / tag `2026.07.15` + in-tree `libkeks`); Q6 signable; A6 may port subset |
| 2026-07-18 | A6 scaffold on disk (agent final notify stalled): `ONEPLUS_BLE_PORT_MAP.md`, `OnePlusCgmDriverReal` + scan/gatt/session/parse/warmup/reconnect stubs; unit tests + `:plugins:dexcom_oneplus` compile OK; **Stub remains default**; real GATT still blocked on A3 device GO |
| 2026-07-18 | A13: `DEXCOM_ONEPLUS_INTEGRATION_NOTES.md` + `DEXCOM_ONEPLUS_QA_MATRIX.md` ; UI/plugin → `OnePlusCgmDrivers.default()` ; port map pin A1 |
| 2026-07-18 | Advance: restore One+ prefs (A7 overwrite fix); pairing invalid-code list + UUIDs from pin; BLE permission helper; eng pref UseRealSkeleton; `ONEPLUS_XDRIP_PIN_INVENTORY.md` |
| 2026-07-18 | Port step: `:plugins:libkeks` (xDrip pin) + `OnePlusGattClientAndroid` + `OnePlusSessionAuthKeks`; Real driver wired; **not device-validated** |
| 2026-07-18 | Scan UI: `OnePlusBleScannerAndroid` (DXC/Dex/FEBC) + Start activity device list → MAC + pair code |
| 2026-07-18 | EGV path: Control indications + EGlucoseTx/Rx parse + CalibrationState→Warmup + onGlucose wire (Ob1 extract; device A3 still required) |

---

**Prochaine action humaine :** preuve device **A3** (spike) → GO/NO-GO A0 ; puis valider A6 sur téléphone Q9.
