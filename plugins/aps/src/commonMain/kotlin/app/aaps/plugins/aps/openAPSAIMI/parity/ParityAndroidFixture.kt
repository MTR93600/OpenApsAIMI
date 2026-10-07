package app.aaps.plugins.aps.openAPSAIMI.parity

/**
 * Frozen from the live Android tick (`DetermineBasalaimiSMB2` as ported). The host test
 * `ShellDecisionTraceTest.parityHarnessCanonicalTracesMatchTheFrozenAndroidFixture` prints the
 * canonical block and that printed text is pasted here. Nothing in this file is written by hand.
 *
 * Nine scenarios in a fixed order, joined with a line holding three dashes. Each scenario is the
 * fixed field order of [ParityDoseTrace], doubles as raw IEEE-754 bits.
 *
 * This records what Android does, not what it should do. Two captured values are open questions
 * and are left exactly as the tick produced them:
 *  - `uam` has `tbrRate=43747ae147ae147b`, which is 9.223372036854776e16 U/h. That is
 *    `Long.MAX_VALUE / 100`, the saturation value of `AimiTickPolicyMath.roundBasal`.
 *  - `declared-meal` has no `tbrRate` and no `tbrMinutes`, although the advisor asks for a
 *    30 minute temp basal.
 */
object ParityAndroidFixture {

    val BLOCK: String = """
        scenario=fasting
        tbrRate=4000000000000000
        tbrMinutes=absent
        smb=absent
        eventual=absent
        isf=absent
        virtualCob=absent
        modelWord0=absent
        healthKit=present
        bg=405b800000000000
        delta=4000000000000000
        port=android
        ---
        scenario=declared-meal
        tbrRate=absent
        tbrMinutes=absent
        smb=400a666666666666
        eventual=4065e00000000000
        isf=absent
        virtualCob=absent
        modelWord0=absent
        healthKit=present
        bg=4064000000000000
        delta=4000000000000000
        port=android
        ---
        scenario=uam
        tbrRate=43747ae147ae147b
        tbrMinutes=30
        smb=absent
        eventual=4071500000000000
        isf=403e000000000000
        virtualCob=0000000000000000
        modelWord0=3f9eea89
        healthKit=present
        bg=4066800000000000
        delta=4014000000000000
        port=android
        ---
        scenario=hypo-rebound
        tbrRate=absent
        tbrMinutes=absent
        smb=3fc70a3d80000000
        eventual=4066800000000000
        isf=absent
        virtualCob=absent
        modelWord0=absent
        healthKit=present
        bg=4066800000000000
        delta=0000000000000000
        port=android
        ---
        scenario=sport
        tbrRate=3ff4cccccccccccd
        tbrMinutes=30
        smb=absent
        eventual=absent
        isf=absent
        virtualCob=absent
        modelWord0=absent
        healthKit=present
        bg=4066800000000000
        delta=4014000000000000
        port=android
        ---
        scenario=night
        tbrRate=0000000000000000
        tbrMinutes=30
        smb=absent
        eventual=405b800000000000
        isf=4049000000000000
        virtualCob=0000000000000000
        modelWord0=absent
        healthKit=present
        bg=405b800000000000
        delta=0000000000000000
        port=android
        ---
        scenario=sensor-gap
        tbrRate=absent
        tbrMinutes=absent
        smb=absent
        eventual=405b800000000000
        isf=absent
        virtualCob=absent
        modelWord0=absent
        healthKit=present
        bg=405b800000000000
        delta=0000000000000000
        port=android
        ---
        scenario=healthkit-absent
        tbrRate=0000000000000000
        tbrMinutes=30
        smb=absent
        eventual=4062200000000000
        isf=4049000000000000
        virtualCob=0000000000000000
        modelWord0=absent
        healthKit=absent
        bg=4061800000000000
        delta=3ff0000000000000
        port=android
        ---
        scenario=healthkit-present
        tbrRate=0000000000000000
        tbrMinutes=30
        smb=absent
        eventual=4062200000000000
        isf=4049000000000000
        virtualCob=0000000000000000
        modelWord0=absent
        healthKit=present
        bg=4061800000000000
        delta=3ff0000000000000
        port=android
    """.trimIndent()
}
