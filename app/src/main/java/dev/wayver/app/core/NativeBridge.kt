package dev.wayver.app.core

import android.view.Surface

object NativeBridge {
    init {
        System.loadLibrary("wayver")
    }

    external fun nativeCreateSession(): Long

    external fun nativeDestroySession(handle: Long)

    external fun nativeInitRuntime(handle: Long, runtimeDir: String)

    external fun nativeGetStatus(handle: Long): String

    external fun nativeOnSurfaceCreated(handle: Long, surface: Surface)

    external fun nativeOnSurfaceChanged(handle: Long, width: Int, height: Int)

    external fun nativeOnSurfaceDestroyed(handle: Long)

    external fun nativeOnTouch(handle: Long, action: Int, id: Int, x: Float, y: Float)

    external fun nativeOnKey(handle: Long, evdevKey: Int, pressed: Boolean, mods: Int)

    external fun nativeRunSelfTest(runtimeDir: String, w: Int, h: Int): Boolean

    external fun nativeReloadDriver(handle: Long, driverPath: String?, w: Int, h: Int): String
}
