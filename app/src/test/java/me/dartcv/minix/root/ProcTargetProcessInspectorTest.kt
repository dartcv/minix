package me.dartcv.minix.root

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProcTargetProcessInspectorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun readsOnlyFixedProcFilesForProcessMetadata() {
        val procRoot = temporaryFolder.newFolder("proc")
        val processDirectory = File(procRoot, "123").apply { mkdirs() }
        File(processDirectory, "cmdline").writeBytes("com.example.target\u0000ignored".toByteArray())
        File(processDirectory, "stat").writeText(
            "123 (target name) S 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 987654\n",
        )
        File(processDirectory, "status").writeText(
            "Name:\ttarget\n" +
                "State:\tS (sleeping)\n" +
                "Uid:\t10101\t10321\t10321\t10321\n" +
                "Threads:\t9\n" +
                "VmRSS:\t4096 kB\n",
        )
        File(processDirectory, "maps").writeText("map-one\nmap-two\nmap-three\n")
        val inspector = ProcTargetProcessInspector(procRoot)

        assertEquals(123, inspector.findPid("com.example.target"))
        assertTrue(inspector.isAlive(123))
        assertEquals("987654", inspector.readStartTimeTicks(123))
        assertEquals(10321, inspector.readEffectiveUid(123))
        val brief = requireNotNull(inspector.readBrief(123))
        assertEquals("target", brief.name)
        assertEquals("S (sleeping)", brief.state)
        assertEquals(9, brief.threads)
        assertEquals("4096 kB", brief.vmRss)
        assertEquals(3, brief.readableMapLines)
        assertEquals(10321, brief.effectiveUid)
    }

    @Test
    fun findPidPrefersTheExactMainProcessOverLowerPidWorkers() {
        val procRoot = temporaryFolder.newFolder("proc-main-priority")
        File(procRoot, "101").apply {
            mkdirs()
            File(this, "cmdline").writeBytes("com.example.target:worker\u0000".toByteArray())
        }
        File(procRoot, "202").apply {
            mkdirs()
            File(this, "cmdline").writeBytes("com.example.target\u0000".toByteArray())
        }

        assertEquals(202, ProcTargetProcessInspector(procRoot).findPid("com.example.target"))
    }

    @Test
    fun rejectsMalformedOrMissingProcessStartTime() {
        val procRoot = temporaryFolder.newFolder("proc-stat-invalid")
        File(procRoot, "100").apply {
            mkdirs()
            File(this, "stat").writeText("100 malformed\n")
        }

        val inspector = ProcTargetProcessInspector(procRoot)
        assertNull(inspector.readStartTimeTicks(100))
        assertNull(inspector.readStartTimeTicks(101))
    }

    @Test
    fun effectiveUidUsesTheSecondProcStatusField() {
        val procRoot = temporaryFolder.newFolder("proc-effective-uid")
        val processDirectory = File(procRoot, "321").apply { mkdirs() }
        File(processDirectory, "status").writeText(
            "Uid:\t11001\t22002\t33003\t44004\n",
        )

        assertEquals(22002, ProcTargetProcessInspector(procRoot).readEffectiveUid(321))
    }

    @Test
    fun rejectsMissingMalformedDuplicateOrOverflowingEffectiveUid() {
        val procRoot = temporaryFolder.newFolder("proc-uid-invalid")
        fun writeStatus(pid: Int, value: String) {
            File(procRoot, pid.toString()).apply {
                mkdirs()
                File(this, "status").writeText(value)
            }
        }
        writeStatus(100, "Name:\ttarget\n")
        writeStatus(101, "Uid:\t1000\tbroken\t1000\t1000\n")
        writeStatus(102, "Uid:\t1000\t2147483648\t1000\t1000\n")
        writeStatus(103, "Uid:\t1000\t1001\t1001\n")
        writeStatus(104, "Uid:\t1000\t1001\t1001\t1001\nUid:\t1\t1\t1\t1\n")
        val inspector = ProcTargetProcessInspector(procRoot)

        (100..104).forEach { pid -> assertNull(inspector.readEffectiveUid(pid)) }
        assertNull(inspector.readEffectiveUid(999))
    }
}
