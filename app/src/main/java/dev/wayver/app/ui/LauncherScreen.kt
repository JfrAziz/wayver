package dev.wayver.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.BrightnessAuto
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.wayver.app.container.ContainerConfig
import dev.wayver.app.container.EnvConfig
import dev.wayver.app.container.ResolutionConfig
import dev.wayver.app.container.ResolutionPreset
import dev.wayver.app.distro.Distro
import dev.wayver.app.distro.DistroRegistry

class LauncherUiState {
    var containers by mutableStateOf<List<ContainerConfig>>(emptyList())
    var runningIds by mutableStateOf<Set<String>>(emptySet())
    var installedContainers by mutableStateOf<Set<String>>(emptySet())
    var cachedDistros by mutableStateOf<Set<String>>(emptySet())
    val downloadProgress = mutableStateMapOf<String, String>()
    var editingContainer by mutableStateOf<ContainerConfig?>(null)
    var showCreateDialog by mutableStateOf(false)
    var themeMode by mutableStateOf(ThemeMode.AUTO)
}

@Composable
fun LauncherScreen(
    state: LauncherUiState,
    onCycleThemeMode: () -> Unit,
    onLaunchContainer: (ContainerConfig) -> Unit,
    onStopContainer: (ContainerConfig) -> Unit,
    onSaveContainer: (ContainerConfig) -> Unit,
    onDeleteContainer: (ContainerConfig) -> Unit,
    onDownloadImage: (Distro) -> Unit,
    onImportImage: (Distro) -> Unit
) {
    WayverTheme(themeMode = state.themeMode) {
        Scaffold(
            containerColor = AppColors.Bg
        ) { innerPadding ->
            Column(
                modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Wayver",
                        color = AppColors.TextPrimary,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val themeIcon =
                            when (state.themeMode) {
                                ThemeMode.AUTO -> Icons.Rounded.BrightnessAuto
                                ThemeMode.DARK -> Icons.Rounded.DarkMode
                                ThemeMode.LIGHT -> Icons.Rounded.LightMode
                            }
                        OutlinedButton(
                            onClick = onCycleThemeMode,
                            modifier = Modifier.height(40.dp),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, AppColors.BorderBright),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp)
                        ) {
                            Icon(themeIcon, null, modifier = Modifier.size(16.dp), tint = AppColors.TextPrimary)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                state.themeMode.label,
                                color = AppColors.TextPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        Button(
                            onClick = {
                                state.editingContainer = null
                                state.showCreateDialog = true
                            },
                            modifier = Modifier.height(40.dp),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                            colors =
                            ButtonDefaults.buttonColors(
                                containerColor = AppColors.Primary,
                                contentColor = AppColors.OnPrimary
                            )
                        ) {
                            Icon(Icons.Rounded.Add, null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("New Container", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }

                // Containers Section (1 col on phone, up to 5 cols on wide tablet)
                SectionHeader("CONTAINERS", "${state.containers.size} configured")
                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    val cols = (maxWidth / 220.dp).toInt().coerceIn(1, 5)
                    AdaptiveGrid(items = state.containers, columns = cols) { container ->
                        val distro = DistroRegistry.byId(container.distroId)
                        ContainerCard(
                            container = container,
                            distro = distro,
                            isRunning = state.runningIds.contains(container.id),
                            isInstalled = state.installedContainers.contains(container.id),
                            hasImage = state.cachedDistros.contains(distro.id),
                            onLaunch = { onLaunchContainer(container) },
                            onStop = { onStopContainer(container) },
                            onEdit = {
                                state.editingContainer = container
                                state.showCreateDialog = true
                            },
                            onDelete = { onDeleteContainer(container) }
                        )
                    }
                }

                // Distro Images Section (1 col on phone, up to 5 cols on wide tablet)
                SectionHeader("DISTRO IMAGES", "Download once, reuse across containers")
                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    val cols = (maxWidth / 220.dp).toInt().coerceIn(1, 5)
                    AdaptiveGrid(items = DistroRegistry.all, columns = cols) { distro ->
                        DistroImageCard(
                            distro = distro,
                            isCached = state.cachedDistros.contains(distro.id),
                            downloadStatus = state.downloadProgress[distro.id],
                            onDownload = { onDownloadImage(distro) },
                            onImport = { onImportImage(distro) }
                        )
                    }
                }
            }
        }

        if (state.showCreateDialog) {
            ContainerEditorDialog(
                existing = state.editingContainer,
                onDismiss = { state.showCreateDialog = false },
                onSave = { updated ->
                    state.showCreateDialog = false
                    onSaveContainer(updated)
                }
            )
        }
    }
}

