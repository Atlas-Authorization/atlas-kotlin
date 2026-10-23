package net.atlasauth.atlas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyHtmlTest {

    @Test
    fun blocksSpansAndLists() {
        val blocks = PolicyHtml.parse(
            "<h1>Terms</h1><p>Hello <strong>bold</strong> and <em>soft</em> <a href=\"https://x.test/a\">link</a>.</p>" +
                "<ul><li>one</li><li>two</li></ul><ol><li>first</li><li>second</li></ol>",
        )
        assertEquals(PolicyBlock.Kind.HEADING, blocks[0].kind)
        assertEquals(1, blocks[0].level)
        assertEquals("Terms", blocks[0].text)
        val p = blocks[1]
        assertEquals("Hello bold and soft link.", p.text)
        assertTrue(p.spans.any { it.bold && it.text == "bold" })
        assertTrue(p.spans.any { it.italic && it.text == "soft" })
        assertEquals("https://x.test/a", p.spans.single { it.href != null }.href)
        assertEquals(listOf("•", "•", "1.", "2."), blocks.filter { it.kind == PolicyBlock.Kind.LIST_ITEM }.map { it.marker })
    }

    @Test
    fun unsafeLinksAndActiveContentAreDropped() {
        val blocks = PolicyHtml.parse(
            "<p><a href=\"javascript:alert(1)\">bad</a><script>alert(1)</script><style>p{}</style> ok</p>",
        )
        assertEquals("bad ok", blocks.single().text)
        assertNull(blocks.single().spans.firstOrNull { it.href != null })
    }

    @Test
    fun entitiesAreDecodedAndWhitespaceCollapsed() {
        assertEquals("A & B <c> “q” é", PolicyHtml.plainText("<p>A &amp;\n  B &lt;c&gt; &ldquo;q&rdquo; &#233;</p>"))
    }

    @Test
    fun nestedListsIndentAndRulesBecomeBlocks() {
        val blocks = PolicyHtml.parse("<ul><li>a<ul><li>b</li></ul></li></ul><hr><blockquote><p>quoted</p></blockquote>")
        val items = blocks.filter { it.kind == PolicyBlock.Kind.LIST_ITEM }
        assertEquals(listOf(0, 1), items.map { it.depth })
        assertTrue(blocks.any { it.kind == PolicyBlock.Kind.RULE })
        assertEquals(PolicyBlock.Kind.QUOTE, blocks.last().kind)
    }

    @Test
    fun unknownTagsAreTransparentAndStrayAnglesAreText() {
        assertEquals("keep this", PolicyHtml.plainText("<section><blink>keep</blink> this</section>"))
        assertEquals("1 < 2", PolicyHtml.plainText("<p>1 < 2"))
        assertEquals("", PolicyHtml.plainText(""))
    }

    @Test
    fun markdownFallbackEscapesEverything() {
        val html = PolicyMarkdown.toSafeHtml("# Title\n\nHi <script>x</script> **b** [l](https://a.b/c)\n\n- one\n- two")
        assertFalse(html.contains("<script>"))
        assertTrue(html.contains("<h1>Title</h1>"))
        assertTrue(html.contains("<strong>b</strong>"))
        assertTrue(html.contains("<a href=\"https://a.b/c\">l</a>"))
        assertTrue(html.contains("<ul>"))
        assertEquals("Title\nHi <script>x</script> b l\n• one\n• two", PolicyHtml.plainText(html))
    }

    @Test
    fun htmlForPrefersTheServersHtml() {
        val base = AtlasPolicy("k", "K", "checkbox", true, "sign_up", "v", "1", "<p>x</p>", "# md", null, "h", null, null, null)
        assertEquals("<p>x</p>", PolicyHtml.htmlFor(base))
        assertTrue(PolicyHtml.htmlFor(base.copy(contentHtml = null)).contains("<h1>md</h1>"))
        assertEquals("", PolicyHtml.htmlFor(base.copy(contentHtml = null, contentMarkdown = null)))
    }
}
