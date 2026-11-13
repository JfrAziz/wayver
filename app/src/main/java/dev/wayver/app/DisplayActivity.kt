package dev.wayver.app

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.ui.platform.ComposeView
import dev.wayver.app.container.ContainerConfig
import dev.wayver.app.container.ContainerManager
import dev.wayver.app.container.ContainerStore
import dev.wayver.app.container.DriverConfig
import dev.wayver.app.core.AppPrefs
import dev.wayver.app.core.NativeBridge
import dev.wayver.app.distro.Distro
import dev.wayver.app.distro.DistroRegistry
import dev.wayver.app.distro.ProotRunner
import dev.wayver.app.distro.RootfsManager
import dev.wayver.app.driver.DriverManager
import dev.wayver.app.input.KeyMapper
import dev.wayver.app.ui.QuickMenuOverlay
import dev.wayver.app.ui.QuickMenuState
import dev.wayver.app.ui.ThemeMode
import dev.wayver.app.utils.ContainerLogger
import java.io.File

class DisplayActivity : AppCompatActivity(), SurfaceHolder.Callback {
    companion object {
        const val EXTRA_CONTAINER_ID = "container_id"

        fun createIntent(context: Context, containerId: String): Intent =
            Intent(context, DisplayActivity::class.java).apply {
                data = Uri.parse("wayver://container/$containerId")
                putExtra(EXTRA_CONTAINER_ID, containerId)
                addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
            }
    }

    private lateinit var surfaceView: SurfaceView
    private lateinit var runtimeDirPath: String
    private lateinit var containerStore: ContainerStore
    private lateinit var containerConfig: ContainerConfig
    private lateinit var driverManager: DriverManager
    private lateinit var rootfsManager: RootfsManager
    private lateinit var prootRunner: ProotRunner
    private val quickMenuState = QuickMenuState()

    private var sessionHandle: Long = 0L
    private var activeDistro: Distro = DistroRegistry.alpine
    private var surfaceW = 0
    private var surfaceH = 0
    private var sessionStarted = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        containerStore = ContainerStore(this)
        val containerId = intent.getStringExtra(EXTRA_CONTAINER_ID) ?: intent.data?.lastPathSegment
        containerConfig = containerStore.getById(containerId)
        activeDistro = DistroRegistry.byId(containerConfig.distroId)
        updateTaskDescription()

        val logger =
            ContainerLogger("LinuxDesktop") { line ->
                runOnUiThread { quickMenuState.appendLog(line) }
            }
        driverManager = DriverManager(filesDir)
        rootfsManager = RootfsManager(this, logger)
        prootRunner = ProotRunner(this, rootfsManager, containerConfig.id, logger)

        runtimeDirPath = prootRunner.xdgRuntimeDir.absolutePath
        sessionHandle = NativeBridge.nativeCreateSession()
        NativeBridge.nativeInitRuntime(sessionHandle, runtimeDirPath)
        ContainerManager.register(containerConfig.id, this, prootRunner)

