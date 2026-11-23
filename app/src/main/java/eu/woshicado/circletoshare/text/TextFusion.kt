package eu.woshicado.circletoshare.text

import java.text.Normalizer

/**
 * The accessibility tree's text, checked against what OCR sees in the
 * picture — the tree has the exact characters and the structure, OCR knows
 * what is actually visible:
 *  1. text the tree has but OCR can't find — the labels of icons, controls
 *     drawn invisible — is left out when it is a short label;
 *  2. a paragraph the crop cuts through is cut at the lines OCR sees, with
 *     the tree's characters;
 *  3. a paragraph with a bullet dot left of it in the pixels is a list item
 *     (browsers don't always say);
 *  4. text OCR is sure of that no node explains (text in images, a code the
 *     page drew some other way) goes in where it sits on screen.
 * Where the tree covers little of what OCR reads — a picture of text, an
 * app that draws its own, a scrollshot — the OCR alone is read, by
 * [LayoutMarkdown].
 */
object TextFusion {

    /**
     * [page] and [gray]: the OCR and the pixels of the selection, in its own
     * pixels; [originX], [originY] where the selection sits on screen and
     * [scaleX], [scaleY] screen pixels per picture pixel, to bring them to
     * the tree's.
     *
     * Where the tree has the text, OCR's boxes do most of the work and only a
     * few lines are read: the first and last visible line of a paragraph the
     * crop cuts, short labels whose visibility is in doubt, and lines no node
     * accounts for.
     */
    fun read(
        screen: ScreenText?, crop: Box, page: OcrPage, gray: Gray, originX: Int, originY: Int,
        scaleX: Float = 1f, scaleY: Float = scaleX,
    ): List<Block> {
        if (screen == null) return LayoutMarkdown.read(page.all(), gray)
        // Nothing OCR can read (white on a colour, a tiny label): the tree's text as it is.
        if (page.boxes.isEmpty()) return TreeMarkdown(crop).read(screen)
        val boxes = page.boxes.map {
            box(originX + it[0] * scaleX, originY + it[1] * scaleY, originX + it[2] * scaleX, originY + it[3] * scaleY)
        }
        if (coverage(screen, boxes) < 0.5) {
            return LayoutMarkdown.read(page.all(), gray).ifEmpty { TreeMarkdown(crop).read(screen) }
        }
        val reader = Fused(crop, boxes, page)
        val items = reader.items(screen)

        // Anchor every block to the OCR box it starts in: that gives its place on
        // screen, and the pixels left of that box say whether it is a list item.
        val placed = ArrayList<Pair<Float, TreeMarkdown.Item>>()
        var y = crop.top.toFloat()
        for (item in items) {
            val i = anchor(item.node, boxes, crop, y)
            val text = anchorText(item)
            // A short label OCR can't find on screen is not visible text: icon
            // labels, hidden controls. On a web page, neither is one where OCR
            // reads something else (a video's frame under its Play button); a
            // native app's text is drawn where it says.
            if (item.isLabel() && text.trim().split(Regex("\\s+")).size <= 3 &&
                (i == null || item.node?.web == true && !reads(page.line(i).text, text))
            ) continue
            var placedItem = item
            if (i != null) {
                y = boxes[i].top.toFloat()
                if (item is TreeMarkdown.Item.Para && !item.indent) {
                    val b = page.boxes[i]
                    if (LayoutMarkdown.bulletLeft(gray, b[0], b[1], b[3], b[3] - b[1])) {
                        placedItem = TreeMarkdown.Item.Li("-", item.text, false, item.node)
                    }
                }
            }
            placed += y to placedItem
        }
        // What only OCR saw, where it is sure of it, in its place on screen — except
        // in pictures: in a selection that is mostly the page's text, the text of
        // a photo or a video frame is not the page's. (A selection that is mostly
        // a picture went to OCR as a whole above.)
        val pictures = ArrayList<Box>()
        fun findPictures(n: TextNode) {
            if (n.isImage && !n.bounds.empty) pictures += n.bounds
            n.children.forEach { findPictures(it) }
        }
        screen.windows.forEach { findPictures(it) }
        val unexplained = boxes.indices.filter { i ->
            val b = boxes[i]
            val cx = (b.left + b.right) / 2
            val cy = (b.top + b.bottom) / 2
            i !in reader.used && pictures.none { cx in it.left..it.right && cy in it.top..it.bottom }
        }
        val extra = page.lines(unexplained).filter { it.score >= 0.9f && it.text.isNotBlank() }
        for (group in clusters(extra)) {
            val blocks = LayoutMarkdown.read(group, gray)
            if (blocks.isNotEmpty()) {
                placed += (originY + group.first().top * scaleY - 0.5f) to TreeMarkdown.Item.Ready(blocks)
            }
        }
        return reader.render(placed.sortedBy { it.first }.map { it.second })
    }

