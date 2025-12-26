# 📦 AI Decision Auditor - Récapitulatif Complet

## Résumé

Le **AI Decision Auditor** est un système révolutionnaire de "Second Cerveau" pour AIMI qui challenge les décisions avec une modulation bornée et sécurisée.

**Statut :** ✅ Architecture complète créée, compilation réussie

---

## Fichiers Créés

### 1. Code Source (6 fichiers Kotlin)

#### `AuditorDataStructures.kt`
- **Localisation :** `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/advisor/auditor/`
- **Lignes :** ~407
- **Rôle :** Data classes pour Input (Snapshot, History, Stats7d) et Output (AuditorVerdict, BoundedAdjustments)
- **Highlight :** Conversion JSON bidirectionnelle, parsing robuste des verdicts

#### `AuditorPromptBuilder.kt`
- **Localisation :** `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/advisor/auditor/`
- **Lignes :** ~124
- **Rôle :** Construction du prompt complet avec instructions strictes
- **Highlight :** Anti "LLM prudent", principes AIMI explicites, sortie JSON forcée

#### `AuditorDataCollector.kt`
- **Localisation :** `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/advisor/auditor/`
- **Lignes :** ~332
- **Rôle :** Extraction des données AIMI runtime → AuditorInput
- **Highlight :** Bridge entre état AIMI et payload AI

#### `AuditorAIService.kt`
- **Localisation :** `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/advisor/auditor/`
- **Lignes :** ~288
- **Rôle :** Appels API (OpenAI, Gemini, DeepSeek, Claude)
- **Highlight :** Timeout gestion, parsing multi-format, coroutines

#### `DecisionModulator.kt`
- **Localisation :** `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/advisor/auditor/`
- **Lignes :** ~192
- **Rôle :** Application de la modulation bornée + triggers intelligents
- **Highlight :** Modes (AUDIT_ONLY, SOFT_MODULATION, HIGH_RISK_ONLY), shouldTriggerAudit()

#### `AuditorOrchestrator.kt`
- **Localisation :** `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/advisor/auditor/`
- **Lignes :** ~326
- **Rôle :** Chef d'orchestre principal, rate limiting, callbacks
- **Highlight :** Async audit, cache verdict, per-hour limiting

**Total Code :** ~1669 lignes

---

### 2. Configuration (3 fichiers Keys)

#### `BooleanKey.kt` (modifié)
- **Localisation :** `core/keys/src/main/kotlin/app/aaps/core/keys/`
- **Ajout :** 1 clé
  - `AimiAuditorEnabled` : Enable/disable auditor

#### `IntKey.kt` (modifié)
- **Localisation :** `core/keys/src/main/kotlin/app/aaps/core/keys/`
- **Ajouts :** 3 clés
  - `AimiAuditorMaxPerHour` : Max audits/heure (1-30, défaut 12)
  - `AimiAuditorTimeoutSeconds` : Timeout API (30-300s, défaut 120s)
  - `AimiAuditorMinConfidence` : Confiance min % (50-95%, défaut 65%)

#### `StringKey.kt` (modifié)
- **Localisation :** `core/keys/src/main/kotlin/app/aaps/core/keys/`
- **Ajout :** 1 clé
  - `AimiAuditorMode` : Mode (AUDIT_ONLY, SOFT_MODULATION, HIGH_RISK_ONLY)

**Réutilise :**
- API keys existantes : `AimiAdvisorOpenAIKey`, `AimiAdvisorGeminiKey`, `AimiAdvisorDeepSeekKey`, `AimiAdvisorClaudeKey`
- Provider selection : `AimiAdvisorProvider`

---

### 3. Documentation (4 fichiers Markdown)

#### `AI_DECISION_AUDITOR.md`
- **Localisation :** `docs/`
- **Lignes :** ~540
- **Rôle :** Documentation technique complète
- **Contenu :**
  - Vue d'ensemble
  - Architecture (2 niveaux)
  - Données envoyées (Snapshot, History, Stats)
  - Format de sortie
  - Déclenchement intelligent
  - Modes de modulation
  - Rate limiting
  - Providers supportés
  - Configuration
  - Exemples concrets
  - Sécurité
  - Innovation mondiale
  - Roadmap

