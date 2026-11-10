# 🧠 AIMI AI Decision Auditor - "Le Second Cerveau"

## Vue d'ensemble

L'**AI Decision Auditor** est un système révolutionnaire qui introduit un « second cerveau » pour AIMI. Il ne prend **jamais** de décisions directes, mais **challenge** les décisions d'AIMI et peut les **moduler de manière bornée et sécurisée**.

### Architecture : 2 Niveaux

1. **Audit Cognitif** : Analyse la décision d'AIMI dans son contexte complet
2. **Modulateur Borné** : Applique des ajustements strictement contrôlés

---

## Principes Fondamentaux

### ✅ Ce que fait l'auditeur

- **Challenge la décision** : Évalue si le SMB/TBR proposé est cohérent
- **Modulation bornée** : Peut réduire le SMB (facteur 0.0-1.0), augmenter l'interval (+0-6min), préférer TBR
- **Détection de risques** : Identifie les patterns dangereux (stacking, montée ignorée, hypo risk...)
- **Mode dégradé** : Fonctionne même sans prédiction (interval + preferTBR)

### ❌ Ce qu'il ne fait JAMAIS

- **Pas de dosage libre** : Ne propose jamais "envoie 1.7U"
- **Pas de commande directe** : N'actionne jamais directement la pompe
- **Pas de modification de profil** : Ne touche jamais ISF/IC/basale de profil
- **Pas d'intervention en P1/P2** : Respecte totalement les fenêtres prebolus

### 🔒 Mode Offline = Zéro Impact

Si l'API est offline, timeout, ou key manquante → **aucun impact**, AIMI fonctionne normalement.

---

## Données Envoyées au LLM

### A) Snapshot (contexte immédiat)

**Glycémie :**
- `bg`, `delta`, `shortAvgDelta`, `longAvgDelta`
- `unit`, `timestamp`, `cgmAgeMin`, `noise`

