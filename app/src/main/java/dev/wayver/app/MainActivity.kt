package dev.wayver.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.wayver.app.ui.lattePalette
import dev.wayver.app.ui.launcher.LauncherEffect
import dev.wayver.app.ui.launcher.LauncherScreen
import dev.wayver.app.ui.launcher.LauncherViewModel
import dev.wayver.app.ui.mochaPalette

class MainActivity : AppCompatActivity() {
    private val viewModel: LauncherViewModel by viewModels()

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            val lifecycleOwner = LocalLifecycleOwner.current
            val isDark = state.themeMode.isDark()

            LaunchedEffect(isDark) {
                val style =
                    if (isDark) {
                        SystemBarStyle.dark(mochaPalette.bg.toArgb())
                    } else {
                        SystemBarStyle.light(lattePalette.bg.toArgb(), lattePalette.bg.toArgb())
                    }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }

            LaunchedEffect(viewModel, lifecycleOwner) {
                lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    viewModel.effects.collect { effect ->
                        when (effect) {
                            is LauncherEffect.ShowToast ->
                                Toast.makeText(this@MainActivity, effect.message, Toast.LENGTH_SHORT).show()
                            is LauncherEffect.OpenDisplay ->
                                startActivity(DisplayActivity.createIntent(this@MainActivity, effect.containerId))
                        }
                    }
                }
            }

            LauncherScreen(
                state = state,
                onAction = viewModel::onAction
            )
        }

        requestNotificationPermissionIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
