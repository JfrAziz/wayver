package dev.wayver.app.utils

import android.util.Log

/**
 * Shared log sink for container setup and PRoot execution.
 * Writes every line to Android logcat and forwards it to an optional UI sink (e.g. TestingHud),
 * avoiding threading `onLog: (String) -> Unit` callbacks through every method signature.
 */
class ContainerLogger(
    private val tag: String = "LinuxDesktop",
    private val onLine: (String) -> Unit = {}
) {
    fun info(message: String) {
        Log.i(tag, message)
        onLine(message)
    }

    fun error(message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.e(tag, message, throwable)
            onLine("$message: ${throwable.message ?: throwable.javaClass.simpleName}")
        } else {
            Log.e(tag, message)
            onLine(message)
        }
    }
}
