package eu.woshicado.circletoshare.text

/**
 * The text of the accessibility tree inside [crop] (screen pixels), as
 * [Block]s. Walks the windows in order — which is reading order — and keeps
 * the text that lies mostly inside the crop. Structure comes from what each
 * kind of app reports:
 *  - browsers: their element roles (paragraph, list item, code, table, …),
 *    Firefox even the heading level;
 *  - native apps: the heading flag, text sizes, and bold/italic ranges.
 * Tables are rebuilt from where their cells sit, since browsers disagree on
 * (or leave out) the row and column numbers of merged cells.
 *
 * Open for [TextFusion], which checks the text against what OCR sees.
 */
open class TreeMarkdown(protected val crop: Box) {

    /** What a walk finds, before headings get their levels and native rows
     *  their grouping (both need everything found to decide). */
    sealed class Item {
        abstract val node: TextNode?

        class Heading(val text: List<Run>, val level: Int?, val height: Int, val px: Float, override val node: TextNode?) : Item()
        class Para(val text: List<Run>, val indent: Boolean, override val node: TextNode?) : Item()
        class Pre(val text: String, override val node: TextNode?) : Item()
        class Li(val marker: String, val text: List<Run>, val indent: Boolean, override val node: TextNode?) : Item()
        class Table(val rows: List<List<String>>, override val node: TextNode?) : Item()
        class Native(val text: List<Run>, val px: Float, val parent: TextNode?, val box: Box, override val node: TextNode?) : Item()
        /** Finished elsewhere (OCR text placed among the tree's). */
        class Ready(val blocks: List<Block>) : Item() { override val node: TextNode? = null }
    }

    fun read(screen: ScreenText): List<Block> = render(items(screen))

    fun items(screen: ScreenText): List<Item> {
        val out = ArrayList<Item>()
        for (window in screen.windows) blocks(window, null, out)
        return out
    }

    // ------------------------------------------------------------ inline

    /** The text of a leaf, or null to leave it out. */
    protected open fun leafText(node: TextNode): String? {
        val text = node.text ?: return null
        if (text.isNotBlank() && !node.bounds.inside(crop)) return null
        return text
    }

    /** Inline runs of a subtree: only leaves inside the crop. */
    fun inline(node: TextNode): List<Run> {
        if (!touches(node)) return emptyList()
        val runs: List<Run> = if (node.children.isNotEmpty() && node.textBelow) {
            node.children.flatMap { inline(it) }
        } else {
            val raw = node.text ?: return emptyList()
            if (raw.isBlank() && raw !in WHITESPACE_LEAVES) return emptyList()
            val text = if (raw.isBlank()) raw else leafText(node) ?: return emptyList()
            if (node.web) listOf(Run(text)) else styledRuns(text, node.styles)
        }
        val plain = runs.plainText()
        if (plain.isBlank()) return runs
        return when (node.role) {
            "code" -> if ('\n' in plain.trim()) runs else runs.map { it.copy(code = true) }
            "strong" -> runs.map { it.copy(bold = true) }
            "emphasis" -> runs.map { it.copy(italic = true) }
            else -> runs
        }
    }

    private fun touches(node: TextNode) = node.bounds.empty || node.bounds.touches(crop)

    // ------------------------------------------------------------ blocks

    private enum class Kind { HEADING, PARA, LI, TABLE, PRE, NATIVE, CONTAINER }

    private fun holdsBlocks(node: TextNode): Boolean = node.children.any {
        it.role in BLOCK_ROLES || it.roleDescription.startsWith("heading level") || holdsBlocks(it)
    }

    private fun kind(node: TextNode): Kind {
        val role = node.role
        return when {
            node.heading || role == "heading" || node.roleDescription.startsWith("heading level") -> Kind.HEADING
            role in WRAPPER_ROLES && holdsBlocks(node) ->
                if (role in LIST_ITEM_ROLES) Kind.LI else Kind.CONTAINER
            role in PARA_ROLES -> Kind.PARA
            role in LIST_ITEM_ROLES -> Kind.LI
            role == "table" || role == "grid" -> Kind.TABLE
            role == "pre" -> Kind.PRE
            !node.web && node.hasText && !node.textBelow -> Kind.NATIVE
            else -> Kind.CONTAINER
        }
    }

    /** Called for a paragraph-like node before it is read; non-null takes
     *  its place (what [TextFusion] does with a paragraph the crop cuts). */
    protected open fun paragraphOverride(node: TextNode): List<Run>? = null

