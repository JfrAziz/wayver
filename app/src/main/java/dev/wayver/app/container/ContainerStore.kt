package dev.wayver.app.container

import android.content.Context
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

class ContainerStore(context: Context) {
    private val prefs = context.getSharedPreferences("wayver_containers", Context.MODE_PRIVATE)

    fun getAll(): List<ContainerConfig> {
        val raw = prefs.getString(KEY_CONTAINERS, null)
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            val list = ArrayList<ContainerConfig>(arr.length())
            for (i in 0 until arr.length()) {
                list.add(fromJson(arr.getJSONObject(i)))
            }
            list
        }.getOrElse { emptyList() }
    }

    fun getById(id: String?): ContainerConfig? {
        val all = getAll()
        return all.firstOrNull { it.id == id } ?: all.firstOrNull()
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
        saveAll(getAll().filterNot { it.id == id })
    }

    fun newId(): String = UUID.randomUUID().toString().take(8)

    private fun saveAll(containers: List<ContainerConfig>) {
        val arr = JSONArray()
        containers.forEach { arr.put(toJson(it)) }
        prefs.edit().putString(KEY_CONTAINERS, arr.toString()).apply()
    }

    private fun toJson(c: ContainerConfig): JSONObject = JSONObject().apply {
        put("id", c.id)
        put("name", c.name)
        put("distroId", c.distroId)
        put("desktopId", c.desktopId)
        put("resolutionScale", c.resolutionScale.toDouble())
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
        val resolutionScale = ScreenScale.clamp(o.optDouble("resolutionScale", 1.0).toFloat())
        val envObj = o.optJSONObject("env")
        val disabledPresets =
            buildSet {
                val arr = envObj?.optJSONArray("disabledPresets")
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
                val obj = envObj?.optJSONObject("overrides")
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
            desktopId = o.optString("desktopId", "sway"),
            resolutionScale = resolutionScale,
            env = EnvConfig(disabledPresets = disabledPresets, presetEdits = presetEdits, overrides = overrides)
        )
    }

    companion object {
        private const val KEY_CONTAINERS = "containers_json"
    }
}
