package dev.wayver.app.ui.launcher

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.wayver.app.container.ContainerConfig
import dev.wayver.app.container.ScreenScale
import dev.wayver.app.ui.AppColors
import dev.wayver.app.ui.components.FieldLabel
import dev.wayver.app.ui.components.WayverChipGroup
import dev.wayver.app.ui.components.WayverDangerButton
import dev.wayver.app.ui.components.WayverDialog
import dev.wayver.app.ui.components.WayverIconButton
import dev.wayver.app.ui.components.WayverPrimaryButton
import dev.wayver.app.ui.components.WayverTextField

@Composable
fun ContainerEditorDialog(existing: ContainerConfig?) {
    val dispatch = LocalLauncherAction.current
    val setDialog = LocalLauncherDialog.current
    val form = rememberContainerFormState(existing)
    var showAdvanced by rememberSaveable { mutableStateOf(false) }

    WayverDialog(
        title = if (form.isEditing) "Edit Container" else "Create Container",
        onDismiss = { setDialog(LauncherDialog.None) },
        confirmButton = {
            WayverPrimaryButton(
                onClick = {
                    dispatch(LauncherAction.Save(form.toConfig()))
                    setDialog(LauncherDialog.None)
                }
            ) {
                Text("Save")
            }
        }
    ) {
        WayverTextField(
            value = form.name,
            onValueChange = { form.name = it },
            label = "Container Name",
            placeholder = form.defaultName,
            modifier = Modifier.fillMaxWidth()
        )

        FieldLabel(if (form.isEditing) "Base Distro (locked)" else "Base Distro Image")
        WayverChipGroup(
            items = form.distros,
            selected = { it.id == form.selectedDistro.id },
            onSelect = form::selectDistro,
            label = { it.name },
            enabled = { !form.isEditing || it.id == form.selectedDistro.id },
            leadingIcon = { Image(painterResource(it.iconRes), null, modifier = Modifier.size(18.dp)) }
        )

        FieldLabel("Desktop / Session")
        WayverChipGroup(
            items = form.desktops,
            selected = { it.id == form.selectedDesktopId },
            onSelect = { form.selectedDesktopId = it.id },
            label = { it.name }
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            FieldLabel("Screen Scale")
            Text(
                text = ScreenScale.formatLabel(form.resolutionScale),
                color = AppColors.accent,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
        Slider(
            value = form.resolutionScale,
            onValueChange = { form.resolutionScale = ScreenScale.clamp(it) },
            valueRange = ScreenScale.MIN..ScreenScale.MAX,
            steps = ScreenScale.STEPS,
            colors = SliderDefaults.colors(
                thumbColor = AppColors.accent,
                activeTrackColor = AppColors.accent,
                inactiveTrackColor = AppColors.border
            )
        )

        TextButton(onClick = { showAdvanced = !showAdvanced }) {
            Icon(
                imageVector =
                if (showAdvanced) {
                    Icons.Rounded.KeyboardArrowDown
                } else {
                    Icons.AutoMirrored.Rounded.KeyboardArrowRight
                },
                contentDescription = null,
                tint = AppColors.textMuted,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text("Advanced", color = AppColors.textMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }

        if (showAdvanced) {
            EnvEditorSection(form)
        }
    }
}

@Composable
fun DeleteContainerDialog(container: ContainerConfig) {
    val dispatch = LocalLauncherAction.current
    val setDialog = LocalLauncherDialog.current
    WayverDialog(
        title = "Delete Container",
        onDismiss = { setDialog(LauncherDialog.None) },
        scrollable = false,
        confirmButton = {
            WayverDangerButton(
                onClick = {
                    dispatch(LauncherAction.ConfirmDelete(container))
                    setDialog(LauncherDialog.None)
                }
            ) {
                Text("Delete")
            }
        }
    ) {
        Text(
            text = "Delete \"${container.name}\"? This will permanently remove its rootfs and all files inside it.",
            color = AppColors.textMuted,
            fontSize = 13.sp
        )
    }
}

@Composable
private fun EnvEditorSection(form: ContainerFormState) {
    var newEnvKey by remember { mutableStateOf("") }
    var newEnvVal by remember { mutableStateOf("") }

    if (form.selectedDistro.appEnvs.isNotEmpty()) {
        FieldLabel("App Env Presets")
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            form.selectedDistro.appEnvs.forEach { (app, defaultVars) ->
                AppEnvPresetCard(app = app, defaultVars = defaultVars, form = form)
            }
        }
    }

    FieldLabel("Custom Environment")
    form.overrides.forEach { (k, v) ->
        key(k) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "$k=$v",
                    color = AppColors.textPrimary,
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f)
                )
                WayverIconButton(
                    icon = Icons.Rounded.DeleteOutline,
                    contentDescription = "Remove $k",
                    onClick = { form.overrides -= k }
                )
            }
        }
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        WayverTextField(
            value = newEnvKey,
            onValueChange = { newEnvKey = it },
            label = "KEY",
            modifier = Modifier.weight(1f)
        )
        WayverTextField(
            value = newEnvVal,
            onValueChange = { newEnvVal = it },
            label = "VALUE",
            modifier = Modifier.weight(1f)
        )
        val trimmedKey = newEnvKey.trim()
        val isKeyValid = ENV_KEY_REGEX.matches(trimmedKey)
        TextButton(
            enabled = isKeyValid,
            onClick = {
                form.overrides += (trimmedKey to newEnvVal)
                newEnvKey = ""
                newEnvVal = ""
            }
        ) {
            Text("Add", fontSize = 12.sp)
        }
    }
}

