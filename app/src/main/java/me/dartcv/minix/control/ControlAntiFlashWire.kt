package me.dartcv.minix.control

import org.json.JSONObject

internal fun ControlAntiFlashState.toWireJson(): String = JSONObject()
    .put("schemaVersion", ANTI_FLASH_STATE_SCHEMA_VERSION)
    .put("status", status.name)
    .put("requestedEnabled", requestedEnabled)
    .put("workerRunning", workerRunning)
    .put("applied", applied)
    .put("iterationCount", iterationCount)
    .put("successfulWriteCount", successfulWriteCount)
    .put("lastFailureIndex", lastFailureIndex ?: JSONObject.NULL)
    .put("targetPid", targetPid ?: JSONObject.NULL)
    .put("targetStartTimeTicks", targetStartTimeTicks)
    .put("profileId", profileId)
    .put("message", message)
    .toString()

internal fun decodeControlAntiFlashStateJson(payload: String): ControlAntiFlashState {
    require(payload.length in 2..ControlProtocol.MAX_ANTI_FLASH_STATE_JSON_CHARS) {
        "Anti-flash state response exceeded the protocol limit"
    }
    val json = JSONObject(payload)
    require(json.getInt("schemaVersion") == ANTI_FLASH_STATE_SCHEMA_VERSION) {
        "Anti-flash state schema is unsupported"
    }
    val statusName = json.getString("status")
    val status = ControlAntiFlashStatus.entries.firstOrNull { it.name == statusName }
        ?: error("Anti-flash state status is unknown")
    val iterationCount = json.getLong("iterationCount")
    val successfulWriteCount = json.getLong("successfulWriteCount")
    require(iterationCount >= 0L && successfulWriteCount >= 0L) {
        "Anti-flash state counters must be non-negative"
    }
    val lastFailureIndex = json.nullableInt("lastFailureIndex")?.also { index ->
        require(index >= 0) { "Anti-flash failure index must be non-negative" }
    }
    val targetPid = json.nullableInt("targetPid")?.also { pid ->
        require(pid > 0) { "Anti-flash target PID must be positive" }
    }
    val targetStartTimeTicks = json.getString("targetStartTimeTicks")
    require(targetStartTimeTicks.length <= MAX_START_TIME_CHARS) {
        "Anti-flash target identity is too long"
    }
    require(targetStartTimeTicks.isEmpty() || targetStartTimeTicks.all(Char::isDigit)) {
        "Anti-flash target identity is malformed"
    }
    val profileId = json.getString("profileId")
    val message = json.getString("message")
    require(profileId.length <= MAX_PROFILE_ID_CHARS) { "Anti-flash profile ID is too long" }
    require(message.length <= MAX_MESSAGE_CHARS) { "Anti-flash state message is too long" }

    return ControlAntiFlashState(
        status = status,
        requestedEnabled = json.getBoolean("requestedEnabled"),
        workerRunning = json.getBoolean("workerRunning"),
        applied = json.getBoolean("applied"),
        iterationCount = iterationCount,
        successfulWriteCount = successfulWriteCount,
        lastFailureIndex = lastFailureIndex,
        targetPid = targetPid,
        targetStartTimeTicks = targetStartTimeTicks,
        profileId = profileId,
        message = message,
    )
}

private fun JSONObject.nullableInt(key: String): Int? =
    if (isNull(key)) null else getInt(key)

private const val ANTI_FLASH_STATE_SCHEMA_VERSION = 1
private const val MAX_START_TIME_CHARS = 32
private const val MAX_PROFILE_ID_CHARS = 96
private const val MAX_MESSAGE_CHARS = 256
