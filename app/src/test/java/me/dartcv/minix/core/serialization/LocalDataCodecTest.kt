package me.dartcv.minix.core.serialization

import me.dartcv.minix.core.model.AccentOption
import me.dartcv.minix.core.model.AppSettings
import me.dartcv.minix.core.model.LocalDataSnapshot
import me.dartcv.minix.core.model.MapPoint
import me.dartcv.minix.core.model.Preset
import me.dartcv.minix.core.model.ResponseMode
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalDataCodecTest {
    @Test
    fun encodeAndDecodeRoundTripPreservesAllLocalData() {
        val data = LocalDataSnapshot(
            settings = AppSettings(selectedPreset = "平衡"),
            presets = listOf(samplePreset("balanced", "平衡"), samplePreset("compact", "紧凑", scale = 0.85f)),
            mapPoints = listOf(MapPoint("center", "中心", "常用", 0.5f, 0.5f)),
        )

        val encoded = LocalDataCodec.encode(data).successValue()
        val decoded = LocalDataCodec.decode(encoded).successValue()

        assertEquals(data, decoded)
    }

    @Test
    fun invalidPresetInsideLocalDataDoesNotReturnPartialData() {
        val json = """
            {
              "version": 1,
              "data": {
                "settings": {
                  "interaction": {
                    "gridEnabled": true,
                    "hapticsEnabled": true,
                    "sensitivity": 0.72,
                    "responseMode": "BALANCED",
                    "alignmentEnabled": true
                  },
                  "overlay": {"panelOpacity": 0.88, "panelScale": 1.0, "x": 24, "y": 180, "expanded": false},
                  "selection": {"selectedPreset": "平衡"},
                  "theme": {"mode": "SYSTEM", "accentOption": "INK", "backgroundUri": null, "audioUri": null}
                },
                "presets": [
                  {
                    "id": "balanced",
                    "name": "平衡",
                    "description": "Valid",
                    "appearance": {"gridEnabled": true, "panelOpacity": 0.88, "panelScale": 1.0, "accentOption": "INK"},
                    "control": {"sensitivity": 0.72, "responseMode": "BALANCED"},
                    "metadata": {"isBuiltIn": false}
                  },
                  {
                    "id": "invalid",
                    "name": "Invalid",
                    "description": "Invalid",
                    "appearance": {"gridEnabled": true, "panelOpacity": 0.88, "panelScale": 3.0, "accentOption": "INK"},
                    "control": {"sensitivity": 0.72, "responseMode": "BALANCED"},
                    "metadata": {"isBuiltIn": false}
                  }
                ],
                "mapPoints": []
              }
            }
        """.trimIndent()

        val error = LocalDataCodec.decode(json).failureError()

        assertEquals(CodecErrorCode.OUT_OF_RANGE, error.code)
        assertEquals("$.data.presets[1].appearance.panelScale", error.path)
    }

    @Test
    fun duplicatePresetIdsAreRejected() {
        val data = LocalDataSnapshot(
            settings = AppSettings(selectedPreset = "平衡"),
            presets = listOf(samplePreset("same", "平衡"), samplePreset("same", "紧凑")),
            mapPoints = emptyList(),
        )

        val error = LocalDataCodec.encode(data).failureError()

        assertEquals(CodecErrorCode.DUPLICATE_VALUE, error.code)
        assertEquals("$.data.presets[1].id", error.path)
    }

    @Test
    fun selectedPresetMustReferenceAnImportedPreset() {
        val data = LocalDataSnapshot(
            settings = AppSettings(selectedPreset = "不存在"),
            presets = listOf(samplePreset("balanced", "平衡")),
            mapPoints = emptyList(),
        )

        val error = LocalDataCodec.encode(data).failureError()

        assertEquals(CodecErrorCode.REFERENCE_NOT_FOUND, error.code)
        assertEquals("$.data.settings.selection.selectedPreset", error.path)
    }

    private fun samplePreset(id: String, name: String, scale: Float = 1f) = Preset(
        id = id,
        name = name,
        description = name,
        gridEnabled = true,
        panelOpacity = 0.88f,
        panelScale = scale,
        sensitivity = 0.72f,
        responseMode = ResponseMode.BALANCED,
        accentOption = AccentOption.INK,
        isBuiltIn = false,
    )
}
