package me.dartcv.minix.core.serialization

import me.dartcv.minix.core.model.AccentOption
import me.dartcv.minix.core.model.Preset
import me.dartcv.minix.core.model.ResponseMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PresetCodecTest {
    @Test
    fun encodeAndDecodeRoundTripPreservesThePreset() {
        val preset = samplePreset()

        val encoded = PresetCodec.encode(preset).successValue()
        val decoded = PresetCodec.decode(encoded).successValue()

        assertTrue(encoded.contains("\"version\":1"))
        assertEquals(preset, decoded)
    }

    @Test
    fun unsupportedVersionReturnsAnErrorWithoutAPreset() {
        val result = PresetCodec.decode("""{"version":2,"preset":{}}""")

        val error = result.failureError()
        assertEquals(CodecErrorCode.UNSUPPORTED_VERSION, error.code)
        assertEquals("$.version", error.path)
    }

    @Test
    fun malformedJsonReturnsAParseError() {
        val error = PresetCodec.decode("""{"version":1""").failureError()

        assertEquals(CodecErrorCode.MALFORMED_JSON, error.code)
        assertEquals("$", error.path)
    }

    @Test
    fun missingRequiredFieldReturnsItsStructuredPath() {
        val json = """
            {
              "version": 1,
              "preset": {
                "id": "balanced",
                "name": "平衡",
                "description": "标准配置",
                "appearance": {
                  "gridEnabled": true,
                  "panelOpacity": 0.88,
                  "panelScale": 1.0,
                  "accentOption": "INK"
                },
                "control": {"sensitivity": 0.72},
                "metadata": {"isBuiltIn": false}
              }
            }
        """.trimIndent()

        val error = PresetCodec.decode(json).failureError()

        assertEquals(CodecErrorCode.MISSING_FIELD, error.code)
        assertEquals("$.preset.control.responseMode", error.path)
    }

    @Test
    fun outOfRangeValueReturnsAnErrorWithoutAPreset() {
        val json = """
            {
              "version": 1,
              "preset": {
                "id": "balanced",
                "name": "平衡",
                "description": "标准配置",
                "appearance": {
                  "gridEnabled": true,
                  "panelOpacity": 0.2,
                  "panelScale": 1.0,
                  "accentOption": "INK"
                },
                "control": {"sensitivity": 0.72, "responseMode": "BALANCED"},
                "metadata": {"isBuiltIn": false}
              }
            }
        """.trimIndent()

        val error = PresetCodec.decode(json).failureError()

        assertEquals(CodecErrorCode.OUT_OF_RANGE, error.code)
        assertEquals("$.preset.appearance.panelOpacity", error.path)
    }

    @Test
    fun unknownEnumValueReturnsItsStructuredPath() {
        val json = """
            {
              "version": 1,
              "preset": {
                "id": "balanced",
                "name": "平衡",
                "description": "标准配置",
                "appearance": {
                  "gridEnabled": true,
                  "panelOpacity": 0.88,
                  "panelScale": 1.0,
                  "accentOption": "INK"
                },
                "control": {"sensitivity": 0.72, "responseMode": "TURBO"},
                "metadata": {"isBuiltIn": false}
              }
            }
        """.trimIndent()

        val error = PresetCodec.decode(json).failureError()

        assertEquals(CodecErrorCode.INVALID_VALUE, error.code)
        assertEquals("$.preset.control.responseMode", error.path)
    }

    private fun samplePreset() = Preset(
        id = "balanced",
        name = "平衡",
        description = "标准配置",
        gridEnabled = true,
        panelOpacity = 0.88f,
        panelScale = 1f,
        sensitivity = 0.72f,
        responseMode = ResponseMode.BALANCED,
        accentOption = AccentOption.INK,
        isBuiltIn = false,
    )
}
