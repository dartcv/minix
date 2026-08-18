package me.dartcv.minix.core.serialization

import me.dartcv.minix.core.model.AccentOption
import me.dartcv.minix.core.model.AppSettings
import me.dartcv.minix.core.model.LocalDataSnapshot
import me.dartcv.minix.core.model.MapPoint
import me.dartcv.minix.core.model.Preset
import me.dartcv.minix.core.model.ResponseMode
import me.dartcv.minix.core.model.ThemeMode
import org.json.JSONArray
import org.json.JSONObject

object LocalDataCodec {
    const val VERSION: Int = PresetCodec.VERSION
    const val MAX_PRESET_COUNT: Int = 100
    const val MAX_MAP_POINT_COUNT: Int = 500

    fun encode(data: LocalDataSnapshot): CodecResult<String> = codecResult {
        validateLocalData(data)
        val presets = JSONArray()
        data.presets.forEach { presets.put(presetToJson(it)) }
        val mapPoints = JSONArray()
        data.mapPoints.forEach { mapPoints.put(mapPointToJson(it)) }
        val dataJson = JSONObject()
            .put("settings", settingsToJson(data.settings))
            .put("presets", presets)
            .put("mapPoints", mapPoints)
        JSONObject()
            .put("version", VERSION)
            .put("data", dataJson)
            .toString()
    }

    fun decode(json: String): CodecResult<LocalDataSnapshot> = codecResult {
        val root = JSONObject(json)
        requireSupportedVersion(root)
        val dataJson = root.requireObject("data", "$")
        val settings = readSettings(dataJson.requireObject("settings", "$.data"), "$.data.settings")
        val presets = readPresets(dataJson.requireArray("presets", "$.data"))
        val mapPoints = readMapPoints(dataJson.requireArray("mapPoints", "$.data"))

        LocalDataSnapshot(
            settings = settings,
            presets = presets,
            mapPoints = mapPoints,
        ).also(::validateLocalData)
    }
}

private const val MAX_TEXT_LENGTH = 64
private const val MAX_URI_LENGTH = 4096
private const val MIN_OVERLAY_COORDINATE = -100_000
private const val MAX_OVERLAY_COORDINATE = 100_000
private val LOCAL_ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")

private fun settingsToJson(settings: AppSettings): JSONObject =
    JSONObject()
        .put(
            "interaction",
            JSONObject()
                .put("gridEnabled", settings.gridEnabled)
                .put("hapticsEnabled", settings.hapticsEnabled)
                .put("sensitivity", settings.sensitivity)
                .put("responseMode", settings.responseMode.name)
                .put("alignmentEnabled", settings.alignmentEnabled)
                .put("motionPreviewEnabled", settings.motionPreviewEnabled)
                .put("effectHighlightEnabled", settings.effectHighlightEnabled)
                .put("favoriteEntryIds", org.json.JSONArray(settings.favoriteEntryIds.sorted())),
        )
        .put(
            "overlay",
            JSONObject()
                .put("panelOpacity", settings.panelOpacity)
                .put("panelScale", settings.panelScale)
                .put("x", settings.overlayX)
                .put("y", settings.overlayY)
                .put("expanded", settings.overlayExpanded),
        )
        .put(
            "selection",
            JSONObject().put("selectedPreset", settings.selectedPreset),
        )
        .put(
            "theme",
            JSONObject()
                .put("mode", settings.themeMode.name)
                .put("accentOption", settings.accentOption.name)
                .put("backgroundUri", settings.backgroundUri ?: JSONObject.NULL)
                .put("audioUri", settings.audioUri ?: JSONObject.NULL),
        )

