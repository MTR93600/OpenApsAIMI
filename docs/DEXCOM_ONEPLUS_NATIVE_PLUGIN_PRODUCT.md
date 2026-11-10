# Fiche produit — Plugin natif Dexcom ONE+ dans OpenApsAIMI / AAPS

**Statut :** fiche produit + scaffold lancé (module / DI / enums ; BLE A6 non porté)  
**Date :** 2026-07-18  
**Auteur contextuel :** analyse architecture fork OpenApsAIMI  
**Décision produit :** Q1–Q12 figés (§0) ; GO Phase 0 2026-07-18  
**Analogie structurelle :** intégration native Eversense (`:plugins:eversense` + `BgSource`) — **pas** le modèle BYODA follower  
**Plan multi-agents :** [DEXCOM_ONEPLUS_NATIVE_AGENT_PLAN.md](DEXCOM_ONEPLUS_NATIVE_AGENT_PLAN.md)  
**Guide user :** [DEXCOM_ONEPLUS_USER_GUIDE.md](DEXCOM_ONEPLUS_USER_GUIDE.md) · **Merge :** [MERGE_CONSTRAINT_DEXCOM_ONEPLUS.md](MERGE_CONSTRAINT_DEXCOM_ONEPLUS.md)  

---

## 0. Décisions produit à figer avant code

Sans ces réponses, ne pas démarrer l’implémentation.

| # | Question | Options | Décision |
|---|----------|---------|----------|
| Q1 | Objectif principal | (A) UX « zéro xDrip » (B) fiabilité > follower (C) AIMI propriétaire du lien BLE (D) combo | **D** (2026-07-18 GO) |
| Q2 | Coexistence app Dexcom officielle | (A) interdite (plugin = seul master BLE) (B) autorisée en companion (fragile) (C) utilisateur choisit | **A** (doc Clarity séparée) |
| Q3 | Coexistence xDrip / Juggluco | (A) mutuellement exclusif (B) fallback follower si plugin OFF | **A** (fallback manuel doc) |
| Q4 | Périmètre capteurs | (A) ONE+ seul (B) famille G7 + ONE+ + Stelo (C) G7/ONE+ d’abord, Stelo plus tard | **A** (ONE+ v1 ; G7 v1.1) |
| Q5 | Source du code BLE | (A) port depuis xDrip Direct (B) port Juggluco (C) RE maison (D) mix A+B | **A** |
| Q6 | Licence / attribution | Acceptation licence du code source (xDrip GPL, etc.) + attribution README | **Oui** (NOTICE obligatoire) |
| Q7 | Flavors | (A) tous flavors (B) `full` seulement (C) opt-in Gradle flag | **A** |
| Q8 | Régions | (A) EU only (B) EU+US (C) pack-aware multi-région | **A** (EU v1) |
| Q9 | Téléphones cibles v1 | Liste OEM/modèles | **Pixel 6/7/8, Samsung S22–S24** |
| Q10 | Upstream Nightscout | (A) fork-only forever (B) PR upstream plus tard (C) module extractable | **A** (fork AIMI) |
| Q11 | Start de session | (A) **natif AAPS** (B) officiel puis reprise (C) A + B dépannage | **C** (A par défaut) |
| Q12 | Affichage warm-up | (A) compte à rebours ~30 min (B) texte seul | **A** |

**GO Phase 0 (2026-07-18) :** développement lancé sur branche `feature/dexcom-oneplus-native`.  
**Spike A3 :** à valider sur device dès qu’un port minimal session existe (ne bloque pas le socle A4/A5/A2).

---

## 1. Contexte et problème

### 1.1 Situation actuelle (ce fork)

| Chemin | ONE+ aujourd’hui | Qualité |
|--------|------------------|---------|
| Native BLE dans AAPS | **Absent** | — |
| BYODA / `DexcomPlugin` | Packs G6/G7 — **pas** `d1plus` / One+ | N/A pour One+ |
| DiaKEM (G7 patché) | Wiki AAPS : **pas One+** ; login Dexcom cassé pour nouvelles installs | Fragile |
| xDrip Direct → source xDrip | **Voie recommandée wiki** | Bonne, mais params OEM/Bluetooth souvent nécessaires |
| Juggluco → broadcast xDrip-like | Supporté wiki | Bonne |
| Notification Reader `com.dexcom.d1plus` | Tag générique `AAPS-Dexcom` / `DEXCOM_NATIVE_UNKNOWN` | Fragile (notif) |
| AIMI « fast sensor » | Branché surtout sur `DEXCOM_G7_NATIVE` | One+ via notif **mal taggé** |

