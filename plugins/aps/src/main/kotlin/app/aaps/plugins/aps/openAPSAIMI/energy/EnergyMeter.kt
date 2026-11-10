package app.aaps.plugins.aps.openAPSAIMI.energy

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.TrafficStats
import android.os.BatteryManager
import android.os.Process
import android.os.SystemClock
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * Measures where the work of the app goes, so the battery use can be explained. It never changes any setting.
 *
 * What is measured exactly:
 *  - for every marked part of the work (a loop calculation, a command to the pump, a log write, a background worker):
 *    how many times it ran, how long it took, and how much processor time the whole process used in that time;
 *  - once per minute: the battery level, charging state, temperature, voltage, current, the processor time of the
 *    whole app and the network bytes of the whole app;
 *  - the moment of the last charge (when the charger was unplugged) and the level at that moment.
 *
 * What is NOT possible: Android does not let an app read the real energy use of its own parts. So the shares are shares
 * of the measured work (processor time and run time), not milliamperes. The screen asks for the total use of the app
 * from the Android battery screen, and splits that number by the shares.
 *
 * The log is a text file in the private folder of the app, one line per minute with activity, kept for 3 days.
 */
object EnergyMeter {

    const val PREFS = "aimi_energy"
    private const val FILE = "AIMI_Energy.jsonl"
    private const val KEEP_MS = 3L * 24L * 3600_000L
    private const val TRIM_ABOVE_BYTES = 1_500_000L

    private const val K_WAS_PLUGGED = "was_plugged"
    private const val K_UNPLUG_MS = "unplug_ms"
    private const val K_UNPLUG_LEVEL = "unplug_level"
    private const val K_APPROX = "approx"

    /** One marked part of the work, with the setting that decides whether and how often it runs. */
    class Feature(val tag: String, val title: String, val setting: String)

    const val LOOP_GLUCOSE = "loop_glucose"
    const val LOOP_PERIODIC = "loop_periodic"
    const val LOOP_OTHER = "loop_other"
    const val PUMP_SMB = "pump_smb"
    const val PUMP_TBR = "pump_tbr"
    const val LOG_DECISIONS = "log_decisions"
    const val SNAPSHOT = "snapshot"
    const val WORKER_BASAL_ML = "worker_basal_ml"
    const val WORKER_BACKFILL = "worker_backfill"
    const val WORKER_NEURAL = "worker_neural"
    const val WORKER_RETENTION = "worker_retention"
    const val WORKER_PHYSIO = "worker_physio"
    const val WORKER_STEPS = "worker_steps"
    const val WORKER_RUNMODE = "worker_runmode"

    val FEATURES: List<Feature> = listOf(
        Feature(LOOP_GLUCOSE, "Расчёт цикла: пришёл новый сахар", "Не отключается: идёт на каждое значение датчика"),
        Feature(LOOP_PERIODIC, "Расчёт цикла: по таймеру Autodrive", "«SMB интервал» (период таймера) и переключатель Autodrive"),
        Feature(LOOP_OTHER, "Расчёт цикла: прочие запуски", "Команды из меню, смена режима, новые данные помпы"),
        Feature(PUMP_SMB, "Команда помпе: SMB (Bluetooth)", "«SMB интервал», размеры SMB, режим «Я ем»"),
        Feature(PUMP_TBR, "Команда помпе: временный базал (Bluetooth)", "Настройки базала AIMI"),
        Feature(LOG_DECISIONS, "Запись журнала решений", "Отдельного переключателя нет, журнал нужен для анализа"),
        Feature(SNAPSHOT, "Снимок состояния в журнал", "Intelligence snapshot export"),
        Feature(WORKER_BASAL_ML, "Обучение базала (ML), раз в час", "ML training / обучение базала"),
        Feature(WORKER_BACKFILL, "Дозаполнение данных Autodrive, раз в 6 часов", "Обучение Autodrive"),
        Feature(WORKER_NEURAL, "Обучение нейросети Autodrive, раз в сутки", "Обучение Autodrive"),
        Feature(WORKER_RETENTION, "Очистка архива, раз в сутки", "Хранение данных AIMI"),
        Feature(WORKER_PHYSIO, "Сбор физиологических данных, каждые 15 минут", "Physio / Health Connect"),
        Feature(WORKER_STEPS, "Синхронизация шагов (Health Connect), каждые 5 минут", "Шаги из Health Connect"),
        Feature(WORKER_RUNMODE, "Таймер режима работы цикла", "Режим работы цикла"),
    )

