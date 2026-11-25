package eu.woshicado.circletoshare.text

import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** A picture in shades of gray, 0–255, row by row. */
class Gray(val width: Int, val height: Int, val px: IntArray) {
    operator fun get(x: Int, y: Int) = px[y * width + x]
}

/** A line of text OCR found, in the pixels of the picture it read. */
data class OcrLine(
    val left: Float, val top: Float, val right: Float, val bottom: Float,
    val text: String, val score: Float,
)

/** A block and where it starts on the picture, for putting blocks from
 *  different readers in order. */
class Placed(val top: Float, val block: Block)

/**
 * OCR'd lines of a picture as [Block]s, by layout rules alone — the step
 * that keeps OCR from coming out as scrambled lines:
 *  - lines on one baseline become rows; bordered tables come from the ruling
 *    lines in the pixels, a missing rule between two rows marking a merged
 *    cell;
 *  - rows fall into blocks at gaps, indents and changes of text size (the
 *    width of a character tells the size better than OCR's box height);
 *  - a block is code (boxed and code-like), a heading (bigger text), a list
 *    item (a marker, or a bullet dot found left of it in the pixels), a
 *    quote (boxed) or a paragraph, whose wrapped lines join up only where
 *    the next word would not have fit — short lines stay line breaks — and
 *    whose broken words lose their hyphen.
 */
object LayoutMarkdown {

    fun read(lines: List<OcrLine>, gray: Gray): List<Block> = place(lines, gray).map { it.block }

    fun place(lines: List<OcrLine>, gray: Gray): List<Placed> {
        val usable = lines.filter { it.score >= 0.5f && it.text.isNotBlank() }
            .map { it.copy(text = Normalizer.normalize(it.text, Normalizer.Form.NFKC)) }
        if (usable.isEmpty()) return emptyList()
        var rows = rowsOf(usable)
        val pageBg = mode(gray.px)
        val weighted = ArrayList<Float>()
        for (r in rows) repeat(max(1, r.chars / 5)) { weighted += r.cw }
        val body = median(weighted)

        // Tables first; their rows leave the flow and the table takes their place.
        val items = ArrayList<Placed>()
        gridTable(gray)?.let { t ->
            val top = t.ys.first()
            val inside = if (t.ys.last() > gray.height - 300) {
                // rows below the last rule still belong to a table that runs off the crop
                t.ys += gray.height
                rows.filter { it.mid > top }
            } else {
                val bottom = t.ys.last()
                rows.filter { it.mid > top && it.mid < bottom }
            }
            tableRows(t, gray, inside)?.let { grid ->
                items += Placed(top.toFloat(), Block.Table(grid))
                rows = rows.filter { it !in inside }
            }
        }

        for (r in rows) {
            r.bg = rowBackground(gray, r)
            r.boxed = abs(r.bg - pageBg) >= 4
            r.bullet = BULLET.containsMatchIn(r.segs[0].text) || bulletLeft(gray, r.x0, r.top, r.bottom, r.h)
        }

        val gaps = (0 until rows.size - 1)
            .filter { abs(rows[it + 1].cw - rows[it].cw) < 0.12f * rows[it].cw }
            .map { rows[it + 1].top - rows[it].bottom }
        val lineGap = if (gaps.isEmpty()) body else median(gaps)

        val blocks = ArrayList<List<Row>>()
        var block = ArrayList<Row>()
        for (r in rows) {
            val p = block.lastOrNull()
            if (p != null) {
                val gap = r.top - p.bottom
                val new = gap > max(1.8f * lineGap, lineGap + 0.5f * r.h) ||
                    (max(r.cw, p.cw) / min(r.cw, p.cw) > 1.22f && !(r.boxed && p.boxed) &&
                        min(r.text().length, p.text().length) >= 6) ||
                    r.boxed != p.boxed ||
                    (r.bullet && !(p.boxed && r.boxed)) ||
                    (abs(r.x0 - p.x0) > r.h && !(block[0].bullet && r.x0 > block[0].x0) && !r.boxed &&
                        abs((r.x0 + r.x1) - (p.x0 + p.x1)) >= 2 * r.h)
                if (new) {
                    blocks += block
                    block = ArrayList()
                }
            }
            block += r
        }
        if (block.isNotEmpty()) blocks += block

        for (b in blocks) {
            val x0 = b.minOf { it.x0 }
            val colRight = rows.filter { abs(it.x0 - x0) < 1.5f * b[0].h || it.x0 < x0 }.maxOf { it.x1 }
            val size = median(b.map { it.cw })
            val texts = b.map { it.text(code = true) }
            val codey = texts.count { CODEY.containsMatchIn(it) || CODE_CHARS.containsMatchIn(it) }
            val cws = b.filter { it.text().length >= 6 }.map { it.cw }
            val mono = cws.size >= 2 && stdev(cws) / cws.average() < 0.06
            val result: Block = when {
                b[0].boxed && codey >= 0.4 * b.size -> {
                    val cwm = median(b.map { it.cw })
                    Block.Code(b.indices.joinToString("\n") { i -> " ".repeat(((b[i].x0 - x0) / cwm).roundToInt()) + texts[i] })
                }
                size >= 1.22f * body && b.size <= 3 -> {
                    val level = if (size >= 1.7f * body) 1 else if (size >= 1.4f * body) 2 else 3
                    Block.Heading(level, plain(b.joinToString(" ") { it.text() }))
                }
                b[0].bullet -> {
                    var runs = joinLines(b, colRight)
                    var marker = "-"
                    val m = BULLET.find(runs.plainText())
                    if (m != null) {
                        marker = if (m.groupValues[1][0].isDigit()) m.groupValues[1] else "-"
                        runs = TreeMarkdown.dropChars(runs, m.range.last + 1)
                    }
                    Block.ListItem(marker, runs.tidy())
                }
                b[0].boxed && !mono -> Block.Quote(joinLines(b, colRight).tidy())
                else -> Block.Paragraph(joinLines(b, colRight).tidy())
            }
            items += Placed(b[0].top, result)
        }
        return items.sortedBy { it.top }
    }