### 1.2 Problème utilisateur

1. Pour One+, la qualité de boucle dépend souvent d’**xDrip** avec des réglages **spécifiques au téléphone** (Direct vs Companion, batterie, Bluetooth OEM).
2. BYODA n’apporte **pas** la couche BLE dans AAPS : c’est une **app Dexcom patchée** qui *broadcast* ; AAPS ne récupère que le flux BG.
3. Il n’existe **pas** de SDK Dexcom officiel pour DIY / AAPS.
4. L’utilisateur veut un parcours **intégré AAPS** (init + connexion + glycémie) sans « deuxième cerveau » xDrip.

### 1.3 Ce que BYODA n’est pas

```
[Capteur] ←BLE/init→ [APK Dexcom patché BYODA/DiaKEM]
                            │ EXTERNAL_BROADCAST
                            ▼
                     [AAPS DexcomPlugin]  ← pas de GATT ici
```

**Interdit de concevoir le plugin comme « import de BYODA ».**  
BYODA = contrat Intent déjà implémenté. La radio reste hors AAPS.

### 1.4 Ce qu’Eversense a prouvé (modèle à reprendre)

- Module driver bas niveau (`:plugins:eversense`)
- Plugin `BgSource` dans `:plugins:source`
- Enums `SourceSensor`, DB, DI, prefs, doc merge constraint
- Pas de SDK vendor : protocole porté / RE
- Watcher/callback plutôt que Service dédié (choix documenté)

---

## 2. Vision produit

### 2.1 Phrase produit

> **Plugin BG source natif Dexcom ONE+ dans AAPS :** l’utilisateur initialise et connecte le capteur **dans AAPS**, reçoit les glycémies en local sans xDrip obligatoire, avec des **defaults sains** et des **profils OEM** pour limiter les réglages téléphone.

### 2.2 Promesses

| Promesse | Non-promesse |
|----------|--------------|
| Init + pair + session BLE **dans** AAPS | Zéro interaction avec les réglages batterie Android |
| Defaults One+ sans 40 toggles xDrip | Compatibilité 100 % de tous les téléphones Android |
| Qualité de lien **au moins** égale à xDrip Direct sur téléphones cibles Q9 | Remplacer Clarity / Share / écosystème Dexcom cloud |
| Fallback documenté si plugin OFF | Coexistence BLE stable avec app Dexcom + xDrip en même temps (sauf Q2/Q3) |
| Intégration AIMI first-class (`SourceSensor` One+) | Merge immédiat dans Nightscout upstream |

### 2.3 Personas

1. **Boucleur One+ fatigué d’xDrip** — veut un seul APK AAPS AIMI.  
2. **Utilisateur Pixel / Samsung cible** — accepte une liste de téléphones supportés v1.  
3. **Utilisateur qui garde Clarity** — doit comprendre Q2 (souvent : pas d’app Dexcom master en parallèle).

---

## 3. Périmètre

### 3.1 In scope (v1)

- Capteur **Dexcom ONE+** (et, si Q4=B/C, alignement protocole **G7** documenté).
- Scan BLE, saisie code de pairing, authentification session (famille J-PAKE / stack portée).
- Connexion persistante + auto-reconnect.
- Ingest glycémie → `PersistenceLayer` via `BgSource`.
- Warm-up / états capteur visibles (au minimum : connecting, warming, active, failed, reconnecting).
- Backfill **court** (fenêtre à définir, ex. dernières 1–3 h) si le protocole source le permet.
- UI AAPS : écran statut, permissions Bluetooth/notif, aide OEM basique.
- Prefs natives (enable plugin, dernier MAC / identity, région si besoin).
- Tests unitaires protocole + smoke device sur téléphones Q9.
- Documentation utilisateur + merge constraint (comme Eversense).
- Tag AIMI `SourceSensor` dédié + branche « fast sensor » (pas de lead G6).

### 3.2 Out of scope (v1)

