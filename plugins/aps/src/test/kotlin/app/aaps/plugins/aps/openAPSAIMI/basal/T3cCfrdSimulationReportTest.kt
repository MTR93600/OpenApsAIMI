package app.aaps.plugins.aps.openAPSAIMI.basal

/**
 * Standalone numeric replay of the support-package episodes.
 * Run via: ./gradlew :plugins:aps:testFullDebugUnitTest --tests "*T3cCfrdSimulationReport*"
 *
 * Prints before/after basal rates so the simulation is human-readable in the test log.
 */
class T3cCfrdSimulationReportTest {

    private data class Tick(
        val label: String,
        val bg: Double,
        val delta: Float,
        val profileBasal: Double,
        val iob: Double,
        val prevRate: Double,
        val hyperDwellOk: Boolean,
    )

    private fun applyHyperBasalFloor(
        piRate: Double,
        bg: Double,
        hyperFloorEnabled: Boolean,
        dwellOk: Boolean,
        profileMaxBasal: Double,
        maxBasalCap: Double,
    ): Double {
        val applies = hyperFloorEnabled && bg >= 160.0 && dwellOk
        val floor = if (applies) profileMaxBasal.coerceIn(0.0, maxBasalCap) else 0.0
        return piRate.coerceAtLeast(floor)
    }

    private fun simulate(
        tick: Tick,
        aggressiveness: Double,
        anticipationStrength: Double,
        cobDelaySteps: Int,
        lgsFloor: Double,
        unlock: Boolean,
        hyperFloorEnabled: Boolean,
    ): Pair<Double, Double> {
        val hints = T3cAnticipation.buildHints(
            predictions = null,
            bgNow = tick.bg,
            lgsThresholdMgdl = lgsFloor,
            activationThreshold = 110.0,
            eventualBg = tick.bg + tick.delta * 6,
            strengthRaw = anticipationStrength,
            lgsFloorMgdl = lgsFloor,
            cobDelaySteps = cobDelaySteps,
        )
        val pi = DynamicBasalController.computeT3c(
            bg = tick.bg,
            targetBg = 100.0,
            delta = tick.delta,
            shortAvgDelta = tick.delta.toDouble(),
            longAvgDelta = tick.delta.toDouble(),
            accel = 0.0,
            iob = tick.iob,
            maxIob = 20.0,
            profileBasal = tick.profileBasal,
            isf = 48.0,
            duraISFminutes = if (tick.bg > 160) 40.0 else 0.0,
            duraISFaverage = if (tick.bg > 160) tick.bg else 100.0,
            eventualBg = tick.bg + tick.delta * 6,
            activationThreshold = 110.0,
            aggressiveness = aggressiveness,
            maxBasalCap = if (unlock) 3.0 else 5.0,
            anticipationHints = hints,
        )
        val unlockDec = T3cAutodriveBasalBridge.UnlockDecision(
            unlock,
            if (unlock) "glycemic_override" else "no_confirmed_rise",
        )
        val fusion = T3cAutodriveBasalBridge.fuse(
            piUph = pi,
            adTbrUph = null,
            strippedSmbU = 0.0,
            profileBasalUph = tick.profileBasal,
            steadyCapUph = 5.0,
            riseCapUph = 3.0,
            previousRateUph = tick.prevRate,
            unlock = unlockDec,
        )
        val ramped = T3cAutodriveBasalBridge.applyRamp(tick.prevRate, fusion.fusedTargetUph, fusion.maxStepUpUph)
        val floored = applyHyperBasalFloor(
            piRate = ramped,
            bg = tick.bg,
            hyperFloorEnabled = hyperFloorEnabled,
            dwellOk = tick.hyperDwellOk,
            profileMaxBasal = 5.0,
            maxBasalCap = fusion.maxBasalCapUph,
        )
        return pi to floored
    }

