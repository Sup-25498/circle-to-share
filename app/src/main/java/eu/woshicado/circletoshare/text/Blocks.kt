package eu.woshicado.circletoshare.text

/**
 * The text of a crop as a short document: what "Copy text" puts on the
 * clipboard, as Markdown (the plain-text slot) and as HTML (the rich one).
 * Both readers of the screen — the accessibility tree and OCR — produce it,
 * so both come out formatted the same way.
 */
sealed class Block {
    /** [level] 1–3. */
    data class Heading(val level: Int, val text: List<Run>) : Block()

    /** Running text; a [Run] of "\n" is a hard line break (a title over its
     *  summary, an address). */
    data class Paragraph(val text: List<Run>, val indent: Boolean = false) : Block()

    /** [marker] "-" or a number like "3." */
    data class ListItem(val marker: String, val text: List<Run>, val indent: Boolean = false) : Block()

    data class Code(val text: String) : Block()

    data class Quote(val text: List<Run>) : Block()

    /** Rows of cell texts, the first row the header. */
    data class Table(val rows: List<List<String>>) : Block()
}

/** A piece of inline text in one style. */
data class Run(val text: String, val bold: Boolean = false, val italic: Boolean = false, val code: Boolean = false) {
    fun sameStyle(other: Run) = bold == other.bold && italic == other.italic && code == other.code
}

fun plain(text: String) = listOf(Run(text))

/** The text of runs, without styles. */
fun List<Run>.plainText(): String = joinToString("") { it.text }

/**
 * Collapse the runs' whitespace the way a page shows it: runs of spaces to
 * one, none at either end, hard breaks kept. Adjacent runs in the same style
 * merge, so "**a** **b**" comes out as "**a b**".
 */
fun List<Run>.tidy(): List<Run> {
    val out = ArrayList<Run>()
    var space = false // a space is due before the next word
    for (run in this) {
        for (part in TOKENS.findAll(run.text.replace(SPACES, " "))) {
            val p = part.value
            val last = out.lastOrNull()
            when {
                p == "\n" -> {
                    if (last != null && last.text != "\n") out += Run("\n")
                    space = false
                }
                p.isBlank() -> space = last != null && last.text != "\n"
                else -> {
                    val text = (if (space) " " else "") + p
                    if (last != null && last.text != "\n" && last.sameStyle(run)) {
                        out[out.size - 1] = last.copy(text = last.text + text)
                    } else {
                        out += run.copy(text = text)
                    }
                    space = false
                }
            }
        }
    }
    while (out.lastOrNull()?.text == "\n") out.removeAt(out.size - 1)
    return out
}

private val TOKENS = Regex("\n| +|[^ \n]+")

private val SPACES = Regex("[ \t\u00A0\u2007\u2009\u200A\u202F\u205F\u3000]+")

/**
 * Characters that look like plain ones but paste as something else: curly
 * quotes, a non-breaking hyphen ("Wi‑Fi"), no-break and thin spaces, and
 * invisible ones. Pages and apps use them for typography; in a copy they
 * break searching, code and plain-text editors. Dashes stay — they are
 * punctuation of their own.
 */
fun normalizeChars(text: String): String {
    val sb = StringBuilder(text.length)
    for (ch in text) {
        when (ch) {
            '\u2018', '\u2019', '\u201A', '\u201B', '\u2032' -> sb.append('\'')
            '\u201C', '\u201D', '\u201E', '\u201F', '\u2033' -> sb.append('"')
            '\u2010', '\u2011' -> sb.append('-')
            '\u00A0', '\u2007', '\u2009', '\u200A', '\u202F', '\u205F', '\u3000' -> sb.append(' ')
            '\u00AD', '\u200B', '\u200C', '\u200D', '\u2060', '\uFEFF' -> Unit
            else -> sb.append(ch)
        }
    }
    return sb.toString()
}

// ---------------------------------------------------------------- Markdown

fun List<Block>.toMarkdown(): String {
    val sb = StringBuilder()
    var prev: Block? = null
    for (block in this) {
        if (prev != null) {
            // Items of one list stay together; everything else gets a blank line.
            sb.append(if (prev is Block.ListItem && block is Block.ListItem) "\n" else "\n\n")
        }
        sb.append(block.markdown())
        prev = block
    }
    return normalizeChars(sb.toString()).trimEnd() + "\n"
}