    protected open fun blocks(node: TextNode, parent: TextNode?, out: MutableList<Item>) {
        if (!node.bounds.empty && !node.bounds.touches(crop) && node.children.isEmpty()) return
        when (kind(node)) {
            Kind.HEADING -> {
                val text = inline(node).tidy()
                if (text.isNotEmpty()) {
                    val level = LEVEL.find(node.roleDescription)?.groupValues?.get(1)?.toInt()
                    out += Item.Heading(text, level, node.bounds.height, node.textPx, node)
                }
            }
            Kind.PARA -> {
                val runs = paragraphOverride(node) ?: inline(node)
                addRun(runs, node, out)
            }
            Kind.NATIVE -> {
                if (node.bounds.inside(crop)) {
                    val text = leafText(node)
                    if (!text.isNullOrBlank()) {
                        out += Item.Native(styledRuns(text, node.styles), node.textPx, parent, node.bounds, node)
                    }
                }
            }
            Kind.LI -> {
                val sub = ArrayList<Item>()
                container(node, sub)
                val first = sub.firstOrNull()
                if (first != null) {
                    var marker = "-"
                    var text = (first as? Item.Para)?.text ?: (first as? Item.Native)?.text ?: emptyList()
                    val plain = text.plainText()
                    val m = MARKER.find(plain)
                    if (m != null) {
                        marker = if (m.groupValues[1][0].isDigit()) m.groupValues[1] else "-"
                        text = dropChars(text, m.range.last + 1)
                    }
                    if (first is Item.Para || first is Item.Native) {
                        out += Item.Li(marker, text, false, node)
                        for (item in sub.drop(1)) out += indented(item)
                    } else {
                        out += Item.Li(marker, emptyList(), false, node)
                        for (item in sub) out += indented(item)
                    }
                }
            }
            Kind.TABLE -> {
                val rows = table(node)
                if (rows != null && isLayout(node, rows)) layoutRows(node, out)
                else if (rows != null) out += Item.Table(rows, node)
            }
            Kind.PRE -> {
                val text = inline(node).plainText()
                if (text.isNotBlank()) out += Item.Pre(text, node)
            }
            Kind.CONTAINER -> container(node, out)
        }
    }

    private fun indented(item: Item): Item = when (item) {
        is Item.Para -> Item.Para(item.text, true, item.node)
        is Item.Li -> Item.Li(item.marker, item.text, true, item.node)
        else -> item
    }

    private fun addRun(runs: List<Run>, node: TextNode?, out: MutableList<Item>) {
        val plain = runs.plainText()
        if (plain.isBlank()) return
        out += if ('\n' in plain.trim()) Item.Pre(plain, node) else Item.Para(runs, false, node)
    }

    private fun container(node: TextNode, out: MutableList<Item>) {
        val run = ArrayList<Run>()
        var runNode: TextNode? = null
        for (child in node.children) {
            val inlineish = child.web && (
                (kind(child) == Kind.PARA && child.role in INLINE_PARA_ROLES) || child.role in INLINE_ROLES)
            if (inlineish) {
                val runs = inline(child)
                if (runs.isNotEmpty() && runNode == null && runs.plainText().isNotBlank()) runNode = child
                run += runs
                continue
            }
            addRun(run.toList(), runNode, out)
            run.clear()
            runNode = null
            blocks(child, node, out)
        }
        addRun(run.toList(), runNode, out)
    }

    // ------------------------------------------------------------ tables

    private fun nested(node: TextNode): Boolean =
        node.children.any { it.role.lowercase() in TABLE_ROLES || nested(it) }

    private fun isLayout(node: TextNode, rows: List<List<String>>): Boolean {
        if (nested(node) || rows[0].size < 2) return true
        val single = rows.count { row -> row.count { it.isNotEmpty() } <= 1 }
        return single > 0.45 * rows.size
    }

    /** A table used for layout (Hacker News): its rows read as lines. */
    private fun layoutRows(node: TextNode, out: MutableList<Item>) {
        for (child in node.children) {
            val role = child.role.lowercase()
            when {
                role in TABLE_ROLES -> blocks(child, node, out)
                role == "row" && !nested(child) -> {
                    val cells = child.children.map { inline(it).tidy() }.filter { it.isNotEmpty() }
                    val line = ArrayList<Run>()
                    for (cell in cells) {
                        if (line.isNotEmpty()) line += Run(" ")
                        line += cell
                    }
                    if (line.isNotEmpty()) out += Item.Para(line, false, child)
                }
                nested(child) -> for (c in child.children) {
                    when {
                        c.role.lowercase() in TABLE_ROLES -> blocks(c, child, out)
                        nested(c) -> layoutRows(c, out)
                        else -> blocks(c, child, out)
                    }
                }
                else -> layoutRows(child, out)
            }
        }
    }

