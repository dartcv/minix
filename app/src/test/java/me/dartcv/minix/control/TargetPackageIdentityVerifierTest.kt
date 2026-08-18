package me.dartcv.minix.control

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetPackageIdentityVerifierTest {
    @Test
    fun exactCertificateSharedUidAndUidPass() {
        val result = CertificateSharedUidIdentity.evaluate(
            packageName = TARGET_PACKAGE,
            evidence = validEvidence(),
            expectedUid = TARGET_UID,
        )

        assertTrue(result.isValid)
        assertTrue(result.message.contains("已核对"))
    }

    @Test
    fun missingPackageFailsClosed() {
        val result = CertificateSharedUidIdentity.evaluate(
            packageName = TARGET_PACKAGE,
            evidence = null,
            expectedUid = TARGET_UID,
        )

        assertFalse(result.isValid)
        assertTrue(result.message.contains("未安装"))
    }

    @Test
    fun sharedUserMismatchFailsClosed() {
        val result = CertificateSharedUidIdentity.evaluate(
            packageName = TARGET_PACKAGE,
            evidence = validEvidence().copy(sharedUserId = "different.shared.uid"),
            expectedUid = TARGET_UID,
        )

        assertFalse(result.isValid)
        assertTrue(result.message.contains("sharedUserId 不匹配"))
    }

    @Test
    fun signerMismatchFailsClosed() {
        val result = CertificateSharedUidIdentity.evaluate(
            packageName = TARGET_PACKAGE,
            evidence = validEvidence().copy(activeSignerSha256 = setOf("0".repeat(64))),
            expectedUid = TARGET_UID,
        )

        assertFalse(result.isValid)
        assertTrue(result.message.contains("签名证书不匹配"))
    }

    @Test
    fun installedUidMismatchFailsClosed() {
        val result = CertificateSharedUidIdentity.evaluate(
            packageName = TARGET_PACKAGE,
            evidence = validEvidence().copy(uid = TARGET_UID + 1),
            expectedUid = TARGET_UID,
        )

        assertFalse(result.isValid)
        assertTrue(result.message.contains("安装 UID 不匹配"))
    }

    private fun validEvidence() = TargetPackageIdentityEvidence(
        uid = TARGET_UID,
        sharedUserId = CertificateSharedUidIdentity.SHARED_USER_ID,
        activeSignerSha256 = setOf(CertificateSharedUidIdentity.SIGNER_SHA256),
    )

    private companion object {
        const val TARGET_PACKAGE = "com.example.target"
        const val TARGET_UID = 10_321
    }
}