#### `AI_AUDITOR_INTEGRATION_GUIDE.md`
- **Localisation :** `docs/`
- **Lignes :** ~365
- **Rôle :** Guide d'intégration pratique
- **Contenu :**
  - Point d'injection dans DetermineBasalAIMI2
  - Code exemple complet (injection, appel, callback)
  - Mode Async vs Sync
  - Helper functions
  - Logging & debugging
  - Préférences UI (XML)
  - Gestion erreurs
  - Tests

#### `AI_AUDITOR_SUMMARY.md`
- **Localisation :** `docs/`
- **Lignes :** ~268
- **Rôle :** Résumé exécutif
- **Contenu :**
  - Qu'est-ce que c'est
  - Architecture condensée
  - Format sortie
  - Modulations bornées
  - Modes
  - Providers
  - Configuration
  - Fichiers créés
  - Intégration
  - Sécurité
  - Innovation
  - Utilisation recommandée
  - Exemple concret
  - Philosophie

#### `AI_AUDITOR_TEST_CASES.md`
- **Localisation :** `docs/`
- **Lignes :** ~470
- **Rôle :** Exemples et cas de test
- **Contenu :**
  - 8 cas de test complets avec JSON input + output attendu
  - Pattern recognition examples
  - Anti-patterns (LLM trop prudent)
  - Cas : stacking risk, montée persistante, prédiction absente, BG bas, mode repas, shift to TBR, autodrive, wcycle

---

## Architecture Globale

```
┌─────────────────────────────────────────────────────────┐
│                  DetermineBasalAIMI2                    │
│                                                         │
│  ┌─────────────────────────────────────────────────┐  │
│  │  1. Calcul Décision AIMI (SMB, TBR, interval)  │  │
│  └──────────────────┬──────────────────────────────┘  │
│                     │                                   │
│                     ▼                                   │
│  ┌─────────────────────────────────────────────────┐  │
│  │      🧠 AI Decision Auditor (Async)             │  │
│  │                                                 │  │
│  │  AuditorOrchestrator.auditDecision()           │  │
│  │    │                                            │  │
│  │    ├─► AuditorDataCollector (extract data)     │  │
│  │    ├─► AuditorPromptBuilder (build prompt)     │  │
│  │    ├─► AuditorAIService (call API)             │  │
│  │    └─► DecisionModulator (apply modulation)    │  │
│  │                                                 │  │
│  └──────────────────┬──────────────────────────────┘  │
│                     │                                   │
│                     ▼ (callback)                        │
│  ┌─────────────────────────────────────────────────┐  │
│  │  2. Modulated Decision (if applicable)         │  │
│  │     - finalSmb = modulated.smbU                │  │
│  │     - finalInterval = modulated.intervalMin    │  │
│  │     - preferTbr = modulated.preferTbr          │  │
│  └─────────────────────────────────────────────────┘  │
│                                                         │
│  ┌─────────────────────────────────────────────────┐  │
│  │  3. Return RT avec decision (orig ou modulée)  │  │
│  └─────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────┘
```

---

## Flux de Données

```
AIMI Runtime State
    │
    ├─► BG, delta, IOB, COB, profile, PKPD, modes...
    │
    ▼
AuditorDataCollector
    │
    ├─► Build Snapshot (current state)
    ├─► Build History (45-60 min trajectory)
    └─► Build Stats7d (patient context)
    │
    ▼
AuditorInput (JSON)
    │
    ▼
AuditorPromptBuilder
    │
    ├─► System prompt (role, constraints)
    ├─► Input data (JSON payload)
    ├─► Instructions (AIMI principles, verdict selection)
    └─► Output schema (strict JSON format)
    │
    ▼
Complete Prompt (text)
    │
    ▼
AuditorAIService
    │
    ├─► Select provider (OpenAI/Gemini/DeepSeek/Claude)
    ├─► Get API key from preferences
    ├─► HTTP POST request (with timeout)
    └─► Parse response
    │
    ▼
AuditorVerdict (JSON)
    │
    ├─► verdict: CONFIRM|SOFTEN|SHIFT_TO_TBR
    ├─► confidence: 0.0-1.0
    ├─► riskFlags: [...]
    ├─► evidence: [...]
    └─► boundedAdjustments: { smbFactorClamp, intervalAddMin, ... }
    │
    ▼
DecisionModulator
    │
    ├─► Check mode (AUDIT_ONLY / SOFT_MODULATION / HIGH_RISK_ONLY)
    ├─► Check confidence >= threshold
    ├─► Apply bounded adjustments
    └─► Generate ModulatedDecision
    │
    ▼
ModulatedDecision
    │
    ├─► smbU (modulated)
    ├─► intervalMin (modulated)
    ├─► preferTbr
    └─► modulationReason (log message)
    │
    ▼
Callback to DetermineBasalAIMI2
    │
    ├─► Log verdict + modulation in consoleLog
    └─► Update decision variables (finalSmb, finalInterval, etc.)
```

