package me.dartcv.minix.core.serialization

import me.dartcv.minix.core.model.AccentOption
import me.dartcv.minix.core.model.MapPoint
import me.dartcv.minix.core.model.Preset
import me.dartcv.minix.core.model.ResponseMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LocalCollectionsCodecTest {
    @Test
    fun presetListRoundTripPreservesCustomPresets() {
        val presets = listOf(samplePreset("custom_a", "自定义 A"))

        assertEquals(presets, PresetListCodec.decode(PresetListCodec.encode(presets)))
    }

    @Test
    fun mapPointListRoundTripPreservesCoordinates() {
        val points = listOf(MapPoint("point_a", "A 点", "自定义", 0.25f, 0.75f))

        assertEquals(points, MapPointListCodec.decode(MapPointListCodec.encode(points)))
    }

    @Test
    fun duplicateMapPointIdsAreRejectedBeforePersistence() {
        val points = listOf(
            MapPoint("same", "A 点", "自定义", 0.25f, 0.75f),
            MapPoint("same", "B 点", "自定义", 0.5f, 0.5f),
        )

        assertThrows(IllegalArgumentException::class.java) {
            MapPointListCodec.encode(points)
        }
    }

    private fun samplePreset(id: String, name: String) = Preset(
        id = id,
        name = name,
        description = "本地预设",
        gridEnabled = true,
        panelOpacity = 0.88f,
        panelScale = 1f,
        sensitivity = 0.72f,
        responseMode = ResponseMode.BALANCED,
        accentOption = AccentOption.INK,
    )
}
