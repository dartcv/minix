package me.dartcv.minix.transport

/**
 * The transport channel selected for the current local-control integration.
 *
 * The wire name is intentionally stable so reports and diagnostics can refer
 * to the channel without depending on historical implementation package names.
 */
internal enum class TransportChannel(
    val wireName: String,
) {
    CERTIFICATE_SHARED_UID_BINDER("CERTIFICATE_SHARED_UID_BINDER"),
}

/**
 * Immutable, side-effect-free declaration of the control transport policy.
 *
 * This is metadata only: it does not inspect the process, invoke a shell, or
 * request a privilege.  The service implementation still lives in the
 * historical `me.dartcv.minix.root` namespace solely to preserve its Binder
 * descriptor and existing integrations.
 */
internal data class TransportPolicyMetadata(
    val channel: TransportChannel,
    val certificateBound: Boolean,
    val sharedUidRequired: Boolean,
    val rootPrivilegeRequired: Boolean,
    val suShellEntryPointEnabled: Boolean,
    val rootSchemeDeprecated: Boolean,
    val historicalCompatibilityNamespace: String,
) {
    /** True only when this policy actually enables a root/su entry path. */
    val rootEntryPointEnabled: Boolean
        get() = rootPrivilegeRequired || suShellEntryPointEnabled

    /**
     * Exact namespace check used by migration tooling.  Deliberately avoids
     * substring matching so a historical package name is not mistaken for a
     * root-privilege entry point.
     */
    fun isHistoricalCompatibilityNamespace(packageName: String): Boolean =
        packageName == historicalCompatibilityNamespace
}

/**
 * Current transport metadata.  Keep this object free of Android/runtime
 * dependencies: consumers can read it from tests, reports, and UI wiring
 * without changing connection behaviour.
 */
internal object TransportPolicy {
    const val CHANNEL_ID = "CERTIFICATE_SHARED_UID_BINDER"
    const val HISTORICAL_ROOT_NAMESPACE = "me.dartcv.minix.root"

    val current: TransportPolicyMetadata = TransportPolicyMetadata(
        channel = TransportChannel.CERTIFICATE_SHARED_UID_BINDER,
        certificateBound = true,
        sharedUidRequired = true,
        rootPrivilegeRequired = false,
        suShellEntryPointEnabled = false,
        rootSchemeDeprecated = true,
        historicalCompatibilityNamespace = HISTORICAL_ROOT_NAMESPACE,
    )
}