- Dexcom Share / Clarity API comme source boucle.
- Patch APK Dexcom / DiaKEM / BYODA (hors plugin).
- Companion Bluetooth / snoop du trafic d’une autre app (trop fragile, hors promesse « natif propre »).
- Calibration manuelle type Libre (One+ = factory cal).
- Multi-capteurs simultanés.
- Wear OS comme master BLE.
- Support Stelo sauf si Q4 l’inclut explicitement.
- Remplacer notification reader pour d’autres apps.
- PR Nightscout obligatoire en v1.

### 3.3 In scope différé (v1.1+)

- Profils OEM étendus au-delà de Q9.
- Backfill long / historique capteur complet.
- Alertes capteur riches (pareil G7 app).
- Mode fallback automatique → xDrip Intent si session native down (si Q3=B).
- Extraction module réutilisable hors fork.

---

## 4. Exigences

### 4.1 Fonctionnelles

| ID | Exigence | Priorité |
|----|----------|----------|
| F1 | L’utilisateur peut activer le plugin comme **BG Source** exclusive (Config Builder) | P0 |
| F2 | Flow d’init : permissions → scan → sélection appareil → code pair → **warm-up** → active | P0 |
| F2b | Pendant warm-up : écran dédié avec **compte à rebours ~30 min** (restant), heure de fin estimée, état lien BLE, pas de fausse BG « prête » | P0 |
| F2c | Fin warm-up : transition automatique vers première BG valide + notif / marqueur `DEXCOM_ONEPLUS_WARMUP_DONE` | P0 |
| F3 | Glycémies insérées ≤ ~5 min d’intervalle nominal capteur, horodatage capteur préservé | P0 |
| F4 | Reconnect automatique après coupure BLE / kill process (best effort + log) | P0 |
| F5 | Écran statut : état lien, dernière BG, âge donnée, identité capteur, batterie si dispo | P0 |
| F6 | Désactivation plugin coupe GATT proprement et permet une autre BG source | P0 |
| F7 | `SourceSensor` distinct One+ (et mapping JSONL / AIMI) | P0 |
| F8 | Profils OEM v1 appliqués **sans** UI avancée obligatoire | P1 |
| F9 | Backfill court au (re)connect | P1 |
| F10 | Export diagnostic (log ligne `DEXCOM_ONEPLUS_*`, option share bugreport) | P1 |
| F11 | Message clair si un autre master BLE est détecté / connexion impossible | P1 |
| F12 | Fallback documenté vers xDrip source (manuel v1) | P2 |

### 4.2 Non-fonctionnelles

| ID | Exigence | Cible |
|----|----------|-------|
| NF1 | Latence ingest AAPS après notify BLE | &lt; 15 s p95 sur téléphones Q9 |
| NF2 | Taux de ticks BG valides (hors warm-up / trous capteur) | ≥ 95 % sur 24 h test lab |
| NF3 | Impact batterie additionnel | Documenté ; pas de scan agressif permanent |
| NF4 | Compilation | Module isolé ; pas de nouvelle dep inter-modules lourde sans discussion |
| NF5 | Tests | Unitaires protocole + smoke checklist device |
| NF6 | Merge `dev` | Fichier `MERGE_CONSTRAINT_DEXCOM_ONEPLUS.md` obligatoire |
| NF7 | Licence | Conformité source (GPL xDrip si port) + NOTICE |

### 4.3 Contraintes AIMI / boucle

| ID | Exigence |
|----|----------|
| A1 | Ne jamais faire dépendre la cascade Tree→Harmonia du plugin CGM (BG absente = comportement existant trous CGM) |
| A2 | One+ = **fast sensor** : pas de compensation lead G6 BYODA |
| A3 | Propager `sourceSensor` jusqu’à `GlucoseStatusAIMI` (corriger le gap actuel si encore présent) |
| A4 | Markers JSONL : `cgm_source=DEXCOM_ONEPLUS_NATIVE` (nom exact à figer) |

### 4.4 Sécurité / conformité

| ID | Exigence |
|----|----------|
| S1 | Secrets de session en mémoire / stockage Android standard ; pas de log du pairing code en clair |
| S2 | Pas d’upload cloud Dexcom obligatoire pour boucler (standalone local) |
| S3 | Avertissement produit : usage hors écosystème officiel Dexcom ; responsabilité utilisateur |
| S4 | Pas de contournement root / Bluetooth HCI snoop en v1 |