private fun Block.markdown(): String = when (this) {
    is Block.Heading -> "#".repeat(level.coerceIn(1, 3)) + " " + text.inlineMarkdown().replace("  \n", " ")
    is Block.Paragraph -> (if (indent) "  " else "") + text.inlineMarkdown()
    is Block.ListItem -> (if (indent) "  " else "") + "$marker " +
        text.inlineMarkdown().replace("\n", "\n" + " ".repeat(marker.length + 1 + if (indent) 2 else 0))
    is Block.Code -> "```\n" + text.trim('\n') + "\n```"
    is Block.Quote -> "> " + text.inlineMarkdown().replace("\n", "\n> ")
    is Block.Table -> {
        val width = rows.maxOf { it.size }
        val cells = rows.map { row -> (row + List(width - row.size) { "" }).map { it.cellMarkdown() } }
        buildString {
            append("| ").append(cells[0].joinToString(" | ")).append(" |\n")
            append("|").append("---|".repeat(width))
            for (row in cells.drop(1)) append("\n| ").append(row.joinToString(" | ")).append(" |")
        }
    }
}

private fun String.cellMarkdown() = replace("|", "\\|").replace('\n', ' ')

private fun List<Run>.inlineMarkdown(): String = joinToString("") { run ->
    val core = run.text.trim()
    if (core.isEmpty() || core == "\n") return@joinToString run.text.replace("\n", "  \n")
    val lead = run.text.substring(0, run.text.indexOf(core))
    val trail = run.text.substring(lead.length + core.length)
    // Inside emphasis, a * or _ of the text itself would read as markup: an
    // italic "*" (Python's keyword-only marker) would come out as "***".
    val emphasized = if (run.bold || run.italic) core.replace(Regex("[*_]")) { "\\" + it.value } else core
    val marked = when {
        run.code -> "`$core`"
        run.bold && run.italic -> "***$emphasized***"
        run.bold -> "**$emphasized**"
        run.italic -> "*$emphasized*"
        else -> core
    }
    (lead + marked + trail).replace("\n", "  \n")
}

// ---------------------------------------------------------------- plain text

/** The text without markup ("Format as Markdown" off): paragraphs apart,
 *  list items with their marker ("•" for a bullet), table cells apart by
 *  tabs, so that they paste into a spreadsheet's cells. */
fun List<Block>.toPlainText(): String {
    val sb = StringBuilder()
    var prev: Block? = null
    for (block in this) {
        if (prev != null) sb.append(if (prev is Block.ListItem && block is Block.ListItem) "\n" else "\n\n")
        sb.append(
            when (block) {
                is Block.Heading -> block.text.plainText()
                is Block.Paragraph -> (if (block.indent) "  " else "") + block.text.plainText()
                is Block.ListItem -> (if (block.indent) "  " else "") +
                    (if (block.marker == "-") "•" else block.marker) + " " + block.text.plainText()
                is Block.Code -> block.text.trim('\n')
                is Block.Quote -> block.text.plainText()
                is Block.Table -> block.rows.joinToString("\n") { row -> row.joinToString("\t") { it.replace('\n', ' ') } }
            }
        )
        prev = block
    }
    return normalizeChars(sb.toString()).trimEnd() + "\n"
}

// ---------------------------------------------------------------- HTML

fun List<Block>.toHtml(): String {
    val sb = StringBuilder()
    var inList: String? = null
    for (block in this) {
        val list = (block as? Block.ListItem)?.let { if (it.marker[0].isDigit()) "ol" else "ul" }
        if (inList != null && list != inList) sb.append("</$inList>")
        if (list != null && list != inList) sb.append("<$list>")
        inList = list
        sb.append(
            when (block) {
                is Block.Heading -> "<h${block.level}>${block.text.inlineHtml()}</h${block.level}>"
                is Block.Paragraph -> "<p>${block.text.inlineHtml()}</p>"
                is Block.ListItem -> "<li>${block.text.inlineHtml()}</li>"
                is Block.Code -> "<pre><code>${escape(block.text.trim('\n'))}</code></pre>"
                is Block.Quote -> "<blockquote>${block.text.inlineHtml()}</blockquote>"
                is Block.Table -> buildString {
                    append("<table>")
                    block.rows.forEachIndexed { i, row ->
                        val tag = if (i == 0) "th" else "td"
                        append("<tr>")
                        for (cell in row) append("<$tag>").append(escape(cell)).append("</$tag>")
                        append("</tr>")
                    }
                    append("</table>")
                }
            }
        )
    }
    if (inList != null) sb.append("</$inList>")
    return normalizeChars(sb.toString())
}

private fun List<Run>.inlineHtml(): String = joinToString("") { run ->
    var html = escape(run.text).replace("\n", "<br>")
    if (run.code) html = "<code>$html</code>"
    if (run.italic) html = "<i>$html</i>"
    if (run.bold) html = "<b>$html</b>"
    html
}

private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
