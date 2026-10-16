package app.aaps.plugins.aps.openAPSAIMI

import android.content.Context
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.maintenance.ImportExportPrefs
import app.aaps.core.interfaces.profile.EffectiveProfile
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.protection.ExportPasswordDataStore
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.stats.TddCalculator
import app.aaps.core.interfaces.stats.TirCalculator
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.interfaces.PreferenceItem
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.interfaces.withCompose
import app.aaps.core.ui.compose.ComposeScreenContent
import app.aaps.plugins.aps.keys.ApsIntentKey
import app.aaps.plugins.aps.openAPSAIMI.advisor.AiCoachingService
import app.aaps.plugins.aps.openAPSAIMI.advisor.AimiAdvisorService
import app.aaps.plugins.aps.openAPSAIMI.advisor.AimiAdvisorStringsAndroid
import app.aaps.plugins.aps.openAPSAIMI.advisor.AimiSharing
import app.aaps.plugins.aps.openAPSAIMI.advisor.compose.AimiProfileAdvisorScreen
import app.aaps.plugins.aps.openAPSAIMI.advisor.compose.AimiSupportPackageScreen
import app.aaps.plugins.aps.openAPSAIMI.advisor.data.AdvisorHistoryRepository
import app.aaps.plugins.aps.openAPSAIMI.advisor.diag.AimiDiagnosticsManager
import app.aaps.plugins.aps.openAPSAIMI.advisor.diag.AimiSupportPackageExporter
import app.aaps.plugins.aps.openAPSAIMI.advisor.diag.AndroidAimiDiagPlatform
import app.aaps.plugins.aps.openAPSAIMI.advisor.meal.ui.AimiMealAdvisorScreen
import app.aaps.plugins.aps.openAPSAIMI.advisor.modesettings.ui.AimiModeSettingsScreen
import app.aaps.plugins.aps.openAPSAIMI.compose.AimiPkpdSettingsScreen
import app.aaps.plugins.aps.openAPSAIMI.context.ContextManager
import app.aaps.plugins.aps.openAPSAIMI.context.ui.AimiContextScreen
import app.aaps.plugins.aps.openAPSAIMI.hormonitor.viewer.AndroidDateFormatter
import app.aaps.plugins.aps.openAPSAIMI.hormonitor.viewer.HormonitorViewerScreen
import app.aaps.plugins.aps.openAPSAIMI.learning.UnifiedReactivityLearner
import app.aaps.plugins.aps.openAPSAIMI.llm.gemini.GeminiModelResolver
import app.aaps.plugins.aps.openAPSAIMI.physio.AIMIPhysioContextStoreMTR
import app.aaps.plugins.aps.openAPSAIMI.physio.AimiHealthConnectPermissionScreen
import app.aaps.plugins.aps.openAPSAIMI.physio.HealthContextRepository
import app.aaps.plugins.aps.openAPSAIMI.ports.AimiPlatformPreferenceItems
import app.aaps.plugins.aps.openAPSAIMI.ports.AimiPlatformPreferenceSlot
import app.aaps.plugins.aps.openAPSAIMI.sos.AimiSosPermissionScreen
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiBackupManager
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiHttp
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiKeyValueCache
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorage
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android half of [AimiPlatformPreferenceItems]: the AIMI settings entries whose screens are still
 * built on Android classes. The code of each entry was moved here unchanged from the plugin.
 */
