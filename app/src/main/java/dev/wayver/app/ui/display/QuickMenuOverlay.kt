package dev.wayver.app.ui.display

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.wayver.app.ui.AppColors
import dev.wayver.app.ui.components.LogBuffer
import dev.wayver.app.ui.components.WayverDangerButton
import dev.wayver.app.ui.components.WayverIconButton

private enum class QuickMenuTab(val label: String) {
    Overview("Overview"),
    Containers("Containers"),
    Logs("Logs")
}

@Composable
internal fun QuickMenuOverlay(
    state: DisplayUiState,
    nativeStatus: String,
    collectLogs: @Composable () -> LogBuffer,
    onMenuOpenChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val parentDispatch = LocalDisplayAction.current
    var isOpen by remember { mutableStateOf(false) }

    LaunchedEffect(isOpen) {
        onMenuOpenChanged(isOpen)
    }

    BackHandler(enabled = isOpen) {
        isOpen = false
    }

    val dispatch =
        remember(parentDispatch) {
            { action: DisplayAction ->
                when (action) {
                    DisplayAction.RestartSession,
                    DisplayAction.OpenMainMenu,
                    is DisplayAction.SwitchContainer -> isOpen = false
                    else -> Unit
                }
                parentDispatch(action)
            }
        }

    CompositionLocalProvider(LocalDisplayAction provides dispatch) {
        Box(modifier = modifier.fillMaxSize()) {
            if (!isOpen) {
                Column(
                    modifier =
                    Modifier
                        .align(Alignment.CenterEnd)
                        .systemBarsPadding()
                        .displayCutoutPadding()
                        .padding(end = 10.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(AppColors.bg.copy(alpha = 0.78f))
                        .border(1.dp, AppColors.border, RoundedCornerShape(999.dp))
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    WayverIconButton(
                        icon = Icons.Rounded.Keyboard,
                        contentDescription = "Toggle Keyboard",
                        onClick = { dispatch(DisplayAction.ToggleKeyboard) },
                        tint = AppColors.textPrimary,
                        size = 40.dp,
                        iconSize = 18.dp
                    )
                    WayverIconButton(
                        icon = Icons.Rounded.Tune,
                        contentDescription = "Quick Menu",
                        onClick = {
                            dispatch(DisplayAction.RefreshQuickMenu)
                            isOpen = true
                        },
                        tint = AppColors.textPrimary,
                        size = 40.dp,
                        iconSize = 18.dp
                    )
                }
            }

            AnimatedVisibility(
                visible = isOpen,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Box(
                    modifier =
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.55f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { isOpen = false }
                )
            }

            AnimatedVisibility(
                visible = isOpen,
                modifier = Modifier.align(Alignment.CenterEnd),
                enter = slideInHorizontally { it },
                exit = slideOutHorizontally { it }
            ) {
                QuickMenuSheet(
                    state = state,
                    nativeStatus = nativeStatus,
                    collectLogs = collectLogs,
                    onClose = { isOpen = false }
                )
            }
        }
    }
}

@Composable
private fun QuickMenuSheet(
    state: DisplayUiState,
    nativeStatus: String,
    collectLogs: @Composable () -> LogBuffer,
    onClose: () -> Unit
) {
    val dispatch = LocalDisplayAction.current
    var selectedTab by remember { mutableStateOf(QuickMenuTab.Overview) }

    Surface(
        modifier =
        Modifier
            .fillMaxHeight()
            .widthIn(min = 300.dp, max = 380.dp)
            .fillMaxWidth(0.86f)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent()
                    }
                }
            },
        color = AppColors.bg
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            VerticalDivider(thickness = 1.dp, color = AppColors.border)
            Column(
                modifier =
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .systemBarsPadding()
                    .displayCutoutPadding()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Quick Menu",
                            color = AppColors.textPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = state.active.config.name,
                            color = AppColors.textMuted,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    WayverIconButton(
                        icon = Icons.Rounded.Close,
                        contentDescription = "Close",
                        onClick = onClose,
                        size = 40.dp,
                        iconSize = 18.dp
                    )
                }

                Row(
                    modifier =
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(AppColors.surfaceElevated)
                        .border(1.dp, AppColors.border, RoundedCornerShape(10.dp))
                        .padding(3.dp)
                        .selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    QuickMenuTab.entries.forEach { tab ->
                        val active = selectedTab == tab
                        Box(
                            modifier =
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(7.dp))
                                .background(if (active) AppColors.primary else Color.Transparent)
                                .selectable(
                                    selected = active,
                                    onClick = { selectedTab = tab },
                                    role = Role.Tab
                                )
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = tab.label,
                                color = if (active) AppColors.primaryOn else AppColors.textMuted,
                                fontSize = 12.sp,
                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium
                            )
                        }
                    }
                }

                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    when (selectedTab) {
                        QuickMenuTab.Overview -> OverviewTab(state = state, nativeStatus = nativeStatus)
                        QuickMenuTab.Containers -> ContainersTab(containers = state.containers)
                        QuickMenuTab.Logs -> LogsTab(logBuffer = collectLogs())
                    }
                }

                WayverDangerButton(
                    onClick = { dispatch(DisplayAction.StopContainer) },
                    modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 40.dp)
                ) {
                    Icon(Icons.Rounded.PowerSettingsNew, null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Stop Container", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