    // ------------------------------------------------------------ rows

    private class Row(var top: Float, var bottom: Float) {
        val segs = ArrayList<OcrLine>()
        var x0 = 0f
        var x1 = 0f
        var h = 0f
        var cw = 0f
        var chars = 0
        var bg = 0f
        var boxed = false
        var bullet = false
        val mid get() = (top + bottom) / 2

        fun text(code: Boolean = false): String {
            val sb = StringBuilder(segs[0].text)
            for (i in 1 until segs.size) {
                val gap = segs[i].left - segs[i - 1].right
                sb.append(" ".repeat(if (code) max(1, (gap / cw).roundToInt()) else 1)).append(segs[i].text)
            }
            return sb.toString()
        }
    }

    private fun cw(l: OcrLine) = (l.right - l.left) / max(1, l.text.length)

    private fun rowsOf(lines: List<OcrLine>): List<Row> {
        val rows = ArrayList<Row>()
        for (l in lines.sortedBy { (it.top + it.bottom) / 2 }) {
            val row = rows.firstOrNull { r ->
                min(l.bottom, r.bottom) - max(l.top, r.top) > 0.5f * min(l.bottom - l.top, r.bottom - r.top)
            }
            if (row != null) {
                row.segs += l
                row.top = min(row.top, l.top)
                row.bottom = max(row.bottom, l.bottom)
            } else {
                rows += Row(l.top, l.bottom).also { it.segs += l }
            }
        }
        for (r in rows) {
            r.segs.sortBy { it.left }
            r.x0 = r.segs.first().left
            r.x1 = r.segs.last().right
            r.h = median(r.segs.map { it.bottom - it.top })
            r.chars = r.segs.sumOf { it.text.length }
            r.cw = r.segs.sumOf { (cw(it) * it.text.length).toDouble() }.toFloat() / max(1, r.chars)
        }
        return rows.sortedBy { it.top }
    }

    /** A paragraph's rows as runs: wrapped lines join, short ones break. */
    private fun joinLines(block: List<Row>, colRight: Float): List<Run> {
        val texts = block.map { it.text() }
        val out = StringBuilder(texts[0])
        val runs = ArrayList<Run>()
        for (k in 1 until block.size) {
            val prev = block[k - 1]
            val t = texts[k]
            val firstWord = t.substringBefore(' ')
            if (colRight - prev.x1 < (firstWord.length + 2) * prev.cw) {
                if (out.endsWith("-") && t.firstOrNull()?.isLowerCase() == true) {
                    out.setLength(out.length - 1)
                    out.append(t)
                } else {
                    out.append(' ').append(t)
                }
            } else {
                runs += Run(out.toString())
                runs += Run("\n")
                out.setLength(0)
                out.append(t)
            }
        }
        runs += Run(out.toString())
        return runs
    }