        val (nativeW, nativeH) = nativeDisplaySize()
        val resConfig = containerConfig.resolution
        val isTrueNative =
            resConfig is dev.wayver.app.container.ResolutionConfig.Preset &&
                resConfig.preset == dev.wayver.app.container.ResolutionPreset.NATIVE
        val (reqW, reqH) = resConfig.resolve(nativeW, nativeH)
        surfaceView =
            SurfaceView(this).apply {
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
                    val res = containerConfig.resolution
                    if (res is dev.wayver.app.container.ResolutionConfig.Preset &&
                        res.preset.nativeScale != null && res.preset.nativeScale != 1.0f
                    ) {
                        val (targetW, targetH) = res.resolve(newW, newH)
                        if (targetW > 0 && targetH > 0 && (targetW != surfaceW || targetH != surfaceH)) {
                            v.post { holder.setFixedSize(targetW, targetH) }
                        }
                    }
                }
                setOnTouchListener { v, event ->
                    v.requestFocus()
                    val idx = event.actionIndex
                    val id = event.getPointerId(idx)
                    val scaleX = if (v.width > 0 && surfaceW > 0) surfaceW.toFloat() / v.width.toFloat() else 1f
                    val scaleY = if (v.height > 0 && surfaceH > 0) surfaceH.toFloat() / v.height.toFloat() else 1f
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN ->
                            NativeBridge.nativeOnTouch(
                                sessionHandle,
                                0,
                                id,
                                event.getX(idx) * scaleX,
                                event.getY(idx) * scaleY
                            )

                        MotionEvent.ACTION_MOVE -> {
                            for (i in 0 until event.pointerCount) {
                                NativeBridge.nativeOnTouch(
                                    sessionHandle,
                                    1,
                                    event.getPointerId(i),
                                    event.getX(i) * scaleX,
                                    event.getY(i) * scaleY
                                )
                            }
                        }

                        MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL ->
                            NativeBridge.nativeOnTouch(
                                sessionHandle,
                                2,
                                id,
                                event.getX(idx) * scaleX,
                                event.getY(idx) * scaleY
                            )
                    }
                    true
                }
            }

        quickMenuState.themeMode = ThemeMode.fromId(AppPrefs(this).themeMode)
        val overlayView =
            ComposeView(this).apply {
                setContent {
                    QuickMenuOverlay(
                        state = quickMenuState,
                        container = containerConfig,
                        distro = activeDistro,
                        onToggleKeyboard = {
                            surfaceView.requestFocus()
                            @Suppress("DEPRECATION")
                            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                                .toggleSoftInput(InputMethodManager.SHOW_FORCED, 0)
                        },
                        onRestartSession = { bootDesktop(containerConfig.desktopId) },
                        onMainMenu = {
                            startActivity(
                                Intent(this@DisplayActivity, MainActivity::class.java)
                                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                            )
                        },
                        onSwitchContainer = { target ->
                            startActivity(createIntent(this@DisplayActivity, target.id))
                        },
                        onStopContainer = {
                            prootRunner.stop()
                            finishAndRemoveTask()
                        },
                        onRefreshStatus = {
                            quickMenuState.themeMode = ThemeMode.fromId(AppPrefs(this@DisplayActivity).themeMode)
                            quickMenuState.nativeStatus = NativeBridge.nativeGetStatus(sessionHandle)
                            quickMenuState.surfaceSize = "${surfaceW}x$surfaceH"
                            quickMenuState.allContainers = containerStore.getAll()
                            quickMenuState.runningIds = ContainerManager.runningIds()
                        }
                    )
                }
            }

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
    }

    private fun bootDesktop(desktopId: String) {
        val spec = activeDistro.desktopById(desktopId)
        spec.prepare(this, rootfsManager.rootfsDir(containerConfig.id))
        prootRunner.runCommand(activeDistro, spec.launch)
    }

    private fun loadBundledTurnip() {
        runCatching {
            assets.open("Maleoon-Turnip-Display-v0.2.0.zip").use { stream ->
                driverManager.importZip(stream, "maleoon_turnip").getOrThrow()
            }
        }.onSuccess { drv ->
            NativeBridge.nativeReloadDriver(sessionHandle, drv.libraryPath, surfaceW, surfaceH)
            NativeBridge.nativeInitRuntime(sessionHandle, runtimeDirPath)
            quickMenuState.appendLog("Loaded bundled driver: ${drv.name} (${drv.format})")
            quickMenuState.nativeStatus = NativeBridge.nativeGetStatus(sessionHandle)
        }.onFailure { err ->
            quickMenuState.appendLog("Bundled driver failed: ${err.message}")
            quickMenuState.nativeStatus = NativeBridge.nativeGetStatus(sessionHandle)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterFullscreen()
    }

    private fun enterFullscreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let { ctrl ->
                ctrl.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                ctrl.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                )
        }
    }

    override fun onResume() {
        super.onResume()
        quickMenuState.themeMode = ThemeMode.fromId(AppPrefs(this).themeMode)
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
        @Suppress("DEPRECATION")
        if (event.action == KeyEvent.ACTION_MULTIPLE && event.characters != null) {
            @Suppress("DEPRECATION")
            for (ch in event.characters!!) {
                val mapped = KeyMapper.fromChar(ch)
                if (mapped != null) {
                    val mods = if (mapped.shift) 1 else 0
                    NativeBridge.nativeOnKey(sessionHandle, mapped.code, true, mods)
                    NativeBridge.nativeOnKey(sessionHandle, mapped.code, false, 0)
                }
            }
            return true
        }
        val evdev = KeyMapper.toEvdev(event.keyCode)
        if (evdev != null && (event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP)) {
            NativeBridge.nativeOnKey(
                sessionHandle,
                evdev,
                event.action == KeyEvent.ACTION_DOWN,
                KeyMapper.modifiers(event)
            )
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        NativeBridge.nativeOnSurfaceCreated(sessionHandle, holder.surface)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        surfaceW = width
        surfaceH = height
        quickMenuState.surfaceSize = "${width}x$height"
        NativeBridge.nativeOnSurfaceChanged(sessionHandle, width, height)

        if (!sessionStarted) {
            sessionStarted = true
            when (val drv = containerConfig.driver) {
                is DriverConfig.BundledTurnip -> loadBundledTurnip()
                is DriverConfig.Imported -> {
                    val path = drv.driverPath
                    if (!path.isNullOrBlank() && File(path).exists()) {
                        NativeBridge.nativeReloadDriver(sessionHandle, path, width, height)
                        NativeBridge.nativeInitRuntime(sessionHandle, runtimeDirPath)
                        quickMenuState.appendLog("Loaded container driver: $path")
                    } else {
                        quickMenuState.appendLog("Imported driver not found, using System Vulkan")
                    }
                }

                is DriverConfig.System -> Unit
            }

            quickMenuState.nativeStatus = NativeBridge.nativeGetStatus(sessionHandle)
            bootDesktop(containerConfig.desktopId)
        }
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
        NativeBridge.nativeOnSurfaceDestroyed(sessionHandle)
    }

    override fun onDestroy() {
        ContainerManager.unregister(containerConfig.id, this)
        prootRunner.stop()
        if (sessionHandle != 0L) {
            NativeBridge.nativeDestroySession(sessionHandle)
            sessionHandle = 0L
        }
        super.onDestroy()
    }
}
