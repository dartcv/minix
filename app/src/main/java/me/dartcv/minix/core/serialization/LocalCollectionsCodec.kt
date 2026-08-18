package me.dartcv.minix.core.serialization

import me.dartcv.minix.core.model.MapPoint
import me.dartcv.minix.core.model.Preset
import org.json.JSONArray
import org.json.JSONObject

/** Compact DataStore payloads for collections that are edited in-app. */
object PresetListCodec {
    private const val VERSION = 1
    private const val MAX_COUNT = 100

    fun encode(value: List<Preset>): String {
        require(value.size <= MAX_COUNT) { "Too many presets" }
        val ids = HashSet<String>()
        val names = HashSet<String>()
        value.forEachIndexed { index, preset ->
            validatePreset(preset, "$.presets[$index]")
            require(ids.add(preset.id)) { "Duplicate preset id" }
            require(names.add(preset.name)) { "Duplicate preset name" }
        }
        return JSONObject()
            .put("version", VERSION)
            .put("presets", JSONArray().apply { value.forEach { put(presetToJson(it)) } })
            .toString()
    }

    fun decode(raw: String): List<Preset> {
        val root = JSONObject(raw)
        require(root.optInt("version", -1) == VERSION) { "Unsupported preset list version" }
        val array = root.optJSONArray("presets") ?: error("Missing presets")
        require(array.length() <= MAX_COUNT) { "Too many presets" }
        val ids = HashSet<String>()
        val names = HashSet<String>()
        return List(array.length()) { index ->
            val preset = readPreset(array.getJSONObject(index), "$.presets[$index]")
            require(ids.add(preset.id)) { "Duplicate preset id" }
            require(names.add(preset.name)) { "Duplicate preset name" }
            preset
        }
    }
}

object MapPointListCodec {
    private const val VERSION = 1
    private const val MAX_COUNT = 500
    private val ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")

    fun encode(value: List<MapPoint>): String {
        validate(value)
        return JSONObject()
            .put("version", VERSION)
            .put("points", JSONArray().apply {
                value.forEach { point ->
                    put(
                        JSONObject()
                            .put("id", point.id)
                            .put("name", point.name)
                            .put("group", point.group)
                            .put("x", point.x)
                            .put("y", point.y),
                    )
                }
            })
            .toString()
    }

    fun decode(raw: String): List<MapPoint> {
        val root = JSONObject(raw)
        require(root.optInt("version", -1) == VERSION) { "Unsupported map point version" }
        val array = root.optJSONArray("points") ?: error("Missing points")
        require(array.length() <= MAX_COUNT) { "Too many map points" }
        val result = List(array.length()) { index ->
            val item = array.getJSONObject(index)
            MapPoint(
                id = item.getString("id"),
                name = item.getString("name"),
                group = item.getString("group"),
                x = item.getDouble("x").toFloat(),
                y = item.getDouble("y").toFloat(),
            )
        }
        validate(result)
        return result
    }

    private fun validate(value: List<MapPoint>) {
        require(value.size <= MAX_COUNT) { "Too many map points" }
        val ids = HashSet<String>()
        value.forEach { point ->
            require(point.id.matches(ID_PATTERN)) { "Invalid map point id" }
            require(point.name.isNotBlank() && point.name.length <= 64) { "Invalid map point name" }
            require(point.group.isNotBlank() && point.group.length <= 64) { "Invalid map point group" }
            require(point.x.isFinite() && point.x in 0f..1f) { "Invalid map point x" }
            require(point.y.isFinite() && point.y in 0f..1f) { "Invalid map point y" }
            require(ids.add(point.id)) { "Duplicate map point id" }
        }
    }
}