    // ------------------------------------------------------------ pixels

    /** The colour just around a line: the pixel rows above and below its glyphs. */
    private fun rowBackground(g: Gray, r: Row): Float {
        val x0 = max(0, (r.x0 - 6).toInt())
        val x1 = min(g.width, (r.x1 + 6).toInt())
        val ys = listOf(r.top.toInt() - 2, r.bottom.toInt() + 2, r.top.toInt() + 1, r.bottom.toInt() - 1)
            .filter { it in 0 until g.height }
        if (ys.isEmpty() || x1 <= x0) return 0f
        val values = ArrayList<Float>(ys.size * (x1 - x0))
        for (y in ys) for (x in x0 until x1) values += g[x, y].toFloat()
        return median(values)
    }

    /** A small solid dot just left of a line's first character. */
    fun bulletLeft(g: Gray, left: Float, top: Float, bottom: Float, h: Float): Boolean {
        val x1 = (left - 0.1f * h).toInt()
        val x0 = max(0f, left - 1.6f * h).toInt()
        val y0 = max(0, top.toInt())
        val y1 = min(g.height, bottom.toInt())
        if (x1 - x0 < 4 || y1 <= y0 || x1 > g.width) return false
        val values = ArrayList<Float>()
        for (y in y0 until y1) for (x in x0 until x1) values += g[x, y].toFloat()
        val bg = median(values)
        var count = 0
        var minX = Int.MAX_VALUE; var maxX = -1; var minY = Int.MAX_VALUE; var maxY = -1
        for (y in y0 until y1) for (x in x0 until x1) {
            if (abs(g[x, y] - bg) > 40) {
                count++
                minX = min(minX, x); maxX = max(maxX, x); minY = min(minY, y); maxY = max(maxY, y)
            }
        }
        if (count < 4) return false
        val bw = (maxX - minX + 1).toFloat()
        val bh = (maxY - minY + 1).toFloat()
        return bw in 0.1f * h..0.45f * h && bh in 0.1f * h..0.45f * h && bw / bh in 0.6f..1.6f && count > 0.5f * bw * bh
    }

    private class Grid(val ys: MutableList<Int>, val xs: List<Int>, val bg: Float)

    /** Longest run of [on] along a line of [n] cells: (length, start). */
    private inline fun longestRun(n: Int, on: (Int) -> Boolean): Pair<Int, Int> {
        var best = 0; var start = 0; var cur = 0; var curStart = 0
        for (i in 0 until n) {
            if (on(i)) {
                if (cur == 0) curStart = i
                cur++
                if (cur > best) { best = cur; start = curStart }
            } else cur = 0
        }
        return best to start
    }

    /** Middles of the runs of set flags no thicker than a ruling line. */
    private fun thinBands(flags: BooleanArray, maxThick: Int = 5): List<Int> {
        val out = ArrayList<Int>()
        var k = 0
        while (k < flags.size) {
            if (flags[k]) {
                var j = k
                while (j + 1 < flags.size && flags[j + 1]) j++
                if (j - k + 1 <= maxThick) out += (k + j) / 2
                k = j + 1
            } else k++
        }
        return out
    }

    /** A bordered table: long thin horizontal rules, and vertical ones between them. */
    private fun gridTable(g: Gray): Grid? {
        val bg = medianOf(g.px)
        fun line(x: Int, y: Int) = abs(g[x, y] - bg) > 28
        // (Every second pixel along a line is plenty.)
        val half = (g.width + 1) / 2
        val hflags = BooleanArray(g.height) { y ->
            val (n, _) = longestRun(half) { i ->
                val x = 2 * i
                line(x, y) && x > 0 && abs(g[x, y] - g[x - 1, y]) < 10
            }
            2 * n >= 0.4 * g.width
        }
        var hys = thinBands(hflags)
        if (hys.size < 3) return null
        val y0 = hys.first()
        val y1 = hys.last()
        val span = y1 - y0 + 1
        val halfY = (span + 1) / 2
        val vruns = Array(g.width) { x ->
            longestRun(halfY) { i ->
                val y = y0 + 2 * i
                line(x, y) && y > y0 && abs(g[x, y] - g[x, y - 1]) < 10
            }
        }
        val vflags = BooleanArray(g.width) { 2 * vruns[it].first >= 0.3 * (y1 - y0) }
        val vxs = thinBands(vflags)
        if (vxs.size < 2) return null
        // The table spans where its vertical rules run; rules outside (underlines, dividers) are not part of it.
        val vy0 = y0 + vxs.minOf { vruns[it].second }
        val vy1 = y0 + vxs.maxOf { vruns[it].second + 2 * vruns[it].first }
        hys = hys.filter { it in vy0 - 6..vy1 + 6 }
        if (hys.size < 2) return null
        val xs = if (vxs.last() < g.width - 20) vxs + (g.width - 1) else vxs
        return Grid(hys.toMutableList(), xs, bg)
    }

