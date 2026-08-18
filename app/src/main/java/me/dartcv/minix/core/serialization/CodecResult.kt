package me.dartcv.minix.core.serialization

enum class CodecErrorCode {
    MALFORMED_JSON,
    MISSING_FIELD,
    INVALID_TYPE,
    UNSUPPORTED_VERSION,
    INVALID_VALUE,
    OUT_OF_RANGE,
    DUPLICATE_VALUE,
    REFERENCE_NOT_FOUND,
}

data class CodecError(
    val code: CodecErrorCode,
    val path: String,
    val detail: String,
)

sealed interface CodecResult<out T> {
    data class Success<T>(val value: T) : CodecResult<T>

    data class Failure(val error: CodecError) : CodecResult<Nothing>
}
