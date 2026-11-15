package dev.wayver.app.ui.display

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
import dev.wayver.app.ui.ThemeMode
import dev.wayver.app.ui.components.LogBuffer
import dev.wayver.app.ui.components.LogEntry
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class BootStage(val segmentIndex: Int) {
    Extracting(0),
    Preparing(1),
    Launching(2)
}

sealed interface DisplayPhase {
    data class Booting(
        val stage: BootStage? = null,
        val statusText: String,
        val progress: Float? = null
    ) : DisplayPhase

    data class Failed(val errorText: String) : DisplayPhase

    data class Running(val nativeStatus: String = "Initializing...") : DisplayPhase
}

enum class SwitchItemState {
    Current,
    Running,
    Stopped
}

data class SwitchContainerItem(
    val config: ContainerConfig = ContainerConfig(id = "", name = "", distroId = DistroRegistry.alpine.id),
    val distro: Distro = DistroRegistry.alpine,
    val desktopName: String = "",
    val state: SwitchItemState = SwitchItemState.Current
)

data class DisplayUiState(
    val active: SwitchContainerItem = SwitchContainerItem(),
    val themeMode: ThemeMode = ThemeMode.AUTO,
    val phase: DisplayPhase = DisplayPhase.Booting(statusText = "Checking container..."),
    val surfaceSize: String = "0x0",
    val containers: List<SwitchContainerItem> = emptyList()
)

sealed interface DisplayAction {
    data object RetryBoot : DisplayAction
    data object CancelBoot : DisplayAction
    data object ToggleKeyboard : DisplayAction
    data object RefreshQuickMenu : DisplayAction
    data object RestartSession : DisplayAction
    data object OpenMainMenu : DisplayAction
    data class SwitchContainer(val target: ContainerConfig) : DisplayAction
    data object StopContainer : DisplayAction
    data object ClearLogs : DisplayAction
}

val LocalDisplayAction = compositionLocalOf<(DisplayAction) -> Unit> { {} }

sealed interface DisplayEffect {
    data object BootDesktop : DisplayEffect
    data object ToggleKeyboard : DisplayEffect
    data object RefreshNativeStatus : DisplayEffect
    data object OpenMainMenu : DisplayEffect
    data class SwitchContainer(val containerId: String) : DisplayEffect
    data object StopAndExit : DisplayEffect
}

@OptIn(FlowPreview::class)
private class LogRing(private val maxSize: Int) {
    private val entries = ArrayDeque<LogEntry>(maxSize)
    private var version = 0L
    private val ticks =
        MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    @Synchronized
    fun append(line: String) {
        if (entries.size >= maxSize) entries.removeFirst()
        entries.addLast(LogEntry(++version, line))
        ticks.tryEmit(Unit)
    }

    @Synchronized
    fun clear(): LogBuffer {
        entries.clear()
        return LogBuffer(emptyList(), ++version)
    }

    @Synchronized
    fun snapshot(): LogBuffer = LogBuffer(entries.toList(), version)

    fun sampledSnapshots(): Flow<LogBuffer> =
        ticks.sample(100.milliseconds).map { snapshot() }.flowOn(Dispatchers.Default)
}

class DisplayViewModel(application: Application) : AndroidViewModel(application) {
    private val containerStore = ContainerStore(application)
    private val appPrefs = AppPrefs(application)

    private val _uiState =
        MutableStateFlow(DisplayUiState(themeMode = ThemeMode.fromId(appPrefs.themeMode)))
    val uiState: StateFlow<DisplayUiState> = _uiState.asStateFlow()

    private val _logs = MutableStateFlow(LogBuffer())
    val logs: StateFlow<LogBuffer> = _logs.asStateFlow()

    private val _effects = Channel<DisplayEffect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    private val logRing = LogRing(MAX_LOGS)

    init {
        logRing.sampledSnapshots()
            .onEach { _logs.value = it }
            .launchIn(viewModelScope)
    }

    fun initSession(container: ContainerConfig, distro: Distro) {
        _uiState.update {
            it.copy(
                active =
                SwitchContainerItem(
                    config = container,
                    distro = distro,
                    desktopName = distro.desktopById(container.desktopId).name,
                    state = SwitchItemState.Current
                ),
                themeMode = ThemeMode.fromId(appPrefs.themeMode)
            )
        }
    }

    fun onAction(action: DisplayAction) {
        when (action) {
            DisplayAction.RetryBoot,
            DisplayAction.RestartSession -> _effects.trySend(DisplayEffect.BootDesktop)

            DisplayAction.CancelBoot,
            DisplayAction.StopContainer -> _effects.trySend(DisplayEffect.StopAndExit)

            DisplayAction.ToggleKeyboard -> _effects.trySend(DisplayEffect.ToggleKeyboard)
            DisplayAction.RefreshQuickMenu -> {
                refreshEnvironment()
                _effects.trySend(DisplayEffect.RefreshNativeStatus)
            }

            DisplayAction.OpenMainMenu -> _effects.trySend(DisplayEffect.OpenMainMenu)
            is DisplayAction.SwitchContainer ->
                _effects.trySend(DisplayEffect.SwitchContainer(action.target.id))

            DisplayAction.ClearLogs -> _logs.value = logRing.clear()
        }
    }