---

## 5. Architecture cible

### 5.1 Découpage modules (miroir Eversense)

```
UI (Status / Pair / Permissions)
        │
DexcomOnePlusPlugin (BgSource, :plugins:source ou module plugin)
        │ watches
DexcomOnePlusCgmDriver (singleton / Hilt)
        │
GattCallback + Session (J-PAKE / crypto portée)
        │
Glucose notify / backfill
        │
onGlucose → GV + SourceSensor.DEXCOM_ONEPLUS_NATIVE
        │
PersistenceLayer.insertCgmSourceData
        │
AutosensDataStore → GlucoseStatusCalculatorAimi → AIMI
```

| Module | Responsabilité | Dépendances autorisées |
|--------|----------------|------------------------|
| `:plugins:dexcom_oneplus` (`app.aaps.plugins.dexcomoneplus`) | BLE, session, paquets, reconnect | `:core:interfaces`, crypto si besoin, **pas** `:plugins:aps` |
| `:plugins:source` (glue) | `BgSource`, activities, DI `@IntKey(446)`, prefs UI | `implementation(project(":plugins:dexcom_oneplus"))` |
| `core/data` | `SourceSensor`, `Sources` | enums only |
| `database/*` | converters | mapping sensor |
| docs | fiche + merge constraint + user guide | — |

**Interdit v1 :** dépendance `:plugins:dexcom_oneplus` → `:plugins:aps` (compile-time coupling AIMI).

### 5.2 Contrats AAPS

- Implémenter `BgSource` (+ `AbstractBgSourcePlugin` / patterns existants).
- Enregistrer dans `SourcePluginsListModule` avec `@IntKey(446)` (ne pas écraser 445 Eversense / 440 Dexcom BYODA).
- Conserver `DexcomPlugin` (BYODA) **inchangé** comme chemin G6/G7 follower.
- Notification Reader : garder `d1plus` comme **secours**, pas comme chemin nominal du plugin natif.

### 5.3 Source du protocole (Q5)

| Option | Avantages | Inconvénients |
|--------|-----------|---------------|
| **A. Port xDrip Direct** | Maturité One+/G7, communauté | GPL, sync upstream, taille |
| **B. Port Juggluco** | Souvent robuste | Licence / structure différente |
| **C. RE maison** | Contrôle total | Coût énorme, redondant |
| **D. Mix** | Prendre le meilleur | Complexité licence |

**Recommandation fiche (défaut proposé) :** **Q5 = A** (xDrip Direct comme base), avec abstraction `OnePlusSession` pour ne pas coller l’UI xDrip.

### 5.4 Gestion des « params téléphone »

Stratégie produit : **profils OEM**, pas une forêt de toggles.

```
DeviceProfileRegistry
  ├── PixelDefault
  ├── SamsungDefault
  ├── XiaomiConservative (si Q9)
  └── GenericFallback
```

Chaque profil peut fixer **uniquement** :

- délais GATT / MTU / connect retry  
- foreground service policy (si retenue)  
- agressivité reconnect  
- workarounds connus (liste versionnée dans le module)

**Toujours hors plugin (responsabilité utilisateur / OS) :**

- optimisation batterie « Non restreinte » pour AAPS  
- Bluetooth système ON  
- exclusion des kills OEM agressifs  

Le plugin affiche une **checklist OEM** au premier lancement (liens / steps), pas 40 switches.

### 5.5 Conflit master BLE

| Scénario | Comportement v1 |
|----------|-----------------|
| Plugin ON + app Dexcom essaie d’être master | Échec connexion possible → message F11 |
| Plugin ON + xDrip Direct | Non supporté (Q3=A) ou fallback doc (Q3=B) |
| Plugin OFF | GATT down ; utilisateur peut activer xDrip source |

---

## 6. Modèle de données

### 6.1 Enums (à ajouter)

| Symbole | Rôle |
|---------|------|
| `SourceSensor.DEXCOM_ONEPLUS_NATIVE` | Lecture native plugin |
| `Sources.DexcomOnePlus` (ou équivalent) | Provenance UE / insert |
| (option) `DEXCOM_G7_NATIVE_PLUGIN` | Si Q4 inclut G7 natif AAPS — **ne pas** confondre avec BYODA `DEXCOM_G7_NATIVE` |

### 6.2 Prefs (indicatif)