---

## Principes de Sécurité

### ✅ TOUJOURS

1. **Modulation bornée uniquement**
   - SMB factor : 0.0-1.0
   - Interval add : 0-6 min
   - TBR factor : 0.8-1.2

2. **Jamais en P1/P2** (prebolus windows)

3. **Offline = No-op** (pas d'erreur si API down)

4. **Rate limiting** (max 12/heure par défaut)

5. **Timeout strict** (120s max)

6. **Confidence threshold** (65% min par défaut)

### ❌ JAMAIS

1. **Dosage libre** ("give 1.7U")

2. **Commande directe** à la pompe

3. **Modification profil** (ISF/IC/basal)

4. **Blocage du loop** si API timeout

---

## Verdicts Possibles

### CONFIRM
- **Signification :** Décision AIMI approuvée telle quelle
- **Action :** Aucune modulation
- **Exemple :** "BG rising with low IOB, SMB appropriate"

### SOFTEN
- **Signification :** Réduire prudence
- **Actions possibles :**
  - SMB factor : 0.3-0.9
  - Interval add : 0-6 min
  - Optionnel : preferTbr
- **Exemple :** "IOB activity at peak, reduce SMB 50%"

### SHIFT_TO_TBR
- **Signification :** Privilégier TBR
- **Actions possibles :**
  - SMB factor : 0.0-0.3 (très bas)
  - TBR factor : 0.8-1.2
  - preferTbr : true
- **Exemple :** "High IOB + no prediction, shift to TBR"

---

## Modes de Fonctionnement

### 1. AUDIT_ONLY (défaut)
- **Comportement :** Pas de modulation
- **Log :** Verdicts uniquement
- **Usage :** Découverte, analyse patterns

### 2. SOFT_MODULATION
- **Comportement :** Applique modulation si confidence ≥ seuil
- **Conditions :**
  - Confidence ≥ 65% (configurable)
  - Pas en P1/P2
  - API ok
- **Usage :** Production (après validation)

### 3. HIGH_RISK_ONLY
- **Comportement :** Applique uniquement si riskFlags non vide
- **Conditions :**
  - riskFlags.isNotEmpty()
  - Confidence ≥ seuil
- **Usage :** Conservateur

---

## Triggers (Quand Auditer)

L'audit se déclenche si **AU MOINS UNE** condition :

1. ✅ `delta > 2.0` OU `shortAvgDelta > 1.5`
2. ✅ `BG < 120` ET `SMB proposé > 0`
3. ✅ `SMB cumulé 30min > seuil` (1.5-2.5U)
4. ✅ `Prédiction absente` ET `SMB proposé > 0`
5. ✅ `IOB > 3.0` ET `SMB > 0.3`

**Exception :** ❌ JAMAIS si `inPrebolusWindow == true`

---

## Rate Limiting

### Per-Hour Limit
- **Défaut :** 12 audits/heure
- **Configurable :** 1-30
- **Reset :** Chaque heure pleine

### Minimum Interval
- **Fixe :** 5 minutes entre 2 audits
- **Non configurable**

### Cache Verdict
- **Durée :** 5 minutes
- **Usage :** Éviter appels redondants

---

## Providers AI

### OpenAI (ChatGPT)
- **Modèle :** GPT-4o
- **URL :** `https://api.openai.com/v1/chat/completions`
- **Key :** `AimiAdvisorOpenAIKey`
- **Format :** `response_format: json_object`

### Gemini (Google)
- **Modèle :** gemini-2.0-flash-exp
- **URL :** `https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash-exp:generateContent`
- **Key :** `AimiAdvisorGeminiKey`
- **Format :** `responseMimeType: application/json`

### DeepSeek
- **Modèle :** deepseek-chat
- **URL :** `https://api.deepseek.com/v1/chat/completions`
- **Key :** `AimiAdvisorDeepSeekKey`
- **Format :** `response_format: json_object`

### Claude (Anthropic)
- **Modèle :** claude-3-5-sonnet-20241022
- **URL :** `https://api.anthropic.com/v1/messages`
- **Key :** `AimiAdvisorClaudeKey`
- **Format :** Texte (parse JSON from content)

---

## Compilation

✅ **Status :** Compilation réussie (0 erreurs)

```bash
./gradlew compileFullDebugKotlin
# BUILD SUCCESSFUL
```

---

## Prochaines Étapes

### Phase 1 : Intégration Core (à faire)
1. ✅ Architecture créée
2. ⏭️ **Injection dans DetermineBasalAIMI2**
   - Inject `AuditorOrchestrator`
   - Appel après calcul SMB/TBR
   - Callback handling
   - Helper function `calculateSmbLast30Min()`

3. ⏭️ **UI Préférences**
   - Ajouter section "AI Decision Auditor" dans AIMI prefs
   - XML layout (SwitchPreference, ListPreference, SeekBars)
   - Arrays pour modes

### Phase 2 : Tests & Validation
4. ⏭️ **Tests Unitaires**
   - Test data structures (JSON parsing)
   - Test prompt builder
   - Test modulator logic
   - Test triggers

5. ⏭️ **Tests d'Intégration**
   - Mock AI responses
   - Verify modulation applied
   - Check rate limiting
   - Verify P1/P2 respect

### Phase 3 : Terrain
6. ⏭️ **Beta Testing**
   - Mode AUDIT_ONLY
   - Collect verdicts
   - Analyze patterns
   - Refine prompt

7. ⏭️ **Production Rollout**
   - Mode SOFT_MODULATION
   - Monitor impact
   - Adjust confidence thresholds
   - Iterate on prompt

---

## Innovation

### Première Mondiale

À notre connaissance, **aucune boucle fermée** n'a :
- ✅ AI qui challenge décisions (pas juste "conseils flous")
- ✅ Modulation bornée (pas dosage libre)
- ✅ Mode offline transparent
- ✅ Respect contraintes métier (P1/P2, modes, etc.)

### Philosophie

> *"Le meilleur de l'humain (règles AIMI) + le meilleur de l'AI (pattern recognition complexe)"*

> *"Deux cerveaux valent mieux qu'un, surtout quand l'un ne peut pas faire n'importe quoi."*

---

## Utilisation Recommandée

### Semaines 1-2 : Découverte
```
Mode : AUDIT_ONLY
Confidence min : N/A (log all)
Observer : Verdicts dans consoleLog
Analyser : Patterns détectés, faux positifs/négatifs
```

### Semaines 3-6 : Test Prudent
```
Mode : HIGH_RISK_ONLY
Confidence min : 80%
Observer : Impact situations à risque uniquement
Analyser : Réduction hypos, gestion montées
```

### Production
```
Mode : SOFT_MODULATION
Confidence min : 65%
Observer : Impact global
Analyser : TIR, hypos, stabilité
Ajuster : Confidence threshold selon retours
```

---

## Contact & Support

Cette fonctionnalité est **expérimentale**.

Retours terrain **essentiels** pour :
- Affiner le prompt
- Ajuster confidence thresholds
- Identifier nouveaux patterns
- Améliorer triggers

---

## Conclusion

Le **AI Decision Auditor** est prêt. L'architecture est solide, la compilation passe, les garde-fous sont en place.

Il reste à :
1. Intégrer dans le flow AIMI
2. Créer l'UI de configuration
3. Tester sur cas réels

🧠 **Le Second Cerveau attend d'être activé.**

---

*Dernière mise à jour : 2025-12-26*
*Version : 1.0 (Architecture complète)*
