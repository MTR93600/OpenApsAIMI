package app.aaps.plugins.main.general.dashboard

import android.content.Context
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineScope

/**
 * Abstraction over [androidx.fragment.app.Fragment] vs a plain [android.view.View] host so
 * [DashboardShellController] can run the same AIMI dashboard wiring in both places.
 */
interface DashboardShellHost {
    val context: Context
    val activity: FragmentActivity?
    val lifecycleOwner: LifecycleOwner
    val lifecycleScope: CoroutineScope

    /**
     * Owner passed to [androidx.lifecycle.LiveData.observe] and used for coroutines that must end
     * when the dashboard binding is torn down. For fragments this matches [lifecycleOwner]
     * ([androidx.fragment.app.Fragment.getViewLifecycleOwner]); for the Compose shell it is a
     * per-[android.view.View] registry destroyed on detach so observers do not survive across
     * [androidx.compose.ui.viewinterop.AndroidView] instances while the activity stays alive.
     */
    val liveDataOwner: LifecycleOwner
        get() = lifecycleOwner

    /** True while UI wiring is safe (fragment added with view, or compose root attached with binding). */
    fun isBindingAttached(): Boolean

    /** When true, periodic overview refresh must not be cancelled on [android.app.Activity.onPause]. */
    fun embeddedInComposeMainShell(): Boolean

    /**
     * When non-null (Compose embedded shell), the hero is pure Compose and [DashboardShellController]
     * drives context badge + metrics sync through this state instead of [CircleTopDashboardView].
     */
    val embeddedComposeState: DashboardEmbeddedComposeState?
        get() = null
}

internal class DashboardFragmentShellHost(
    private val fragment: DashboardFragment,
) : DashboardShellHost {

    override val context: Context get() = fragment.requireContext()
    override val activity: FragmentActivity? get() = fragment.activity
    override val lifecycleOwner: LifecycleOwner get() = fragment.viewLifecycleOwner
    override val lifecycleScope: CoroutineScope get() = fragment.viewLifecycleOwner.lifecycleScope

    override fun isBindingAttached(): Boolean = fragment.isAdded && fragment.view != null

    override fun embeddedInComposeMainShell(): Boolean {
        val n = activity?.javaClass?.name ?: return false
        return n == "app.aaps.ComposeMainActivity" || n.endsWith(".ComposeMainActivity")
    }
}