    fun refreshTheme() {
        _uiState.update { it.copy(themeMode = ThemeMode.fromId(appPrefs.themeMode)) }
    }

    fun refreshEnvironment() {
        val currentId = _uiState.value.active.config.id
        viewModelScope.launch(Dispatchers.IO) {
            val all = containerStore.getAll()
            val running = ContainerManager.runningIds()
            val theme = ThemeMode.fromId(appPrefs.themeMode)
            val items =
                all.map { item ->
                    val distro = DistroRegistry.byId(item.distroId)
                    val state =
                        when (item.id) {
                            currentId -> SwitchItemState.Current
                            in running -> SwitchItemState.Running
                            else -> SwitchItemState.Stopped
                        }
                    SwitchContainerItem(
                        config = item,
                        distro = distro,
                        desktopName = distro.desktopById(item.desktopId).name,
                        state = state
                    )
                }
            _uiState.update { it.copy(themeMode = theme, containers = items) }
        }
    }

    fun updateSurfaceSize(size: String) {
        _uiState.update { it.copy(surfaceSize = size) }
    }

    fun updateNativeStatus(status: String) {
        _uiState.update { state ->
            if (state.phase is DisplayPhase.Running) {
                state.copy(phase = DisplayPhase.Running(nativeStatus = status))
            } else {
                state
            }
        }
    }

    fun appendLog(rawLine: String) {
        val line = rawLine.trim()
        if (line.isEmpty()) return
        logRing.append(line)
        if (_uiState.value.phase !is DisplayPhase.Booting) return

        val (nextStatus, nextProgress) = parseBootOutput(line)
        if (nextStatus != null || nextProgress != null) {
            _uiState.update { state ->
                val current = state.phase as? DisplayPhase.Booting ?: return@update state
                state.copy(
                    phase =
                    current.copy(
                        statusText = nextStatus ?: current.statusText,
                        progress = nextProgress ?: current.progress
                    )
                )
            }
        }
    }

    fun startBoot(statusText: String) {
        _logs.value = logRing.clear()
        _uiState.update { it.copy(phase = DisplayPhase.Booting(statusText = statusText)) }
    }

    fun updateBootStage(stage: BootStage, statusText: String, clearProgress: Boolean = false) {
        _uiState.update { state ->
            val current = state.phase as? DisplayPhase.Booting
            state.copy(
                phase =
                DisplayPhase.Booting(
                    stage = stage,
                    statusText = statusText,
                    progress = if (clearProgress) null else current?.progress
                )
            )
        }
    }

    private fun parseBootOutput(rawLine: String): Pair<String?, Float?> {
        val line = rawLine.replaceFirst(DISTRO_LOG_PREFIX_REGEX, "")
        val stepMatch = STEP_PROGRESS_REGEX.find(line)
        val progress =
            stepMatch?.let { match ->
                val step = match.groupValues[1].toFloatOrNull()
                val total = match.groupValues[2].toFloatOrNull()
                if (step != null && total != null && total > 0f) (step / total).coerceIn(0f, 1f) else null
            }

        val status =
            when {
                line.startsWith(":: Synchronizing") -> "Synchronizing package databases..."
                line.startsWith(":: Retrieving packages") -> "Downloading packages..."
                line.startsWith(":: Processing package changes") -> "Installing packages..."
                line.startsWith(":: Running post-transaction hooks") -> "Running post-install hooks..."
                line.startsWith("Get:") || line.startsWith("Hit:") -> "Updating package lists..."
                line.startsWith("Unpacking ") || line.startsWith("Setting up ") -> line
                stepMatch != null -> line
                else -> null
            }

        return status to progress
    }

    fun failBoot(errorText: String) {
        _uiState.update {
            it.copy(phase = DisplayPhase.Failed(errorText = errorText))
        }
    }

    fun markRunningIfLaunching(nativeStatus: String = "Initializing...") {
        _uiState.update { state ->
            val current = state.phase as? DisplayPhase.Booting
            if (current?.stage == BootStage.Launching) {
                state.copy(phase = DisplayPhase.Running(nativeStatus = nativeStatus))
            } else {
                state
            }
        }
    }

    private companion object {
        const val MAX_LOGS = 200
        val DISTRO_LOG_PREFIX_REGEX = Regex("""^\[[^\]]+]\s*""")
        val STEP_PROGRESS_REGEX = Regex("""\((\d+)/(\d+)\)""")
    }
}
