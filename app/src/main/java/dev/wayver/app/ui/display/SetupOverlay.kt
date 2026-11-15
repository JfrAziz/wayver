package dev.wayver.app.ui.display

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.wayver.app.ui.AppColors
import dev.wayver.app.ui.components.LogBuffer
import dev.wayver.app.ui.components.LogConsole
import dev.wayver.app.ui.components.WayverOutlinedButton
import dev.wayver.app.ui.components.WayverPrimaryButton

@Composable
internal fun SetupOverlay(
    phase: DisplayPhase,
    active: SwitchContainerItem,
    collectLogs: @Composable () -> LogBuffer,
    modifier: Modifier = Modifier
) {
    if (phase is DisplayPhase.Running) return

    val dispatch = LocalDisplayAction.current
    val isFailed = phase is DisplayPhase.Failed
    var showLogs by remember(isFailed) { mutableStateOf(isFailed) }
    val logBuffer = collectLogs()

    val message =
        when (phase) {
            is DisplayPhase.Booting -> phase.statusText
            is DisplayPhase.Failed -> phase.errorText
            is DisplayPhase.Running -> ""
        }

    val bg = AppColors.bg
    val primary = AppColors.primary

    Box(
        modifier =
        modifier
            .fillMaxSize()
            .background(bg)
            .drawWithCache {
                val brush =
                    Brush.radialGradient(
                        0f to primary.copy(alpha = 0.12f),
                        0.65f to bg.copy(alpha = 0.0f),
                        center = Offset(size.width / 2f, size.height * 0.44f),
                        radius = maxOf(size.width, size.height) * 0.55f
                    )
                onDrawBehind { drawRect(brush) }
            }
            .systemBarsPadding()
            .displayCutoutPadding()
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 32.dp, vertical = 24.dp)
        ) {
            Image(
                painter = painterResource(active.distro.iconRes),
                contentDescription = null,
                modifier = Modifier.size(64.dp)
            )

            Spacer(Modifier.height(24.dp))

            Text(
                text = if (isFailed) "${active.config.name} failed" else active.config.name,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                color = AppColors.textPrimary,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Text(
                text = message,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                color = if (isFailed) AppColors.danger else AppColors.textMuted,
                textAlign = TextAlign.Center,
                maxLines = if (isFailed) 4 else 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp).widthIn(max = 520.dp)
            )

            if (phase is DisplayPhase.Booting) {
                if (phase.stage != null) {
                    LoadSegments(
                        count = BootStage.entries.size,
                        active = phase.stage.segmentIndex,
                        progress = phase.progress,
                        modifier = Modifier.padding(top = 20.dp)
                    )
                } else {
                    Spacer(Modifier.height(23.dp))
                }
            }

            if (logBuffer.lines.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = { showLogs = !showLogs }) {
                    Text(
                        text = if (showLogs) "Hide logs" else "Show logs (${logBuffer.lines.size})",
                        fontSize = 12.sp,
                        color = AppColors.primary
                    )
                }

                AnimatedVisibility(visible = showLogs) {
                    Surface(
                        modifier =
                        Modifier
                            .padding(top = 6.dp)
                            .widthIn(max = 520.dp)
                            .fillMaxWidth()
                            .heightIn(min = 90.dp, max = 180.dp),
                        shape = RoundedCornerShape(8.dp),
                        color = AppColors.surface,
                        border = BorderStroke(1.dp, AppColors.border)
                    ) {
                        LogConsole(
                            buffer = logBuffer,
                            textColor = AppColors.textMuted,
                            fontSize = 11.sp,
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp)
                        )
                    }
                }
            }

            if (isFailed) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(top = 22.dp)
                ) {
                    WayverOutlinedButton(
                        onClick = { dispatch(DisplayAction.CancelBoot) },
                        borderColor = AppColors.border
                    ) {
                        Text("Back to Menu", color = AppColors.textPrimary)
                    }
                    WayverPrimaryButton(
                        onClick = { dispatch(DisplayAction.RetryBoot) }
                    ) {
                        Text("Retry")
                    }
                }
            }
        }

        Text(
            text = "${active.distro.name} • ${active.desktopName}",
            fontSize = 12.sp,
            color = AppColors.textMuted.copy(alpha = 0.7f),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp)
        )

        if (!isFailed) {
            TextButton(
                onClick = { dispatch(DisplayAction.CancelBoot) },
                modifier =
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 12.dp, bottom = 12.dp)
            ) {
                Text(
                    text = "Cancel",
                    fontSize = 13.sp,
                    color = AppColors.textMuted
                )
            }
        }
    }
}

@Composable
private fun LoadSegments(count: Int, active: Int, progress: Float?, modifier: Modifier = Modifier) {
    val accent = AppColors.primary
    val track = AppColors.textPrimary.copy(alpha = 0.14f)

    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
    ) {
        repeat(count) { index ->
            val segModifier =
                Modifier
                    .width(48.dp)
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (index < active) accent else track)

            if (index == active && progress == null) {
                IndeterminateSegment(accent = accent, modifier = segModifier)
            } else {
                Box(
                    segModifier.drawBehind {
                        if (index == active && progress != null) {
                            drawRect(accent, size = Size(size.width * progress.coerceIn(0f, 1f), size.height))
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun IndeterminateSegment(accent: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier) {
    val sweep by rememberInfiniteTransition(label = "segment").animateFloat(
        initialValue = -0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1300, easing = FastOutSlowInEasing)),
        label = "sweep"
    )
    Box(
        modifier.drawBehind {
            drawRect(
                accent,
                topLeft = Offset(size.width * sweep, 0f),
                size = Size(size.width * 0.4f, size.height)
            )
        }
    )
}
