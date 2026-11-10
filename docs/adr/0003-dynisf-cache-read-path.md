# ADR 0003 — Fix the dynamic ISF cache read path

**Status:** accepted (partial: decisions 1, 2, 5)
**Depends on:** [0001](0001-replay-harness.md)
**Implemented before:** [0002](0002-sensitivity-three-levels.md)
**Behaviour change:** yes

## Context

ADR 0002 explains *where* the sensitivity value is stored wrongly. This ADR explains *why it jumps*.

> **Ordering note.** This ADR ships **before** 0002, although 0002 has the lower number. Fixing the
> read path is the narrower change and removes most of the instability on its own — the ×2 to ×11
> jumps are source transitions, not physiology. Rewiring the fifteen `profile.sens` read sites can
> follow once the value they read is stable.

`OpenAPSAIMIPlugin.getIsfMgdl` (line 551) never computes anything. It returns the cache and starts
the real computation in the background:

```kotlin
override fun getIsfMgdl(profile: Profile, caller: String): Double? {
    val multiplier = (profile as? ProfileSealed.EPS)?.value?.originalPercentage?.div(100.0)
        ?: return null                                          // (A) silent static fallback
    val cached = synchronized(dynIsfCacheLock) {
        if (dynIsfCache.size() == 0) null                       // (B) silent static fallback
        else dynIsfCache.valueAt(dynIsfCache.size() - 1)        // (C) newest key ever, no freshness test
    }
    aimiPluginIoScope.launch { runCatching { calculateVariableIsf(start) } }  // (D) async, result used next tick
    return cached?.let { it * multiplier }
}
```

Four separate problems:

- **(A) and (B)** — returning `null` makes `ProfileSealed.getIsfMgdl` fall back to
  `getProfileIsfMgdl()`, a completely different quantity. The switch is silent and invisible in the
  logs.
- **(C)** — `dynIsfCache` is a `LongSparseArray<Double>` (line 633) and its key is **not** a
  timestamp. The warm-up loop builds it as:

  ```kotlin
  val key = timestamp - timestamp % T.mins(30).msecs() + glucose.toLong()
  ```

  That is `bucketStart + glucose`, where `bucketStart` is the timestamp floored to 30 minutes.
  `LongSparseArray` keeps keys sorted, so `valueAt(size() - 1)` returns **the entry with the highest
  glucose of the newest 30-minute bucket** — not the most recent one.

  The consequence is asymmetric. While BG rises, each new reading has a higher glucose, so the
  returned entry happens to be the latest. While BG falls, the returned entry stays pinned to the
  ISF computed at the **peak** of the bucket, for up to thirty minutes. There is no TTL and no
  staleness check either way.
- **(D)** — the value used by this tick was computed for a previous tick, under a different BG and
  a different delta. Whether it is one tick old or ten depends on whether the coroutine finished.

And line 824-826 wipes the cache wholesale:

```kotlin
if (dynIsfCache.size > 1000) dynIsfCache.clear()
```

After the wipe, `size() == 0` → path (B) → the next tick silently uses the static profile ISF.

## Measured effect

This is the mechanism behind the numbers in ADR 0002: the ×2 to ×11 jumps between consecutive
5-minute ticks are transitions between "stale dynamic value", "fresh dynamic value" and "static
profile fallback", arbitrated by a race with a background coroutine.

It also explains the 2026-06-22 plateau: a stale value of 9 mg/dL/U held across a 50-minute rise
because nothing forced a refresh, and nothing flagged the value as old.

## Decision

1. **Key the read on freshness, not on sort order.** Look up the cache entry for the current tick
   with a tolerance of one CGM interval. Do not use `valueAt(size() - 1)`: with a
   `bucketStart + glucose` key that selects by glucose, not by recency. Consider splitting the key
   so time and glucose are not summed into one orderable scalar.
2. **Make the fallback explicit and observable.** When no fresh value exists, return the static
   profile ISF *and record which source was used* in the tick export
   (`isf_source = DYNAMIC_FRESH | DYNAMIC_STALE | PROFILE_FALLBACK`).
3. **Never silently mix sources across consecutive ticks.** If the source changes, rate-limit the
   transition the same way `aimi_isf_fusion_max_change_per_tick` already limits fusion moves.
4. **Bound the staleness.** A dynamic value older than a configurable window is treated as absent,
   not as valid.
5. **Replace the wholesale `clear()`** with eviction of entries older than the window, so the cache
   never empties in normal operation.

Computing synchronously on the APS path is out of scope here — the async design exists to keep the
UI thread free. The fix is to make staleness visible and bounded, not to remove the cache.

## What was implemented, and what was deliberately held back

Decisions **1**, **2** and **5** are implemented. The store is now `DynIsfCache`, keyed on time
alone, so the newest entry is really the most recent one; the sample carries its own write time, so
`isf_age_ms` is exact; and the wholesale `clear()` is replaced by eviction of anything older than
24 hours, so the store never empties. Two new fields, `isf_calc_path` and `isf_cache_size`, make it
visible whether the cache is being written at all.

Decision **4** — treat a value older than the window as absent — is **postponed on purpose**.

A 24-hour support package of 1412 cycles showed the store was almost never written: the loop took
the database short cut on nearly every cycle, so `isf_source` was `DYNAMIC_STALE` on **93.9 %** of
them, median age **45.7 min**, p95 **128.8 min**, maximum **241.9 min**. Applying decision 4 on its
own, against a cache in that state, would have turned roughly 94 % of the cycles into a static
profile fallback. The profile ISF is lower than the dynamic value here, and a lower ISF means the
loop assumes insulin acts less strongly, so it would have commanded **more** insulin — the opposite
of what this ADR is for.

The order therefore is: first make the cache actually be written, then bound the staleness.
Decision 4 can be applied once a support package shows `isf_age_ms` with a p95 **below 5 minutes**.

## Acceptance criteria

- On every corpus package replay, `isf_source` is exported on every tick.
- `PROFILE_FALLBACK` occurrences are counted; a day with more than a handful is a defect, not a
  normal state.
- No consecutive-tick change in command ISF exceeds the configured per-tick limit.
- The 2026-08-03 package (95.4 % time in range) shows no change in its decision stream beyond the
  ISF source annotation, or the delta is explained.

## Consequences

- Combined with ADR 0002, this is the root-cause fix. Everything in ADR 0004–0006 is an amplifier.
- Expect the dynamic ISF to become slower and less spiky. Some of the loop's current
  responsiveness is noise being tracked as signal.
- The new `isf_source` field is the first observable that would have made this defect visible from
  a support package. Until it exists, this class of bug is invisible to support analysis.
