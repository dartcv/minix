package me.dartcv.minix.control

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlReadOnlyFieldProfilesTest {
    @Test
    fun productionProfileUsesExactArtifactFingerprintAndTypedFieldRecipes() {
        val candidate = ControlReadOnlyFieldProfileCatalog.candidates.single()

        assertEquals(2, candidate.schemaVersion)
        assertEquals("1.58.2", candidate.targetVersion)
        assertTrue(candidate.isEvidenceComplete)
        assertEquals(ControlGameAppArtifact1582.MODULE_NAME, candidate.moduleEvidence.moduleName)
        assertEquals(ControlGameAppArtifact1582.SHA256, candidate.moduleEvidence.expectedSha256)
        assertEquals(setOf("liblibGameApp.so"), ControlReadOnlyFieldProfileCatalog.fingerprintModuleNames)
        assertEquals(
            ControlRecoveredAddressRecipes.lifeState,
            candidate.fields.single {
                it.id == ControlReadOnlyFieldId.LIFE_STATE
            }.addressRecipe,
        )
        assertEquals(
            ControlInt32SourceEncoding.FLOAT32_TRUNCATE_TOWARD_ZERO,
            (candidate.fields.single {
                it.id == ControlReadOnlyFieldId.LIFE_STATE
            } as ControlInt32FieldDefinition).sourceEncoding,
        )
        assertEquals(
            ControlRecoveredAddressRecipes.dataLongSelector1,
            candidate.fields.single {
                it.id == ControlReadOnlyFieldId.DATA_LONG_SELECTOR_1
            }.addressRecipe,
        )
        assertEquals(
            ControlModuleSpecFieldAddressRecipe(
                anchorProfile = ControlRecoveredSearchIdProfiles.miniWorld1582,
                finalOffset = ControlGameAppArtifact1582.KILL_COUNT_ANCHOR_OFFSET,
                sourceArtifact = ControlGameAppArtifact1582.SOURCE_ARTIFACT,
                sourceReference =
                    "Verified anonymous-BSS anchor plus recovered GetData selector 3 offset",
            ),
            candidate.fields.single {
                it.id == ControlReadOnlyFieldId.KILL_COUNT
            }.moduleSpecAddressRecipe,
        )
        assertEquals(
            listOf(
                ControlReadOnlyFieldId.LIFE_STATE to Int.SIZE_BYTES,
                ControlReadOnlyFieldId.KILL_COUNT to Int.SIZE_BYTES,
                ControlReadOnlyFieldId.DATA_LONG_SELECTOR_1 to Long.SIZE_BYTES,
            ),
            candidate.fields.map { definition -> definition.id to definition.byteCount },
        )

        val mismatchedModuleRecipe = candidate.copy(
            moduleEvidence = candidate.moduleEvidence.copy(expectedSha256 = "b".repeat(64)),
        )
        assertFalse(mismatchedModuleRecipe.isEvidenceComplete)
    }

    @Test
    fun productionProfileIsReadyOnlyForTheExactUniqueModuleIdentity() {
        val candidate = ControlReadOnlyFieldProfileCatalog.candidates.single()
        val module = ControlNativeModuleIdentity(
            name = ControlGameAppArtifact1582.MODULE_NAME,
            path = "/data/app/${ControlGameAppArtifact1582.MODULE_NAME}",
            loadBase = 0x7100_0000L,
            mappedBytes = 0x1000L,
            memoryElf = true,
            sha256 = ControlGameAppArtifact1582.SHA256,
        )

        val resolution = ControlReadOnlyFieldProfileCatalog.resolver.resolve(
            ControlTargetCatalog.default.packageName,
            listOf(module),
        )

        assertTrue(resolution.state.isReady)
        assertNull(resolution.profile?.fields?.get(ControlReadOnlyFieldId.KILL_COUNT)?.address)
    }

    @Test
    fun completeEvidenceResolvesExactModuleAndModuleRelativeAddresses() {
        val resolver = ControlReadOnlyFieldProfileResolver(listOf(completeCandidate()))

        val resolution = resolver.resolve(
            packageName = "com.example.target",
            modules = listOf(matchingModule()),
        )

        assertTrue(resolution.state.isReady)
        assertEquals("fixture-v1", resolution.state.profileId)
        assertEquals(
            0x71000020L,
            resolution.profile?.fields?.get(ControlReadOnlyFieldId.LIFE_STATE)?.address,
        )
        assertEquals(
            0x71000030L,
            resolution.profile?.fields?.get(ControlReadOnlyFieldId.DATA_LONG_SELECTOR_1)?.address,
        )
    }

    @Test
    fun fingerprintMismatchDoesNotResolveFields() {
        val resolver = ControlReadOnlyFieldProfileResolver(listOf(completeCandidate()))

        val resolution = resolver.resolve(
            packageName = "com.example.target",
            modules = listOf(matchingModule().copy(sha256 = "b".repeat(64))),
        )

        assertEquals(ControlReadOnlyFieldProfileStatus.FINGERPRINT_MISMATCH, resolution.state.status)
        assertNull(resolution.profile)
        assertTrue(resolution.state.fieldIds.isEmpty())
    }

    @Test
    fun duplicateNamedModulesFailClosedEvenWhenOnlyOneFingerprintMatches() {
        val resolver = ControlReadOnlyFieldProfileResolver(listOf(completeCandidate()))

        val resolution = resolver.resolve(
            packageName = "com.example.target",
            modules = listOf(
                matchingModule(),
                matchingModule().copy(
                    path = "/other/libfixture.so",
                    sha256 = "b".repeat(64),
                ),
            ),
        )

        assertEquals(ControlReadOnlyFieldProfileStatus.MODULE_AMBIGUOUS, resolution.state.status)
        assertNull(resolution.profile)
    }

    @Test
    fun duplicateExactFingerprintMatchesFailClosedAsAmbiguous() {
        val resolver = ControlReadOnlyFieldProfileResolver(listOf(completeCandidate()))

        val resolution = resolver.resolve(
            packageName = "com.example.target",
            modules = listOf(
                matchingModule(),
                matchingModule().copy(path = "/other/libfixture.so"),
            ),
        )

        assertEquals(ControlReadOnlyFieldProfileStatus.MODULE_AMBIGUOUS, resolution.state.status)
        assertNull(resolution.profile)
    }

    @Test
    fun missingOffsetEvidenceStopsProfileBeforeModuleMatching() {
        val candidate = completeCandidate().copy(
            fields = completeCandidate().fields.map { definition ->
                if (definition.id == ControlReadOnlyFieldId.KILL_COUNT) {
                    ControlInt32FieldDefinition(
                        id = ControlReadOnlyFieldId.KILL_COUNT,
                        offsetEvidence = ControlModuleOffsetEvidence(
                            moduleOffset = null,
                            sourceArtifact = "fixture.json",
                            sourceReference = "missing",
                        ),
                    )
                } else {
                    definition
                }
            },
        )
        val resolver = ControlReadOnlyFieldProfileResolver(listOf(candidate))

        val resolution = resolver.resolve("com.example.target", listOf(matchingModule()))

        assertEquals(ControlReadOnlyFieldProfileStatus.INCOMPLETE_EVIDENCE, resolution.state.status)
        assertNull(resolution.profile)
    }

    @Test
    fun structurallyValidResolverRecipeCanReplaceADirectModuleOffset() {
        val candidate = completeCandidate().copy(
            fields = listOf(
                ControlInt64FieldDefinition(
                    id = ControlReadOnlyFieldId.DATA_LONG_SELECTOR_1,
                    offsetEvidence = ControlModuleOffsetEvidence(
                        moduleOffset = null,
                        sourceArtifact = "fixture.json",
                        sourceReference = "recipe",
                    ),
                    addressRecipe = ControlRecoveredAddressRecipes.dataLongSelector1,
                ),
            ),
        )
        val resolver = ControlReadOnlyFieldProfileResolver(listOf(candidate))

        val resolution = resolver.resolve("com.example.target", listOf(matchingModule()))

        assertTrue(resolution.state.isReady)
        assertNull(
            resolution.profile
                ?.fields
                ?.get(ControlReadOnlyFieldId.DATA_LONG_SELECTOR_1)
                ?.address,
        )
    }

    @Test
    fun offsetOutsideMappedModuleIsRejected() {
        val resolver = ControlReadOnlyFieldProfileResolver(listOf(completeCandidate()))

        val resolution = resolver.resolve(
            packageName = "com.example.target",
            modules = listOf(matchingModule().copy(mappedBytes = 0x28L)),
        )

        assertEquals(ControlReadOnlyFieldProfileStatus.OFFSET_OUT_OF_RANGE, resolution.state.status)
        assertNull(resolution.profile)
    }

    @Test
    fun fileFingerprintUsesFullSha256() {
        val file = File.createTempFile("minix-module-", ".so")
        try {
            file.writeText("abc")

            assertEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                sha256File(file),
            )
        } finally {
            assertTrue(file.delete() || !file.exists())
        }
    }

    @Test
    fun floatBackedInt32FieldTruncatesTowardZero() {
        val definition = ControlInt32FieldDefinition(
            id = ControlReadOnlyFieldId.LIFE_STATE,
            offsetEvidence = ControlModuleOffsetEvidence(0x20L, "fixture.json", "life"),
            sourceEncoding = ControlInt32SourceEncoding.FLOAT32_TRUNCATE_TOWARD_ZERO,
        )

        assertEquals(3, definition.decode(3.9f.toRawBits().toLong()))
        assertEquals(-3, definition.decode((-3.9f).toRawBits().toLong()))
    }

    private fun completeCandidate(): ControlReadOnlyFieldProfileCandidate =
        ControlReadOnlyFieldProfileCandidate(
            profileId = "fixture-v1",
            schemaVersion = 1,
            targetVersion = "1.0",
            packageNames = setOf("com.example.target"),
            moduleEvidence = ControlModuleIdentityEvidence(
                moduleName = "libfixture.so",
                expectedSha256 = "a".repeat(64),
                sourceArtifact = "fixture.json",
                sourceReference = "module",
            ),
            fields = listOf(
                ControlInt32FieldDefinition(
                    id = ControlReadOnlyFieldId.LIFE_STATE,
                    offsetEvidence = ControlModuleOffsetEvidence(0x20L, "fixture.json", "life"),
                ),
                ControlInt32FieldDefinition(
                    id = ControlReadOnlyFieldId.KILL_COUNT,
                    offsetEvidence = ControlModuleOffsetEvidence(0x24L, "fixture.json", "kills"),
                ),
                ControlInt64FieldDefinition(
                    id = ControlReadOnlyFieldId.DATA_LONG_SELECTOR_1,
                    offsetEvidence = ControlModuleOffsetEvidence(0x30L, "fixture.json", "long"),
                ),
            ),
        )

    private fun matchingModule(): ControlNativeModuleIdentity = ControlNativeModuleIdentity(
        name = "libfixture.so",
        path = "/data/app/libfixture.so",
        loadBase = 0x71000000L,
        mappedBytes = 0x1000L,
        memoryElf = true,
        sha256 = "a".repeat(64),
    )
}