| Clé | Type | Défaut | Rôle |
|-----|------|--------|------|
| Plugin enable | via Config Builder | off | BG source |
| Last device address / identity | string | — | reconnect |
| Region / package dialect | enum | EU | si multi-région |
| OemProfile override | enum | Auto | debug |
| Verbose BLE log | bool | false | diagnostic |
| Backfill on connect | bool | true | F9 |

### 6.3 Markers / JSONL

| Marker | Quand |
|--------|-------|
| `DEXCOM_ONEPLUS_SCAN` | scan start/stop |
| `DEXCOM_ONEPLUS_PAIR` | pair result |
| `DEXCOM_ONEPLUS_SESSION` | session up/down |
| `DEXCOM_ONEPLUS_BG` | ingest (rate-limited) |
| `DEXCOM_ONEPLUS_RECONNECT` | retry |
| `DEXCOM_ONEPLUS_OEM_PROFILE` | profil appliqué |
| `cgm_source=DEXCOM_ONEPLUS_NATIVE` | export décisions / AIMI |

---

## 7. UX / parcours utilisateur

### 7.1 Premier démarrage

1. Config Builder → BG Source → **Dexcom ONE+ (natif)**.  
2. Écran permissions (Bluetooth, notifications si FGS, batterie).  
3. Checklist OEM (profil détecté + actions OS).  
4. Scan → sélection → saisie **code capteur**.  
5. **Écran warm-up (~30 min)** :  
   - titre clair (« Capteur en démarrage »)  
   - **compte à rebours** mm:ss ou min restantes (source = horloge session protocole, pas un timer inventé si le capteur expose un remaining)  
   - heure de fin estimée  
   - état BLE (connecté / reconnect…)  
   - message : pas encore de glycémie de boucle  
   - pas d’injection de BG placeholder dans le loop  
6. Fin warm-up → première BG → confirmation « source active ».  

### 7.2 Quotidien

- Statut accessible depuis onglet / menu plugin.  
- Reconnect silencieux.  
- Alerte si âge BG &gt; seuil (réutiliser patterns AAPS existants).  

### 7.3 Changement de capteur

- Flow « nouveau capteur » (nouveau code) sans réinstaller AAPS.  
- Purge identité précédente documentée.  

### 7.4 Textes à ne pas exposer en v1

- Companion mode  
- « Listen to Dexcom app »  
- Calibration  
- Cloud Dexcom credentials (sauf si protocole force un endpoint — alors documenter en Q8)

---

## 8. Plan de réalisation (étapes complètes)

Légende statut : ⬜ Pending · 🔄 In progress · ✅ Done · ⏸ Blocked · 🧪 Device OK (user)

### Phase 0 — Cadrage (obligatoire)

| # | Étape | Livrable | Statut |
|---|-------|----------|--------|
| 0.1 | Remplir §0 (Q1–Q10) | Décisions figées dans ce fichier | ⬜ |
| 0.2 | Choisir source protocole (Q5) + audit licence | NOTE licence + NOTICE draft | ⬜ |
| 0.3 | Liste téléphones Q9 + critères sortie lab | Tableau OEM | ⬜ |
| 0.4 | Spike 3–5 jours : preuve connect+1 BG sur 1 téléphone cible | Rapport spike (go/no-go) | ⬜ |
| 0.5 | Go/No-Go produit | Case cochée ci-dessous | ⬜ |

**Go/No-Go :** ☐ GO implémentation · ☐ NO-GO (rester xDrip follower + améliorations A)

---

### Phase 1 — Socle AAPS (sans BLE complet)

| # | Étape | Détail | Statut |
|---|-------|--------|--------|
| 1.1 | Créer module `:plugins:dexcom_oneplus` | `settings.gradle`, namespace, `build.gradle.kts` minimal | ⬜ |
| 1.2 | Stub driver + interface `OnePlusGlucoseWatcher` | Miroir `EversenseWatcher` | ⬜ |
| 1.3 | `SourceSensor` + DB converters + `Sources` | Migrations / tests converters | ⬜ |
| 1.4 | `DexcomOnePlusPlugin` `BgSource` + DI `@IntKey` | Ne pas casser BYODA `@440` / Eversense `@445` | ⬜ |
| 1.5 | Activities permission / status skeleton | Manifest source module | ⬜ |
| 1.6 | Prefs keys (`core/keys`) | EN strings only | ⬜ |
| 1.7 | Doc `MERGE_CONSTRAINT_DEXCOM_ONEPLUS.md` | Liste fichiers must-preserve | ⬜ |
| 1.8 | Compile `fullDebug` + tests module vides | CI locale | ⬜ |