@Composable
private fun <T> AdaptiveGrid(items: List<T>, columns: Int, itemContent: @Composable (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items.chunked(columns).forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                rowItems.forEach { item ->
                    Box(modifier = Modifier.weight(1f)) {
                        itemContent(item)
                    }
                }
                repeat(columns - rowItems.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, subtitle: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom
    ) {
        Text(
            text = title,
            color = AppColors.TextMuted,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.9.sp
        )
        Text(
            text = subtitle,
            color = AppColors.TextMuted,
            fontSize = 11.sp
        )
    }
}

@Composable
private fun ContainerCard(
    container: ContainerConfig,
    distro: Distro,
    isRunning: Boolean,
    isInstalled: Boolean,
    hasImage: Boolean,
    onLaunch: () -> Unit,
    onStop: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val desktopName = distro.desktopById(container.desktopId).name
    val statusText =
        when {
            isRunning -> "Running"
            isInstalled -> "Ready"
            hasImage -> "Image Cached"
            else -> "Needs Image"
        }
    val statusColor =
        when {
            isRunning || isInstalled -> AppColors.Success
            hasImage -> AppColors.Primary
            else -> AppColors.Warning
        }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = AppColors.Surface,
        border = BorderStroke(1.dp, if (isRunning) AppColors.Success.copy(alpha = 0.4f) else AppColors.Border)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Image(
                    painter = painterResource(distro.iconRes),
                    contentDescription = distro.name,
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
                            color = AppColors.TextPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        StatusBadge(label = statusText, color = statusColor)
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "${distro.name} • $desktopName",
                        color = AppColors.TextMuted,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = container.resolution.label(),
                        color = AppColors.TextMuted,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onLaunch,
                    shape = RoundedCornerShape(8.dp),
                    colors =
                    ButtonDefaults.buttonColors(
                        containerColor = if (isRunning) AppColors.Success else AppColors.Primary,
                        contentColor = AppColors.OnPrimary
                    ),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Icon(Icons.Rounded.PlayArrow, null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(3.dp))
                    Text(if (isRunning) "Resume" else "Launch", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }

                if (isRunning) {
                    IconButton(onClick = onStop, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Rounded.Stop, "Stop", tint = AppColors.Danger, modifier = Modifier.size(16.dp))
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Rounded.Edit, "Edit", tint = AppColors.TextMuted, modifier = Modifier.size(16.dp))
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Rounded.DeleteOutline,
                        "Delete",
                        tint = AppColors.Danger,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun DistroImageCard(
    distro: Distro,
    isCached: Boolean,
    downloadStatus: String?,
    onDownload: () -> Unit,
    onImport: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = AppColors.Surface,
        border = BorderStroke(1.dp, AppColors.Border)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Image(
                    painter = painterResource(distro.iconRes),
                    contentDescription = distro.name,
                    modifier = Modifier.size(32.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = distro.name,
                        color = AppColors.TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val subtitle =
                        when {
                            downloadStatus != null -> downloadStatus
                            isCached -> "Cached"
                            distro.rootfsUrl != null -> "Not downloaded"
                            else -> "Import .tar.gz"
                        }
                    Text(
                        text = subtitle,
                        color =
                        when {
                            downloadStatus != null -> AppColors.Primary
                            isCached -> AppColors.Success
                            else -> AppColors.TextMuted
                        },
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (distro.rootfsUrl != null && downloadStatus == null) {
                    if (!isCached) {
                        Button(
                            onClick = onDownload,
                            shape = RoundedCornerShape(8.dp),
                            colors =
                            ButtonDefaults.buttonColors(
                                containerColor = AppColors.Primary,
                                contentColor = AppColors.OnPrimary
                            ),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.weight(1f).height(30.dp)
                        ) {
                            Icon(Icons.Rounded.Download, null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Download", fontSize = 11.sp)
                        }
                    } else {
                        OutlinedButton(
                            onClick = onDownload,
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, AppColors.BorderBright),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.weight(1f).height(30.dp)
                        ) {
                            Icon(
                                Icons.Rounded.Refresh,
                                null,
                                modifier = Modifier.size(14.dp),
                                tint = AppColors.TextPrimary
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Redownload", color = AppColors.TextPrimary, fontSize = 11.sp, maxLines = 1)
                        }
                    }
                }
                OutlinedButton(
                    onClick = onImport,
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, AppColors.BorderBright),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.weight(1f).height(30.dp)
                ) {
                    Text("Import", color = AppColors.TextPrimary, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(label: String, color: Color) {
    Row(
        modifier =
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(color.copy(alpha = 0.14f))
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(999.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Box(
            modifier =
            Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(color)
        )
        Text(
            text = label,
            color = color,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ContainerEditorDialog(
    existing: ContainerConfig?,
    onDismiss: () -> Unit,
    onSave: (ContainerConfig) -> Unit
) {
    val distros = DistroRegistry.all
    var selectedDistroIdx by remember {
        mutableIntStateOf(distros.indexOfFirst { it.id == existing?.distroId }.coerceAtLeast(0))
    }
    val chosenDistro = distros[selectedDistroIdx]
    val desktops = chosenDistro.desktops
    var selectedDesktopId by remember(selectedDistroIdx) {
        mutableStateOf(
            desktops.firstOrNull { it.id == existing?.desktopId }?.id ?: desktops.first().id
        )
    }
    var name by remember { mutableStateOf(existing?.name ?: "") }

    val presets = ResolutionPreset.entries
    val initialCustom = existing?.resolution as? ResolutionConfig.Custom
    var isCustomRes by remember { mutableStateOf(existing?.resolution is ResolutionConfig.Custom) }
    var selectedPreset by remember {
        mutableStateOf((existing?.resolution as? ResolutionConfig.Preset)?.preset ?: ResolutionPreset.NATIVE)
    }
    var customW by remember { mutableStateOf((initialCustom?.width ?: 1600).toString()) }
    var customH by remember { mutableStateOf((initialCustom?.height ?: 1000).toString()) }

    var disabledPresets by remember { mutableStateOf(existing?.env?.disabledPresets ?: emptySet()) }
    var presetEdits by remember { mutableStateOf(existing?.env?.presetEdits ?: emptyMap()) }
    var expandedPreset by remember { mutableStateOf<String?>(null) }
    var overrides by remember { mutableStateOf(existing?.env?.overrides ?: emptyMap()) }
    var newEnvKey by remember { mutableStateOf("") }
    var newEnvVal by remember { mutableStateOf("") }
    var showAdvanced by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AppColors.SurfaceElevated,
        title = {
            Text(
                text = if (existing == null) "Create Container" else "Edit Container",
                color = AppColors.TextPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Container Name") },
                    placeholder = {
                        Text(
                            "${chosenDistro.name} + ${chosenDistro.desktopById(selectedDesktopId).name}"
                        )
                    },
                    singleLine = true,
                    colors =
                    OutlinedTextFieldDefaults.colors(
                        focusedTextColor = AppColors.TextPrimary,
                        unfocusedTextColor = AppColors.TextPrimary,
                        focusedBorderColor = AppColors.Primary,
                        unfocusedBorderColor = AppColors.BorderBright
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    text = if (existing == null) "Base Distro Image" else "Base Distro (locked)",
                    color = AppColors.TextMuted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    distros.forEachIndexed { idx, d ->
                        FilterChip(
                            selected = selectedDistroIdx == idx,
                            onClick = { if (existing == null) selectedDistroIdx = idx },
                            enabled = existing == null || selectedDistroIdx == idx,
                            label = { Text(d.name, fontSize = 12.sp) },
                            leadingIcon = {
                                Image(painterResource(d.iconRes), null, modifier = Modifier.size(18.dp))
                            },
                            colors =
                            FilterChipDefaults.filterChipColors(
                                selectedContainerColor = AppColors.PrimarySoft,
                                selectedLabelColor = AppColors.TextPrimary
                            )
                        )
                    }
                }

                Text(
                    "Desktop / Session",
                    color = AppColors.TextMuted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    desktops.forEach { d ->
                        FilterChip(
                            selected = selectedDesktopId == d.id,
                            onClick = { selectedDesktopId = d.id },
                            label = { Text(d.name, fontSize = 12.sp) }
                        )
                    }
                }

                Text(
                    "Screen Resolution (scale 1x)",
                    color = AppColors.TextMuted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    presets.forEach { preset ->
                        FilterChip(
                            selected = !isCustomRes && selectedPreset == preset,
                            onClick = {
                                isCustomRes = false
                                selectedPreset = preset
                            },
                            label = { Text(preset.label, fontSize = 11.sp) }
                        )
                    }
                    FilterChip(
                        selected = isCustomRes,
                        onClick = { isCustomRes = true },
                        label = { Text("Custom", fontSize = 11.sp) }
                    )
                }

                if (isCustomRes) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = customW,
                            onValueChange = { customW = it },
                            label = { Text("Width") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = customH,
                            onValueChange = { customH = it },
                            label = { Text("Height") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                TextButton(onClick = { showAdvanced = !showAdvanced }) {
                    Icon(
                        imageVector =
                        if (showAdvanced) {
                            Icons.Rounded.KeyboardArrowDown
                        } else {
                            Icons.AutoMirrored.Rounded.KeyboardArrowRight
                        },
                        contentDescription = null,
                        tint = AppColors.TextMuted,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Advanced",
                        color = AppColors.TextMuted,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                if (showAdvanced) {
                    if (chosenDistro.appEnvs.isNotEmpty()) {
                        Text(
                            text = "App Env Presets",
                            color = AppColors.TextMuted,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            chosenDistro.appEnvs.forEach { (app, defaultVars) ->
                                val enabled = app !in disabledPresets
                                val activeVars = presetEdits[app] ?: defaultVars
                                AppEnvPresetCard(
                                    app = app,
                                    enabled = enabled,
                                    isModified = app in presetEdits,
                                    isExpanded = expandedPreset == app,
                                    vars = activeVars,
                                    onToggleEnabled = {
                                        disabledPresets =
                                            if (enabled) disabledPresets + app else disabledPresets - app
                                    },
                                    onToggleExpand = {
                                        expandedPreset = if (expandedPreset == app) null else app
                                    },
                                    onReset = { presetEdits = presetEdits - app },
                                    onUpdateVar = { k, newVal ->
                                        presetEdits = presetEdits + (app to (activeVars + (k to newVal)))
                                    },
                                    onRemoveVar = { k ->
                                        presetEdits = presetEdits + (app to (activeVars - k))
                                    }
                                )
                            }
                        }
                    }

                    Text(
                        text = "Custom Environment",
                        color = AppColors.TextMuted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    overrides.forEach { (k, v) ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "$k=$v",
                                color = AppColors.TextPrimary,
                                fontSize = 12.sp,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = { overrides = overrides - k }) {
                                Text("Remove", color = AppColors.TextMuted, fontSize = 11.sp)
                            }
                        }
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = newEnvKey,
                            onValueChange = { newEnvKey = it },
                            label = { Text("KEY") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = newEnvVal,
                            onValueChange = { newEnvVal = it },
                            label = { Text("VALUE") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            onClick = {
                                val k = newEnvKey.trim()
                                if (k.isNotEmpty()) {
                                    overrides = overrides + (k to newEnvVal)
                                    newEnvKey = ""
                                    newEnvVal = ""
                                }
                            }
                        ) {
                            Text("Add", fontSize = 12.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val resolution =
                        if (isCustomRes) {
                            ResolutionConfig.Custom(
                                width = customW.toIntOrNull()?.coerceIn(320, 4096) ?: 1600,
                                height = customH.toIntOrNull()?.coerceIn(240, 4096) ?: 1000
                            )
                        } else {
                            ResolutionConfig.Preset(selectedPreset)
                        }
                    val finalName =
                        name.trim().ifEmpty {
                            "${chosenDistro.name} + ${chosenDistro.desktopById(selectedDesktopId).name}"
                        }
                    onSave(
                        ContainerConfig(
                            id = existing?.id ?: "",
                            name = finalName,
                            distroId = chosenDistro.id,
                            desktopId = selectedDesktopId,
                            resolution = resolution,
                            env =
                            EnvConfig(
                                disabledPresets = disabledPresets,
                                presetEdits = presetEdits,
                                overrides = overrides
                            )
                        )
                    )
                },
                colors =
                ButtonDefaults.buttonColors(
                    containerColor = AppColors.Primary,
                    contentColor = AppColors.OnPrimary
                )
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = AppColors.TextMuted)
            }
        }
    )
}

@Composable
private fun AppEnvPresetCard(
    app: String,
    enabled: Boolean,
    isModified: Boolean,
    isExpanded: Boolean,
    vars: Map<String, String>,
    onToggleEnabled: () -> Unit,
    onToggleExpand: () -> Unit,
    onReset: () -> Unit,
    onUpdateVar: (String, String) -> Unit,
    onRemoveVar: (String) -> Unit
) {
    Column(
        modifier =
        Modifier
            .fillMaxWidth()
            .border(1.dp, AppColors.Border, RoundedCornerShape(10.dp))
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp)
        ) {
            Checkbox(
                checked = enabled,
                onCheckedChange = { onToggleEnabled() }
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app,
                    color = if (enabled) AppColors.TextPrimary else AppColors.TextMuted,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = if (isModified) "Modified · ${vars.size} vars" else "${vars.size} vars",
                    color = if (isModified) AppColors.Primary else AppColors.TextMuted,
                    fontSize = 11.sp
                )
            }
            if (isModified) {
                IconButton(onClick = onReset, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = "Reset $app preset",
                        tint = AppColors.TextMuted,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            IconButton(onClick = onToggleExpand, modifier = Modifier.size(40.dp)) {
                Icon(
                    imageVector =
                    if (isExpanded) {
                        Icons.Rounded.KeyboardArrowDown
                    } else {
                        Icons.AutoMirrored.Rounded.KeyboardArrowRight
                    },
                    contentDescription = if (isExpanded) "Collapse $app" else "Expand $app",
                    tint = AppColors.TextMuted,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
        if (isExpanded) {
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(start = 12.dp, end = 8.dp, bottom = 10.dp)
            ) {
                vars.forEach { (k, v) ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = v,
                            onValueChange = { onUpdateVar(k, it) },
                            label = { Text(k, fontSize = 10.sp) },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = { onRemoveVar(k) },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.DeleteOutline,
                                contentDescription = "Remove $k",
                                tint = AppColors.TextMuted,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
