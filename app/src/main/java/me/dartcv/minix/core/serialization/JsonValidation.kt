package me.dartcv.minix.core.serialization

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

internal class CodecAbort(
    val codecError: CodecError,
) : RuntimeException(codecError.detail, null, false, false)

internal inline fun <T> codecResult(block: () -> T): CodecResult<T> =
    try {
        CodecResult.Success(block())
    } catch (error: CodecAbort) {
        CodecResult.Failure(error.codecError)
    } catch (error: JSONException) {
        CodecResult.Failure(
            CodecError(
                code = CodecErrorCode.MALFORMED_JSON,
                path = "$",
                detail = error.message ?: "Malformed JSON",
            ),
        )
    }

internal fun codecFailure(
    code: CodecErrorCode,
    path: String,
    detail: String,
): Nothing = throw CodecAbort(CodecError(code = code, path = path, detail = detail))

internal fun JSONObject.requireObject(name: String, parentPath: String): JSONObject {
    val path = "$parentPath.$name"
    return requireValue(name, parentPath) as? JSONObject
        ?: codecFailure(CodecErrorCode.INVALID_TYPE, path, "Expected an object")
}

internal fun JSONObject.requireArray(name: String, parentPath: String): JSONArray {
    val path = "$parentPath.$name"
    return requireValue(name, parentPath) as? JSONArray
        ?: codecFailure(CodecErrorCode.INVALID_TYPE, path, "Expected an array")
}

internal fun JSONObject.requireString(name: String, parentPath: String): String {
    val path = "$parentPath.$name"
    return requireValue(name, parentPath) as? String
        ?: codecFailure(CodecErrorCode.INVALID_TYPE, path, "Expected a string")
}

internal fun JSONObject.requireNullableString(name: String, parentPath: String): String? {
    val path = "$parentPath.$name"
    if (!has(name)) {
        codecFailure(CodecErrorCode.MISSING_FIELD, path, "Required field is missing")
    }
    if (isNull(name)) return null
    return get(name) as? String
        ?: codecFailure(CodecErrorCode.INVALID_TYPE, path, "Expected a string or null")
}

internal fun JSONObject.requireBoolean(name: String, parentPath: String): Boolean {
    val path = "$parentPath.$name"
    return requireValue(name, parentPath) as? Boolean
        ?: codecFailure(CodecErrorCode.INVALID_TYPE, path, "Expected a boolean")
}

internal inline fun <reified T : Enum<T>> JSONObject.requireEnum(
    name: String,
    parentPath: String,
): T {
    val path = "$parentPath.$name"
    val rawValue = requireString(name, parentPath)
    return enumValues<T>().firstOrNull { it.name == rawValue }
        ?: codecFailure(
            CodecErrorCode.INVALID_VALUE,
            path,
            "Expected one of ${enumValues<T>().joinToString { it.name }}",
        )
}

internal fun JSONObject.requireInt(name: String, parentPath: String): Int {
    val path = "$parentPath.$name"
    val number = requireValue(name, parentPath) as? Number
        ?: codecFailure(CodecErrorCode.INVALID_TYPE, path, "Expected an integer")
    val doubleValue = number.toDouble()
    if (!doubleValue.isFinite() || doubleValue % 1.0 != 0.0 || doubleValue !in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble()) {
        codecFailure(CodecErrorCode.INVALID_TYPE, path, "Expected an integer")
    }
    return doubleValue.toInt()
}

internal fun JSONObject.requireFloat(
    name: String,
    parentPath: String,
    range: ClosedFloatingPointRange<Double>,
): Float {
    val path = "$parentPath.$name"
    val number = requireValue(name, parentPath) as? Number
        ?: codecFailure(CodecErrorCode.INVALID_TYPE, path, "Expected a number")
    val value = number.toDouble()
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
    return value.toFloat()
}

private fun JSONObject.requireValue(name: String, parentPath: String): Any {
    val path = "$parentPath.$name"
    if (!has(name) || isNull(name)) {
        codecFailure(CodecErrorCode.MISSING_FIELD, path, "Required field is missing")
    }
    return get(name)
}
