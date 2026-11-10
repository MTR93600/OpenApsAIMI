package app.aaps.plugins.main.general.dashboard

import android.view.View
import android.widget.FrameLayout
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.RecyclerView
import app.aaps.plugins.main.databinding.FragmentDashboardBinding
import app.aaps.plugins.main.general.dashboard.views.AdjustmentStatusView
import app.aaps.plugins.main.general.dashboard.views.CircleTopDashboardView
import app.aaps.plugins.main.general.dashboard.views.GlucoseGraphView
import com.google.android.material.bottomnavigation.BottomNavigationView

/**
 * View ports used by [DashboardShellController] for both the classic [DashboardFragment] layout and
 * the Compose-shell column (Compose hero + body [AndroidView]).
 */
internal data class DashboardShellBinding(
    val root: View,
    val nestedScrollView: NestedScrollView?,
    val overviewNotifications: RecyclerView?,
    val glucoseGraph: GlucoseGraphView?,
    val adjustmentStatus: AdjustmentStatusView?,
    val bottomNavigation: BottomNavigationView?,
    val statusCard: CircleTopDashboardView?,
    private val standaloneAuditorHost: FrameLayout?,
) {

    fun auditorHost(): FrameLayout =
        standaloneAuditorHost
            ?: statusCard?.getAuditorContainer()
            ?: error("DashboardShellBinding: missing auditor host")

    internal companion object {

        fun fromFragmentDashboard(binding: FragmentDashboardBinding): DashboardShellBinding {
            val nested = binding.root.getChildAt(0) as NestedScrollView
            return DashboardShellBinding(
                root = binding.root,
                nestedScrollView = nested,
                overviewNotifications = binding.overviewNotifications,
                glucoseGraph = binding.glucoseGraph,
                adjustmentStatus = binding.adjustmentStatus,
                bottomNavigation = binding.bottomNavigation,
                statusCard = binding.statusCard,
                standaloneAuditorHost = null,
            )
        }

        fun fromComposeEmbeddedColumn(
            shellPostRoot: View,
            auditorHost: FrameLayout,
            glucoseGraph: GlucoseGraphView?,
        ): DashboardShellBinding =
            DashboardShellBinding(
                root = shellPostRoot,
                nestedScrollView = null,
                overviewNotifications = null,
                glucoseGraph = glucoseGraph,
                adjustmentStatus = null,
                bottomNavigation = null,
                statusCard = null,
                standaloneAuditorHost = auditorHost,
            )
    }
}
