package app.aaps.plugins.aps.openAPSAIMI

import androidx.compose.runtime.remember
import app.aaps.core.interfaces.concurrent.aapsIoDispatcher
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.maintenance.ImportExportPrefs
import app.aaps.core.interfaces.profile.EffectiveProfile
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.protection.ExportPasswordDataStore
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.sharedPreferences.KeyValueStore
import app.aaps.core.interfaces.stats.TddCalculator
import app.aaps.core.interfaces.stats.TirCalculator
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.interfaces.IntentPreferenceKey
import app.aaps.core.keys.interfaces.PreferenceItem
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.keys.interfaces.withCompose
import app.aaps.core.ui.compose.ComposeScreenContent
import app.aaps.plugins.aps.ApsStrings
import app.aaps.plugins.aps.keys.ApsIntentKey
import app.aaps.plugins.aps.openAPSAIMI.compose.AimiPkpdSettingsScreen
import app.aaps.plugins.aps.openAPSAIMI.advisor.AiCoachingService
import app.aaps.plugins.aps.openAPSAIMI.advisor.AimiAdvisorService
import app.aaps.plugins.aps.openAPSAIMI.advisor.AimiAdvisorStringsTextResolver
import app.aaps.plugins.aps.openAPSAIMI.advisor.AimiSharing
import app.aaps.plugins.aps.openAPSAIMI.advisor.compose.AimiProfileAdvisorScreen
import app.aaps.plugins.aps.openAPSAIMI.advisor.compose.AimiSupportPackageScreen
import app.aaps.plugins.aps.openAPSAIMI.advisor.data.AdvisorHistoryRepository
import app.aaps.plugins.aps.openAPSAIMI.advisor.diag.AimiDiagnosticsManager
import app.aaps.plugins.aps.openAPSAIMI.advisor.diag.AimiSupportPackageExporter
import app.aaps.plugins.aps.openAPSAIMI.advisor.diag.IosAimiDiagPlatform
import app.aaps.plugins.aps.openAPSAIMI.advisor.diag.IosAimiSupportZipFactory
import app.aaps.plugins.aps.openAPSAIMI.advisor.meal.FoodRecognitionService
import app.aaps.plugins.aps.openAPSAIMI.advisor.meal.ui.AimiMealAdvisorScreen
import app.aaps.plugins.aps.openAPSAIMI.advisor.meal.ui.IosMealAdvisorCameraCapture
import app.aaps.plugins.aps.openAPSAIMI.advisor.modesettings.IosAimiModeDurationStore
import app.aaps.plugins.aps.openAPSAIMI.advisor.modesettings.ui.AimiModeSettingsScreen
import app.aaps.plugins.aps.openAPSAIMI.advisor.oref.IosBundleAimiAssetReader
import app.aaps.plugins.aps.openAPSAIMI.context.ContextManager
import app.aaps.plugins.aps.openAPSAIMI.context.ui.AimiContextScreen
import app.aaps.plugins.aps.openAPSAIMI.hormonitor.viewer.HormonitorViewerScreen
import app.aaps.plugins.aps.openAPSAIMI.hormonitor.viewer.IosDateFormatter
import app.aaps.plugins.aps.openAPSAIMI.learning.UnifiedReactivityLearner
import app.aaps.plugins.aps.openAPSAIMI.llm.gemini.GeminiModelResolver
import app.aaps.plugins.aps.openAPSAIMI.permissions.AimiPermissionRow
import app.aaps.plugins.aps.openAPSAIMI.permissions.AimiPermissionStatusScreen
import app.aaps.plugins.aps.openAPSAIMI.physio.AIMIPhysioContextStoreMTR
import app.aaps.plugins.aps.openAPSAIMI.physio.HealthContextRepository
import app.aaps.plugins.aps.openAPSAIMI.physio.IosHealthKitAccess
import app.aaps.plugins.aps.openAPSAIMI.ports.AimiPlatformPreferenceItems
import app.aaps.plugins.aps.openAPSAIMI.ports.AimiPlatformPreferenceSlot
import app.aaps.plugins.aps.openAPSAIMI.sos.IosSosAlertPermissions
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiHttp
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiKeyValueCache
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorage
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.withContext
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString

/**
 * iOS half of [AimiPlatformPreferenceItems].
 *
 * Every slot is answered. A slot whose screen iOS could not honour would be `null`, so the entry
 * would be left out of the settings tree and no dead row would be shown.
 *
 * Answered:
 * - `CONTEXT`: the shared screen uses shared context services. The health snapshot refresh uses
 *   the iOS HealthKit-backed repository.
 * - `MEAL_ADVISOR`: the shared screen uses the shared vision service and an AVFoundation camera
 *   preview supplied by the Swift app bridge.
 * - `HORMONITOR_VIEWER`: the viewer is shared code. It reads the same `Documents/AAPS` files the
 *   engine writes.
 * - `PROFILE_ADVISOR`: the report, history, settings export and advisor screens use shared code.
 *   iOS has no ONNX runtime linked yet, so the OREF risk scores are reported as unavailable.
 * - `SUPPORT_PACKAGE`: the screen and the package are shared code
 *   ([AimiSupportPackageExporter]), so the ZIP holds the same entries as on Android. Only the ZIP
 *   bytes differ: [IosAimiSupportZipFactory] writes STORED (not compressed) entries to the app
 *   temporary directory, and the iOS share sheet sends it. The mail subject date is formatted like
 *   Android's `java.util.Date.toString()`.
 * - `PKPD_SETUP`: the screen is shared code, with the same two loaders as Android: the profile
 *   insulin hint and the advisor recommendations ([AimiAdvisorService] is shared code).
 * - `MODE_SETTINGS`: the screen is shared code ([AimiModeSettingsScreen]). Only the store of the
 *   mode durations is iOS ([IosAimiModeDurationStore]).
 * - `SOS_PERMISSIONS`: iOS cannot send SMS, so the SOS is a local alert on this phone
 *   (`IosAimiEmergencySos`). The entry has an iOS title that says so, and opens a status screen for
 *   the notification and critical alert permissions that alert needs ([IosSosAlertPermissions]).
 *   There is no location row: the iOS alert does not carry a location.
 * - `HEALTH_CONNECT_PERMISSIONS`: Health Connect is Android only. On iOS the entry is
 *   "Apple Health access" and opens a status screen ([IosHealthKitAccess]). HealthKit shows its
 *   sheet only once and never tells an app what was allowed, so after the first answer the screen
 *   can only explain where to change it in the Health app.
 */
