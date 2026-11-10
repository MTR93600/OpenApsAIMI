package app.aaps.plugins.aps.openAPSAIMI.meal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle

/** Package-private bridge: Wear transport requests a mode, EatingEngine remains the only owner. */
class AimiEatingWearReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val state = handle(context, intent) ?: return
        writeResult(state)
    }

    internal fun handle(context: Context, intent: Intent): EatingEngine.Snapshot? {
        val engine = EatingEngine(context)
        when (aimiEatingWearCommand(intent.action)) {
            AimiEatingWearCommand.START -> {
                val size = intent.getIntExtra(EXTRA_SIZE, EatingEngine.SIZE_AUTO).coerceIn(0, EatingEngine.SIZE_AUTO)
                val fat = intent.getBooleanExtra(EXTRA_FAT, false)
                engine.setSize(size)
                engine.setFat(fat)
                engine.start(System.currentTimeMillis(), 0.0, size)
            }
            AimiEatingWearCommand.STOP -> engine.cancel()
            AimiEatingWearCommand.STATUS -> Unit
            null -> return null
        }
        return engine.snapshot()
    }

    private fun writeResult(state: EatingEngine.Snapshot) {
        val window = if (state.fat) EatingEngine.WINDOW_MIN_FAT else EatingEngine.WINDOW_MIN
        val elapsed = if (state.startMs > 0) (System.currentTimeMillis() - state.startMs) / 60_000.0 else 0.0
        setResultExtras(Bundle().apply {
            putBoolean(RESULT_ACTIVE, state.active)
            putInt(RESULT_SIZE, state.size)
            putBoolean(RESULT_FAT, state.fat)
            putDouble(RESULT_ESTIMATE_G, state.estimateG)
            putDouble(RESULT_NEEDED_U, state.neededU)
            putDouble(RESULT_DELIVERED_U, state.deliveredU)
            putInt(RESULT_REMAINING_MIN, (window - elapsed).toInt().coerceAtLeast(0))
        })
    }

    companion object {
        const val ACTION_START = "app.aaps.action.AIMI_EATING_START"
        const val ACTION_STOP = "app.aaps.action.AIMI_EATING_STOP"
        const val ACTION_STATUS = "app.aaps.action.AIMI_EATING_STATUS"
        const val EXTRA_SIZE = "size"
        const val EXTRA_FAT = "fat"
        const val RESULT_ACTIVE = "active"
        const val RESULT_SIZE = "result_size"
        const val RESULT_FAT = "result_fat"
        const val RESULT_ESTIMATE_G = "estimate_g"
        const val RESULT_NEEDED_U = "needed_u"
        const val RESULT_DELIVERED_U = "delivered_u"
        const val RESULT_REMAINING_MIN = "remaining_min"
    }
}