---

### Phase 2 — Protocole & session BLE

| # | Étape | Détail | Statut |
|---|-------|--------|--------|
| 2.1 | Importer / porter stack session (Q5) | Abstraction `OnePlusBleSession` | ⬜ |
| 2.2 | Scanner + filtres publicitaires One+/G7 | Tests unitaires parsing ADV si possible | ⬜ |
| 2.3 | Pairing code → J-PAKE / auth | State machine testable | ⬜ |
| 2.4 | Subscribe glucose + parse GV | Bornes plausibles (ex. 20–600) | ⬜ |
| 2.5 | Reconnect / backoff | Pas de busy-loop ; logs markers | ⬜ |
| 2.6 | Backfill court | Fenêtre figée en constante documentée | ⬜ |
| 2.7 | Battery / sensor status si dispo | Best effort | ⬜ |
| 2.8 | Tests unitaires crypto/paquets | Sans device | ⬜ |
| 2.9 | Spike device → session stable 2 h | 🧪 user | ⬜ |

---

### Phase 3 — Profils OEM & robustesse

| # | Étape | Détail | Statut |
|---|-------|--------|--------|
| 3.1 | `DeviceProfileRegistry` + détection modèle | Auto + override debug | ⬜ |
| 3.2 | Profils Q9 | Delays / retry / FGS policy | ⬜ |
| 3.3 | Checklist UI premier lancement | Batterie / Bluetooth / OEM | ⬜ |
| 3.4 | Détection échec master BLE | Message F11 | ⬜ |
| 3.5 | Foreground service **si** nécessaire | Justifier ; type `connectedDevice` / dataSync selon policy Android | ⬜ |
| 3.6 | Matrice tests 24 h sur chaque téléphone Q9 | Tableau résultats | ⬜ |

---

### Phase 4 — Intégration boucle / AIMI

| # | Étape | Détail | Statut |
|---|-------|--------|--------|
| 4.1 | Ingest → `insertCgmSourceData` | Source + UE corrects | ⬜ |
| 4.2 | Propager `sourceSensor` dans `GlucoseStatusAIMI` | Corriger gap bucketed ads si présent | ⬜ |
| 4.3 | Désactiver lead G6 pour One+ natif | AIMI fast path | ⬜ |
| 4.4 | Markers JSONL / console | §6.3 | ⬜ |
| 4.5 | Vérifier trous CGM / safety existants | Pas de nouveau kill-switch cascade | ⬜ |
| 4.6 | Non-régression Eversense + BYODA G6/G7 | Checklist | ⬜ |

---

### Phase 5 — Qualité, docs, sortie

| # | Étape | Détail | Statut |
|---|-------|--------|--------|
| 5.1 | Guide utilisateur EN (et pointeur FR si besoin) | Install, pair, OEM, troubleshooting | ⬜ |
| 5.2 | Mettre à jour `NON_REGRESSION_CHECKLIST.md` | Section One+ native | ⬜ |
| 5.3 | NOTICE / LICENSE third-party | Conformité Q5 | ⬜ |
| 5.4 | Soft launch interne (utilisateurs pilotes Q9) | Feedback | ⬜ |
| 5.5 | Critères §9 tous verts | 🧪 confirmation user | ⬜ |
| 5.6 | Décision v1.1 (G7 natif, fallback auto, upstream) | Backlog | ⬜ |

---

### Phase A (parallèle, faible risque) — Améliorer One+ **sans** BLE natif

À faire **même si NO-GO Phase 0**, valeur immédiate :

| # | Étape | Statut |
|---|-------|--------|
| A.1 | `SourceSensor` / mapping `com.dexcom.d1plus` → One+ (plus `NATIVE_UNKNOWN`) | ⬜ |
| A.2 | AIMI : traiter One+ comme fast sensor | ⬜ |
| A.3 | Doc utilisateur « One+ recommandé = xDrip Direct » dans le fork | ⬜ |
| A.4 | (Option) packages One+ dans chemins follower **si** broadcast réel existe | ⬜ |

