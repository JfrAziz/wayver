package dev.wayver.app

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.wayver.app.container.ContainerConfig
import dev.wayver.app.container.ContainerManager
import dev.wayver.app.container.ContainerStore
import dev.wayver.app.container.ResolutionPreset
import dev.wayver.app.core.NativeBridge
import dev.wayver.app.distro.Distro
import dev.wayver.app.distro.DistroRegistry
import dev.wayver.app.distro.PrepareContext
import dev.wayver.app.distro.ProotRunner
import dev.wayver.app.distro.RootfsManager
import dev.wayver.app.input.KeyMapper
import dev.wayver.app.ui.display.BootStage
import dev.wayver.app.ui.display.DisplayAction
import dev.wayver.app.ui.display.DisplayEffect
import dev.wayver.app.ui.display.DisplayPhase
import dev.wayver.app.ui.display.DisplayScreen
import dev.wayver.app.ui.display.DisplayViewModel
import dev.wayver.app.utils.ContainerLogger
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class DisplayActivity : AppCompatActivity(), SurfaceHolder.Callback {
    companion object {
        const val EXTRA_CONTAINER_ID = "container_id"

        fun createIntent(context: Context, containerId: String): Intent =
            Intent(context, DisplayActivity::class.java).apply {
                data = Uri.parse("wayver://container/$containerId")
                putExtra(EXTRA_CONTAINER_ID, containerId)
                addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
    }

    private val viewModel: DisplayViewModel by viewModels()

    private lateinit var surfaceView: SurfaceView
    private lateinit var containerConfig: ContainerConfig
    private lateinit var activeDistro: Distro
    private lateinit var rootfsManager: RootfsManager
    private lateinit var prootRunner: ProotRunner

    private var session: Long = 0L

    private val heldKeys = mutableSetOf<Int>()

    private var bootJob: Job? = null
    private var isQuickMenuOpen = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val containerId = intent.getStringExtra(EXTRA_CONTAINER_ID) ?: intent.data?.lastPathSegment

        containerConfig = ContainerStore(this).getById(containerId)

        activeDistro = DistroRegistry.byId(containerConfig.distroId)

        viewModel.initSession(containerConfig, activeDistro)

        updateTaskDescription()

        val logger = ContainerLogger("Wayver") { line -> viewModel.appendLog(line) }

        rootfsManager = RootfsManager(this, logger)
        prootRunner = ProotRunner(this, rootfsManager, containerConfig.id, logger)

        session = NativeBridge.nativeCreateSession()
        NativeBridge.nativeSetRefreshRate(session, getDisplayRefreshRateHz())
        NativeBridge.nativeInitRuntime(session, prootRunner.xdgRuntimeDir.absolutePath)
        ContainerManager.register(containerConfig, this, prootRunner)
        setupClipboardSync()

        surfaceView = createSurfaceView()
        val overlayView = createOverlayView()

        val root = FrameLayout(this)
        root.addView(surfaceView)
        root.addView(
            overlayView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        setContentView(root)
        actionBar?.hide()
        enterFullscreen()
        surfaceView.requestFocus()

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (viewModel.uiState.value.phase is DisplayPhase.Running)
                        viewModel.onAction(DisplayAction.OpenMainMenu)
                    else
                        viewModel.onAction(DisplayAction.CancelBoot)
                }
            }
        )
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createSurfaceView(): SurfaceView {
        val (nativeW, nativeH) = nativeDisplaySize()
        val resPreset = containerConfig.resolution
        val isTrueNative = resPreset == ResolutionPreset.NATIVE
        val (reqW, reqH) = resPreset.resolve(nativeW, nativeH)
        return SurfaceView(this).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            if (!isTrueNative && reqW > 0 && reqH > 0) {
                holder.setFixedSize(reqW, reqH)
            }
            holder.addCallback(this@DisplayActivity)
            addOnLayoutChangeListener { v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
                val newW = right - left
                val newH = bottom - top
                val oldW = oldRight - oldLeft
                val oldH = oldBottom - oldTop
                if (newW <= 0 || newH <= 0 || (newW == oldW && newH == oldH)) return@addOnLayoutChangeListener
                if (!isTrueNative) {
                    val (targetW, targetH) = resPreset.resolve(newW, newH)
                    val frame = holder.surfaceFrame
                    if (targetW > 0 && targetH > 0 && (targetW != frame.width() || targetH != frame.height())) {
                        v.post { holder.setFixedSize(targetW, targetH) }
                    }
                }
            }
            setOnTouchListener { v, event ->
                v.requestFocus()
                val idx = event.actionIndex
                val id = event.getPointerId(idx)
                val frame = holder.surfaceFrame
                val scaleX =
                    if (v.width > 0 && frame.width() > 0) frame.width().toFloat() / v.width else 1f
                val scaleY =
                    if (v.height > 0 && frame.height() > 0) frame.height().toFloat() / v.height else 1f
                val touchState =
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> 0
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> 2
                        else -> -1
                    }
                if (touchState >= 0) {
                    NativeBridge.nativeOnTouch(
                        session,
                        touchState,
                        id,
                        event.getX(idx) * scaleX,
                        event.getY(idx) * scaleY
                    )
                } else if (event.actionMasked == MotionEvent.ACTION_MOVE) {
                    for (i in 0 until event.pointerCount) {
                        NativeBridge.nativeOnTouch(
                            session,
                            1,
                            event.getPointerId(i),
                            event.getX(i) * scaleX,
                            event.getY(i) * scaleY
                        )
                    }
                }
                true
            }
        }
    }

    private fun createOverlayView(): ComposeView = ComposeView(this).apply {
        setContent {
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            val lifecycleOwner = LocalLifecycleOwner.current

            LaunchedEffect(viewModel, lifecycleOwner) {
                lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    viewModel.effects.collect(::handleEffect)
                }
            }

            DisplayScreen(
                state = state,
                collectLogs = { viewModel.logs.collectAsStateWithLifecycle().value },
                onAction = viewModel::onAction,
                onMenuOpenChanged = { isQuickMenuOpen = it }
            )
        }
    }

    private fun handleEffect(effect: DisplayEffect) {
        when (effect) {
            DisplayEffect.BootDesktop -> bootDesktop(containerConfig.desktopId)
            DisplayEffect.ToggleKeyboard -> {
                surfaceView.requestFocus()
                @Suppress("DEPRECATION")
                (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .toggleSoftInput(InputMethodManager.SHOW_FORCED, 0)
            }

            DisplayEffect.RefreshNativeStatus ->
                viewModel.updateNativeStatus(NativeBridge.nativeGetStatus(session))

            DisplayEffect.OpenMainMenu -> openMainMenu()
            is DisplayEffect.SwitchContainer ->
                startActivity(createIntent(this, effect.containerId))

            DisplayEffect.StopAndExit -> {
                openMainMenu()
                ContainerManager.stopContainer(containerConfig.id)
            }
        }
    }

    private fun openMainMenu() {
        startActivity(
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun bootDesktop(desktopId: String) {
        val spec = activeDistro.desktopById(desktopId)
        viewModel.startBoot("Checking ${spec.name}...")

        val prev = bootJob
        prev?.cancel()
        prootRunner.stop()
        bootJob =
            lifecycleScope.launch(Dispatchers.IO) {
                prev?.cancelAndJoin()
                try {
                    if (!rootfsManager.isContainerInstalled(containerConfig.id, activeDistro)) {
                        viewModel.updateBootStage(
                            BootStage.Extracting,
                            "Extracting ${activeDistro.name} rootfs..."
                        )
                    }
                    if (!rootfsManager.ensureContainerReady(containerConfig.id, activeDistro)) {
                        viewModel.failBoot("Failed to prepare ${activeDistro.name} rootfs. Download the image first.")

                        return@launch
                    }

                    if (!rootfsManager.isDesktopPrepared(containerConfig.id, spec.id)) {
                        viewModel.updateBootStage(BootStage.Preparing, "Installing ${spec.name}...")
                        val prepareCtx =
                            PrepareContext(
                                context = this@DisplayActivity,
                                rootfsDir = rootfsManager.rootfsDir(containerConfig.id),
                                runInProot = { cmd ->
                                    prootRunner.runSetup(activeDistro, cmd, containerConfig.env)
                                }
                            )
                        if (!spec.prepare(prepareCtx)) {
                            viewModel.failBoot("Setup failed for ${spec.name}. Check logs and retry.")
                            return@launch
                        }
                        rootfsManager.markDesktopPrepared(containerConfig.id, activeDistro.id, spec.id)
                    }

                    viewModel.updateBootStage(
                        stage = BootStage.Launching,
                        statusText = "Starting ${spec.name}...",
                        clearProgress = true
                    )
                    prootRunner.runCommand(
                        cmd = spec.launch,
                        distro = activeDistro,
                        envConfig = containerConfig.env
                    )
                    delay(1500.milliseconds)
                    viewModel.markRunningIfLaunching(NativeBridge.nativeGetStatus(session))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    viewModel.failBoot(e.message ?: "Boot failed unexpectedly.")
                }
            }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            enterFullscreen()
            syncAndroidClipboardToGuest()
        } else {
            for (key in heldKeys.toList()) NativeBridge.nativeOnKey(session, key, false, 0)
            heldKeys.clear()
        }
    }

    private fun enterFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshTheme()
        updateTaskDescription()
    }

    private fun updateTaskDescription() {
        title = containerConfig.name
        val taskDesc =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ActivityManager.TaskDescription.Builder()
                    .setLabel(containerConfig.name)
                    .setIcon(activeDistro.iconRes)
                    .build()
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                @Suppress("DEPRECATION")
                ActivityManager.TaskDescription(containerConfig.name, activeDistro.iconRes)
            } else {
                @Suppress("DEPRECATION")
                ActivityManager.TaskDescription(containerConfig.name)
            }
        setTaskDescription(taskDesc)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (viewModel.uiState.value.phase !is DisplayPhase.Running || isQuickMenuOpen)
            return super.dispatchKeyEvent(event)

        @Suppress("DEPRECATION")
        if (event.action == KeyEvent.ACTION_MULTIPLE && event.characters != null) {
            @Suppress("DEPRECATION")
            for (ch in event.characters!!) {
                val mapped = KeyMapper.fromChar(ch)
                if (mapped != null) {
                    val mods = if (mapped.shift) 1 else 0
                    NativeBridge.nativeOnKey(session, mapped.code, true, mods)
                    NativeBridge.nativeOnKey(session, mapped.code, false, 0)
                }
            }
            return true
        }
        val evdev = KeyMapper.toEvdev(event.keyCode)
        if (evdev != null && (event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP)) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount > 0) {
                return true
            }
            val pressed = event.action == KeyEvent.ACTION_DOWN
            if (pressed) heldKeys.add(evdev) else heldKeys.remove(evdev)
            NativeBridge.nativeOnKey(
                session,
                evdev,
                pressed,
                KeyMapper.modifiers(event)
            )
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        NativeBridge.nativeOnSurfaceCreated(session, holder.surface)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        viewModel.updateSurfaceSize("${width}x$height")
        NativeBridge.nativeOnSurfaceChanged(session, width, height)

        if (bootJob == null) {
            bootDesktop(containerConfig.desktopId)
        }
    }

    private fun getDisplayRefreshRateHz(): Float {
        val hz =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                display?.refreshRate
            } else {
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay?.refreshRate
            }
        return (hz ?: 60f).coerceIn(30f, 240f)
    }

    private fun nativeDisplaySize(): Pair<Int, Int> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val bounds = windowManager.currentWindowMetrics.bounds
        bounds.width() to bounds.height()
    } else {
        @Suppress("DEPRECATION")
        val dm = resources.displayMetrics
        dm.widthPixels to dm.heightPixels
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        NativeBridge.nativeOnSurfaceDestroyed(session)
    }

    private var clipboardManager: ClipboardManager? = null
    private var lastSyncedClipboard: String? = null
    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener { syncAndroidClipboardToGuest() }

    private fun setupClipboardSync() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        clipboardManager = cm
        cm.addPrimaryClipChangedListener(clipboardListener)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    pollGuestClipboard()
                    delay(350.milliseconds)
                }
            }
        }
    }

    private fun syncAndroidClipboardToGuest() {
        if (session == 0L) return
        val text = clipboardManager?.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(this)?.toString()
        if (!text.isNullOrEmpty() && text != lastSyncedClipboard) {
            lastSyncedClipboard = text
            NativeBridge.nativeSetClipboard(session, text)
        }
    }

    private fun pollGuestClipboard() {
        if (session == 0L) return
        val text = NativeBridge.nativePollClipboard(session) ?: return
        if (text.isNotEmpty() && text != lastSyncedClipboard) {
            lastSyncedClipboard = text
            clipboardManager?.setPrimaryClip(ClipData.newPlainText("Wayver", text))
        }
    }

    override fun onDestroy() {
        clipboardManager?.removePrimaryClipChangedListener(clipboardListener)
        if (!isChangingConfigurations) {
            ContainerManager.unregister(containerConfig.id, this)
            prootRunner.stop()
            if (session != 0L) {
                NativeBridge.nativeDestroySession(session)
                session = 0L
            }
        }
        super.onDestroy()
    }
}
