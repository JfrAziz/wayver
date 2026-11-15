package dev.wayver.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import dev.wayver.app.container.ContainerConfig
import dev.wayver.app.container.ContainerManager
import dev.wayver.app.container.ContainerStore
import dev.wayver.app.core.AppPrefs
import dev.wayver.app.distro.Distro
import dev.wayver.app.distro.DistroRegistry
import dev.wayver.app.distro.RootfsManager
import dev.wayver.app.ui.LauncherScreen
import dev.wayver.app.ui.LauncherUiState
import dev.wayver.app.ui.ThemeMode
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    private lateinit var containerStore: ContainerStore
    private lateinit var rootfsManager: RootfsManager
    private lateinit var appPrefs: AppPrefs
    private val uiState = LauncherUiState()

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val sessionListener: () -> Unit = {
        runOnUiThread { refreshState() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        containerStore = ContainerStore(this)
        rootfsManager = RootfsManager(this)
        appPrefs = AppPrefs(this)
        uiState.themeMode = ThemeMode.fromId(appPrefs.themeMode)

        setContent {
            LauncherScreen(
                state = uiState,
                onCycleThemeMode = {
                    val next = uiState.themeMode.next()
                    uiState.themeMode = next
                    appPrefs.themeMode = next.id
                },
                onLaunchContainer = ::launchContainer,
                onStopContainer = { container ->
                    ContainerManager.stopContainer(container.id)
                    refreshState()
                },
                onSaveContainer = { updated ->
                    val withId = if (updated.id.isEmpty()) updated.copy(id = containerStore.newId()) else updated
                    containerStore.upsert(withId)
                    refreshState()
                },
                onDeleteContainer = { container ->
                    ContainerManager.stopContainer(container.id)
                    containerStore.delete(container.id)
                    refreshState()
                    thread(name = "delete-${container.id}") {
                        rootfsManager.deleteContainer(container.id)
                    }
                },
                onDownloadImage = ::startImageDownload
            )
        }

        ContainerManager.addListener(sessionListener)
        requestNotificationPermissionIfNeeded()
        refreshState()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onResume() {
        super.onResume()
        refreshState()
    }

    override fun onDestroy() {
        ContainerManager.removeListener(sessionListener)
        super.onDestroy()
    }

    private fun refreshState() {
        val all = containerStore.getAll()
        uiState.containers = all
        uiState.runningIds = ContainerManager.runningIds()
        uiState.installedContainers =
            all.filter {
                rootfsManager.isContainerInstalled(it.id, DistroRegistry.byId(it.distroId))
            }.map { it.id }.toSet()
        uiState.cachedDistros =
            DistroRegistry.all.filter {
                rootfsManager.imageFile(it).exists()
            }.map { it.id }.toSet()
    }

    private fun launchContainer(container: ContainerConfig) {
        val distro = DistroRegistry.byId(container.distroId)
        val canBoot = rootfsManager.isContainerInstalled(container.id, distro) || rootfsManager.hasImage(distro)
        if (!canBoot) {
            toast("Download a ${distro.name} image first")
            return
        }
        startActivity(DisplayActivity.createIntent(this, container.id))
    }

    private fun startImageDownload(distro: Distro) {
        if (uiState.downloadProgress.containsKey(distro.id)) return
        uiState.downloadProgress[distro.id] = "Starting..."
        thread(name = "dl-${distro.id}") {
            val ok =
                runCatching {
                    rootfsManager.downloadImage(distro) { msg ->
                        runOnUiThread { uiState.downloadProgress[distro.id] = msg }
                    }
                }.getOrDefault(false)
            runOnUiThread {
                uiState.downloadProgress.remove(distro.id)
                toast(if (ok) "Downloaded ${distro.name} image" else "Download failed for ${distro.name}")
                refreshState()
            }
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