**Insuline :**
- `iob`, `iobActivity` (% d'activité PKPD actuelle)
- `cob` (carbsOnBoard)

**Sensibilité & Cibles :**
- `isfProfile` (ISF du profil)
- `isfUsed` (ISF fusionné : PKPD + learners + autosens)
- `ic` (ratio insuline/carbs)
- `target` (cible glycémique)

**PKPD :**
- `diaMin` (durée d'action insuline, minutes)
- `peakMin` (pic d'action, minutes)
- `tailFrac` (fraction de queue)
- `onsetConfirmed` (onset confirmé ou non)
- `residualEffect` (effet résiduel actuel)

**Activité :**
- `steps5min`, `steps30min`
- `hrAvg5`, `hrAvg15` (heartrate)

**États :**
- `modeType` (breakfast, lunch, dinner, highCarb, snack, meal, null)
- `modeRuntimeMin` (durée du mode actuel)
- `autodriveState` (OFF, EARLY, CONFIRMED)
- `wcyclePhase` + `wcycleFactor` (phase cycle + facteur appliqué)

**Limites :**
- `maxSMB`, `maxSMBHB`, `maxIOB`, `maxBasal`
- `tbrMaxMode`, `tbrMaxAutoDrive`

**Décision AIMI :**
- `smbU` (SMB proposé, U)
- `tbrUph` (TBR proposé, U/h)
- `tbrMin` (durée TBR, minutes)
- `intervalMin` (interval proposé)
- `reasonTags` (liste des raisons de décision)

**Dernière livraison :**
- `lastBolusU`/`lastBolusTime`
- `lastSmbU`/`lastSmbTime`
- `lastTbrRate`/`lastTbrTime`

### B) History (cinématique 45-60 min, max 12 points)

- `bgSeries` (glycémie)
- `deltaSeries` (deltas)
- `iobSeries` (IOB)
- `tbrSeries` (TBR appliquées)
- `smbSeries` (SMB appliqués)
- `hrSeries` (heartrate)
- `stepsSeries` (steps)

### C) Stats 7j (contexte patient)

- `tir` (Time In Range, %)
- `hypoPct` (temps en hypo, %)
- `hyperPct` (temps en hyper, %)
- `meanBG` (BG moyen)
- `cv` (coefficient de variation)
- `tdd7dAvg` (TDD moyen 7j)
- `basalPct` / `bolusPct`

---

## Format de Sortie (JSON Strict)

Le LLM retourne **uniquement** ce JSON :

```json
{
  "verdict": "CONFIRM|SOFTEN|SHIFT_TO_TBR",
  "confidence": 0.85,
  "degradedMode": false,
  "riskFlags": ["stacking_risk", "rapid_rise_ignored"],
  "evidence": [
    "IOB activity at peak (85%), last SMB 8min ago, proposed 0.8U risks stacking",
    "BG rising +3 mg/dL/5min for 45min, low IOB activity (15%), SMB 0.5U reasonable",
    "Prediction absent, degraded mode: recommend interval +3min + preferTBR"
  ],
  "boundedAdjustments": {
    "smbFactorClamp": 0.7,
    "intervalAddMin": 3,
    "preferTbr": false,
    "tbrFactorClamp": 1.0
  },
  "debugChecks": [
    "check_prediction_visible_in_UI",
    "check_pkpd_used_in_smb_throttle",
    "check_autodrive_not_sticky"
  ]
}
```

### Verdicts

1. **CONFIRM** : Décision AIMI approuvée telle quelle
2. **SOFTEN** : Réduire SMB (facteur 0.3-0.9) et/ou augmenter interval (0-+6min)
3. **SHIFT_TO_TBR** : SMB très réduit (0-0.3) + TBR modérée (0.8-1.2)

---

## Déclenchement Intelligent

L'audit n'est **pas** lancé toutes les 5 minutes. Il se déclenche si :

1. **Delta élevé** : `delta > 2` OU `shortAvgDelta > 1.5`
2. **BG bas + SMB** : `BG < 120` ET `SMB proposé > 0`
3. **SMB cumulé élevé** : SMB 30min > seuil (1.5U normal, 2.5U en mode repas)
4. **Prédiction absente** : Pas de prédiction mais SMB proposé
5. **IOB très élevé** : `IOB > 3.0` ET `SMB > 0.3`

**Exception : Jamais en fenêtre P1/P2** (prebolus)

---

## Modes de Modulation

### 1. **AUDIT_ONLY** (par défaut)
- Aucune modulation appliquée
- Log uniquement (pour analyse)
- Mode découverte

### 2. **SOFT_MODULATION**
- Applique la modulation si :
  - `confidence >= seuil` (défaut 65%)
  - Pas en fenêtre P1/P2
  - API ok + réponse < 2 min

### 3. **HIGH_RISK_ONLY**
- Applique la modulation **uniquement** si :
  - `riskFlags` non vide
  - `confidence >= seuil`
  - Mode haute prudence

---

## Rate Limiting

Pour éviter les appels excessifs :

- **Max par heure** : configurable (défaut 12/heure)
- **Interval minimum** : 5 minutes entre 2 audits
- **Cache de verdict** : 5 minutes

---

## Providers Supportés

Réutilise l'infrastructure existante (mêmes API keys) :

1. **ChatGPT (GPT-4o)** - OpenAI
2. **Gemini (2.0 Flash)** - Google
3. **DeepSeek (Chat)** - DeepSeek
4. **Claude (3.5 Sonnet)** - Anthropic

---

## Configuration (Préférences)

### Clés ajoutées

**BooleanKey :**
- `AimiAuditorEnabled` : Activer/désactiver l'auditeur

**IntKey :**
- `AimiAuditorMaxPerHour` : Max audits/heure (1-30, défaut 12)
- `AimiAuditorTimeoutSeconds` : Timeout API (30-300s, défaut 120s)
- `AimiAuditorMinConfidence` : Confiance min % pour moduler (50-95%, défaut 65%)

**StringKey :**
- `AimiAuditorMode` : Mode (AUDIT_ONLY, SOFT_MODULATION, HIGH_RISK_ONLY)

**Réutilise :**
- `AimiAdvisorProvider` : Provider (OPENAI, GEMINI, DEEPSEEK, CLAUDE)
- `AimiAdvisorOpenAIKey`, `AimiAdvisorGeminiKey`, etc. : API keys

---

## Exemples de Modulation

### Exemple 1 : Softening SMB

**Contexte :**
- BG = 180 mg/dL, delta = +4
- IOB = 2.5 U, IOB activity = 75%
- AIMI propose : SMB 0.8U, interval 3min

**Verdict AI :**
```json
{
  "verdict": "SOFTEN",
  "confidence": 0.85,
  "riskFlags": ["stacking_risk"],
  "evidence": ["IOB activity at 75%, last SMB 8min ago, stacking risk"],
  "boundedAdjustments": {
    "smbFactorClamp": 0.5,
    "intervalAddMin": 3,
    "preferTbr": false,
    "tbrFactorClamp": 1.0
  }
}
```

**Résultat :**
- SMB modulé : `0.8 × 0.5 = 0.4 U`
- Interval modulé : `3 + 3 = 6 min`

### Exemple 2 : Shift to TBR

**Contexte :**
- BG = 200 mg/dL, delta = +1
- IOB = 3.5 U, IOB activity = 85%
- Prédiction absente
- AIMI propose : SMB 1.0U, interval 3min

**Verdict AI :**
```json
{
  "verdict": "SHIFT_TO_TBR",
  "confidence": 0.90,
  "degradedMode": true,
  "riskFlags": ["prediction_missing", "stacking_risk"],
  "evidence": ["No prediction, high IOB activity, prefer TBR"],
  "boundedAdjustments": {
    "smbFactorClamp": 0.2,
    "intervalAddMin": 0,
    "preferTbr": true,
    "tbrFactorClamp": 1.1
  }
}
```

**Résultat :**
- SMB modulé : `1.0 × 0.2 = 0.2 U`
- preferTBR activé → décision basale privilégiée
- TBR factor : 1.1

---

## Intégration dans DetermineBasalAIMI2

**Point d'injection :**

Après le calcul de la décision AIMI, mais **avant** finalisation :

```kotlin
// Appel de l'auditeur (async)
auditorOrchestrator.auditDecision(
    bg = bg,
    delta = delta,
    // ... tous les paramètres ...
    callback = { verdict, modulated ->
        // verdict : AuditorVerdict?
        // modulated : ModulatedDecision
        
        // Si modulation appliquée :
        if (modulated.appliedModulation) {
            consoleLog.add("🧠 AI Auditor: ${modulated.modulationReason}")
            consoleLog.add("   Verdict: ${verdict?.verdict}, Confidence: ${verdict?.confidence}")
            
            // Appliquer la décision modulée
            smbProposed = modulated.smbU
            intervalMin = modulated.intervalMin
            preferTbr = modulated.preferTbr
        }
    }
)
```

---

## Éviter le "LLM Prudent"

Le prompt est conçu pour éviter le blocage systématique :

1. **Principes AIMI explicites** : "ligne la plus droite", modes repas, etc.
2. **BG < 120 ≠ paralysie** : Prudence mais pas blocage
3. **Pas de prédiction ≠ stop** : Mode dégradé (interval + preferTBR)
4. **Activité insuline haute** : Réduire SMB, pas bloquer
5. **Montée persistante + activité basse** : SMB acceptable

---

## Sécurité

### Garde-fous

1. **Jamais en P1/P2** : Aucune modulation pendant prebolus
2. **Confidence seuil** : Uniquement si confiance >= X%
3. **Facteurs bornés** : 
   - SMB factor : 0.0-1.0
   - Interval add : 0-6 min
   - TBR factor : 0.8-1.2
4. **Timeout strict** : 120s max, puis fallback
5. **Rate limiting** : Max 12/heure

### Mode Offline

Si API offline, key manquante, timeout, erreur :
→ **Aucun impact**, AIMI continue normalement avec sa décision originale.

---

## Architecture Fichiers

```
plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/advisor/auditor/
├── AuditorDataStructures.kt     # Input/Output data classes
├── AuditorPromptBuilder.kt      # Prompt generation avec instructions strictes
├── AuditorDataCollector.kt      # Extraction des données AIMI
├── AuditorAIService.kt          # Appels API (OpenAI/Gemini/DeepSeek/Claude)
├── DecisionModulator.kt         # Logique de modulation bornée + triggers
└── AuditorOrchestrator.kt       # Chef d'orchestre principal
```

---

## Prochaines Étapes

1. ✅ **Architecture créée** (ce que nous venons de faire)
2. ⏭️ **Intégration dans DetermineBasalAIMI2** (appel de l'orchestrateur)
3. ⏭️ **UI Préférences** (section AIMI → AI Decision Auditor)
4. ⏭️ **Tests avec vrais cas** (montées, hypos, stacking...)
5. ⏭️ **Affinage du prompt** (selon retours terrain)
6. ⏭️ **Logging & Analytics** (tracer les modulations)

---

## Innovation Mondiale

À notre connaissance, **aucune boucle fermée au monde** n'a implémenté un tel système :

- Pas de "AI qui décide" (trop risqué)
- Pas de "AI en conseil flou" (pas d'impact)
- Mais : **AI en modulateur borné** = équilibre parfait

C'est une **première mondiale** dans le diabète.

---

## Utilisation Recommandée

### Phase 1 : Découverte (1-2 semaines)
- Mode : **AUDIT_ONLY**
- Observer les verdicts dans les logs
- Analyser les patterns détectés

### Phase 2 : Test Prudent (2-4 semaines)
- Mode : **HIGH_RISK_ONLY**
- Confidence min : 80%
- Observer l'impact sur les situations à risque

### Phase 3 : Production (après validation)
- Mode : **SOFT_MODULATION**
- Confidence min : 65%
- Monitoring continu

---

## Contact & Support

Cette fonctionnalité est expérimentale et révolutionnaire. 
Retours terrain essentiels pour affiner le système.

**Philosophie : Le meilleur de l'humain (AIMI règles) + le meilleur de l'AI (pattern recognition complexe)**

🧠 *"Deux cerveaux valent mieux qu'un, surtout quand l'un ne peut pas faire n'importe quoi."*