private fun readSettings(json: JSONObject, path: String): AppSettings {
    val interactionPath = "$path.interaction"
    val interaction = json.requireObject("interaction", path)
    val overlayPath = "$path.overlay"
    val overlay = json.requireObject("overlay", path)
    val selectionPath = "$path.selection"
    val selection = json.requireObject("selection", path)
    val themePath = "$path.theme"
    val theme = json.requireObject("theme", path)

    val settings = AppSettings(
        gridEnabled = interaction.requireBoolean("gridEnabled", interactionPath),
        hapticsEnabled = interaction.requireBoolean("hapticsEnabled", interactionPath),
        panelOpacity = overlay.requireFloat("panelOpacity", overlayPath, 0.55..1.0),
        panelScale = overlay.requireFloat("panelScale", overlayPath, 0.8..1.2),
        overlayX = overlay.requireInt("x", overlayPath),
        overlayY = overlay.requireInt("y", overlayPath),
        overlayExpanded = overlay.requireBoolean("expanded", overlayPath),
        selectedPreset = selection.requireString("selectedPreset", selectionPath),
        sensitivity = interaction.requireFloat("sensitivity", interactionPath, 0.2..1.0),
        responseMode = interaction.requireEnum<ResponseMode>("responseMode", interactionPath),
        alignmentEnabled = interaction.requireBoolean("alignmentEnabled", interactionPath),
        motionPreviewEnabled = interaction.optBoolean("motionPreviewEnabled", false),
        effectHighlightEnabled = interaction.optBoolean("effectHighlightEnabled", true),
        favoriteEntryIds = interaction.optionalStringSet("favoriteEntryIds"),
        themeMode = theme.requireEnum<ThemeMode>("mode", themePath),
        accentOption = theme.requireEnum<AccentOption>("accentOption", themePath),
        backgroundUri = theme.requireNullableString("backgroundUri", themePath),
        audioUri = theme.requireNullableString("audioUri", themePath),
    )
    validateSettings(settings, path)
    return settings
}

private fun readPresets(json: JSONArray): List<Preset> {
    if (json.length() > LocalDataCodec.MAX_PRESET_COUNT) {
        codecFailure(
            CodecErrorCode.OUT_OF_RANGE,
            "$.data.presets",
            "Expected at most ${LocalDataCodec.MAX_PRESET_COUNT} presets",
        )
    }
    return List(json.length()) { index ->
        val path = "$.data.presets[$index]"
        val item = json.get(index) as? JSONObject
            ?: codecFailure(CodecErrorCode.INVALID_TYPE, path, "Expected an object")
        readPreset(item, path)
    }
}

private fun readMapPoints(json: JSONArray): List<MapPoint> {
    if (json.length() > LocalDataCodec.MAX_MAP_POINT_COUNT) {
        codecFailure(
            CodecErrorCode.OUT_OF_RANGE,
            "$.data.mapPoints",
            "Expected at most ${LocalDataCodec.MAX_MAP_POINT_COUNT} map points",
        )
    }
    return List(json.length()) { index ->
        val path = "$.data.mapPoints[$index]"
        val item = json.get(index) as? JSONObject
            ?: codecFailure(CodecErrorCode.INVALID_TYPE, path, "Expected an object")
        MapPoint(
            id = item.requireString("id", path),
            name = item.requireString("name", path),
            group = item.requireString("group", path),
            x = item.requireFloat("x", path, 0.0..1.0),
            y = item.requireFloat("y", path, 0.0..1.0),
        ).also { validateMapPoint(it, path) }
    }
}

private fun mapPointToJson(point: MapPoint): JSONObject =
    JSONObject()
        .put("id", point.id)
        .put("name", point.name)
        .put("group", point.group)
        .put("x", point.x)
        .put("y", point.y)

private fun validateLocalData(data: LocalDataSnapshot) {
    validateSettings(data.settings, "$.data.settings")
    if (data.presets.size > LocalDataCodec.MAX_PRESET_COUNT) {
        codecFailure(
            CodecErrorCode.OUT_OF_RANGE,
            "$.data.presets",
            "Expected at most ${LocalDataCodec.MAX_PRESET_COUNT} presets",
        )
    }
    if (data.mapPoints.size > LocalDataCodec.MAX_MAP_POINT_COUNT) {
        codecFailure(
            CodecErrorCode.OUT_OF_RANGE,
            "$.data.mapPoints",
            "Expected at most ${LocalDataCodec.MAX_MAP_POINT_COUNT} map points",
        )
    }

    val presetIds = HashSet<String>(data.presets.size)
    val presetNames = HashSet<String>(data.presets.size)
    data.presets.forEachIndexed { index, preset ->
        validatePreset(preset, "$.data.presets[$index]")
        if (!presetIds.add(preset.id)) {
            codecFailure(
                CodecErrorCode.DUPLICATE_VALUE,
                "$.data.presets[$index].id",
                "Preset id '${preset.id}' is duplicated",
            )
        }
        if (!presetNames.add(preset.name)) {
            codecFailure(
                CodecErrorCode.DUPLICATE_VALUE,
                "$.data.presets[$index].name",
                "Preset name '${preset.name}' is duplicated",
            )
        }
    }
    if (data.settings.selectedPreset !in presetNames) {
        codecFailure(
            CodecErrorCode.REFERENCE_NOT_FOUND,
            "$.data.settings.selection.selectedPreset",
            "No preset exists with name '${data.settings.selectedPreset}'",
        )
    }

    val mapPointIds = HashSet<String>(data.mapPoints.size)
    data.mapPoints.forEachIndexed { index, point ->
        validateMapPoint(point, "$.data.mapPoints[$index]")
        if (!mapPointIds.add(point.id)) {
            codecFailure(
                CodecErrorCode.DUPLICATE_VALUE,
                "$.data.mapPoints[$index].id",
                "Map point id '${point.id}' is duplicated",
            )
        }
    }
}

