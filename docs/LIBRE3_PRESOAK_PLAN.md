# Libre 3 pre-soak (staging) — design specification

Status: **specification only**. No production code is changed by this document.

Reference implementation: `docs/DEXCOM_ONEPLUS_DUAL_SENSOR_STAGING_PLAN.md` and the Dexcom ONE+
staging code. This plan mirrors that structure. Where it deviates, the deviation is named and the
reason is given.

---

## 1. Goal and non-goals

### 1.1 Goal

Let a user start a **new Libre 3 sensor while the current one keeps feeding the loop**. The new
sensor warms up and reads glucose in a second, parallel slot. Its warm-up and its glucose are
**visible** to the user, and they **never** reach the database and never reach the loop. One button
promotes the new sensor to production with a clean cut.

The user gains what a pre-soak is for: the new sensor is already past its 60 minute warm-up and
already settled when the old one ends, so there is no gap without glucose.

### 1.2 Non-goals

- No change to the generic staging frame in `:core:interfaces` or to the dashboard card. Both
  already exist and already work for the ONE+.
- No third slot. Exactly one PRODUCTION sensor and at most one STAGING sensor.
- No automatic promotion. Promotion is always an explicit user action.
- No calibration, no cross-sensor blending, no "average the two sensors". The pre-soak sensor is
  collect-only until the moment it becomes the only sensor.
- No change to how Libre 3 glucose is parsed, decrypted or timed. The pre-soak reuses the existing
  driver end to end.
- No back-port of the improvements below to the ONE+, except the one foreground-service fix the
  user asked for in Lot 6.

---

## 2. Invariants

These are the rules the whole design rests on. Every lot below is checked against them.

- **I1 — A STAGING sample never reaches `PersistenceLayer.insertCgmSourceData` and never reaches the
  loop.** The staging watcher has no reference to the persistence layer on its collect-only path. It
  writes only to an in-memory buffer and to in-memory counters. This is the invariant the unit test
  in §9 asserts directly.

- **I2 — A STAGING operation never touches the production sensor's crypto material.** The production
  `phase5_raw_key`, `k_enc` and `iv_enc` live in the production preferences file. No staging call
  path ever opens that file for writing. This is the single most important invariant of the whole
  feature, because `Libre3SensorStore.saveIdentityAndWait` deletes exactly those three keys when the
  serial changes, and a running Libre 3 refuses a fresh first pairing — so a lost production key is
  a **permanently unreachable production sensor**.

- **I3 — A STAGING operation never touches the global `Libre3Ingest` state.** `Libre3Ingest` is a
  process-wide `object` whose dedup floor is keyed on `lifeCount`, and a new sensor restarts its
  `lifeCount` at 0. A staging sample calling `Libre3Ingest.shouldAccept` would either be swallowed or
  would lower the production floor and let a repeated production reading through.

- **I4 — One physical sensor is never held by two slots at once.** Enforced on the serial **and** on
  the BLE MAC, and enforced *before* the NFC activation command is sent, not after.

- **I5 — Promotion is the only action that changes which sensor feeds the loop.** It is a user
  action, it is logged, and it is atomic at the store level (one `commit()`).

- **I6 — After promotion the promoted sensor is the production sensor in every sense**: identity and
  keys in the production preferences file, publishing through the normal production watcher, resumed
  by the production path after a restart.

- **I7 — Every write the Bluetooth work depends on uses `commit()`, not `apply()`.** Unchanged rule
  from the existing driver, restated because promotion adds a new such write (`adopt`).

- **I8 — The whole feature is off unless the user turns it on.** With the preference off, the code
  paths below are unreachable and the plugin behaves exactly as it does today.

- **I9 — A failure in the pre-soak never degrades production.** Every staging call site that touches
  the driver, the store or the notification is wrapped so that a throw cannot escape into a
  production path. A pre-soak that cannot start is a message on screen, never a production outage.

---

## 3. The trade-off the user accepted

On a Dexcom ONE+ / G7 the wear clock starts when the transmitter is paired, so a pre-soak costs
almost nothing.

**On a Libre 3 the warm-up clock and the 14-day wear clock both start at the NFC activation.** The
sensor counts minutes itself (`lifeCount`), and everything — `remainingWarmupMinutes`,
`remainingWearMinutes`, `isExpired` — is derived from that one counter in `Libre3WarmupClock`. There
is no way to activate a Libre 3 "quietly" and start its life later.

So a pre-soak on a Libre 3 **spends real wear time**. A 12 hour pre-soak leaves 13.5 days of usable
life instead of 14. The user has accepted this. The UI must say it plainly on the Start screen when
the STAGING slot is chosen, so nobody discovers it afterwards.

The design consequence: **there is no minimum soak gate.** Charging the user wear time and then
refusing to let them use it would be the worst of both. See §4 and §7.

---

## 4. Decisions taken, with reasons

| # | Decision | Reason |
|---|---|---|
| D1 | Promotion rejects **only** `STAGING_ABSENT`. No time-based soak gate. | §3. The user pays wear time for the soak; the app must not also veto. Soak duration and reading count are shown as **information**. |
| D2 | `allowEarly` is a **no-op** for Libre 3. | With no soak gate there is nothing for it to relax. `promoteStagingToProduction(true)` and `(false)` behave identically. The parameter stays in the signature because the shared interface declares it and the ONE+ uses it. |
| D3 | The pre-soak glucose curve lives in **`Libre3StatusActivity`**, not in `DashboardStagingCard`. | The card is source-agnostic and shared with the ONE+. A Libre-3-only chart there would need either a new field on `CgmSensorStatusProvider` (the generic frame must not change) or a type check on the plugin inside `:plugins:main` — and `:plugins:main` does not depend on `:plugins:libre3`, so that would mean a new inter-module dependency, which is forbidden. A pre-soak curve is also an *inspection* surface looked at a few times over many hours, while the dashboard is a *glance* surface. |
| D4 | The production preferences file keeps its current name `libre3_sensor_store`; only STAGING gets a suffix (`libre3_sensor_store_staging`). | This is the ONE+ pattern, and it makes the migration a **no-op by construction**: no copy, no window in which the production crypto keys exist in one file and are expected in another. A rename-plus-copy migration would put I2 at risk for zero benefit. See §5.1 for the migration question answered in full. |
| D5 | The receiver id (`app_uuid` / `receiver_id`) is **shared** by both slots and always read and written in the production file. | It is the phone's identity, not a sensor's. A per-slot receiver id would give the same phone two identities, and a sensor binds itself to the receiver that activated it. This is a deliberate addition over the ONE+, which has no such concept. |
| D6 | Promotion **swaps the driver instances' roles** instead of setting a `publishesToLoop` flag on a fixed instance. | See §4.1. This keeps the "no BLE reconnection" property the user asked for **and** avoids two latent defects the flag-based ONE+ shape has. |
| D7 | The foreground service gets its **own** preference, separate from the pre-soak preference. | The service fixes standby session drops for the single-sensor case too. Tying it to the pre-soak would make a general fix unreachable for users who never pre-soak. The pre-soak preference must never silently switch the service preference on. |

### 4.1 Why not copy the ONE+ promotion shape literally

`DexcomOnePlusPlugin` promotes by setting `stagingPublishesToLoop = true` on a fixed staging
instance, shutting the production instance down, and clearing the staging store. Two things in that
shape do not survive a second pass, and one of them is fatal on Libre 3:

1. **The retired instance is dead for good.** `Libre3CgmDriverReal.shutdown()` calls
   `bleExecutor.shutdownNow()`. The instance is held by a `by lazy` singleton, so it can never open a
   session again. After one promotion there is no working production instance left; a second
   pre-soak and promotion has nowhere to go.
2. **The promoted instance keeps reading the wrong preferences file.** On Libre 3 the driver's store
   is bound in `setContext` and used by every reconnect (`openSession` → `Libre3BleSession(client,
   sensorStore, pairingBlocks)`). A promoted instance still bound to the staging namespace, whose
   file was just cleared, fails its next reconnect with `NO_SENSOR_STORED` — i.e. the loop loses its
   sensor at the first link loss after promotion.

The instance swap in §6.2 and §7 fixes both by construction and costs about thirty lines.

---

## 5. Lot 1 — namespaced `Libre3SensorStore`

**This lot is the blocker.** Today `Libre3StartActivity` builds `Libre3SensorStore(this)` — the one
un-namespaced file — and the NFC scan writes the new sensor into it. `saveIdentityAndWait` sees a
different serial and **deletes** `phase5_raw_key`, `k_enc`, `iv_enc`, `last_life_count` and
`sensor_change_logged_serial`. The running production sensor is then unreachable for the rest of its
life, because a running Libre 3 refuses a fresh first pairing. Lot 1 alone does not fix this — Lot 5
must land with it, because Lot 5 is what makes the Start screen open the *staging* file.

### 5.1 Files

Modify: `plugins/libre3/src/main/kotlin/app/aaps/plugins/libre3/identity/Libre3SensorStore.kt`

### 5.2 Signature change

