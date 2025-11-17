# Manuale utente – OpenAPS AIMI

Benvenuti in AIMI (Adaptive Insulin Management Intelligence), il motore predittivo di AndroidAPS che combina apprendimento automatico, monitoraggio fisiologico e sistemi di sicurezza avanzati per gestire basale e SMB (Super Micro-Bolus). AIMI osserva la vostra storia glicemica, i bolus, i passi/ritmo cardiaco e i modi dichiarati per regolare dinamicamente sensibilità, durata d’azione dell’insulina e micro-bolus, mantenendo le protezioni storiche di OpenAPS.【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/OpenAPSAIMIPlugin.kt†L95-L175】【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/DetermineBasalAIMI2.kt†L2480-L2648】

AIMI non è una scatola nera: pensatelo come un co-pilota. Più i vostri dati sono accurati (profilo aggiornato, registrazione dei pasti, chiusura delle loop notturne), più AIMI anticipa con precisione e stabilizza le vostre glicemie.

---

## Sommario
1. [Installazione e attivazione](#installazione-e-attivazione)
2. [Principi generali e verifica del funzionamento](#principi-generali-e-verifica-del-funzionamento)
3. [🔧 Impostazioni generali](#-impostazioni-generali)
4. [⚙️ Regolazione basale & SMB](#️-regolazione-basale--smb)
5. [🧠 Intelligenza adattativa (ISF, PeakTime, PK/PD)](#-intelligenza-adattativa-isf-peaktime-pkpd)
6. [💡 Modi & rilevamento pasti](#-modi--rilevamento-pasti)
7. [💪 Esercizio & regole di sicurezza](#-esercizio--regole-di-sicurezza)
8. [🌙 Modalità notte & crescita notturna](#-modalità-notte--crescita-notturna)
9. [❤️ Integrazione frequenza cardiaca & passi (Wear OS)](#️-integrazione-frequenza-cardiaca--passi-wear-os)
10. [♀️ WCycle – monitoraggio del ciclo mestruale](#️-wcycle--monitoraggio-del-ciclo-mestruale)
11. [Consigli per aggiustamenti rapidi](#consigli-per-aggiustamenti-rapidi)
12. [Risoluzione problemi e interpretazione dei log](#risoluzione-problemi-e-interpretazione-dei-log)
13. [Riepilogo didattico](#riepilogo-didattico)

---

## Installazione e attivazione
1. **Attivate il plugin** da *Configurazione ▶️ Plugin ▶️ APS* e selezionate **OpenAPS AIMI**. AIMI verifica automaticamente che la vostra pompa supporti le basali temporanee.【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/OpenAPSAIMIPlugin.kt†L226-L238】
2. **Riavviate il loop** : all’avvio AIMI ricarica le vostre sensibilità variabili passate e installa il suo calcolatore Kalman/PK-PD.【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/OpenAPSAIMIPlugin.kt†L140-L175】
3. **Autorizzate i permessi** : se attivate passi/FC, assicuratevi che l’orologio Wear OS sincronizzi correttamente verso AAPS (vedi sezione ❤️).
4. **Verificate lo stato**
   - Lo schermo OpenAPS mostra *Algoritmo AIMI* e la data dell’ultimo calcolo (`lastAPSRun`).【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/OpenAPSAIMIPlugin.kt†L162-L165】
   - I log contengono motivazioni `AIMI+` quando l’adaptive basal attiva un kicker o una micro-ripresa.【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/AIMIAdaptiveBasal.kt†L79-L112】
   - Le colonne `SMB`/`Basal` dello stato mostrano i moltiplicatori WCycle o NightGrowth quando sono attivi.【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/DetermineBasalAIMI2.kt†L2493-L2531】【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/DetermineBasalAIMI2.kt†L417-L444】

---

## Principi generali e verifica del funzionamento
- **Loop completo** : AIMI recupera il `GlucoseStatusAIMI`, calcola un piano basale tramite `BasalPlanner`, applica `AIMIAdaptiveBasal` per i plateau e regola gli SMB tramite PK/PD e ISF adattativo.【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/basal/BasalDecisionEngine.kt†L25-L113】【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/pkpd/PkPdIntegration.kt†L27-L109】
- **Apprendimento continuo** : i parametri PK/PD (DIA e tempo di picco) vengono aggiornati quando è disponibile abbastanza IOB, a meno che non siano rilevati sport o pasti ad assorbimento ritardato.【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/pkpd/AdaptivePkPdEstimator.kt†L20-L52】
- **Log utili** : `rT.reason` include i trigger (plateau kicker, NGR, WCycle). I CSV AIMI (`AAPS/oapsaimi*.csv`) registrano ogni decisione per analisi successive.【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/OapsAIMIsmb.kt†L205-L276】

---

## 🔧 Impostazioni generali
Questi parametri definiscono la base fisiologica utilizzata da tutti i moduli AIMI.

### 🔹 `OApsAIMIMLtraining`
- **Valore di default :** `false` (disattivato).【F:core/keys/src/main/kotlin/app/aaps/core/keys/BooleanKey.kt†L123-L136】
- **Scopo :** consentire l’addestramento del modello SMB locale (file `oapsaimiML_records.csv`).【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/OapsAIMIsmb.kt†L205-L223】
- **Effetto :** in modalità training, AIMI registra le tue loop per affinare la rete `neuralnetwork5` dopo aver accumulato almeno 60 min di dati.【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/OapsAIMIsmb.kt†L236-L244】
- **Regolare se :**
  - **Ipos frequenti :** lasciare disattivato per identificare la fonte prima di ri-addestrare.
  - **Ipers frequenti :** attivare per apprendere i tuoi pattern, ma monitorare la sicurezza (SMB sempre limitato).
  - **Variabilità :** addestrare solo dopo aver stabilizzato i profili (almeno 3-4 giorni di dati omogenei).

### 🔹 `OApsAIMIweight`, `OApsAIMICHO`, `OApsAIMITDD7`
- **Valori di default :** 50 kg, 50 g, 40 U rispettivamente.【F:core/keys/src/main/kotlin/app/aaps/core/keys/DoubleKey.kt†L67-L69】
- **Scopo :** fornire limiti fisiologici utilizzati per inizializzare il filtro Kalman ISF e la PK/PD se la tua storia è vuota.
- **Effetto :** un peso/TDD sottostimato rende l’ISF troppo aggressivo; un CHO medio troppo basso rileverà più spesso pasti «grassi».
- **Regolare :**
  - **Ipos :** aumentare leggermente `OApsAIMIweight` o `OApsAIMITDD7` verso i valori reali → l’ISF si addolcisce.
  - **Ipers :** regolare `OApsAIMICHO` verso i tuoi apporti reali per mantenere realistici i modelli pasto.
  - **Variabilità :** armonizzare questi parametri con il tuo profilo (stesse unità dei report giornalieri).

### 🔹 `AimiUamConfidence`
- **Valore di default :** `0.5` (fiducia media).【F:core/keys/src/main/kotlin/app/aaps/core/keys/DoubleKey.kt†L144-L146】
- **Scopo :** ponderare l’apprendimento «UAM» quando la rilevazione di pasti non annunciati è affidabile.
- **Effetto :** più alta è la fiducia, meno l’algoritmo dinamico di sensibilità (IsfAdjustmentEngine) si discosta dal profilo.【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/ISF/IsfAdjustmentEngine.kt†L13-L36】
- **Regolare :**
  - **Ipos post-UAM :** aumentare (0.6–0.8) per limitare la riduzione dell’ISF.
  - **Ipers prolungate non annunciate :** ridurre (0.3–0.4) affinché l’ISF si adatti più rapidamente.
  - **Variabilità :** lasciare di default finché il motore accumula abbastanza Kalman trust.

### 🔹 `OApsAIMIEnableBasal`
- **Valore di default :** `false`.【F:core/keys/src/main/kotlin/app/aaps/core/keys/BooleanKey.kt†L123-L136】
- **Scopo :** attivare una basale predittiva specifica (legacy). Attualmente non usata (commentata): lasciare disattivata salvo richiesta specifica.

### 🔹 `OApsAIMIautoDrive`
- **Valore di default :** `false`.【F:core/keys/src/main/kotlin/app/aaps/core/keys/BooleanKey.kt†L130-L136】
- **Scopo :** attivare l’autoDrive, cioè l’uso automatico dei fattori mode (pasti, auto-bolus) e del profilo combinato (`combinedDelta`).
- **Effetto :** applica i fattori `autodrivePrebolus`, `autodrivesmallPrebolus`, limita la basale tramite `autodriveMaxBasal` e regola i trigger `combinedDelta`/`AutodriveDeviation`.【F:core/keys/src/main/kotlin/app/aaps/core/keys/DoubleKey.kt†L105-L114】
- **Regolare :** iniziare OFF, poi attivare quando i mode pasto sono correttamente impostati.

### 🔹 Parametri target AutoDrive (`OApsAIMIAutodriveBG`, `OApsAIMIAutodriveTarget`)
- **Valori di default :** 90 e 70 mg/dL.【F:core/keys/src/main/kotlin/app/aaps/core/keys/IntKey.kt†L83-L86】
- **Effetto :** servono come riferimento per la rilevazione di deviazioni minime e per attivare micro-prebolus autoDrive.
- **Suggerimento :** mantenere `AutodriveBG` sopra il target reale (≈ 90–100) per consentire ad AIMI di assorbire piccole risalite senza sovra-correggere.

---

## ⚙️ Regolazione basale & SMB
AIMI controlla simultaneamente la basale temporanea (kickers, anti-stall) e l’intensità degli SMB tramite i suoi parametri.

### Parametri SMB globali
| Parametro | Valore di default | Ruolo | Aggiustamento ipo | Aggiustamento iper | Variabilità |
|-----------|------------------|------|------------------|-------------------|-------------|
| `OApsAIMIMaxSMB` | 1.0 U | tetto SMB standard | ↓ a 0.7–0.8 se ipos dopo SMB | ↑ fino a 1.2 se post-prandiali alte | combinare con fattori pasto |【F:core/keys/src/main/kotlin/app/aaps/core/keys/DoubleKey.kt†L64-L66】|
| `OApsAIMIHighBGMaxSMB` | 1.0 U | tetto SMB quando AIMI rileva un plateau alto | idem | ↑ (1.5) per correggere più velocemente un plateau >180 mg/dL | Monitorare NGR |【F:core/keys/src/main/kotlin/app/aaps/core/keys/DoubleKey.kt†L64-L66】|
| `autodriveMaxBasal` | 1.0 U/h | tetto basale autoDrive | ↓ se ipos notturne | ↑ (×1.2) se plateau iper in autoDrive | Collegato ad anti-stall |【F:core/keys/src/main/kotlin/app/aaps/core/keys/DoubleKey.kt†L105-L114】|
| `meal_modes_MaxBasal` | 1.0 U/h | tetto basale durante i mode pasto | idem | ↑ (×1.3) se tollerate più nei pasti lunghi | Lasciare > basale profilo |【F:core/keys/src/main/kotlin/app/aaps/core/keys/DoubleKey.kt†L113-L115】|

**Suggerimento :** I tetti SMB/basale sono applicati dopo tutte le sicurezze (`applyMaxLimits`).【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/OapsAIMIsmb.kt†L296-L308】

### Intervalli SMB / mode
Le preferenze `OApsAIMIHighBGinterval`, `OApsAIMImealinterval`, ecc., definiscono la frequenza minima (per 5 min) alla quale AIMI può riproporre un SMB nel mode corrispondente (di default 3 × 5 min = 15 min).【F:core/keys/src/main/kotlin/app/aaps/core/keys/IntKey.kt†L75-L82】
- **Ipos :** aumentare l’intervallo (4–5) per distanziare gli SMB.
- **Iper prolungate :** ridurre a 2 (10 min) solo per HighBG.

### AIMIAdaptiveBasal (plateau, micro-riprese)
- **Soglia alta** `OApsAIMIHighBg` = 180 mg/dL : attiva i kicks quando viene identificato un plateau alto.【F:core/keys/src/main/kotlin/app/aaps/core/keys/DoubleKey.kt†L135-L143】【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/AIMIAdaptiveBasal.kt†L62-L112】
- **Banda plateau** `OApsAIMIPlateauBandAbs` = ±2.5 mg/dL/5 min : più la banda è ampia, più AIMI tollera variazioni prima di kick-are.【F:core/keys/src/main/kotlin/app/aaps/core/keys/DoubleKey.kt†L135-L143】
- **Moltiplicatore max** `OApsAIMIMaxMultiplier` = ×1.6 : limita la basale temporanea in plateau.【F:core/keys/src/main/kotlin/app/aaps/core/keys/DoubleKey.kt†L135-L143】
- **Kicker step/min** (`OApsAIMIKickerStep`, `OApsAIMIKickerMinUph`, `OApsAIMIKickerStartMin`, `OApsAIMIKickerMaxMin`) controllano l’intensità e la durata del kicker.【F:core/keys/src/main/kotlin/app/aaps/core/keys/DoubleKey.kt†L138-L140】【F:core/keys/src/main/kotlin/app/aaps/core/keys/IntKey.kt†L93-L98】
- **Micro-ripresa** (`OApsAIMIZeroResumeMin`, `OApsAIMIZeroResumeFrac`, `OApsAIMIZeroResumeMax`) : rilancia una basale bassa dopo una pausa ≥10 min per evitare risalite post-ipoglicemia.【F:core/keys/src/main/kotlin/app/aaps/core/keys/DoubleKey.kt†L141-L142】【F:core/keys/src/main/kotlin/app/aaps/core/keys/IntKey.kt†L96-L97】【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/AIMIAdaptiveBasal.kt†L79-L112】
- **Anti-stall** `OApsAIMIAntiStallBias` (10 %) e `OApsAIMIDeltaPosRelease` (Δ+1 mg/dL) definiscono l’overdrive minimo in plateau stabile.【F:core/keys/src/main/kotlin/app/aaps/core/keys/DoubleKey.kt†L142-L143】

**Albero decisionale pratico :**
```
Se plateau >180 mg/dL e Δ≈0 → aumentare `OApsAIMIKickerStep` (+0,05) per correggere più velocemente.
Se ipoglicemie dopo ripresa basale → ridurre `OApsAIMIZeroResumeFrac` (0,2) o aumentare `ZeroResumeMin` (15 min).
Se salita lenta nonostante i kicks → aumentare `OApsAIMIMaxMultiplier` (1,8 max) e verificare `KickerMinUph`.
```

### Sicurezza ipoglicemia
AIMI applica un guardrail che blocca gli SMB se la glicemia si avvicina alla soglia di ipo con pendenza negativa, tenendo conto di un margine aggiuntivo in base alla velocità di discesa.【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/DetermineBasalAIMI2.kt†L400-L413】

---

## 🧠 Intelligenza adattativa (ISF, PeakTime, PK/PD)

### PK/PD dinamico
- **Attivazione** : `OApsAIMIPkpdEnabled` (OFF di default).【F:core/keys/src/main/kotlin/app/aaps/core/keys/BooleanKey.kt†L130-L136】
- **Parametri iniziali** (`OApsAIMIPkpdInitialDiaH`, `OApsAIMIPkpdInitialPeakMin`) definiscono il DIA (20 h) e il picco (40 min).【F:core/keys/src/main/kotlin/app/aaps/core/keys/DoubleKey.kt†L71-L80】
- **Limiti & velocità** (`OApsAIMIPkpdBoundsDia*`, `OApsAIMIPkpdBoundsPeak*`, `OApsAIMIPkpdMax*`) limitano l’apprendimento giornaliero.【F:core/keys/src/main/kotlin/app/aaps/core/keys/DoubleKey.kt†L71-L78】
- **Stato persistente** (`OApsAIMIPkpdStateDiaH`, `OApsAIMIPkpdStatePeakMin`) memorizza l’ultimo DIA/picco appreso.【F:core/keys/src/main/kotlin/app/aaps/core/keys/DoubleKey.kt†L79-L80】
- **Effetto :** quando attivato, AIMI fonde l’ISF profilo/TDD con la stima PK/PD e applica un *pkpdScale* legato alla frazione di coda dell’IOB.【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/pkpd/PkPdIntegration.kt†L27-L82】
- **Aggiustamenti :**
  - **Ipoglicemie tardive** : ridurre `OApsAIMIPkpdMaxDiaChangePerDayH` per frenare l’allungamento del DIA.【F:core/keys/src/main/kotlin/app/aaps/core/keys/DoubleKey.kt†L77-L78】
  - **Iper glicemie post-pasto** : abbassare `OApsAIMIPkpdBoundsPeakMinMax` (es. 180) per favorire picchi più brevi.
  - **Dati instabili** : disattivare temporaneamente `PkpdEnabled` e tornare ai valori iniziali (reset tramite preferenze).

### Fusione ISF & blending rapido
- **`OApsAIMIIsfFusionMinFactor` / `MaxFactor`** : fattori min/max applicati all’ISF del profilo (0.75–2.0 di default).【F:core/keys/src/main/kotlin/app/aaps/core/keys/DoubleKey.kt†L81-L83】
- **`OApsAIMIIsfFusionMaxChangePerTick`** : variazione massima ±40 % per tick da 5 min.【F:core/keys/src/main/kotlin/app/aaps/core/keys/DoubleKey.kt†L81-L83】
- **Effetto :** la fusione miscela ISF TDD/PkPd e Kalman rapido tramite `IsfBlender`, rispettando un lisciamento ±5 % per ciclo.【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/ISF/IsfBlender.kt†L5-L45】

### Aggiustamento adattativo ISF
`IsfAdjustmentEngine` utilizza la glicemia Kalman e una EMA del TDD per ricalcolare l’ISF target (legge logaritmica) limitando il cambiamento a ±5 % per ciclo e ±20 % per ora.【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/ISF/IsfAdjustmentEngine.kt†L6-L49】
- **Ipoglicemie** : ridurre `AimiUamConfidence` o disattivare PK/PD se l’ISF scende troppo rapidamente.
- **Iper glicemie** : verificare che `OApsAIMIIsfFusionMaxFactor` rimanga ≥1.6.

### SMB damping intelligente
I parametri `OApsAIMISmbTailThreshold`, `OApsAIMISmbTailDamping`, `OApsAIMISmbExerciseDamping`, `OApsAIMISmbLateFatDamping` controllano la riduzione degli SMB a fine azione, dopo esercizio o pasti grassi.【F:core/keys/src/main/kotlin/app/aaps/core/keys/DoubleKey.kt†L84-L87】【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/pkpd/SmbDamping.kt†L4-L77】
- **Consiglio :**
  - Se rimani alto a fine azione → aumentare `SmbTailThreshold` (0.35) o incrementare `SmbTailDamping` (0.6).
  - Se ipoglicemie dopo sport → ridurre `SmbExerciseDamping` (0.4) per tagliare più forte.

### PeakTime dinamico
Il calcolo `calculateDynamicPeakTime` combina IOB, attività futura, passi, FC e sensore per regolare il tempo di picco tra 35 e 120 min.【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/DetermineBasalAIMI2.kt†L2533-L2645】
- **Ipoglicemie notturne** : se il picco è troppo corto, aumentare `OApsAIMIcombinedDelta` (1.5) per rendere AIMI più prudente in autoDrive.
- **Iper glicemie post-prandiali** : assicurarsi che passi/FC siano sincronizzati correttamente per consentire un picco accorciato quando si è attivi.

---

## 💡 Modalità & rilevamento pasti
AIMI modula i suoi SMB in base alle vostre modalità temporali e ai fattori dedicati.

### Fattori giornalieri
`OApsAIMIMorningFactor`, `OApsAIMIAfternoonFactor`, `OApsAIMIEveningFactor` (default 50 %) ponderano gli SMB previsti secondo la fascia oraria.【F:core/keys/src/main/kotlin/app/aaps/core/keys/DoubleKey.kt†L88-L101】【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/OapsAIMIsmb.kt†L236-L245】
- **Ipoglicemie mattutine** : ridurre il MorningFactor (40 %).
- **Iper glicemie serali** : aumentare EveningFactor (60–70 %).

### Modalità pasti specifiche
Ogni modalità dispone di un trio *(prebolus1, prebolus2, fattore %)* e di un intervallo:
- **Colazione** : `OApsAIMIBFPrebolus` (2.5 U), `OApsAIMIBFPrebolus2` (2.0 U), `OApsAIMIBFFactor` (50 %), intervallo 15 min.【F:core/keys/src/main/kotlin/app/aaps/core/keys/DoubleKey.kt†L95-L101】【F:core/keys/src/main/kotlin/app/aaps/core/keys/IntKey.kt†L81-L82】
- **Pranzo / Cena** : parametri analoghi (`Lunch*`, `Dinner*`).【F:core/keys/src/main/kotlin/app/aaps/core/keys/DoubleKey.kt†L98-L101】【F:core/keys/src/main/kotlin/app/aaps/core/keys/IntKey.kt†L76-L79】
- **Snack / HighCarb / Pasti generici** : `OApsAIMISnackPrebolus`, `OApsAIMIHighCarbPrebolus`, ecc.【F:core/keys/src/main/kotlin/app/aaps/core/keys/DoubleKey.kt†L120-L123】
- **Modalità Hyper** : `OApsAIMIHyperFactor` (60 %) rinforza gli SMB se BG>180.【F:core/keys/src/main/kotlin/app/aaps/core/keys/DoubleKey.kt†L100-L103】

**Suggerimenti :**
- Usare `OApsAIMImealinterval` (15 min di default) per evitare SMB troppo ravvicinati durante un pasto prolungato.【F:core/keys/src/main/kotlin/app/aaps/core/keys/IntKey.kt†L75-L82】
- `OApsAIMIMealFactor` pondera gli SMB anche senza modalità esplicita (utile per pasti improvvisi).【F:core/keys/src/main/kotlin/app/aaps/core/keys/DoubleKey.kt†L91-L101】

### AutoDrive prebolus
`OApsAIMIautodrivePrebolus` (1 U) e `OApsAIMIautodrivesmallPrebolus` (0.1 U) servono da limiti per micro-prebolus automatici quando `autoDrive` è attivo.【F:core/keys/src/main/kotlin/app/aaps/core/keys/DoubleKey.kt†L105-L107】

### Gestione note & rilevamento pasti
AIMI scansiona le vostre note (sleep, sport, meal…) per attivare le modalità se dimenticate di cliccare sul pulsante, e le registra nei log SMB.【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/DetermineBasalAIMI2.kt†L2656-L2678】【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/OapsAIMIsmb.kt†L311-L360】

---

## 💪 Esercizio & regole di sicurezza

### Interruttori fisiologici
- **`OApsAIMIpregnancy`**, **`OApsAIMIhoneymoon`** : attivano aggiustamenti specifici in `BasalDecisionEngine` (es. aumentare la basale se delta>0 durante la gravidanza).【F:core/keys/src/main/kotlin/app/aaps/core/keys/BooleanKey.kt†L123-L136】【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/basal/BasalDecisionEngine.kt†L53-L63】【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/basal/BasalDecisionEngine.kt†L461-L463】
- **`OApsAIMIforcelimits`** : forzare i limiti basale/SMB (utilizzato da alcuni profili). Lasciate OFF salvo indicazione clinica.

### Rilevamento sport & sicurezza SMB
- Le regole `isSportSafetyCondition` interrompono gli SMB quando passi/FC indicano un’attività intensa, o quando il target è elevato (>140).【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/OapsAIMIsmb.kt†L342-L350】
- `applySpecificAdjustments` riduce della metà gli SMB se siete in sonno/snack/bassa attività prolungata.【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/OapsAIMIsmb.kt†L353-L360】

### Albero decisionale sicurezza
```
Se ipoglicemie dopo sport → attivare `OApsAIMIEnableStepsFromWatch` + ridurre `SmbExerciseDamping`.
Se ipoglicemie in gravidanza → ridurre `OApsAIMIMaxMultiplier` e verificare che `pregnancy` sia attivo.
Se iperglicemie in luna di miele → attivare `OApsAIMIhoneymoon` per consentire maggiore aggressività.
```

---

## 🌙 Modalità notte & crescita notturna

### Modalità notte classica
- **Interruttore** `OApsAIMInight` (OFF di default).【F:core/keys/src/main/kotlin/app/aaps/core/keys/BooleanKey.kt†L127-L129】
- **Fattore sonno** `OApsAIMIsleepFactor` (60 %) e intervallo `OApsAIMISleepinterval` (15 min) modulano le SMB durante la notte.【F:core/keys/src/main/kotlin/app/aaps/core/keys/DoubleKey.kt†L102-L103】【F:core/keys/src/main/kotlin/app/aaps/core/keys/IntKey.kt†L81-L82】

### Night Growth Resistance (NGR)
Questo modulo gestisce i picchi dell’ormone della crescita in bambini/adolescenti.
- **Attivazione**: automatica per <18 anni o tramite `OApsAIMINightGrowthEnabled` (ON di default).【F:core/keys/src/main/kotlin/app/aaps/core/keys/BooleanKey.kt†L133-L136】【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/DetermineBasalAIMI2.kt†L417-L444】
- **Parametri chiave**:
  - `OApsAIMINightGrowthAgeYears` (14 anni), finestre `OApsAIMINightGrowthStart`/`End` (22:00–06:00).【F:core/keys/src/main/kotlin/app/aaps/core/keys/IntKey.kt†L87-L90】【F:core/keys/src/main/kotlin/app/aaps/core/keys/StringKey.kt†L56-L61】
  - `OApsAIMINightGrowthMinRiseSlope` (≥5 mg/dL/5 min), `MinDuration`, `MinEventualOverTarget` definiscono la rilevazione.【F:core/keys/src/main/kotlin/app/aaps/core/keys/DoubleKey.kt†L128-L132】【F:core/keys/src/main/kotlin/app/aaps/core/keys/IntKey.kt†L87-L90】
  - Moltiplicatori SMB/Basal e massimali IOB (`NightGrowthSmbMultiplier`, ecc.).【F:core/keys/src/main/kotlin/app/aaps/core/keys/DoubleKey.kt†L128-L132】
- **Funzionamento**: NGR monitora la pendenza massima, conferma l’evento e applica i moltiplicatori fino a uno stato DECAY controllato.【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/NightGrowthResistanceMonitor.kt†L13-L198】

**Consigli:**
- Se iperglicemie notturne persistenti → aumentare `NightGrowthSmbMultiplier` (1.3) e `NightGrowthBasalMultiplier` (1.2).
- Se ipoglicemie alla fine dell’episodio → ridurre `NightGrowthMaxSmbClamp` o `MaxIobExtra`.
- Per un bambino più piccolo, ridurre `MinRiseSlope` (3–4) per rilevare prima i cambiamenti.

---

## ❤️ Integrazione frequenza cardiaca & passi (Wear OS)
- **Attivazione** : `OApsAIMIEnableStepsFromWatch` (OFF di default).【F:core/keys/src/main/kotlin/app/aaps/core/keys/BooleanKey.kt†L123-L129】
- **Effetti** :
  - I passi negli ultimi 5–180 min (`recentSteps*`) e la FC media 5/60/180 min sono utilizzati per regolare il tempo di picco, modulare SMB (sport) e decidere eventuali riprese basali.【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/OapsAIMIsmb.kt†L848-L911】【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/DetermineBasalAIMI2.kt†L2539-L2645】
  - In caso di attività intensa (>1000 passi e FC>110), AIMI allunga il picco (×1.2) e limita SMB.【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/DetermineBasalAIMI2.kt†L2616-L2626】【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/OapsAIMIsmb.kt†L342-L350】
  - A riposo (passi<200, FC<50), il picco viene accorciato (×0.75) per evitare ritardi d’azione.【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/DetermineBasalAIMI2.kt†L2618-L2626】

**Suggerimenti:**
- Verificate che l’orologio trasmetta correttamente ogni 5 min (altrimenti i valori resteranno nulli e AIMI non effettuerà aggiustamenti).
- In caso di ipoglicemie durante l’attività, riducete `SmbExerciseDamping` o disattivate temporaneamente l’opzione.

---

## ♀️ WCycle – monitoraggio del ciclo mestruale
AIMI può adattare basale e SMB in base alla fase del ciclo mestruale.

### Attivazione & modalità
- **`OApsAIMIwcycle`** : attiva il modulo (OFF di default).【F:core/keys/src/main/kotlin/app/aaps/core/keys/BooleanKey.kt†L130-L134】
- **Modalità di monitoraggio** : `OApsAIMIWCycleTrackingMode` (`FIXED_28`, `CALENDAR_VARIABLE`, ecc.).【F:core/keys/src/main/kotlin/app/aaps/core/keys/StringKey.kt†L56-L59】
- **Parametri fisiologici** : contraccettivo, stato tiroideo, Verneuil influenzano l’ampiezza dei moltiplicatori.【F:core/keys/src/main/kotlin/app/aaps/core/keys/StringKey.kt†L56-L59】【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/wcycle/WCycleTypes.kt†L1-L39】
- **Clamp min/max** (`OApsAIMIWCycleClampMin` 0.8, `ClampMax` 1.25) limitano la scala applicata.【F:core/keys/src/main/kotlin/app/aaps/core/keys/DoubleKey.kt†L124-L126】
- **Opzioni shadow/confirm** :
  - `OApsAIMIWCycleShadow` mantiene i calcoli senza applicarli (modalità osservazione).【F:core/keys/src/main/kotlin/app/aaps/core/keys/BooleanKey.kt†L132-L135】
  - `OApsAIMIWCycleRequireConfirm` richiede conferma prima di applicare una modifica.【F:core/keys/src/main/kotlin/app/aaps/core/keys/BooleanKey.kt†L132-L135】

### Funzionamento
- `ensureWCycleInfo()` interroga `WCycleFacade` con le vostre preferenze e restituisce fase, moltiplicatori e un testo `reason` inserito nei log.【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/DetermineBasalAIMI2.kt†L2493-L2517】
- `updateWCycleLearner` regola i moltiplicatori appresi rispettando `ClampMin/Max`.【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/DetermineBasalAIMI2.kt†L2521-L2531】
- I valori di base seguono `WCycleDefaults` (es. +12 % basale in fase luteale).【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/wcycle/WCycleTypes.kt†L18-L38】

**Consigli:**
- Definite la durata media (`OApsAIMIWCycleAvgLength`, 28 g) e il giorno di inizio (`OApsAIMIwcycledateday`).【F:core/keys/src/main/kotlin/app/aaps/core/keys/IntKey.kt†L86-L87】【F:core/keys/src/main/kotlin/app/aaps/core/keys/DoubleKey.kt†L124-L126】
- In caso di contraccezione ormonale, l’ampiezza viene automaticamente ridotta (×0.4–0.5).【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/wcycle/WCycleTypes.kt†L23-L30】

**Consigli:**
- Definite la durata media (`OApsAIMIWCycleAvgLength`, 28 g) e il giorno di inizio (`OApsAIMIwcycledateday`).【F:core/keys/src/main/kotlin/app/aaps/core/keys/IntKey.kt†L86-L87】【F:core/keys/src/main/kotlin/app/aaps/core/keys/DoubleKey.kt†L124-L126】
- In caso di contraccezione ormonale, l’ampiezza viene automaticamente ridotta (×0.4–0.5).【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/wcycle/WCycleTypes.kt†L23-L30】

---

## Consigli di regolazione rapida
| Situazione | Regolazione suggerita | Preferenza correlata |
|-----------|----------------------|--------------------|
| Ipo post-SMB | ↓ `OApsAIMIMaxSMB`, ↑ `OApsAIMISmbTailDamping` | SMB & PK/PD |
| Ipo notturne | ↑ `OApsAIMIZeroResumeMin`, ↓ `NightGrowthBasalMultiplier` | Basal & Night |
| Iper glicemie post-pasto | ↑ fattori pasto (60–70 %), ↓ `OApsAIMIPkpdBoundsPeakMinMax` | Modalità & PK/PD |
| Iper plateau piatto | ↑ `OApsAIMIKickerStep`, controllare `HighBGMaxSMB` | Adaptive Basal |
| Forte variabilità | Stabilizzare peso/TDD, disattivare `PkpdEnabled`, attivare `Shadow` WCycle | Generale & WCycle |

### Mini albero decisionale quotidiano
```
Se rimanete >180 mg/dL nonostante SMB → controllare la modalità HighBG: aumentare `HighBGMaxSMB` e `HyperFactor`.
Se discesa troppo rapida dopo autoDrive → diminuire `autodrivePrebolus` e aumentare `AutodriveDeviation` (1.5).
Se tendenza alta durante l’attività → attivare il monitoraggio passi/FC e ridurre `SmbExerciseDamping` per mantenere un po’ di SMB.
```

---

## Risoluzione dei problemi e interpretazione dei log
1. **Leggere `rT.reason`**: ogni ciclo concatena i modelli (`plateau kicker`, `WCycle`, `NGR`). Cercate le frasi `AIMI+` per vedere le azioni adattative.【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/AIMIAdaptiveBasal.kt†L79-L112】【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/DetermineBasalAIMI2.kt†L2493-L2531】
2. **CSV AIMI**: `_records.csv` contiene tutte le variabili (passi, TDD, ISF). Utile per verificare se le vostre modalità o passi sono stati correttamente presi in considerazione.【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/OapsAIMIsmb.kt†L205-L276】
3. **PK/PD non si aggiorna più**: verificate che `PkpdEnabled` sia attivo e che non siate in modalità esercizio (il flag interrompe l’apprendimento).【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/pkpd/AdaptivePkPdEstimator.kt†L20-L38】
4. **Ritorno ai valori di default**: ogni chiave può essere reimpostata dal menu (i valori di default sono elencati più sopra). Se volete un reset completo, disattivate `PkpdEnabled`, eliminate i file `oapsaimi*_records.csv`, quindi riattivate.
5. **Nessun SMB**: verificate le sicurezze `isCriticalSafetyCondition` (BG<target, delta negativo, ecc.) e i limiti `maxIob`/`maxSMB`.【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/OapsAIMIsmb.kt†L296-L339】

---

## Riepilogo pedagogico
AIMI è un co-pilota adattativo:  
- Osserva le vostre glicemie, i vostri sforzi e le vostre modalità per regolare ISF, tempo di picco e SMB.  
- I suoi sistemi di sicurezza (plateau kicker, NGR, smorzamento SMB, sicurezza sport) evitano gli estremi pur lasciando evolvere l’apprendimento.  
- Lasciare che AIMI accumuli dati coerenti (profilo aggiornato, annunci pasti, tappe/pulsazioni affidabili) massimizza le sue prestazioni. Ogni parametro è regolabile per riflettere la vostra realtà, ma modificate un solo setting alla volta per poter osservare l’impatto nei log.  

Continuate a collaborare con AIMI: più fornirete dati stabili, più affinerà le sue previsioni e manterrà la vostra glicemia nel target.
