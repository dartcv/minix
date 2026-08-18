package me.dartcv.minix.core.serialization

import me.dartcv.minix.core.model.AccentOption
import me.dartcv.minix.core.model.Preset
import me.dartcv.minix.core.model.ResponseMode
import org.json.JSONObject

object PresetCodec {
    const val VERSION: Int = 1
    const val MIN_PANEL_OPACITY: Float = 0.55f
    const val MAX_PANEL_OPACITY: Float = 1f
    const val MIN_PANEL_SCALE: Float = 0.8f
    const val MAX_PANEL_SCALE: Float = 1.2f
    const val MIN_SENSITIVITY: Float = 0.2f
    const val MAX_SENSITIVITY: Float = 1f

    fun encode(preset: Preset): CodecResult<String> = codecResult {
        validatePreset(preset, "$.preset")
        JSONObject()
            .put("version", VERSION)
            .put("preset", presetToJson(preset))
            .toString()
    }

    fun decode(json: String): CodecResult<Preset> = codecResult {
        val root = JSONObject(json)
        requireSupportedVersion(root)
        readPreset(root.requireObject("preset", "$"), "$.preset")
    }
}

private const val MAX_ID_LENGTH = 64
private const val MAX_NAME_LENGTH = 64
private const val MAX_DESCRIPTION_LENGTH = 256
private val PRESET_ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")

internal fun requireSupportedVersion(root: JSONObject) {
    val version = root.requireInt("version", "$")
    if (version != PresetCodec.VERSION) {
        codecFailure(
            CodecErrorCode.UNSUPPORTED_VERSION,
            "$.version",
            "Supported version is ${PresetCodec.VERSION}, received $version",
        )
    }
}

internal fun presetToJson(preset: Preset): JSONObject =
    JSONObject()
        .put("id", preset.id)
        .put("name", preset.name)
        .put("description", preset.description)
        .put(
            "appearance",
            JSONObject()
                .put("gridEnabled", preset.gridEnabled)
                .put("panelOpacity", preset.panelOpacity)
                .put("panelScale", preset.panelScale)
                .put("accentOption", preset.accentOption.name),
        )
        .put(
            "control",
            JSONObject()
                .put("sensitivity", preset.sensitivity)
                .put("responseMode", preset.responseMode.name),
        )
        .put(
            "metadata",
            JSONObject().put("isBuiltIn", preset.isBuiltIn),
        )

internal fun readPreset(json: JSONObject, path: String): Preset {
    val id = json.requireString("id", path)
    val name = json.requireString("name", path)
    val description = json.requireString("description", path)
    val appearancePath = "$path.appearance"
    val appearance = json.requireObject("appearance", path)
    val controlPath = "$path.control"
    val control = json.requireObject("control", path)
    val metadataPath = "$path.metadata"
    val metadata = json.requireObject("metadata", path)

    val preset = Preset(
        id = id,
        name = name,
        description = description,
        gridEnabled = appearance.requireBoolean("gridEnabled", appearancePath),
        panelOpacity = appearance.requireFloat(
            name = "panelOpacity",
            parentPath = appearancePath,
            range = 0.55..1.0,
        ),
        panelScale = appearance.requireFloat(
            name = "panelScale",
            parentPath = appearancePath,
            range = 0.8..1.2,
        ),
        sensitivity = control.requireFloat(
            name = "sensitivity",
            parentPath = controlPath,
            range = 0.2..1.0,
        ),
        responseMode = control.requireEnum<ResponseMode>("responseMode", controlPath),
        accentOption = appearance.requireEnum<AccentOption>("accentOption", appearancePath),
        isBuiltIn = metadata.requireBoolean("isBuiltIn", metadataPath),
    )
    validatePreset(preset, path)
    return preset
}

internal fun validatePreset(preset: Preset, path: String) {
    if (preset.id.length !in 1..MAX_ID_LENGTH || !PRESET_ID_PATTERN.matches(preset.id)) {
        codecFailure(
            CodecErrorCode.INVALID_VALUE,
            "$path.id",
            "Expected 1..$MAX_ID_LENGTH ASCII letters, digits, dots, underscores, or hyphens",
        )
    }
    if (preset.name.isBlank() || preset.name.length > MAX_NAME_LENGTH) {
        codecFailure(
            CodecErrorCode.INVALID_VALUE,
            "$path.name",
            "Expected a non-blank name of at most $MAX_NAME_LENGTH characters",
        )
    }
    if (preset.description.length > MAX_DESCRIPTION_LENGTH) {
        codecFailure(
            CodecErrorCode.OUT_OF_RANGE,
            "$path.description",
            "Expected at most $MAX_DESCRIPTION_LENGTH characters",
        )
    }
    validateFloatRange(
        value = preset.panelOpacity,
        range = PresetCodec.MIN_PANEL_OPACITY..PresetCodec.MAX_PANEL_OPACITY,
        path = "$path.appearance.panelOpacity",
    )
    validateFloatRange(
        value = preset.panelScale,
        range = PresetCodec.MIN_PANEL_SCALE..PresetCodec.MAX_PANEL_SCALE,
        path = "$path.appearance.panelScale",
    )
    validateFloatRange(
        value = preset.sensitivity,
        range = PresetCodec.MIN_SENSITIVITY..PresetCodec.MAX_SENSITIVITY,
        path = "$path.control.sensitivity",
    )
}

internal fun validateFloatRange(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    path: String,
) {
    if (!value.isFinite()) {
        codecFailure(CodecErrorCode.INVALID_VALUE, path, "Expected a finite number")
    }
    if (value !in range) {
        codecFailure(
            CodecErrorCode.OUT_OF_RANGE,
            path,
            "Expected a value in ${range.start}..${range.endInclusive}",
        )
    }
}
