package app.aaps.plugins.aps.openAPSAIMI.wcycle

class WCycleAdjuster(
    private val prefs: WCyclePreferences,
    private val estimator: WCycleEstimator,
    private val learner: WCycleLearner
) {
    fun getInfo(): WCycleInfo {
        if (!prefs.enabled()) return WCycleInfo(
            enabled = false,
            dayInCycle = 0,
            phase = CyclePhase.UNKNOWN,
            baseBasalMultiplier = 1.0,
            baseSmbMultiplier = 1.0,
            learnedBasalMultiplier = 1.0,
            learnedSmbMultiplier = 1.0,
            basalMultiplier = 1.0,
            smbMultiplier = 1.0,
            applied = false,
            reason = ""
        )
        val profile = WCycleProfile(
            prefs.trackingMode(), prefs.contraceptive(), prefs.thyroid(), prefs.verneuil(),
            prefs.startDom(), prefs.avgLen(), prefs.shadow(), prefs.requireConfirm(),
            prefs.clampMin(), prefs.clampMax()
        )
        val (day, phase0) = estimator.estimate()
        val (b0, s0) = WCycleDefaults.baseMultipliers(phase0)

        val ampContraceptive = WCycleDefaults.amplitudeScale(profile.contraceptive)
        val ampMode = when (profile.trackingMode) {
            CycleTrackingMode.PERIMENOPAUSE -> 0.8
            CycleTrackingMode.NO_MENSES_LARC -> 0.6
            else -> 1.0
        }
        val amp = ampContraceptive * ampMode
        var basal = 1.0 + (b0 - 1.0) * amp
        var smb   = 1.0 + (s0 - 1.0) * amp
        val (vb, vs) = WCycleDefaults.verneuilBump(profile.verneuil)
        basal *= vb; smb *= vs

        // Thyroid dampening removed as per user request and research alignment
        // Treated thyroid conditions should not penalize the cycle adjustment
        val thyroidDamp = 1.0 
        basal = 1.0 + (basal - 1.0) * thyroidDamp
        smb   = 1.0 + (smb   - 1.0) * thyroidDamp

        val baseBasal = basal.coerceIn(profile.clampMin, profile.clampMax)
        val baseSmb = smb.coerceIn(profile.clampMin, profile.clampMax)

        val (bLearn, sLearn) = learner.learnedMultipliers(phase0, profile.clampMin, profile.clampMax)
        basal = (baseBasal * bLearn).coerceIn(profile.clampMin, profile.clampMax)
        smb   = (baseSmb   * sLearn).coerceIn(profile.clampMin, profile.clampMax)

        val apply = !(profile.shadowMode || profile.requireUserConfirm)
        val finalBasal = if (apply) basal else 1.0
        val finalSmb = if (apply) smb else 1.0
        val guardReason = when {
            profile.shadowMode -> "shadow"
            profile.requireUserConfirm -> "confirm"
            else -> "apply"
        }

        val reason = "♀️ ${phase0} J${day + 1} | amp=${fmt(amp)} thy=${fmt(thyroidDamp)} ver=${profile.verneuil} | base=(${fmt(baseBasal)},${fmt(baseSmb)}) learn=(${fmt(bLearn)},${fmt(sLearn)}) ${guardReason}"
        return WCycleInfo(
            enabled = true,
            dayInCycle = day,
            phase = phase0,
            baseBasalMultiplier = baseBasal,
            baseSmbMultiplier = baseSmb,
            learnedBasalMultiplier = bLearn,
            learnedSmbMultiplier = sLearn,
            basalMultiplier = finalBasal,
            smbMultiplier = finalSmb,
            applied = apply,
            reason = reason
        )
    }
    private fun fmt(x: Double) = String.format("%.2f", x)
}
