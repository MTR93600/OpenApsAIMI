package app.aaps.pump.omnipod.dashctl.driver

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.utils.Round
import app.aaps.pump.omnipod.dashctl.pod.command.GetStatusCommand
import app.aaps.pump.omnipod.dashctl.pod.command.GetVersionCommand
import app.aaps.pump.omnipod.dashctl.pod.command.ProgramAlertsCommand
import app.aaps.pump.omnipod.dashctl.pod.command.ProgramBasalCommand
import app.aaps.pump.omnipod.dashctl.pod.command.ProgramBolusCommand
import app.aaps.pump.omnipod.dashctl.pod.command.SetUniqueIdCommand
import app.aaps.pump.omnipod.dashctl.pod.command.base.Command
import app.aaps.pump.omnipod.dashctl.pod.definition.ActivationProgress
import app.aaps.pump.omnipod.dashctl.pod.definition.AlertConfiguration
import app.aaps.pump.omnipod.dashctl.pod.definition.AlertTrigger
import app.aaps.pump.omnipod.dashctl.pod.definition.AlertType
import app.aaps.pump.omnipod.dashctl.pod.definition.BasalProgram
import app.aaps.pump.omnipod.dashctl.pod.definition.BeepRepetitionType
import app.aaps.pump.omnipod.dashctl.pod.definition.BeepType
import app.aaps.pump.omnipod.dashctl.pod.definition.PodConstants
import app.aaps.pump.omnipod.dashctl.pod.definition.PodStatus
import app.aaps.pump.omnipod.dashctl.pod.definition.ProgramReminder
import app.aaps.pump.omnipod.dashctl.pod.response.ResponseType
import app.aaps.pump.omnipod.dashctl.session.DashPodStateManager

/** An activation step that could not be done. The activation stops; the stored progress says where. */
class DashActivationException(message: String) : Exception(message)

/**
 * The result of one exchange with the pod.
 *
 * @property reachedRadio false only when nothing could reach the pod (the write failed before any
 *   byte left). Then the pod cannot have the command.
 * @property error null when the answer of the pod was read.
 */
class DashExchange(val reachedRadio: Boolean, val error: String?)

/**
 * What the activation needs from the pump. Called with the command lock of the pump held, on a
 * session that is set up.
 */
interface DashActivationPod {

    /**
     * Builds one command with the stored unique id and 4-bit sequence, runs [beforeSend], sends the
     * command and reads its answer into the pod state (`DashPodStateManager.updateFromResponse`), as
     * Android `bleManager.sendCommand` with the `PodEventInterceptor`.
     */
    fun send(build: (uniqueId: Int, sequence: Short) -> Command, beforeSend: () -> Unit = {}): DashExchange
}

/**
 * The activation of a new pod, in two parts, as Android `OmnipodDashManagerImpl.activatePodPart1`
 * and `activatePodPart2`. Pairing (the LTK) and the session are set up by the pump before part 1
 * (Android `pairNewPod` inside part 1); the connection is held by the pump (Android
 * `observeConnectToPod` is a step of its own there).
 *
 * Each step runs only when the stored [ActivationProgress] is before it, and stores its progress
 * when the answer of the pod was read, so a crash or an error resumes where it stopped. Part 1, in
 * Android order: GetVersion (GOT_POD_VERSION), SetUniqueId (SET_UNIQUE_ID), the low reservoir alert
 * when asked (PROGRAMMED_LOW_RESERVOIR_ALERTS), the lump of coal alert (REPROGRAMMED_LUMP_OF_COAL_ALERT),
 * the prime bolus (PRIMING), a wait of one second per prime pulse, a status that must show
 * CLUTCH_DRIVE_ENGAGED (PRIME_COMPLETED), then PHASE_1_COMPLETED. Part 2: the basal program
 * (PROGRAMMED_BASAL), the expiration alerts (UPDATED_EXPIRATION_ALERTS), the cannula bolus
 * (INSERTING_CANNULA), a wait of one second per pulse, a status that must show
 * RUNNING_ABOVE_MIN_VOLUME (CANNULA_INSERTED), then COMPLETED. Beeps are off, as all the
 * activation commands of Android (the alerts beep, as on Android: they are the pod alarms).
 *
 * As Android, the type of the answer is not checked: a NAK to a step is taken as an answer, and the
 * status check after the prime or the cannula bolus then stops the activation.
 *
 * Deliberate difference from Android (a bolus sent twice): the prime and the cannula bolus are
 * marked in the stored state before their bytes go ([DashPodStateManager.markActivationStepSent]),
 * and the mark is cleared in the same write that stores their progress. When an activation finds
 * a mark (the answer was lost, or the app stopped after the bytes went), it reads a status first and
 * lets the pod status decide ([resolveStepInFlight]). Android sends the bolus again: the cannula
 * bolus goes under the skin, so a second one is insulin the user gets twice.
 *
 * Deliberate difference from Android: the basal program the pod took becomes the running program in
 * the same write that stores PROGRAMMED_BASAL. Android stores the program given to the last call of
 * part 2 once the whole part 2 is done, so after a resume with another profile it would keep a
 * program the pod does not run, and `isThisProfileSet` would never send the right one.
 *
 * The timer is [sleep], so a test runs the waits on virtual time.
 */
