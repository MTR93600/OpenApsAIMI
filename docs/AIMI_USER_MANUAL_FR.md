# 📘 Manuel Utilisateur AIMI - Guide Complet
## Advanced Intelligent Mathematical Insulin (AIMI)

**Version**: 3.4.0  
**Dernière mise à jour**: Janvier 2026

---

## 📋 Table des Matières

1. [Démarrage Rapide](#demarrage-rapide)
2. [Réglages Essentiels](#reglages-essentiels) ⚠️ À LIRE ABSOLUMENT
3. [Modes Repas](#modes-repas)
4. [AIMI Advisor](#aimi-advisor) 🤖
5. [AIMI Meal Advisor](#aimi-meal-advisor) 📸
6. [AIMI Context](#aimi-context) 🎯
7. [Fonctionnalités de Sécurité](#fonctionnalites-de-securite) 🛡️
   - [AIMI Auditor](#aimi-auditor-auditeur-ia)
   - [AIMI Trajectory](#aimi-trajectory-trajectoire-predictive)
   - [PKPD](#pkpd-pharmacocinetiquepharmacodynamie)
8. [Dépannage](#depannage)
9. [Réglages Recommandés](#reglages-recommandes-par-type-dutilisateur)

---

## 🚀 Démarrage Rapide

### Étape 1 : Activer AIMI

1. Aller dans **Générateur de Configuration** → Onglet **APS**
2. Sélectionner **"OpenAPS AIMI"**
3. Cliquer sur **Préférences Plugin** (icône engrenage)

### Étape 2 : Réglages Essentiels ⚠️

**CRITIQUE : Ces réglages DOIVENT être configurés correctement :**

#### ✅ Désactiver ApsSensitivityRaisesTarget

**Chemin** : Générateur de Configuration → Sensibilité → Réglages avancés

```
❌ ApsSensitivityRaisesTarget = OFF (DOIT être désactivé)
```

**Pourquoi ?** AIMI utilise son propre système d'ISF dynamique. Laisser cette option activée crée des conflits et des sur-corrections.

#### ✅ Définir Max IOB Approprié

**Chemin** : OpenAPS AIMI → Onglet Sécurité

```
Max IOB : Commencer prudemment
- Adultes : 15-25U
- Adolescents : 10-15U
- Enfants : 5-10U
```

**Conseil** : Commencer bas et augmenter progressivement selon les résultats.

---

## 🍽️ Modes Repas

AIMI inclut **8 modes repas spécialisés** qui ajustent automatiquement l'insuline pour différents types de repas.

### Modes Disponibles

| Icône | Mode | Quand l'utiliser | Glucides typiques | Prébolus |
|-------|------|------------------|-------------------|----------|
| 🌅 | **Petit-déjeuner** | Repas du matin | 30-60g | 15 min avant |
| 🍱 | **Déjeuner** | Repas de midi | 40-80g | 10 min avant |
| 🍽️ | **Dîner** | Repas du soir | 50-100g | 15 min avant |
| 🍕 | **High Carb** | Pizza, pâtes | 80-150g | 20 min avant |
| 🍪 | **Snack** | Petit repas | 10-30g | 5 min avant |
| 🍴 | **Meal (Générique)** | N'importe quel repas | Variable | 10 min avant |
| 😴 | **Sleep** | Avant de dormir | 0-20g | Optionnel |

### Comment Créer les Boutons de Mode Repas

#### Méthode : Via Automation + Careportal

1. Aller dans l'onglet **Automation**
2. Créer une **Nouvelle Règle**
3. **Nommer la règle** (ex: "AIMI Petit-déjeuner")
4. **Déclencheur** : Cocher **"Action utilisateur"** (user action)
5. Dans **Action** : Sélectionner **"Careportal"**
6. **Nom du mode** : Entrer le code du mode souhaité :
   - `bfast` : Petit-déjeuner
   - `lunch` : Déjeuner
   - `dinner` : Dîner
   - `highcarb` : High Carb (pizza, pâtes)
   - `snack` : Snack
   - `meal` : Repas générique
   - `sport` : Mode Sport
   - `stop` : Arrêter le mode en cours
   - `sleep` : Mode Sommeil
7. **Durée** : 
   - **60 ou 90 minutes** pour les modes repas normaux
   - **5 minutes OBLIGATOIRES** pour le mode `stop` (annule le mode en cours)
8. **Sauvegarder** la règle
9. Répéter pour chaque mode désiré

⚠️ **Important** :
- **Rafraîchir la boucle** (pull-to-refresh sur l'écran principal) peut activer le mode plus rapidement
- **Sans glycémie active**, le prébolus ne sera **PAS envoyé** car la boucle ne se rafraîchit pas sans données CGM

### Préférences des Modes Repas

**Chemin** : Préférences OpenAPS AIMI → Modes Repas

Chaque mode a des paramètres personnalisables :

| Paramètre | Description | Plage typique |
|-----------|-------------|---------------|
| **Quantité Prébolus** | Quantité d'insuline avant le repas | 30-100% |
| **Timer Prébolus** | Minutes avant le repas | 5-30 min |
| **Facteur** | Multiplicateur d'agressivité | 0.8-1.5 |
| **Basal Max** | Débit basal maximum durant le mode | 3-10 U/h |

**Exemple de Configuration :**

```yaml
Mode Petit-déjeuner :
  Prébolus : 60% de l'estimation
  Timer : 15 minutes
  Facteur : 1.2 (plus agressif)
  Basal Max : 5.0 U/h
```

---

## 🤖 AIMI Advisor

**Conseiller de profil alimenté par IA utilisant GPT-5.2, Gemini 2.5 ou Claude.**

### Ce Qu'il Fait

- Analyse vos **7-14 derniers jours** de données glycémiques et d'insuline
- Identifie les motifs (hypos, hypers, variabilité)
- Évalue la performance globale de votre profil actuel
- Suggère des **ajustements spécifiques et précis** pour :
  - Débits de basal (par tranche horaire)
  - ISF (Facteur de Sensibilité à l'Insuline)
  - CR (Ratio de Glucides)
  - DIA (Durée d'Action de l'Insuline)
  - Cible Glycémique
  - Max IOB
  - Paramètres AIMI (réactivité, modes repas)

### Action du AIMI Advisor

L'Advisor génère un **rapport détaillé** contenant :

1. **Analyse de Performance** :
   - Temps dans la cible (TIR - Time In Range)
   - Fréquence et sévérité des hypos/hypers
   - Variabilité glycémique (CV - Coefficient de Variation)
   - Analyse par période (nuit, matin, après-midi, soir)

2. **Recommandations Spécifiques** :
   - Changements suggérés avec pourcentages précis
   - Justification basée sur vos données
   - Priorisation des ajustements (critique → optionnel)

3. **Validation de Sécurité** :
   - Chaque recommandation est **automatiquement auditée** par l'Auditeur IA
   - Les suggestions dangereuses sont bloquées ou ajustées
   - Respect des limites physiologiques

### Comment l'Utiliser

1. Aller dans **Préférences OpenAPS AIMI**
2. Descendre jusqu'à la section **"🤖 Assistant IA"**
3. Appuyer sur **"AIMI Profile Advisor"**
4. Sélectionner **Fournisseur IA** :
   - **ChatGPT (GPT-5.2)** : Raisonnement le plus avancé
   - **Gemini (2.5 Flash)** : Meilleur rapport qualité/prix ✅ Recommandé
   - **DeepSeek (Chat)** : Le plus économique
   - **Claude (3.5 Sonnet)** : Alternative haute qualité
5. Entrer votre **Clé API** (obtenir sur le site du fournisseur)
6. Appuyer sur **"Analyser le Profil"**
7. Attendre 30-60 secondes
8. **Examiner les recommandations** attentivement
9. Appliquer les changements **un par un** et surveiller les résultats

### Configuration Clé API

**OpenAI (GPT-5.2)** :
- Aller sur https://platform.openai.com/api-keys
- Créer nouvelle clé
- Copier et coller dans AAPS

**Google Gemini (2.5 Flash)** ✅ Recommandé :
- Aller sur https://makersuite.google.com/app/apikey
- Créer clé API
- Copier et coller dans AAPS
- **Coût** : ~30x moins cher que GPT

### Fonctionnalités de Sécurité

✅ **Auditeur IA** : Chaque recommandation est automatiquement vérifiée pour la sécurité  
✅ **Limites de Plage** : Les suggestions restent dans des plages physiologiques sûres  
✅ **Approbation Humaine** : Vous devez appliquer manuellement chaque changement  

---

## 📸 AIMI Meal Advisor

**Prenez une photo de votre nourriture, obtenez une estimation instantanée des glucides.**

### Modèles IA Supportés

| Modèle | Cas d'Usage | Précision | Coût |
|--------|-------------|-----------|------|
| **GPT-4o Vision** | Haute précision nécessaire | ⭐⭐⭐⭐⭐ | $$$ |
| **Gemini (2.5 Flash)** | Meilleur équilibre | ⭐⭐⭐⭐ | $ ✅ |
| **DeepSeek (Chat)** | Option budget | ⭐⭐⭐ | ¢ |
| **Claude (3.5 Sonnet)** | Alternative | ⭐⭐⭐⭐ | $$$ |

### Comment l'Utiliser

1. Ouvrir **AIMI Meal Advisor** depuis le menu
2. Sélectionner **Modèle IA** (liste déroulante en haut)
3. Appuyer sur **"📷 Prendre Photo Nourriture"**
4. Prendre une photo claire de votre repas
5. Attendre 5-10 secondes pour l'analyse
6. Examiner l'estimation :
   - **Total Effectif** : Glucides + équivalent FPU
   - **Glucides** : Glucides directs
   - **FPU** : Unités Lipides/Protéines (converties en g)
7. Appuyer sur **"✅ Confirmer"** pour injecter dans AIMI
8. AIMI ajustera automatiquement l'administration d'insuline

---

## 🎯 AIMI Context

**Informez AIMI de vos activités, stress, maladie, etc. pour un meilleur dosage d'insuline.**

### Qu'est-ce que le Context ?

AIMI Context vous permet d'**informer l'algorithme** des facteurs qui affectent les besoins en insuline :

- 🏃 **Exercice** (cardio, force, yoga, sports)
- 🤒 **Maladie** (fièvre, infection, stress)
- 😰 **Stress** (émotionnel, travail, examens)
- 🍷 Consommation d'**Alcool**
- ✈️ **Voyage** (changements de fuseau horaire)
- 🔄 Phase du **Cycle Menstruel**
- 🍕 Risque de **repas non annoncé**

### Comment l'Utiliser

#### Méthode 1 : Langage Naturel (LLM)

1. Ouvrir **AIMI Context** depuis le menu
2. Activer le bouton **"Utiliser l'Analyse IA"**
3. Taper en **français naturel** :
   ```
   "séance de cardio intense 1 heure"
   "malade avec grippe, résistant"
   "2 bières à l'instant"
   "deadline stressante au travail aujourd'hui"
   ```
4. L'IA convertit votre texte en intention structurée
5. Appuyer sur **"Ajouter Intention"**

#### Méthode 2 : Boutons Prédéfinis

1. Ouvrir **AIMI Context**
2. Appuyer sur un **bouton prédéfini** :
   - 🏃 Exercice Léger
   - 🏃‍♂️ Exercice Modéré
   - 🏃‍♀️ Exercice Intense
   - 🤒 Maladie
   - 😰 Stress
3. Ajuster **durée** et **intensité** si nécessaire
4. Appuyer sur **"Confirmer"**

### Comment le Context Affecte l'Insuline

| Type de Context | Effet sur l'Insuline | Durée Typique |
|-----------------|----------------------|---------------|
| 🏃 **Exercice (Cardio)** | ⬇️ -30 à -60% basal/SMB | 2-4 heures |
| 💪 **Exercice (Force)** | ⬇️ -15 à -30% | 1-2 heures |
| 🧘 **Yoga** | ⬇️ -10 à -20% | 1-2 heures |
| 🤒 **Maladie** | ⬆️ +20 à +50% | 12-48 heures |
| 😰 **Stress** | ⬆️ +10 à +30% | 4-8 heures |
| 🍷 **Alcool** | ⬇️⬆️ Complexe (baisse initiale, puis montée) | 4-12 heures |
| 🔄 **Phase Lutéale** | ⬆️ +10 à +20% | 14 jours |

---

## 🛡️ Fonctionnalités de Sécurité

### AIMI Auditor (Auditeur IA)

**Système de sécurité en temps réel qui audite chaque décision d'insuline avant exécution.**

#### Ce Qu'il Fait

L'Auditeur IA est un **second cerveau indépendant** qui vérifie toutes les décisions AIMI :

**Vérifications Effectuées** :
- ✅ **Évaluation du risque d'hypoglycémie** :
  - Analyse de la glycémie actuelle et des tendances
  - Calcul de l'IOB total (insuline active)
  - Prédiction de la glycémie future (30-120 minutes)
  
- ✅ **Saturation IOB** :
  - Vérifie si trop d'insuline est déjà active
  - Détecte les empilements (insulin stacking) dangereux
  - Respecte les limites Max IOB configurées
  
- ✅ **Analyse de tendance Delta** :
  - Évalue la vitesse de changement glycémique
  - Détecte les chutes rapides (risque hypo)
  - Identifie les montées rapides (ajustement nécessaire)
  
- ✅ **Cohérence des prédictions** :
  - Compare les prédictions AIMI avec les modèles de sécurité
  - Bloque les contradictions dangereuses
  - Valide que les doses proposées sont proportionnelles

**Types de Verdict** :
- ✅ **APPROUVÉ** : La dose est sûre, exécution immédiate
- ⚠️ **APPROUVÉ_AVEC_RÉDUCTION** : Dose réduite pour plus de sécurité (ex: -30%)
- ❌ **REJETÉ** : Dose bloquée, trop dangereuse

#### Quand l'Auditor Intervient

L'Auditor vérifie :
- **Tous les SMB** (Super Micro Bolus)
- **Tous les prébolus** des modes repas
- **Tous les ajustements de basal** temporaires
- **Toutes les recommandations** du AIMI Advisor

**Exemple de Protection** :
```
Scénario : Glycémie = 85 mg/dL, Delta = -5 mg/dL/5min, IOB = 3U
AIMI propose : 0.5U SMB
Auditor : ❌ REJETÉ - Risque hypo élevé, tendance baisse rapide
Résultat : Aucune insuline délivrée
```

### Gardes Glycémie Basse

**Plusieurs couches de protection** :

1. **Clamp de Réactivité** : Limite l'agressivité en dessous de 120 mg/dL
2. **Plafond SMB** : Réduit le SMB max de 80% en dessous de 120 mg/dL
3. **LGS (Suspension Glucose Bas)** : Arrête toute insuline en dessous du seuil
4. **Prédiction Hypo** : Bloque l'insuline si hypo prédite dans 30 min

### AIMI Trajectory (Trajectoire Prédictive)

**Système de prédiction avancé qui anticipe vos glycémies futures.**

#### Ce Qu'il Fait

- **Calcule la trajectoire glycémique** sur 30 à 180 minutes
- **Intègre tous les facteurs actifs** :
  - IOB (Insuline On Board) avec modèle PKPD
  - COB (Carbs On Board) avec absorption dynamique
  - Tendances Delta actuelles
  - Basal temporaire active
  - Context (exercice, stress, etc.)
  
- **Ajuste les décisions en temps réel** :
  - Prébolus anticipé si montée prédite
  - Réduction/arrêt si hypo prédite
  - Optimisation du timing insuline

**Affichage** :
Vous pouvez voir la trajectoire prédite dans :
- Les logs AIMI (onglet OpenAPS)
- La courbe de prédiction sur le graphique principal
- Les détails de décision (tap sur notification)

### PKPD (Pharmacocinétique/Pharmacodynamie)

**Modèle avancé d'absorption et d'action de l'insuline.**

#### Qu'est-ce que le PKPD ?

Au lieu d'utiliser une courbe DIA fixe, PKPD modélise l'insuline de façon **dynamique** :

**Pharmacocinétique (PK)** - Comment l'insuline est absorbée :
- Vitesse d'absorption variable selon :
  - Type d'insuline (Fiasp, NovoRapid, Humalog)
  - Site d'injection (abdomen, bras, cuisse)
  - Température corporelle (exercice = absorption plus rapide)
  - Flux sanguin local

**Pharmacodynamie (PD)** - Comment l'insuline agit :
- Effet sur la glycémie variable selon :
  - Sensibilité actuelle (ISF dynamique)
  - Saturation des récepteurs (beaucoup d'IOB = effet réduit)
  - Résistance temporaire (stress, maladie)

#### Avantages du PKPD

✅ **Prédictions plus précises** : Modèle réaliste de l'action insuline  
✅ **Adaptation aux situations** : Détecte la saturation et ajuste  
✅ **Meilleure gestion repas** : Timing optimal des bolus  
✅ **Moins d'empilements** : Détecte l'insuline "cachée" encore active  

**Paramètres Configurables** :
- Type d'insuline (ultra-rapide vs rapide)
- Pic d'action (25-75 minutes)
- DIA effectif (3-7 heures)
- Facteur de saturation

### Application Max SMB/IOB

**CRITIQUE** : Les préférences utilisateur sont **TOUJOURS respectées**.

```
✅ Si vous définissez max_smb_size = 0.5U → il ne dépassera JAMAIS 0.5U
✅ Si vous définissez max_iob = 10U → il ne dépassera JAMAIS 10U
```

---

## 🔧 Dépannage

### "Trop d'hypos"

**Étapes** :
1. **Baisser Max SMB** :
   - Réglages → Max SMB > 120 : 0.5U
   - Max SMB < 120 : 0.2U
2. **Augmenter Cible Glycémique** :
   - Considérer 110-120 mg/dL au lieu de 100
3. **Vérifier React** :
   - Devrait s'auto-adapter à la baisse après hypos
   - Vérifier les logs : `globalFactor` devrait diminuer
4. **Désactiver Fonctionnalités Agressives** :
   - Baisser `Facteur d'Ajustement dynISF` à 0.9
   - Augmenter l'intervalle SMB

### "Pas assez d'insuline pour les repas"

**Étapes** :
1. **Utiliser les Modes Repas** :
   - Ne pas compter uniquement sur l'auto-bolus
   - Activer le mode approprié 15 min avant de manger
2. **Augmenter le Prébolus du Mode Repas** :
   - Réglages → Modes Repas → Prébolus : 80-100%
3. **Vérifier le Ratio de Glucides** :
   - Peut nécessiter un ajustement via le Profil
4. **Utiliser Meal Advisor** :
   - Comptage des glucides plus précis

---

## 📊 Réglages Recommandés par Type d'Utilisateur

### Conservateur (Sujet aux Hypos)

```yaml
Max SMB > 120 : 0.5 U
Max SMB < 120 : 0.2 U
Max IOB : 8 U
Facteur dynISF : 100
Cible Glycémique : 110-120 mg/dL
Prébolus Autodrive : 0.1
```

### Équilibré (Standard)

```yaml
Max SMB > 120 : 1.0 U
Max SMB < 120 : 0.5 U
Max IOB : 15 U
Facteur dynISF : 200
Cible Glycémique : 100-110 mg/dL
Prébolus Autodrive : 0.5
```

### Agressif (Contrôle Serré)

```yaml
Max SMB > 120 : 1.5 U
Max SMB < 120 : 0.8 U
Max IOB : 25 U
Facteur dynISF : 300
Cible Glycémique : 90-100 mg/dL
Prébolus Autodrive : 1.0
```

---

**Dernière Mise à Jour** : 4 Janvier 2026  
**Version du Manuel** : 2.0  
**Version AIMI** : 3.4.0

