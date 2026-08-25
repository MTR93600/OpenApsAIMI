# A9 — Surfaces produit AIMI : Advisor, Auditor, TPO, UI, LLM et fonctions optionnelles

> **Référence auditée :** `dev_OAPSAIMI` à `06e7bc5021ca8fdd976505d1fefb03cc88681c19`.  
> **But :** couvrir les dossiers AIMI qui ne sont ni le cœur Tree/Harmonia/RBT, ni le runtime ML,
> mais qui peuvent malgré tout modifier le dosage ou la configuration.

## 1. Conclusion

Les packages `advisor`, `context`, `tpo`, `compose`, `llm`, `comparison`, `quality`, `sos` et
`utils` ne doivent pas être traités comme un bloc unique « UI optionnelle ».

Ils contiennent quatre niveaux d'autorité différents :

1. **purement informatif** : rapport, coaching, viewer, diagnostic ;
2. **configuration supervisée** : recommandations et Apply explicite ;
3. **modification autonome de configuration** : TPO ;
4. **modulation dose-facing** : Meal Advisor, Local Sentinel/Auditor selon le mode.

Le niveau 3 et le niveau 4 appartiennent au périmètre de parité du moteur. Les écrans, caméras et
providers réseau peuvent être différés, mais leurs effets sur la configuration et la décision
doivent être modélisés dès le contrat v1.

## 2. Inventaire

| Package | Fichiers / LOC | Rôle dominant | Priorité iOS |
|---|---:|---|---|
| `advisor` | 63 / 15 207 | coaching, auditor, Meal Vision, oref, tuning | séparer par autorité |
| `context` | 11 / 3 459 | intents, influence, LLM et UI | modèles avant UI |
| `tpo` | 12 / 1 937 | overlay temporaire autonome | dosage/config critique |
| `compose` | 10 / 3 915 | Control Center et PKPD settings | UI iOS à réécrire |
| `comparison` | 8 / 2 553 | simulation/shadow/KPI | testkit, pas runtime initial |
| `quality` | 2 / 622 | replay quality et SMB binding | testkit/telemetry |
| `sos` | 2 / 454 | SMS urgence/permission | capability plateforme |
| `llm` | 3 / 336 | retry/prompt/model selection | shell réseau |
| `utils` | 7 / 1 051 | stockage, backup, logs | ports plateforme |

## 3. Matrice d'autorité

| Fonction | Observe | Modifie prefs | Modifie dose | Réseau | Classe migration |
|---|---:|---:|---:|---:|---|
| Profile Advisor report | oui | seulement après Apply utilisateur | non directement | optionnel | produit supervisé |
| PKPD Advisor | oui | après Apply utilisateur | au tick suivant via prefs | non requis | config versionnée |
| Control Center families | oui | oui, confirmé | au tick suivant via prefs | non | config versionnée |
| Meal Vision | oui | produit une estimation repas | peut déclencher Meal Advisor | oui | input utilisateur/platform |
| Meal Advisor one-shot | oui | consomme un trigger | **oui, SMB/TBR** | pas nécessaire au tick | chemin dose-facing |
| Local Sentinel | oui | non | oui si mode non audit-only | non | moteur commun candidat |
| Auditor externe | oui | non | peut moduler selon mode | oui | résultat différé, jamais hot path |
| TPO | oui | **oui automatiquement** | indirectement dès le tick | LLM optionnel | état/config critique |
| OREF personal scorer | oui | non | **non dans la baseline** (`CALIBRATED=false`) | non | shadow/informatif |
| Comparison simulator | oui | non | shadow uniquement | non | testkit |
| SOS | non | non | non | SMS | iOS feature distincte |

## 4. Advisor et Control Center

### 4.1 Ce qui peut être commun

- `AdvisorModels` ;
- causal analyzer et family bridge ;
- clinical report rules ;
- PKPD Advisor déterministe ;
- tuning context models/engine ;
- OREF feature/outcome/reason pipelines communs ; le scorer ONNX reste derrière un port runtime et
  le MLP personnel reste shadow jusqu'à correction objectif/readback/reload et calibration ;
- behavior family registry, profiles et projection des réglages ;
- recommendation visibility/cooldowns lorsqu'ils sont exprimés avec une clock injectée.

### 4.2 Ce qui reste dans les shells

- `AimiProfileAdvisorActivity` et `AimiModeSettingsActivity` ;
- `AimiControlCenterScreen`, PKPD Compose screens et Android resources ;
- notifications ;
- stockage d'historique concret ;
- appels réseau et gestion des secrets API ;
- Apply effectif des préférences.

### 4.3 Contrat recommandé

