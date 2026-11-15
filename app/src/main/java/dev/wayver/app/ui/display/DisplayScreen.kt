package dev.wayver.app.ui.display

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import dev.wayver.app.ui.WayverTheme
import dev.wayver.app.ui.components.LogBuffer

@Composable
fun DisplayScreen(
    state: DisplayUiState,
    collectLogs: @Composable () -> LogBuffer,
    onAction: (DisplayAction) -> Unit,
    onMenuOpenChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    CompositionLocalProvider(LocalDisplayAction provides onAction) {
        WayverTheme(themeMode = state.themeMode) {
            when (val phase = state.phase) {
                is DisplayPhase.Booting,
                is DisplayPhase.Failed ->
                    SetupOverlay(
                        phase = phase,
                        active = state.active,
                        collectLogs = collectLogs,
                        modifier = modifier
                    )
                is DisplayPhase.Running ->
                    QuickMenuOverlay(
                        state = state,
                        nativeStatus = phase.nativeStatus,
                        collectLogs = collectLogs,
                        onMenuOpenChanged = onMenuOpenChanged,
                        modifier = modifier
                    )
            }
        }
    }
}
