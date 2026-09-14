package app.aaps.plugins.main.general.dashboard

import android.content.Context
import android.util.AttributeSet
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import app.aaps.core.ui.compose.LocalPreferences
import app.aaps.plugins.main.general.dashboard.compose.LocalDashboardHeroCommands
import app.aaps.plugins.main.general.dashboard.compose.NoopDashboardHeroCommands
import app.aaps.plugins.main.general.dashboard.glass.GlassOverviewComposeEmbedded
import app.aaps.plugins.main.general.dashboard.glass.LocalGlassHeroCommands
import app.aaps.plugins.main.general.dashboard.glass.NoopGlassHeroCommands
import app.aaps.plugins.main.general.dashboard.viewmodel.OverviewViewModel
import app.aaps.plugins.main.skins.DashboardHomeVariant
import app.aaps.ui.compose.overview.graphs.GraphViewModel
import app.aaps.ui.compose.overview.statusLights.StatusViewModel
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope

/**
 * AIMI dashboard for the Compose main shell: Compose hero + [AndroidView] body (graph, etc.).
 */
class AimiDashboardComposeRootView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    private val dashboardHomeVariant: DashboardHomeVariant = DashboardHomeVariant.DASHBOARD_V1,
    private val availablePluginClassNames: Set<String> = emptySet(),
    private val onToolAction: (DashboardV2ToolAction) -> Unit = {},
) : FrameLayout(context, attrs),
    LifecycleOwner {

    private var lifecycleRegistry: LifecycleRegistry = LifecycleRegistry(this)

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    private val embeddedComposeState = DashboardEmbeddedComposeState()

    private var composeView: ComposeView? = null
    private var shellController: DashboardShellController? = null
    private var lifecycleObserver: DefaultLifecycleObserver? = null
    private var observedLifecycle: LifecycleOwner? = null

    private val shellHost: DashboardShellHost = object : DashboardShellHost {
        override val context: Context get() = this@AimiDashboardComposeRootView.context
        override val activity: FragmentActivity? get() = context as? FragmentActivity
        override val lifecycleOwner: LifecycleOwner get() = this@AimiDashboardComposeRootView
        override val liveDataOwner: LifecycleOwner get() = this@AimiDashboardComposeRootView
        override val lifecycleScope: CoroutineScope get() = lifecycleOwner.lifecycleScope
        override fun isBindingAttached(): Boolean = isAttachedToWindow && shellController != null
        override fun embeddedInComposeMainShell(): Boolean = true
        override val embeddedComposeState: DashboardEmbeddedComposeState?
            get() = this@AimiDashboardComposeRootView.embeddedComposeState
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (composeView != null) return
        val act = context as FragmentActivity
        val deps = EntryPointAccessors.fromApplication(
            act.applicationContext,
            DashboardShellDepsEntryPoint::class.java,
        ).dashboardShellDeps()
        val viewModel = ViewModelProvider(act, deps.overviewViewModelFactory).get(
            VIEW_MODEL_KEY,
            OverviewViewModel::class.java,
        )
        val graphViewModel = ViewModelProvider(act)[GraphViewModel::class.java]
        val statusViewModel = ViewModelProvider(act)[StatusViewModel::class.java]
        val cv = ComposeView(context).apply {
            // Dispose with our LifecycleRegistry (not DetachedFromWindow): manual removeAllViews()
            // while nested AndroidView interop is recomposing can race with accessibility teardown
            // (NPE in AndroidComposeViewAccessibilityDelegateCompat.onViewDetachedFromWindow).
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnLifecycleDestroyed(this@AimiDashboardComposeRootView),
            )
            setContent {
                var shellCtrl by remember { mutableStateOf<DashboardShellController?>(null) }
                val heroCommands = shellCtrl?.heroCommandsForCompose() ?: NoopDashboardHeroCommands
                val glassHeroCommands = shellCtrl?.glassHeroCommandsForCompose() ?: NoopGlassHeroCommands
                CompositionLocalProvider(
                    LocalPreferences provides deps.preferences,
                    LocalDashboardHeroCommands provides heroCommands,
                    LocalGlassHeroCommands provides glassHeroCommands,
                ) {
                    val onShellBindingReady: (DashboardShellBinding) -> Unit = { shellBinding ->
                        if (shellCtrl == null) {
                            val controller = DashboardShellController(
                                host = shellHost,
                                deps = deps,
                                viewModel = viewModel,
                                eventSourcePrefix = EVENT_SOURCE_PREFIX,
                            )
                            shellCtrl = controller
                            shellController = controller
                            controller.attachShell(shellBinding)
                            installActivityLifecycleObserver(act)
                        }
                    }
                    when (dashboardHomeVariant) {
                        DashboardHomeVariant.GLASS -> {
                            val auditorHost = remember { FrameLayout(context) }
                            LaunchedEffect(Unit) {
                                onShellBindingReady(
                                    DashboardShellBinding.fromComposeEmbeddedColumn(
                                        shellPostRoot = this@AimiDashboardComposeRootView,
                                        auditorHost = auditorHost,
                                        glucoseGraph = null,
                                    ),
                                )
                            }
                            GlassOverviewComposeEmbedded(
                                overviewViewModel = viewModel,
                                statusViewModel = statusViewModel,
                                graphViewModel = graphViewModel,
                                embeddedState = embeddedComposeState,
                                availablePluginClassNames = availablePluginClassNames,
                                onToolAction = onToolAction,
                            )
                        }

                        DashboardHomeVariant.DASHBOARD_V1,
                        DashboardHomeVariant.OVERVIEW,
                        -> AimiDashboardComposeEmbedded(
                            shellPostRoot = this@AimiDashboardComposeRootView,
                            embeddedState = embeddedComposeState,
                            preferences = deps.preferences,
                            viewModel = viewModel,
                            graphViewModel = graphViewModel,
                            onShellBindingReady = onShellBindingReady,
                        )
                    }
                }
            }
        }
        composeView = cv
        addView(cv, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    private fun installActivityLifecycleObserver(owner: LifecycleOwner) {
        if (lifecycleObserver != null) return
        observedLifecycle = owner
        val observer = object : DefaultLifecycleObserver {
            override fun onStart(o: LifecycleOwner) {
                moveViewLifecycleToStarted()
                shellController?.start()
            }

            override fun onResume(o: LifecycleOwner) {
                moveViewLifecycleToResumed()
                shellController?.resume()
            }

            override fun onPause(o: LifecycleOwner) {
                shellController?.pause()
                moveViewLifecycleToStartedFromResumed()
            }

            override fun onStop(o: LifecycleOwner) {
                shellController?.stop()
                moveViewLifecycleToCreatedFromStarted()
            }
        }
        lifecycleObserver = observer
        owner.lifecycle.addObserver(observer)
        post {
            if (shellController == null || !isAttachedToWindow) return@post
            replayShellForCurrentActivityState(owner)
        }
    }

    private fun replayShellForCurrentActivityState(owner: LifecycleOwner) {
        val state = owner.lifecycle.currentState
        if (state.isAtLeast(Lifecycle.State.STARTED)) {
            moveViewLifecycleToStarted()
            shellController?.start()
        }
        if (state.isAtLeast(Lifecycle.State.RESUMED)) {
            moveViewLifecycleToResumed()
            shellController?.resume()
        }
    }

    override fun onDetachedFromWindow() {
        lifecycleObserver?.let { obs ->
            observedLifecycle?.lifecycle?.removeObserver(obs)
        }
        lifecycleObserver = null
        observedLifecycle = null
        moveViewLifecycleToDestroyed()
        shellController?.stop()
        shellController?.destroyView()
        shellController = null
        // Lifecycle → ON_DESTROY runs dispose via DisposeOnLifecycleDestroyed before we detach children.
        val cv = composeView
        composeView = null
        if (cv != null && cv.parent === this) {
            removeView(cv)
        }
        super.onDetachedFromWindow()
        lifecycleRegistry = LifecycleRegistry(this)
    }

    private fun moveViewLifecycleToStarted() {
        when (lifecycleRegistry.currentState) {
            Lifecycle.State.INITIALIZED ->
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            else -> Unit
        }
        if (lifecycleRegistry.currentState == Lifecycle.State.CREATED) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        }
    }

    private fun moveViewLifecycleToResumed() {
        moveViewLifecycleToStarted()
        if (lifecycleRegistry.currentState == Lifecycle.State.STARTED) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }
    }

    private fun moveViewLifecycleToStartedFromResumed() {
        if (lifecycleRegistry.currentState == Lifecycle.State.RESUMED) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        }
    }

    private fun moveViewLifecycleToCreatedFromStarted() {
        moveViewLifecycleToStartedFromResumed()
        if (lifecycleRegistry.currentState == Lifecycle.State.STARTED) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        }
    }

    private fun moveViewLifecycleToDestroyed() {
        moveViewLifecycleToStartedFromResumed()
        if (lifecycleRegistry.currentState == Lifecycle.State.STARTED) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        }
        if (lifecycleRegistry.currentState == Lifecycle.State.CREATED) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        }
        if (lifecycleRegistry.currentState == Lifecycle.State.INITIALIZED) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        }
    }

    private companion object {
        private const val VIEW_MODEL_KEY = "AimiDashboardCompose"
        private const val EVENT_SOURCE_PREFIX = "AimiDashboardComposeView"
    }
}
