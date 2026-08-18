package me.dartcv.minix.root

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProcRootModuleFingerprintProviderTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun deletedMappingUsesExactOffsetZeroLoadBaseMapFile() {
        val procRoot = temporaryFolder.newFolder("proc-deleted")
        val process = File(procRoot, PID.toString()).apply { mkdirs() }
        val mapFiles = File(process, "map_files").apply { mkdirs() }
        val expectedBytes = "deleted-module-fixture".toByteArray()
        File(mapFiles, "1000-2000").writeBytes(expectedBytes)
        File(mapFiles, "3000-4000").writeBytes("wrong".toByteArray())
        File(process, "maps").writeText(
            "00001000-00002000 r-xp 00000000 00:00 0 $DELETED_PATH\n" +
                "00003000-00004000 r-xp 00000000 00:00 0 $DELETED_PATH\n",
        )

        assertEquals(
            sha256(expectedBytes),
            ProcRootModuleFingerprintProvider.sha256(PID, DELETED_PATH, 0x1000, procRoot),
        )
    }

    @Test
    fun deletedMappingWithoutExactUniqueLoadBaseFailsClosed() {
        val procRoot = temporaryFolder.newFolder("proc-deleted-fail")
        val process = File(procRoot, PID.toString()).apply { mkdirs() }
        val mapFiles = File(process, "map_files").apply { mkdirs() }
        File(mapFiles, "1000-2000").writeBytes("fixture".toByteArray())
        File(process, "maps").writeText(
            "00001000-00002000 r-xp 00000000 00:00 0 $DELETED_PATH\n",
        )

        assertNull(ProcRootModuleFingerprintProvider.sha256(PID, DELETED_PATH, 0x3000, procRoot))
        assertNull(ProcRootModuleFingerprintProvider.sha256(PID, DELETED_PATH, 0, procRoot))
    }

    @Test
    fun deletedMappingRejectsNonZeroFileOffset() {
        val procRoot = temporaryFolder.newFolder("proc-deleted-offset")
        val process = File(procRoot, PID.toString()).apply { mkdirs() }
        val mapFiles = File(process, "map_files").apply { mkdirs() }
        File(mapFiles, "1000-2000").writeBytes("fixture".toByteArray())
        File(process, "maps").writeText(
            "00001000-00002000 r-xp 00001000 00:00 0 $DELETED_PATH\n",
        )

        assertNull(ProcRootModuleFingerprintProvider.sha256(PID, DELETED_PATH, 0x1000, procRoot))
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private companion object {
        const val PID = 77
        const val DELETED_PATH = "/data/app/libfixture.so (deleted)"
    }
}
