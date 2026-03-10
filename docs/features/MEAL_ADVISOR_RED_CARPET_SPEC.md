# Spécification Technique : Logique "Tapis Rouge" & Fix Trajectoire

## 1. Objectif Global
Cette spécification adresse deux problèmes critiques identifiés dans le comportement du Meal Advisor :
1.  **Priorité au Repas (Red Carpet)** : Empêcher les sécurités mineures (petites baisses, throttle) d'annuler une demande de bolus explicite ou nécessaire.
2.  **Mise à jour Trajectoire (Obsolescence)** : Corriger le problème de "Stale Trajectory" (19h45) où l'Auditor cesse de mettre à jour la prédiction pendant le repas, bloquant les SuperSMB.

---

## 2. Analyse de l'Incident de 19h45 (Trajectory Staleness)

### Diagnostic
L'analyse des logs (26/01, ~19h45) révèle une défaillance de la mise à jour de la trajectoire AI :
*   **19h45** : Le système détecte un début de montée mais affiche `Auditor: STALE (39m old)`.
*   **Conséquence** : La dernière trajectoire connue date de 40 minutes. Le système considère l'information comme "incertaine" ou "périmée".
*   **Cause Racine** : Dans `AuditorOrchestrator`, l'appel à l'IA (External) est conditionné par le `LocalSentinel`.
    *   Si le Sentinel juge la situation "Peu risquée" (Safe), il **bloque** l'appel à l'IA pour économiser des ressources.
    *   Pendant le repas, si la montée est "douce" au début, le Sentinel ne déclenche pas le Tier HIGH.
    *   Résultat : Pas de nouvelle trajectoire AI -> Le `DetermineBasal` passe en mode sécurité -> SMB 0U ou bridés.

### Solution Requise
Il faut forcer le rafraîchissement de l'Auditor (IA) lorsque nous sommes en contexte de repas actif (COB > 0 ou Mode Repas), même si le Sentinel est "calme". Cela garantit une trajectoire fraîche pour piloter les SMB.

---

## 3. Implémentation Partie 1 : Auditor Orchestrator (Le Fix Trajectoire)

**Fichier Cible** : `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/advisor/auditor/AuditorOrchestrator.kt`
**Méthode** : `auditDecision`

**Action** : Modifier la condition `shouldCallExternal` pour inclure un forçage en cas de contexte repas si la donnée est vieille.

```kotlin
        // ... (vers ligne 210) ...
        
        // Contextes nécessitant une trajectoire fraîche (Repas actif, COB, ou Autosens instable)
        val isMealContext = (cob ?: 0.0) > 0.0 || modeType != null || inPrebolusWindow
        val isStale = (now - lastVerdictTime) > 15 * 60 * 1000L // 15 minutes
        
        // Force update si contexte repas ET donnée vieille, MÊME si Sentinel dit "Low Risk"
        val forceExternal = isMealContext && isStale

        // Determine if External should be called (Sentinel Tier HIGH OR Force Update)
        val shouldCallExternal = sentinelAdvice.tier == LocalSentinel.Tier.HIGH || forceExternal
        
        if (!shouldCallExternal) {
             // ... (Code existant qui skip l'appel)
```

---

## 4. Implémentation Partie 2 : Red Carpet (La Priorité Repas)

**Fichier Cible** : `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/DetermineBasalAIMI2.kt`
**Méthode** : `finalizeAndCapSMB`

**Action** : Remplacer le bloc de logique de forçage pour utiliser `proposedUnits` et restaurer le bolus si nécessaire.

