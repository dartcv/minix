package me.dartcv.minix.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayGeometryTest {
    @Test
    fun constrainCapsOversizedPanelAndHonorsInsetOrigin() {
        val result = constrainOverlayLayout(
            layout = OverlayLayout(x = -50, y = 800, width = 500, height = 700),
            bounds = OverlayBounds(left = 12, top = 36, right = 332, bottom = 596),
        )

        assertEquals(
            OverlayLayout(x = 12, y = 36, width = 320, height = 560),
            result,
        )
    }

    @Test
    fun constrainKeepsPanelInsideRightAndBottomEdges() {
        val result = constrainOverlayLayout(
            layout = OverlayLayout(x = 290, y = 480, width = 80, height = 100),
            bounds = OverlayBounds(left = 0, top = 24, right = 360, bottom = 560),
        )

        assertEquals(
            OverlayLayout(x = 280, y = 460, width = 80, height = 100),
            result,
        )
    }

    @Test
    fun snapMovesNearbyEdgesWithoutMovingCenteredAxis() {
        val result = snapOverlayLayout(
            layout = OverlayLayout(x = 205, y = 180, width = 80, height = 100),
            bounds = OverlayBounds(left = 0, top = 24, right = 300, bottom = 500),
            snapDistance = 20,
        )

        assertEquals(
            OverlayLayout(x = 220, y = 180, width = 80, height = 100),
            result,
        )
    }
}