    /** Whether OCR's [line] starts like [text]. */
    private fun reads(line: String, text: String): Boolean {
        val head = norm(text).take(16)
        return similarity(head, norm(line).take(head.length)) >= 0.75
    }

    private fun TreeMarkdown.Item.isLabel() =
        this is TreeMarkdown.Item.Para || this is TreeMarkdown.Item.Li ||
            this is TreeMarkdown.Item.Native || this is TreeMarkdown.Item.Heading

    private fun anchorText(item: TreeMarkdown.Item): String = when (item) {
        is TreeMarkdown.Item.Heading -> item.text.plainText()
        is TreeMarkdown.Item.Para -> item.text.plainText()
        is TreeMarkdown.Item.Li -> item.text.plainText()
        is TreeMarkdown.Item.Native -> item.text.plainText()
        is TreeMarkdown.Item.Pre -> item.text
        is TreeMarkdown.Item.Table -> item.rows.first().joinToString(" ")
        is TreeMarkdown.Item.Ready -> ""
    }

    /** The OCR box a block's node starts in: the first (leftmost of the top
     *  row of) boxes overlapping the node's visible part, at or below [after]
     *  where there is one — a switch's knob, a tad higher, is not its label. */
    private fun anchor(node: TextNode?, boxes: List<Box>, crop: Box, after: Float): Int? {
        val b = node?.bounds ?: return null
        if (b.empty) return null
        val region = b.intersect(crop)
        if (region.empty) return null
        val hits = boxes.indices.filter { boxes[it].overlap(region) > 0.5 * minOf(boxes[it].area, region.area) }
        val pool = hits.filter { boxes[it].top >= after - 10 }.ifEmpty { hits }
        val top = pool.minByOrNull { boxes[it].top } ?: return null
        val row = boxes[top].top + boxes[top].height / 2
        return pool.filter { boxes[it].top < row }.minBy { boxes[it].left }
    }

    private fun box(l: Float, t: Float, r: Float, b: Float) = Box(l.toInt(), t.toInt(), r.toInt(), b.toInt())

    /** The tree's reader, with OCR to tell what is visible. */
    private class Fused(crop: Box, val boxes: List<Box>, val page: OcrPage) : TreeMarkdown(crop) {
        /** OCR boxes the tree's text accounts for (by index). */
        val used = HashSet<Int>()

        private fun hits(b: Box) = boxes.indices.filter { b.overlap(boxes[it]) > 0.3 * minOf(b.area, boxes[it].area) }

        override fun leafText(node: TextNode): String? {
            if (node.isImage) return null
            val b = node.bounds
            if (b.empty) return null // clipped away by its window (behind a system bar, scrolled off)
            used += hits(b)
            return if (b.inside(crop)) node.text else null
        }

        override fun paragraphOverride(node: TextNode): List<Run>? {
            val b = node.bounds
            if (b.empty) return null
            val overlap = b.overlap(crop).toDouble() / b.area
            if (overlap <= 0.05 || overlap >= 0.95 || b.height < 80) return null
            val visible = b.intersect(crop)
            val idx = boxes.indices.filter { boxes[it].overlap(visible) > 0.5 * boxes[it].area }
                .sortedWith(compareBy({ boxes[it].top }, { boxes[it].left }))
            used += idx
            if (idx.isEmpty()) return emptyList()
            // OCR says which lines are visible; the characters still come from the tree.
            val full = fullText(node)
            val plain = full.plainText()
            val start = locate(plain, text(idx.first()), atStart = true)
            val end = locate(plain, text(idx.last()), atStart = false)
            if (start == null || end == null || end <= start) {
                return listOf(Run(page.lines(idx).joinToString(" ") { norm(it) }))
            }
            return slice(full, start, end)
        }

