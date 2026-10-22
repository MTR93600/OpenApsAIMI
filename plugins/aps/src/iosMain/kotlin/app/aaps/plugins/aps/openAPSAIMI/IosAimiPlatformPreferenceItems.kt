package app.aaps.plugins.aps.openAPSAIMI

import androidx.compose.runtime.remember
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.maintenance.ImportExportPrefs
import app.aaps.core.interfaces.profile.EffectiveProfile
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.protection.ExportPasswordDataStore
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.stats.TddCalculator
import app.aaps.core.interfaces.stats.TirCalculator
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.interfaces.PreferenceItem
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.interfaces.withCompose
import app.aaps.core.ui.compose.ComposeScreenContent
import app.aaps.plugins.aps.keys.ApsIntentKey
import app.aaps.plugins.aps.openAPSAIMI.compose.AimiPkpdSettingsScreen
import app.aaps.plugins.aps.openAPSAIMI.advisor.AiCoachingService
import app.aaps.plugins.aps.openAPSAIMI.advisor.AimiAdvisorService
import app.aaps.plugins.aps.openAPSAIMI.advisor.AimiAdvisorStringsTextResolver
import app.aaps.plugins.aps.openAPSAIMI.advisor.AimiSharing
import app.aaps.plugins.aps.openAPSAIMI.advisor.compose.AimiProfileAdvisorScreen
import app.aaps.plugins.aps.openAPSAIMI.advisor.data.AdvisorHistoryRepository
import app.aaps.plugins.aps.openAPSAIMI.advisor.meal.FoodRecognitionService
import app.aaps.plugins.aps.openAPSAIMI.advisor.meal.ui.AimiMealAdvisorScreen
import app.aaps.plugins.aps.openAPSAIMI.advisor.meal.ui.IosMealAdvisorCameraCapture
import app.aaps.plugins.aps.openAPSAIMI.advisor.oref.IosBundleAimiAssetReader
import app.aaps.plugins.aps.openAPSAIMI.context.ContextManager
import app.aaps.plugins.aps.openAPSAIMI.context.ui.AimiContextScreen
import app.aaps.plugins.aps.openAPSAIMI.hormonitor.viewer.HormonitorViewerScreen
import app.aaps.plugins.aps.openAPSAIMI.hormonitor.viewer.IosDateFormatter
import app.aaps.plugins.aps.openAPSAIMI.learning.UnifiedReactivityLearner
import app.aaps.plugins.aps.openAPSAIMI.llm.gemini.GeminiModelResolver
import app.aaps.plugins.aps.openAPSAIMI.physio.AIMIPhysioContextStoreMTR
import app.aaps.plugins.aps.openAPSAIMI.physio.HealthContextRepository
import app.aaps.plugins.aps.openAPSAIMI.ports.AimiPlatformPreferenceItems
import app.aaps.plugins.aps.openAPSAIMI.ports.AimiPlatformPreferenceSlot
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiHttp
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiKeyValueCache
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorage
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * iOS half of [AimiPlatformPreferenceItems].
 *
 * A slot is answered only when its screen already exists in shared code. Every other slot is
 * `null`, so the entry is left out of the settings tree and no dead row is shown.
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
 * - `PKPD_SETUP`: the screen is shared code. Android also passes a loader for advisor
 *   recommendations, which is built on Android classes (`AimiAdvisorService`). iOS passes none,
 *   and the screen then shows its settings without the recommendations block. The profile insulin
 *   hint is passed, because `ProfileFunction` is shared.
 *
 * Not answered (`null`):
 * - `SUPPORT_PACKAGE`: the screen is shared, but building and sharing the zip is Android code.
 * - `MODE_SETTINGS`: this screen is not yet registered on iOS.
 * - `SOS_PERMISSIONS`, `HEALTH_CONNECT_PERMISSIONS`: Android permission screens. iOS asks for
 *   these permissions in its own system prompts.
 */
@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
class IosAimiPlatformPreferenceItems @Inject constructor(
    private val preferences: Preferences,
    private val persistenceLayer: PersistenceLayer,
    private val profileFunction: ProfileFunction,
    private val storage: AimiStorage,
    private val sharing: AimiSharing,
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
                val advisorService = remember {
                    AimiAdvisorService(
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
                }
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
        AimiPlatformPreferenceSlot.PKPD_SETUP        -> ApsIntentKey.PkpdSetup.withCompose(
            ComposeScreenContent { onBack ->
                AimiPkpdSettingsScreen(
                    preferences = preferences,
                    onBack = onBack,
                    loadProfileInsulin = {
                        val profile = profileFunction.getProfile() as? EffectiveProfile
                        profile?.iCfg?.dia to profile?.iCfg?.peak?.toDouble()
                    },
                )
            },
        )
        else                                         -> null
    }

    internal companion object {

        /** The slots this class answers. A test pins this, so a new slot is a decision. */
        val ANSWERED: Set<AimiPlatformPreferenceSlot> = setOf(
            AimiPlatformPreferenceSlot.CONTEXT,
            AimiPlatformPreferenceSlot.HORMONITOR_VIEWER,
            AimiPlatformPreferenceSlot.PKPD_SETUP,
            AimiPlatformPreferenceSlot.MEAL_ADVISOR,
            AimiPlatformPreferenceSlot.PROFILE_ADVISOR,
        )
    }
}
