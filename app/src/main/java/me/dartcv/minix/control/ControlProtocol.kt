package me.dartcv.minix.control

internal object ControlProtocol {
    // Bumped with the descriptor/package migration to invalidate stale
    // clients compiled against the previous descriptor namespace.
    const val VERSION = 11
    const val ACCESS_TIMEOUT_MILLIS = 60_000L
    const val CONNECTION_TIMEOUT_MILLIS = 15_000L
    const val MAX_PACKAGE_LENGTH = 255
    const val MAX_PROBE_MODULES = 24
    const val MAX_ANTI_FLASH_STATE_JSON_CHARS = 4_096

    private val packagePattern = Regex(
        pattern = "[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+",
    )

    fun normalizePackageName(value: String): String =
        value.trim().take(MAX_PACKAGE_LENGTH)

    fun isValidPackageName(value: String): Boolean =
        value.length <= MAX_PACKAGE_LENGTH && packagePattern.matches(value)

    fun processMatchesPackage(processName: String, packageName: String): Boolean =
        processName == packageName || processName.startsWith("$packageName:")
}