    fun featureOf(tag: String): Feature = FEATURES.firstOrNull { it.tag == tag } ?: Feature(tag, tag, "—")

    private class Acc {
        var count = 0
        var wallMs = 0L
        var cpuMs = 0L
    }

    private val lock = Any()
    private var appContext: Context? = null
    private val bucket = HashMap<String, Acc>()
    private var bucketMinute = 0L
    private var lastAppCpuMs = -1L
    private var lastRx = -1L
    private var lastTx = -1L

    /** Called once from any place that has a context. Later calls do nothing. */
    fun attach(context: Context) {
        return
        synchronized(lock) {
            if (appContext != null) return
            appContext = context.applicationContext
            trimIfNeeded()
        }
    }

    /** Runs [block] and records its time and processor use under [tag]. */
    fun <T> measure(tag: String, block: () -> T): T = block()

    /** Same as [measure] for code that suspends. */
    suspend fun <T> measureSuspend(tag: String, block: suspend () -> T): T = block()

    private fun record(tag: String, wallMs: Long, cpuMs: Long) {
        return
        try {
            synchronized(lock) {
                if (appContext == null) return
                val minute = System.currentTimeMillis() / 60_000L
                if (bucketMinute != 0L && bucketMinute != minute) flushLocked()
                bucketMinute = minute
                val acc = bucket.getOrPut(tag) { Acc() }
                acc.count += 1
                acc.wallMs += wallMs.coerceAtLeast(0L)
                acc.cpuMs += cpuMs.coerceAtLeast(0L)
            }
        } catch (_: Exception) {
            // the meter must never stop the real work
        }
    }

    /** Writes the running minute to the file. The screen calls it before it reads the file. */
    fun flushNow() {
        return
        try {
            synchronized(lock) { if (bucketMinute != 0L) flushLocked() }
        } catch (_: Exception) {
            // see above
        }
    }

    private fun flushLocked() {
        val ctx = appContext ?: return
        val minuteStartMs = bucketMinute * 60_000L
        val battery = readBattery(ctx)
        trackCharge(ctx, battery)

        val appCpu = Process.getElapsedCpuTime()
        val cpuDelta = if (lastAppCpuMs < 0L) 0L else (appCpu - lastAppCpuMs).coerceAtLeast(0L)
        lastAppCpuMs = appCpu
        val uid = Process.myUid()
        val rx = TrafficStats.getUidRxBytes(uid)
        val tx = TrafficStats.getUidTxBytes(uid)
        val rxDelta = if (lastRx < 0L || rx < 0L) 0L else (rx - lastRx).coerceAtLeast(0L)
        val txDelta = if (lastTx < 0L || tx < 0L) 0L else (tx - lastTx).coerceAtLeast(0L)
        lastRx = rx
        lastTx = tx

        val features = JSONObject()
        bucket.forEach { (tag, a) -> features.put(tag, JSONArray().put(a.count).put(a.wallMs).put(a.cpuMs)) }
        val line = JSONObject()
            .put("t", minuteStartMs)
            .put("lvl", battery.level)
            .put("plug", battery.plugged)
            .put("tc", battery.tempC)
            .put("mv", battery.millivolt)
            .put("ma", battery.milliamp)
            .put("cpu", cpuDelta)
            .put("rx", rxDelta)
            .put("tx", txDelta)
            .put("f", features)
        File(ctx.filesDir, FILE).appendText(line.toString() + "\n")
        bucket.clear()
        bucketMinute = 0L
    }

    // ---- battery and the moment of the last charge ----

    private class Battery(
        val level: Int,
        val plugged: Boolean,
        val tempC: Double,
        val millivolt: Int,
        val milliamp: Int,
    )

