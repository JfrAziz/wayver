package dev.wayver.app.ui.display

import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.wayver.app.BuildConfig
import dev.wayver.app.ui.AppColors
import dev.wayver.app.ui.components.FieldLabel
import dev.wayver.app.ui.components.LogBuffer
import dev.wayver.app.ui.components.LogConsole
import dev.wayver.app.ui.components.WayverCard
import dev.wayver.app.ui.components.WayverOutlinedButton

@Composable
internal fun OverviewTab(state: DisplayUiState, nativeStatus: String) {
    val dispatch = LocalDisplayAction.current
    val active = state.active
    val container = active.config
    val distro = active.distro

    Column(
        modifier =
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        WayverCard(containerColor = AppColors.surfaceElevated) {
            FieldLabel("ACTIVE CONTAINER")
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Image(
                    painter = painterResource(distro.iconRes),
                    contentDescription = null,
                    modifier = Modifier.size(38.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = container.name,
                        color = AppColors.textPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${distro.name} • ${active.desktopName}",
                        color = AppColors.textMuted,
                        fontSize = 12.sp
                    )
                    Text(
                        text = container.resolution.label,
                        color = AppColors.textMuted,
                        fontSize = 11.sp
                    )
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                WayverOutlinedButton(
                    onClick = { dispatch(DisplayAction.RestartSession) },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    Icon(Icons.Rounded.Refresh, null, modifier = Modifier.size(14.dp), tint = AppColors.textPrimary)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Restart", color = AppColors.textPrimary, fontSize = 12.sp)
                }
                WayverOutlinedButton(
                    onClick = { dispatch(DisplayAction.OpenMainMenu) },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    Icon(Icons.Rounded.Home, null, modifier = Modifier.size(14.dp), tint = AppColors.textPrimary)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Main Menu", color = AppColors.textPrimary, fontSize = 12.sp)
                }
            }
        }

        WayverCard(containerColor = AppColors.surfaceElevated) {
            FieldLabel("DEVICE & DISPLAY")
            Spacer(modifier = Modifier.height(8.dp))
            InfoRow("Wayver", "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            InfoRow("Device", "${Build.MANUFACTURER} ${Build.MODEL}")
            InfoRow(
                "ABI / Android",
                "${Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"} • API ${Build.VERSION.SDK_INT}"
            )
            InfoRow("Output", "${state.surfaceSize} • ${container.resolution.label}")
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = nativeStatus,
                color = AppColors.textMuted,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                lineHeight = 14.sp
            )
        }
    }
}

@Composable
internal fun ContainersTab(containers: List<SwitchContainerItem>) {
    val dispatch = LocalDisplayAction.current
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(items = containers, key = { it.config.id }) { item ->
            val isCurrent = item.state == SwitchItemState.Current
            WayverCard(
                modifier =
                Modifier.clickable(enabled = !isCurrent) {
                    dispatch(DisplayAction.SwitchContainer(item.config))
                },
                containerColor = if (isCurrent) AppColors.primarySoft else AppColors.surfaceElevated,
                borderColor = if (isCurrent) AppColors.primary.copy(alpha = 0.6f) else AppColors.border,
                contentPadding = PaddingValues(10.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Image(
                        painter = painterResource(item.distro.iconRes),
                        contentDescription = null,
                        modifier = Modifier.size(30.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.config.name,
                            color = AppColors.textPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${item.distro.name} • ${item.desktopName}",
                            color = AppColors.textMuted,
                            fontSize = 11.sp
                        )
                    }
                    when (item.state) {
                        SwitchItemState.Current ->
                            Text(
                                text = "Active",
                                color = AppColors.primary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        SwitchItemState.Running ->
                            Text(
                                text = "Switch",
                                color = AppColors.success,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        SwitchItemState.Stopped ->
                            Icon(
                                imageVector = Icons.Rounded.PlayArrow,
                                contentDescription = "Launch",
                                tint = AppColors.textMuted,
                                modifier = Modifier.size(18.dp)
                            )
                    }
                }
            }
        }
    }
}

@Composable
internal fun LogsTab(logBuffer: LogBuffer) {
    val dispatch = LocalDisplayAction.current

    Column(
        modifier =
        Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(10.dp))
            .background(AppColors.crust)
            .border(1.dp, AppColors.border, RoundedCornerShape(10.dp))
            .padding(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            FieldLabel("CONTAINER LOGS")
            TextButton(onClick = { dispatch(DisplayAction.ClearLogs) }) {
                Text("Clear", color = AppColors.textMuted, fontSize = 11.sp)
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        LogConsole(
            buffer = logBuffer,
            textColor = AppColors.textLog,
            emptyText = "Waiting for output...",
            modifier = Modifier.fillMaxSize()
        )
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
        Text(text = label, color = AppColors.textMuted, fontSize = 11.sp)
        Text(
            text = value,
            color = AppColors.textPrimary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
