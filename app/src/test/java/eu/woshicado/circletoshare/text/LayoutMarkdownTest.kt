package eu.woshicado.circletoshare.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutMarkdownTest {

    @Test
    fun everyFixtureReadsAsExpected() {
        val failed = Fixtures.NAMES.filter { name ->
            !Fixtures.matches("$name.layout.md", LayoutMarkdown.read(Fixtures.ocr(name), Fixtures.gray(name)).toMarkdown())
        }
        assertTrue("differs (actual in app/build/copytext): $failed", failed.isEmpty())
    }

    @Test
    fun aBorderedTable_isReadFromItsRulingLines_mergedRanksIncluded() {
        val blocks = LayoutMarkdown.read(Fixtures.ocr("chrome_wiki_table"), Fixtures.gray("chrome_wiki_table"))
        val table = blocks.filterIsInstance<Block.Table>().single()
        assertEquals("Rank", table.rows[0][0])
        assertEquals(listOf("4", "Malaysia"), table.rows[4].take(2))
        assertEquals(listOf("4", "Hong Kong"), table.rows[5].take(2))
    }

    @Test
    fun bulletsTheOcrDropped_comeBackFromTheDotsInThePixels() {
        val md = LayoutMarkdown.read(Fixtures.ocr("ff_error_page"), Fixtures.gray("ff_error_page")).toMarkdown()
        assertTrue(md, "- Check the address" in md && "- If you are unable" in md)
    }
}
