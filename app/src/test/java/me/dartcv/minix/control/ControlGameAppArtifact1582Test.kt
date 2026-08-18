package me.dartcv.minix.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlGameAppArtifact1582Test {
    @Test
    fun staticIdentityConstantsMatchTheVerifiedElfArtifact() {
        assertEquals("liblibGameApp.so", ControlGameAppArtifact1582.MODULE_NAME)
        assertEquals(
            "d8efe55c37b061ce41cb3830ec743401138bf40b8bacf251f18f7b026bf0140e",
            ControlGameAppArtifact1582.SHA256,
        )
        assertEquals(
            "662a450a7331aff319cbc4b3897c2124f8fd61f9",
            ControlGameAppArtifact1582.GNU_BUILD_ID,
        )
        assertEquals(176_533_320L, ControlGameAppArtifact1582.FILE_SIZE_BYTES)
        assertEquals("ELF64", ControlGameAppArtifact1582.ELF_CLASS)
        assertEquals("AArch64", ControlGameAppArtifact1582.ELF_MACHINE)
        assertEquals("DYN", ControlGameAppArtifact1582.ELF_TYPE)
        assertEquals(0x02c49f50L, ControlGameAppArtifact1582.ELF_ENTRY_POINT)
        assertEquals(3, ControlGameAppArtifact1582.LOAD_SEGMENT_COUNT)
        assertEquals(0x1000L, ControlGameAppArtifact1582.LOAD_ALIGNMENT_BYTES)
        assertEquals(0x0a85a780L, ControlGameAppArtifact1582.LOAD_FILE_BACKED_END)
        assertEquals(0x0b3c0128L, ControlGameAppArtifact1582.LOAD_VIRTUAL_END)
        assertEquals(0x0a85c780L, ControlGameAppArtifact1582.BSS_SECTION_VADDR)
        assertEquals(0x0a85d000L, ControlGameAppArtifact1582.ANON_BSS_4K_OFFSET)
        assertEquals(0x0b3c1000L, ControlGameAppArtifact1582.ANON_BSS_4K_END)
        assertEquals(0x0001cc78L, ControlGameAppArtifact1582.KILL_COUNT_ANCHOR_OFFSET)
        assertEquals(0x0a879c78L, ControlGameAppArtifact1582.KILL_COUNT_LOAD_OFFSET)
        assertEquals(0x0afde370L, ControlGameAppArtifact1582.PLAYER_CONTROL_GLOBAL_RVA)
        assertEquals(0x00781370L, ControlGameAppArtifact1582.PLAYER_CONTROL_BSS_OFFSET)
        assertEquals(
            ControlGameAppArtifact1582.PLAYER_CONTROL_GLOBAL_RVA,
            ControlGameAppArtifact1582.ANON_BSS_4K_OFFSET +
                ControlGameAppArtifact1582.PLAYER_CONTROL_BSS_OFFSET,
        )
        assertEquals(
            ControlGameAppArtifact1582.ANON_BSS_4K_OFFSET +
                ControlGameAppArtifact1582.KILL_COUNT_ANCHOR_OFFSET,
            ControlGameAppArtifact1582.KILL_COUNT_LOAD_OFFSET,
        )
        assertTrue(
            ControlGameAppArtifact1582.KILL_COUNT_LOAD_OFFSET + Int.SIZE_BYTES <=
                ControlGameAppArtifact1582.ANON_BSS_4K_END,
        )
        assertEquals(
            "artifacts/liblibGameApp-1.58.2-elf-identity.json",
            ControlGameAppArtifact1582.SOURCE_ARTIFACT,
        )
        assertTrue(!ControlGameAppArtifact1582.SOURCE_ARTIFACT.contains(":"))
    }

    @Test
    fun allThreeProductionProfilesReuseTheSharedModuleIdentity() {
        val readOnly = ControlReadOnlyFieldProfileCatalog.candidates.single()
        val injection = ControlInjectionProfileCatalog.candidates.single()
        val searchId = ControlRecoveredSearchIdProfiles.miniWorld1582

        assertEquals(ControlGameAppArtifact1582.MODULE_NAME, readOnly.moduleEvidence.moduleName)
        assertEquals(ControlGameAppArtifact1582.SHA256, readOnly.moduleEvidence.normalizedSha256)
        assertEquals(ControlGameAppArtifact1582.SOURCE_ARTIFACT, readOnly.moduleEvidence.sourceArtifact)

        assertEquals(ControlGameAppArtifact1582.MODULE_NAME, injection.moduleEvidence.moduleName)
        assertEquals(ControlGameAppArtifact1582.SHA256, injection.moduleEvidence.normalizedSha256)
        assertEquals(ControlGameAppArtifact1582.SOURCE_ARTIFACT, injection.moduleEvidence.sourceArtifact)
        assertTrue(injection.requiresModuleIdentity)

        assertEquals(ControlGameAppArtifact1582.MODULE_NAME, searchId.moduleName)
        assertEquals(ControlGameAppArtifact1582.SHA256, searchId.normalizedSha256)
        assertEquals("${ControlGameAppArtifact1582.MODULE_NAME}:bss", searchId.moduleSpec)

        val expectedFingerprintModules = setOf(ControlGameAppArtifact1582.MODULE_NAME)
        assertEquals(expectedFingerprintModules, ControlReadOnlyFieldProfileCatalog.fingerprintModuleNames)
        assertEquals(expectedFingerprintModules, ControlInjectionProfileCatalog.fingerprintModuleNames)
        assertEquals(expectedFingerprintModules, ControlRecoveredSearchIdProfiles.fingerprintModuleNames)
    }
}
