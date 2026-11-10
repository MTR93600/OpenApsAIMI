# 🧠 AI Decision Auditor - Résumé Exécutif

## Qu'est-ce que c'est ?

Le **AI Decision Auditor** est un système révolutionnaire de **« Second Cerveau »** pour AIMI qui :

- ✅ **Challenge** les décisions d'AIMI avec une analyse contextuelle complète
- ✅ **Module** de manière **bornée et sécurisée** (jamais de dosage libre)
- ✅ **N'agit JAMAIS** directement (pas de commande directe à la pompe)
- ✅ **Offline = Zéro impact** (mode dégradé transparent)

---

## Architecture

### 2 Niveaux

1. **Audit Cognitif** → Analyse LLM de la décision AIMI dans son contexte complet
2. **Modulateur Borné** → Applique des ajustements strictement contrôlés

### Données Envoyées au LLM

**Snapshot (ici & maintenant) :**
- Glycémie : bg, delta, shortAvgDelta, longAvgDelta, noise, cgmAge
- Insuline : IOB, IOB activity (PKPD %), COB
- Sensibilité : ISF profile, ISF utilisé (fusionné), IC, target
- PKPD : DIA, peak, tail fraction, onset, residual effect
- Activité : steps 5/30min, heartrate 5/15min
- États : mode repas, autodrive, wcycle phase/factor
- Limites : maxSMB, maxSMBHB, maxIOB, maxBasal, TBR max
- Décision AIMI : SMB proposé, TBR proposé, interval, reason tags
- Dernière livraison : last bolus/SMB/TBR time & amount

**History (45-60 min, max 12 points) :**
- Séries : BG, delta, IOB, TBR, SMB, HR, steps

**Stats 7j :**
- TIR, hypo%, hyper%, meanBG, CV, TDD 7j avg, basal%/bolus%

---

## Format de Sortie

**JSON Strict :**

```json
{
  "verdict": "CONFIRM|SOFTEN|SHIFT_TO_TBR",
  "confidence": 0.85,
  "degradedMode": false,
  "riskFlags": ["stacking_risk"],
  "evidence": ["IOB activity at 85%, stacking risk", "..."],
  "boundedAdjustments": {
    "smbFactorClamp": 0.7,      // 0.0-1.0
    "intervalAddMin": 3,         // 0-6 min
    "preferTbr": false,
    "tbrFactorClamp": 1.0        // 0.8-1.2
  },
  "debugChecks": ["check_prediction_visible", "..."]
}
```

### Verdicts Possibles

1. **CONFIRM** → Décision AIMI approuvée
2. **SOFTEN** → SMB réduit (0.3-0.9×), interval augmenté (0-+6min)
3. **SHIFT_TO_TBR** → SMB très réduit (0-0.3×), TBR privilégié

---

## Modulations Bornées

### ❌ Ce qu'il ne fait JAMAIS

- Dosage libre ("envoie 1.7U")
- Commande directe à la pompe
- Modification de profil (ISF/IC/basal)
- Intervention en fenêtre P1/P2

### ✅ Ce qu'il peut faire

- **SMB factor** : multiplier SMB proposé par 0.0-1.0
- **Interval add** : ajouter 0-6 minutes à l'interval
- **Prefer TBR** : activer préférence TBR
- **TBR factor** : multiplier TBR par 0.8-1.2

---

## Déclenchement Intelligent

**Pas toutes les 5 min !** Trigger si :

1. Delta > 2 OU shortAvgDelta > 1.5
2. BG < 120 ET SMB > 0
3. SMB cumulé 30min > seuil (1.5-2.5U)
4. Prédiction absente ET SMB > 0
5. IOB > 3.0 ET SMB > 0.3

**Exception :** JAMAIS en fenêtre P1/P2

---

## Modes

### 1. AUDIT_ONLY (défaut)
- Aucune modulation
- Log uniquement
- Mode découverte

### 2. SOFT_MODULATION
- Applique modulation si confidence ≥ seuil (65%)
- Respecte P1/P2
- Mode production

### 3. HIGH_RISK_ONLY
- Uniquement si riskFlags non vide
- Mode conservateur

---

## Rate Limiting

- **Max/heure** : 12 (configurable 1-30)
- **Interval min** : 5 minutes
- **Cache** : 5 minutes

---

## Providers Supportés

Réutilise API keys existantes :
1. ChatGPT (GPT-4o)
2. Gemini (2.0 Flash)
3. DeepSeek (Chat)
4. Claude (3.5 Sonnet)

---

## Configuration (Préférences)

**Nouvelles clés :**

- `AimiAuditorEnabled` : ON/OFF
- `AimiAuditorMode` : AUDIT_ONLY / SOFT_MODULATION / HIGH_RISK_ONLY
- `AimiAuditorMaxPerHour` : 1-30 (défaut 12)
- `AimiAuditorTimeoutSeconds` : 30-300s (défaut 120s)
- `AimiAuditorMinConfidence` : 50-95% (défaut 65%)

**Réutilise :**
- API keys existantes (OpenAI, Gemini, DeepSeek, Claude)
- Provider selection existant

---

## Fichiers Créés