---

## 9. Critères d’acceptation (Definition of Done v1)

Ne jamais marquer « fully working » sans confirmation user device.

| # | Critère | Preuve |
|---|---------|--------|
| D1 | Pair + warm-up + BG dans AAPS sans xDrip installé | Capture + logcat |
| D1b | UI warm-up : compte à rebours ~30 min visible, puis transition auto à la 1ʳᵉ BG | Capture écran + marker `WARMUP_DONE` |
| D2 | 24 h lab sur ≥ 2 téléphones Q9 : ≥ 95 % ticks BG attendus | Tableau |
| D3 | Reconnect après airplane 60 s | Log `RECONNECT` + BG reprend |
| D4 | Changement de source → plugin stoppe GATT | Pas de conflit BLE résiduel |
| D5 | AIMI voit `DEXCOM_ONEPLUS_NATIVE` / fast path | JSONL |
| D6 | BYODA + Eversense non régressés | Tests + smoke |
| D7 | Docs merge constraint + user guide présents | Fichiers |
| D8 | Licence / NOTICE OK | Review |

---

## 10. Risques et mitigations

| Risque | Impact | Mitigation |
|--------|--------|------------|
| Port protocole incomplet | Bloquant | Spike Phase 0.4 obligatoire |
| Conflit master BLE avec app Dexcom | Perte BG | Q2 clair + message F11 ; doc |
| Cassures firmware One+/G7 | Régression terrain | Versionner workarounds ; markers |
| Licence GPL xDrip | Contagion licence fork | Audit 0.2 ; isolation module ; NOTICE |
| OEM non listés | Support nightmare | Q9 strict v1 ; GenericFallback prudent |
| Dette maintenance = xDrip dans AAPS | Coût long terme | Q10 ; owner nommé ; sync périodique |
| Illusion « plus de params téléphone » | Frustration user | Checklist OEM honnête dans UX |
| Scope creep G7/Stelo/Share | Retard | Q4 + §3.2 |
| Merge `dev` drop module | Perte feature | `MERGE_CONSTRAINT_DEXCOM_ONEPLUS.md` |
| Sécurité pairing code dans logs | Fuite | S1 + review |

---

## 11. Alternatives et recommandation

| Option | Effort | Risque | UX | Reco |
|--------|--------|--------|----|------|
| **A. Follower amélioré** (Phase A) | Faible | Faible | Moyenne | **Toujours faire** |
| **B. Plugin natif AAPS** (Phases 0–5) | Élevé | Élevé | Haute si réussi | **Conditionnel au GO Phase 0** |
| **C. Rester xDrip Direct only** | Nul | Nul | Dépend xDrip | Défaut si NO-GO |
| **D. Extraire BYODA BLE** | Très élevé | Très élevé | Illusoire | **Rejeter** |

**Recommandation architecture :**

1. Exécuter **Phase A** immédiatement (valeur One+ / AIMI).  
2. Ne lancer **B** qu’après GO Phase 0 (spike BLE réel + Q1–Q10).  
3. Base protocole = **xDrip Direct** (ou Juggluco), **pas** BYODA.  
4. Structure = **Eversense-like** (driver module + BgSource + merge constraint).

---

## 12. Estimation indicative ( Ordre de grandeur )

| Phase | Ordre de grandeur (dev expérimenté) |
|-------|-------------------------------------|
| 0 Cadrage + spike | 3–10 jours |
| 1 Socle AAPS | 3–7 jours |
| 2 Protocole BLE | 3–8 **semaines** |
| 3 OEM / robustesse | 2–4 semaines |
| 4 AIMI / boucle | 3–7 jours |
| 5 Qualité / docs / pilotes | 2–3 semaines |
| **Total si GO** | **~2–4 mois** calendaires selon Q4/Q9 et imprévus protocole |

*(Estimations, pas un engagement planning.)*

---

## 13. Fichiers / zones touchés (prévision)

### 13.1 Nouveaux

- `plugins/dexcom_oneplus/**` (driver)
- `plugins/source/.../DexcomOnePlusPlugin.kt` (+ activities)
- `docs/MERGE_CONSTRAINT_DEXCOM_ONEPLUS.md`
- `docs/DEXCOM_ONEPLUS_USER_GUIDE.md`
- Tests `plugins/dexcom_oneplus/src/test/**`

