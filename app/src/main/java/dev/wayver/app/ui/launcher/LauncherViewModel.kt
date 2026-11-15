package dev.wayver.app.ui.launcher

import android.app.Application
import androidx.compose.runtime.compositionLocalOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.wayver.app.container.ContainerConfig
import dev.wayver.app.container.ContainerManager
import dev.wayver.app.container.ContainerStore
import dev.wayver.app.core.AppPrefs
import dev.wayver.app.distro.Distro
import dev.wayver.app.distro.DistroRegistry
import dev.wayver.app.distro.RootfsManager
import dev.wayver.app.ui.ThemeMode
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ContainerStatus {
    Running,
    Ready,
    ImageCached,
    NeedsImage
}

sealed interface ImageState {
    data object NotDownloaded : ImageState
    data class Downloading(val progress: String) : ImageState
    data object Cached : ImageState
}

data class ContainerItem(
    val config: ContainerConfig,
    val distro: Distro,
    val status: ContainerStatus
)

data class DistroItem(
    val distro: Distro,
    val state: ImageState
)

data class LauncherUiState(
    val distros: List<DistroItem> = emptyList(),
    val containers: List<ContainerItem> = emptyList(),
    val themeMode: ThemeMode = ThemeMode.AUTO
)

sealed interface LauncherAction {
    data object CycleTheme : LauncherAction
    data class Launch(val container: ContainerConfig) : LauncherAction
    data class Stop(val container: ContainerConfig) : LauncherAction
    data class Save(val container: ContainerConfig) : LauncherAction
    data class ConfirmDelete(val container: ContainerConfig) : LauncherAction
    data class DownloadImage(val distro: Distro) : LauncherAction
}

val LocalLauncherAction = compositionLocalOf<(LauncherAction) -> Unit> { {} }

sealed interface LauncherEffect {
    data class ShowToast(val message: String) : LauncherEffect
    data class OpenDisplay(val containerId: String) : LauncherEffect
}

class LauncherViewModel(application: Application) : AndroidViewModel(application) {
    private val containerStore = ContainerStore(application)
    private val rootfsManager = RootfsManager(application)
    private val appPrefs = AppPrefs(application)

    private val _uiState =
        MutableStateFlow(
            LauncherUiState(themeMode = ThemeMode.fromId(appPrefs.themeMode))
        )
    val uiState: StateFlow<LauncherUiState> = _uiState.asStateFlow()

    private val _effects = Channel<LauncherEffect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    private val refreshRequests = Channel<Unit>(Channel.CONFLATED)
    private val activeDownloads = ConcurrentHashMap<String, String>()

    private val sessionListener: () -> Unit = { refresh() }

    init {
        ContainerManager.addListener(sessionListener)
        viewModelScope.launch(Dispatchers.IO) {
            for (ignored in refreshRequests) {
                loadSnapshot()
            }
        }
        refresh()
    }

    override fun onCleared() {
        ContainerManager.removeListener(sessionListener)
        refreshRequests.close()
        super.onCleared()
    }

    fun refresh() {
        refreshRequests.trySend(Unit)
    }

    private fun loadSnapshot() {
        val all = containerStore.getAll()
        val running = ContainerManager.runningIds()
        val cached = DistroRegistry.all.filter { rootfsManager.imageFile(it).exists() }.mapTo(hashSetOf()) { it.id }

        val containers =
            all.map { config ->
                val distro = DistroRegistry.byId(config.distroId)
                val status =
                    when {
                        config.id in running -> ContainerStatus.Running
                        rootfsManager.isContainerInstalled(config.id, distro) -> ContainerStatus.Ready
                        distro.id in cached -> ContainerStatus.ImageCached
                        else -> ContainerStatus.NeedsImage
                    }
                ContainerItem(config = config, distro = distro, status = status)
            }

        val distros =
            DistroRegistry.all.map { distro ->
                val progress = activeDownloads[distro.id]
                val state =
                    when {
                        progress != null -> ImageState.Downloading(progress)
                        distro.id in cached -> ImageState.Cached
                        else -> ImageState.NotDownloaded
                    }
                DistroItem(distro = distro, state = state)
            }

        _uiState.update { it.copy(containers = containers, distros = distros) }
    }

    fun onAction(action: LauncherAction) {
        when (action) {
            LauncherAction.CycleTheme -> {
                val next = _uiState.value.themeMode.next()
                appPrefs.themeMode = next.id
                _uiState.update { it.copy(themeMode = next) }
            }

            is LauncherAction.Launch -> launchContainer(action.container)
            is LauncherAction.Stop -> {
                viewModelScope.launch(Dispatchers.IO) {
                    ContainerManager.stopContainer(action.container.id)
                    refresh()
                }
            }

            is LauncherAction.Save -> {
                val updated = action.container
                viewModelScope.launch(Dispatchers.IO) {
                    val withId = if (updated.id.isEmpty()) updated.copy(id = containerStore.newId()) else updated
                    containerStore.upsert(withId)
                    refresh()
                }
            }

            is LauncherAction.ConfirmDelete -> {
                val container = action.container
                viewModelScope.launch(Dispatchers.IO) {
                    ContainerManager.stopContainer(container.id)
                    containerStore.delete(container.id)
                    refresh()
                    rootfsManager.deleteContainer(container.id)
                    refresh()
                }
            }

            is LauncherAction.DownloadImage -> startImageDownload(action.distro)
        }
    }

    private fun launchContainer(container: ContainerConfig) {
        viewModelScope.launch(Dispatchers.IO) {
            val distro = DistroRegistry.byId(container.distroId)
            val canBoot = rootfsManager.isContainerInstalled(container.id, distro) || rootfsManager.hasImage(distro)
            if (!canBoot) {
                _effects.trySend(LauncherEffect.ShowToast("Download a ${distro.name} image first"))
                return@launch
            }
            _effects.trySend(LauncherEffect.OpenDisplay(container.id))
        }
    }

    private fun startImageDownload(distro: Distro) {
        if (activeDownloads.putIfAbsent(distro.id, "Starting...") != null) return
        updateDistroState(distro.id, ImageState.Downloading("Starting..."))
        viewModelScope.launch(Dispatchers.IO) {
            val error =
                runCatching {
                    val ok =
                        rootfsManager.downloadImage(distro) { msg ->
                            activeDownloads[distro.id] = msg
                            updateDistroState(distro.id, ImageState.Downloading(msg))
                        }
                    if (!ok) "Download failed for ${distro.name}" else null
                }.getOrElse { e ->
                    "Download failed for ${distro.name}: ${e.message ?: "unknown error"}"
                }
            activeDownloads.remove(distro.id)
            _effects.trySend(
                LauncherEffect.ShowToast(error ?: "Downloaded ${distro.name} image")
            )
            refresh()
        }
    }

    private fun updateDistroState(distroId: String, state: ImageState) {
        _uiState.update { ui ->
            ui.copy(
                distros =
                ui.distros.map { item ->
                    if (item.distro.id == distroId) item.copy(state = state) else item
                }
            )
        }
    }
}
