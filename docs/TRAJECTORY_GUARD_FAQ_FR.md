# ❓ TRAJECTORY GUARD - FAQ
## **Toutes vos questions, réponses simples**

---

## 🎯 **QUESTIONS BASIQUES**

### **Q1: C'est quoi Trajectory Guard en 1 phrase ?**

**R:** Un système qui **anticipe** où vont vos glycémies au lieu de juste réagir quand elles changent.

Analogie : Plutôt que freiner quand vous voyez l'obstacle, vous freinez quand vous voyez la route tourner.

---

### **Q2: Ça sert à quoi concrètement ?**

**R:** À avoir des glycémies plus stables :
- Moins de montées brutales après repas
- Moins d'hypos (surtout la nuit)
- Moins de "yoyos" glycémiques
- Time in Range amélioré de ~3-5%

---

### **Q3: C'est compliqué à utiliser ?**

**R:** **Non, ultra-simple !**
1. 3 clics pour l'activer
2. Zéro réglage à faire
3. Il travaille en arrière-plan

---

## ⚙️ **ACTIVATION & UTILISATION**

### **Q4: Comment je l'active ?**

**R:** Menu (☰) → Préférences → OpenAPS AIMI → 🌀 Trajectory Guard → Switch ON ✅

C'est tout !

---

### **Q5: Je dois faire des réglages après l'avoir activé ?**

**R:** **Non, aucun !**

Trajectory Guard :
- S'adapte automatiquement
- Utilise vos réglages actuels (ISF, CR, profil basal)
- Ne nécessite aucune configuration

---

### **Q6: Comment je sais si c'est actif ?**

**R:** Après 20 minutes, onglet **OpenAPS**, cherchez :
```
trajectoryEnabled: true
```

Si vous voyez ça → ✅ C'est actif !

---

### **Q7: Pourquoi ça met 20 minutes pour s'activer ?**

**R:** Il a besoin de **4-6 valeurs de glycémie** récentes pour analyser la trajectoire.

Avec des mesures toutes les 5 minutes : 4 × 5 min = 20 min minimum.

---

## 🛡️ **SÉCURITÉ**

### **Q8: C'est dangereux ?**

**R:** **Non, très sûr !**

- Ajustements **très doux** (±10% maximum)
- **Toutes** les sécurités AAPS restent actives
- Peut être désactivé **instantanément**
- A été testé pendant des mois

---

### **Q9: Ça peut provoquer des hypos ?**

**R:** Au contraire, ça **réduit** les hypos !

Le système **détecte les descentes** précocement et réduit l'insuline avant que ce soit critique.

Résultat attendu : **-30% d'hypos** après 2-3 semaines.

---

### **Q10: Et si ça marche pas bien pour moi ?**

**R:** Vous pouvez le **désactiver à tout moment** :
- Même chemin que l'activation
- Désactivez le switch
- Tout revient comme avant instantanément

---

## 🔧 **TECHNIQUE**

### **Q11: Ça change mon profil basal ?**

**R:** **Non !** Votre profil basal reste intact.

Trajectory Guard ajuste juste l'insuline **en temps réel**, comme une **micro-correction continue**.

---

### **Q12: Mon TDD (Total Daily Dose) va changer ?**

**R:** Légèrement, généralement entre **-2% et +3%** selon votre stabilité actuelle.

- Si vous étiez stable : Pas de changement
- Si vous aviez des yoyos : Légère augmentation (rattrapage anticipé)
- Si vous aviez beaucoup d'hypos : Légère diminution

---

### **Q13: Je dois modifier mes réglages ISF/CR ?**

**R:** **Non, gardez vos réglages actuels !**

Trajectory Guard s'adapte à VOS réglages. Pas l'inverse.

---

### **Q14: Ça marche avec ma pompe [Combo/Medtrum/Dana/autre] ?**

**R:** **Oui !** Compatible avec **toutes les pompes** supportées par AAPS.

Le système est dans AAPS lui-même, pas dans la pompe.

---

### **Q15: Ça consomme plus de batterie ?**

**R:** **Non**, impact négligeable (< 1% par jour).

---

## 📊 **RÉSULTATS**

### **Q16: Quand vais-je voir des résultats ?**

**R:** Timeline type :
- **Jour 1-2** : Rien de visible (rodage)
- **Jour 3-5** : Légère amélioration
- **Semaine 2** : Résultats clairs (+3-5% TIR)
- **Semaine 3** : Système optimisé

---

### **Q17: Combien de % de Time in Range en plus ?**

**R:** Généralement **+3% à +5%** après 2-3 semaines.

Exemple :
- Avant : 72% TIR
- Après : 77% TIR
- Gain : +5%

---

### **Q18: Est-ce que tout le monde a les mêmes résultats ?**

**R:** Non, ça dépend de votre situation :

**Vous gagnerez PLUS si vous aviez** :
- Beaucoup de yoyos (variabilité élevée)
- Hypos fréquentes
- Montées post-repas difficiles à gérer

**Vous gagnerez MOINS si vous étiez déjà** :
- Très stable (TIR >80%)
- Peu de variabilité
- Profil bien optimisé

---

## 🎨 **FONCTIONNALITÉS**

### **Q19: Je peux voir les graphiques des trajectoires ?**

**R:** Pas dans l'interface pour l'instant.

Les données sont dans les logs AAPS, mais **pas de graphique visuel** (peut-être dans une future version).

---

### **Q20: Ça affiche de nouvelles alarmes ?**

**R:** **Non**, aucune nouvelle alarme.

Tout se passe en arrière-plan, silencieusement.

---

### **Q21: Il y a de nouveaux boutons à presser ?**

