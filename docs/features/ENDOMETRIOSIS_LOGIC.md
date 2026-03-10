# 🌸 Module Endométriose & Cycle menstruel (AIMI)

Ce document détaille le fonctionnement du module de gestion de l'endométriose dans le système AIMI. Ce module est conçu pour adapter la délivrance d'insuline aux réalités physiologiques de l'inflammation chronique et des crises de douleur.

## 🎯 Philosophie : "Basal-First, SMB-Sober"

L'endométriose crée deux types de perturbations glycémiques :
1.  **Inflammation Chronique** (bruit de fond) : Augmente légèrement la résistance à l'insuline de manière constante.
2.  **Crise de Douleur ("Flare")** : Provoque un pic de Cortisol (stress) massif. Le foie libère du glucose, la résistance explose.

**La réponse AIMI :**
*   Le système privilégie une **augmentation du Basal** ("Basal-First") pour contrer la résistance de fond.
*   Il **calme les SMB (Super Micro-Bolus)** ("SMB-Sober") pendant les crises aiguës pour éviter l'hypoglycémie réactionnelle quand la douleur (et donc le Cortisol) chute brutalement.

---

## ⚙️ Les Modes de Fonctionnement

### 1. Mode Suppression Hormonale (Automatique)
*Détecté via vos réglages de contraception dans WCycle.*

Si vous prenez une contraception hormonale (Pilule, DIU Hormonal, Implant...) pour gérer l'endométriose, votre corps est dans un état inflammatoire stable mais "contrôlé".

| Paramètre | Ajustement | Effet |
| :--- | :--- | :--- |
| **Basal** | **+5%** (x1.05) | Compense la résistance de fond due au progestatif ou à l'inflammation résiduelle. |
| **ISF** | **-5%** (x0.95) | Rend le système insensiblement plus agressif sur les corrections. |
| **SMB** | **-5%** (x0.95) | Légère prudence pour éviter le sur-dosage. |
| **Activation** | Permanente (si Glycémie > 85 mg/dL) |

---

### 2. Mode Crise de Douleur "Pain Flare" (Manuel)
*Activé via l'interrupteur "Pain Flare Active" dans les préférences.*

À utiliser lors d'une crise douloureuse aiguë. C'est un état de "guerre" métabolique.

| Paramètre | Ajustement | Effet |
| :--- | :--- | :--- |
| **Basal** | **+30% à +50%** (Configurable) | **Augmentation massive** pour traverser le mur de résistance du Cortisol. C'est le moteur principal. |
| **ISF** | **~-25%** (Variable) | Suit l'augmentation du basal. Si le basal augmente, l'ISF diminue (plus agressif). |
| **SMB** | **Freiné (x0.7 ou moins)** | **CRITIQUE.** On limite les gros bolus instantanés. Pourquoi ? Parce qu'une crise de douleur peut s'arrêter net (médicament). Si vous avez 5 unités d'insuline active (IOB) par SMB à ce moment-là, c'est l'hypo sévère garantie. Le basal, lui, se coupe instantanément (TBR 0). |
| **Durée** | Timer de 4 heures | Se désactive automatiquement pour sécurité. |
| **Sécurité** | Se coupe si BG < 110 mg/dL | Ne s'active pas si vous êtes déjà bas. |

---

## 🔄 Interaction avec le Cycle Naturel (WCycle)

Si vous n'êtes **pas** sous suppression hormonale (Cycles naturels) :

1.  **Phase Folliculaire :** Sensibilité normale. Endométriose généralement calme.
2.  **Ovulation / Phase Lutéale :** Résistance naturelle (+20-40% via WCycle).
3.  **Menstruation (Règles) :** C'est la zone de danger pour l'endométriose.
    *   WCycle prévoit une *baisse* des besoins (chute hormonale).
    *   MAIS l'Endométriose prévoit une *hausse* (douleur/inflammation).
    *   **Arbitrage :** Si vous activez "Pain Flare" pendant les règles, **c'est le mode Flare qui gagne**. Le système ignorera la baisse de besoins prévue par WCycle pour appliquer la hausse de besoins nécessaire à la gestion de la douleur.

---

## 🛡️ Sécurités Intégrées

1.  **Safety Switch (< 85 mg/dL) :** Tout ajustement d'endométriose (Suppression ou Flare) est **immédiatement désactivé** si la glycémie passe sous 85 mg/dL.
2.  **Safety Switch Flare (< 110 mg/dL) :** Le mode "Crise" (très agressif sur le basal) ne s'enclenche que si la glycémie est au-dessus de 110 mg/dL.
3.  **Rapid Drop Protection :** Si la glycémie chute vite (`delta < -5`), les SMB sont totalement coupés, même en crise.

---
*Ce module est une aide à la décision, pas un substitut à la gestion médicale de l'endométriose.*
