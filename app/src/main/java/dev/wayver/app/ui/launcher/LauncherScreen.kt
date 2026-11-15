package dev.wayver.app.ui.launcher

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.BrightnessAuto
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.wayver.app.BuildConfig
import dev.wayver.app.container.ContainerConfig
import dev.wayver.app.ui.AppColors
import dev.wayver.app.ui.ThemeMode
import dev.wayver.app.ui.WayverTheme
import dev.wayver.app.ui.components.SectionHeader
import dev.wayver.app.ui.components.WayverCard
import dev.wayver.app.ui.components.WayverIconButton
import dev.wayver.app.ui.components.WayverPrimaryButton

sealed interface LauncherDialog {
    data object None : LauncherDialog
    data class Editor(val existing: ContainerConfig? = null) : LauncherDialog
    data class DeleteConfirm(val container: ContainerConfig) : LauncherDialog
}

val LocalLauncherDialog = compositionLocalOf<(LauncherDialog) -> Unit> { {} }

@Composable
fun LauncherScreen(state: LauncherUiState, onAction: (LauncherAction) -> Unit, modifier: Modifier = Modifier) {
    var dialog by remember { mutableStateOf<LauncherDialog>(LauncherDialog.None) }
    val setDialog = remember { { next: LauncherDialog -> dialog = next } }

    CompositionLocalProvider(
        LocalLauncherAction provides onAction,
        LocalLauncherDialog provides setDialog
    ) {
        WayverTheme(themeMode = state.themeMode) {
            Scaffold(
                modifier = modifier,
                containerColor = AppColors.bg
            ) { innerPadding ->
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(220.dp),
                    modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentPadding = PaddingValues(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    fullSpanItem("header") {
                        LauncherHeader(themeMode = state.themeMode)
                    }

                    fullSpanItem("containers_header") {
                        SectionHeader(
                            title = "CONTAINERS",
                            subtitle = "${state.containers.size} configured",
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }

                    if (state.containers.isEmpty()) {
                        fullSpanItem("containers_empty") {
                            WayverCard(contentPadding = PaddingValues(24.dp)) {
                                Text(
                                    text = "No containers yet. Tap + New to create one.",
                                    color = AppColors.textMuted,
                                    fontSize = 13.sp
                                )
                            }
                        }
                    } else {
                        items(
                            items = state.containers,
                            key = { "container:${it.config.id}" }
                        ) { item ->
                            ContainerCard(
                                container = item.config,
                                distro = item.distro,
                                status = item.status
                            )
                        }
                    }

                    fullSpanItem("distros_header") {
                        SectionHeader(
                            title = "DISTRO IMAGES",
                            subtitle = "Download once, reuse across containers",
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }

                    items(
                        items = state.distros,
                        key = { "distro:${it.distro.id}" }
                    ) { item ->
                        DistroImageCard(
                            distro = item.distro,
                            imageState = item.state
                        )
                    }
                }
            }

            when (val active = dialog) {
                LauncherDialog.None -> Unit
                is LauncherDialog.Editor -> ContainerEditorDialog(existing = active.existing)
                is LauncherDialog.DeleteConfirm -> DeleteContainerDialog(container = active.container)
            }
        }
    }
}

@Composable
private fun LauncherHeader(themeMode: ThemeMode) {
    val dispatch = LocalLauncherAction.current
    val setDialog = LocalLauncherDialog.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Wayver",
                    color = AppColors.textPrimary,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "v${BuildConfig.VERSION_NAME}",
                    color = AppColors.textMuted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
            Text(
                text = "Native Wayland + Vulkan + PRoot",
                color = AppColors.textMuted,
                fontSize = 12.sp
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            WayverIconButton(
                icon =
                when (themeMode) {
                    ThemeMode.AUTO -> Icons.Rounded.BrightnessAuto
                    ThemeMode.DARK -> Icons.Rounded.DarkMode
                    ThemeMode.LIGHT -> Icons.Rounded.LightMode
                },
                contentDescription = "Theme: ${themeMode.label}",
                onClick = { dispatch(LauncherAction.CycleTheme) },
                tint = AppColors.textPrimary,
                size = 40.dp,
                iconSize = 20.dp
            )
            WayverPrimaryButton(onClick = { setDialog(LauncherDialog.Editor()) }) {
                Icon(Icons.Rounded.Add, null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("New", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

private fun LazyGridScope.fullSpanItem(key: String, content: @Composable () -> Unit) =
    item(key = key, span = { GridItemSpan(maxLineSpan) }) { content() }
