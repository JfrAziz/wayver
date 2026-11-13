package dev.wayver.app.driver

import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream
import org.json.JSONObject

data class InstalledDriver(
    val id: String,
    val name: String,
    val libraryPath: String,
    val icdJsonPath: String?,
    val format: String
)

class DriverManager(private val baseDir: File) {
    private val driversDir = File(baseDir, "drivers").apply { mkdirs() }

    fun listDrivers(): List<InstalledDriver> {
        val out = mutableListOf<InstalledDriver>()
        driversDir.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }?.forEach { dir ->
            val metaFile = File(dir, "driver_info.json")
            if (metaFile.exists()) {
                runCatching {
                    val j = JSONObject(metaFile.readText())
                    val lib = File(dir, j.getString("libraryName"))
                    if (lib.exists()) {
                        val icd =
                            j.optString("icdJson", "").takeIf { it.isNotEmpty() }
                                ?.let { File(dir, it).takeIf(File::exists)?.absolutePath }
                        out.add(
                            InstalledDriver(
                                id = dir.name,
                                name = j.optString("name", dir.name),
                                libraryPath = lib.absolutePath,
                                icdJsonPath = icd,
                                format = j.optString("format", "unknown")
                            )
                        )
                    }
                }
            }
        }
        return out
    }

    fun importZip(zipStream: InputStream, fallbackName: String): Result<InstalledDriver> = runCatching {
        val safeId =
            fallbackName.replace(Regex("[^a-zA-Z0-9._-]"), "_").ifEmpty {
                "driver_${System.currentTimeMillis()}"
            }
        val destDir =
            File(driversDir, safeId).apply {
                if (exists()) deleteRecursively()
                mkdirs()
            }

        ZipInputStream(zipStream.buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val outFile = File(destDir, File(entry.name).name)
                    outFile.outputStream().use { zis.copyTo(it) }
                    if (outFile.name.endsWith(".so")) {
                        outFile.setExecutable(true, false)
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        val metaJson = File(destDir, "meta.json")
        if (metaJson.exists()) {
            val meta = JSONObject(metaJson.readText())
            val libName = meta.getString("libraryName")
            val name = meta.optString("name", safeId)
            val libFile = File(destDir, libName)
            require(libFile.exists()) { "Missing libraryName '$libName' in zip" }

            saveInfo(destDir, name, libName, null, "adrenotools")
            return@runCatching InstalledDriver(safeId, name, libFile.absolutePath, null, "adrenotools")
        }

        val icdFile =
            destDir.listFiles()?.firstOrNull {
                it.name.endsWith(".json") && it.name.contains("icd", ignoreCase = true)
            }
        if (icdFile != null) {
            val icd = JSONObject(icdFile.readText())
            val libPathRaw = icd.getJSONObject("ICD").getString("library_path")
            val libName = File(libPathRaw).name
            val libFile = File(destDir, libName)
            require(libFile.exists()) { "Missing ICD library '$libName' in zip" }

            saveInfo(destDir, safeId, libName, icdFile.name, "vulkan-icd")
            return@runCatching InstalledDriver(
                safeId,
                safeId,
                libFile.absolutePath,
                icdFile.absolutePath,
                "vulkan-icd"
            )
        }

        val firstSo =
            destDir.listFiles()?.firstOrNull { it.name.endsWith(".so") }
                ?: error("No meta.json, ICD .json, or .so found in driver zip")
        saveInfo(destDir, safeId, firstSo.name, null, "raw-so")
        InstalledDriver(safeId, safeId, firstSo.absolutePath, null, "raw-so")
    }

    private fun saveInfo(dir: File, name: String, libName: String, icdJson: String?, format: String) {
        val info =
            JSONObject().apply {
                put("name", name)
                put("libraryName", libName)
                if (icdJson != null) put("icdJson", icdJson)
                put("format", format)
            }
        File(dir, "driver_info.json").writeText(info.toString(2))
    }
}
