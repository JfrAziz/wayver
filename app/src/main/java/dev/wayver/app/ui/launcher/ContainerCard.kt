package dev.wayver.app.ui.launcher

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.wayver.app.container.ContainerConfig
import dev.wayver.app.distro.Distro
import dev.wayver.app.ui.AppColors
import dev.wayver.app.ui.components.StatusBadge
import dev.wayver.app.ui.components.WayverCard
import dev.wayver.app.ui.components.WayverIconButton
import dev.wayver.app.ui.components.WayverPrimaryButton

private data class ContainerCardStyle(
    val statusText: String,
    val statusColor: Color,
    val borderColor: Color,
    val launchColor: Color,
    val launchLabel: String
)

@Composable
fun ContainerCard(container: ContainerConfig, distro: Distro, status: ContainerStatus, modifier: Modifier = Modifier) {
    val dispatch = LocalLauncherAction.current
    val setDialog = LocalLauncherDialog.current
    val desktopName = distro.desktopById(container.desktopId).name
    val style =
        when (status) {
            ContainerStatus.Running ->
                ContainerCardStyle(
                    "Running",
                    AppColors.success,
                    AppColors.success.copy(alpha = 0.4f),
                    AppColors.success,
                    "Resume"
                )
            ContainerStatus.Ready ->
                ContainerCardStyle("Ready", AppColors.success, AppColors.border, AppColors.primary, "Launch")
            ContainerStatus.ImageCached ->
                ContainerCardStyle("Image Cached", AppColors.primary, AppColors.border, AppColors.primary, "Launch")
            ContainerStatus.NeedsImage ->
                ContainerCardStyle("Needs Image", AppColors.warning, AppColors.border, AppColors.primary, "Launch")
        }

    WayverCard(
        modifier = modifier,
        borderColor = style.borderColor
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Image(
                painter = painterResource(distro.iconRes),
                contentDescription = null,
                modifier = Modifier.size(34.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = container.name,
                        color = AppColors.textPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    StatusBadge(label = style.statusText, color = style.statusColor)
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${distro.name} • $desktopName",
                    color = AppColors.textMuted,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = container.resolution.label,
                    color = AppColors.textMuted,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 40.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            WayverPrimaryButton(
                onClick = { dispatch(LauncherAction.Launch(container)) },
                containerColor = style.launchColor,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Icon(Icons.Rounded.PlayArrow, null, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(3.dp))
                Text(style.launchLabel, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
            when (status) {
                ContainerStatus.Running ->
                    WayverIconButton(
                        icon = Icons.Rounded.Stop,
                        contentDescription = "Stop",
                        tint = AppColors.danger,
                        onClick = { dispatch(LauncherAction.Stop(container)) }
                    )
                ContainerStatus.Ready,
                ContainerStatus.ImageCached,
                ContainerStatus.NeedsImage -> Unit
            }

            Spacer(modifier = Modifier.weight(1f))

            WayverIconButton(
                icon = Icons.Rounded.Edit,
                contentDescription = "Edit",
                onClick = { setDialog(LauncherDialog.Editor(container)) }
            )
            WayverIconButton(
                icon = Icons.Rounded.DeleteOutline,
                contentDescription = "Delete",
                tint = AppColors.danger,
                onClick = { setDialog(LauncherDialog.DeleteConfirm(container)) }
            )
        }
    }
}