private val ENV_KEY_REGEX = Regex("^[A-Za-z_][A-Za-z0-9_]*$")

@Composable
private fun AppEnvPresetCard(app: String, defaultVars: Map<String, String>, form: ContainerFormState) {
    var expanded by remember { mutableStateOf(false) }
    val enabled = app !in form.disabledPresets
    val isModified = app in form.presetEdits
    val vars = form.presetEdits[app] ?: defaultVars

    Column(
        modifier =
        Modifier
            .fillMaxWidth()
            .border(1.dp, AppColors.border, RoundedCornerShape(10.dp))
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp)
        ) {
            Checkbox(
                checked = enabled,
                onCheckedChange = {
                    form.disabledPresets =
                        if (enabled) form.disabledPresets + app else form.disabledPresets - app
                }
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app,
                    color = if (enabled) AppColors.textPrimary else AppColors.textMuted,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = if (isModified) "Modified · ${vars.size} vars" else "${vars.size} vars",
                    color = if (isModified) AppColors.primary else AppColors.textMuted,
                    fontSize = 11.sp
                )
            }
            if (isModified) {
                WayverIconButton(
                    icon = Icons.Rounded.Refresh,
                    contentDescription = "Reset $app preset",
                    onClick = { form.presetEdits -= app },
                    size = 36.dp,
                    iconSize = 18.dp
                )
            }
            WayverIconButton(
                icon =
                if (expanded) Icons.Rounded.KeyboardArrowDown
                else Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = if (expanded) "Collapse $app" else "Expand $app",
                onClick = { expanded = !expanded },
                size = 40.dp,
                iconSize = 24.dp
            )
        }
        if (expanded) {
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(start = 12.dp, end = 8.dp, bottom = 10.dp)
            ) {
                vars.forEach { (k, v) ->
                    key(k) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            WayverTextField(
                                value = v,
                                onValueChange = { newVal ->
                                    form.presetEdits += (app to (vars + (k to newVal)))
                                },
                                label = k,
                                modifier = Modifier.weight(1f)
                            )
                            WayverIconButton(
                                icon = Icons.Rounded.DeleteOutline,
                                contentDescription = "Remove $k",
                                onClick = {
                                    form.presetEdits += (app to (vars - k))
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
