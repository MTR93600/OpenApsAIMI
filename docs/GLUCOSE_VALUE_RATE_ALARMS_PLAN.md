# Plan de conception — Alarmes glycémiques : descente rapide / hypo / hyper (paramétrables en valeur)

> Statut : **PLAN — à valider avant tout développement.**
> Ces alarmes sont **purement notification** (son / vibration / notif système) — elles **ne touchent
> jamais au dosage** ni à la boucle. Pas de gate clinicien requis ; l'enjeu est l'ergonomie (pas de
> spam) et l'honnêteté des données (pas d'alarme sur données périmées). Ce n'est pas un conseil médical.

---

## 1. Objectif

Ajouter à AAPS trois alarmes locales, activables et **paramétrables en valeur** par l'utilisateur :
- **Hypo** : glycémie ≤ seuil.
- **Hyper** : glycémie ≥ seuil.
- **Descente rapide** : chute ≥ N mg/dL sur une fenêtre de M minutes.

Constat de l'étude de l'existant : **AAPS n'a aujourd'hui AUCUNE alarme sur la valeur de glycémie**.
Les seuils `low_mark`/`high_mark` (72/180) sont **purement visuels** (coloration du graphe). Le seul
système d'alerte local (`LocalAlertUtilsImpl`) ne couvre que *données périmées* + *pompe injoignable*.
Aucune alarme rate-of-change n'existe. → On comble une lacune réelle, **générique à toutes les
sources CGM** (Dexcom natif ONE+, xDrip, Nightscout…).

---

## 2. Décisions actées (arbitrages validés)

1. **Emplacement : système d'alertes AAPS générique** (pas dans le plugin ONE+). Fonctionne pour
   toutes les sources ; réutilise l'infra notif/son/snooze/préférences.
2. **Descente rapide = delta calculé côté AAPS** (variation des glycémies en base sur une fenêtre).
   Universel. (Le trend rate natif du ONE+ — aujourd'hui décodé puis jeté — reste une amélioration
   *optionnelle* future, hors de ce plan, cf. §10.)
3. **Seuils hypo/hyper = clés d'alarme dédiées** (indépendantes des marks visuels).

---

## 3. Nouvelles clés de préférence (`core/keys`)

Modèle existant : enums typées (`BooleanKey`, `IntKey`, `UnitDoubleKey`) + `dependency` pour griser
le champ quand l'interrupteur est off. Les seuils glycémiques utilisent **`UnitDoubleKey`**
(stockés en mg/dL, affichés dans l'unité de l'utilisateur via `profileUtil` — unit-aware mg/dL↔mmol).

| Clé | Type | Défaut (mg/dL) | Bornes | Rôle |
|---|---|---|---|---|
| `AlertHypo` | `BooleanKey` | off | — | active l'alarme hypo |
| `AlertHypoThreshold` | `UnitDoubleKey` | 70 (3.9) | 50–100 | seuil hypo (`dependency = AlertHypo`) |
| `AlertHyper` | `BooleanKey` | off | — | active l'alarme hyper |
| `AlertHyperThreshold` | `UnitDoubleKey` | 250 (13.9) | 140–400 | seuil hyper (`dependency = AlertHyper`) |
| `AlertRapidFall` | `BooleanKey` | off | — | active l'alarme descente rapide |
| `AlertRapidFallDrop` | `UnitDoubleKey` | 30 (1.7) | 15–60 | ampleur de chute (unit-aware) (`dependency = AlertRapidFall`) |
| `AlertRapidFallWindow` | `IntKey` (min) | 15 | 5–30 | fenêtre de mesure de la chute (`dependency = AlertRapidFall`) |

> `AlertRapidFallDrop` est une **différence** de glycémie : `UnitDoubleKey` convertit correctement
> (30 mg/dL ≈ 1.7 mmol). Bornes clonées sur le modèle `AlertsStaleDataThreshold` (`IntKey.kt`).

---

## 4. Notifications

Ajouts à `NotificationId` (`core/interfaces/.../notifications/NotificationId.kt`), catégorie CGM :

| Id | Niveau proposé | Son | Comportement |
|---|---|---|---|
| `BG_HYPO` | **URGENT** | `R.raw.alarm` | son + plein écran (danger immédiat) |
| `BG_HYPER` | **IMPORTANT** | (option son) | notif prioritaire, pas plein écran par défaut |
| `BG_RAPID_FALL` | **URGENT** | `R.raw.alarm` | son + plein écran (risque hypo imminente) |