@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
class AndroidAimiPlatformPreferenceItems @Inject constructor(
    private val context: Context,
    private val rh: ResourceHelper,
    private val preferences: Preferences,
    private val aapsLogger: AAPSLogger,
    private val dateUtil: DateUtil,
    private val profileFunction: ProfileFunction,
    private val persistenceLayer: PersistenceLayer,
    private val storage: AimiStorage,
    private val sharing: AimiSharing,
    private val contextManager: ContextManager,
    private val healthContextRepository: HealthContextRepository,
    private val physioContextStore: AIMIPhysioContextStoreMTR,
    private val geminiModelResolver: GeminiModelResolver,
    private val aimiHttp: AimiHttp,
    private val unifiedReactivityLearner: UnifiedReactivityLearner,
    private val tddCalculator: TddCalculator,
    private val tirCalculator: TirCalculator,
    private val keyValueCache: AimiKeyValueCache,
    private val importExportPrefs: ImportExportPrefs,
    private val exportPasswordDataStore: ExportPasswordDataStore,
    private val aiCoachingService: AiCoachingService,
    // Kept for its construction, not for any call. AimiBackupManager.init subscribes to
    // EventAimiCloudBackupTrigger on the rx bus, and nothing else asks for the manager, so it only
    // starts listening if something builds it. The plugin used to take it as a constructor
    // parameter for this reason. It moved here when the plugin moved to shared code: this class is
    // Android only and the plugin asks for it, so the manager is still built at the same moment.
    @Suppress("unused") private val aimiBackupManager: AimiBackupManager,
) : AimiPlatformPreferenceItems {

    override fun item(slot: AimiPlatformPreferenceSlot): PreferenceItem? = when (slot) {
        AimiPlatformPreferenceSlot.SUPPORT_PACKAGE            -> supportPackage()
        AimiPlatformPreferenceSlot.CONTEXT                    -> contextScreen()
        AimiPlatformPreferenceSlot.MEAL_ADVISOR               -> mealAdvisor()
        AimiPlatformPreferenceSlot.MODE_SETTINGS              -> modeSettings()
        AimiPlatformPreferenceSlot.PROFILE_ADVISOR            -> profileAdvisor()
        AimiPlatformPreferenceSlot.SOS_PERMISSIONS            -> ApsIntentKey.AimiSosPermissions.withCompose(
            ComposeScreenContent { onBack -> AimiSosPermissionScreen(onBack = onBack) },
        )
        AimiPlatformPreferenceSlot.HEALTH_CONNECT_PERMISSIONS -> ApsIntentKey.AimiHealthConnectPermissions.withCompose(
            ComposeScreenContent { onBack -> AimiHealthConnectPermissionScreen(onBack = onBack) },
        )
        AimiPlatformPreferenceSlot.HORMONITOR_VIEWER          -> ApsIntentKey.HormonitorViewer.withCompose(
            ComposeScreenContent { onBack ->
                HormonitorViewerScreen(storage = storage, dateFormatter = AndroidDateFormatter(), onBack = onBack)
            }
        )
        AimiPlatformPreferenceSlot.PKPD_SETUP                 -> pkpdSetup()
    }

    private fun supportPackage(): PreferenceItem =
        ApsIntentKey.AimiSupportPackage.withCompose(
            ComposeScreenContent { onBack ->
                val exporter = AimiSupportPackageExporter(
                    context = context,
                    preferences = preferences,
                    logger = aapsLogger,
                    profileFunction = profileFunction,
                    rh = rh,
                    storage = storage,
                    sharing = sharing,
                )
                AimiSupportPackageScreen(
                    onBack = onBack,
                    verifyCode = { code -> AimiDiagnosticsManager.verifyCode(code, AndroidAimiDiagPlatform(context)) },
                    buildPackage = { issue -> exporter.build(issue) },
                    sharePackage = { zip, issue -> exporter.share(zip, issue) },
                )
            },
        )

    private fun contextScreen(): PreferenceItem =
        ApsIntentKey.AimiContext.withCompose(
            ComposeScreenContent { onBack ->
                AimiContextScreen(
                    contextManager = contextManager,
                    preferences = preferences,
                    healthContextRepository = healthContextRepository,
                    physioContextStore = physioContextStore,
                    aapsLogger = aapsLogger,
                    dateUtil = dateUtil,
                    textResolver = rh,
                    onBack = onBack,
                )
            },
        )

    private fun mealAdvisor(): PreferenceItem =
        ApsIntentKey.AimiMealAdvisor.withCompose(
            ComposeScreenContent { onBack ->
                AimiMealAdvisorScreen(
                    preferences = preferences,
                    persistenceLayer = persistenceLayer,
                    profileFunction = profileFunction,
                    aapsLogger = aapsLogger,
                    geminiModelResolver = geminiModelResolver,
                    aimiHttp = aimiHttp,
                    onBack = onBack,
                )
            },
        )

    private fun modeSettings(): PreferenceItem =
        ApsIntentKey.AimiModeSettings.withCompose(
            ComposeScreenContent { onBack ->
                AimiModeSettingsScreen(
                    preferences = preferences,
                    persistenceLayer = persistenceLayer,
                    aapsLogger = aapsLogger,
                    onBack = onBack,
                )
            },
        )

    private fun profileAdvisor(): PreferenceItem =
        ApsIntentKey.AimiProfileAdvisor.withCompose(
            ComposeScreenContent { onBack ->
                val advisorService = advisorService()
                AimiProfileAdvisorScreen(
                    preferences = preferences,
                    advisorService = advisorService,
                    historyRepo = AdvisorHistoryRepository(keyValueCache),
                    importExportPrefs = importExportPrefs,
                    exportPasswordDataStore = exportPasswordDataStore,
                    aiCoachingService = aiCoachingService,
                    rh = rh,
                    storage = storage,
                    sharing = sharing,
                    onBack = onBack,
                )
            },
        )

    private fun pkpdSetup(): PreferenceItem =
        ApsIntentKey.PkpdSetup.withCompose(
            ComposeScreenContent { onBack ->
                AimiPkpdSettingsScreen(
                    preferences = preferences,
                    onBack = onBack,
                    loadProfileInsulin = {
                        val profile = profileFunction.getProfile() as? EffectiveProfile
                        profile?.iCfg?.dia to profile?.iCfg?.peak?.toDouble()
                    },
                    loadPkpdRecommendations = {
                        withContext(Dispatchers.IO) {
                            advisorService().pkpdRecommendationsForSettings(7)
                        }
                    },
                )
            }
        )

    private fun advisorService(): AimiAdvisorService = AimiAdvisorService(
        profileFunction = profileFunction,
        persistenceLayer = persistenceLayer,
        preferences = preferences,
        strings = AimiAdvisorStringsAndroid(rh),
        unifiedReactivityLearner = unifiedReactivityLearner,
        tddCalculator = tddCalculator,
        tirCalculator = tirCalculator,
        aapsLogger = aapsLogger,
        storage = storage,
    )
}