```kotlin
class Libre3SensorStore(
    context: Context,
    namespace: String? = null,
) : Libre3IdentityStore, Libre3SessionStore
```

Internals:

```kotlin
private val appContext = context.applicationContext
private val prefsName = if (namespace.isNullOrBlank()) PREFS_NAME else "${PREFS_NAME}_$namespace"
private val prefs = appContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

/**
 * The phone's own identity, always in the production file — see D5. A per-slot receiver id would
 * give one phone two identities, and a sensor binds itself to the receiver that activated it.
 */
private val identityPrefs =
    if (namespace.isNullOrBlank()) prefs
    else appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
```

`receiverId()` reads and writes `identityPrefs` (`KEY_APP_UUID`, `KEY_RECEIVER_ID`) instead of
`prefs`. Everything else in the class is untouched and keeps using `prefs`.

### 5.3 Migration and back-compat

**There is no migration, and that is the point.** `namespace == null` maps to `libre3_sensor_store`,
byte for byte the file existing installs already have. An existing user's serial, MAC, PIN, receiver
id, generation, warm-up minutes, wear minutes, activation time, life counter, pairing key and
session keys are all read from the same file by the same keys as before. Nothing is copied, so
nothing can be half-copied. This is a stronger back-compat answer than a rename plus a migration
step, and it removes the only realistic way to break I2.

If a reviewer later insists on an explicit `production` namespace, the migration would have to be: a
single `commit()` that writes every key of the old file into the new one, verified by re-reading the
PIN and the pairing key before the old file is deleted, with the delete skipped on any mismatch.
That is strictly more risk for no user-visible gain, so this plan does not do it.

### 5.4 New members

```kotlin
/** Wipe this slot's file completely. Only for the STAGING file — see the note below. */
@Synchronized
fun clearAll(): Boolean

/**
 * Persist this slot's collect-only progress, so a process restart does not reset a warming
 * pre-soak to "no sensor".
 */
fun saveSlotProgress(present: Boolean, validReadingCount: Int)

fun loadSlotPresent(): Boolean

fun loadSlotValidReadingCount(): Int

/** Latch: this slot's sensor has left warm-up at least once. */
fun saveSlotWarmupDone(done: Boolean)

fun loadSlotWarmupDone(): Boolean

/** Epoch ms the pre-soak slot's sensor was really activated, 0 when unknown. */
fun saveSlotActivatedAt(epochMs: Long)

fun loadSlotActivatedAt(): Long

/**
 * Take another slot's sensor over into this file — used by promotion, so the production driver
 * durably resumes the promoted sensor after a restart.
 *
 * One `commit()`, so it either all lands or none of it does. The dropped keys matter:
 * `last_life_count` belongs to the OLD sensor and would refuse every reading of the new one for
 * its whole life; `sensor_change_logged_serial` must go so the new sensor's SENSOR_CHANGE is
 * written.
 *
 * @return true only when the write really reached the disk.
 */
@Synchronized
fun adopt(identity: Libre3SensorIdentity, keys: Libre3SessionKeys): Boolean
```

`clearAll` note: it must never be called on the production file. The existing `clear()` (which keeps
the receiver id) stays as the "forget this sensor" action of the Status screen and is unchanged.
With D5 the receiver id no longer lives in the staging file at all, so `clearAll` on the staging file
cannot destroy it.

`adopt` writes, in one editor: `KEY_SERIAL`, `KEY_MAC`, `KEY_PIN`, `KEY_RECEIVER_ID`,
`KEY_GENERATION`, `KEY_WARMUP_MINUTES`, `KEY_WEAR_MINUTES`, `KEY_ACTIVATED_AT`, and each of
`KEY_PHASE5_RAW_KEY` / `KEY_K_ENC` / `KEY_IV_ENC` that is non-null in `keys`; and removes
`KEY_LAST_LIFE_COUNT` and `KEY_SENSOR_CHANGE_SERIAL`. It does **not** go through
`saveIdentityAndWait`, precisely because that method's serial-change branch would delete the keys it
has just been asked to install.

---

## 6. Lot 2 — a second driver instance

### 6.1 Files

Modify: `plugins/libre3/src/main/kotlin/app/aaps/plugins/libre3/Libre3CgmDriverReal.kt`
Modify: `plugins/libre3/src/main/kotlin/app/aaps/plugins/libre3/Libre3CgmDrivers.kt`

`:plugins:libre3` already has `implementation(project(":core:interfaces"))`, so
`app.aaps.core.interfaces.source.SensorSlot` is importable there. **No new module dependency.**

### 6.2 `Libre3CgmDriverReal`

```kotlin
class Libre3CgmDriverReal(
    private val pairingBlocks: Libre3PairingBlockFactory,
    storeNamespace: String? = null,
) : Libre3CgmDriver {

    /**
     * Which preferences file this instance's sessions read and write.
     *
     * A `var` and not a constructor `val` because promotion hands a **live link** over from the
     * pre-soak slot to production: the link must not be dropped, but from the promotion on, every
     * new session of this instance must open against the production file. See [rebindStore].
     */
    @Volatile
    private var storeNamespace: String? = storeNamespace

    /** Short name of this instance for the log and for the MAC arbiter: "prod" or "presoak". */
    private val slotName: String = if (storeNamespace.isNullOrBlank()) SLOT_PRODUCTION else SLOT_STAGING
}
```

Changes inside:

- `setContext` becomes
  `this.store = Libre3SensorStore(context.applicationContext, storeNamespace)`.
- The executor thread name becomes `"libre3-ble-$slotName"`, so two concurrent sessions can be told
  apart in a bug report.
- Every `Libre3Log` line in this class gains a `[$slotName]` prefix, following
  `OnePlusMacArbiter`'s convention. Nothing else about logging changes.
- New:

```kotlin
/**
 * Point this instance at another slot's preferences file, without dropping the running link.
 *
 * Used once, by promotion. The running read loop captured its store as a local, so it keeps
 * writing wear extensions into the old file until the link ends; that is harmless (see §7 step 9)
 * and the next session opens against the new file.
 */
fun rebindStore(namespace: String?) {
    storeNamespace = namespace
    context?.let { store = Libre3SensorStore(it, namespace) }
}

/** True when a sensor with a PIN is stored for this instance's slot. */
fun hasStoredSensor(): Boolean = store?.isReadyForBle() == true
```

### 6.3 `Libre3CgmDrivers`

The `by lazy` singletons are replaced by nullable fields behind the existing `lock`, so an instance
that has been shut down can be dropped and rebuilt.

```kotlin
object Libre3CgmDrivers {

    /** Preferences namespace of the pre-soak slot. */
    const val STAGING_NAMESPACE = "staging"

    /** The one definition of a slot's namespace, shared by the drivers and by any UI that opens a store. */
    fun storeNamespace(slot: SensorSlot): String? = when (slot) {
        SensorSlot.PRODUCTION -> null
        SensorSlot.STAGING    -> STAGING_NAMESPACE
    }

    @Volatile private var productionReal: Libre3CgmDriverReal? = null
    @Volatile private var stagingReal: Libre3CgmDriverReal? = null

    /** The instance that feeds the loop. Built on first use. */
    fun realProduction(): Libre3CgmDriverReal

    /** The pre-soak instance. Built on first use, always the Real driver. */
    fun staging(): Libre3CgmDriverReal

    /** Why the pre-soak cannot run, or null when it can. Staging is always Real, so only the tables matter. */
    fun stagingBlockedReason(): String?

    /**
     * Hand the pre-soak instance the production role, keeping its live link.
     *
     * Under [lock]: rebinds the promoted instance to the production file, makes it the production
     * instance, and frees the pre-soak pointer so the next pre-soak builds a fresh instance.
     *
     * @return the instance that has just been retired, for the caller to shut down **outside** the
     *   lock, or null when there was no pre-soak instance to promote.
     */
    fun promoteStagingInstance(): Libre3CgmDriverReal?
}
```

`default()` becomes
`if (useRealSkeleton && Libre3RuntimeTables.pairingTablesPresent()) realProduction() else Libre3CgmDriverStub.instance`
— same condition as today, only the instance source changes.

`select()` is unchanged in behaviour and still only swaps Stub ↔ Real for **production**. It must
never touch `stagingReal`: turning the engineering switch off does not end a pre-soak, it only stops
production from using the Real driver. Add that sentence to its KDoc.

### 6.4 Back-compat

With the pre-soak preference off, `staging()` and `promoteStagingInstance()` are never called, and
`realProduction()` returns the same single Real instance the `by lazy` returned before. The only
observable difference is the thread name.

---

## 7. Lot 3 — the collect-only path

### 7.1 Files

Create: `plugins/source/src/main/kotlin/app/aaps/plugins/source/Libre3Staging.kt` (pure logic)
Modify: `plugins/source/src/main/kotlin/app/aaps/plugins/source/Libre3NativePlugin.kt`

### 7.2 `Libre3Staging` — the pure part

Mirrors `DexcomOnePlusStaging`. Kept out of the plugin so the state machine is unit-testable without
Android and without the DI graph.