```
plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/advisor/auditor/
├── AuditorDataStructures.kt     # Input/Output data classes (407 lignes)
├── AuditorPromptBuilder.kt      # Prompt avec instructions strictes (124 lignes)
├── AuditorDataCollector.kt      # Extraction données AIMI (332 lignes)
├── AuditorAIService.kt          # Appels API multi-providers (288 lignes)
├── DecisionModulator.kt         # Modulation bornée + triggers (192 lignes)
└── AuditorOrchestrator.kt       # Chef d'orchestre (326 lignes)

Total : ~1669 lignes de code
```

**Clés ajoutées :**

```
core/keys/src/main/kotlin/app/aaps/core/keys/
├── BooleanKey.kt  (+1 clé : AimiAuditorEnabled)
├── IntKey.kt      (+3 clés : MaxPerHour, TimeoutSeconds, MinConfidence)
└── StringKey.kt   (+1 clé : AimiAuditorMode)
```

**Documentation :**

```
docs/
├── AI_DECISION_AUDITOR.md           # Spec complète (540 lignes)
└── AI_AUDITOR_INTEGRATION_GUIDE.md  # Guide d'intégration (365 lignes)
```

---

## Intégration

### Point d'injection

Dans `DetermineBasalAIMI2.determineBasal()`, après calcul SMB/TBR :

```kotlin
auditorOrchestrator.auditDecision(
    bg, delta, shortAvgDelta, longAvgDelta,
    glucoseStatus, iob, cob, profile, pkpdRuntime,
    isfUsed, smbProposed, tbrRate, tbrDuration, intervalMin,
    maxSMB, maxSMBHB, maxIOB, maxBasal, reasonTags,
    modeType, modeRuntimeMin, autodriveState,
    wcyclePhase, wcycleFactor, tbrMaxMode, tbrMaxAutoDrive,
    smb30min, predictionAvailable, inPrebolusWindow
) { verdict, modulated ->
    if (modulated.appliedModulation) {
        consoleLog.add("🧠 AI Auditor: ${modulated.modulationReason}")
        finalSmb = modulated.smbU
        finalInterval = modulated.intervalMin
        preferTbr = modulated.preferTbr
    }
}
```

---

## Sécurité

### Garde-fous

1. ✅ Jamais en P1/P2
2. ✅ Confidence ≥ seuil
3. ✅ Facteurs strictement bornés
4. ✅ Timeout 120s max
5. ✅ Rate limiting
6. ✅ Mode offline = no-op

### Mode Dégradé

Si API offline, timeout, erreur :
→ **Aucun impact**, AIMI continue normalement

---

## Innovation Mondiale

**Première mondiale** dans le domaine des boucles fermées :

- Pas de "AI qui décide" (trop risqué)
- Pas de "AI en conseil flou" (pas d'impact)
- Mais : **AI en modulateur borné** ✅

Équilibre parfait entre :
- **Sécurité** (bornes strictes, pas de commande directe)
- **Innovation** (pattern recognition complexe par LLM)
- **Pragmatisme** (offline = zéro impact)

---

## Utilisation Recommandée

### Phase 1 : Découverte (1-2 semaines)
- Mode : AUDIT_ONLY
- Observer verdicts dans logs
- Analyser patterns détectés

### Phase 2 : Test Prudent (2-4 semaines)
- Mode : HIGH_RISK_ONLY
- Confidence min : 80%
- Observer impact situations à risque

### Phase 3 : Production
- Mode : SOFT_MODULATION
- Confidence min : 65%
- Monitoring continu

---

## Exemple Concret

**Contexte :**
- BG = 180 mg/dL, delta = +4, IOB = 2.5U (activity 75%)
- AIMI propose : SMB 0.8U, interval 3min

**AI Verdict :**
```
Verdict: SOFTEN (confidence 0.85)
Risk Flags: stacking_risk
Evidence: "IOB activity at 75%, last SMB 8min ago, stacking risk"
Adjustments: smbFactorClamp=0.5, intervalAddMin=3
```

**Résultat :**
- SMB modulé : 0.8 × 0.5 = **0.4U** ✅
- Interval modulé : 3 + 3 = **6 min** ✅

---

## Pourquoi c'est Révolutionnaire

### Le Problème du "LLM Prudent"

Les LLMs ont tendance à être trop prudents ("mieux vaut ne rien faire que de se tromper").

### La Solution AIMI

1. **Prompt structuré** avec principes AIMI explicites
2. **Sortie bornée** (JSON strict, pas de texte libre)
3. **Modulation uniquement** (jamais de dosage direct)
4. **Contexte PKPD** (activité insuline, tail fraction, etc.)
5. **Respect P1/P2** (jamais toucher prebolus)

Le LLM devient un **pattern matcher expert**, pas un décideur.

---

## Compiltion

✅ **Compilation réussie** (0 erreurs)

Toute l'infrastructure est prête. Il reste :

1. Intégration dans `DetermineBasalAIMI2.determineBasal()`
2. UI préférences
3. Tests terrain

---

## Philosophie

> *"Le meilleur de l'humain (règles AIMI) + le meilleur de l'AI (pattern recognition complexe)"*

> *"Deux cerveaux valent mieux qu'un, surtout quand l'un ne peut pas faire n'importe quoi."*

---

## Contact

Cette fonctionnalité est **expérimentale** et **révolutionnaire**.

Retours terrain essentiels pour affiner le système.

🧠 **Le Second Cerveau est prêt.**
