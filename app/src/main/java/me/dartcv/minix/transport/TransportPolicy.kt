package me.dartcv.minix.transport

/** Wire channel used by the local control service. */
internal enum class TransportChannel(
    val wireName: String,
) {
    CERTIFICATE_SHARED_UID_BINDER("CERTIFICATE_SHARED_UID_BINDER"),
}

/** Immutable description of the one supported control transport. */
internal data class TransportPolicyMetadata(
    val channel: TransportChannel,
    val certificateBound: Boolean,
    val sharedUidRequired: Boolean,
    val serviceExported: Boolean,
    val serviceProcessName: String,
    val aidlDescriptor: String,
)

internal object TransportPolicy {
    const val CHANNEL_ID = "CERTIFICATE_SHARED_UID_BINDER"
    const val SERVICE_PROCESS_NAME = ":control"
    const val AIDL_DESCRIPTOR = "me.dartcv.minix.control.IControlBridge"

    val current = TransportPolicyMetadata(
        channel = TransportChannel.CERTIFICATE_SHARED_UID_BINDER,
        certificateBound = true,
        sharedUidRequired = true,
        serviceExported = false,
        serviceProcessName = SERVICE_PROCESS_NAME,
        aidlDescriptor = AIDL_DESCRIPTOR,
    )
}