```kotlin
data class AdvisorInput(
    val metrics: AdvisorMetrics,
    val config: AimiConfigSnapshot,
    val history: AdvisorActionHistory,
    val capabilities: CapabilitySnapshot,
    val nowMs: Long
)

data class AdvisorProposal(
    val causes: List<CausalHypothesis>,
    val recommendations: List<ConfigChangeProposal>,
    val explanationContext: AdvisorExplanationContext
)
```

Le LLM reçoit `AdvisorExplanationContext` et peut produire une narration. Il ne crée pas de clé ou
de valeur hors des `ConfigChangeProposal` déterministes autorisées.

L'Apply retourne un `ConfigChangeEvent` enregistré avec : ancienne valeur, nouvelle valeur,
source, consentement, timestamp, version de config et possibilité de revert.

## 5. Meal Advisor et Meal Vision

Le dossier `advisor/meal` contient :

- contrats/provider vision ;
- providers OpenAI, Gemini, Claude et DeepSeek ;
- parsers et sanitizer ;
- caméra/bitmap et UI Android ;
- service de reconnaissance ;
- chemin one-shot consommé par `DetermineBasalAIMI2`.

### 5.1 Découpage iOS

| Élément | Cible |
|---|---|
| photo/camera picker | Swift/iOS |
| compression/image/base64 | shell iOS |
| appel provider | client réseau plateforme ou Ktor hors moteur |
| parser/sanitizer | `commonMain` |
| estimation repas confirmée | `MealIntentEvent` versionné |
| trigger one-shot | input du tick, consommé transactionnellement |
| logique Meal Advisor dose-facing | stage commun après safety précoce |

### 5.2 Invariant de consommation

Le trigger actuel est une préférence booléenne mise à `false` dans le tick. La cible doit être un
événement avec ID :

```text
MealAdvisorIntent(id, createdAt, expiresAt, carbs, confidence, userConfirmed)
```

Le résultat du tick contient `ConsumedIntent(id)`. Le shell commit la consommation en même temps
que l'état, ce qui évite un double SMB après crash ou relance.

### 5.3 Sécurité

- un résultat vision non confirmé ne possède aucune autorité insulinique ;
- la donnée provider est non fiable jusqu'au sanitizer et à la confirmation ;
- les API keys ne figurent jamais dans replay/Hormonitor/logs ;
- la safety précoce reste avant Meal Advisor ;
- un intent expiré ne peut pas être rejoué.

## 6. Auditor : séparation du Sentinel et du réseau

### 6.1 État actuel

`AuditorOrchestrator` combine :

- `LocalSentinel`, déterministe et offline ;
- triggers/rate limits/caches ;
- appel d'un Auditor externe ;
- state/UI status ;
- callback de modulation.

Le callback peut arriver après l'appel à `auditDecision`. Le code actuel documente que l'export
principal s'exécute après **l'appel**, pas nécessairement après le callback. Le callback capture
`finalResult` et peut encore en modifier SMB, rate ou duration.

Cette propriété est incompatible avec un résultat de moteur immuable et rend la décision difficile
à rejouer : selon la latence, l'export, la copie vers `APSResult` et l'enactment peuvent observer des
versions différentes du même objet.

### 6.2 Cible

**Local Sentinel** :

- calcul pur dans le moteur commun ;
- posture bornée, placée après la proposition et le second passage Harmonia mais avant l'enveloppe
  terminale immuable ; il ne peut que confirmer, réduire ou bloquer ;
- aucune UI ni clock globale ;
- résultat inclus dans la trace.

**Auditor externe** :

- jamais attendu dans le hot path ;
- reçoit la décision du tick N comme événement signé/versionné ;
- réponse stockée comme `AuditorAdvice` avec expiry ;
- utilisable au tick N+1 uniquement si fraîche, compatible avec le contexte et autorisée par le mode ;
- aucune mutation d'un `AimiTickResult` déjà retourné ;
- en mode `AUDIT_ONLY`, aucune influence sur la commande.

```text
tick N -> decision immutable -> AuditorRequest
                              -> async response
tick N+1 snapshot includes fresh AuditorAdvice? -> bounded policy
```

### 6.3 Gate de migration

Un test doit injecter des réponses Auditor à différentes latences. La commande du tick N doit rester
identique ; seule une réponse explicitement éligible dans le snapshot N+1 peut agir.

Ce placement est une correction architecturale à valider par ADR contre la baseline Android : aucun
module exécuté après l'enveloppe terminale ne peut relever SMB/TBR, et la safety terminale reste le
dernier mot. Si le comportement historique est d'abord émulé, la correction est dual-runnée et
mesurée avant activation.

## 7. TPO — Transient Preference Overlay

### 7.1 État actuel

TPO possède :

- ledger d'épisodes ;
- trigger déterministe ;
- construction de deltas ;
- validation LLM asynchrone optionnelle ;
- sessions avec TTL/revert/supersession ;
- écriture directe dans `Preferences` ;
- persistence JSON et historique ;
- notifications.

