package app.inkask.tablet

import kotlin.math.roundToInt

internal data class PdfScrollPadding(val leading: Int, val trailing: Int)
internal data class PdfZoomPosition(val offset: Int, val padding: PdfScrollPadding)

internal fun pdfZoomPosition(
    origin: Float, viewport: Int, normalPadding: PdfScrollPadding,
    distanceToStart: Float?, distanceToEnd: Float?,
): PdfZoomPosition {
    // Lazy lists clamp scroll at document edges. Give the page room to stay under
    // the fingers when the requested origin would otherwise hit that clamp.
    val position = origin.roundToInt()
    val leading = (distanceToStart?.let { (origin - it).roundToInt() } ?: normalPadding.leading)
        .coerceIn(normalPadding.leading, maxOf(normalPadding.leading, viewport - normalPadding.trailing))
    val trailing = (distanceToEnd?.let { (viewport - origin - it).roundToInt() } ?: normalPadding.trailing)
        .coerceIn(normalPadding.trailing, maxOf(normalPadding.trailing, viewport - leading))
    return PdfZoomPosition(leading - position, PdfScrollPadding(leading, trailing))
}
