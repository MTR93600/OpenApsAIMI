package app.aaps.wear.complications

import android.content.ComponentName
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.wear.data.ComplicationDataRepository
import app.aaps.wear.events.EventWearPreferenceChange
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Asks the system to refresh the Custom watch face image complications when the picture has actually
 * changed, instead of waiting for the periodic timer.
 *
 * `UPDATE_PERIOD_SECONDS` is documented as a wish and was measured being honoured only every 1.5 to
 * 6 minutes, which is far too slow for a watch face. A data source may also **push**, and a push was
 * measured being delivered in **15 to 115 ms** - effectively immediately, and not throttled. So the
 * cadence is ours to choose; see `_docs/CWF_WFF_Prompt.md`.
 *
 * Three triggers, each matching something that genuinely changes the picture:
 *
 * - **the clock**, on a timer, refreshing only the upper half - the half that carries the time;
 * - **new data** from the phone, refreshing both halves;
 * - **a preference change**, refreshing both halves at once, because a preference can change the
 *   layout itself and the user is looking at the watch when they change one.
 *
 * Lives for the life of the process rather than of a service: a data source is bound only for as
 * long as it takes to answer one request, so nothing inside one can drive a schedule.
 */
@Singleton
class CwfComplicationUpdater @Inject constructor(
    private val context: Context,
    private val rxBus: RxBus,
    private val complicationDataRepository: ComplicationDataRepository,
    private val aapsLogger: AAPSLogger
) {

    companion object {

        /**
         * Refresh rates for the upper half, the half that carries the clock.
         *
         * Both are aligned to a multiple of the interval rather than "now plus interval": the picture
         * contains the watch face's own clock, so landing just *after* a boundary is what keeps the
         * displayed value right. Refreshing every minute at an arbitrary phase would leave the minute
         * wrong for most of each minute, and a second shown off the 0/1/2... grid looks wrong even
         * when it is only a second out.
         */
        private const val SECOND_INTERVAL_MS = 1_000L
        private const val MINUTE_INTERVAL_MS = 60_000L

        /**
         * How long a burst of triggers is allowed to coalesce into one refresh.
         *
         * Both trigger sources arrive in bursts. The repository is a DataStore flow that emits per
         * field, so one arrival from the phone produces several emissions; and one user action in the
         * settings writes several preference keys, each firing the listener. Refreshing on each of
         * them was measured producing ten renders in two seconds.
         */
        private const val COALESCE_MS = 1_000L

        /**
         * The quiet gap asked of a data change, longer than [COALESCE_MS].
         *
         * The phone writes four times per sync - status, glucose, graph and treatments - and the
         * writes land about a second apart, which is just far enough apart to clear a one second
         * gap one after another. Measured: 13 refreshes in under 3 minutes, each redrawing both
         * halves, for data that arrives roughly once a minute.
         *
         * Waiting a few seconds collapses a sync into a single refresh. The delay costs nothing
         * visible: glucose arrives every 5 minutes, so a picture that appears a few seconds later
         * is still the same picture.
         */
        private const val DATA_COALESCE_MS = 5_000L

        /**
         * How often the debounce above is checked.
         *
         * [COALESCE_MS] is a **trailing** debounce - the refresh waits for that much quiet - not a
         * tumbling window. A fixed window only rate-limits: three arrivals a second apart were
         * measured producing three full refreshes, each redrawing both halves, because each landed
         * in a window of its own. Polling has to be well below the debounce so waiting for quiet
         * does not add a visible delay of its own.
         */
        private const val COALESCE_POLL_MS = 200L

        /**
         * How recently the clock half must have been asked for before a clock tick skips itself.
         *
         * A data change redraws both halves, and the clock loop does not know it happened - so when
         * the two land in the same second the upper half was rendered and encoded twice, about
         * 380 ms of work thrown away, once per sync. Short enough that a genuine tick is never
         * dropped: ticks are a second apart and this is a fraction of that.
         */
        private const val UPPER_DEDUPE_MS = 500L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun requester(cls: Class<*>) =
        ComplicationDataSourceUpdateRequester.create(context, ComponentName(context, cls))

    private val lower by lazy { requester(CwfLowerComplication::class.java) }
    private val upper by lazy { requester(CwfUpperComplication::class.java) }
    private val whole by lazy { requester(CwfImageComplication::class.java) }
    private val aimiDial by lazy { requester(AimiDialComplication::class.java) }

    /** When the clock half was last asked for, by any path, so a tick can skip a fresh redraw. */
    private val lastUpperRequest = AtomicLong(0)

    private fun requestUpper() {
        lastUpperRequest.set(System.currentTimeMillis())
        upper.requestUpdateAll()
        aimiDial.requestUpdateAll()
        whole.requestUpdateAll()
    }

    /**
     * Refreshes everything - both halves and the whole-face variant, for whoever is using which.
     *
     * Both halves together on purpose: one data change can drive `DynData` on views in either half,
     * so redrawing only one could leave the two disagreeing - a lower half in red beside an upper
     * half in green.
     */
    private fun refreshAll(reason: String) {
        aapsLogger.debug(LTag.WEAR, "CwfComplicationUpdater: refresh all ($reason)")
        lower.requestUpdateAll()
        aimiDial.requestUpdateAll()
        requestUpper()
    }

    /**
     * Refreshes only what carries the clock. The lower half does not change with the time.
     *
     * Skips itself when the clock half was drawn a moment ago for another reason - see
     * [UPPER_DEDUPE_MS].
     */
    private fun refreshClock() {
        if (System.currentTimeMillis() - lastUpperRequest.get() < UPPER_DEDUPE_MS) return
        requestUpper()
    }

    /** A refresh waiting to be issued: why it was asked for, and how much quiet it wants first. */
    private data class Pending(val reason: String, val quietMs: Long)

    /** Set by any trigger; the coalescing loop turns a burst of them into one refresh. */
    private val pending = AtomicReference<Pending?>(null)

    /** When the most recent trigger arrived, so the loop can wait for a quiet gap after it. */
    private val lastTrigger = AtomicLong(0)

    /**
     * Refreshes as soon as the watch enters or leaves ambient.
     *
     * Without this the picture lags a whole refresh behind the mode: the last render before dozing
     * is made while still active, so its second hand is drawn and then sits frozen through ambient,
     * and the first render after waking is made from a tick scheduled earlier that still reads doze,
     * so the hand is hidden while the watch is awake. Observed on device as the second hand appearing
     * in the wrong mode both ways round.
     *
     * `onDisplayChanged` also fires for changes that are not mode switches, so the state is compared
     * before asking for anything.
     */
    private val displayListener = object : DisplayManager.DisplayListener {
        private var wasAmbient: Boolean? = null
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            val ambient = CwfBlockComplication.isAmbient(context)
            if (ambient != wasAmbient) {
                wasAmbient = ambient
                aapsLogger.debug(LTag.WEAR, "CwfComplicationUpdater: ambient=$ambient secondsShown=${CwfBlockComplication.showsSeconds()}")
                // Refreshed here and now rather than through the coalescing loop. That loop sleeps a
                // second between passes, and the process is being frozen as the watch dozes, so a
                // refresh left pending often never got issued - the picture then kept the second hand
                // it was drawn with while awake, frozen, until the next minute tick. Coalescing exists
                // to tame bursts of data and preference events; a mode change is neither.
                refreshAll("ambient changed")
                // The clock loop has already committed to an interval and is sleeping it out - up to
                // a minute in ambient - so it cannot notice the mode changed. Restart it so the new
                // rate takes effect now instead of whenever the old sleep happens to end.
                startClockLoop()
            }
        }
    }

    /**
     * Asks for a refresh once things have been quiet for [quietMs].
     *
     * When triggers of different kinds overlap the shortest wait wins: a preference change while
     * data is arriving means the user is looking at the watch, and should not wait out the longer
     * data gap.
     */
    private fun requestRefresh(reason: String, quietMs: Long) {
        pending.getAndUpdate { current ->
            if (current == null || quietMs < current.quietMs) Pending(reason, quietMs) else current
        }
        lastTrigger.set(System.currentTimeMillis())
    }

    private var clockJob: Job? = null

    /**
     * Refreshes the clock half on a grid, at a rate that depends on what is actually on screen.
     *
     * Per second only when seconds are really shown: the zip must ask for them, the user must not
     * have switched them off, and the watch must be awake. Any other case gains nothing from a faster
     * render and would only cost battery.
     *
     * Restarted rather than signalled when the mode changes, because the loop is inside a delay of up
     * to a minute and would otherwise keep the old rate until that sleep ended.
     */
    private fun startClockLoop() {
        clockJob?.cancel()
        clockJob = scope.launch {
            while (true) {
                val interval =
                    if (!CwfBlockComplication.isAmbient(context) && CwfBlockComplication.showsSeconds()) SECOND_INTERVAL_MS
                    else MINUTE_INTERVAL_MS
                delay(interval - System.currentTimeMillis() % interval)
                refreshClock()
            }
        }
    }

    fun start() {
        // A preference can change the layout itself, and the user is watching when they change one
        rxBus.toObservable(EventWearPreferenceChange::class.java)
            .subscribe({ requestRefresh("preference changed", COALESCE_MS) }, { aapsLogger.error(LTag.WEAR, "CwfComplicationUpdater: preference stream failed", it) })

        // New data from the phone, or a newly sent watch face. drop(1) skips the value the flow
        // replays on subscription, which is not a change and would refresh for nothing at startup.
        // drop(1) skips the value the flow replays on subscription, which is not a change and would
        // refresh for nothing at startup. The timestamp is dropped from the comparison because every
        // write stamps it with the current time, so two otherwise identical writes never compare
        // equal - it is only removed from the *comparison*, the render reloads the real data itself.
        scope.launch {
            complicationDataRepository.complicationData
                .map { it.copy(lastUpdateTimestamp = 0L) }
                .distinctUntilChanged()
                .drop(1)
                .collect { requestRefresh("data changed", DATA_COALESCE_MS) }
        }
        scope.launch {
            while (true) {
                delay(COALESCE_POLL_MS)
                val waiting = pending.get() ?: continue
                if (System.currentTimeMillis() - lastTrigger.get() >= waiting.quietMs)
                    pending.getAndSet(null)?.let { refreshAll(it.reason) }
            }
        }

        context.getSystemService(DisplayManager::class.java)
            ?.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))

        startClockLoop()
        aapsLogger.debug(LTag.WEAR, "CwfComplicationUpdater: started")
    }
}
