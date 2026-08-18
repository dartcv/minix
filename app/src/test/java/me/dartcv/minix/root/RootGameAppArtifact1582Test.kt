package me.dartcv.minix.root

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RootGameAppArtifact1582Test {
    @Test
    fun staticIdentityConstantsMatchTheVerifiedElfArtifact() {
        assertEquals("liblibGameApp.so", RootGameAppArtifact1582.MODULE_NAME)
        assertEquals(
            "d8efe55c37b061ce41cb3830ec743401138bf40b8bacf251f18f7b026bf0140e",
            RootGameAppArtifact1582.SHA256,
        )
        assertEquals(
            "662a450a7331aff319cbc4b3897c2124f8fd61f9",
            RootGameAppArtifact1582.GNU_BUILD_ID,
        )
        assertEquals(176_533_320L, RootGameAppArtifact1582.FILE_SIZE_BYTES)
        assertEquals("ELF64", RootGameAppArtifact1582.ELF_CLASS)
        assertEquals("AArch64", RootGameAppArtifact1582.ELF_MACHINE)
        assertEquals("DYN", RootGameAppArtifact1582.ELF_TYPE)
        assertEquals(0x02c49f50L, RootGameAppArtifact1582.ELF_ENTRY_POINT)
        assertEquals(3, RootGameAppArtifact1582.LOAD_SEGMENT_COUNT)
        assertEquals(0x1000L, RootGameAppArtifact1582.LOAD_ALIGNMENT_BYTES)
        assertEquals(0x0a85a780L, RootGameAppArtifact1582.LOAD_FILE_BACKED_END)
        assertEquals(0x0b3c0128L, RootGameAppArtifact1582.LOAD_VIRTUAL_END)
        assertEquals(0x0a85c780L, RootGameAppArtifact1582.BSS_SECTION_VADDR)
        assertEquals(0x0a85d000L, RootGameAppArtifact1582.ANON_BSS_4K_OFFSET)
        assertEquals(0x0b3c1000L, RootGameAppArtifact1582.ANON_BSS_4K_END)
        assertEquals(0x0001cc78L, RootGameAppArtifact1582.KILL_COUNT_ANCHOR_OFFSET)
        assertEquals(0x0a879c78L, RootGameAppArtifact1582.KILL_COUNT_LOAD_OFFSET)
        assertEquals(0x0afde370L, RootGameAppArtifact1582.PLAYER_CONTROL_GLOBAL_RVA)
        assertEquals(0x00781370L, RootGameAppArtifact1582.PLAYER_CONTROL_BSS_OFFSET)
        assertEquals(
            RootGameAppArtifact1582.PLAYER_CONTROL_GLOBAL_RVA,
            RootGameAppArtifact1582.ANON_BSS_4K_OFFSET +
                RootGameAppArtifact1582.PLAYER_CONTROL_BSS_OFFSET,
        )
        assertEquals(
            RootGameAppArtifact1582.ANON_BSS_4K_OFFSET +
                RootGameAppArtifact1582.KILL_COUNT_ANCHOR_OFFSET,
            RootGameAppArtifact1582.KILL_COUNT_LOAD_OFFSET,
        )
        assertTrue(
            RootGameAppArtifact1582.KILL_COUNT_LOAD_OFFSET + Int.SIZE_BYTES <=
                RootGameAppArtifact1582.ANON_BSS_4K_END,
        )
        assertEquals(
            "artifacts/liblibGameApp-1.58.2-elf-identity.json",
            RootGameAppArtifact1582.SOURCE_ARTIFACT,
        )
        assertTrue(!RootGameAppArtifact1582.SOURCE_ARTIFACT.contains(":"))
    }

    @Test
    fun allThreeProductionProfilesReuseTheSharedModuleIdentity() {
        val readOnly = RootReadOnlyFieldProfileCatalog.candidates.single()
        val injection = RootInjectionProfileCatalog.candidates.single()
        val searchId = RootRecoveredSearchIdProfiles.miniWorld1582

        assertEquals(RootGameAppArtifact1582.MODULE_NAME, readOnly.moduleEvidence.moduleName)
        assertEquals(RootGameAppArtifact1582.SHA256, readOnly.moduleEvidence.normalizedSha256)
        assertEquals(RootGameAppArtifact1582.SOURCE_ARTIFACT, readOnly.moduleEvidence.sourceArtifact)

        assertEquals(RootGameAppArtifact1582.MODULE_NAME, injection.moduleEvidence.moduleName)
        assertEquals(RootGameAppArtifact1582.SHA256, injection.moduleEvidence.normalizedSha256)
        assertEquals(RootGameAppArtifact1582.SOURCE_ARTIFACT, injection.moduleEvidence.sourceArtifact)
        assertTrue(injection.requiresModuleIdentity)

        assertEquals(RootGameAppArtifact1582.MODULE_NAME, searchId.moduleName)
        assertEquals(RootGameAppArtifact1582.SHA256, searchId.normalizedSha256)
        assertEquals("${RootGameAppArtifact1582.MODULE_NAME}:bss", searchId.moduleSpec)

        val expectedFingerprintModules = setOf(RootGameAppArtifact1582.MODULE_NAME)
        assertEquals(expectedFingerprintModules, RootReadOnlyFieldProfileCatalog.fingerprintModuleNames)
        assertEquals(expectedFingerprintModules, RootInjectionProfileCatalog.fingerprintModuleNames)
        assertEquals(expectedFingerprintModules, RootRecoveredSearchIdProfiles.fingerprintModuleNames)
    }
}