```kotlin
internal object Libre3Staging {

    private const val HOUR_MS = 60L * 60L * 1000L
    private const val MINUTE_MS = 60L * 1000L

    /** Fallback sensor life when the NFC scan did not report a wear time. Libre 3 is 14 days. */
    const val DEFAULT_LIFE_MS = 14L * 24L * HOUR_MS

    /** End-of-life grace. A sensor that still reads past its stored end is still a sensor. */
    const val SENSOR_GRACE_MS = 12L * HOUR_MS

    /** Early-life window: fresh Libre 3 readings run low and jumpy for about half a day. */
    const val EARLY_LIFE_MS = 12L * HOUR_MS

    /** End-of-life window: prompt the user to start the next sensor. */
    const val END_OF_LIFE_MS = 12L * HOUR_MS

    /**
     * Readings needed before the pre-soak slot is shown as READY.
     *
     * This is NOT a soak gate (see §3 and D1): a Libre 3 speaks once a minute, so five readings is
     * five minutes. It only means "the sensor really talks", so the dashboard's promote button is
     * never offered for a sensor that has produced nothing.
     */
    const val STAGING_MIN_VALID_READINGS = 5

    /** How many pre-soak readings the in-memory curve keeps: 24 h at one a minute. */
    const val CURVE_CAP = 1440

    /**
     * Two slots must never hold one physical sensor.
     *
     * Serial **or** MAC is enough to say "same sensor": the serial is known from the NFC patch-info
     * read, before any activation command is sent, and the MAC is what a stored session is keyed on.
     * Both comparisons are case-insensitive, because a serial reaches the store from an NFC parse
     * and a MAC from an NFC parse or a scan hit, and only some of those upper-case.
     */
    fun isSameSensor(storedSerial: String?, storedMac: String?, serial: String?, mac: String?): Boolean

    /**
     * Sensor life from the moment it was activated. [wearMinutes] is what the NFC scan read from the
     * sensor; null falls back to [DEFAULT_LIFE_MS], which also covers Libre 3 Plus conservatively.
     */
    fun computeLifecycle(
        slot: SensorSlot,
        activatedAtMs: Long,
        wearMinutes: Int?,
        nowMs: Long,
    ): CgmSensorLifecycle?

    /** See §8 for the mapping this implements. */
    fun computeStagingState(
        present: Boolean,
        warming: Boolean,
        validReadingCount: Int,
    ): StagingState

    /** What the pre-soak slot knows about its warm-up after one driver phase — see [applyWarmupPhase]. */
    data class StagingWarmupDecision(val warmupDone: Boolean, val warming: Boolean)

    /**
     * Feed one driver warm-up phase into the pre-soak warm-up latch.
     *
     * Leaving warm-up is an EVENT that is latched once, never re-derived from the live phase: a
     * healthy sensor reconnects for its whole life, and reading CONNECTING / RECONNECTING as "still
     * warming up" would keep the slot in WARMUP for ever, so the promote affordance would never
     * appear. Same reasoning, same shape as `DexcomOnePlusStaging.applyWarmupPhase`.
     */
    fun applyWarmupPhase(warmupDoneBefore: Boolean, readyPhase: Boolean): StagingWarmupDecision

    /**
     * Should this pre-soak sample be added to the curve?
     *
     * A private, per-slot repeat guard. It must NOT be `Libre3Ingest` (invariant I3): that object is
     * process-wide, its floor is keyed on `lifeCount`, and a pre-soak sensor's `lifeCount` restarts
     * at 0, so sharing it would either swallow every pre-soak reading or lower the production floor.
     *
     * @param lastLifeCount highest pre-soak life counter accepted so far, -1 when none.
     */
    fun acceptForCurve(lastLifeCount: Int, sample: Libre3GlucoseSample): Boolean =
        sample.mgdl >= Libre3Ingest.MIN_MGDL &&
            sample.mgdl <= Libre3Ingest.MAX_MGDL &&
            (lastLifeCount < 0 || sample.lifeCount > lastLifeCount)
}
```

### 7.3 The curve buffer

```kotlin
/** One collected pre-soak reading. Never stored, never published — see invariant I1. */
data class Libre3PresoakPoint(val timestampMs: Long, val mgdl: Double)
```

Declared in `Libre3Staging.kt`, in package `app.aaps.plugins.source`, so the Compose section in
`:plugins:source` can read it with no new dependency.

On the plugin:

```kotlin
private val _stagingCurve = MutableStateFlow<List<Libre3PresoakPoint>>(emptyList())

/**
 * The pre-soak readings collected so far, newest last.
 *
 * A real, readable buffer behind a `StateFlow`, not a private `ArrayDeque` — the ONE+'s
 * `stagingBuffer` is written on every sample and read by nothing, so a dead pre-soak sensor and a
 * healthy one look identical. Capped at [Libre3Staging.CURVE_CAP].
 */
val stagingCurve: StateFlow<List<Libre3PresoakPoint>> = _stagingCurve.asStateFlow()
```

The list is replaced, not mutated, so Compose recomposes. Appending under the cap is
`(_stagingCurve.value + point).takeLast(CURVE_CAP)`; at one reading a minute this is cheap.

### 7.4 The staging watcher

```kotlin
/**
 * Watches the pre-soak driver.
 *
 * Every path here is collect-only. It holds no reference to [persistenceLayer] and it never calls
 * [Libre3Ingest] — invariants I1 and I3. After promotion this watcher is removed and the promoted
 * instance publishes through the plugin's own production watcher instead, so there is no
 * "am I promoted?" branch to get wrong.
 */
private val stagingWatcher = object : Libre3GlucoseWatcher {
    override fun onWarmup(state: Libre3WarmupState) = handleStagingWarmup(state)
    override fun onGlucose(sample: Libre3GlucoseSample) = handleStagingGlucose(sample)
    override fun onSession(up: Boolean, reason: String?) { /* log only */ }
    override fun onError(message: String, fatal: Boolean) { /* log only */ }
}
```

`handleStagingGlucose(sample)`:

1. `if (!Libre3Staging.acceptForCurve(stagingLastLifeCount, sample)) return`
2. `stagingLastLifeCount = sample.lifeCount`
3. append to `_stagingCurve` (capped)
4. `markStagingWarmupDone()` — a collected reading is proof warm-up is over, even if no READY phase
   was ever published. The driver only emits a sample past `clock.isWarmingUp`, so this is exact on
   Libre 3, not a heuristic as on the ONE+.
5. `stagingValidReadingCount++`
6. `stagingStore.saveSlotProgress(present = true, validReadingCount = stagingValidReadingCount)`
7. refresh `_stagingLifecycle`, `_stagingState`, `_stagingEvidence`
8. log at debug: value, count, and the words "not published"

`handleStagingWarmup(state)`:

1. `val decision = Libre3Staging.applyWarmupPhase(stagingWarmupDone, state.phase == READY)`
2. latch or store `warming`
3. `_stagingWarmup.value = Libre3WarmupMapper.toCgmWarmupStatus(state)` — the existing mapper,
   reused unchanged
4. `stagingWarmupNotification.update(state)` — see Lot 5
5. refresh `_stagingState`

No branch in either handler writes to the database.

---

## 8. State machine of the STAGING slot

Mapped onto the existing `app.aaps.core.interfaces.source.StagingState`. Nothing in
`:core:interfaces` changes.

```
                 beginStaging()                first sample OR READY phase
   ABSENT ─────────────────────────► WARMUP ─────────────────────────────► SETTLING
      ▲                                 │                                      │
      │                                 │                            count >= 5 readings
      │  cancelStaging() / promote()    │                                      ▼
      └─────────────────────────────────┴──────────────────────────────────  READY
```

| `StagingState` | Condition | Where the numbers come from |
|---|---|---|
| `ABSENT` | `!stagingPresent` | `stagingStore.loadSlotPresent()` across restarts, in-memory otherwise. |
| `WARMUP` | `stagingPresent && !stagingWarmupDone` | `stagingWarmupDone` is a **latch**, persisted by `saveSlotWarmupDone`. It is set by the first `Libre3WarmupState.Phase.READY` **or** by the first collected sample. The countdown shown next to it is `Libre3WarmupState.remainingMs`, which the driver fills from `Libre3WarmupClock.remainingWarmupMs = max(0, warmupMinutes - lifeCount)` — so it is derived from the sensor's own minute counter, not from a phone timer, and it is correct across restarts for free. |
| `SETTLING` | `stagingPresent && stagingWarmupDone && validReadingCount < 5` | `Libre3Staging.STAGING_MIN_VALID_READINGS`. |
| `READY` | `stagingPresent && stagingWarmupDone && validReadingCount >= 5` | Same constant. |

Two points a reviewer should check:

- **`WARMUP` on Libre 3 means literally no glucose.** `Libre3CgmDriverReal.handlePlaintext` returns
  early while `clock.isWarmingUp`, so the pre-soak slot receives zero samples for the whole warm-up.
  The slot is not silent, though: the driver keeps publishing `Phase.WARMING` with a shrinking
  `remainingMs`, so the card and the notification stay honest.