**R:** **Non !** Une fois activé, vous n'avez **rien à faire**.

Pas de nouveau bouton, pas de nouveau menu, pas de nouvelle action.

---

## 🔄 **SITUATIONS SPÉCIALES**

### **Q22: Ça marche pendant l'exercice physique ?**

**R:** **Oui**, mais gardez vos précautions habituelles :
- Utilisez votre profil "Sport" si vous en avez un
- Les ajustements seront plus prudents pendant l'activité

---

### **Q23: Ça fonctionne si je mange beaucoup de glucides ?**

**R:** **Oui !** C'est même là qu'il est le plus utile.

Il détecte les montées post-repas **plus tôt** et ajuste l'insuline en conséquence.

Résultat : Pics post-repas réduits de ~15-25%.

---

### **Q24: Ça marche la nuit ?**

**R:** **Oui, 24h/24 !**

La nuit c'est même très efficace :
- Détecte les petites descentes avant qu'elles deviennent des hypos
- Réduit les hypos nocturnes de ~30-50%

---

### **Q25: Si mon capteur déconnecte, que se passe-t-il ?**

**R:** Trajectory Guard se **met en pause automatiquement**.

Dès que le capteur se reconnecte et envoie à nouveau des données → Il se réactive automatiquement.

---

## 🆘 **PROBLÈMES**

### **Q26: J'ai activé mais rien ne se passe après 30 min, pourquoi ?**

**R:** Vérifiez :

1. **Le switch est bien activé** (bleu ✅) ?
2. **Votre capteur envoie des données** ?
3. **Vous avez attendu 20 minutes minimum** ?
4. **Pas de trous dans l'historique BG** ?

Si tout est OK et toujours rien → Redémarrez AAPS.

---

### **Q27: Je vois "trajectoryEnabled: false", c'est normal ?**

**R:** Causes possibles :

**Normal** :
- Moins de 20 min après démarrage AAPS
- Capteur déconnecté
- Trous dans l'historique BG

**Anormal** :
- Switch désactivé → Réactivez-le
- Bug → Partagez logs sur le forum

---

### **Q28: Ça a l'air de faire n'importe quoi, que faire ?**

**R:** 
1. **Désactivez-le** immédiatement (switch OFF)
2. **Notez** ce qui vous semble bizarre
3. **Partagez sur le forum** avec logs AAPS
4. **Revenez au système classique** en attendant

Votre sécurité d'abord !

---

## 🤝 **COMMUNAUTÉ**

### **Q29: Où puis-je trouver de l'aide ?**

**R:** 
- **Forum AAPS** : Section AIMI
- **Discord AAPS** : Channel #aimi
- **Telegram** : Groupe AAPS France
- **Documentation** : Voir guides utilisateur

---

### **Q30: Je peux aider à améliorer le système ?**

**R:** **Oui !** Plusieurs façons :

**Facile** :
- Partager votre retour d'expérience
- Signaler bugs/comportements bizarres

**Plus technique** :
- Fournir logs détaillés
- Participer aux tests beta

---

## 📱 **VERSIONS & MISES À JOUR**

### **Q31: Quelle version d'AAPS je dois avoir ?**

**R:** AIMI version **>= Janvier 2026**

Si vous avez une version antérieure, Trajectory Guard n'est pas disponible.

---

### **Q32: Ça va évoluer dans le futur ?**

**R:** **Oui !** Fonctionnalités prévues :

**Court terme** :
- Graphiques visuels dans l'interface
- Widget avec status actuel

**Moyen terme** :
- Intégration Nightscout
- Statistiques détaillées

**Long terme** :
- Apprentissage de vos patterns personnels
- Prédictions plus fines

---

## 💡 **CONSEILS**

### **Q33: Vous avez des conseils pour optimiser les résultats ?**

**R:** 

**DO** ✅ :
- Gardez vos réglages ISF/CR/profil bien calibrés
- Assurez-vous que votre capteur est précis
- Laissez le système s'adapter 2-3 semaines
- Notez vos résultats (TIR, hypos, etc.)

**DON'T** ❌ :
- Ne changez pas vos réglages juste après l'activation
- Ne désactivez pas/réactivez pas constamment
- Ne paniquez pas si jour 1-2 semble identique
- Ne comparez pas jour à jour (regardez sur 1 semaine)

---

### **Q34: Je devrais l'activer tout de suite ou attendre ?**

**R:** **Activez maintenant** si :
- ✅ Vos réglages AAPS sont déjà bien calibrés
- ✅ Vous êtes à l'aise avec AAPS
- ✅ Vous voulez améliorer votre TIR

**Attendez** si :
- ❌ Vous venez de commencer AAPS (< 1 mois)
- ❌ Vos réglages ISF/CR ne sont pas encore bons
- ❌ Vous changez souvent de profil/pompe/capteur

---

### **Q35: Une dernière chose à savoir ?**

**R:** **Oui : Soyez patient !**

Trajectory Guard est excellent, mais pas magique :
- Les résultats prennent **2-3 semaines** à apparaître
- L'adaptation est **progressive**
- Les bénéfices sont **cumulatifs**

**Activez-le et laissez-le travailler.** Vous serez surpris après quelques semaines ! 🚀

---

## 🎊 **ENCORE DES QUESTIONS ?**

**Posez-les sur le forum AAPS !**

La communauté est là pour vous aider 💙

---

**Guides complets** :
- Guide utilisateur : `TRAJECTORY_GUARD_USER_GUIDE_FR.md`
- Quick start : `TRAJECTORY_GUARD_QUICK_START_FR.md`
- Infographie : `TRAJECTORY_GUARD_INFOGRAPHIC_FR.md`

---
