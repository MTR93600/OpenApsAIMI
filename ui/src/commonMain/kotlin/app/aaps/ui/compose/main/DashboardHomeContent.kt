package app.aaps.ui.compose.main

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Constrains alternate dashboard skins to the same system-bar and shell-chrome viewport as
 * Overview's content. MainScreen draws its app bars as overlays, so the padding must be applied
 * here rather than relying on Scaffold slots to reserve space.
 */
@Composable
internal fun DashboardHomeContent(
    contentPadding: PaddingValues,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
    ) {
        content()
    }
}
