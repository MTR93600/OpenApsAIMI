# 🔍 ANALYSE COMPLÈTE DU PLUGIN OpenAPS AIMI - Phase 1
## Inventaire des Modules et Fonctionnalités

**Date** : 26 janvier 2026  
**Fichiers analysés** : 133 fichiers Kotlin  
**Objectif** : Mise à jour exhaustive du manuel utilisateur

---

## 📊 STRUCTURE GLOBALE DU PLUGIN

### **Statistiques**
- **Total fichiers** : 133 fichiers .kt
- **Modules principaux** : 23 répertoires
- **Fichier principal** : DetermineBasalAIMI2.kt (363 KB - moteur décisionnel)
- **Configuration** : OpenAPSAIMIPlugin.kt (108 KB - UI préférences)

---

## 🗂️ MODULES IDENTIFIÉS

### **1. ADVISOR (31 fichiers) - Assistant IA & Sécurité**
📁 `/advisor/`

#### **Sous-modules** :
- **auditor/** (13 fichiers) - **🛡️ Système de Sécurité**
  - `AuditorAIService.kt` - Cerveau IA de sécurité
  - `AuditorOrchestrator.kt` - Orchestrateur des vérifications
  - `AuditorDataCollector.kt` - Collecte données pour analyse
  - `AuditorPromptBuilder.kt` - Construction prompts LLM
  - `LocalSentinel.kt` - Garde local (pre-LLM)
  - `DecisionModulator.kt` - Modulation des décisions
  - `ui/` - Interface utilisateur status Auditor

- **meal/** (8 fichiers) - **📸 Meal Advisor (Vision IA)**
  - `MealAdvisorActivity.kt` - UI principale
  - `FoodRecognitionService.kt` - Service reconnaissance
  - `OpenAIVisionProvider.kt` - GPT-4o Vision
  - `GeminiVisionProvider.kt` - Gemini 2.5 Flash
  - `ClaudeVisionProvider.kt` - Claude 3.5 Sonnet
  - `DeepSeekVisionProvider.kt` - DeepSeek Vision
  - `AIVisionProvider.kt` - Interface commune

- **gestation/** (1 fichier) - **🤰 Grossesse**
  - `GestationalAutopilot.kt` - Pilote automatique grossesse

- **data/** (1 fichier)
  - `AdvisorHistoryRepository.kt` - Historique recommendations

- **diag/** (1 fichier)
  - `AimiDiagnosticsManager.kt` - Diagnostics système

- **Racine advisor/** (7 fichiers)
  - `AimiAdvisorService.kt` - **🤖 Profile Advisor principal**
  - `AiCoachingService.kt` - Coach IA
  - `AimiProfileAdvisorActivity.kt` - UI advisor
  - `AimiClinicalReportEngine.kt` - Rapports cliniques
  - `PkpdAdvisor.kt` - Conseils PKPD
  - `AdvisorModels.kt` - Modèles de données

**Fonctionnalités clés** :
- ✅ Audit en temps réel de CHAQUE décision d'insuline
- ✅ Reconnaissance photo de repas avec 4 modèles IA
- ✅ Recommandations profil basées sur analyse 7-14 jours
- ✅ Gestion automatique grossesse (DPA-based)
- ✅ Protection multi-niveaux (Local Sentinel + LLM Auditor)

---

### **2. TRAJECTORY (4 fichiers) - 🌀 Analyse Trajectoire**
📁 `/trajectory/`

- `TrajectoryGuard.kt` - Garde principal analyse phase-space
- `TrajectoryHistoryProvider.kt` - Historique états
- `TrajectoryMetricsCalculator.kt` - Calcul métriques (κ, conv, etc.)
- `PhaseSpaceModels.kt` - Modèles mathématiques

**Fonctionnalités** :
- ✅ Classification trajectoires (DIVERGING, CONVERGING, SPIRAL, ORBIT, UNCERTAIN)
- ✅ Modulation SMB/basal based on trajectory
- ✅ Visualisation ASCII dans rT
- ✅ Prédiction convergence vers cible

---

### **3. PKPD (12 fichiers) - 🧬 Modèle Pharmacocinétique**
📁 `/pkpd/`

- `InsulinActionProfiler.kt` - Profil action insuline
- `PkpdEngine.kt` - Moteur calcul PKPD
- `PkpdPredictor.kt` - Prédictions
- `PkpdThrottleEngine.kt` - Throttling based on PKPD state
- `ActivityStage.kt` - Définition stages (PRE_ONSET, RISING, PEAK, TAIL)
- `BiExponentialModel.kt` - Modèle bi-exponentiel
- `CompartmentModel.kt` - Modèle compartiments
- `...` (+ autres fichiers modélisation)

**Fonctionnalités** :
- ✅ Modélisation réaliste absorption/action insuline
- ✅ Détection saturation récepteurs
- ✅ Throttling SMB selon stage PKPD
- ✅ Support multi-types insuline (Fiasp, NovoRapid, etc.)

---

### **4. CONTEXT (8 fichiers) - 🎯 Contexte Utilisateur**
📁 `/context/`

- `ContextManager.kt` - Gestion contextes actifs
- `ContextIntention.kt` - Intentions (exercice, stress, maladie)
- `ContextModulator.kt` - Modulation basale/SMB
- `ContextNLPProcessor.kt` - Traitement langage naturel
- `ContextBuckets.kt` - Catégorisation
- `ui/ContextActivity.kt` - Interface utilisateur

**Fonctionnalités** :
- ✅ Déclaration contextes (🏃 exercice, 😰 stress, 🤒 maladie, 🍷 alcool)
- ✅ Input langage naturel ou boutons prédéfinis
- ✅ Modulation automatique basal/SMB (-60% à +50%)
- ✅ Gestion durée et intensité

---

### **5. WCYCLE (8 fichiers) - 🔄 Cycle Menstruel**
📁 `/wcycle/`

- `WCycleManager.kt` - Gestion cycle
- `WCyclePhaseDetector.kt` - Détection phase
- `WCycleModulator.kt` - Modulation insuline
- `WCyclePreferences.kt` - Préférences
- `EndometriosisHandler.kt` - **🆕 Gestion endométriose**
- `...`

**Fonctionnalités** :
- ✅ Tracking cycle menstruel (4 phases)
- ✅ Ajustement basal/ISF selon phase (+12% phase lutéale)
- ✅ **NOUVEAU** : Support endométriose avec gestion douleur
- ✅ Détection automatique phases

---

### **6. PHYSIO (14 fichiers) - 🏥 Données Physiologiques**
📁 `/physio/`

- `AIMIPhysioManagerMTR.kt` - Manager principal
- `AIMIPhysioDataRepositoryMTR.kt` - Repository données
- `AIMILLMPhysioAnalyzerMTR.kt` - Analyse LLM
- `AIMIHealthConnectPermissionsHandlerMTR.kt` - Permissions HealthConnect
- `HeartRateProcessor.kt` - Traitement FC
- `StepsProcessor.kt` - Traitement pas
- `SleepAnalyzer.kt` - Analyse sommeil
- `...`

**Fonctionnalités** :
- ✅ Intégration HealthConnect (Android 14+)
- ✅ Analyse FC temps réel (stress, exercice)
- ✅ Comptage pas (détection activité)
- ✅ Analyse qualité sommeil
- ✅ Modulation automatique basée physio

---

### **7. SMB (6 fichiers) - 💉 Super Micro Bolus**
📁 `/smb/`

- `SmbDecisionEngine.kt` - Moteur décision SMB
- `SmbInstructionExecutor.kt` - Exécution SMB
- `SmbSafetyValidator.kt` - Validation sécurité
- `SmbThrottler.kt` - Throttling
- `SmbPredictor.kt` - Prédictions
- `AutosensHandler.kt` - Autosens

**Fonctionnalités** :
- ✅ Calcul SMB optimal
- ✅ Validation multi-couches (Local + Auditor)
- ✅ Throttling selon PKPD, trajectory, context
- ✅ Prédictions impact SMB

---

### **8. BASAL (3 fichiers) - ⚙️ Débit Basal**
📁 `/basal/`

- `BasalDecisionEngine.kt` - Décision basal temporaire
- `BasalPlanner.kt` - Planification
- `BasalHistoryUtils.kt` - Historique

**Fonctionnalités** :
- ✅ Temp basal dynamique
- ✅ Planification multi-horizons
- ✅ Historique et learning

---

### **9. LEARNING (2 fichiers) - 🧠 Apprentissage**
📁 `/learning/`

- `UnifiedReactivityLearner.kt` - **Learner principal**
- `LearningDataRepository.kt` - Repository données

**Fonctionnalités** :
- ✅ Apprentissage automatique du facteur `react`
- ✅ Analyse hypos/hypers/variabilité
- ✅ Ajustement progressif (0.4 à 2.5)
- ✅ Remplace facteurs matin/après-midi/soir

---

### **10. ISF (2 fichiers) - 📊 Insulin Sensitivity Factor**
📁 `/ISF/`

- `IsfAdjustmentEngine.kt` - Ajustement ISF dynamique
- `IsfBlender.kt` - Blending multi-sources

**Fonctionnalités** :
- ✅ ISF dynamique temps réel
- ✅ Blending profil + autosens + PKPD
- ✅ Adaptation circadienne

---

### **11. SAFETY (4 fichiers) - 🛡️ Sécurité**
📁 `/safety/`

- `SafetyGuard.kt` - Garde sécurité global
- `HypoPredictor.kt` - Prédiction hypo
- `IobSaturationDetector.kt` - Détection saturation IOB
- `CriticalConditionChecker.kt` - Vérif conditions critiques

**Fonctionnalités** :
- ✅ Détection hypo imminente
- ✅ Blocage sur IOB saturé
- ✅ Vérification multi-contraintes

---

### **12. STEPS (11 fichiers) - 👣 Activité Physique**
📁 `/steps/`

- Traitement pas depuis Wear OS et HealthConnect
- Calibration et filtrage
- Détection patterns activité

---

### **13. COMPARISON (6 fichiers) - 📊 Comparaison Algorithmes**
📁 `/comparison/`

- Comparaison AIMI vs OpenAPS standard
- Métriques de performance

---

### **14. VALIDATION (1 fichier)**
📁 `/validation/`

- Validation cohérence paramètres

---

### **15. UTILS (3 fichiers)**
📁 `/utils/`

- Utilitaires calcul, formatage, etc.

---

### **16. ACTIVITY (2 fichiers) - 🏃 Gestion Activité**
📁 `/activity/`

- `ActivityManager.kt`
- `ActivityContext.kt`

---

### **17. CARBS (1 fichier) - 🍽️ Glucides**
📁 `/carbs/`

- `FoodCarbLoad.kt` - Gestion absorption glucides

---

### **18. KEYS (2 fichiers) - 🔑 Clés Préférences**
📁 `/keys/`

- `AimiStringKey.kt` - **🆕 Clés String (DPA grossesse)**
- `AimiLongKey.kt` - **🆕 Clés Long (timestamp DPA)**

---

## 📌 FICHIERS RACINE CRITIQUES

### **DetermineBasalAIMI2.kt** (363 KB)
**LE CERVEAU D'AIMI**

Sections principales :
1. Initialisation profil et données
2. **Gestation Autopilot** (🆕 lignes 3704-3746)
3. **PKPD Runtime** (lignes 3580-3680)
4. **Trajectory Guard** (lignes 4304-4395)
5. **Context Module** (lignes 4395-4450)
6. **SMB Decision** (lignes 1600-2200)
7. **Basal Decision** (lignes 2500-3000)
8. **Auditor Integration** (partout via callbacks)

### **OpenAPSAIMIPlugin.kt** (108 KB)
**CONFIGURATION & UI PRÉFÉRENCES**

Sections :
- Préférences UI (1200 lignes)
- Mode repas (8 modes)
- **NOUVEAU** : Pregnancy DueDate preference
- WCycle preferences
- Physio preferences
- Learning preferences

---

## 🆕 NOUVELLES FONCTIONNALITÉS IDENTIFIÉES (Phase 1)

### **1. 🤰 Gestational Autopilot (GROSSESSE)**
- **Fichiers** : `GestationalAutopilot.kt`, `AimiStringKey.kt`, `AimiLongKey.kt`
- **Intégration** : `DetermineBasalAIMI2.kt` lignes 3704-3746
- **Fonctionnement** :
  - Input : Date Prévue Accouchement (DPA) format YYYY-MM-DD
  - calcul automatique semaine gestationnel (SA)
  - Application facteurs multiplication selon trimestre :
    - T1 (0-13 SA) : Basal ×0.85, ISF ×0.90
    - T2 (14-27 SA) : Basal ×1.10, ISF ×1.15
    - T3 (28-40 SA) : Basal ×1.35, ISF ×1.45
  - Affichage dans rT : "🤰 GESTATION ACTIVE: XX SA"
- **Statut Manuel** : ❌ NON DOCUMENTÉ

### **2. 🌀 Trajectory Guard (VISUALISATION ASCII)**
- **Nouveauté** : Méthode `asciiArt()` ajoutée
- **Affichage** : Représentations visuelles dans rT
  - `●→●→●→` (diverging)
  - `●→●→●` (closing)
  - Spiral multi-lignes
  - Orbit circle
- **Statut Manuel** : ⚠️ PARTIELLEMENT DOCUMENTÉ (Section 11, minimal)

### **3. 🩸 Endométriose Handler**
- **Fichier** : `wcycle/EndometriosisHandler.kt`
- **Fonctionnalités** :
  - Gestion douleur cyclique
  - Modulation insuline selon intensité douleur
  - Tracking symptoms
- **Statut Manuel** : ❌ NON DOCUMENTÉ

### **4. 🏥 HealthConnect Integration**
- **Fichiers** : Tout le module `physio/`
- **Fonctionnalités** :
  - FC, pas, sommeil, SpO2, température
  - Permissions auto-gestion
  - Analyse LLM des données physio
- **Statut Manuel** : ⚠️ BASIQUE (Wear OS mentionné, pas HealthConnect)

### **5. 🆕 Honeymoon Mode**
- **Clé** : `BooleanKey.OApsAIMIhoneymoon`
- **Impact** : Modifie `highBG` preference dynamiquement
- **Statut Manuel** : ❌ NON DOCUMENTÉ

---

## 📋 ANALYSE PASSES 1 - FINDINGS

### **Fonctionnalités à documenter** :
1. ✅ Gestational Autopilot (complet)
2. ✅ Trajectory Guard (enrichir Section 11 ou créer Section 15)
3. ✅ Endométriose (nouvelle section WCycle)
4. ✅ HealthConnect (enrichir Section 11 ou ajouter sous Physio)
5. ✅ Honeymoon Mode (Section 10 ou 11)
6. ✅ Tous les modules Advisor détaillés
7. ✅ PKPD stages et throttling
8. ✅ Context buckets et NLP
9. ✅ Safety multi-layers

### **Interactions préférences** :
- Mode Honeymoon → Change highBG threshold
- Pregnancy → Multiplie basal/ISF
- Context Exercice → Réduit basal/SMB
- WCycle Phase Lutéale → Augmente basal
- PKPD Stage TAIL → Damping SMB
- Trajectory SPIRAL → Réduit SMB drastiquement

---

## 📊 PROCHAINES ÉTAPES (Phase 2)

1. ✅ Analyse détaillée de chaque module
2. ✅ Extraction préférences et impacts
3. ✅ Mise à jour manuel section par section
4. ✅ Build test
5. ✅ Récapitulatif complet

**FIN PHASE 1 - Inventaire Complet**
