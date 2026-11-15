package dev.wayver.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.wayver.app.container.ContainerConfig
import dev.wayver.app.distro.Distro

enum class SetupStage {
    EXTRACTING,
    PREPARING,
    LAUNCHING,
    FAILED,
    READY
}

class SetupUiState {
    var themeMode by mutableStateOf(ThemeMode.AUTO)
    var stage by mutableStateOf(SetupStage.EXTRACTING)
    var statusText by mutableStateOf("Starting...")
    var progress by mutableStateOf<Float?>(null)
    var errorText by mutableStateOf<String?>(null)
    val logs = mutableStateListOf<String>()

    fun reset(initialStage: SetupStage, message: String) {
        stage = initialStage
        statusText = message
        progress = null
        errorText = null
        logs.clear()
    }

    fun updateFromOutput(rawLine: String) {
        val line = rawLine.trim()
        if (line.isEmpty()) return
        logs.add(line)
        if (logs.size > 200) logs.removeAt(0)

        val stepMatch = STEP_PROGRESS_REGEX.find(line)
        if (stepMatch != null) {
            val current = stepMatch.groupValues[1].toFloatOrNull()
            val total = stepMatch.groupValues[2].toFloatOrNull()
            if (current != null && total != null && total > 0f) {
                progress = (current / total).coerceIn(0f, 1f)
            }
        }

        statusText =
            when {
                line.startsWith(":: Synchronizing") -> "Synchronizing package databases..."
                line.startsWith(":: Retrieving packages") -> "Downloading packages..."
                line.startsWith(":: Processing package changes") -> "Installing packages..."
                line.startsWith(":: Running post-transaction hooks") -> "Running post-install hooks..."
                line.startsWith("Get:") || line.startsWith("Hit:") -> "Updating package lists..."
                line.startsWith("Unpacking ") || line.startsWith("Setting up ") -> line
                stepMatch != null -> line
                else -> statusText
            }
    }

    private companion object {
        val STEP_PROGRESS_REGEX = Regex("""\((\d+)/(\d+)\)""")
    }
}

@Composable
fun SetupOverlay(
    state: SetupUiState,
    container: ContainerConfig,
    distro: Distro,
    desktopName: String,
    onRetry: () -> Unit,
    onCancel: () -> Unit
) {
    if (state.stage == SetupStage.READY) return

    var showLogs by remember(state.stage) { mutableStateOf(state.stage == SetupStage.FAILED) }
    val activeSegment =
        when (state.stage) {
            SetupStage.EXTRACTING -> 0
            SetupStage.PREPARING -> 1
            SetupStage.LAUNCHING -> 2
            SetupStage.FAILED, SetupStage.READY -> 1
        }

    WayverTheme(themeMode = state.themeMode, syncSystemBars = false) {
        val bg = AppColors.Bg
        val primary = AppColors.Primary
        Box(
            modifier =
            Modifier
                .fillMaxSize()
                .background(bg)
                .drawBehind {
                    drawRect(
                        Brush.radialGradient(
                            0f to primary.copy(alpha = 0.12f),
                            0.65f to bg.copy(alpha = 0.0f),
                            center = Offset(size.width / 2f, size.height * 0.44f),
                            radius = maxOf(size.width, size.height) * 0.55f
                        )
                    )
                }
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 32.dp, vertical = 24.dp)
            ) {
                Image(
                    painter = painterResource(distro.iconRes),
                    contentDescription = distro.name,
                    modifier = Modifier.size(64.dp)
                )

                Spacer(Modifier.height(24.dp))

                Text(
                    text = if (state.stage == SetupStage.FAILED) "${container.name} failed" else container.name,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AppColors.TextPrimary,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Text(
                    text = state.errorText ?: state.statusText,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    color = if (state.stage == SetupStage.FAILED) AppColors.Danger else AppColors.TextMuted,
                    textAlign = TextAlign.Center,
                    maxLines = if (state.stage == SetupStage.FAILED) 4 else 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp).widthIn(max = 520.dp)
                )

                if (state.stage != SetupStage.FAILED) {
                    LoadSegments(
                        count = 3,
                        active = activeSegment,
                        progress = state.progress,
                        modifier = Modifier.padding(top = 20.dp)
                    )
                }

                if (state.logs.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = if (showLogs) "Hide logs" else "Show logs (${state.logs.size})",
                        fontSize = 12.sp,
                        color = AppColors.Primary,
                        modifier = Modifier.clickable { showLogs = !showLogs }
                    )

                    AnimatedVisibility(visible = showLogs) {
                        val listState = rememberLazyListState()
                        LaunchedEffect(state.logs.size) {
                            if (state.logs.isNotEmpty()) listState.scrollToItem(state.logs.lastIndex)
                        }
                        Surface(
                            modifier =
                            Modifier
                                .padding(top = 10.dp)
                                .widthIn(max = 520.dp)
                                .fillMaxWidth()
                                .heightIn(min = 90.dp, max = 180.dp),
                            shape = RoundedCornerShape(8.dp),
                            color = AppColors.Surface,
                            border = BorderStroke(1.dp, AppColors.Border)
                        ) {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.padding(8.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                items(state.logs) { line ->
                                    Text(
                                        text = line,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        lineHeight = 14.sp,
                                        color = AppColors.TextMuted
                                    )
                                }
                            }
                        }
                    }
                }

                if (state.stage == SetupStage.FAILED) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.padding(top = 22.dp)
                    ) {
                        OutlinedButton(
                            onClick = onCancel,
                            border = BorderStroke(1.dp, AppColors.Border)
                        ) {
                            Text("Back to Menu", color = AppColors.TextPrimary)
                        }
                        Button(
                            onClick = onRetry,
                            colors =
                            ButtonDefaults.buttonColors(
                                containerColor = AppColors.Primary,
                                contentColor = AppColors.OnPrimary
                            )
                        ) {
                            Text("Retry")
                        }
                    }
                }
            }

            Text(
                text = "${distro.name} • $desktopName",
                fontSize = 12.sp,
                color = AppColors.TextMuted.copy(alpha = 0.7f),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp)
            )

            if (state.stage != SetupStage.FAILED) {
                Text(
                    text = "Cancel",
                    fontSize = 13.sp,
                    color = AppColors.TextMuted,
                    modifier =
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 20.dp, bottom = 20.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .clickable(onClick = onCancel)
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun LoadSegments(count: Int, active: Int, progress: Float?, modifier: Modifier = Modifier) {
    val accent = AppColors.Primary
    val track = AppColors.TextPrimary.copy(alpha = 0.14f)
    val sweep by rememberInfiniteTransition(label = "segment").animateFloat(
        initialValue = -0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1300, easing = FastOutSlowInEasing)),
        label = "sweep"
    )
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
    ) {
        repeat(count) { index ->
            Box(
                Modifier
                    .width(48.dp)
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (index < active) accent else track)
                    .drawBehind {
                        if (index != active) return@drawBehind
                        if (progress != null) {
                            drawRect(accent, size = Size(size.width * progress.coerceIn(0f, 1f), size.height))
                        } else {
                            drawRect(
                                accent,
                                topLeft = Offset(size.width * sweep, 0f),
                                size = Size(size.width * 0.4f, size.height)
                            )
                        }
                    }
            )
        }
    }
}
