package eu.woshicado.circletoshare.text

/** A rectangle in screen pixels. Empty (zero or negative size) for a node
 *  its window clips away entirely, which accessibility reports that way. */
data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
    val empty get() = width <= 0 || height <= 0
    val area: Long get() = if (empty) 0 else width.toLong() * height

    fun intersect(o: Box): Box =
        Box(maxOf(left, o.left), maxOf(top, o.top), minOf(right, o.right), minOf(bottom, o.bottom))

    fun overlap(o: Box): Long = intersect(o).area

    fun touches(o: Box) = right > o.left && left < o.right && bottom > o.top && top < o.bottom

    /** At least [share] of this box lies inside [o]. */
    fun inside(o: Box, share: Double = 0.5): Boolean =
        if (empty) o.left <= left && left <= o.right && o.top <= top && top <= o.bottom
        else overlap(o) >= share * area
}

/**
 * The text on screen as an accessibility service saw it at the moment of a
 * capture: a plain copy of the window trees, nothing Android-specific, so
 * that it outlives the nodes and can be turned into [Block]s anywhere —
 * and tested without a device. Held in memory while the crop tool is open,
 * never stored.
 */
class ScreenText(val width: Int, val height: Int, val windows: List<TextNode>)

/**
 * One node. [role] is a browser's own name for what the element is
 * (Chrome's "chromeRole", Firefox's "geckoRole": "paragraph", "code",
 * "listItem", …); [web] says whether it came from a browser at all.
 * [roleDescription] is what a screen reader would announce ("heading level
 * 2"). [item] is the row, column, row span and column span of a list or
 * table item. [styles] are the bold/italic ranges of a native text.
 */
class TextNode(
    val cls: String,
    val bounds: Box,
    val text: String? = null,
    val heading: Boolean = false,
    val web: Boolean = false,
    val role: String = "",
    val roleDescription: String = "",
    val item: IntArray? = null,
    val styles: List<StyleRange> = emptyList(),
    val textPx: Float = 0f,
    val children: List<TextNode> = emptyList(),
) {
    val hasText get() = !text.isNullOrBlank()

    /** Whether any node under it has text. */
    val textBelow: Boolean get() = children.any { it.hasText || it.textBelow }

    val isImage get() = role == "image" || role == "graphic" || role == "img" ||
        cls == "Image" || cls == "ImageView"
}

/** [style]: 1 bold, 2 italic, 3 both, as android.graphics.Typeface. */
data class StyleRange(val start: Int, val end: Int, val style: Int)
