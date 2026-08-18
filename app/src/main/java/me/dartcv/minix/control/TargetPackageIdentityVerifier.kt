package me.dartcv.minix.control

import android.content.pm.PackageManager
import java.security.MessageDigest

internal data class TargetPackageIdentityEvidence(
    val uid: Int,
    val sharedUserId: String?,
    val activeSignerSha256: Set<String>,
)

internal data class TargetPackageIdentityVerification(
    val isValid: Boolean,
    val message: String,
)

internal fun interface TargetPackageIdentityVerifier {
    fun verify(packageName: String): TargetPackageIdentityVerification
}

internal object AcceptAnyTargetPackageIdentity : TargetPackageIdentityVerifier {
    override fun verify(packageName: String): TargetPackageIdentityVerification =
        TargetPackageIdentityVerification(true, "fixture identity accepted")
}

internal object CertificateSharedUidIdentity {
    const val SHARED_USER_ID = "ca.sailboat.a"
    const val SIGNER_SHA256 =
        "A40DA80A59D170CAA950CF15C18C454D47A39B26989D8B640ECD745BA71BF5DC"

    fun evaluate(
        packageName: String,
        evidence: TargetPackageIdentityEvidence?,
        expectedUid: Int,
        expectedSharedUserId: String = SHARED_USER_ID,
        expectedSignerSha256: String = SIGNER_SHA256,
    ): TargetPackageIdentityVerification {
        if (evidence == null) {
            return TargetPackageIdentityVerification(
                false,
                "目标包未安装或对本应用不可见：$packageName",
            )
        }
        if (evidence.sharedUserId != expectedSharedUserId) {
            return TargetPackageIdentityVerification(
                false,
                "目标包 sharedUserId 不匹配：${evidence.sharedUserId ?: "<空>"}，预期 $expectedSharedUserId",
            )
        }
        if (evidence.activeSignerSha256 != setOf(expectedSignerSha256.uppercase())) {
            val actual = evidence.activeSignerSha256.sorted().joinToString().ifBlank { "<空>" }
            return TargetPackageIdentityVerification(
                false,
                "目标包签名证书不匹配：$actual",
            )
        }
        if (evidence.uid != expectedUid) {
            return TargetPackageIdentityVerification(
                false,
                "目标包安装 UID 不匹配：${evidence.uid}，控制服务 UID $expectedUid",
            )
        }
        return TargetPackageIdentityVerification(
            true,
            "目标包证书、sharedUserId 与安装 UID 已核对",
        )
    }
}

internal class AndroidTargetPackageIdentityVerifier(
    private val packageManagerProvider: () -> PackageManager,
    private val expectedUid: Int,
) : TargetPackageIdentityVerifier {
    override fun verify(packageName: String): TargetPackageIdentityVerification {
        if (!ControlProtocol.isValidPackageName(packageName)) {
            return TargetPackageIdentityVerification(false, "目标包名格式无效")
        }
        val evidence = readEvidence(packageName)
        return CertificateSharedUidIdentity.evaluate(
            packageName = packageName,
            evidence = evidence,
            expectedUid = expectedUid,
        )
    }

    @Suppress("DEPRECATION")
    private fun readEvidence(packageName: String): TargetPackageIdentityEvidence? = runCatching {
        val packageInfo = packageManagerProvider().getPackageInfo(
            packageName,
            PackageManager.GET_SIGNING_CERTIFICATES,
        )
        val signers = packageInfo.signingInfo
            ?.apkContentsSigners
            .orEmpty()
            .mapTo(linkedSetOf()) { signature -> sha256(signature.toByteArray()) }
        TargetPackageIdentityEvidence(
            uid = packageInfo.applicationInfo?.uid ?: INVALID_UID,
            sharedUserId = packageInfo.sharedUserId,
            activeSignerSha256 = signers,
        )
    }.getOrNull()

    private fun sha256(value: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value)
            .joinToString(separator = "") { byte -> "%02X".format(byte.toInt() and 0xff) }

    private companion object {
        const val INVALID_UID = -1
    }
}
