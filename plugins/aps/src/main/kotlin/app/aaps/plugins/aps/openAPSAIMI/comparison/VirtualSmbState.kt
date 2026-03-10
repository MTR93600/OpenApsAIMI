package app.aaps.plugins.aps.openAPSAIMI.comparison

import app.aaps.core.data.model.BS
import app.aaps.core.data.model.TB
import app.aaps.core.interfaces.aps.AutosensResult
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.RT
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.data.iob.Iob
import app.aaps.core.objects.extensions.plus
import app.aaps.core.objects.extensions.getPassedDurationToTimeInMinutes
import app.aaps.core.interfaces.aps.OapsProfile
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.max
import kotlin.math.ceil

/**
 * =============================================================================
 * VIRTUAL SMB STATE
 * =============================================================================
 * 
 * Houses the "Shadow State" for the SMB engine.
 * Records what SMB *would have done* to calculate what its IOB *would be*.
 * 
 * This treats SMB as a completely separate "Virtual Patient" who only receives
 * the insulin that SMB itself decided.
 */
class VirtualInsulinReservoir {
    // History of Virtual Decisions
    val virtualBoluses = mutableListOf<BS>()
    val virtualTempBasals = mutableListOf<TB>()

    fun addDecision(decision: RT, timestamp: Long) {
        // 1. Convert SMB Micro-Bolus
        if ((decision.units ?: 0.0) > 0.0) {
            val bolus = BS(
                timestamp = timestamp,
                amount = decision.units!!,
                type = BS.Type.SMB,
                isBasalInsulin = false
            )
            virtualBoluses.add(bolus)
        }

        // 2. Convert Temp Basal
        if (decision.rate != null && decision.duration != null) {
            val tb = TB(
                timestamp = timestamp,
                duration = decision.duration!! * 60 * 1000L, // min to ms
                rate = decision.rate!!,
                isAbsolute = true,
                type = TB.Type.NORMAL
            )
            // Logic: A new TB theoretically replaces/cancels the old one.
            truncateRunningTemp(timestamp)
            virtualTempBasals.add(tb)
        }
    }

    private fun truncateRunningTemp(now: Long) {
        val lastTb = virtualTempBasals.lastOrNull() ?: return
        if (lastTb.end > now) {
            // Shorten the previous TB to end now
            lastTb.duration = max(0L, now - lastTb.timestamp)
        }
    }
    
    fun clear() {
        virtualBoluses.clear()
        virtualTempBasals.clear()
    }
    
    fun pruneOldData(threshold: Long) {
        virtualBoluses.removeIf { it.timestamp < threshold }
        virtualTempBasals.removeIf { it.end < threshold }
    }
}

/**
 * Calculates IOB based strictly on the Virtual Reservoir.
 * Mimics behaviors of IobCobCalculator but sources data from VirtualReservoir.
 * 
 * Uses internal Exponential Decay math to avoid dependency on ActivePlugin/Insulin interfaces
 * that are hard to inject here.
 */
class VirtualIobCalculator(
    private val reservoir: VirtualInsulinReservoir,
    private val dateUtil: DateUtil
) {
    /**
     * Calculates the IOB array (future projection) for SMB.
     * Replicates `calculateIobArrayForSMB` signature but uses snapshot OapsProfile.
     */
    fun calculateIobArrayForSMB(
        profile: OapsProfile,
        lastAutosensResult: AutosensResult?,
        exerciseMode: Boolean,
        halfBasalExerciseTarget: Int,
        isTempTarget: Boolean
    ): Array<IobTotal> {
        val now = dateUtil.now()
        val len = 4 * 60 / 5 // 4 hours projection (standard for SMB)
        val array = Array(len) { IobTotal(0) }
        
        for ((pos, i) in (0 until len).withIndex()) {
            val t = now + i * 5 * 60000
            val iob = calculateTotalIob(t, profile)
            array[pos] = iob
        }
        return array
    }

    private fun calculateTotalIob(
        time: Long,
        profile: OapsProfile
    ): IobTotal {
        val total = IobTotal(time)
        val dia = profile.dia

        // 1. Virtual Boluses (SMBs)
        reservoir.virtualBoluses.forEach { bolus ->
            if (bolus.isValid && bolus.timestamp < time) {
                // INTERNAL MATH: Replace activePlugin.activeInsulin.iobCalcForTreatment
                val res = calculateIobForTreatment(bolus, time, dia)
                total.iob += res.iobContrib
                total.activity += res.activityContrib
                if (bolus.amount > 0 && bolus.timestamp > total.lastBolusTime) {
                    total.lastBolusTime = bolus.timestamp
                }
            }
        }

        // 2. Virtual Temp Basals
        reservoir.virtualTempBasals.forEach { tb ->
           if (tb.timestamp < time) {
                // INLINE IMPLEMENTATION OF TB IOB
                val realDuration = tb.getPassedDurationToTimeInMinutes(time)
                if (realDuration > 0) {
                     val aboutFiveMinIntervals = ceil(realDuration / 5.0).toInt()
                     val tempBolusSpacing = realDuration / aboutFiveMinIntervals.toDouble()
                     for (j in 0L until aboutFiveMinIntervals) {
                        val calcDate = (tb.timestamp + j * tempBolusSpacing * 60 * 1000 + 0.5 * tempBolusSpacing * 60 * 1000).toLong()
                        // Use constant basal from snapshot profile
                        val basalRate = profile.current_basal
                        // Simple absolute TB logic (SMB always sets absolute)
                        val netRate = if (tb.isAbsolute) tb.rate - basalRate else (tb.rate - 100)/100.0 * basalRate
                        
                        val term = time - dia * 60 * 60 * 1000
                        if (calcDate > term && calcDate <= time) {
                            val tempBolusSize = netRate * tempBolusSpacing / 60.0
                            val tempBolusPart = BS(timestamp = calcDate, amount = tempBolusSize, type = BS.Type.NORMAL)
                            val aIOB = calculateIobForTreatment(tempBolusPart, time, dia)
                            total.basaliob += aIOB.iobContrib
                            total.activity += aIOB.activityContrib
                            total.netbasalinsulin += tempBolusPart.amount
                             if (tempBolusPart.amount > 0) total.hightempinsulin += tempBolusPart.amount
                        }
                     }
                }
           }
        }
        return total
    }

    /**
     * Internal implementation of Exponential IOB Curves (Fiasp/Rapid)
     * Adapted from InsulinOrefBasePlugin.kt
     */
    private fun calculateIobForTreatment(bolus: BS, time: Long, dia: Double): Iob {
        val result = Iob()
        // Default Peak for Fiasp/Lyumjev/Rapid is ~75m usually, or 55m for Fiasp.
        // We will assume 75 minutes (standard rapid acting).
        val peak = 75 
        
        if (bolus.amount != 0.0) {
            val bolusTime = bolus.timestamp
            val t = (time - bolusTime) / 1000.0 / 60.0
            val td = dia * 60 
            val tp = peak.toDouble()
            
            if (t < td) {
                val tau = tp * (1 - tp / td) / (1 - 2 * tp / td)
                val a = 2 * tau / td
                val s = 1 / (1 - a + (1 + a) * exp(-td / tau))
                result.activityContrib = bolus.amount * (s / tau.pow(2.0)) * t * (1 - t / td) * exp(-t / tau)
                result.iobContrib = bolus.amount * (1 - s * (1 - a) * ((t.pow(2.0) / (tau * td * (1 - a)) - t / tau - 1) * exp(-t / tau) + 1))
            }
        }
        return result
    }
}
