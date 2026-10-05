package app.aaps.plugins.aps.openAPSAIMI.effects

import app.aaps.core.interfaces.aps.AutosensResult
import app.aaps.core.interfaces.aps.CurrentTemp
import app.aaps.core.interfaces.aps.GlucoseStatusAIMI
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.MealData
import app.aaps.core.interfaces.aps.RT
import app.aaps.core.interfaces.notifications.AlarmSound
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.plugins.aps.openAPSAIMI.orchestration.AimiTickContext
import app.aaps.plugins.aps.openAPSAIMI.safety.SafetyDecision
import kotlin.reflect.KClass

/**
 * The locked activity scene, on the ceiling an active session left behind.
 *
 * Same arguments as the Android shell test: protection on, request 2 U, meal-high IOB damping
 * 0.50, microbolus not allowed. The console line is the one `decideInsulinReqActivityRelaxAndMicrobolus`
 * already writes. A ceiling of 0.80 U delivers 0.20 U.
 */
internal fun activityProtectionInsulinReq(maxSmb: Double, consoleLog: MutableList<String>): Double {
    val rT = RT(runningDynamicIsf = false)
    decideInsulinReqActivityRelaxAndMicrobolus(
        ctx = activityTickContext(),
        rT = rT,
        iobTotal = IobTotal(time = 0L, iob = 4.0),
        smbToGive = ActiveTpoActivityScene.SMB_TO_GIVE,
        allowMealHighIob = true,
        mealHighIobDamping = ActiveTpoActivityScene.MEAL_HIGH_IOB_DAMPING,
        maxIobLimit = 2.0,
        safetyDecision = SafetyDecision(stopBasal = false, bolusFactor = 1.0, reason = "", basalLS = false),
        enableSMB = true,
        isMealActive = false,
        bg = 180.0,
        delta = 4.0f,
        hypoThresholdMgdl = 70.0,
        systemTime = 0L,
        basalBoostApplied = false,
        basalBoostSource = null,
        texts = SilentTextResolver,
        consoleLog = consoleLog,
        state = object : AimiInsulinReqState {
            override fun activityProtectionMode() = true
            override fun activityStateIntense() = false
            override fun maxSMB() = maxSmb
            override fun hyperReleaseFloorU() = 0.0
        },
        smbIntervalPort = AimiInsulinReqSmbInterval { 0 },
        finalize = AimiInsulinReqFinalize { _, _, _, _, _, _, _, _, _ -> },
    )
    return rT.insulinReq ?: error("activity insulin request missing")
}

private fun activityTickContext(): AimiTickContext = AimiTickContext(
    glucoseStatus = GlucoseStatusAIMI(glucose = 180.0, delta = 4.0, date = 0L),
    currentTemp = CurrentTemp(duration = 0, rate = 1.0, minutesrunning = 0),
    iobDataArray = arrayOf(IobTotal(time = 0L, iob = 1.0)),
    profile = iosNeutralProfile(),
    autosensData = AutosensResult(),
    mealData = MealData(mealCOB = 0.0),
    microBolusAllowed = false,
    currentTime = 0L,
    flatBGsDetected = false,
    dynIsfMode = false,
    uiInteraction = SilentUi,
    extraDebug = "",
)

private object SilentUi : UiInteraction {
    override val mainActivity: KClass<*> = SilentUi::class
    override val errorHelperActivity: KClass<*> = SilentUi::class
    override fun runAlarm(status: String, title: String, sound: AlarmSound?) = Unit
    override fun stopAlarm(reason: String) = Unit
}

private object SilentTextResolver : TextResolver {
    override fun gs(ref: TextRef): String = ""
    override fun gs(ref: TextRef, vararg args: Any?): String = ""
    override fun gsNotLocalised(ref: TextRef): String = ""
    override fun shortTextMode(): Boolean = false
}
