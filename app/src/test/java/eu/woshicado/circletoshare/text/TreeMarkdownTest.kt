package eu.woshicado.circletoshare.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TreeMarkdownTest {

    @Test
    fun everyFixtureReadsAsExpected() {
        val failed = Fixtures.NAMES.filter { name ->
            val tree = Fixtures.tree(name)
            !Fixtures.matches("$name.tree.md", TreeMarkdown(tree.crop).read(tree.screen).toMarkdown())
        }
        assertTrue("differs (actual in app/build/copytext): $failed", failed.isEmpty())
    }

    @Test
    fun aTableWithMergedRankCells_repeatsTheRankOnEveryRow() {
        val tree = Fixtures.tree("chrome_wiki_table")
        val table = TreeMarkdown(tree.crop).read(tree.screen).filterIsInstance<Block.Table>().single()
        assertEquals(listOf("4", "Malaysia"), table.rows[4].take(2))
        assertEquals(listOf("4", "Hong Kong"), table.rows[5].take(2))
    }

    /** Chrome gives headings no level, so they are ranked by their height on
     *  screen; a pixel of sub-pixel rounding must not rank two of a kind apart
     *  (seen on a page whose h2s were 60 and 61 px tall). */
    @Test
    fun headingsAPixelApartInHeight_getTheSameLevel() {
        fun heading(text: String, top: Int, bottom: Int) =
            TextNode("android.widget.TextView", Box(49, top, 1031, bottom), text, web = true, role = "heading")
        fun para(text: String, top: Int, bottom: Int) =
            TextNode("android.widget.TextView", Box(49, top, 1031, bottom), text, web = true, role = "paragraph")
        val page = TextNode(
            "android.webkit.WebView", Box(0, 0, 1080, 2424), web = true, children = listOf(
                heading("Weekend at the lake", 181, 265),
                para("Two nights at the cabin.", 370, 498),
                heading("Packing list", 543, 603),
                para("Tent, sleeping bags and the small stove", 624, 677),
                heading("Costs", 931, 992),
                para("Cabin, 2 nights", 1010, 1102),
            )
        )
        val screen = ScreenText(1080, 2424, listOf(page))
        val levels = TreeMarkdown(Box(0, 530, 1080, 1470)).read(screen)
            .filterIsInstance<Block.Heading>().map { it.text.plainText() to it.level }
        assertEquals(listOf("Packing list" to 2, "Costs" to 2), levels)
        // The page's title, a size larger, ranks above them.
        val all = TreeMarkdown(Box(0, 0, 1080, 2424)).read(screen)
            .filterIsInstance<Block.Heading>().map { it.text.plainText() to it.level }
        assertEquals(listOf("Weekend at the lake" to 2, "Packing list" to 3, "Costs" to 3), all)
    }

    @Test
    fun nativeBoldAndBulletLines_becomeMarkdown() {
        val tree = Fixtures.tree("ctshare_main")
        val md = TreeMarkdown(tree.crop).read(tree.screen).toMarkdown()
        assertTrue(md, "- Long-press inside the selection: **copy**" in md)
    }
}