    private fun ruleAt(g: Gray, t: Grid, y: Int, xl: Int, xr: Int): Boolean {
        val x0 = xl + 4
        val x1 = xr - 4
        if (x1 <= x0) return true
        for (yy in max(0, y - 2) until min(g.height, y + 3)) {
            var n = 0
            for (x in x0 until x1) if (abs(g[x, yy] - t.bg) > 28) n++
            if (n > 0.6 * (x1 - x0)) return true
        }
        return false
    }

    private fun tableRows(t: Grid, g: Gray, rows: List<Row>): List<List<String>>? {
        val ys = t.ys
        val xs = t.xs
        val nr = ys.size - 1
        val nc = xs.size - 1
        val cells = HashMap<Pair<Int, Int>, MutableList<OcrLine>>()
        for (r in rows) for (s in r.segs) {
            val cx = (s.left + s.right) / 2
            val cy = (s.top + s.bottom) / 2
            val ri = ys.count { it < cy } - 1
            val ci = xs.count { it < cx } - 1
            if (ri in 0 until nr && ci in 0 until nc) cells.getOrPut(ri to ci) { ArrayList() } += s
        }
        // Merged cells: where the rule between two rows is missing in a column (and the
        // lower cell is empty), they are one cell.
        val owner = HashMap<Pair<Int, Int>, Pair<Int, Int>>()
        for (c in 0 until nc) for (r in 0 until nr) {
            owner[r to c] = if (r > 0 && (r to c) !in cells && !ruleAt(g, t, ys[r], xs[c], xs[c + 1])) {
                owner[r - 1 to c] ?: (r - 1 to c)
            } else r to c
        }
        val text = HashMap<Pair<Int, Int>, MutableList<OcrLine>>()
        for ((key, segs) in cells) text.getOrPut(owner[key]!!) { ArrayList() } += segs
        var grid = (0 until nr).map { r ->
            (0 until nc).map { c ->
                (text[owner[r to c]] ?: emptyList<OcrLine>())
                    .sortedWith(compareBy({ (it.top / 20).roundToInt() }, { it.left }))
                    .joinToString(" ") { it.text }
            }
        }.filter { row -> row.any { it.isNotEmpty() } }
        val keep = (0 until nc).filter { c -> grid.any { it[c].isNotEmpty() } }
        grid = grid.map { row -> keep.map { row[it] } }
        return grid.ifEmpty { null }
    }

    // ------------------------------------------------------------ numbers

    private val BULLET = Regex("^\\s*([•·●◦▪‣*–-]|\\d{1,3}[.)])\\s+")
    private val CODEY = Regex("^(>>>|\\.\\.\\.|\\$ |//|def |class |import |from |return |const |let |var |function )")
    private val CODE_CHARS = Regex("[{}();=\\[\\]<>]{2,}|\\w\\(.*\\)")

    private fun stdev(values: List<Float>): Double {
        val mean = values.average()
        return sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
    }

    /** The median of 0–255 values, from a histogram (a picture has millions). */
    private fun medianOf(px: IntArray): Float {
        val counts = IntArray(256)
        for (p in px) counts[p.coerceIn(0, 255)]++
        // As the median of a sorted list: the middle value, or the mean of the two middle ones.
        fun nth(k: Int): Int {
            var seen = 0
            for (v in 0..255) {
                seen += counts[v]
                if (seen > k) return v
            }
            return 255
        }
        val n = px.size
        return if (n % 2 == 1) nth(n / 2).toFloat() else (nth(n / 2 - 1) + nth(n / 2)) / 2f
    }

    private fun mode(px: IntArray): Float {
        val counts = IntArray(256)
        for (p in px) counts[p.coerceIn(0, 255)]++
        return counts.indices.maxBy { counts[it] }.toFloat()
    }
}

/** The middle of [values] (in any order), or the mean of the two middle
 *  ones; 0 for none. */
internal fun median(values: List<Float>): Float {
    if (values.isEmpty()) return 0f
    val s = values.sorted()
    return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
}
