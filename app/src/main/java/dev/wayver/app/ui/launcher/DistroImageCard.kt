package dev.wayver.app.ui.launcher

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
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
import dev.wayver.app.distro.Distro
import dev.wayver.app.ui.AppColors
import dev.wayver.app.ui.components.WayverCard
import dev.wayver.app.ui.components.WayverIconButton
import dev.wayver.app.ui.components.WayverTintedIconButton

private data class DistroCardStyle(
    val borderColor: Color,
    val subtitle: String,
    val subtitleColor: Color
)

@Composable
fun DistroImageCard(distro: Distro, imageState: ImageState, modifier: Modifier = Modifier) {
    val dispatch = LocalLauncherAction.current
    val style =
        when (imageState) {
            is ImageState.Downloading ->
                DistroCardStyle(AppColors.primary.copy(alpha = 0.4f), imageState.progress, AppColors.primary)
            ImageState.Cached ->
                DistroCardStyle(AppColors.success.copy(alpha = 0.35f), "Cached", AppColors.success)
            ImageState.NotDownloaded ->
                DistroCardStyle(AppColors.border, "Not downloaded", AppColors.textMuted)
        }

    WayverCard(modifier = modifier, borderColor = style.borderColor) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Image(
                painter = painterResource(distro.iconRes),
                contentDescription = null,
                modifier = Modifier.size(32.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = distro.name,
                    color = AppColors.textPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = style.subtitle,
                    color = style.subtitleColor,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            when (imageState) {
                is ImageState.Downloading -> {
                    Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = AppColors.primary
                        )
                    }
                }
                ImageState.NotDownloaded -> {
                    WayverTintedIconButton(
                        icon = Icons.Rounded.Download,
                        contentDescription = "Download",
                        onClick = { dispatch(LauncherAction.DownloadImage(distro)) }
                    )
                }
                ImageState.Cached -> {
                    WayverIconButton(
                        icon = Icons.Rounded.Refresh,
                        contentDescription = "Redownload",
                        onClick = { dispatch(LauncherAction.DownloadImage(distro)) }
                    )
                }
            }
        }
    }
}
