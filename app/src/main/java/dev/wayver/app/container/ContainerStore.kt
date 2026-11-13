package dev.wayver.app.container

import android.content.Context
import dev.wayver.app.core.AppPrefs
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

class ContainerStore(context: Context) {
    private val prefs = context.getSharedPreferences("linux_desktop_containers", Context.MODE_PRIVATE)
    private val appPrefs = AppPrefs(context)

    fun getAll(): List<ContainerConfig> {
        val raw = prefs.getString(KEY_CONTAINERS, null)
        if (raw.isNullOrBlank()) {
            val defaults = listOf(createDefaultContainer())
            saveAll(defaults)
            return defaults
        }
        return runCatching {
            val arr = JSONArray(raw)
            val list = ArrayList<ContainerConfig>(arr.length())
            for (i in 0 until arr.length()) {
                list.add(fromJson(arr.getJSONObject(i)))
            }
            if (list.isEmpty()) listOf(createDefaultContainer()) else list
        }.getOrElse {
            listOf(createDefaultContainer())
        }
    }

    fun getById(id: String?): ContainerConfig {
        val all = getAll()
        return all.firstOrNull { it.id == id } ?: all.first()
    }

    fun upsert(container: ContainerConfig) {
        val list = getAll().toMutableList()
        val idx = list.indexOfFirst { it.id == container.id }
        if (idx >= 0) {
            list[idx] = container
        } else {
            list.add(container)
        }
        saveAll(list)
    }

    fun delete(id: String) {
        val list = getAll().filterNot { it.id == id }
        if (list.isNotEmpty()) {
            saveAll(list)
        }
    }

    fun newId(): String = UUID.randomUUID().toString().take(8)

    private fun saveAll(containers: List<ContainerConfig>) {
        val arr = JSONArray()
        containers.forEach { arr.put(toJson(it)) }
        prefs.edit().putString(KEY_CONTAINERS, arr.toString()).apply()
    }

    private fun createDefaultContainer(): ContainerConfig = ContainerConfig(
        id = "default-alpine-labwc",
        name = "Alpine + LabWC",
        distroId = appPrefs.activeDistroId,
        desktopId = "labwc",
        resolution = ResolutionConfig.Preset(ResolutionPreset.NATIVE),
        driver = DriverConfig.System
    )

    private fun toJson(c: ContainerConfig): JSONObject = JSONObject().apply {
        put("id", c.id)
        put("name", c.name)
        put("distroId", c.distroId)
        put("desktopId", c.desktopId)
        when (val res = c.resolution) {
            is ResolutionConfig.Preset -> {
                put("resolutionType", "preset")
                put("resolutionPreset", res.preset.id)
            }

            is ResolutionConfig.Custom -> {
                put("resolutionType", "custom")
                put("customWidth", res.width)
                put("customHeight", res.height)
            }
        }
        when (val drv = c.driver) {
            is DriverConfig.System -> put("driverType", "system")
            is DriverConfig.BundledTurnip -> put("driverType", "turnip")
            is DriverConfig.Imported -> {
                put("driverType", "imported")
                if (drv.driverPath != null) put("driverPath", drv.driverPath)
            }
        }
    }

    private fun fromJson(o: JSONObject): ContainerConfig {
        val resolution =
            when (o.optString("resolutionType", "preset")) {
                "custom" ->
                    ResolutionConfig.Custom(
                        width = o.optInt("customWidth", 1600).coerceIn(320, 4096),
                        height = o.optInt("customHeight", 1000).coerceIn(240, 4096)
                    )

                else ->
                    ResolutionConfig.Preset(
                        ResolutionPreset.fromId(o.optString("resolutionPreset", ResolutionPreset.NATIVE.id))
                    )
            }
        val driver =
            when (o.optString("driverType", "system")) {
                "turnip" -> DriverConfig.BundledTurnip
                "imported" ->
                    DriverConfig.Imported(
                        driverPath =
                        if (o.has("driverPath") && !o.isNull("driverPath")) {
                            o.optString(
                                "driverPath"
                            )
                        } else {
                            null
                        }
                    )

                else -> DriverConfig.System
            }
        return ContainerConfig(
            id = o.optString("id", newId()),
            name = o.optString("name", "Linux Container"),
            distroId = o.optString("distroId", "alpine"),
            desktopId = o.optString("desktopId", "labwc"),
            resolution = resolution,
            driver = driver
        )
    }

    companion object {
        private const val KEY_CONTAINERS = "containers_json"
    }
}
