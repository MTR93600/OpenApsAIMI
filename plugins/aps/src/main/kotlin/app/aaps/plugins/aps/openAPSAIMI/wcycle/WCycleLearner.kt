package app.aaps.plugins.aps.openAPSAIMI.wcycle

import android.content.Context
import java.io.File
import java.util.EnumMap
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

class WCycleLearner(
    private val ctx: Context, // <-- on injecte Context pour persister sur disque
    private val alpha: Double = 0.10,
    private val clampMin: Double = 0.70,
    private val clampMax: Double = 1.30
) {
    private val learnedBasal = EnumMap<CyclePhase, Double>(CyclePhase::class.java)
    private val learnedSmb   = EnumMap<CyclePhase, Double>(CyclePhase::class.java)
    @Volatile private var initialized = false

    private val dir by lazy { File(ctx.getExternalFilesDir(null), "Documents/AAPS") }
    private val learnedFile by lazy { File(dir, "oapsaimi_wcycle_learned.json") }

    init {
        CyclePhase.values().forEach {
            learnedBasal[it] = 1.0
            learnedSmb[it]   = 1.0
        }
    }

    fun initFromDiskIfNeeded() {
        if (initialized) return
        runCatching {
            if (!learnedFile.exists()) { initialized = true; return }
            val txt = learnedFile.readText()
            val json = JSONObject(txt)
            fun loadArr(key: String, target: EnumMap<CyclePhase, Double>) {
                val arr = json.optJSONArray(key) ?: return
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val ph = runCatching { CyclePhase.valueOf(o.getString("phase")) }.getOrNull() ?: continue
                    val v  = o.optDouble("v", 1.0).coerceIn(clampMin, clampMax)
                    target[ph] = v
                }
            }
            loadArr("basal", learnedBasal)
            loadArr("smb", learnedSmb)
        }.onFailure { /* ignore */ }
        initialized = true
    }
    fun provideWCycleLearner(context: Context): WCycleLearner =
        WCycleLearner(ctx = context)

    fun persistToDisk() {
        runCatching {
            dir.mkdirs()
            val obj = JSONObject()
            fun dump(map: EnumMap<CyclePhase, Double>): JSONArray {
                val arr = JSONArray()
                CyclePhase.values().forEach { ph ->
                    val o = JSONObject()
                    o.put("phase", ph.name)
                    o.put("v", map[ph] ?: 1.0)
                    arr.put(o)
                }
                return arr
            }
            obj.put("basal", dump(learnedBasal))
            obj.put("smb", dump(learnedSmb))
            learnedFile.writeText(obj.toString())
        }
    }

    fun update(phase: CyclePhase, needBasalScale: Double?, needSmbScale: Double?) {
        initFromDiskIfNeeded()
        if (phase == CyclePhase.UNKNOWN) return
        needBasalScale?.let {
            val prev = learnedBasal[phase] ?: 1.0
            learnedBasal[phase] = clamp(ema(prev, it))
        }
        needSmbScale?.let {
            val prev = learnedSmb[phase] ?: 1.0
            learnedSmb[phase] = clamp(ema(prev, it))
        }
        persistToDisk() // simple ; si tu veux limiter les E/S, persiste 1 fois / 10
    }

    fun learnedMultipliers(phase: CyclePhase, clampMin: Double, clampMax: Double): Pair<Double, Double> {
        initFromDiskIfNeeded()
        val b = (learnedBasal[phase] ?: 1.0).coerceIn(clampMin, clampMax)
        val s = (learnedSmb  [phase] ?: 1.0).coerceIn(clampMin, clampMax)
        return b to s
    }

    /** Offline: rejoue un apprentissage à partir du CSV wcycle */
    fun retrainFromCsv(csv: File, maxRows: Int = Int.MAX_VALUE) {
        initFromDiskIfNeeded()
        if (!csv.exists() || !csv.canRead()) return

        val lines = csv.readLines()
        if (lines.isEmpty()) return
        val header = lines.first().split(',').map { it.trim().lowercase() }

        fun idx(vararg names: String): Int? {
            for (n in names) {
                val i = header.indexOf(n.lowercase())
                if (i >= 0) return i
            }
            return null
        }

        val idxPhase = idx("phase") ?: return
        // ces deux colonnes seront ajoutées au CSV dans la section 1.2
        val idxNeedBasal = idx("needbasalscale", "need_basal", "needbasal")
        val idxNeedSmb   = idx("needsmbscale", "need_smb", "needsmb")

        lines.drop(1).take(maxRows).forEach { line ->
            val parts = line.split(',')
            fun part(i: Int?) = i?.let { if (it in parts.indices) parts[it].trim() else null }

            val ph = runCatching { CyclePhase.valueOf(part(idxPhase) ?: return@forEach) }.getOrNull() ?: return@forEach
            val nb = part(idxNeedBasal)?.toDoubleOrNull()
            val ns = part(idxNeedSmb)?.toDoubleOrNull()
            update(ph, nb, ns)
        }
        persistToDisk()
    }

    private fun ema(prev: Double, obs: Double) = (1 - alpha) * prev + alpha * obs
    private fun clamp(v: Double) = max(clampMin, min(clampMax, v))
}
