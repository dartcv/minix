package me.dartcv.minix.root

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RootAntiFlashScopedMapsTest {
    @Test
    fun unrelatedMappingChangesDoNotChangeScopedGeneration() {
        val target = target(
            scopedMappings = listOf(
                mapping(0x1000, 0x2000, "r-xp", 0, "/data/liba.so"),
                mapping(0x3000, 0x4000, "rw-p", 0, "[anon:.bss]"),
                mapping(0x5000, 0x6000, "r--p", 0, "/data/unrelated-a"),
            ),
        )
        val changedUnrelated = target.copy(
            scopedMappings = target.scopedMappings.dropLast(1) +
                mapping(0x7000, 0x8000, "r--p", 0, "/data/unrelated-b"),
        )
        val requests = listOf(
            RootAntiFlashMappingRange(0x1100, 4, requireWritable = false, requireExecutable = true),
            RootAntiFlashMappingRange(0x3100, 4, requireWritable = true, requireExecutable = false),
        )

        assertEquals(
            target.scopedMapsGeneration(requests),
            changedUnrelated.scopedMapsGeneration(requests),
        )
    }

    @Test
    fun accessedMappingIdentityChangeChangesScopedGeneration() {
        val target = target(
            scopedMappings = listOf(
                mapping(0x1000, 0x2000, "r-xp", 0, "/data/liba.so"),
                mapping(0x3000, 0x4000, "rw-p", 0, "[anon:.bss]"),
            ),
        )
        val changed = target.copy(
            scopedMappings = listOf(
                mapping(0x1000, 0x2000, "r-xp", 0x1000, "/data/liba.so"),
                target.scopedMappings[1],
            ),
        )
        val requests = listOf(
            RootAntiFlashMappingRange(0x1100, 4, requireWritable = false, requireExecutable = true),
        )

        assertNotEquals(
            target.scopedMapsGeneration(requests),
            changed.scopedMapsGeneration(requests),
        )
    }

    @Test
    fun accessedMappingInodeChangeChangesScopedGeneration() {
        val target = target(
            scopedMappings = listOf(
                mapping(0x1000, 0x2000, "r-xp", 0, "/data/liba.so", inode = "41"),
            ),
        )
        val changed = target.copy(
            scopedMappings = listOf(
                mapping(0x1000, 0x2000, "r-xp", 0, "/data/liba.so", inode = "42"),
            ),
        )
        val requests = listOf(
            RootAntiFlashMappingRange(0x1100, 4, requireWritable = false, requireExecutable = true),
        )

        assertNotEquals(
            target.scopedMapsGeneration(requests),
            changed.scopedMapsGeneration(requests),
        )
    }

    @Test
    fun mappingPermissionLossOrAmbiguityFailsClosed() {
        val request = listOf(
            RootAntiFlashMappingRange(0x1100, 4, requireWritable = false, requireExecutable = true),
        )
        val permissionLoss = target(
            scopedMappings = listOf(mapping(0x1000, 0x2000, "r--p", 0, "/data/liba.so")),
        )
        val ambiguous = target(
            scopedMappings = listOf(
                mapping(0x1000, 0x2000, "r-xp", 0, "/data/liba.so"),
                mapping(0x1000, 0x2000, "r-xp", 0, "/data/liba-copy.so"),
            ),
        )

        assertNull(permissionLoss.scopedMapsGeneration(request))
        assertNull(ambiguous.scopedMapsGeneration(request))
    }

    private fun target(scopedMappings: List<RootAntiFlashScopedMapping>) = RootAntiFlashTarget(
        pid = 77,
        startTimeTicks = "123",
        mapsGeneration = "0123456789abcdef",
        gameAppLoadBias = 0x1000,
        tprtLoadBias = 0x2000,
        gameAppBssAnchor = 0x3000,
        gameAppPath = "/data/liba.so",
        tprtPath = "/data/libb.so",
        scopedMappings = scopedMappings,
    )

    private fun mapping(
        start: Long,
        end: Long,
        permissions: String,
        fileOffset: Long,
        path: String,
        device: String = "00:00",
        inode: String = "0",
    ) = RootAntiFlashScopedMapping(start, end, permissions, fileOffset, device, inode, path)
}