```kotlin
        // 🚀 MEAL MODES FORCE SEND: "Red Carpet" Logic
        
        // Définition élargie du contexte prioritaire "Tapis Rouge"
        // 1. Action Explicite (Bouton appuyé)
        // 2. Mode Repas Actif (Dinner, Lunch, etc.) OU AIMI Context Meal (RContext déclaré)
        // 3. Chaos Carbohydrate (COB présents + Montée violente > 5 mg/dL/5m)
        val isMealChaos = (mealData.mealCOB > 10.0 && delta > 5.0)
        
        // Ajout de la vérification AIMI Context (RContext Meal)
        val isAimiContextMeal = isValidAIMIContextMeal() // Vérifie si un contexte repas (Breakfast/Lunch/Dinner) est actif dans AIMI Profile
        
        val isRedCarpetSituation = isExplicitUserAction || isMealModeCondition() || isAimiContextMeal || ((isMealChaos || mealData.isMealStart) && proposedUnits > 0.5f)

        // On entre dans la logique forcée si on est en situation "Red Carpet" et qu'il y a une demande
        if (isRedCarpetSituation && proposedUnits > 0.0) {
            
            // 1. Restauration de la demande
            // Si les sécurités mineures ont coupé plus de 40% du bolus, on restaure la demande initiale.
            val candidateUnits = if (gatedUnits < proposedUnits.toFloat() * 0.6f) { 
                consoleLog.add("✨ RED CARPET: Restoring meal bolus blocked by minor safety (Proposed=${"%.2f".format(proposedUnits)} vs Gated=${"%.2f".format(gatedUnits)})")
                 proposedUnits.toFloat()
            } else {
                 gatedUnits 
            }

            // 2. Appliquer les Sécurités VITALES (Hard Caps uniquement)
            
            // a. Cap MaxSMB - On utilise MaxSMBHB (High) si dispo, sinon config standard
            val maxSmbCap = if (maxSmbHigh > baseLimit) maxSmbHigh.toFloat() else baseLimit.toFloat()
            var mealBolus = min(candidateUnits, maxSmbCap)

            // b. Cap MaxIOB (Sécurité Ultime) - On ne s'autorise à remplir QUE l'espace disponible
            val iobSpace = (this.maxIob - this.iob).coerceAtLeast(0.0)
            
            if (mealBolus > iobSpace.toFloat()) {
                consoleLog.add("🛡️ RED CARPET: Clamped by MaxIOB (Need=${"%.2f".format(mealBolus)}, Space=${"%.2f".format(iobSpace)})")
                mealBolus = iobSpace.toFloat()
            }
            
            // c. Hard Cap 30U (Ceinture de sécurité absolue anti-bug)
            mealBolus = mealBolus.coerceAtMost(30f)

            finalUnits = mealBolus.toDouble()
            
            // Log explicite pour le debugging
            if (finalUnits > gatedUnits + 0.1) {
                val reason = if (isExplicitUserAction) "UserAction" else if (isMealChaos) "CarbChaos" else "MealMode"
                consoleLog.add("🍱 MEAL_FORCE_EXECUTED ($reason): ${"%.2f".format(finalUnits)} U (Overrides minor safety checks)")
            }

        } else {
            // Comportement standard (Pas de repas ou demande nulle)
            // On utilise la logique existante capSmbDose qui applique toutes les restrictions (throttle, etc.)
            finalUnits = capSmbDose(
                proposedSmb = gatedUnits,
                bg = this.bg,
                maxSmbConfig = baseLimit,
                iob = this.iob.toDouble(),
                maxIob = this.maxIob
            ).toDouble()
        }
```

---

## 5. Résumé des Bénéfices
1.  **Réactivité Immédiate** : En cas de "choc glucidique" (COB + Delta), le système réagit même si l'utilisateur a oublié d'activer le mode repas ou si le temps est écoulé.
2.  **Fidélité de Trajectoire** : Le fix `AuditorOrchestrator` garantit que pendant toute la durée digestive (COB > 0), l'IA donnera son avis au moins toutes les 15 minutes, évitant le blackout de décision.
3.  **Sécurité Maintenue** : MaxIOB reste infranchissable. On débloque le frein à main (Throttle) mais on garde la barrière de sécurité (MaxIOB).

---

## 7. FAQ & Robustesse

### Question : Si l'IA n'est pas disponible, la trajectoire est-elle perdue ?
**Réponse : NON.**
L'architecture AIMI est conçue en couches (Dual-System) :
1.  **Couche IA (External Auditor)** : Fournit des confirmations "Long-term" et des ajustements complexes. En cas de panne (offline, timeout), cette couche retourne `SKIPPED`.
2.  **Couche Locale (Sentinel + UAM/MPC)** : C'est le moteur principal qui tourne en permanence.
    *   Le module **PKPD/UAM** calcule toujours une trajectoire basée sur les COB et l'IOB.
    *   La fonction de sécurité `capSmbDose` utilise la trajectoire locale pour éviter les hypos.
    *   **Sans l'IA**, le système devient simplement plus **conservateur** (il hésitera à faire des SuperSMB massifs), mais il continuera d'injecter de l'insuline et de sécuriser la glycémie.

Le **"Red Carpet"** ajoute une sécurité supplémentaire : même si l'IA est muette, si le système local détecte un "Chaos Glucidique" (COB+Montée), il s'autorisera à être agressif pour couvrir le repas, grâce aux règles codées en dur, sans attendre la permission de l'IA.

### Question : Quelle est la fréquence d'appel recommandée ?
**Réponse :** Pour maintenir une **trajectoire fraîche** sans surcharger, **6 à 10 appels par heure** sont idéaux.
*   En période calme (nuit) : 0-2 appels/h suffisent (Sentinel filtre).
*   En période intense (repas) : L'IA doit être consultée toutes les **5 à 10 minutes**.
*   Le fix "Force Update" intégré ci-dessus force un appel toutes les **15 minutes max** pendant un repas si le Sentinel est inactif, ce qui garantit une trajectoire valide à chaque cycle important, sans nécessiter 12 appels/heure (toutes les 5 min). La recommandation de **6 appels/h** est donc un bon équilibre.