- **`READY` is not a promotion gate.** `promoteStagingToProduction` rejects only `STAGING_ABSENT`
  (D1). `READY` exists because `DashboardStagingCard` only renders its Promote button in that state
  (`DashboardStagingCard.kt:58`), and offering a promote for a sensor that has produced nothing would
  be a trap. A user who must promote *before* the first reading — the production sensor failed during
  the pre-soak warm-up — uses the second Promote button in the Libre 3 Status screen's pre-soak
  section (Lot 5), which calls the same method and is not gated on `READY`.

---

## 9. Lot 4 — staging flows and the three actions

### 9.1 Files

Modify: `plugins/source/src/main/kotlin/app/aaps/plugins/source/Libre3NativePlugin.kt`

### 9.2 Replacing the empty flows

`Libre3NativePlugin.kt:148-166` currently declares four hardcoded-empty flows and a
`promoteStagingToProduction` that always returns `Rejected(STAGING_ABSENT)`. The flows keep their
declarations and their types; only their producers change. New private state, all `@Volatile`:

```kotlin
private val stagingDriver: Libre3CgmDriverReal get() = Libre3CgmDrivers.staging()
private val stagingStore by lazy { Libre3SensorStore(context, Libre3CgmDrivers.STAGING_NAMESPACE) }

@Volatile private var stagingPresent = false
@Volatile private var stagingWarming = false
@Volatile private var stagingWarmupDone = false
@Volatile private var stagingValidReadingCount = 0
@Volatile private var stagingLastLifeCount = -1
@Volatile private var stagingLastValueMgdl: Double? = null
@Volatile private var stagingLastValueAtMs: Long? = null
```

Plus the private refreshers `refreshStagingLifecycle()`, `refreshStagingState()`,
`refreshStagingEvidence()`, `refreshProductionLifecycle()`, each a one-liner over `Libre3Staging`.

`refreshProductionLifecycle()` is new behaviour for Libre 3 (`_lifecycle` is currently never
written): it reads `sensorStore.loadIdentity()` and feeds `activatedAtMs` and `wearDurationMinutes`
into `Libre3Staging.computeLifecycle(SensorSlot.PRODUCTION, …)`. It is called from `onStart`, after
each accepted production reading, and after promotion. This finally gives the dashboard the Libre 3
"early life / end of life" hint the generic frame was built for.

### 9.3 `beginStaging`

```kotlin
/**
 * Start a pre-soak on the sensor the NFC scan has just stored in the pre-soak slot.
 *
 * @return false when the request was refused because that sensor already feeds the loop, so the
 *   caller must not connect. Refusing here and not only in the UI keeps invariant I4 for every
 *   future call site.
 */
fun beginStaging(identity: Libre3SensorIdentity): Boolean
```

Steps:

1. `if (!preferences.get(Libre3BooleanKey.PresoakEnabled)) return false` — I8.
2. `if (isProductionSensor(identity.serialNumber, identity.bleAddress)) { log; return false }` — I4.
3. `stagingDriver.setContext(context)`; `stagingDriver.addWatcher(stagingWatcher)`.
4. Decide whether this is the **same** pre-soak sensor started again (an extra tap on the Start
   screen) or a different one, from `stagingStore.loadIdentity()?.serialNumber`:
   - **Different sensor**: `stagingStore.clearAll()` first — this is the only place the staging file
     is wiped, and it is the reason promotion can afford to leave it alone (§10 step 9). Then reset
     every counter, `stagingWarmupDone = false`, `stagingLastLifeCount = -1`, clear
     `_stagingCurve`.
   - **Same sensor**: keep the counters. Restoring them from
     `stagingStore.loadSlotValidReadingCount()` / `loadSlotWarmupDone()` — resetting them would send
     a settled slot back to warm-up and hide the Promote button for another hour for nothing.
5. `stagingStore.saveSlotActivatedAt(identity.activatedAtMs)` and
   `stagingStore.saveSlotProgress(present = true, validReadingCount = …)`; `saveSlotWarmupDone(…)`.
   Durability: a restart must be able to tell "a pre-soak sensor is warming" from "no pre-soak
   sensor".
6. `stagingPresent = true`; `stagingWarming = !stagingWarmupDone`.
7. `refreshSessionService()` (Lot 6).
8. Refresh the three staging flows; log `LIBRE3_PRESOAK: begin`.
9. `return true`.

Note that the NFC scan has **already** written the identity into the staging file by the time this is
called — Lot 5's veto is what protects I4 before that write, and step 2 here is the second line of
defence for callers that are not the Start screen.

### 9.4 `connectStagingSensor`

```kotlin
/** Start Bluetooth for the pre-soak slot. Never touches the production driver. */
fun connectStagingSensor(deviceAddress: String)
```

Same shape as `connectStoredSensor`, but it consults `Libre3CgmDrivers.stagingBlockedReason()`
instead of `realDriverBlockedReason()` (the pre-soak is always the Real driver, so only the pairing
tables matter) and it calls `stagingDriver.connect(deviceAddress)`.

### 9.5 `cancelStaging`

```kotlin
/** Stop and throw away the pre-soak sensor. No effect on production. */
fun cancelStaging()
```

1. `runCatching { stagingDriver.removeWatcher(stagingWatcher) }` — first, so nothing can arrive
   after this point.
2. `runCatching { stagingDriver.disconnect() }`, `runCatching { stagingDriver.shutdown() }`.
3. `runCatching { stagingStore.clearAll() }` — the pre-soak identity, PIN and pairing key go with
   the pre-soak. The production file is not opened.
4. Reset every in-memory counter, clear `_stagingCurve`, set the four flows to null / `ABSENT`.
5. `stagingWarmupNotification.cancel()`.
6. `refreshSessionService()`.
7. Log `LIBRE3_PRESOAK: cancelled`.

The physical sensor keeps running on the arm. The screen must say so, exactly as
`libre3_forget_sensor_explain` already does for production.

### 9.6 `resumeStagingSessionIfStored`

Called from `onStart`, after the production resume. Without it, a pre-soak that survives an app
restart is lost: the plugin would show `ABSENT` while the staging file still holds a valid identity
and pairing key, and the sensor would soak invisibly and could never be promoted.

1. `if (!preferences.get(Libre3BooleanKey.PresoakEnabled)) return false`
2. `if (!stagingStore.loadSlotPresent()) return false`
3. `stagingDriver.setContext(context)`; `stagingDriver.addWatcher(stagingWatcher)`
4. `val identity = stagingStore.loadIdentity()` — null means the slot flag outlived its sensor:
   remove the watcher again, set the slot to `ABSENT` **without** clearing the file, log a warning
   telling the user to re-run the pre-soak start. (Clearing here would silently destroy a pairing
   key on a transient read problem.)
