package dev.wayver.app.ui

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.wayver.app.container.ContainerConfig
import dev.wayver.app.distro.Distro
import dev.wayver.app.distro.DistroRegistry

class QuickMenuState {
    var isOpen by mutableStateOf(false)
    var themeMode by mutableStateOf(ThemeMode.AUTO)
    var nativeStatus by mutableStateOf("Initializing...")
    var surfaceSize by mutableStateOf("0x0")
    var allContainers by mutableStateOf<List<ContainerConfig>>(emptyList())
    var runningIds by mutableStateOf<Set<String>>(emptySet())
    val logs = mutableStateListOf<String>()

    fun appendLog(line: String) {
        if (logs.size >= 80) logs.removeAt(0)
        logs.add(line)
    }
}

@Composable
fun QuickMenuOverlay(
    state: QuickMenuState,
    container: ContainerConfig,
    distro: Distro,
    onToggleKeyboard: () -> Unit,
    onRestartSession: () -> Unit,
    onMainMenu: () -> Unit,
    onSwitchContainer: (ContainerConfig) -> Unit,
    onStopContainer: () -> Unit,
    onRefreshStatus: () -> Unit
) {
    LinuxDesktopTheme(themeMode = state.themeMode, syncSystemBars = false) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Only 2 floating pill buttons when the right-side sheet is closed: Keyboard + Quick Menu.
            if (!state.isOpen) {
                Row(
                    modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(14.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(AppColors.Bg.copy(alpha = 0.78f))
                        .border(1.dp, AppColors.Border, RoundedCornerShape(999.dp))
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FloatingPillButton(
                        icon = Icons.Rounded.Keyboard,
                        contentDescription = "Toggle Keyboard",
                        onClick = onToggleKeyboard
                    )
                    FloatingPillButton(
                        icon = Icons.Rounded.Tune,
                        contentDescription = "Quick Menu",
                        onClick = {
                            onRefreshStatus()
                            state.isOpen = true
                        }
                    )
                }
            }

            AnimatedVisibility(
                visible = state.isOpen,
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
                        ) { state.isOpen = false }
                )
            }

            AnimatedVisibility(
                visible = state.isOpen,
                modifier = Modifier.align(Alignment.CenterEnd),
                enter = slideInHorizontally { it },
                exit = slideOutHorizontally { it }
            ) {
                QuickMenuSheet(
                    state = state,
                    container = container,
                    distro = distro,
                    onClose = { state.isOpen = false },
                    onRestartSession = {
                        state.isOpen = false
                        onRestartSession()
                    },
                    onMainMenu = {
                        state.isOpen = false
                        onMainMenu()
                    },
                    onSwitchContainer = { target ->
                        state.isOpen = false
                        onSwitchContainer(target)
                    },
                    onStopContainer = onStopContainer
                )
            }
        }
    }
}

@Composable
private fun FloatingPillButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit
) {
    Box(
        modifier =
        Modifier
            .size(34.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = AppColors.TextPrimary,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun QuickMenuSheet(
    state: QuickMenuState,
    container: ContainerConfig,
    distro: Distro,
    onClose: () -> Unit,
    onRestartSession: () -> Unit,
    onMainMenu: () -> Unit,
    onSwitchContainer: (ContainerConfig) -> Unit,
    onStopContainer: () -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Overview", "Containers", "Logs")

    Surface(
        modifier =
        Modifier
            .fillMaxHeight()
            .widthIn(min = 300.dp, max = 380.dp)
            .fillMaxWidth(0.86f)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {},
        color = AppColors.Bg,
        border = BorderStroke(1.dp, AppColors.Border)
    ) {
        Column(
            modifier =
            Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Sheet Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Quick Menu",
                        color = AppColors.TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = container.name,
                        color = AppColors.TextMuted,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Close",
                        tint = AppColors.TextMuted,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // Segmented Tabs (compact so it works cleanly on both phones and tablets)
            Row(
                modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(AppColors.SurfaceElevated)
                    .border(1.dp, AppColors.Border, RoundedCornerShape(10.dp))
                    .padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                tabs.forEachIndexed { idx, title ->
                    val active = selectedTab == idx
                    Box(
                        modifier =
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(7.dp))
                            .background(if (active) AppColors.Primary else Color.Transparent)
                            .clickable { selectedTab = idx }
                            .padding(vertical = 7.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = title,
                            color = if (active) AppColors.OnPrimary else AppColors.TextMuted,
                            fontSize = 12.sp,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium
                        )
                    }
                }
            }

            // Tab Body
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (selectedTab) {
                    0 ->
                        OverviewTab(
                            state = state,
                            container = container,
                            distro = distro,
                            onRestartSession = onRestartSession,
                            onMainMenu = onMainMenu
                        )

                    1 ->
                        ContainersTab(
                            currentId = container.id,
                            containers = state.allContainers,
                            runningIds = state.runningIds,
                            onSwitchContainer = onSwitchContainer
                        )

                    else -> LogsTab(state = state)
                }
            }

            // Pinned Stop Container Footer
            Button(
                onClick = onStopContainer,
                modifier = Modifier.fillMaxWidth().height(40.dp),
                shape = RoundedCornerShape(8.dp),
                colors =
                ButtonDefaults.buttonColors(
                    containerColor = AppColors.DangerSoft,
                    contentColor = AppColors.Danger
                ),
                border = BorderStroke(1.dp, AppColors.Danger.copy(alpha = 0.45f))
            ) {
                Icon(Icons.Rounded.PowerSettingsNew, null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Stop Container", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun OverviewTab(
    state: QuickMenuState,
    container: ContainerConfig,
    distro: Distro,
    onRestartSession: () -> Unit,
    onMainMenu: () -> Unit
) {
    val desktopName = distro.desktopById(container.desktopId).name
    Column(
        modifier =
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SheetCard(title = "ACTIVE CONTAINER") {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Image(
                    painter = painterResource(distro.iconRes),
                    contentDescription = distro.name,
                    modifier = Modifier.size(38.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = container.name,
                        color = AppColors.TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${distro.name} • $desktopName",
                        color = AppColors.TextMuted,
                        fontSize = 12.sp
                    )
                    Text(
                        text = container.resolution.label(),
                        color = AppColors.TextMuted,
                        fontSize = 11.sp
                    )
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onRestartSession,
                    modifier = Modifier.weight(1f).height(34.dp),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, AppColors.BorderBright),
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    Icon(Icons.Rounded.Refresh, null, modifier = Modifier.size(14.dp), tint = AppColors.TextPrimary)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Restart", color = AppColors.TextPrimary, fontSize = 12.sp)
                }
                OutlinedButton(
                    onClick = onMainMenu,
                    modifier = Modifier.weight(1f).height(34.dp),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, AppColors.BorderBright),
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    Icon(Icons.Rounded.Home, null, modifier = Modifier.size(14.dp), tint = AppColors.TextPrimary)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Main Menu", color = AppColors.TextPrimary, fontSize = 12.sp)
                }
            }
        }

        SheetCard(title = "DEVICE & DISPLAY") {
            InfoRow("Device", "${Build.MANUFACTURER} ${Build.MODEL}")
            InfoRow(
                "ABI / Android",
                "${Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"} • API ${Build.VERSION.SDK_INT}"
            )
            InfoRow("Output", "${state.surfaceSize} (scale 1x)")
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = state.nativeStatus,
                color = AppColors.TextMuted,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                lineHeight = 14.sp
            )
        }
    }
}