### 13.2 Modifiés

- `settings.gradle` / `settings.gradle.kts`
- `plugins/source/build.gradle.kts` + `SourcePluginsListModule` + `SourceModule`
- `core/data/.../SourceSensor.kt`, `Sources.kt`
- `database/impl` + `database/persistence` converters / extensions
- `core/keys` prefs + `plugins/source` strings EN
- `docs/NON_REGRESSION_CHECKLIST.md`
- AIMI : `GlucoseStatusCalculatorAimi` / `DetermineBasalAIMI2` (fast sensor, sourceSensor)
- évent. `notification_reader_packages.json` (Phase A)

### 13.3 Ne pas casser

- `DexcomPlugin` BYODA  
- `:plugins:eversense` + contrainte merge Eversense  
- xDrip source plugin  
- Cascade AIMI Tree→Harmonia (indépendante du CGM)

---

## 14. Checklist merge (à recopier dans MERGE_CONSTRAINT)

Lors de tout merge `dev` :

- [ ] Module `:plugins:dexcom_oneplus` toujours `include`  
- [ ] DI `@IntKey` plugin présent  
- [ ] `SourceSensor.DEXCOM_ONEPLUS_NATIVE` + converters  
- [ ] Activities manifest  
- [ ] Prefs keys  
- [ ] AIMI mapping source / fast path  
- [ ] NOTICE licence  
- [ ] Smoke : pair + 1 BG sur device pilote  

---

## 15. Journal

| Date | Événement |
|------|-----------|
| 2026-07-18 | Fiche produit créée (analyse architecture OpenApsAIMI ; pas d’implémentation) |
| 2026-07-18 | Scaffold lancé sur `feature/dexcom-oneplus-native` (module, DI 446, enums, notif) ; A12 : USER_GUIDE + MERGE_CONSTRAINT + NON_REGRESSION |
| 2026-07-18 | A1 port OK + NOTICE ; A6 Real skeleton (Stub default) ; A2/A7/A8/A9 livrés ; A13 notes + QA matrix — **BLE natif non validé device** |

---

## 16. Annexes

### 16.1 Glossaire

| Terme | Sens |
|-------|------|
| BYODA | App Dexcom **patchée** qui broadcast vers AAPS ; pas une lib BLE AAPS |
| DiaKEM | Patcher / app G7 patchée ; **pas One+** ; fragile login |
| xDrip Direct | xDrip parle BLE au capteur ; AAPS suit via Intent |
| Native (ce doc) | GATT **dans le process AAPS** |
| Native (enum historique) | Souvent « app Dexcom/xDrip native collector » ≠ BLE AAPS |
| Master BLE | Unique client connecté au capteur One+/G7 |

### 16.2 Références

- Wiki AAPS — Dexcom G7 / ONE+ : follower xDrip / Juggluco ; DiaKEM ≠ One+  
- Doc fork Eversense : [MERGE_CONSTRAINT_EVERSENSE.md](MERGE_CONSTRAINT_EVERSENSE.md)  
- Non-régression : [NON_REGRESSION_CHECKLIST.md](NON_REGRESSION_CHECKLIST.md)  
- Guide user : [DEXCOM_ONEPLUS_USER_GUIDE.md](DEXCOM_ONEPLUS_USER_GUIDE.md)  
- Merge constraint : [MERGE_CONSTRAINT_DEXCOM_ONEPLUS.md](MERGE_CONSTRAINT_DEXCOM_ONEPLUS.md)  
- Plugin follower actuel : `plugins/source/.../DexcomPlugin.kt`  
- Notif One+ : `plugins/source/src/main/assets/notification_reader_packages.json` (`com.dexcom.d1plus`, `com.dexcom.dexcomone`)

### 16.3 Matrice de décision rapide

```
Besoin = moins de friction One+ dans AIMI ?
  ├─ Oui, sans BLE AAPS → Phase A seulement
  └─ Oui, un seul APK avec radio AAPS
        ├─ Spike Phase 0.4 OK + Q1–Q10 figés → Phases 1–5
        └─ Spike KO / licence KO → rester xDrip Direct + Phase A
```

---

**Fin de fiche.**  
Prochaine action humaine : remplir §0 puis lancer Phase 0.4 (spike) **ou** Phase A seule.
