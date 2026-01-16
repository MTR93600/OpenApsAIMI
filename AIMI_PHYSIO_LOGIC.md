# 🏥 AIMI Physiological Logic - Documentation Technique

Ce document détaille le fonctionnement, le déclenchement et l'impact du module **Physiologie** (Physio) d'AIMI (OpenAPS). Son objectif est d'adapter l'agressivité de la boucle en fonction de l'état de forme de l'utilisateur (Sommeil, Stress, Maladie).

---

## 1. ⚙️ Orchestration & Déclenchement

Le chef d'orchestre est **`AIMIPhysioManagerMTR`**.

*   **Cadence :** Le module s'exécute automatiquement toutes les **4 heures**.
*   **Déclenchement Manuel :** Possible via le menu ou actions de débogage.
*   **Conditions :**
    *   Il vérifie si Health Connect est disponible et activé.
    *   Il ne s'exécute pas si l'utilisateur est considéré comme "en train de dormir" (basé sur une heuristique simple horaire pour éviter de réveiller le processeur inutilement, bien que Health Connect soit passif).
*   **Pipeline :** À chaque exécution, il suit ces 5 étapes :
    1.  `Fetch` de données brutes (7 derniers jours) depuis Health Connect.
    2.  `Extract` des métriques normalisées (Features).
    3.  `Update` de la référence (Baseline) sur 7 jours.
    4.  `Analyze` du contexte (Détection d'anomalies).
    5.  `Store` du résultat pour utilisation par la boucle.

---

## 2. 📥 Sources de Données (Health Connect)

Le module récupère les données suivantes (via `AIMIPhysioDataRepositoryMTR`) :

1.  **💤 Sommeil :** Durée totale, Efficacité (%), Fragmentation, Phases (optionnel).
2.  **❤️ Variabilité Cardiaque (HRV) :** Moyenne RMSSD (la référence pour le stress physiologique).
3.  **💓 Fréquence Cardiaque au Repos (RHR) :** La moyenne "Morning RHR" ou minimale nocturne.
4.  **👣 Activité :** Pas quotidiens (tendance globlale).

> **Note :** Le module a besoin d'environ **3 jours** de données pour établir une "Baseline" fiable. Avant cela, il reste en mode `NEUTRAL`.

---

## 3. 🧠 Analyse & États (Le Cerveau)

Le moteur `AIMIPhysioContextEngineMTR` compare les données de la nuit/journée en cours avec la moyenne des 7 derniers jours (Baseline). Il utilise le **Z-Score** (écart-type) pour détecter des anomalies significatives.

### Les États Détectés :

1.  **✅ OPTIMAL**
    *   *Conditions :* Tout est dans la normale.
    *   *Action :* Aucune modifiation. 100% du profil.

2.  **😴 RECOVERY_NEEDED (Besoin de Récupération)**
    *   *Déclencheur :* Nuit courte (< 5.5h), sommeil fragmenté, ou baisse significative du HRV.
    *   *Logique :* Le corps est fatigué, la sensibilité à l'insuline peut varier.
    *   *Action :* Légère réduction des SMB (-5%), légère augmentation ISF (+8%). On calme le jeu.

3.  **⚠️ STRESS_DETECTED (Stress)**
    *   *Déclencheur :* Le RHR (cœur au repos) est élevé ET le HRV est bas. Signe classique de stress physiologique fort.
    *   *Action :* Réduction Basale (-5%), Réduction SMB (-8%), Augmentation ISF (+10%). Mode prudence.

4.  **🚨 INFECTION_RISK (Risque Maladie)**
    *   *Déclencheur :* Combinaison sévère (Anomalies multiples : RHR très haut + HRV très bas + Sommeil HS).
    *   *Logique :* Le corps combat quelque chose (virus, fatigue extrême). La résistance à l'insuline est probable mais le risque d'hypo sur correction l'est aussi.
    *   *Action :* **Protection Maximale**. ISF +15%, Basale -10%, SMB -10%. On évite à tout prix de surcharger en insuline active.

5.  **❓ UNKNOWN**
    *   Données insuffisantes ou incohérentes. Pas d'action (Mode Neutre).

---

## 4. 💉 Impact sur la Boucle (L'Action)

C'est `AIMIInsulinDecisionAdapterMTR` qui applique ces décisions **au moment du calcul de la boucle** (`DetermineBasalAIMI2`).

### Sécurités (Hard Caps) :
Quoi qu'il arrive, le module s'interdit de modifier le profil au-delà de limites strictes de sécurité :
*   **ISF (Sensibilité) :** +/- 15% Max.
*   **Basale :** +/- 15% Max.
*   **SMB (Bolus) :** +/- 10% Max.

### Garde-fous ultimes :
Le module **se désactive (retour à Neutral)** immédiatement si :
*   La glycémie actuelle est < **80 mg/dL**.
*   Une **hypoglycémie** a été détectée dans les **2 dernières heures**.
*   La confiance (Data Quality) est trop basse (< 50%).

---

## 5. 🔍 Vérification (Comment voir si ça marche ?)

Dans l'onglet **LOG** (ou Console Script) d'AndroidAPS/AIMI :

1.  Regardez les logs taggés `[PhysioManager]`.
    *   *Succès :* `✅ Pipeline completed... State: OPTIMAL/RECOVERY...`
    *   *Echec :* `⚠️ No physiological data available` (Problème de source).

2.  En haut du log de boucle (`DetermineBasalAIMI2`) :
    *   Vous verrez une ligne : `🏥 Physio Status: OPTIMAL (Conf: 85%)` ou `🏥 Physio: Waiting for initial Health Connect sync...`.

Si vous voyez ces lignes, le système est actif et surveille votre état.