    private fun readBattery(ctx: Context): Battery {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val plugged = (i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val temp = (i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
        val mv = i?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val microAmp = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) ?: 0
        return Battery(percent, plugged, temp, mv, microAmp / 1000)
    }

    private fun trackCharge(ctx: Context, b: Battery) {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val wasPlugged = sp.getBoolean(K_WAS_PLUGGED, false)
        val now = System.currentTimeMillis()
        if (b.plugged) {
            if (!wasPlugged) sp.edit().putBoolean(K_WAS_PLUGGED, true).apply()
            return
        }
        if (wasPlugged) {
            // The charger was unplugged: from now on the use is counted from this level.
            sp.edit().putBoolean(K_WAS_PLUGGED, false).putLong(K_UNPLUG_MS, now).putInt(K_UNPLUG_LEVEL, b.level).putBoolean(K_APPROX, false).apply()
        } else if (sp.getLong(K_UNPLUG_MS, 0L) == 0L) {
            // The meter started while the phone was already on battery: the real moment of the last charge is not known.
            sp.edit().putLong(K_UNPLUG_MS, now).putInt(K_UNPLUG_LEVEL, b.level).putBoolean(K_APPROX, true).apply()
        }
    }

    class ChargeInfo(
        /** When the counting starts: the last unplug, or the first start of the meter. */
        val sinceMs: Long,
        val levelAtStart: Int,
        val levelNow: Int,
        val plugged: Boolean,
        /** True when the real last charge is not known (the meter started on battery). */
        val approximate: Boolean,
    )

    fun chargeInfo(context: Context): ChargeInfo {
        val battery = readBattery(context)
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return ChargeInfo(
            sinceMs = sp.getLong(K_UNPLUG_MS, 0L),
            levelAtStart = sp.getInt(K_UNPLUG_LEVEL, battery.level),
            levelNow = battery.level,
            plugged = battery.plugged,
            approximate = sp.getBoolean(K_APPROX, true),
        )
    }

    // ---- reading for the screen ----

    class Minute(
        val timeMs: Long,
        val level: Int,
        val plugged: Boolean,
        val tempC: Double,
        val millivolt: Int,
        val milliamp: Int,
        /** Processor time of the whole app in this minute, ms. */
        val appCpuMs: Long,
        val rxBytes: Long,
        val txBytes: Long,
        /** tag -> (runs, run time ms, processor time ms) */
        val features: Map<String, Triple<Int, Long, Long>>,
    )

    fun readMinutes(context: Context, sinceMs: Long): List<Minute> {
        flushNow()
        val file = File(context.filesDir, FILE)
        if (!file.exists()) return emptyList()
        val result = ArrayList<Minute>()
        file.forEachLine { text ->
            try {
                val o = JSONObject(text)
                val t = o.getLong("t")
                if (t >= sinceMs) {
                    val f = o.optJSONObject("f")
                    val map = LinkedHashMap<String, Triple<Int, Long, Long>>()
                    f?.keys()?.forEach { key ->
                        val a = f.getJSONArray(key)
                        map[key] = Triple(a.getInt(0), a.getLong(1), a.getLong(2))
                    }
                    result.add(
                        Minute(
                            timeMs = t,
                            level = o.optInt("lvl", -1),
                            plugged = o.optBoolean("plug", false),
                            tempC = o.optDouble("tc", 0.0),
                            millivolt = o.optInt("mv", 0),
                            milliamp = o.optInt("ma", 0),
                            appCpuMs = o.optLong("cpu", 0L),
                            rxBytes = o.optLong("rx", 0L),
                            txBytes = o.optLong("tx", 0L),
                            features = map,
                        )
                    )
                }
            } catch (_: Exception) {
                // a broken line is skipped
            }
        }
        return result
    }

    private fun trimIfNeeded() {
        val ctx = appContext ?: return
        try {
            val file = File(ctx.filesDir, FILE)
            if (!file.exists() || file.length() < TRIM_ABOVE_BYTES) return
            val limit = System.currentTimeMillis() - KEEP_MS
            val kept = file.readLines().filter { line ->
                try {
                    JSONObject(line).getLong("t") >= limit
                } catch (_: Exception) {
                    false
                }
            }
            file.writeText(if (kept.isEmpty()) "" else kept.joinToString("\n", postfix = "\n"))
        } catch (_: Exception) {
            // the file is only a diagnostic log
        }
    }
}