    /** Cells placed by geometry: browsers disagree on (or leave out) row and
     *  column indices and spans. A merged cell repeats down its rows. */
    private fun table(node: TextNode): List<List<String>>? {
        val rows = ArrayList<List<TextNode>>()
        fun cellsOf(x: TextNode, acc: MutableList<TextNode>) {
            for (k in x.children) {
                val r = k.role.lowercase()
                if (r in CELL_ROLES) acc += k else if (r !in TABLE_ROLES) cellsOf(k, acc)
            }
        }
        fun walk(x: TextNode) {
            for (k in x.children) {
                val r = k.role.lowercase()
                if (r == "row") {
                    val acc = ArrayList<TextNode>()
                    cellsOf(k, acc)
                    if (acc.isNotEmpty()) rows += acc
                } else if (r !in TABLE_ROLES) walk(k)
            }
        }
        walk(node)
        val visible = rows.map { r -> r.filter { it.bounds.inside(crop, 0.3) } }.filter { it.isNotEmpty() }
        if (visible.isEmpty()) return null
        val tol = 8
        val lefts = ArrayList<Int>()
        for (x in visible.flatten().map { it.bounds.left }.sorted()) {
            if (lefts.isEmpty() || x - lefts.last() > tol) lefts += x
        }
        val tops = visible.map { r -> r.minOf { it.bounds.top } }
        val grid = HashMap<Pair<Int, Int>, String>()
        visible.forEachIndexed { ri, row ->
            for (cell in row) {
                val b = cell.bounds
                val c0 = lefts.indices.last { lefts[it] <= b.left + tol }
                val c1 = maxOf(c0 + 1, lefts.count { it < b.right - tol })
                val r1 = maxOf(ri + 1, tops.count { it < b.bottom - tol })
                val text = inline(cell).tidy().plainText().ifEmpty { cell.text?.trim() ?: "" }
                for (rr in ri until r1) for (cc in c0 until c1) {
                    grid.putIfAbsent(rr to cc, if (cc == c0) text else "")
                }
            }
        }
        val out = visible.indices.map { r -> lefts.indices.map { c -> grid[r to c] ?: "" } }
        val keep = lefts.indices.filter { c -> out.any { it[c].isNotEmpty() } }
        return out.map { row -> keep.map { row[it] } }
    }

    // ------------------------------------------------------------ render

    /** Whether two heading heights are one size: within 3 px, or a twelfth. */
    private fun sameSize(a: Int, b: Int) = kotlin.math.abs(a - b) <= maxOf(3, maxOf(a, b) / 12)

    fun render(items: List<Item>): List<Block> {
        // The heading sizes on screen, largest first, for headings without a
        // level (Chrome's): one entry per size, not per exact height — the
        // same h2 is 60 px tall here and 61 there (sub-pixel layout rounds
        // the box differently from place to place), and that must not make
        // one a level deeper than the other.
        val headHeights = ArrayList<Int>()
        for (h in items.filterIsInstance<Item.Heading>().filter { it.level == null && it.height > 0 }
            .map { it.height }.sortedDescending()) {
            if (headHeights.none { sameSize(it, h) }) headHeights += h
        }
        val bodyPx = median(items.filterIsInstance<Item.Native>().map { it.px }.filter { it > 0 })
        val out = ArrayList<Block>()
        var prevParent: TextNode? = null
        var prevBottom = 0
        for (item in items) {
            if (item !is Item.Native) prevParent = null
            when (item) {
                is Item.Heading -> {
                    val size = headHeights.indexOfFirst { sameSize(it, item.height) }
                    val level = item.level ?: when {
                        item.px > 0 && bodyPx > 0 && item.px >= 1.4 * bodyPx -> 1
                        item.px <= 0 && size >= 0 -> 2 + minOf(size, 1)
                        else -> 2
                    }
                    out += Block.Heading(level.coerceIn(1, 3), item.text.tidy())
                }
                is Item.Para -> item.text.tidy().takeIf { it.isNotEmpty() }?.let { out += Block.Paragraph(it, item.indent) }
                is Item.Pre -> out += Block.Code(item.text.trim('\n'))
                is Item.Li -> out += Block.ListItem(item.marker, item.text.tidy(), item.indent)
                is Item.Table -> out += Block.Table(item.rows)
                is Item.Ready -> out += item.blocks
                is Item.Native -> {
                    if (item.px > 0 && bodyPx > 0 && item.px >= 1.4 * bodyPx) {
                        out += Block.Heading(1, item.text.tidy())
                        prevParent = null
                        continue
                    }
                    val gap = if (prevParent != null) item.box.top - prevBottom else Int.MAX_VALUE
                    prevBottom = item.box.bottom
                    val joins = prevParent != null && prevParent === item.parent &&
                        gap < 0.35 * (if (item.px > 0) item.px else 40f) && out.lastOrNull() is Block.Paragraph
                    addNative(item.text, joins, out)
                    prevParent = item.parent
                }
            }
        }
        return out
    }

