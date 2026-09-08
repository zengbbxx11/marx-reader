package org.marxreader.app.ui

/** Nearby superscript taps belong to the note, before considering page-turn zones. */
internal fun readerHitDistance(
    x: Float, y: Float, left: Float, top: Float, right: Float, bottom: Float, tolerance: Float
): Float? {
    if (x < left - tolerance || x > right + tolerance ||
        y < top - tolerance || y > bottom + tolerance) return null
    val dx = x - x.coerceIn(left, right)
    val dy = y - y.coerceIn(top, bottom)
    return dx * dx + dy * dy
}
