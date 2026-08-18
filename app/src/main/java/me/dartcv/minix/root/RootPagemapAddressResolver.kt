package me.dartcv.minix.root

internal enum class RootAddressResolveStatus {
    RESOLVED,
    INVALID_PID,
    INVALID_SEED,
    SCALAR_READ_FAILED,
}

internal data class RootAddressResolveResult(
    val status: RootAddressResolveStatus,
    val seed: Long,
    val resolvedAddress: Long? = null,
    val processStartTimeTicks: String = "",
    val message: String = "",
) {
    val isSuccess: Boolean
        get() = status == RootAddressResolveStatus.RESOLVED && resolvedAddress != null
}

internal fun interface RootSeedAddressResolver {
    fun resolve(pid: Int, seed: Long): RootAddressResolveResult
}

internal class RootMaskedRemoteAddressResolver(
    private val scalarReader: TargetScalarReader,
) : RootSeedAddressResolver {
    override fun resolve(pid: Int, seed: Long): RootAddressResolveResult {
        if (pid <= 0) {
            return failure(RootAddressResolveStatus.INVALID_PID, seed, "PID must be positive")
        }
        if (seed <= 0L) {
            return failure(
                RootAddressResolveStatus.INVALID_SEED,
                seed,
                "Resolver seed must be positive",
            )
        }
        val readAddress = seed and UINT32_MAX
        if (readAddress == 0L) {
            return failure(
                RootAddressResolveStatus.INVALID_SEED,
                seed,
                "Resolver seed truncates to the zero uint32 address",
            )
        }

        val scalar = scalarReader.readInt32(pid, readAddress)
        val valueBits = scalar.valueBits
        if (!scalar.isSuccess || valueBits == null) {
            return failure(
                status = RootAddressResolveStatus.SCALAR_READ_FAILED,
                seed = seed,
                message = scalar.message.ifBlank { "Resolver uint32 read failed" },
                processStartTimeTicks = scalar.processStartTimeTicks,
            )
        }
        return RootAddressResolveResult(
            status = RootAddressResolveStatus.RESOLVED,
            seed = seed,
            resolvedAddress = valueBits and UINT24_MAX,
            processStartTimeTicks = scalar.processStartTimeTicks,
        )
    }

    private fun failure(
        status: RootAddressResolveStatus,
        seed: Long,
        message: String,
        processStartTimeTicks: String = "",
    ): RootAddressResolveResult = RootAddressResolveResult(
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

internal object JniRootSeedAddressResolver {
    val instance: RootSeedAddressResolver = RootMaskedRemoteAddressResolver(
        scalarReader = JniTargetScalarReader,
    )
}