`allowMultiple = false` par id. Le niveau URGENT est le seul qui déclenche son + rampe de volume +
plein écran (respecte `AlertIncreaseVolume` / `AlertOverrideDoNotDisturb` globaux existants).
> **À trancher** : hyper en IMPORTANT (défaut proposé) ou URGENT ? (voir §9)

Émission (pattern existant `LocalAlertUtilsImpl.kt:70`) :
```kotlin
notificationManager.post(
    NotificationId.BG_HYPO,
    rh.gs(R.string.alert_hypo_message, profileUtil.fromMgdlToStringWithUnits(bg)),
    soundRes = R.raw.alarm,
    actions = listOf(NotificationAction(R.string.snooze) { snooze(BG_HYPO) }),
)
```
Dismiss : `notificationManager.dismiss(NotificationId.BG_HYPO)`.

---

## 5. Logique de détection

**Où** : nouvelle méthode `checkGlucoseAlerts()` sur `LocalAlertUtils` (interface `core/interfaces`),
implémentée dans `LocalAlertUtilsImpl`, **appelée par `KeepAliveWorker`** juste après
`checkStaleBGAlert()` (`KeepAliveWorker.kt:151`) — même cadence (~1 min).

**Étapes (par tick) :**
1. `last = persistenceLayer.getLastGlucoseValue()`.
2. **Garde fraîcheur** : si `last` absent ou plus vieux que le seuil de données périmées
   (`missedReadingsThreshold()`), **ne rien faire** — c'est le rôle de l'alarme *stale*. Pas d'alarme
   sur donnée morte.
3. **Hypo** (si `AlertHypo`) : `last.value ≤ AlertHypoThreshold` → arme `BG_HYPO`.
4. **Hyper** (si `AlertHyper`) : `last.value ≥ AlertHyperThreshold` → arme `BG_HYPER`.
5. **Descente rapide** (si `AlertRapidFall`) :
   - `window = getBgReadingsDataFromTime(now − AlertRapidFallWindow·60_000, ascending = true)`.
   - besoin de ≥ 2 lectures valides couvrant la fenêtre (sinon skip — trou de données).
   - `drop = window.first().value − last.value` ; si `drop ≥ AlertRapidFallDrop` **et** tendance
     descendante (delta courant < 0) → arme `BG_RAPID_FALL`.
6. **Hystérésis (anti-flapping)** à la levée :
   - hypo : dismiss quand `value ≥ AlertHypoThreshold + 5 mg/dL`.
   - hyper : dismiss quand `value ≤ AlertHyperThreshold − 10 mg/dL`.
   - rapid-fall : single-shot par épisode ; ré-armable quand la pente redevient ≥ −(drop/window)/2.
7. **Ré-alarme / snooze** : réutiliser le pattern `LocalAlertLongKey` (comme
   `NextMissedReadingsAlarm`). Ajouter `NextHypoAlarm` / `NextHyperAlarm` / `NextRapidFallAlarm` :
   tant que la condition persiste, **re-poster au plus toutes les** 15 min (hypo/rapid-fall) / 30 min
   (hyper). L'action « Snooze » de la notif repousse ce prochain-instant.

Tout est **suspend** (accès persistence), comme `checkStaleBGAlert()`.

---

## 6. UI — écran « Alertes » existant

L'écran est déclaré centralement dans `BuiltInSearchables.alerts`
(`ui/.../search/BuiltInSearchables.kt:190`), rendu par `AllPreferencesScreen`. On **ajoute une
section « Alarmes glycémie »** avec les 7 clés du §3 :
- Hypo : switch + seuil (`AdaptiveUnitDoublePreference`, unit-aware).
- Hyper : switch + seuil.
- Descente rapide : switch + ampleur de chute (unit-aware) + fenêtre (minutes, `Adaptive`Int).

Aucun nouvel écran, aucune nouvelle dépendance inter-module. Les champs seuils se grisent via
`dependency` quand le switch parent est off (pattern `AlertsStaleDataThreshold`).

---

## 7. Strings (EN uniquement)

Nouveaux titres/résumés + textes de notif. Les textes de notif **portent la valeur avec son unité**
via un **template à placeholder** (jamais de concaténation — règle projet) :
- `alert_hypo_message` = `"Low glucose: %1$s"` (%1$s = valeur+unité via `fromMgdlToStringWithUnits`).
- `alert_hyper_message` = `"High glucose: %1$s"`.
- `alert_rapid_fall_message` = `"Glucose dropping fast: %1$s over %2$s"` (%1$s = chute+unité, %2$s = fenêtre).
- titres/summary des préférences + `comment="..."` sur celles à placeholders/unités.