private fun validateSettings(settings: AppSettings, path: String) {
    validateFloatRange(
        settings.panelOpacity,
        PresetCodec.MIN_PANEL_OPACITY..PresetCodec.MAX_PANEL_OPACITY,
        "$path.overlay.panelOpacity",
    )
    validateFloatRange(
        settings.panelScale,
        PresetCodec.MIN_PANEL_SCALE..PresetCodec.MAX_PANEL_SCALE,
        "$path.overlay.panelScale",
    )
    validateFloatRange(
        settings.sensitivity,
        PresetCodec.MIN_SENSITIVITY..PresetCodec.MAX_SENSITIVITY,
        "$path.interaction.sensitivity",
    )
    validateIntRange(settings.overlayX, "$path.overlay.x")
    validateIntRange(settings.overlayY, "$path.overlay.y")
    validateText(settings.selectedPreset, "$path.selection.selectedPreset")
    validateNullableUri(settings.backgroundUri, "$path.theme.backgroundUri")
    validateNullableUri(settings.audioUri, "$path.theme.audioUri")
    settings.favoriteEntryIds.forEach { id ->
        if (!LOCAL_ID_PATTERN.matches(id)) {
            codecFailure(CodecErrorCode.INVALID_VALUE, "$path.interaction.favoriteEntryIds", "Invalid local entry id")
        }
    }
}

private fun org.json.JSONObject.optionalStringSet(name: String): Set<String> {
    if (!has(name) || isNull(name)) return emptySet()
    val array = requireArray(name, "$.data.settings.interaction")
    return buildSet {
        for (index in 0 until array.length()) {
            val value = array.get(index) as? String
                ?: codecFailure(
                    CodecErrorCode.INVALID_TYPE,
                    "$.data.settings.interaction.$name[$index]",
                    "Expected a string",
                )
            if (!add(value)) {
                codecFailure(
                    CodecErrorCode.DUPLICATE_VALUE,
                    "$.data.settings.interaction.$name[$index]",
                    "Favorite entry id '$value' is duplicated",
                )
            }
        }
    }
}

private fun validateMapPoint(point: MapPoint, path: String) {
    if (point.id.length !in 1..MAX_TEXT_LENGTH || !LOCAL_ID_PATTERN.matches(point.id)) {
        codecFailure(CodecErrorCode.INVALID_VALUE, "$path.id", "Expected a stable local id")
    }
    validateText(point.name, "$path.name")
    validateText(point.group, "$path.group")
    validateFloatRange(point.x, 0f..1f, "$path.x")
    validateFloatRange(point.y, 0f..1f, "$path.y")
}

private fun validateText(value: String, path: String) {
    if (value.isBlank() || value.length > MAX_TEXT_LENGTH) {
        codecFailure(
            CodecErrorCode.INVALID_VALUE,
            path,
            "Expected a non-blank value of at most $MAX_TEXT_LENGTH characters",
        )
    }
}

private fun validateNullableUri(value: String?, path: String) {
    if (value != null && (value.isBlank() || value.length > MAX_URI_LENGTH)) {
        codecFailure(
            CodecErrorCode.INVALID_VALUE,
            path,
            "Expected null or a non-blank URI of at most $MAX_URI_LENGTH characters",
        )
    }
}

private fun validateIntRange(value: Int, path: String) {
    if (value !in MIN_OVERLAY_COORDINATE..MAX_OVERLAY_COORDINATE) {
        codecFailure(
            CodecErrorCode.OUT_OF_RANGE,
            path,
            "Expected a value in $MIN_OVERLAY_COORDINATE..$MAX_OVERLAY_COORDINATE",
        )
    }
}
