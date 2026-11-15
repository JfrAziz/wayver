package dev.wayver.app.ui.launcher

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.wayver.app.container.ContainerConfig
import dev.wayver.app.container.EnvConfig
import dev.wayver.app.container.ResolutionPreset
import dev.wayver.app.distro.DesktopSpec
import dev.wayver.app.distro.Distro
import dev.wayver.app.distro.DistroRegistry

@Stable
class ContainerFormState(private val existing: ContainerConfig?) {
    val distros: List<Distro> = DistroRegistry.all
    val isEditing: Boolean = existing != null

    var name by mutableStateOf(existing?.name ?: "")
    var selectedDistro by mutableStateOf(
        distros.firstOrNull { it.id == existing?.distroId } ?: distros.first()
    )
    var selectedDesktopId by mutableStateOf(
        selectedDistro.desktops.firstOrNull { it.id == existing?.desktopId }?.id
            ?: selectedDistro.desktops.first().id
    )
    var selectedPreset by mutableStateOf(existing?.resolution ?: ResolutionPreset.NATIVE)

    var disabledPresets by mutableStateOf(existing?.env?.disabledPresets ?: emptySet())
    var presetEdits by mutableStateOf(existing?.env?.presetEdits ?: emptyMap())
    var overrides by mutableStateOf(existing?.env?.overrides ?: emptyMap())

    val desktops: List<DesktopSpec> get() = selectedDistro.desktops

    val defaultName: String
        get() = "${selectedDistro.name} + ${selectedDistro.desktopById(selectedDesktopId).name}"

    fun selectDistro(distro: Distro) {
        if (isEditing || selectedDistro.id == distro.id) return
        selectedDistro = distro
        if (distro.desktops.none { it.id == selectedDesktopId }) {
            selectedDesktopId = distro.desktops.first().id
        }
        disabledPresets = emptySet()
        presetEdits = emptyMap()
    }

    fun toConfig(): ContainerConfig = ContainerConfig(
        id = existing?.id ?: "",
        name = name.trim().ifEmpty { defaultName },
        distroId = selectedDistro.id,
        desktopId = selectedDesktopId,
        resolution = selectedPreset,
        env =
        EnvConfig(
            disabledPresets = disabledPresets,
            presetEdits = presetEdits,
            overrides = overrides
        )
    )
}

@Composable
fun rememberContainerFormState(existing: ContainerConfig?): ContainerFormState =
    remember(existing?.id) { ContainerFormState(existing) }