---

## 8. Sécurité & comportement

- **Notification-only** : n'écrit rien dans la boucle, ne modifie aucun basal/SMB. Aucune interaction
  avec le dosage.
- **Pas d'alarme sur données périmées** (garde §5.2) — évite de hurler sur un capteur déconnecté.
- **Anti-spam** : hystérésis + intervalle de ré-alarme + snooze.
- **DND / volume** : hérite des réglages globaux existants (`AlertOverrideDoNotDisturb`,
  `AlertIncreaseVolume`) — pas de nouveau canal son.
- **Unit-aware** : seuils et messages en mg/dL ou mmol/L selon le profil.
- **Désactivé par défaut** : les 3 switches sont off à l'installation (opt-in explicite).

---

## 9. Schéma de validation (comportement attendu)

`Hy=AlertHypoThreshold(70)`, `Hr=AlertHyperThreshold(250)`, `Drop=30/15min`. Fraîcheur OK sauf mention.

| # | Contexte | Attendu |
|---|---|---|
| A1 | BG 68, hypo ON | `BG_HYPO` URGENT posté (son), message « Low glucose: 68 mg/dL » |
| A2a | BG 73 après A1 (< seuil+5 = 75) | `BG_HYPO` **maintenu** (dans la bande d'hystérésis) |
| A2b | BG 76 après A1 (≥ seuil+5 = 75) | `BG_HYPO` **dismiss** (remonté franchement) |
| A3 | BG 68, hypo **OFF** | rien |
| A4 | BG 260, hyper ON | `BG_HYPER` IMPORTANT posté, « High glucose: 260 mg/dL » |
| A5 | BG 240 après A4 (dismiss ≤240) | `BG_HYPER` dismiss |
| A6 | 150→115 en 15 min (chute 35 ≥ 30), rapid ON | `BG_RAPID_FALL` URGENT posté |
| A7 | 150→130 en 15 min (chute 20 < 30) | rien |
| A8 | chute 35 mais 1 seule lecture dans la fenêtre | rien (trou de données) |
| A9 | BG 68 mais dernière lecture > seuil stale | rien (alarme stale gère) |
| A10 | hypo actif, condition persiste, < 15 min depuis post | pas de re-post (throttle) |
| A11 | hypo actif, snooze appuyé | pas de re-post avant fin du snooze |
| A12 | mmol : seuil 3.9, BG 3.7 mmol | `BG_HYPO`, message en mmol/L |

**Invariants :** J1 = aucune écriture boucle/dosage ; J2 = jamais d'alarme si données périmées ;
J3 = au plus 1 notif active par type ; J4 = throttle respecté (pas de spam par tick).

---

## 10. Découpage & tests

| Lot | Périmètre | Test |
|---|---|---|
| L1 clés | 7 clés dans `core/keys` (+ `LocalAlertLongKey` ×3) | compile |
| L2 détection | `checkGlucoseAlerts()` + hystérésis + throttle dans `LocalAlertUtilsImpl` ; hook `KeepAliveWorker` | tests unitaires matrice A1–A12 + invariants J1–J4 (extension de `LocalAlertUtilsImplTest`) |
| L3 notifs | 3 `NotificationId` + strings EN + templates unité | — |
| L4 UI | section dans `BuiltInSearchables.alerts` | rendu / grisage `dependency` |
| L5 vérif | compile module/module, revue conventions (imports explicites, pas de concat de strings), non-régression alertes existantes | GO/NO-GO |

**Amélioration future (hors scope, à discuter séparément) :** cesser de jeter le **trend rate natif
ONE+** (mg/dL·min, cf. parsing `OnePlusGlucoseParser` → aujourd'hui perdu) et l'exposer sur le `GV`
pour un rapid-fall plus réactif quand la source le fournit — via une interface source-agnostique,
fallback sur le delta calculé.

---

## 11. Definition of Done
Matrice A1–A12 + invariants J1–J4 verts ; modules compilent ; alertes existantes non régressées ;
3 alarmes off par défaut, unit-aware, paramétrables en valeur ; rien de commité sans validation.

*Plan préparé en tant qu'architecte logiciel senior — à valider avant la phase de développement.*
