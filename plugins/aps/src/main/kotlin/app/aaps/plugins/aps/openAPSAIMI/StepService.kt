package app.aaps.plugins.aps.openAPSAIMI
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.util.Log
import kotlin.math.roundToLong

/**
 * Phone step counts from a live sensor listener, kept in 5 minute buckets.
 *
 * **`null` means unknown, and 0 means the person really took no steps.** The getters answer `null`
 * until this listener has received its first sensor event since the process started. Before that we
 * cannot tell a missing sensor, or a listener that was never registered, from a person who has not
 * walked yet, so we do not claim "no steps". After the first event the listener is proven alive,
 * and an empty bucket is a real 0. One limit stays: the latch is never cleared, so a listener that
 * dies later still answers 0.
 *
 * The engine takes no step-driven decision from `null`: in `BasalDecisionEngine` neither the 1.5
 * times raise (steps under 100) nor the 0.5 times lowering (steps over 100) can fire, and in the DIA
 * adjustment neither the 1.2 nor the 0.90 factor is applied.
 *
 * `MainApp` registers this object on `TYPE_STEP_COUNTER`. It must stay a counter: [onSensorChanged]
 * subtracts the last value, which is wrong for `TYPE_STEP_DETECTOR`.
 */
object StepService : SensorEventListener {

    private const val TAG = "StepService"
    private var previousStepCount = -1
    private val stepsMap = LinkedHashMap<Long, Int>()
    private const val fiveMinutesInMs = 300000
    private const val numOf5MinBlocksToKeep = 20

    /** Set by the first sensor event. While it is false the getters answer `null` (unknown). */
    @Volatile
    private var hasReceivedSensorEvent = false

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        Log.i(TAG, "onAccuracyChanged: Sensor: $sensor; accuracy: $accuracy")
    }

    private fun currentTimeIn5Min(): Long {
        return (System.currentTimeMillis() / fiveMinutesInMs.toDouble()).roundToLong()
    }

    override fun onSensorChanged(sensorEvent: SensorEvent?) {
        sensorEvent ?: return
        hasReceivedSensorEvent = true

        val now = currentTimeIn5Min()
        val stepCount = sensorEvent.values[0].toInt()
        // A counter value below the last one means the counter was reset (for example a reboot).
        // Start a new baseline instead of storing a negative number of steps.
        if(previousStepCount >= 0 && stepCount >= previousStepCount) {
            var recentStepCount = stepCount - previousStepCount
            if(stepsMap.contains(now)) {
                recentStepCount += stepsMap.getValue(now)
            }
            stepsMap[now] = recentStepCount
        }
        previousStepCount = stepCount

        if(stepsMap.size > numOf5MinBlocksToKeep) {
            val removeBefore = now - numOf5MinBlocksToKeep
            stepsMap.entries.removeIf { it.key < removeBefore}
        }
    }

    fun getRecentStepCount5Min(): Int? {
        if (!hasReceivedSensorEvent) return null
        val now = currentTimeIn5Min() - 1
        return if(stepsMap.contains(now)) stepsMap.getValue(now) else 0
    }

    fun getRecentStepCount10Min(): Int? {
        if (!hasReceivedSensorEvent) return null
        val tenMinAgo = currentTimeIn5Min() - 2
        return if(stepsMap.contains(tenMinAgo)) stepsMap.getValue(tenMinAgo) else 0
    }

    fun getRecentStepCount15Min(): Int? {
        if (!hasReceivedSensorEvent) return null
        val fifteenMinAgo = currentTimeIn5Min() - 3
        return if(stepsMap.contains(fifteenMinAgo)) stepsMap.getValue(fifteenMinAgo) else 0
    }

    fun getRecentStepCount30Min(): Int? {
        if (!hasReceivedSensorEvent) return null
        return getStepsInLastXMin(6)
    }

    fun getRecentStepCount60Min(): Int? {
        if (!hasReceivedSensorEvent) return null
        return getStepsInLastXMin(12)
    }
    fun getRecentStepCount180Min(): Int? {
        if (!hasReceivedSensorEvent) return null
        return getStepsInLastXMin(34)
    }

    private fun getStepsInLastXMin(numberOf5MinIncrements: Int): Int {
        var stepCount = 0
        val thirtyMinAgo = currentTimeIn5Min() - numberOf5MinIncrements
        for (entry in stepsMap.entries) {
            if (entry.key > thirtyMinAgo) {
                stepCount += entry.value
            }
        }
        return stepCount
    }

}