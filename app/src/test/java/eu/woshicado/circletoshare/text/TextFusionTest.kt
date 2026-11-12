package eu.woshicado.circletoshare.text

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextFusionTest {

    private fun fused(name: String): String {
        val tree = Fixtures.tree(name)
        return TextFusion.read(
            tree.screen, tree.crop, Fixtures.page(name), Fixtures.gray(name), tree.crop.left, tree.crop.top
        ).toMarkdown()
    }

    @Test
    fun everyFixtureReadsAsExpected() {
        val failed = Fixtures.NAMES.filter { !Fixtures.matches("$it.fused.md", fused(it)) }
        assertTrue("differs (actual in app/build/copytext): $failed", failed.isEmpty())
    }

    @Test
    fun labelsOfIconsAndHiddenControls_areLeftOut() {
        val md = fused("ff_wiki_intro")
        assertFalse(md, "Full Screen" in md || "Mute" in md || "Language" in md)
    }

    @Test
    fun aParagraphTheCropCuts_endsAtTheLastVisibleLine_withTheTreesCharacters() {
        val md = fused("ff_wiki_intro_partial")
        assertTrue(md, md.trimEnd().endsWith("cognitive computing, machine translation,"))
        assertTrue(md, "records – whether" in md) // the tree's en dash, not OCR's guess
    }

    @Test
    fun whereTheTreeHasTheText_fewLinesAreRead() {
        for (name in listOf("chrome_wiki_table", "ff_python_docs", "settings_main")) {
            val tree = Fixtures.tree(name)
            val page = Fixtures.page(name)
            TextFusion.read(tree.screen, tree.crop, page, Fixtures.gray(name), tree.crop.left, tree.crop.top)
            assertTrue("$name read ${page.asked.size} of ${page.boxes.size}", page.asked.size <= page.boxes.size / 4)
        }
    }

    /** The same screen, its tree in coordinates twice the picture's (a picture
     *  taken at half the screen's resolution): the same text. */
    @Test
    fun aPictureScaledFromTheScreen_readsTheSame() {
        fun Box.x2() = Box(left * 2, top * 2, right * 2, bottom * 2)
        fun TextNode.x2(): TextNode = TextNode(
            cls, bounds.x2(), text, heading, web, role, roleDescription, item, styles, textPx * 2, children.map { it.x2() }
        )
        for (name in listOf("chrome_wiki_table", "ff_wiki_intro_partial", "settings_main")) {
            val tree = Fixtures.tree(name)
            val screen = ScreenText(tree.screen.width * 2, tree.screen.height * 2, tree.screen.windows.map { it.x2() })
            val crop = tree.crop.x2()
            val md = TextFusion.read(
                screen, crop, Fixtures.page(name), Fixtures.gray(name), crop.left, crop.top, 2f, 2f
            ).toMarkdown()
            assertTrue(name, md == Fixtures.expected("$name.fused.md"))
        }
    }

    @Test
    fun aPictureOfText_isReadByOcr() {
        assertTrue(fused("chrome_letter").startsWith("Albert Einstein"))
    }
}