@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
class IosAimiPlatformPreferenceItems @Inject constructor(
    private val preferences: Preferences,
    private val persistenceLayer: PersistenceLayer,
    private val profileFunction: ProfileFunction,
    private val storage: AimiStorage,
    private val sharing: AimiSharing,
    // The diagnostic report lists every stored key, also keys Preferences does not know.
    private val keyValueStore: KeyValueStore,
    private val keyValueCache: AimiKeyValueCache,
    private val importExportPrefs: ImportExportPrefs,
    private val exportPasswordDataStore: ExportPasswordDataStore,
    private val aiCoachingService: AiCoachingService,
    private val unifiedReactivityLearner: UnifiedReactivityLearner,
    private val tddCalculator: TddCalculator,
    private val tirCalculator: TirCalculator,
    private val geminiModelResolver: GeminiModelResolver,
    private val aimiHttp: AimiHttp,
    private val contextManager: ContextManager,
    private val healthContextRepository: HealthContextRepository,
    private val physioContextStore: AIMIPhysioContextStoreMTR,
    private val aapsLogger: AAPSLogger,
    private val dateUtil: DateUtil,
    private val textResolver: TextResolver,
) : AimiPlatformPreferenceItems {

    override fun item(slot: AimiPlatformPreferenceSlot): PreferenceItem? = when (slot) {
        AimiPlatformPreferenceSlot.CONTEXT -> ApsIntentKey.AimiContext.withCompose(
            ComposeScreenContent { onBack ->
                AimiContextScreen(
                    contextManager = contextManager,
                    preferences = preferences,
                    healthContextRepository = healthContextRepository,
                    physioContextStore = physioContextStore,
                    aapsLogger = aapsLogger,
                    dateUtil = dateUtil,
                    textResolver = textResolver,
                    onBack = onBack,
                )
            },
        )
        AimiPlatformPreferenceSlot.MEAL_ADVISOR -> ApsIntentKey.AimiMealAdvisor.withCompose(
            ComposeScreenContent { onBack ->
                val recognitionService = remember {
                    FoodRecognitionService(geminiModelResolver, preferences, aapsLogger, aimiHttp)
                }
                AimiMealAdvisorScreen(
                    preferences = preferences,
                    persistenceLayer = persistenceLayer,
                    profileFunction = profileFunction,
                    aapsLogger = aapsLogger,
                    cameraCapture = { onCaptured, onError, onCancel ->
                        IosMealAdvisorCameraCapture(onCaptured, onError, onCancel)
                    },
                    analyzeImage = recognitionService::estimateCarbsFromImage,
                    onBack = onBack,
                )
            },
        )
        AimiPlatformPreferenceSlot.HORMONITOR_VIEWER -> ApsIntentKey.HormonitorViewer.withCompose(
            ComposeScreenContent { onBack ->
                HormonitorViewerScreen(storage = storage, dateFormatter = IosDateFormatter(), onBack = onBack)
            },
        )
        AimiPlatformPreferenceSlot.PROFILE_ADVISOR -> ApsIntentKey.AimiProfileAdvisor.withCompose(
            ComposeScreenContent { onBack ->
                val advisorService = remember { advisorService() }
                AimiProfileAdvisorScreen(
                    preferences = preferences,
                    advisorService = advisorService,
                    historyRepo = AdvisorHistoryRepository(keyValueCache),
                    importExportPrefs = importExportPrefs,
                    exportPasswordDataStore = exportPasswordDataStore,
                    aiCoachingService = aiCoachingService,
                    textResolver = textResolver,
                    storage = storage,
                    sharing = sharing,
                    assetReader = IosBundleAimiAssetReader(),
                    filesDir = storage.directory(),
                    onBack = onBack,
                )
            },
        )
        AimiPlatformPreferenceSlot.SUPPORT_PACKAGE   -> supportPackage()
        AimiPlatformPreferenceSlot.PKPD_SETUP        -> ApsIntentKey.PkpdSetup.withCompose(
            ComposeScreenContent { onBack ->
                AimiPkpdSettingsScreen(
                    preferences = preferences,
                    onBack = onBack,
                    loadProfileInsulin = {
                        val profile = profileFunction.getProfile() as? EffectiveProfile
                        profile?.iCfg?.dia to profile?.iCfg?.peak?.toDouble()
                    },
                    loadPkpdRecommendations = {
                        withContext(aapsIoDispatcher) {
                            advisorService().pkpdRecommendationsForSettings(PKPD_RECOMMENDATION_DAYS)
                        }
                    },
                )
            },
        )
        AimiPlatformPreferenceSlot.MODE_SETTINGS     -> ApsIntentKey.AimiModeSettings.withCompose(
            ComposeScreenContent { onBack ->
                AimiModeSettingsScreen(
                    preferences = preferences,
                    persistenceLayer = persistenceLayer,
                    aapsLogger = aapsLogger,
                    textResolver = textResolver,
                    durationStore = remember { IosAimiModeDurationStore(keyValueCache) },
                    onBack = onBack,
                )
            },
        )
        AimiPlatformPreferenceSlot.SOS_PERMISSIONS   -> sosAlertPermissions()
        AimiPlatformPreferenceSlot.HEALTH_CONNECT_PERMISSIONS -> healthKitAccess()
    }

    private fun advisorService(): AimiAdvisorService = AimiAdvisorService(
        profileFunction = profileFunction,
        persistenceLayer = persistenceLayer,
        preferences = preferences,
        strings = AimiAdvisorStringsTextResolver(textResolver),
        unifiedReactivityLearner = unifiedReactivityLearner,
        tddCalculator = tddCalculator,
        tirCalculator = tirCalculator,
        aapsLogger = aapsLogger,
        storage = storage,
    )

    private fun sosAlertPermissions(): PreferenceItem =
        IosTitledIntentKey(
            delegate = ApsIntentKey.AimiSosPermissions,
            title = ApsStrings.pref_title_aimi_sos_alert_ios,
            summary = ApsStrings.pref_summary_aimi_sos_alert_ios,
        ).withCompose(
            ComposeScreenContent { onBack ->
                val permissions = remember { IosSosAlertPermissions(aapsLogger) }
                AimiPermissionStatusScreen(
                    title = ApsStrings.aimi_sos_ios_screen_title,
                    intro = ApsStrings.aimi_sos_ios_intro,
                    note = ApsStrings.aimi_sos_ios_note,
                    loadRows = { permissions.rows() },
                    ask = { permissions.ask() },
                    openSettings = { openAppSettings() },
                    onBack = onBack,
                )
            },
        )

    private fun healthKitAccess(): PreferenceItem =
        IosTitledIntentKey(
            delegate = ApsIntentKey.AimiHealthConnectPermissions,
            title = ApsStrings.pref_title_aimi_healthkit_access,
            summary = ApsStrings.pref_summary_aimi_healthkit_access,
        ).withCompose(
            ComposeScreenContent { onBack ->
                val access = remember { IosHealthKitAccess(aapsLogger) }
                AimiPermissionStatusScreen(
                    title = ApsStrings.pref_title_aimi_healthkit_access,
                    intro = ApsStrings.aimi_healthkit_intro,
                    note = ApsStrings.aimi_healthkit_note,
                    loadRows = { listOf(AimiPermissionRow(ApsStrings.aimi_healthkit_row_read, access.state())) },
                    ask = { access.ask() },
                    // The app page in iPhone Settings has no Apple Health switch. The note says
                    // where the choice is changed instead.
                    openSettings = null,
                    onBack = onBack,
                )
            },
        )

    /** Opens this app's page in iPhone Settings, where notification choices are changed. */
    private fun openAppSettings() {
        val url = NSURL.URLWithString(UIApplicationOpenSettingsURLString) ?: return
        UIApplication.sharedApplication.openURL(url, options = emptyMap<Any?, Any?>(), completionHandler = null)
    }

    /**
     * The same entry with an iOS title and summary. The key stays the same, so the entry keeps its
     * place in the tree and in search. Only the words change, where the Android text (SMS, Health
     * Connect) would be wrong on iOS.
     */
    private class IosTitledIntentKey(
        delegate: IntentPreferenceKey,
        override val title: TextRef,
        override val summary: TextRef?,
    ) : IntentPreferenceKey by delegate

    private fun supportPackage(): PreferenceItem =
        ApsIntentKey.AimiSupportPackage.withCompose(
            ComposeScreenContent { onBack ->
                val diagPlatform = remember { IosAimiDiagPlatform() }
                val exporter = remember {
                    AimiSupportPackageExporter(
                        platform = diagPlatform,
                        preferences = preferences,
                        logger = aapsLogger,
                        profileFunction = profileFunction,
                        textResolver = textResolver,
                        storage = storage,
                        sharing = sharing,
                        zipFactory = IosAimiSupportZipFactory(),
                        subjectTime = { IosDateFormatter().format(aimiWallClockMs(), JAVA_DATE_TO_STRING) },
                        allSettings = { keyValueStore.getAll() },
                    )
                }
                AimiSupportPackageScreen(
                    onBack = onBack,
                    verifyCode = { code -> AimiDiagnosticsManager.verifyCode(code, diagPlatform) },
                    buildPackage = { issue -> exporter.build(issue) },
                    sharePackage = { zip, issue -> exporter.share(zip, issue) },
                )
            },
        )

    internal companion object {

        /** The pattern of `java.util.Date.toString()`, which Android puts in the mail subject. */
        private const val JAVA_DATE_TO_STRING = "EEE MMM dd HH:mm:ss zzz yyyy"

        /** Days of history the PK/PD advisor recommendations look at, as on Android. */
        private const val PKPD_RECOMMENDATION_DAYS = 7

        /** The slots this class answers. A test pins this, so a new slot is a decision. */
        val ANSWERED: Set<AimiPlatformPreferenceSlot> = setOf(
            AimiPlatformPreferenceSlot.CONTEXT,
            AimiPlatformPreferenceSlot.HORMONITOR_VIEWER,
            AimiPlatformPreferenceSlot.PKPD_SETUP,
            AimiPlatformPreferenceSlot.MEAL_ADVISOR,
            AimiPlatformPreferenceSlot.PROFILE_ADVISOR,
            AimiPlatformPreferenceSlot.SUPPORT_PACKAGE,
            AimiPlatformPreferenceSlot.MODE_SETTINGS,
            AimiPlatformPreferenceSlot.SOS_PERMISSIONS,
            AimiPlatformPreferenceSlot.HEALTH_CONNECT_PERMISSIONS,
        )
    }
}
