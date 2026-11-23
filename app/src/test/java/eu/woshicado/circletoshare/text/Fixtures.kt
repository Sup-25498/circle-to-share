package eu.woshicado.circletoshare.text

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The Copy text fixtures (src/test/resources/copytext): 14 crops from a
 * Pixel 10 Pro, of public web pages, a news list written for these tests
 * (ff_news: made-up headlines and names; its page is ff_news.source.html),
 * Settings and this app. Per crop:
 *  - NAME.tree.json: the accessibility tree at capture time, cut down to
 *    the nodes near the crop, with the crop box in screen pixels;
 *  - NAME.ocr.json: PaddleOCR (PP-OCRv5 mobile) lines, in crop pixels;
 *  - NAME.pgm.gz: the crop, grayscale;
 *  - NAME.tree.md / .layout.md / .fused.md: the expected Markdown.
 * A mismatch writes the actual output to build/copytext/ for review.
 */
object Fixtures {
    val NAMES = listOf(
        "ff_error_page", "chrome_error_page", "ff_wiki_intro", "ff_wiki_intro_partial", "ff_wiki_section",
        "ff_wiki_unicode", "chrome_wiki_table", "ff_python_docs", "chrome_mdn", "ff_news", "chrome_letter",
        "settings_main", "settings_display", "ctshare_main",
    )

    private fun resource(name: String): String =
        Fixtures::class.java.getResource("/copytext/$name")!!.readText()

    fun hasResource(name: String) = Fixtures::class.java.getResource("/copytext/$name") != null

    class Tree(val screen: ScreenText, val crop: Box)

    fun tree(name: String): Tree {
        val json = JSONObject(resource("$name.tree.json"))
        val c = json.getJSONArray("crop")
        val windows = json.getJSONArray("windows")
        return Tree(
            ScreenText(json.getInt("w"), json.getInt("h"), (0 until windows.length()).map { node(windows.getJSONObject(it)) }),
            Box(c.getInt(0), c.getInt(1), c.getInt(2), c.getInt(3))
        )
    }

    private fun node(o: JSONObject): TextNode {
        val b = o.getJSONArray("b")
        val kids = o.optJSONArray("k") ?: JSONArray()
        val spans = o.optJSONArray("s") ?: JSONArray()
        return TextNode(
            cls = o.getString("c"),
            bounds = Box(b.getInt(0), b.getInt(1), b.getInt(2), b.getInt(3)),
            text = if (o.has("t")) o.getString("t") else null,
            heading = o.optBoolean("h"),
            web = o.optBoolean("w"),
            role = o.optString("r"),
            roleDescription = o.optString("d"),
            item = o.optJSONArray("i")?.let { a -> IntArray(a.length()) { a.getInt(it) } },
            styles = (0 until spans.length()).map {
                val s = spans.getJSONArray(it)
                StyleRange(s.getInt(0), s.getInt(1), s.getInt(2))
            },
            textPx = o.optDouble("px", 0.0).toFloat(),
            children = (0 until kids.length()).map { node(kids.getJSONObject(it)) },
        )
    }

    fun ocr(name: String): List<OcrLine> {
        val lines = JSONObject(resource("$name.ocr.json")).getJSONArray("lines")
        return (0 until lines.length()).map {
            val l = lines.getJSONObject(it)
            val b = l.getJSONArray("box")
            OcrLine(
                b.getDouble(0).toFloat(), b.getDouble(1).toFloat(), b.getDouble(2).toFloat(), b.getDouble(3).toFloat(),
                l.getString("text"), l.getDouble("score").toFloat()
            )
        }
    }

    /** The OCR lines as a page, every line already read (but counted when asked for). */
    class FixturePage(private val read: List<OcrLine>) : OcrPage {
        val asked = HashSet<Int>()
        override val boxes = read.map { floatArrayOf(it.left, it.top, it.right, it.bottom) }
        override fun lines(indices: Collection<Int>): List<OcrLine> = indices.map { asked += it; read[it] }
    }

    fun page(name: String) = FixturePage(ocr(name))

    /** The crop, from a gzipped binary PGM (no image decoder off-device). */
    fun gray(name: String): Gray {
        val bytes = java.util.zip.GZIPInputStream(Fixtures::class.java.getResourceAsStream("/copytext/$name.pgm.gz")!!)
            .use { it.readBytes() }
        // Header: "P5\n<w> <h>\n255\n", then one byte per pixel.
        var pos = 0
        val fields = ArrayList<String>()
        while (fields.size < 4) {
            val end = bytes.indexOf('\n'.code.toByte(), pos)
            fields += String(bytes, pos, end - pos).split(' ')
            pos = end + 1
        }
        val w = fields[1].toInt()
        val h = fields[2].toInt()
        return Gray(w, h, IntArray(w * h) { bytes[pos + it].toInt() and 0xFF })
    }

    private fun ByteArray.indexOf(b: Byte, from: Int): Int {
        for (i in from until size) if (this[i] == b) return i
        return -1
    }

    fun expected(file: String): String = resource(file)

    /** Compare, and on a mismatch leave the actual output next to the build for review. */
    fun matches(file: String, actual: String): Boolean {
        val ok = hasResource(file) && expected(file) == actual
        if (!ok) File("build/copytext").apply { mkdirs() }.resolve(file).writeText(actual)
        return ok
    }
}
