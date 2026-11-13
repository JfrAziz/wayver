package dev.wayver.app.utils

import android.content.Context
import java.io.File

object AssetCopy {
    fun copyAssetTree(context: Context, assetPath: String, destDir: File) {
        val entries = context.assets.list(assetPath) ?: return
        if (entries.isEmpty()) {
            destDir.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                destDir.outputStream().use { output -> input.copyTo(output) }
            }
            return
        }
        destDir.mkdirs()
        for (name in entries) {
            copyAssetTree(context, "$assetPath/$name", File(destDir, name))
        }
    }

    fun copyAssetFileIfMissing(context: Context, assetPath: String, destFile: File) {
        if (destFile.exists()) return
        destFile.parentFile?.mkdirs()
        runCatching {
            context.assets.open(assetPath).use { input ->
                destFile.outputStream().use { output -> input.copyTo(output) }
            }
        }
    }
}