Dans `DetermineBasalAIMI2`, `onTickStart` peut expirer/revert une session et
`onPatientStateReady` peut lancer/appliquer une nouvelle session. Lorsque TPO signale un changement,
le tick recharge explicitement `MaxSMB` et `HighBGMaxSMB`, mais pas nécessairement toutes les autres
clés susceptibles d'être dans le pack.

Une réponse LLM peut aussi écrire des préférences depuis une coroutine indépendante. La vue de
configuration d'un tick peut donc être partiellement ancienne et partiellement nouvelle.

### 7.2 Cible : overlay immuable

TPO ne doit plus écrire les préférences de base pour influencer le moteur. Il produit un overlay
versionné :

```kotlin
data class AimiConfigOverlay(
    val sessionId: String,
    val packId: String,
    val createdAtMs: Long,
    val expiresAtMs: Long,
    val baseConfigHash: String,
    val changes: Map<AimiConfigKey, ConfigValue>,
    val authority: OverlayAuthority,
    val validation: OverlayValidation
)
```

Le shell construit au début du tick :

```text
effectiveConfig = baseConfig + activeOverlay
```

Cette configuration reste immuable jusqu'au tick suivant. Une validation LLM arrivée pendant le
tick est éligible seulement pour le prochain snapshot.

### 7.3 Provenance des labels ML

TPO, Meal Advisor et Auditor peuvent influencer la dose qui devient ensuite une cible du trainer
SMB. Le nouvel événement dataset doit donc porter au minimum :

- `originOwner` et `finalOwner` ;
- `intentId`, `overlaySessionId`, `auditorAdviceId` ;
- `baseConfigHash` et `effectiveConfigHash` ;
- dose avant/après chaque intervention ;
- statut user-confirmed/algorithmic/external-advice ;
- flags de censure pour l'entraînement.

Par défaut, une ligne modifiée par une autorité externe non représentée dans les features est exclue
de l'entraînement. Sinon le modèle apprendrait une action dont il ne connaît pas la cause.

### 7.4 Revert et édition utilisateur

Avec un overlay séparé, le revert supprime l'overlay sans réécrire toutes les préférences. Si
l'utilisateur modifie la config de base pendant une session :

- le nouvel hash de base est visible ;
- l'overlay peut continuer uniquement sur les clés non conflictuelles ;
- les conflits sont invalidés ou demandent confirmation ;
- aucune ancienne baseline ne réécrit le choix récent au moment du revert.

Cette architecture élimine la classe de bug « revert écrase une modification utilisateur ».

## 8. `context` et intents patient

### 8.1 Commun

- `ContextIntent` et son schéma ;
- parser et deserializer ;
- `ContextInfluenceEngine` ;
- calcul des durées/expiry avec clock injectée ;
- projection vers `PatientContextSnapshot`.

### 8.2 Plateforme

- Activity/ViewModel/RecyclerView/Compose ;
- saisie texte/voix ;
- LLM client et secrets ;
- persistence concrète ;
- notifications.

### 8.3 Invariant

Une activité déclarée et une activité détectée restent deux sources distinctes. Leur fusion est une
décision du moteur, pas une normalisation du shell. Chaque intent porte ID, source, création,
expiration, confiance et confirmation utilisateur.

## 9. UI et localisation

### 9.1 Android

L'UI contient environ 2 357 LOC Compose et 4 254 LOC d'Activities/View UI. Le plus grand coût isolé
est `AimiProfileAdvisorActivity.kt` (2 316 LOC, UI construite largement en Kotlin Android).

### 9.2 iOS

Pour une intégration Trio, la recommandation est SwiftUI :

- dashboard AIMI ;
- état Tree/Harmonia/RBT/PKPD ;
- Control Center et preview de config ;
- statut learners/models ;
- Hormonitor viewer/export ;
- permissions HealthKit ;
- diagnostics et rollback.

Les ViewModels peuvent consommer les DTO du framework. Il n'est pas nécessaire de porter les
Activities Android ou de rendre Compose Multiplatform bloquant pour le moteur.

Le statut modèle minimal expose pour chaque tête : `ACTIVE/SHADOW/PREVIOUS/CANDIDATE`, SHA,
feature schema, génération du dataset/checkpoint, provenance, runtime, dernière tentative et son
résultat, état du fallback, rollback disponible et dernière sortie de probe.

### 9.3 Raisons médicales et strings

Les raisons utilisées pour replay/diagnostic ne doivent pas dépendre du texte localisé. Le moteur
retourne :

```text
reasonCodes + structuredParameters
```

Le shell traduit ces codes. Les anciennes strings peuvent être dual-written pendant la transition
pour conserver la compatibilité des exports.

