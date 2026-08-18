package me.dartcv.minix.root

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RootReadOnlyFieldProfilesTest {
    @Test
    fun productionProfileUsesExactArtifactFingerprintAndTypedFieldRecipes() {
        val candidate = RootReadOnlyFieldProfileCatalog.candidates.single()

        assertEquals(2, candidate.schemaVersion)
        assertEquals("1.58.2", candidate.targetVersion)
        assertTrue(candidate.isEvidenceComplete)
        assertEquals(RootGameAppArtifact1582.MODULE_NAME, candidate.moduleEvidence.moduleName)
        assertEquals(RootGameAppArtifact1582.SHA256, candidate.moduleEvidence.expectedSha256)
        assertEquals(setOf("liblibGameApp.so"), RootReadOnlyFieldProfileCatalog.fingerprintModuleNames)
        assertEquals(
            RootRecoveredAddressRecipes.lifeState,
            candidate.fields.single {
                it.id == RootReadOnlyFieldId.LIFE_STATE
            }.addressRecipe,
        )
        assertEquals(
            RootInt32SourceEncoding.FLOAT32_TRUNCATE_TOWARD_ZERO,
            (candidate.fields.single {
                it.id == RootReadOnlyFieldId.LIFE_STATE
            } as RootInt32FieldDefinition).sourceEncoding,
        )
        assertEquals(
            RootRecoveredAddressRecipes.dataLongSelector1,
            candidate.fields.single {
                it.id == RootReadOnlyFieldId.DATA_LONG_SELECTOR_1
            }.addressRecipe,
        )
        assertEquals(
            RootModuleSpecFieldAddressRecipe(
                anchorProfile = RootRecoveredSearchIdProfiles.miniWorld1582,
                finalOffset = RootGameAppArtifact1582.KILL_COUNT_ANCHOR_OFFSET,
                sourceArtifact = RootGameAppArtifact1582.SOURCE_ARTIFACT,
                sourceReference =
                    "Verified anonymous-BSS anchor plus recovered GetData selector 3 offset",
            ),
            candidate.fields.single {
                it.id == RootReadOnlyFieldId.KILL_COUNT
            }.moduleSpecAddressRecipe,
        )
        assertEquals(
            listOf(
                RootReadOnlyFieldId.LIFE_STATE to Int.SIZE_BYTES,
                RootReadOnlyFieldId.KILL_COUNT to Int.SIZE_BYTES,
                RootReadOnlyFieldId.DATA_LONG_SELECTOR_1 to Long.SIZE_BYTES,
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
        val candidate = RootReadOnlyFieldProfileCatalog.candidates.single()
        val module = RootNativeModuleIdentity(
            name = RootGameAppArtifact1582.MODULE_NAME,
            path = "/data/app/${RootGameAppArtifact1582.MODULE_NAME}",
            loadBase = 0x7100_0000L,
            mappedBytes = 0x1000L,
            memoryElf = true,
            sha256 = RootGameAppArtifact1582.SHA256,
        )

        val resolution = RootReadOnlyFieldProfileCatalog.resolver.resolve(
            RootTargetCatalog.default.packageName,
            listOf(module),
        )

        assertTrue(resolution.state.isReady)
        assertNull(resolution.profile?.fields?.get(RootReadOnlyFieldId.KILL_COUNT)?.address)
    }

    @Test
    fun completeEvidenceResolvesExactModuleAndModuleRelativeAddresses() {
        val resolver = RootReadOnlyFieldProfileResolver(listOf(completeCandidate()))

        val resolution = resolver.resolve(
            packageName = "com.example.target",
            modules = listOf(matchingModule()),
        )

        assertTrue(resolution.state.isReady)
        assertEquals("fixture-v1", resolution.state.profileId)
        assertEquals(
            0x71000020L,
            resolution.profile?.fields?.get(RootReadOnlyFieldId.LIFE_STATE)?.address,
        )
        assertEquals(
            0x71000030L,
            resolution.profile?.fields?.get(RootReadOnlyFieldId.DATA_LONG_SELECTOR_1)?.address,
        )
    }

    @Test
    fun fingerprintMismatchDoesNotResolveFields() {
        val resolver = RootReadOnlyFieldProfileResolver(listOf(completeCandidate()))

        val resolution = resolver.resolve(
            packageName = "com.example.target",
            modules = listOf(matchingModule().copy(sha256 = "b".repeat(64))),
        )

        assertEquals(RootReadOnlyFieldProfileStatus.FINGERPRINT_MISMATCH, resolution.state.status)
        assertNull(resolution.profile)
        assertTrue(resolution.state.fieldIds.isEmpty())
    }

    @Test
    fun duplicateNamedModulesFailClosedEvenWhenOnlyOneFingerprintMatches() {
        val resolver = RootReadOnlyFieldProfileResolver(listOf(completeCandidate()))

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

        assertEquals(RootReadOnlyFieldProfileStatus.MODULE_AMBIGUOUS, resolution.state.status)
        assertNull(resolution.profile)
    }

    @Test
    fun duplicateExactFingerprintMatchesFailClosedAsAmbiguous() {
        val resolver = RootReadOnlyFieldProfileResolver(listOf(completeCandidate()))

        val resolution = resolver.resolve(
            packageName = "com.example.target",
            modules = listOf(
                matchingModule(),
                matchingModule().copy(path = "/other/libfixture.so"),
            ),
        )

        assertEquals(RootReadOnlyFieldProfileStatus.MODULE_AMBIGUOUS, resolution.state.status)
        assertNull(resolution.profile)
    }

    @Test
    fun missingOffsetEvidenceStopsProfileBeforeModuleMatching() {
        val candidate = completeCandidate().copy(
            fields = completeCandidate().fields.map { definition ->
                if (definition.id == RootReadOnlyFieldId.KILL_COUNT) {
                    RootInt32FieldDefinition(
                        id = RootReadOnlyFieldId.KILL_COUNT,
                        offsetEvidence = RootModuleOffsetEvidence(
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
        val resolver = RootReadOnlyFieldProfileResolver(listOf(candidate))

        val resolution = resolver.resolve("com.example.target", listOf(matchingModule()))

        assertEquals(RootReadOnlyFieldProfileStatus.INCOMPLETE_EVIDENCE, resolution.state.status)
        assertNull(resolution.profile)
    }

    @Test
    fun structurallyValidResolverRecipeCanReplaceADirectModuleOffset() {
        val candidate = completeCandidate().copy(
            fields = listOf(
                RootInt64FieldDefinition(
                    id = RootReadOnlyFieldId.DATA_LONG_SELECTOR_1,
                    offsetEvidence = RootModuleOffsetEvidence(
                        moduleOffset = null,
                        sourceArtifact = "fixture.json",
                        sourceReference = "recipe",
                    ),
                    addressRecipe = RootRecoveredAddressRecipes.dataLongSelector1,
                ),
            ),
        )
        val resolver = RootReadOnlyFieldProfileResolver(listOf(candidate))

        val resolution = resolver.resolve("com.example.target", listOf(matchingModule()))

        assertTrue(resolution.state.isReady)
        assertNull(
            resolution.profile
                ?.fields
                ?.get(RootReadOnlyFieldId.DATA_LONG_SELECTOR_1)
                ?.address,
        )
    }

    @Test
    fun offsetOutsideMappedModuleIsRejected() {
        val resolver = RootReadOnlyFieldProfileResolver(listOf(completeCandidate()))

        val resolution = resolver.resolve(
            packageName = "com.example.target",
            modules = listOf(matchingModule().copy(mappedBytes = 0x28L)),
        )

        assertEquals(RootReadOnlyFieldProfileStatus.OFFSET_OUT_OF_RANGE, resolution.state.status)
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
        val definition = RootInt32FieldDefinition(
            id = RootReadOnlyFieldId.LIFE_STATE,
            offsetEvidence = RootModuleOffsetEvidence(0x20L, "fixture.json", "life"),
            sourceEncoding = RootInt32SourceEncoding.FLOAT32_TRUNCATE_TOWARD_ZERO,
        )

        assertEquals(3, definition.decode(3.9f.toRawBits().toLong()))
        assertEquals(-3, definition.decode((-3.9f).toRawBits().toLong()))
    }

    private fun completeCandidate(): RootReadOnlyFieldProfileCandidate =
        RootReadOnlyFieldProfileCandidate(
            profileId = "fixture-v1",
            schemaVersion = 1,
            targetVersion = "1.0",
            packageNames = setOf("com.example.target"),
            moduleEvidence = RootModuleIdentityEvidence(
                moduleName = "libfixture.so",
                expectedSha256 = "a".repeat(64),
                sourceArtifact = "fixture.json",
                sourceReference = "module",
            ),
            fields = listOf(
                RootInt32FieldDefinition(
                    id = RootReadOnlyFieldId.LIFE_STATE,
                    offsetEvidence = RootModuleOffsetEvidence(0x20L, "fixture.json", "life"),
                ),
                RootInt32FieldDefinition(
                    id = RootReadOnlyFieldId.KILL_COUNT,
                    offsetEvidence = RootModuleOffsetEvidence(0x24L, "fixture.json", "kills"),
                ),
                RootInt64FieldDefinition(
                    id = RootReadOnlyFieldId.DATA_LONG_SELECTOR_1,
                    offsetEvidence = RootModuleOffsetEvidence(0x30L, "fixture.json", "long"),
                ),
            ),
        )

    private fun matchingModule(): RootNativeModuleIdentity = RootNativeModuleIdentity(
        name = "libfixture.so",
        path = "/data/app/libfixture.so",
        loadBase = 0x71000000L,
        mappedBytes = 0x1000L,
        memoryElf = true,
        sha256 = "a".repeat(64),
    )
}
