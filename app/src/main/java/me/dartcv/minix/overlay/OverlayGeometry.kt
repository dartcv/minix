package me.dartcv.minix.overlay

internal data class OverlayBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

internal data class OverlayLayout(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)

internal fun constrainOverlayLayout(
    layout: OverlayLayout,
    bounds: OverlayBounds,
): OverlayLayout {
    val availableWidth = (bounds.right - bounds.left).coerceAtLeast(1)
    val availableHeight = (bounds.bottom - bounds.top).coerceAtLeast(1)
    val width = layout.width.coerceIn(1, availableWidth)
    val height = layout.height.coerceIn(1, availableHeight)
    return OverlayLayout(
        x = layout.x.coerceIn(bounds.left, maxOf(bounds.left, bounds.right - width)),
        y = layout.y.coerceIn(bounds.top, maxOf(bounds.top, bounds.bottom - height)),
        width = width,
        height = height,
    )
}

internal fun snapOverlayLayout(
    layout: OverlayLayout,
    bounds: OverlayBounds,
    snapDistance: Int,
): OverlayLayout {
    val constrained = constrainOverlayLayout(layout, bounds)
    val snapped = constrained.copy(
        x = when {
            constrained.x - bounds.left <= snapDistance -> bounds.left
            bounds.right - (constrained.x + constrained.width) <= snapDistance -> {
                bounds.right - constrained.width
            }
            else -> constrained.x
        },
        y = when {
            constrained.y - bounds.top <= snapDistance -> bounds.top
            bounds.bottom - (constrained.y + constrained.height) <= snapDistance -> {
                bounds.bottom - constrained.height
            }
            else -> constrained.y
        },
    )
    return constrainOverlayLayout(snapped, bounds)
}
