package dev.wayver.app.container

import android.content.Context
import dev.wayver.app.core.AppPrefs
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

class ContainerStore(context: Context) {
    private val prefs = context.getSharedPreferences("wayver_containers", Context.MODE_PRIVATE)
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
        resolution = ResolutionConfig.Preset(ResolutionPreset.NATIVE)
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
        put(
            "env",
            JSONObject().apply {
                put("disabledPresets", JSONArray(c.env.disabledPresets.sorted()))
                put(
                    "presetEdits",
                    JSONObject().apply {
                        c.env.presetEdits.forEach { (app, vars) ->
                            put(
                                app,
                                JSONObject().apply {
                                    vars.forEach { (k, v) -> put(k, v) }
                                }
                            )
                        }
                    }
                )
                put(
                    "overrides",
                    JSONObject().apply {
                        c.env.overrides.forEach { (k, v) -> put(k, v) }
                    }
                )
            }
        )
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
        val envObj = o.optJSONObject("env")
        val disabledPresets =
            buildSet {
                val arr = envObj?.optJSONArray("disabledPresets") ?: o.optJSONArray("disabledAppEnvs")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val item = arr.optString(i).trim()
                        if (item.isNotEmpty()) add(item)
                    }
                }
            }
        val presetEdits =
            buildMap {
                val editsObj = envObj?.optJSONObject("presetEdits")
                if (editsObj != null) {
                    val apps = editsObj.keys()
                    while (apps.hasNext()) {
                        val app = apps.next().trim()
                        val varsObj = editsObj.optJSONObject(app)
                        if (app.isNotEmpty() && varsObj != null) {
                            val vars =
                                buildMap {
                                    val keys = varsObj.keys()
                                    while (keys.hasNext()) {
                                        val k = keys.next().trim()
                                        if (k.isNotEmpty()) put(k, varsObj.optString(k))
                                    }
                                }
                            put(app, vars)
                        }
                    }
                }
            }
        val overrides =
            buildMap {
                val obj = envObj?.optJSONObject("overrides") ?: o.optJSONObject("customEnv")
                if (obj != null) {
                    val keys = obj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next().trim()
                        if (k.isNotEmpty()) put(k, obj.optString(k))
                    }
                }
            }
        return ContainerConfig(
            id = o.optString("id", newId()),
            name = o.optString("name", "Linux Container"),
            distroId = o.optString("distroId", "alpine"),
            desktopId = o.optString("desktopId", "labwc"),
            resolution = resolution,
            env = EnvConfig(disabledPresets = disabledPresets, presetEdits = presetEdits, overrides = overrides)
        )
    }

    companion object {
        private const val KEY_CONTAINERS = "containers_json"
    }
}
