package app.aaps.plugins.main.general.dashboard

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface DashboardShellDepsEntryPoint {
    fun dashboardShellDeps(): DashboardShellDeps
}