        private fun text(i: Int) = norm(page.line(i))

        private fun norm(l: OcrLine) = Normalizer.normalize(l.text, Normalizer.Form.NFKC)

        /** The node's whole text, as if the crop held all of it. */
        private fun fullText(node: TextNode): List<Run> =
            TreeMarkdown(Box(Int.MIN_VALUE / 2, Int.MIN_VALUE / 2, Int.MAX_VALUE / 2, Int.MAX_VALUE / 2)).inline(node)
    }

    /** Characters [start] until [end] of [runs]. */
    private fun slice(runs: List<Run>, start: Int, end: Int): List<Run> {
        val out = ArrayList<Run>()
        var pos = 0
        for (run in runs) {
            val a = maxOf(start, pos)
            val b = minOf(end, pos + run.text.length)
            if (a < b) out += run.copy(text = run.text.substring(a - pos, b - pos))
            pos += run.text.length
        }
        return out
    }

    /** Where an OCR'd [line] starts (or ends) inside the exact [full] text,
     *  snapped to a word edge; null where it can't be found. */
    fun locate(full: String, line: String, atStart: Boolean): Int? {
        val (aEnd, bEnd, size) = longestCommon(full.lowercase(), line.lowercase())
        if (size < minOf(8, line.length / 2)) return null
        val a = aEnd - size
        val b = bEnd - size
        return if (atStart) {
            var pos = maxOf(0, a - b)
            while (pos > 0 && !full[pos - 1].isWhitespace()) pos--
            pos
        } else {
            var pos = minOf(full.length, a + (line.length - b))
            while (pos < full.length && !full[pos].isWhitespace()) pos++
            pos
        }
    }

    /** The longest common substring: where it ends in [a] and [b], and its length. */
    private fun longestCommon(a: String, b: String): Triple<Int, Int, Int> {
        var best = Triple(0, 0, 0)
        var prev = IntArray(b.length + 1)
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            for (j in 1..b.length) {
                if (a[i - 1] == b[j - 1]) {
                    cur[j] = prev[j - 1] + 1
                    if (cur[j] > best.third) best = Triple(i, j, cur[j])
                }
            }
            prev = cur
        }
        return best
    }

    private fun norm(s: String) = s.lowercase().filter { it in 'a'..'z' || it in '0'..'9' }

    /** How alike two short strings are, 0–1 (twice the common subsequence
     *  over both lengths). */
    private fun similarity(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 1..a.length) for (j in 1..b.length) {
            dp[i][j] = if (a[i - 1] == b[j - 1]) dp[i - 1][j - 1] + 1 else maxOf(dp[i - 1][j], dp[i][j - 1])
        }
        return 2.0 * dp[a.length][b.length] / (a.length + b.length)
    }

    private fun clusters(lines: List<OcrLine>): List<List<OcrLine>> {
        val out = ArrayList<MutableList<OcrLine>>()
        for (l in lines.sortedBy { it.top }) {
            val last = out.lastOrNull()?.last()
            if (last != null && l.top - last.bottom < 1.5f * (l.bottom - l.top)) out.last() += l
            else out += mutableListOf(l)
        }
        return out
    }

    /** Share of what OCR sees (by width, about its characters) that falls inside
     *  some leaf of the tree with text. */
    private fun coverage(screen: ScreenText, ocr: List<Box>): Double {
        val boxes = ArrayList<Box>()
        fun textBelow(n: TextNode): Boolean = n.children.any { it.hasText || textBelow(it) }
        fun walk(n: TextNode) {
            // Leaves only: a web view's root carries the page title over the whole screen.
            if (n.hasText && !n.bounds.empty && !textBelow(n) && n.cls != "WebView" && !n.isImage) boxes += n.bounds
            n.children.forEach { walk(it) }
        }
        screen.windows.forEach { walk(it) }
        var total = 0L
        var covered = 0L
        for (b in ocr) {
            total += b.width
            // (A line is often several nodes side by side: links, bold words.)
            if (boxes.sumOf { it.overlap(b) } > 0.5 * b.area) covered += b.width
        }
        return covered.toDouble() / maxOf(1, total)
    }
}
