package me.dartcv.minix.core.serialization

internal fun <T> CodecResult<T>.successValue(): T = when (this) {
    is CodecResult.Success -> value
    is CodecResult.Failure -> throw AssertionError("Expected success, received $error")
}

internal fun CodecResult<*>.failureError(): CodecError = when (this) {
    is CodecResult.Success -> throw AssertionError("Expected failure, received $value")
    is CodecResult.Failure -> error
}
