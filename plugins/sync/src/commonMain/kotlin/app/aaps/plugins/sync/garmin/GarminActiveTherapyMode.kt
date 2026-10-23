package app.aaps.plugins.sync.garmin

import app.aaps.core.data.model.TE

/**
 * Which AIMI therapy mode the watch shows as `mode` in `/sgv.json` (`FCL`, `DINNER`, `COFFEE`, ...).
 *
 * Source: `origin/dev_OAPSAIMI` `LoopHubImpl.detectActiveTherapyMode` (`82370cdaae`, `e903113af8`,
 * `60010166bb`). It follows the keyword windows of the AIMI `Therapy` class in `:plugins:aps`, but
 * as a copy, so `:plugins:sync` does not depend on `:plugins:aps`. Keep the two in step.
 *
 * Pure: no clock, no database. The caller reads the NOTE events and passes the time.
 */
internal object GarminActiveTherapyMode {

    /** Same floor as `Therapy.FCL_MIN_WINDOW_MS`: FCL notes often have duration 0. */
    const val FCL_MIN_WINDOW_MS: Long = 60 * 60_000L

    /** `CoffeeRiseMode.DEFAULT_WINDOW_MIN`: window of a coffee note without a duration. */
    const val COFFEE_DEFAULT_WINDOW_MS: Long = 90 * 60_000L

    /** `CoffeeRiseMode.MAX_WINDOW_MIN`: a coffee note never lasts longer than this. */
    const val COFFEE_MAX_WINDOW_MS: Long = 150 * 60_000L

    /**
     * Returns the short label of the active mode, or null when no mode is active.
     * The first match in priority order wins.
     */
    fun detect(allEvents: List<TE>, now: Long): String? {
        // Context intents ("AIMI_CONTEXT:<id>:{json}") are not modes the person set, see Therapy.
        val events = allEvents.filterNot { it.isAimiContextNote() }

        fun active(keyword: String, minWindowMs: Long = 0L): Boolean =
            events.any { event ->
                val note = event.note ?: return@any false
                note.contains(keyword, ignoreCase = true) &&
                    now <= (event.timestamp + maxOf(event.duration, minWindowMs))
            }

        // High carb: also accept "high carb" with a space.
        fun activeHighCarb(): Boolean =
            events.any { event ->
                val note = event.note ?: return@any false
                (note.contains("highcarb", ignoreCase = true) ||
                    note.contains("high carb", ignoreCase = true)) &&
                    now <= (event.timestamp + event.duration)
            }

        fun activeBfast(): Boolean =
            events.any { event ->
                val note = event.note ?: return@any false
                (note.contains("bfast", ignoreCase = true) ||
                    note.contains("breakfast", ignoreCase = true)) &&
                    now <= (event.timestamp + event.duration)
            }

        fun activeCoffee(): Boolean =
            events.any { event ->
                val note = event.note ?: return@any false
                // Same keywords as CoffeeRiseMode (coffee / cafe / café).
                val isCoffee = note.contains("coffee", ignoreCase = true) ||
                    note.contains("cafe", ignoreCase = true) ||
                    note.contains("café", ignoreCase = true)
                if (!isCoffee) return@any false
                // 90 min when the NOTE has no duration, never more than 150 min (CoffeeRiseMode.windowMin).
                val windowMs = when {
                    event.duration <= 0L -> COFFEE_DEFAULT_WINDOW_MS
                    else -> minOf(event.duration, COFFEE_MAX_WINDOW_MS)
                }
                now <= (event.timestamp + windowMs)
            }

        return when {
            active("sport") -> "SPORT"
            active("fcl", FCL_MIN_WINDOW_MS) -> "FCL"
            active("dinner") -> "DINNER"
            active("lunch") -> "LUNCH"
            activeBfast() -> "BFAST"
            activeHighCarb() -> "HIGHCARB"
            active("meal") -> "MEAL"
            active("fasting") -> "FASTING"
            active("lowcarb") -> "LOWCARB"
            active("anticip") -> "ANTICIP"
            // After every meal mode: in the loop a declared meal switches the coffee mode off
            // (CoffeeRiseMode), so the watch must show the meal, not COFFEE.
            activeCoffee() -> "COFFEE"
            else -> null
        }
    }
}