@Composable
private fun ContainersTab(
    currentId: String,
    containers: List<ContainerConfig>,
    runningIds: Set<String>,
    onSwitchContainer: (ContainerConfig) -> Unit
) {
    Column(
        modifier =
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        containers.forEach { item ->
            val itemDistro = DistroRegistry.byId(item.distroId)
            val isCurrent = item.id == currentId
            val isRunning = runningIds.contains(item.id)
            Surface(
                modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !isCurrent) { onSwitchContainer(item) },
                shape = RoundedCornerShape(10.dp),
                color = if (isCurrent) AppColors.PrimarySoft else AppColors.SurfaceElevated,
                border =
                BorderStroke(
                    1.dp,
                    if (isCurrent) AppColors.Primary.copy(alpha = 0.6f) else AppColors.Border
                )
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Image(
                        painter = painterResource(itemDistro.iconRes),
                        contentDescription = itemDistro.name,
                        modifier = Modifier.size(30.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.name,
                            color = AppColors.TextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${itemDistro.name} • ${itemDistro.desktopById(item.desktopId).name}",
                            color = AppColors.TextMuted,
                            fontSize = 11.sp
                        )
                    }
                    when {
                        isCurrent ->
                            Text(
                                text = "Active",
                                color = AppColors.Primary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )

                        isRunning ->
                            Text(
                                text = "Switch",
                                color = AppColors.Success,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )

                        else ->
                            Icon(
                                imageVector = Icons.Rounded.PlayArrow,
                                contentDescription = "Launch",
                                tint = AppColors.TextMuted,
                                modifier = Modifier.size(18.dp)
                            )
                    }
                }
            }
        }
    }
}

@Composable
private fun LogsTab(state: QuickMenuState) {
    val logScroll = rememberScrollState()
    LaunchedEffect(state.logs.size) {
        logScroll.animateScrollTo(logScroll.maxValue)
    }

    Column(
        modifier =
        Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(10.dp))
            .background(AppColors.Crust)
            .border(1.dp, AppColors.Border, RoundedCornerShape(10.dp))
            .padding(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "CONTAINER LOGS",
                color = AppColors.TextMuted,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.8.sp
            )
            Text(
                text = "Clear",
                color = AppColors.TextMuted,
                fontSize = 11.sp,
                modifier = Modifier.clickable { state.logs.clear() }
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Box(
            modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(logScroll)
        ) {
            Text(
                text = if (state.logs.isEmpty()) "Waiting for output..." else state.logs.joinToString("\n"),
                color = AppColors.LogText,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                lineHeight = 14.sp
            )
        }
    }
}

@Composable
private fun SheetCard(title: String, content: @Composable () -> Unit) {
    Column(
        modifier =
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(AppColors.SurfaceElevated)
            .border(1.dp, AppColors.Border, RoundedCornerShape(10.dp))
            .padding(12.dp)
    ) {
        Text(
            text = title,
            color = AppColors.TextMuted,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.8.sp
        )
        Spacer(modifier = Modifier.height(8.dp))
        content()
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier =
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, color = AppColors.TextMuted, fontSize = 11.sp)
        Text(
            text = value,
            color = AppColors.TextPrimary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