## 10. Providers LLM et secrets

Les providers OpenAI, Gemini, Claude et DeepSeek ne font pas partie du framework doseur commun.
Ils utilisent un port réseau hors moteur :

```kotlin
interface AimiAdvisoryProvider {
    suspend fun request(request: AdvisoryRequest): AdvisoryResponse
}
```

Règles :

- aucune clé dans `commonMain`, replay, logs ou crash report ;
- stockage Keychain/Keystore ;
- timeout, cancellation, rate limit et redaction ;
- réponse LLM validée par un schéma et bornée par une policy locale ;
- offline = comportement déterministe valide ;
- provider/version/prompt hash inscrits dans l'audit, jamais la clé.

## 11. Comparison et Quality

### 11.1 Destination

- `ComparisonData`, `KpiCalculator`, `PerformanceScorer`, `VirtualGlucoseEngine` et simulateur vont
  dans `:plugins:aimi-testkit` ou un module outil ;
- le parser CSV et le writer sont séparés du calcul ;
- `ReplayQualityExport` et `SmbBindingTrace` utilisent des DTO sérialisables communs ;
- les comparaisons ne peuvent jamais commander une pompe.

### 11.2 Extension nécessaire

Le comparateur actuel doit apprendre à comparer :

- état avant/après ;
- autorités et ownership ;
- TPO effective config hash ;
- version de modèle ;
- SHA, feature schema et générations model/dataset/checkpoint ;
- sorties d'inférence attendues et événements de publication ;
- Local Sentinel et advice externe consommé ;
- commande sémantique et commande quantifiée.

## 12. SOS et notifications

`EmergencySosManager` repose sur les permissions/SMS Android. Sur iOS, l'équivalent exact n'est pas
un prérequis du moteur et peut nécessiter un parcours utilisateur différent. Il doit être déclaré
comme capability :

- `SUPPORTED_AUTOMATIC` ;
- `SUPPORTED_USER_CONFIRMED` ;
- `UNSUPPORTED`.

Les notifications ordinaires ont un adaptateur iOS. Les alertes qui passent outre le mode silencieux
nécessitent un entitlement Apple spécial et ne peuvent pas être promises dans le contrat commun.

## 13. Séquencement

| Vague | Contenu | Gate |
|---|---|---|
| P0 | matrice d'autorité complète des 98 fichiers de ces packages | aucun chemin dose-facing mal classé |
| P1 | reason codes, intents et config events communs | replay des triggers/config |
| P2 | Local Sentinel commun + Auditor différé | aucune mutation après retour |
| P3 | TPO overlay immuable | config stable pendant un tick |
| P4 | Meal Advisor intent transactionnel | pas de double consommation |
| P5 | advisor/rules/common ViewModels | tests Native |
| P6 | SwiftUI + clients réseau + secrets | offline et permission tests |
| P7 | comparison/quality/testkit | rapports Android/iOS automatisés |

## 14. Charges indicatives

| Travail | P50 | P80 |
|---|---:|---:|
| matrice d'autorité et contrats events | 2–4 sp | 5–6 sp |
| Local Sentinel/Auditor lifecycle | 3–5 sp | 6–8 sp |
| TPO overlay et migration session | 3–5 sp | 6–8 sp |
| Meal Advisor intent + parsers | 2–4 sp | 5–7 sp |
| règles Advisor/Control Center communes | 3–5 sp | 6–8 sp |
| UI SwiftUI AIMI ciblée | 6–10 sp | 12–16 sp |
| clients LLM/secrets/observabilité | 3–5 sp | 6–8 sp |
| comparison/quality testkit | 2–4 sp | 5–7 sp |
| **Total avec recouvrements** | **18–28 sp** | **30–42 sp** |

Une v1 strictement doseur peut différer l'UI Advisor avancée et les providers LLM, mais ne peut pas
différer les contrats TPO, Meal Advisor et Auditor si ces fonctions sont actives dans la baseline à
reproduire.

## 15. Definition of Done de cette surface

1. chaque fichier possède une classe d'autorité ;
2. aucun callback ne mute une décision retournée ;
3. la configuration reste immuable pendant un tick ;
4. TPO est un overlay atomique et réversible ;
5. les intents one-shot sont consommés exactement une fois ;
6. Local Sentinel est rejouable/offline ;
7. l'Auditor externe n'agit qu'au tick suivant selon freshness ;
8. le moteur ne contient aucun client LLM, secret, Activity, ViewModel ou notification ;
9. les reason codes sont stables et localisés par le shell ;
10. toutes les fonctions optionnelles annoncent leurs capabilities iOS et leurs fallbacks.
11. OREF personnel et OnlineLearner restent explicitement `SHADOW` tant qu'un projet de calibration
    séparé n'a pas validé leur activation.
