# 🤰 Plan d'Intégration "Gestational Autopilot" dans AIMI

## 🎯 Objectif
Remplacer le switch binaire "Pregnancy" obsolète par un pilote automatique basé sur la date du terme (DPA), intégré au cœur de `WCycle` et de l'Auditor.

---

## 🏗️ Architecture

### 1. Stockage des Préférences (`WCyclePreferences`)
Nous devons enrichir le modèle de données de WCycle.
*   **Mode de Cycle :** `MENSTRUAL` (défaut) | `PREGNANCY` | `MENOPAUSE/NONE`.
*   **Référence Temporelle :** `LongKey.PregnancyDueDate` (Date Prévue d'Accouchement - DPA). C'est la référence absolue.
    *   *Note : La Date de début (DDR) est déductible : DPA - 280 jours.*

### 2. Le Cerveau (`GestationalAutopilot`)
*   Le prototype actuel est bon (`advisor/gestation/GestationalAutopilot.kt`).
*   Il doit devenir un `@Singleton` injecté via Dagger.

### 3. Le Point d'Injection (`DetermineBasalAIMI2`)
Au tout début de `DetermineBasal`, avant toute logique SMB :
1.  Vérifier si Mode == PREGNANCY.
2.  Si oui, appeler `GestationalAutopilot.calculateState(dpa)`.
3.  Appliquer les facteurs (`basal * f`, `isf / f`, `cr / f`) sur une copie du profil (`fusedProfile`).
4.  Utiliser ce profil "boosté" pour tout le reste des calculs.

### 4. La Conscience (`AuditorAIService`)
L'Auditor doit "savoir" pour ne pas halluciner devant la résistance du T3.
*   Ajouter `gestationalState` dans la structure JSON envoyée au LLM.
*   Modifier le prompt pour inclure les règles de sécurité obstétriques (Fœtus > Algorithme).

---

## 📋 Plan d'Action Technique

### Étape 1 : WCycle & Prefs
*   [ ] Déplacer/Créer les clés de préférences dans `WCyclePreferences`.
*   [ ] Créer l'UI de saisie de la DPA dans le fragment WCycle (hors scope immédiat du bot, mais à noter).

### Étape 2 : Wiring Engine
*   [ ] Transformer `GestationalAutopilot` en Service injectable.
*   [ ] Modifier `DetermineBasalAIMI2` pour injecter ce service.
*   [ ] Implémenter la logique de modulation du profil "In-Flight".

### Étape 3 : Wiring Auditor
*   [ ] Mettre à jour `AuditorDataStructures` (Status Snapshot).
*   [ ] Mettre à jour `AuditorDataCollector` pour lire la DPA.
*   [ ] Modifier `AuditorPromptBuilder` avec section "Obstetrics".

### Étape 4 : Nettoyage
*   [ ] Supprimer l'usage de `BooleanKey.OApsAIMIpregnancy` dans `BasalDecisionEngine`.
*   [ ] Déprécier l'ancienne préférence.

---

## 🧠 Réponses aux questions de l'utilisateur

1.  **DPA vs DDR ?**
    *   La DPA (Date Prévue Accouchement) est plus robuste. Lors des échographies, la date de début théorique change souvent, mais la DPA est la "target" médicale. Le calcul `SA = 40 - semaines_restantes` est standard.

2.  **WCycle ?**
    *   Oui, c'est le bon endroit. La grossesse est un "Super-Cycle" de 9 mois.

3.  **Câblé actuellement ?**
    *   **NON.** Le fichier `GestationalAutopilot.kt` est inerte. Il n'est appelé nulle part.

4.  **Auditor ?**
    *   **NON.** L'Auditor ne sait rien de la grossesse actuellement. Il interpréterait la résistance du T3 comme une anomalie grave.

## 🚀 Prochaine étape immédiate
Implémenter l'Étape 2 (Wiring Engine) en injectant le pilote dans `DetermineBasalAIMI2` (en mode "Silent" pour commencer) et afficher l'état de la grossesse dans le log ASCII.
