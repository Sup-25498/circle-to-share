package eu.woshicado.circletoshare.text

import org.junit.Assert.assertEquals
import org.junit.Test

class BlocksTest {

    @Test
    fun typographicLookalikes_pasteAsPlainCharacters() {
        assertEquals("can't \"Wi-Fi\" 28 % ab", normalizeChars("can’t “Wi‑Fi” 28 % a­b"))
        assertEquals("x", normalizeChars("​x﻿"))
        assertEquals("1891–1923 — yes", normalizeChars("1891–1923 — yes"))
    }

    @Test
    fun runsCollapseSpaces_andKeepStylesApart() {
        val runs = listOf(Run("  Check "), Run("ww", bold = true), Run(".example.com    instead "), Run("\n"), Run(" next"))
        assertEquals(
            listOf(Run("Check"), Run(" ww", bold = true), Run(".example.com instead"), Run("\n"), Run("next")),
            runs.tidy()
        )
    }

    @Test
    fun plainText_dropsTheMarkup() {
        val blocks = listOf(
            Block.Heading(2, plain("Title")),
            Block.Paragraph(listOf(Run("Use "), Run("map()", code = true), Run(" "), Run("now", bold = true), Run("\n"), Run("next"))),
            Block.ListItem("-", plain("one")),
            Block.ListItem("2.", plain("two")),
            Block.Code("x = 1"),
            Block.Table(listOf(listOf("A", "B"), listOf("1", "2"))),
        )
        assertEquals("Title\n\nUse map() now\nnext\n\n• one\n2. two\n\nx = 1\n\nA\tB\n1\t2\n", blocks.toPlainText())
    }

    @Test
    fun anItalicStar_isEscaped_soItReadsAsAStar() {
        val md = listOf(Block.Paragraph(listOf(Run("list.sort("), Run("*", italic = true), Run(", "), Run("key=None", italic = true), Run(")"))))
            .toMarkdown()
        assertEquals("list.sort(*\\**, *key=None*)\n", md)
    }

    @Test
    fun markdownAndHtml_fromTheSameBlocks() {
        val blocks = listOf(
            Block.Heading(2, plain("Title")),
            Block.Paragraph(listOf(Run("Use "), Run("map()", code = true), Run(" now"), Run("\n"), Run("next line"))),
            Block.ListItem("-", plain("one")),
            Block.ListItem("-", listOf(Run("two", bold = true))),
            Block.Code("a < b"),
            Block.Table(listOf(listOf("A", "B|C"), listOf("1", "2"))),
        )
        assertEquals(
            "## Title\n\nUse `map()` now  \nnext line\n\n- one\n- **two**\n\n```\na < b\n```\n\n" +
                "| A | B\\|C |\n|---|---|\n| 1 | 2 |\n",
            blocks.toMarkdown()
        )
        assertEquals(
            "<h2>Title</h2><p>Use <code>map()</code> now<br>next line</p><ul><li>one</li><li><b>two</b></li></ul>" +
                "<pre><code>a &lt; b</code></pre><table><tr><th>A</th><th>B|C</th></tr><tr><td>1</td><td>2</td></tr></table>",
            blocks.toHtml()
        )
    }
}
