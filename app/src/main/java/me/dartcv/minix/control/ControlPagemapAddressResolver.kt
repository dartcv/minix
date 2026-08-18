package me.dartcv.minix.control

internal enum class ControlAddressResolveStatus {
    RESOLVED,
    INVALID_PID,
    INVALID_SEED,
    SCALAR_READ_FAILED,
}

internal data class ControlAddressResolveResult(
    val status: ControlAddressResolveStatus,
    val seed: Long,
    val resolvedAddress: Long? = null,
    val processStartTimeTicks: String = "",
    val message: String = "",
) {
    val isSuccess: Boolean
        get() = status == ControlAddressResolveStatus.RESOLVED && resolvedAddress != null
}

internal fun interface ControlSeedAddressResolver {
    fun resolve(pid: Int, seed: Long): ControlAddressResolveResult
}

internal class ControlMaskedRemoteAddressResolver(
    private val scalarReader: TargetScalarReader,
) : ControlSeedAddressResolver {
    override fun resolve(pid: Int, seed: Long): ControlAddressResolveResult {
        if (pid <= 0) {
            return failure(ControlAddressResolveStatus.INVALID_PID, seed, "PID must be positive")
        }
        if (seed <= 0L) {
            return failure(
                ControlAddressResolveStatus.INVALID_SEED,
                seed,
                "Resolver seed must be positive",
            )
        }
        val readAddress = seed and UINT32_MAX
        if (readAddress == 0L) {
            return failure(
                ControlAddressResolveStatus.INVALID_SEED,
                seed,
                "Resolver seed truncates to the zero uint32 address",
            )
        }

        val scalar = scalarReader.readInt32(pid, readAddress)
        val valueBits = scalar.valueBits
        if (!scalar.isSuccess || valueBits == null) {
            return failure(
                status = ControlAddressResolveStatus.SCALAR_READ_FAILED,
                seed = seed,
                message = scalar.message.ifBlank { "Resolver uint32 read failed" },
                processStartTimeTicks = scalar.processStartTimeTicks,
            )
        }
        return ControlAddressResolveResult(
            status = ControlAddressResolveStatus.RESOLVED,
            seed = seed,
            resolvedAddress = valueBits and UINT24_MAX,
            processStartTimeTicks = scalar.processStartTimeTicks,
        )
    }

    private fun failure(
        status: ControlAddressResolveStatus,
        seed: Long,
        message: String,
        processStartTimeTicks: String = "",
    ): ControlAddressResolveResult = ControlAddressResolveResult(
        status = status,
        seed = seed,
        processStartTimeTicks = processStartTimeTicks,
        message = message,
    )

    private companion object {
        const val UINT32_MAX = 0xffff_ffffL
        const val UINT24_MAX = 0x00ff_ffffL
    }
}

internal object JniControlSeedAddressResolver {
    val instance: ControlSeedAddressResolver = ControlMaskedRemoteAddressResolver(
        scalarReader = JniTargetScalarReader,
    )
}