    @org.junit.jupiter.api.Test
    fun `print support-package before after simulation report`() {
        val out = StringBuilder()
        out.appendLine("=== T3C/CFRD SIMULATION REPORT (support package 1785422390332) ===")
        out.appendLine()

        // --- Morning whipsaw ---
        val whip = Tick("07:10", 208.2, -5.33f, 0.30, 3.76, 1.61, true)
        val whipLegacy = simulate(whip, 0.8, 0.3, 0, 70.0, unlock = false, hyperFloorEnabled = false)
        val whipFixed = simulate(whip, 0.8, 0.6, 6, 95.0, unlock = false, hyperFloorEnabled = true)
        out.appendLine("1) MORNING WHIPSAW (BG 208 falling after peak 228)")
        out.appendLine("   Observed package: target_basal=0.00 U/h")
        out.appendLine("   Sim LEGACY  : PI=${"%.3f".format(whipLegacy.first)} → final=${"%.3f".format(whipLegacy.second)} U/h")
        out.appendLine("   Sim CORRECT : PI=${"%.3f".format(whipFixed.first)} → final=${"%.3f".format(whipFixed.second)} U/h (hyper floor ON)")
        out.appendLine("   Delta final : ${"%.3f".format(whipFixed.second - whipLegacy.second)} U/h")
        out.appendLine()

        // --- Afternoon under-dose ---
        val aft = Tick("16:50", 186.8, 11.26f, 0.30, -0.79, 0.42, true)
        val aftLegacy = simulate(aft, 0.6, 0.3, 0, 70.0, unlock = false, hyperFloorEnabled = false)
        val aftPrefsOnly = simulate(aft, 0.6, 0.6, 6, 95.0, unlock = false, hyperFloorEnabled = true)
        val aftFull = simulate(aft, 1.2, 0.6, 6, 95.0, unlock = true, hyperFloorEnabled = true)
        out.appendLine("2) AFTERNOON RISE (BG 186.8 Δ=+11.3, observed ~0.49 U/h)")
        out.appendLine("   Sim LEGACY       : PI=${"%.3f".format(aftLegacy.first)} → final=${"%.3f".format(aftLegacy.second)} U/h")
        out.appendLine("   Sim PREFS-ONLY   : PI=${"%.3f".format(aftPrefsOnly.first)} → final=${"%.3f".format(aftPrefsOnly.second)} U/h (CFRD+floor, unlock=false, agg=0.6)")
        out.appendLine("   Sim FULL(B3-ish) : PI=${"%.3f".format(aftFull.first)} → final=${"%.3f".format(aftFull.second)} U/h (unlock+agg 1.2)")
        out.appendLine()

        // --- Climb ---
        out.appendLine("3) MORNING CLIMB ramp (unlock=true, CFRD defaults)")
        var prev = 0.01
        for ((label, bg, delta) in listOf(
            Triple("06:35", 168.8, 21.87f),
            Triple("06:40", 194.7, 23.56f),
            Triple("06:45", 217.2, 22.78f),
            Triple("06:50", 228.4, 16.68f),
        )) {
            val tick = Tick(label, bg, delta, 0.30, 3.1, prev, bg >= 160)
            val (_, finalRate) = simulate(tick, 0.7, 0.6, 6, 95.0, unlock = true, hyperFloorEnabled = true)
            out.appendLine("   $label BG=${"%.1f".format(bg)} Δ=${"%.1f".format(delta)} → basal=${"%.3f".format(finalRate)} U/h")
            prev = finalRate
        }
        out.appendLine()
        out.appendLine("=== END REPORT ===")

        // Always fail-soft print via assertion message so Gradle shows the report
        org.junit.jupiter.api.Assertions.assertTrue(true, out.toString())
        println(out.toString())
        System.err.println(out.toString())
        val reportFile = java.io.File("build/t3c_cfrd_simulation_report.txt")
        reportFile.parentFile?.mkdirs()
        reportFile.writeText(out.toString())
        // Also write under repo-visible reports path used by Gradle module
        val moduleReport = java.io.File("plugins/aps/build/reports/t3c_cfrd_simulation_report.txt")
        moduleReport.parentFile?.mkdirs()
        moduleReport.writeText(out.toString())
    }
}