    /** A native text: its lines, "• " lines as list items; joined under the
     *  paragraph before when it is the summary of the same row. */
    private fun addNative(text: List<Run>, joins: Boolean, out: MutableList<Block>) {
        var lead = ArrayList<Run>()
        var joinNext = joins
        fun flushLead() {
            val tidy = lead.tidy()
            if (tidy.isEmpty()) return
            val last = out.lastOrNull()
            if (joinNext && last is Block.Paragraph) {
                out[out.size - 1] = Block.Paragraph(last.text + Run("\n") + tidy, last.indent)
            } else {
                out += Block.Paragraph(tidy)
            }
            lead = ArrayList()
            joinNext = false
        }
        for (line in splitLines(text)) {
            val plain = line.plainText()
            if (plain.startsWith("• ") || plain.startsWith("· ")) {
                flushLead()
                out += Block.ListItem("-", dropChars(line, 2).tidy())
                joinNext = false
            } else {
                if (lead.isNotEmpty()) lead += Run("\n")
                lead += line
            }
        }
        flushLead()
    }

    companion object {
        private val WHITESPACE_LEAVES = setOf(" ", "\n", " ")
        private val LEVEL = Regex("level (\\d)")
        private val MARKER = Regex("^\\s*(\\d+[.)]|[•·◦▪‣–-])\\s*")
        private val BLOCK_ROLES = setOf("paragraph", "list", "pre", "table", "heading", "descriptionList", "definitionlist")
        private val PARA_ROLES = setOf(
            "paragraph", "term", "definition", "pushbutton", "button", "caption", "figcaption",
            "label", "staticText", "text leaf", "link"
        )
        private val LIST_ITEM_ROLES = setOf("listitem", "listItem")
        /** Roles that read as one paragraph unless they hold blocks of their own. */
        private val WRAPPER_ROLES = setOf("paragraph", "term", "definition") + LIST_ITEM_ROLES
        private val INLINE_PARA_ROLES = setOf("text leaf", "staticText", "link", "strong", "emphasis", "code", "label")
        private val INLINE_ROLES = setOf(
            "strong", "emphasis", "code", "text", "superscript", "subscript", "mark", "whitespace",
            "list item marker", "listMarker", "abbr", "time"
        )
        private val TABLE_ROLES = setOf("table", "grid")
        private val CELL_ROLES = setOf("cell", "columnheader", "rowheader", "gridcell")

        /** [text] with native style ranges applied as runs. */
        fun styledRuns(text: String, styles: List<StyleRange>): List<Run> {
            if (styles.isEmpty()) return listOf(Run(text))
            val bold = BooleanArray(text.length)
            val italic = BooleanArray(text.length)
            for (s in styles) for (i in s.start.coerceAtLeast(0) until s.end.coerceAtMost(text.length)) {
                if (s.style and 1 != 0) bold[i] = true
                if (s.style and 2 != 0) italic[i] = true
            }
            val out = ArrayList<Run>()
            var start = 0
            for (i in 1..text.length) {
                if (i == text.length || bold[i] != bold[start] || italic[i] != italic[start]) {
                    out += Run(text.substring(start, i), bold = bold[start], italic = italic[start])
                    start = i
                }
            }
            return out
        }

        /** Runs split at their "\n"s into lines. */
        fun splitLines(runs: List<Run>): List<List<Run>> {
            val lines = arrayListOf(ArrayList<Run>())
            for (run in runs) {
                val parts = run.text.split('\n')
                parts.forEachIndexed { i, part ->
                    if (i > 0) lines += ArrayList<Run>()
                    if (part.isNotEmpty()) lines.last() += run.copy(text = part)
                }
            }
            return lines
        }

        /** [runs] without their first [count] characters. */
        fun dropChars(runs: List<Run>, count: Int): List<Run> {
            var left = count
            val out = ArrayList<Run>()
            for (run in runs) {
                if (left >= run.text.length) {
                    left -= run.text.length
                    continue
                }
                out += run.copy(text = run.text.substring(left))
                left = 0
            }
            return out
        }
    }
}