5. Restore `stagingValidReadingCount`, `stagingWarmupDone`, `stagingLastLifeCount = -1` (the curve is
   in memory only and starts empty after a restart — say so in the UI's empty state).
6. `stagingDriver.connect(identity.bleAddress)`; refresh the flows; log.

### 9.7 `onStop`

Add, before the existing production teardown:

```kotlin
runCatching { stagingDriver.removeWatcher(stagingWatcher) }
runCatching { stagingDriver.shutdown() }
stagingWarmupNotification.cancel()
```

The staging **file** is deliberately not cleared: the plugin being disabled must not throw away a
12 hour pre-soak.

### 9.8 `watchRadioLease`

Both slots must obey the pump-setup radio lease. Add
`runCatching { stagingDriver.setRadioBackOff(lentOut) }` next to the existing production call.
Leaving the pre-soak on the air would give back most of what production just gave up — the ONE+
already learned this (`DexcomOnePlusPlugin.watchRadioLease`).

---

## 10. Promotion sequence

`override suspend fun promoteStagingToProduction(allowEarly: Boolean): PromotionResult`

`allowEarly` is accepted and ignored (D2). Its KDoc must say so in one sentence, so nobody later
"fixes" it into a gate.

The order below is chosen so that **every step that can fail comes before any step that cannot be
undone**.

| # | Step | On failure |
|---|---|---|
| 1 | `if (!stagingPresent) return Rejected(STAGING_ABSENT)` | Nothing changed. |
| 2 | `val staged = stagingStore.loadIdentity() ?: return Rejected(STAGING_ABSENT)`. `loadIdentity` returns non-null only when serial, MAC and PIN are all present, i.e. the NFC write really landed. | Nothing changed. |
| 3 | `val keys = stagingStore.loadSessionKeys()`. A null `phase5RawKey` is **not** a rejection — a sensor adopted mid-life can hold only `kEnc` / `ivEnc`. Log it at info. | — |
| 4 | `if (!sensorStore.adopt(staged, keys)) { log ERROR; return Rejected(STAGING_ABSENT) }`. One `commit()`, so a false means **nothing** was written. This is the last reversible step. | Nothing changed. The production sensor keeps running untouched. See §10.1 for why the reject reason is `STAGING_ABSENT`. |
| 5 | `preferences.put(Libre3BooleanKey.UseRealSkeleton, true)` — the promoted sensor is a real sensor; without this a restart routes production to the Stub. Idempotent. | Wrapped in `runCatching`; a failure is logged and the promotion continues, because the in-session swap below still works and the user would otherwise be left with two half-retired sensors. |
| 6 | Retire the outgoing production sensor: `runCatching { driver.removeWatcher(this) }` **first** (this is what closes I1 for the outgoing sensor), then `runCatching { driver.disconnect() }`, then `runCatching { driver.shutdown() }`. | All swallowed and logged. The watcher removal is the only step that matters for safety and it is the one least able to fail. |
| 7 | `Libre3Ingest.reset()`. **After** step 6, so no in-flight production sample can re-raise the dedup floor between the reset and the flip. The persisted floor was already removed by `adopt` in step 4 — without that, a restart would seed the old sensor's high `last_life_count` and refuse every reading of the promoted sensor for its whole life. | Cannot fail. |
| 8 | `val retired = Libre3CgmDrivers.promoteStagingInstance()` — rebinds the promoted instance to the production preferences file, makes it the production instance, frees the pre-soak pointer. | Returns null only when there was no pre-soak instance, which step 1 already excluded. Logged and treated as a hard error. |
| 9 | Move the watchers on the promoted instance: `promoted.addWatcher(this)` **then** `runCatching { promoted.removeWatcher(stagingWatcher) }`. The link is never dropped, so there is no glucose gap. Order matters: `watchers` is a `CopyOnWriteArrayList`, so removing first would leave a window in which a `forEach` sees an empty list and one reading is lost; adding first leaves a window in which both fire, so one reading is both buffered and inserted — buffering a promoted reading is harmless, losing one is not. | Both calls are idempotent. |
| 10 | `stagingStore.clearAll()`. The pre-soak file's copy of the PIN and the pairing key must not survive the promotion. Consequence, verified: the running read loop captured the staging store as a local, so its next `extendWearIfStillAlive` reads a stored wear of 0 and returns 0, `wearMinutes.takeIf { it > 0 }` becomes null, and `Libre3WarmupClock.isExpired` is then false — readings keep flowing, only the wear-extension safety net is idle until the next session, which opens against the production file and its correct wear minutes. | `runCatching`; a failure leaves a stale secret in an app-private file and is logged at warn. |
| 11 | `logSensorChangeOnce(staged.activatedAtMs)` — **after** step 4, so `sensorStore.loadIdentity()?.serialNumber` already returns the new serial while `loadSensorChangeLoggedSerial()` returns null (step 4 removed it), which is exactly what `Libre3SensorChange.serialToLog` needs to write the event. The event is dated on the **pre-soak's real activation time**, so the dashboard sensor age and the calibration plugin's session boundary are both correct from the first minute. When `activatedAtMs <= 0` (a sensor adopted mid-life that reported no epoch) nothing is written here and the existing self-healing path in `onGlucose` writes it from the first reading via `Libre3WarmupClock.activationTimeFromReading`. | The insert is on `ioScope` and already tolerates failure: the mark is written only after the event reaches the database, so the next reading retries. |
| 12 | Reset the pre-soak in-memory state: counters to 0, `stagingLastLifeCount = -1`, `stagingPresent = false`, clear `_stagingCurve`, set `_stagingWarmup` / `_stagingLifecycle` / `_stagingEvidence` to null and `_stagingState` to `ABSENT`. `stagingWarmupNotification.cancel()`. | Cannot fail. |
| 13 | `refreshProductionLifecycle()`; `refreshSessionService()`; `runCatching { retired?.let { … } }` log line naming both instances. | — |
| 14 | `return PromotionResult.Ok` | — |

Log one line at info before step 4 and one after step 14, both with the serial, the soak duration
(`now - staged.activatedAtMs`) and the reading count, so a support package can reconstruct the swap.

### 10.1 Why the store-write failure returns `STAGING_ABSENT`

There is no `PromotionRejectReason` for "the phone could not write". Adding one would change
`:core:interfaces` — which this plan is not allowed to do — and would break the exhaustive `when` in
`OverviewViewModel.promotionRejectReasonRes` until a new string is added in `:plugins:main`. A failed
`commit()` on an app-private preferences file means the phone cannot write at all, which is a
much larger problem than this feature; the honest handling is to change nothing, log at ERROR, and
report the promotion as refused. The Libre 3 Status screen's own Promote button shows the ERROR log
line, so the user is not left guessing.

---

## 11. Lot 5 — Start screen, notifications, cross-slot guard, curve

### 11.1 Slot selector in `Libre3StartActivity`

Modify: `plugins/source/src/main/kotlin/app/aaps/plugins/source/activities/Libre3StartActivity.kt`
Modify: `plugins/libre3/src/main/kotlin/app/aaps/plugins/libre3/nfc/Libre3NfcSession.kt`
Modify: `plugins/libre3/src/main/kotlin/app/aaps/plugins/libre3/nfc/Libre3NfcFailure.kt`

The activity currently builds one store and one reader in `onCreate` (`Libre3StartActivity.kt:76-95`).
The store must follow the slot, but the NFC reader must not be rebuilt on every toggle: it owns an
executor and registers reader mode on the activity, and only one may be enabled at a time.

`Libre3NfcSession` therefore takes a **store supplier**, with a secondary constructor so nothing
existing changes:

```kotlin
class Libre3NfcSession(
    private val store: () -> Libre3IdentityStore,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    /**
     * Asked before the activation command is sent, with the serial just read from the patch info.
     * Returning true stops the scan there — the sensor is untouched and no PIN is written.
     */
    private val veto: (serialNumber: String) -> Boolean = { false },
) {
    constructor(store: Libre3IdentityStore, nowMs: () -> Long = { System.currentTimeMillis() }) :
        this({ store }, nowMs)
}
```

Inside `scan`, `store.receiverId()` becomes `store().receiverId()` and
`store.saveIdentityAndWait(identity)` becomes `store().saveIdentityAndWait(identity)`. The veto is
checked immediately after `Libre3NfcCommands.parsePatchInfo(...)` and **before**
`Libre3NfcCommands.activationFrame(...)`:

```kotlin
if (veto(patchInfo.serialNumber)) {
    throw Libre3NfcException(
        "this sensor is already held by the other slot",
        Libre3NfcFailure.SAME_SENSOR_OTHER_SLOT,
    )
}
```

**This placement is the whole point of I4.** By the time `onSensorScanned` fires, the activation
command has already been sent and the PIN has already been replaced; a check there is too late. New
enum constant `Libre3NfcFailure.SAME_SENSOR_OTHER_SLOT`, mapped in `:plugins:source` to a new string
`libre3_nfc_same_sensor_other_slot`.

Activity changes:

- `private var slot by mutableStateOf(SensorSlot.PRODUCTION)` hoisted into the activity, so the
  supplier can read it.
- The reader is built once with
  `Libre3NfcSession({ Libre3SensorStore(this, Libre3CgmDrivers.storeNamespace(slot)) }, veto = ::sensorHeldByOtherSlot)`.
- `sensorHeldByOtherSlot(serial)` calls `plugin.isProductionSensor(serial, mac = null)` when the
  STAGING slot is selected and `plugin.isStagingSensor(serial, mac = null)` when PRODUCTION is
  selected.
- A `SingleChoiceSegmentedButtonRow` with two `SegmentedButton`s, exactly as
  `DexcomOnePlusStartActivity.kt:667-683`, rendered **only** when
  `preferences.get(Libre3BooleanKey.PresoakEnabled)` is true. Labels
  `libre3_slot_production_short` / `libre3_slot_staging_short`.
- A `CgmHelpCard` visible only in the STAGING slot carrying the wear-time warning of §3
  (`libre3_presoak_wear_cost`).
- `onSensorScanned` branches:

```kotlin
when (slot) {
    SensorSlot.PRODUCTION -> {
        // unchanged behaviour
        plugin.onSensorChanged()
        plugin.syncDriverFromPrefs()
        if (result.readyForBle) plugin.connectStoredSensor(result.identity.bleAddress)
    }
    SensorSlot.STAGING    -> {
        // NEVER onSensorChanged() here: it resets the process-wide Libre3Ingest and would make the
        // production sensor's next reading look new — invariant I3.
        if (!plugin.beginStaging(result.identity)) errorText = stagingIsProduction
        else if (result.readyForBle) plugin.connectStagingSensor(result.identity.bleAddress)
    }
}
```

The four-step `CgmStepper` labels stay the same; only the last one changes text in the STAGING slot
("Pre-soak" instead of "Warm-up").

### 11.2 Cross-slot guard, second line

Create: `plugins/libre3/src/main/kotlin/app/aaps/plugins/libre3/session/Libre3MacArbiter.kt`

A direct port of `OnePlusMacArbiter` (same `claim` / `release` / `ownerOf` / `reset` API, same
process-wide map, same "a slot may re-claim what it already holds" rule). The reason is the same and
it is not covered by the UI checks: `onStart` resumes **both** slots with no UI involved, so an
install whose two files already hold one sensor would reproduce the collision on every launch.

Wiring in `Libre3CgmDriverReal`:

- `openSession` claims `Libre3MacArbiter.claim(identity.bleAddress, slotName)` **before** the scan;
  a refusal publishes `Phase.FAILED` with a clear message and does **not** schedule a retry.
- `stopSession` and `shutdown` call `Libre3MacArbiter.release(slotName)`.

Plugin-side helpers used by the UI:

```kotlin
/** Whether that sensor is the one the pre-soak slot holds — serial or MAC is enough (I4). */
fun isStagingSensor(serial: String?, mac: String?): Boolean

/** Whether that sensor is the one currently feeding the loop. */
fun isProductionSensor(serial: String?, mac: String?): Boolean
```

Both delegate to `Libre3Staging.isSameSensor` against the matching store's `loadIdentity()`.

### 11.3 Per-slot warm-up notification

Modify: `plugins/source/src/main/kotlin/app/aaps/plugins/source/Libre3WarmupNotification.kt`

```kotlin
class Libre3WarmupNotification(
    private val context: Context,
    private val slot: SensorSlot = SensorSlot.PRODUCTION,
)
```

- Notification id: `4471` for PRODUCTION (**unchanged**, so an app update does not orphan a live
  notification) and `4472` for STAGING.
- Channel: the **same** `libre3_sensor_status` for both, so the user has one switch.
- Title: `libre3_notif_title` for production, a new `libre3_notif_title_presoak` for staging, so a
  user with two notifications can tell which sensor is which.
- Content intent: `Libre3WarmupActivity` for production (unchanged); `Libre3StatusActivity` for
  staging, because the pre-soak's detail view — curve, evidence, Promote — lives there. The
  `PendingIntent` request code already follows the notification id, so it separates itself.

The plugin holds two instances: the existing `warmupNotification` and a new
`stagingWarmupNotification by lazy { Libre3WarmupNotification(context, SensorSlot.STAGING) }`.

### 11.4 Pre-soak section in `Libre3StatusActivity`

Modify: `plugins/source/src/main/kotlin/app/aaps/plugins/source/activities/Libre3StatusActivity.kt`
Create: `plugins/source/src/main/kotlin/app/aaps/plugins/source/compose/Libre3PresoakCurve.kt`

A new `item(key = "presoak")` in the existing `CgmLazyColumn`, rendered only when the preference is
on and `stagingState != StagingState.ABSENT`. Inside one `CgmCard`:

1. `CgmCardHeader` with a `CgmStateChip` showing the slot state.
2. During `WARMUP`: the existing `CgmWarmupRing` plus `Libre3WarmupCountdown`, reused unchanged.
3. Evidence rows, each a `CgmKeyValueRow` fed by its own format-string resource — never built by
   concatenation:
   - `libre3_presoak_soak_time` — how long the sensor has been on, from `stagingLifecycle.ageMs`.
   - `libre3_presoak_reading_count` — valid readings collected.
   - `libre3_presoak_last_value` — last value with its unit and its time.
4. `Libre3PresoakCurve(points = curve, nowMs = …)`.
5. A `Button` "Promote this sensor" behind a confirmation `AlertDialog`, calling
   `plugin.promoteStagingToProduction()` from a `rememberCoroutineScope`. Not gated on `READY`
   (§8). The dialog must state that the current sensor stops feeding the loop immediately.
6. A `TextButton` "Cancel pre-soak", also confirmed, calling `plugin.cancelStaging()`.

`Libre3PresoakCurve`:

```kotlin
@Composable
fun Libre3PresoakCurve(
    points: List<Libre3PresoakPoint>,
    nowMs: Long,
    modifier: Modifier = Modifier,
)
```

- Drawn with `androidx.compose.foundation.Canvas`. **No chart library**, so no new dependency —
  `:plugins:source` has Compose but no charting artifact, and adding one is out of scope.
- Colours from `MaterialTheme.colorScheme` only (`primary` for the line, `outlineVariant` for the
  grid, `onSurfaceVariant` for the labels). Sizes from `AapsSpacing`. **No hardcoded dp, no
  hardcoded colours, and no Android attrs** (`rh.gac` must not appear).
- Y range clamped to the data with a sensible floor and ceiling so a flat trace is still readable.
- Empty state: a centred `Text(stringResource(R.string.libre3_presoak_curve_empty))`, never a blank
  box. Its wording must cover the honest case that the buffer is in memory only and starts empty
  after an app restart.
- All text via `stringResource()`. `ResourceHelper` must not be used in this file.

---

## 12. Lot 6 — foreground service

### 12.1 New Libre 3 service

Create: `plugins/source/src/main/kotlin/app/aaps/plugins/source/Libre3SessionService.kt`
Modify: `plugins/source/src/main/AndroidManifest.xml`

A direct copy of `DexcomOnePlusSessionService`, with:

- Channel id `LIBRE3_STATUS`, reusing the existing channel name and description strings.
- Notification id and request code `4473` (distinct from `4471` / `4472`).
- Content intent to `Libre3StatusActivity`.
- `START_NOT_STICKY` and the same `ServiceCompat.startForeground(…,
  FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)` on Android 14+, with the same `runCatching` around
  `start` / `stop`.

Manifest entry next to the ONE+ one:

```xml
<service
    android:name=".Libre3SessionService"
    android:exported="false"
    android:foregroundServiceType="connectedDevice" />
```

`plugins/libre3/src/main/AndroidManifest.xml` declares no `<service>` today and stays that way — the
service lives in `:plugins:source` beside the plugin that starts it, exactly like the ONE+ one.

### 12.2 Started for either slot

Libre 3 has no `OemDeviceProfile`, so there is no `useForegroundService` flag to consult. One helper
on the plugin, and it is the **only** place that decides:

```kotlin
/**
 * Keep the connectedDevice service alive while EITHER slot wants a Bluetooth session, and give the
 * privilege back when neither does.
 */
private fun refreshSessionService() {
    if (!preferences.get(Libre3BooleanKey.KeepSessionAlive)) {
        Libre3SessionService.stop(context.applicationContext)
        return
    }
    val wanted = sensorStore.isReadyForBle() ||
        (preferences.get(Libre3BooleanKey.PresoakEnabled) && stagingStore.isReadyForBle())
    if (wanted) Libre3SessionService.start(context.applicationContext)
    else Libre3SessionService.stop(context.applicationContext)
}
```

Call sites: `onStart`, `onSensorChanged`, `beginStaging`, `cancelStaging`,
`promoteStagingToProduction`, and the Status screen's "forget sensor" action. `onStop` always calls
`Libre3SessionService.stop(...)` after both slots are torn down.

### 12.3 The ONE+ fixes

Modify: `plugins/source/src/main/kotlin/app/aaps/plugins/source/DexcomOnePlusPlugin.kt`

Reading the code, the brief's second point needs a correction, and the spec must be honest about it:

- **Confirmed defect 1 — `beginStaging` never starts the service.** `DexcomOnePlusPlugin.beginStaging`
  (around line 569) sets the driver up and connects, but unlike `onSensorSessionStarted` (line 423)
  it never calls `DexcomOnePlusSessionService.start`. A pre-soak started on a phone where no
  production sensor was stored — or where the service had already been stopped — runs with no
  `connectedDevice` foreground service, which is exactly the standby-drop condition the service
  exists to prevent. **Fix**: add the same two lines at the top of `beginStaging`, *before* the
  `isProductionSensor` early return, mirroring `onSensorSessionStarted`.

- **Confirmed defect 2 — `onStart` ignores a stored staging session.** Line 256 reads
  `if (profileWantsForegroundService() && sensorStore.load() != null)`. An install whose only stored
  sensor is a pre-soak gets no service on launch. **Fix**:
  `… && (sensorStore.load() != null || stagingStore.load() != null)`.

- **Not a defect — `onStop`.** The brief expected `onStop` to stop the service without checking for a
  live staging session. It does stop it unconditionally, but two lines above it already removes the
  staging watcher and shuts the staging driver down, so there is no live staging session left to
  protect. Leaving it as is, is correct. **No change.**

- **Related, worth doing while here** — `cancelStaging` should call the same re-evaluation, so
  cancelling the only pre-soak on a phone with no production sensor gives the privilege back instead
  of leaving a permanent notification.

Everything else in the ONE+ (the flag-based promotion of §4.1) stays as it is. Reworking it is out of
scope for this plan and should be its own change.

---

## 13. The preferences

Modify: `plugins/source/src/main/kotlin/app/aaps/plugins/source/keys/Libre3BooleanKey.kt`

Two new constants, both following the existing `UseRealSkeleton` shape (engineering-mode only, not
exportable, default **false**):

```kotlin
/**
 * Turns the Libre 3 pre-soak on: a second sensor may be started in the STAGING slot while the
 * current one keeps feeding the loop.
 *
 * Off by default. With it off there is no second driver instance, no second preferences file, no
 * slot selector on the Start screen, and the plugin behaves exactly as it did before — invariant I8.
 * A pre-soak spends real sensor wear time, because a Libre 3 starts its 14-day clock at the NFC
 * activation; see docs/LIBRE3_PRESOAK_PLAN.md §3.
 */
PresoakEnabled(
    key = "libre3_presoak_enabled",
    defaultValue = false,
    titleResId = R.string.libre3_presoak_enabled,
    summaryResId = R.string.libre3_presoak_enabled_summary,
    engineeringModeOnly = true,
    exportable = false,
),

/**
 * Runs a `connectedDevice` foreground service for as long as a Libre 3 session is wanted.
 *
 * Separate from [PresoakEnabled] on purpose: it also helps the ordinary single-sensor case, where
 * an aggressive OEM tears a GATT link down in standby. A pre-soak wants it on, because two links
 * have twice as much to lose, but the pre-soak must never switch it on by itself.
 */
KeepSessionAlive(
    key = "libre3_keep_session_alive",
    defaultValue = false,
    titleResId = R.string.libre3_keep_session_alive,
    summaryResId = R.string.libre3_keep_session_alive_summary,
    engineeringModeOnly = true,
    exportable = false,
),
```

Both are added to the `items` list of `Libre3NativePlugin.getPreferenceScreenContent()`.

---

## 14. BLE scan pacing (risk (c), designed here)

Every `Libre3GattClientAndroid.connect` runs a 20 s `SCAN_MODE_LOW_LATENCY` scan
(`SCAN_TIMEOUT_MS = 20_000L`). Android's undocumented quota is **5 `startScan` calls per 30 s per
app**, and once it trips, `startScan` still succeeds but delivers no results — so both slots go dark
and the symptom looks exactly like "sensor out of range". The ONE+ has already hit this on some OEMs.

With two slots on `Libre3ReconnectPolicy.FIRST_RETRY_MS = 3_000L`, a bad radio moment can produce
roughly twenty scan starts in thirty seconds: four times over quota.

Create: `plugins/libre3/src/main/kotlin/app/aaps/plugins/libre3/reconnect/Libre3ScanBudget.kt`
Modify: `plugins/libre3/src/main/kotlin/app/aaps/plugins/libre3/reconnect/Libre3ReconnectPolicy.kt`
Modify: `plugins/libre3/src/main/kotlin/app/aaps/plugins/libre3/Libre3CgmDriverReal.kt`

```kotlin
/**
 * Process-wide budget for BLE scan starts, shared by both slots.
 *
 * Android allows about five `startScan` calls per thirty seconds per app, and a throttled scan
 * fails silently: it returns success and never reports a device. Four is used, not five, to leave
 * one start for the rest of AAPS.
 */
object Libre3ScanBudget {

    const val MAX_SCAN_STARTS = 4
    const val WINDOW_MS = 30_000L

    /** @return true when a scan may start now; it then counts against the window. */
    fun tryAcquire(nowMs: Long): Boolean

    /** How long to wait before a start would be allowed, 0 when one is allowed now. */
    fun waitMsUntilNextStart(nowMs: Long): Long

    /** Test hook: forget the window. */
    fun reset()
}
```

```kotlin
/**
 * @param slot which slot is retrying. The pre-soak slot is deliberately slower: it has hours to
 *   succeed and must never crowd the sensor that is feeding the loop out of the scan budget.
 */
fun nextDelayMs(attempt: Int, slot: SensorSlot = SensorSlot.PRODUCTION): Long

/** The pre-soak slot never retries faster than this. */
const val STAGING_MIN_RETRY_MS = 20_000L

/** Added to the pre-soak slot's wait so the two slots do not knock at the same instant. */
const val STAGING_RETRY_OFFSET_MS = 7_000L
```

For `SensorSlot.STAGING` the result is
`(base + STAGING_RETRY_OFFSET_MS).coerceAtLeast(STAGING_MIN_RETRY_MS)`. `SensorSlot.PRODUCTION`
returns exactly what it returns today, so production behaviour is unchanged.

In `Libre3CgmDriverReal.openSession`, before the client is built:

```kotlin
if (!Libre3ScanBudget.tryAcquire(System.currentTimeMillis())) {
    // A throttled scan reports success and finds nothing, so waiting is the only way to be heard.
    scheduleRetryAfter(generation, Libre3ScanBudget.waitMsUntilNextStart(System.currentTimeMillis()))
    return
}
```

`scheduleRetry` gains an explicit-delay sibling so the budget wait is used verbatim instead of the
ladder's. Both remain pure decisions plus one `Thread.sleep` on the slot's own executor, so nothing
here can block the other slot.

---

## 15. Test plan

All new pure logic is unit-tested. Both `:plugins:libre3` and `:plugins:source` already have JUnit 5,
Mockito and Truth through the `test-module-dependencies` convention plugin; `:plugins:source` also
has Robolectric and the JUnit vintage engine. **No test dependency is added anywhere.**

### 15.1 `Libre3StagingTest` — `:plugins:source`, mirrors `DexcomOnePlusStagingTest`

- `computeLifecycle` returns null for an unknown activation time (0).
- A sensor one hour old is early-life and not end-of-life; `ageMs` is one hour.
- A sensor thirteen hours old is no longer early-life.
- A sensor within twelve hours of `activatedAt + wear + grace` is end-of-life.
- With `wearMinutes = null` the fallback is fourteen days; with `wearMinutes = 15 * 24 * 60`
  (Libre 3 Plus) the expiry moves out by one day.
- `computeStagingState`: absent when `present = false`; `WARMUP` while warming; `SETTLING` when
  warm-up is done and the count is below five; `READY` at exactly five; still `READY` above five.
- `computeStagingState` has **no** time argument — a regression test that a soak gate has not crept
  back in (D1).
- `applyWarmupPhase`: a latched `true` stays true through a `CONNECTING` phase; a first `READY`
  latches; a slot that never warmed up reports `warming = true` for an idle or failed radio.
- `isSameSensor`: same serial, different MAC → true. Different serial, same MAC → true. Different
  both → false. Case differences on either → true. Null or blank on either side → false (a blank
  must never match a blank).
- `acceptForCurve`: first sample accepted; the same `lifeCount` again refused; a lower `lifeCount`
  refused; a value below `Libre3Ingest.MIN_MGDL` or above `MAX_MGDL` refused.

### 15.2 `Libre3StagingIngestTest` — `:plugins:source` — **the invariant test**

Built like `Libre3NativePluginVisibilityTest`, which already constructs a real `Libre3NativePlugin`
with mocked `ResourceHelper`, `Preferences`, `Config`, `Context`, `PersistenceLayer`,
`Libre3AvailabilityProvider` and `BleRadioPriority`.

- **`a staging sample never reaches the persistence layer`** — the required test. Drive the plugin's
  staging watcher with ten `Libre3GlucoseSample`s, then assert
  `verify(persistenceLayer, never()).insertCgmSourceData(any(), any(), any(), any())`.
- `a staging sample never moves the production dedup floor` — assert
  `Libre3Ingest.lastAcceptedLifeCount()` is unchanged before and after those ten samples (I3).
- `staging samples are visible` — `stagingEvidence.value!!.validCount == 10`,
  `lastValueMgdl` and `lastValueAtEpochMs` match the tenth sample, and `stagingCurve.value` has ten
  points in order.
- `the curve is capped` — feed `CURVE_CAP + 50` samples, assert the size is exactly `CURVE_CAP` and
  the first point is the fifty-first sample.
- `after promotion the same path does publish` — the symmetric assertion, which is what proves the
  flip actually works rather than only that it is off: promote, feed one more sample through the
  production watcher, assert `insertCgmSourceData` was called exactly once.
- `promotion is refused when no staging sensor is present` — returns
  `Rejected(PromotionRejectReason.STAGING_ABSENT)` and calls nothing on `persistenceLayer`.
- `allowEarly changes nothing` — `promoteStagingToProduction(true)` and `(false)` return the same
  result from the same state (D2).

### 15.3 `Libre3SensorStoreNamespaceTest` — `:plugins:source` (Robolectric)

The store lives in `:plugins:libre3`, which has no Robolectric; `:plugins:source` has it and depends
on `:plugins:libre3`, so the test goes there rather than adding a dependency.

- `the production namespace is the original file` — write through `Libre3SensorStore(context)`, read
  the raw `libre3_sensor_store` preferences, assert the same keys.
- **`storing a staging sensor does not touch production keys`** — the I2 test. Store production
  sensor A with a pairing key, then `saveIdentityAndWait` sensor B through
  `Libre3SensorStore(context, "staging")`, then assert production still returns A's identity **and**
  A's `phase5RawKey`, `kEnc`, `ivEnc`.
- `the receiver id is shared` — a staging store's `receiverId()` equals the production store's, and
  creating the staging store first does not create a second id (D5).
- `adopt installs the staged sensor` — after `adopt`, the production store returns the staged
  identity and the staged keys, `loadLastLifeCount()` is `-1`, and
  `loadSensorChangeLoggedSerial()` is null.
- `adopt keeps null keys out` — a staged session with a null `phase5RawKey` does not write an empty
  value that would later decode to a wrong-sized key.
- `clearAll wipes only the staging file` — production untouched.

### 15.4 `Libre3ScanBudgetTest` and `Libre3ReconnectPolicySlotTest` — `:plugins:libre3`

- Four starts inside one window are allowed and the fifth is refused.
- The fifth is allowed once the window has rolled past `WINDOW_MS`.
- `waitMsUntilNextStart` is 0 while the budget has room, and is the exact remaining window
  afterwards.
- `nextDelayMs(attempt, PRODUCTION)` equals today's `nextDelayMs(attempt)` for every attempt from 1
  to `MAX_ATTEMPTS + 2` — a regression guard on production pacing.
- `nextDelayMs(attempt, STAGING)` is never below `STAGING_MIN_RETRY_MS` and is always strictly
  greater than the production value for the same attempt.

### 15.5 `Libre3SensorChange` reuse

Extend the existing `Libre3SensorChangeTest`:

- `promotion writes the event at the pre-soak activation time, not at the promotion time` — with
  `loggedSerial` = the old sensor's, `serialNumber` = the staged sensor's and `activatedAtMs` twelve
  hours in the past, `serialToLog` returns the staged serial. The plugin test then asserts
  `insertCgmSourceData` was called with `sensorInsertionTime = staged.activatedAtMs`.

### 15.6 Not unit-tested (stated so it is not mistaken for coverage)

Two concurrent GATT links, NFC activation of a second patch, the foreground service's standby
behaviour and the Compose curve rendering are device concerns. They are covered by §16, not by the
suite, and no build result may be read as evidence that they work.

---

## 16. Risks and unknowns

### (a) Unverified — will a Libre 3 tolerate NFC activation of a second patch while another runs?

The whole feature assumes yes: two patches, each bound to this phone's receiver id, each answering
its own BLE address. Nothing in the Libre 3 protocol as implemented here says otherwise — the
receiver id is per phone, not per session, and `saveIdentityAndWait` shows the upstream design
already expects one phone to hold different sensors over time.

But it has **not been tried on hardware**, and there is one specific way it could fail: if the
activation command binds the sensor to a receiver in a way the sensor treats as exclusive, activating
the second patch could invalidate the first. That would be silent — the production sensor would
simply stop answering — and it would cost the user two sensors.

**Mitigation:** the very first device test must be a pre-soak against a production sensor the user is
willing to lose, and the acceptance criterion is that production glucose keeps arriving for a full
hour after the second activation, checked in the log, before anything else is tested. The preference
default of OFF exists exactly for this.

### (b) Unverified — are two concurrent Libre 3 GATT links stable?

Each slot builds its own `Libre3GattClientAndroid` and its own single-threaded executor, so there is
no shared state at the driver level. What is shared is the phone's radio and the platform's GATT
client budget (historically about seven concurrent clients, fewer on some OEMs).

Unknowns: whether the two links interleave cleanly at the sensor's one-a-minute cadence, and whether
the connection-interval changes made by the radio lease (`setLowPower`) behave sensibly on two links
at once.

**Mitigation:** the reading loop already tolerates a dead link and reconnects, so the failure mode is
degraded rather than silent — but a pre-soak that reconnects every minute would also eat the scan
budget, which is why §14 exists. The device test must record, over at least four hours, the number of
`LINK_LOST` events per slot with the pre-soak on and compare it with the same figure from a
single-slot run.

### (c) BLE scan quota

Designed for in §14. The residual risk is that the real quota on a given OEM is tighter than four per
thirty seconds, or that other AAPS components (pump drivers) are already consuming it. The budget is
a single object with two constants, so tightening it is a one-line change.

### (d) The retired production instance

`promoteStagingInstance` drops the retired instance so the next `staging()` builds a fresh one. If a
future change reintroduces a `by lazy` there, defect (1) of §4.1 comes straight back and only shows
up on the **second** promotion — days later. The KDoc on `promoteStagingInstance` must say this, and
the review checklist should include it.

### (e) The pre-soak curve is memory-only

`stagingCurve` does not survive a process restart; the counters do. A user who restarts AAPS
mid-pre-soak sees an empty chart next to a reading count of several hundred. This is deliberate —
persisting glucose values that must never be stored would sit uncomfortably close to invariant I1 —
but the empty state must explain it, or it will be read as a broken sensor.

---

## 17. Project rules this work must follow

Restated here because they bind the implementation, not just the review.

- **Explicit imports only.** Never a fully-qualified name inline — not in type parameters, not in
  return types, not in `remember { mutableStateOf<…>() }`. Add the `import` even when the symbol is
  used once. This applies to `SensorSlot`, `StagingState`, `CgmStagingEvidence`, `ImageVector`,
  `kotlin.math.abs` and everything else touched below.
- **KDoc `[symbol]` links must resolve, or use backticks.** A link only resolves if the symbol is
  importable from that file. `Libre3NativePlugin` cannot link private members of
  `Libre3CgmDriverReal`, and `:core:interfaces` cannot link anything in `:plugins:libre3` — those
  become `` `Libre3CgmDriverReal.openSession` ``. Never
  `@Suppress("KDocUnresolvedReference")`. Never add a module dependency to make a doc link resolve.
- **No new inter-module `implementation(project(...))` dependency.** Checked: `:plugins:source`
  already depends on `:plugins:libre3`, `:core:interfaces`, `:core:ui` and Compose;
  `:plugins:libre3` already depends on `:core:interfaces` and `androidx.core`. Everything specified
  above fits inside that. In particular the curve is drawn with `androidx.compose.foundation.Canvas`
  rather than a chart library, and `Libre3PresoakPoint` is declared in `:plugins:source` so
  `:plugins:main` never needs to see it.
- **Compose:** `stringResource()`, never `ResourceHelper`, in any `@Composable`. Theme values only —
  `AapsTheme` / `MaterialTheme.colorScheme` / `AapsSpacing` — never a hardcoded dp or colour. Never
  an Android attr (`rh.gac(context, R.attr.…)`) in Compose. Use the existing `CgmScaffold`,
  `CgmCard`, `CgmCardHeader`, `CgmKeyValueRow`, `CgmStateChip`, `CgmWarmupRing` kit; do not write a
  parallel one.
- **No user-facing text built by concatenation.** Every new string is a format-string resource with
  positional placeholders, and values carry their own unit. Every new string with a placeholder, a
  unit or an ambiguous short label gets a `comment="…"` translator note explaining each placeholder
  with an example, following `libre3_start_scanned`. Plain self-explanatory sentences get no comment.
- **English strings only.** Add to `plugins/source/src/main/res/values/strings.xml`; touch no
  translation file.
- **Simple school English** in code, KDoc, log lines, commit messages and UI text. Short common
  words, short sentences, no idioms.
- **Never manipulate localized strings programmatically** — no `.replace`, no `.removeSuffix` on a
  resource string.

### 17.1 New strings (all English, in `plugins/source/src/main/res/values/strings.xml`)

`libre3_presoak_enabled`, `libre3_presoak_enabled_summary`, `libre3_keep_session_alive`,
`libre3_keep_session_alive_summary`, `libre3_slot_production_short`, `libre3_slot_staging_short`,
`libre3_presoak_wear_cost`, `libre3_presoak_heading`, `libre3_presoak_soak_time`,
`libre3_presoak_reading_count`, `libre3_presoak_last_value`, `libre3_presoak_curve_empty`,
`libre3_presoak_promote`, `libre3_presoak_promote_title`, `libre3_presoak_promote_explain`,
`libre3_presoak_promote_confirm`, `libre3_presoak_promote_cancel`, `libre3_presoak_cancel`,
`libre3_presoak_cancel_title`, `libre3_presoak_cancel_explain`, `libre3_staging_is_production`,
`libre3_nfc_same_sensor_other_slot`, `libre3_notif_title_presoak`.

---

## 18. Order of work

The lots are not independent. Landing them out of order leaves a build in which the Start screen can
destroy a running sensor's keys.

1. **Lot 1 + Lot 5's Start-screen slot wiring together.** Lot 1 alone adds the second file but does
   not make the Start screen use it, so the blocker of §5 stays open.
2. Lot 2 (second driver instance).
3. Lot 3 (collect-only path) — invariant I1 is testable from here.
4. Lot 4 (flows, begin / cancel / promote).
5. The rest of Lot 5 (notification, MAC arbiter, curve).
6. Lot 6 (foreground service, plus the two ONE+ fixes, which are independent of everything above and
   can land first if that is convenient).
7. §14 (scan pacing) must land **before** any multi-hour two-slot device test.

## 19. Definition of done

- The preference is off by default and, with it off, the plugin's behaviour is byte-for-byte the one
  it has today.
- Every test in §15 passes, including the two invariant tests (§15.2 first item, §15.3 second item).
- A device run has shown: production glucose uninterrupted through a second NFC activation; the
  pre-soak warm-up counting down on screen and in its own notification; a readable pre-soak curve;
  a promotion with no gap in the loop's glucose; and a correct sensor age plus a
  `SENSOR_CHANGE` dated on the pre-soak's activation time afterwards.
- **None of the above may be called "working" until the user has confirmed it on their own device.**
  A green build is not evidence.
