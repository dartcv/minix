package me.dartcv.minix.control

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProcControlAntiFlashTargetResolverTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun fullMapsGenerationMismatchDoesNotRejectStableScopedMappings() {
        val resolution = resolve(initialMaps = mapsText())

        assertEquals(ControlAntiFlashResolveStatus.RESOLVED, resolution.status)
        val target = requireNotNull(resolution.target)
        assertNotEquals(UNRELATED_FULL_MAPS_GENERATION, target.mapsGeneration)
        assertEquals(GAME_APP_BASE, target.gameAppLoadBias)
        assertEquals(TPRT_BASE, target.tprtLoadBias)
    }

    @Test
    fun unrelatedMappingChangeBetweenSnapshotsUsesFinalSnapshot() {
        val initialPath = "[anon:unrelated-initial]"
        val finalPath = "[anon:unrelated-final]"

        val resolution = resolve(
            initialMaps = mapsText(unrelatedPath = initialPath),
            finalMaps = mapsText(unrelatedPath = finalPath),
        )

        assertEquals(ControlAntiFlashResolveStatus.RESOLVED, resolution.status)
        val target = requireNotNull(resolution.target)
        assertEquals(16, target.mapsGeneration.length)
        assertTrue(target.scopedMappings.any { it.path == finalPath })
        assertFalse(target.scopedMappings.any { it.path == initialPath })
    }

    @Test
    fun accessedMappingInodeChangeBetweenSnapshotsReturnsTargetChanged() {
        val resolution = resolve(
            initialMaps = mapsText(tprtInode = 102),
            finalMaps = mapsText(tprtInode = 103),
        )

        assertEquals(ControlAntiFlashResolveStatus.TARGET_CHANGED, resolution.status)
        assertNull(resolution.target)
    }

    @Test
    fun accessedMappingPermissionLossBetweenSnapshotsReturnsTargetChanged() {
        val resolution = resolve(
            initialMaps = mapsText(tprtPermissions = "r-xp"),
            finalMaps = mapsText(tprtPermissions = "r--p"),
        )

        assertEquals(ControlAntiFlashResolveStatus.TARGET_CHANGED, resolution.status)
        assertNull(resolution.target)
    }

    @Test
    fun identityChangeAfterFinalMapsReturnsTargetChanged() {
        val resolution = resolve(
            initialMaps = mapsText(),
            startTimes = listOf(START_TIME, START_TIME, "54321"),
        )

        assertEquals(ControlAntiFlashResolveStatus.TARGET_CHANGED, resolution.status)
        assertNull(resolution.target)
    }

    private fun resolve(
        initialMaps: String,
        finalMaps: String = initialMaps,
        startTimes: List<String> = listOf(START_TIME, START_TIME, START_TIME),
    ): ControlAntiFlashTargetResolution {
        val profile = ControlAntiFlashProfileCatalog.profile
        val procControl = temporaryFolder.newFolder("proc-${System.nanoTime()}")
        val pidDirectory = File(procControl, PID.toString()).apply { mkdirs() }
        val mapsFile = File(pidDirectory, "maps").apply { writeText(initialMaps) }
        var identityReads = 0
        val inspector = object : TargetProcessInspector {
            override fun findPid(packageName: String): Int? = PID
            override fun readProcessName(pid: Int): String? = PACKAGE_NAME
            override fun readStartTimeTicks(pid: Int): String? {
                identityReads += 1
                if (identityReads == 2) mapsFile.writeText(finalMaps)
                return startTimes.getOrElse(identityReads - 1) { startTimes.last() }
            }

            override fun readEffectiveUid(pid: Int): Int? = UID
            override fun readBrief(pid: Int): TargetProcessBrief? = null
            override fun isAlive(pid: Int): Boolean = pid == PID
        }
        return ProcControlAntiFlashTargetResolver(inspector, procControl).resolve(
            request = request(profile),
            profile = profile,
        )
    }

    private fun request(profile: ControlAntiFlashProfile) = ControlAntiFlashStartRequest(
        pid = PID,
        startTimeTicks = START_TIME,
        mapsGeneration = UNRELATED_FULL_MAPS_GENERATION,
        modules = listOf(
            ControlNativeModuleIdentity(
                name = profile.gameApp.name,
                path = GAME_APP_PATH,
                loadBase = GAME_APP_BASE,
                mappedBytes = profile.gameApp.fileSizeBytes,
                memoryElf = true,
                sha256 = profile.gameApp.sha256,
            ),
            ControlNativeModuleIdentity(
                name = profile.tprt.name,
                path = TPRT_PATH,
                loadBase = TPRT_BASE,
                mappedBytes = profile.tprt.fileSizeBytes,
                memoryElf = true,
                sha256 = profile.tprt.sha256,
            ),
        ),
    )

    private fun mapsText(
        unrelatedPath: String = "[anon:unrelated]",
        tprtInode: Long = 102,
        tprtPermissions: String = "r-xp",
    ): String = buildString {
        appendLine(mapRow(GAME_APP_BASE, GAME_APP_BASE + GAME_APP_RX_SIZE, "r-xp", GAME_APP_PATH, 101))
        appendLine(mapRow(TPRT_BASE, TPRT_BASE + TPRT_RX_SIZE, tprtPermissions, TPRT_PATH, tprtInode))
        appendLine(
            mapRow(
                GAME_APP_BASE + ControlGameAppArtifact1582.ANON_BSS_4K_OFFSET,
                GAME_APP_BASE + ControlGameAppArtifact1582.ANON_BSS_4K_END,
                "rw-p",
                "[anon:.bss]",
                0,
                device = "00:00",
            ),
        )
        appendLine(mapRow(UNRELATED_BASE, UNRELATED_BASE + 0x1000L, "rw-p", unrelatedPath, 0))
    }

    private fun mapRow(
        start: Long,
        end: Long,
        permissions: String,
        path: String,
        inode: Long,
        device: String = "fe:54",
    ): String = "%x-%x %s 00000000 %s %d %s".format(
        start,
        end,
        permissions,
        device,
        inode,
        path,
    )

    private companion object {
        const val PID = 77
        const val UID = 10_552
        const val START_TIME = "12345"
        const val PACKAGE_NAME = "com.minitech.miniworld"
        const val GAME_APP_BASE = 0x1_0000_0000L
        const val TPRT_BASE = 0x2_0000_0000L
        const val UNRELATED_BASE = 0x3_0000_0000L
        const val GAME_APP_RX_SIZE = 0x0a13_e000L
        const val TPRT_RX_SIZE = 0x0018_4000L
        const val GAME_APP_PATH = "/data/app/fixture/lib/arm64/liblibGameApp.so"
        const val TPRT_PATH = "/data/app/fixture/lib/arm64/libtprt.so"
        const val UNRELATED_FULL_MAPS_GENERATION = "ffffffffffffffff"
    }
}