class DashActivation(
    private val state: DashPodStateManager,
    private val pod: DashActivationPod,
    private val logger: AAPSLogger,
    private val now: () -> Long,
    private val sleep: suspend (Long) -> Unit,
) {

    /**
     * Android `activatePodPart1`. Throws [DashActivationException] when a step fails; the steps done
     * before stay stored.
     *
     * @param lowReservoirAlertTrigger the low reservoir alert, or null for none (Android: only when the
     *   user turned it on, at units x 10 microliters).
     */
    suspend fun activatePart1(lowReservoirAlertTrigger: AlertTrigger.ReservoirVolumeTrigger?) {
        if (!state.activationProgress.isBefore(ActivationProgress.PHASE_1_COMPLETED)) {
            throw DashActivationException("Pod is in an incorrect state")
        }
        resolveStepInFlight()
        if (isBefore(ActivationProgress.GOT_POD_VERSION)) {
            step(ActivationProgress.GOT_POD_VERSION, "GetVersion") { _, seq ->
                GetVersionCommand.Builder()
                    .setSequenceNumber(seq)
                    .setUniqueId(GetVersionCommand.DEFAULT_UNIQUE_ID)
                    .build()
            }
        }
        if (isBefore(ActivationProgress.SET_UNIQUE_ID)) {
            val lotNumber = state.lotNumber ?: throw DashActivationException("Lot number of the pod not known")
            val podSequenceNumber = state.podSequenceNumber ?: throw DashActivationException("Sequence number of the pod not known")
            step(ActivationProgress.SET_UNIQUE_ID, "SetUniqueId") { uid, seq ->
                SetUniqueIdCommand.Builder()
                    .setSequenceNumber(seq)
                    .setUniqueId(uid)
                    .setLotNumber(lotNumber.toInt())
                    .setPodSequenceNumber(podSequenceNumber.toInt())
                    .setInitializationTime(now())
                    .build()
            }
        }
        if (lowReservoirAlertTrigger != null && isBefore(ActivationProgress.PROGRAMMED_LOW_RESERVOIR_ALERTS)) {
            val alert = AlertConfiguration(
                AlertType.LOW_RESERVOIR,
                enabled = true,
                durationInMinutes = 0,
                autoOff = false,
                lowReservoirAlertTrigger,
                BeepType.FOUR_TIMES_BIP_BEEP,
                BeepRepetitionType.XXX
            )
            step(ActivationProgress.PROGRAMMED_LOW_RESERVOIR_ALERTS, "low reservoir alert") { uid, seq ->
                alertsCommand(listOf(alert), multiCommandFlag = false, uid, seq)
            }
        }
        if (isBefore(ActivationProgress.REPROGRAMMED_LUMP_OF_COAL_ALERT)) {
            val alert = AlertConfiguration(
                AlertType.EXPIRATION,
                enabled = true,
                durationInMinutes = 55,
                autoOff = false,
                AlertTrigger.TimerTrigger(5),
                BeepType.FOUR_TIMES_BIP_BEEP,
                BeepRepetitionType.XXX5
            )
            step(ActivationProgress.REPROGRAMMED_LUMP_OF_COAL_ALERT, "lump of coal alert") { uid, seq ->
                alertsCommand(listOf(alert), multiCommandFlag = false, uid, seq)
            }
        }
        if (isBefore(ActivationProgress.PRIMING)) {
            val pulses = state.firstPrimeBolusVolume ?: throw DashActivationException("Prime volume of the pod not known")
            val pulseRate = state.primePulseRate ?: throw DashActivationException("Prime pulse rate of the pod not known")
            bolusStep(ActivationProgress.PRIMING, "prime", pulses, pulseRate)
            // Android: the connection can time out while the pod primes; it connects again after it.
            sleep(pulses * ONE_SECOND_MS)
        }
        if (isBefore(ActivationProgress.PRIME_COMPLETED)) {
            verifyPodStatus(PodStatus.CLUTCH_DRIVE_ENGAGED)
            state.updateActivationProgress(ActivationProgress.PRIME_COMPLETED)
        }
        state.updateActivationProgress(ActivationProgress.PHASE_1_COMPLETED)
    }

    /**
     * Android `activatePodPart2`. Throws [DashActivationException] when a step fails; the steps done
     * before stay stored.
     *
     * @param basalProgram the program to start (Android: the profile running now, mapped).
     * @param userConfiguredExpirationReminderHours hours before the end of the pod for the reminder,
     *   null when the user turned it off.
     * @param userConfiguredExpirationAlarmHours hours before the end of the pod for the expiration
     *   alarm, null when the user turned it off.
     */
    suspend fun activatePart2(
        basalProgram: BasalProgram,
        userConfiguredExpirationReminderHours: Long?,
        userConfiguredExpirationAlarmHours: Long?,
    ) {
        val progress = state.activationProgress
        if (!progress.isAtLeast(ActivationProgress.PHASE_1_COMPLETED) || !progress.isBefore(ActivationProgress.COMPLETED)) {
            throw DashActivationException("Pod is in an incorrect state")
        }
        resolveStepInFlight()
        if (isBefore(ActivationProgress.PROGRAMMED_BASAL)) {
            val sent = BasalProgram(basalProgram.segments)
            val exchange = pod.send({ uid, seq -> basalProgramCommand(sent, uid, seq) })
            exchange.error?.let { throw DashActivationException("Basal program: $it") }
            state.updateActivationProgress(ActivationProgress.PROGRAMMED_BASAL, basalProgram = sent)
        }
        if (isBefore(ActivationProgress.UPDATED_EXPIRATION_ALERTS)) {
            val alerts = expirationAlerts(userConfiguredExpirationReminderHours, userConfiguredExpirationAlarmHours)
            step(ActivationProgress.UPDATED_EXPIRATION_ALERTS, "expiration alerts") { uid, seq ->
                alertsCommand(alerts, multiCommandFlag = true, uid, seq)
            }
        }
        if (isBefore(ActivationProgress.INSERTING_CANNULA)) {
            val pulses = state.secondPrimeBolusVolume ?: throw DashActivationException("Cannula volume of the pod not known")
            val pulseRate = state.primePulseRate ?: throw DashActivationException("Prime pulse rate of the pod not known")
            bolusStep(ActivationProgress.INSERTING_CANNULA, "cannula insertion", pulses, pulseRate)
            sleep(pulses * ONE_SECOND_MS)
        }
        if (isBefore(ActivationProgress.CANNULA_INSERTED)) {
            verifyPodStatus(PodStatus.RUNNING_ABOVE_MIN_VOLUME)
            state.updateActivationProgress(ActivationProgress.CANNULA_INSERTED)
        }
        state.updateActivationProgress(ActivationProgress.COMPLETED)
    }

    /**
     * A bolus may have reached the pod in an activation that did not finish (the mark of
     * [DashPodStateManager.markActivationStepSent] is still stored). A status is read, and the pod
     * status decides, before anything else is sent:
     * - the prime: UID_SET means the pod did not take it, so it is sent; ENGAGING_CLUTCH_DRIVE or
     *   CLUTCH_DRIVE_ENGAGED means it took it, so PRIMING is stored and the status check follows.
     * - the cannula bolus: BASAL_PROGRAM_SET means the pod did not take it, so it is sent; PRIMING or
     *   a running status means it took it, so INSERTING_CANNULA is stored and the status check follows.
     * Any other status, or no status read: the activation stops and the mark stays (fail closed).
     *
     * Only a status this read stored decides. An answer with no status (a NAK, likely after a lost
     * answer left the message sequence out of step) leaves the stored pod status as it was before
     * the bolus, which is exactly the "not taken" one: deciding on it would send the bolus twice.
     */
    private fun resolveStepInFlight() {
        val step = state.activationStepInFlight ?: return
        logger.info(LTag.PUMP, "Activation step $step may have reached the pod: reading the pod status first")
        if (!readStatus()) {
            throw DashActivationException("No pod status in the answer: $step may have reached the pod, it is not sent again")
        }
        val podStatus = state.reportedPodStatus
        val (notTaken, taken) = when (step) {
            ActivationProgress.PRIMING -> setOf(PodStatus.UID_SET) to
                setOf(PodStatus.ENGAGING_CLUTCH_DRIVE, PodStatus.CLUTCH_DRIVE_ENGAGED)

            else                       -> setOf(PodStatus.BASAL_PROGRAM_SET) to
                setOf(PodStatus.PRIMING, PodStatus.RUNNING_ABOVE_MIN_VOLUME, PodStatus.RUNNING_BELOW_MIN_VOLUME)
        }
        when (podStatus) {
            in notTaken -> {
                logger.info(LTag.PUMP, "The pod did not take $step (pod status $podStatus): it is sent again")
                state.clearActivationStepInFlight()
            }

            in taken    -> {
                logger.info(LTag.PUMP, "The pod took $step (pod status $podStatus): it is not sent again")
                state.updateActivationProgress(step)
            }

            else        -> throw DashActivationException("Unexpected Pod status: got $podStatus after $step was sent")
        }
    }

    private fun isBefore(progress: ActivationProgress): Boolean = state.activationProgress.isBefore(progress)

    /** One command; its progress is stored once the answer was read. */
    private fun step(progress: ActivationProgress, name: String, build: (uniqueId: Int, sequence: Short) -> Command) {
        val exchange = pod.send(build)
        exchange.error?.let { throw DashActivationException("$name: $it") }
        state.updateActivationProgress(progress)
    }

    /**
     * The prime or the cannula bolus, marked before its bytes go. A write that failed before any byte
     * left clears the mark; a lost answer keeps it.
     */
    private fun bolusStep(progress: ActivationProgress, name: String, pulses: Short, pulseRate: Short) {
        val units = Round.roundTo(pulses * PodConstants.POD_PULSE_BOLUS_UNITS, PodConstants.POD_PULSE_BOLUS_UNITS)
        logger.info(LTag.PUMP, "Activation: $name bolus of $units U")
        val exchange = pod.send(
            { uid, seq ->
                ProgramBolusCommand.Builder()
                    .setUniqueId(uid)
                    .setSequenceNumber(seq)
                    .setNonce(NONCE)
                    .setNumberOfUnits(units)
                    .setDelayBetweenPulsesInEighthSeconds(pulseRate.toByte())
                    .setProgramReminder(ProgramReminder(atStart = false, atEnd = false, atInterval = 0))
                    .build()
            },
            beforeSend = { state.markActivationStepSent(progress) }
        )
        if (!exchange.reachedRadio) state.clearActivationStepInFlight()
        exchange.error?.let { throw DashActivationException("$name: $it") }
        state.updateActivationProgress(progress)
    }

    /**
     * Android `observeVerifyPrime` and `observeVerifyCannulaInsertion`: a status, then its pod status.
     * As Android, the pod status stored last is checked, also when this read gave a NAK: the bolus
     * answer stored before it then decides. Kept for parity: it can store a progress early, but it
     * sends nothing twice.
     */
    private fun verifyPodStatus(expected: PodStatus) {
        readStatus()
        val podStatus = state.reportedPodStatus
        if (podStatus != expected) {
            throw DashActivationException("Unexpected Pod status: got $podStatus, expected $expected")
        }
    }

    /**
     * Sends a status read. Throws when its answer could not be read. Returns true when the answer was
     * a status (stored by the pod state), false for an answer with no status (a NAK).
     */
    private fun readStatus(): Boolean {
        val statusesBefore = state.statusResponseCount
        val exchange = pod.send(::statusCommand)
        exchange.error?.let { throw DashActivationException("Pod status: $it") }
        return state.statusResponseCount > statusesBefore
    }

    /**
     * The three expiration alerts of Android `createActivationPart2Observables`, with the same values.
     * The pod life left is from now to `DashPodStateManager.expiryMs` (Android `expiry`). Minutes are
     * whole minutes as Java `Duration.toMinutes` gives them.
     */
    private fun expirationAlerts(reminderHours: Long?, alarmHours: Long?): List<AlertConfiguration> {
        val expiry = state.expiryMs ?: throw DashActivationException("Pod expiry not known")
        val podLifeLeftMs = expiry - now()

        val expirationAlarmEnabled = alarmHours != null && alarmHours > 0
        val alarmHoursOrDefault = alarmHours ?: PodConstants.POD_EXPIRATION_ALERT_HOURS_REMAINING_DEFAULT
        val expirationAlarmDelayMs = podLifeLeftMs - alarmHoursOrDefault * MS_PER_HOUR + GRACE_HOURS * MS_PER_HOUR
        val expirationImminentDelayMs =
            podLifeLeftMs - PodConstants.POD_EXPIRATION_IMMINENT_ALERT_HOURS_REMAINING * MS_PER_HOUR + GRACE_HOURS * MS_PER_HOUR

        val alerts = mutableListOf(
            AlertConfiguration(
                AlertType.EXPIRATION,
                enabled = expirationAlarmEnabled,
                durationInMinutes = (alarmHoursOrDefault * MINUTES_PER_HOUR - 60).toShort(),
                autoOff = false,
                AlertTrigger.TimerTrigger(wholeMinutes(expirationAlarmDelayMs).toShort()),
                BeepType.FOUR_TIMES_BIP_BEEP,
                BeepRepetitionType.XXX3
            ),
            AlertConfiguration(
                AlertType.EXPIRATION_IMMINENT,
                enabled = expirationAlarmEnabled,
                durationInMinutes = 0,
                autoOff = false,
                AlertTrigger.TimerTrigger(wholeMinutes(expirationImminentDelayMs).toShort()),
                BeepType.FOUR_TIMES_BIP_BEEP,
                BeepRepetitionType.XXX4
            )
        )
        val userExpiryReminderEnabled = reminderHours != null && reminderHours > 0
        val userExpiryReminderDelayMs =
            podLifeLeftMs - (reminderHours ?: (PodConstants.MAX_POD_LIFETIME.inWholeHours + 1)) * MS_PER_HOUR
        if (userExpiryReminderDelayMs < 0) {
            logger.warn(LTag.PUMPBTCOMM, "activation part 2: negative expiryAlertDuration=$userExpiryReminderDelayMs ms")
        } else {
            alerts.add(
                AlertConfiguration(
                    AlertType.USER_SET_EXPIRATION,
                    enabled = userExpiryReminderEnabled,
                    durationInMinutes = 0,
                    autoOff = false,
                    AlertTrigger.TimerTrigger(wholeMinutes(userExpiryReminderDelayMs).toShort()),
                    BeepType.FOUR_TIMES_BIP_BEEP,
                    BeepRepetitionType.EVERY_MINUTE_AND_EVERY_15_MIN
                )
            )
        }
        return alerts
    }

    /**
     * Android `observeSendProgramBasalCommand` with `hasBasalBeepEnabled` false. The current time is
     * read when the command is built, as Android reads `Date()` there.
     */
    private fun basalProgramCommand(basalProgram: BasalProgram, uid: Int, seq: Short): Command =
        ProgramBasalCommand.Builder()
            .setUniqueId(uid)
            .setSequenceNumber(seq)
            .setNonce(NONCE)
            .setProgramReminder(ProgramReminder(atStart = false, atEnd = false, atInterval = 0))
            .setBasalProgram(basalProgram)
            .setCurrentTime(now())
            .build()

    private fun alertsCommand(alerts: List<AlertConfiguration>, multiCommandFlag: Boolean, uid: Int, seq: Short): Command =
        ProgramAlertsCommand.Builder()
            .setUniqueId(uid)
            .setSequenceNumber(seq)
            .setNonce(NONCE)
            .setAlertConfigurations(alerts)
            .setMultiCommandFlag(multiCommandFlag)
            .build()

    companion object {

        /** `OmnipodDashManagerImpl.NONCE`: the Omnipod Dash seems to use a fixed nonce. */
        const val NONCE = 1229869870

        private const val ONE_SECOND_MS = 1000L
        private const val MS_PER_HOUR = 3_600_000L
        private const val MINUTES_PER_HOUR = 60L

        /** Android adds 8 hours to the expiration alarms "for grace period". */
        private const val GRACE_HOURS = 8L

        /** A default status read (Android `observeSendGetPodStatusCommand`). */
        fun statusCommand(uid: Int, seq: Short): Command =
            GetStatusCommand.Builder()
                .setUniqueId(uid)
                .setSequenceNumber(seq)
                .setStatusResponseType(ResponseType.StatusResponseType.DEFAULT_STATUS_RESPONSE)
                .build()

        /** Java `Duration.ofMillis(ms).toMinutes()`: whole seconds rounded down, then minutes toward zero. */
        fun wholeMinutes(ms: Long): Long = ms.floorDiv(1000L) / 60
    }
}
