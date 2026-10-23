package net.atlasauth.atlas

/**
 * A tiny, dependency-free reader for the sanitised HTML a policy document is
 * served as (`content_html`). It turns the markup into a list of [PolicyBlock]s
 * (paragraphs, headings, list items, quotes, rules) made of styled
 * [PolicySpan]s, which the Compose UI draws as an `AnnotatedString`. Pure
 * Kotlin, so it is unit-tested off-device.
 *
 * It is deliberately forgiving: tags it does not know are transparent (their
 * text is kept), `<script>` / `<style>` content is dropped, and only `http(s)`
 * and `mailto:` links survive. It never builds a view hierarchy from the
 * markup, so nothing in a document can run or load anything.
 */
data class PolicySpan(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
    /** The link target, only for safe schemes (`http`, `https`, `mailto`). */
    val href: String? = null,
)

data class PolicyBlock(
    val kind: Kind,
    val spans: List<PolicySpan>,
    /** 1 to 6 for a [Kind.HEADING]; otherwise 0. */
    val level: Int = 0,
    /** The list marker (`•` or `1.`) for a [Kind.LIST_ITEM]. */
    val marker: String? = null,
    /** List nesting depth, 0 for a top-level item. */
    val depth: Int = 0,
) {
    enum class Kind { PARAGRAPH, HEADING, LIST_ITEM, QUOTE, RULE }

    /** The block's text with no styling. */
    val text: String get() = spans.joinToString("") { it.text }
}

object PolicyHtml {
    private val BLOCK_TAGS = setOf(
        "p", "div", "section", "article", "header", "footer", "ul", "ol", "li", "blockquote",
        "h1", "h2", "h3", "h4", "h5", "h6", "tr", "table", "pre", "hr", "br",
    )
    private val DROP_TAGS = setOf("script", "style", "head", "template", "iframe", "object", "embed")

    /** Parse sanitised policy HTML into blocks. */
    fun parse(html: String): List<PolicyBlock> {
        val blocks = ArrayList<PolicyBlock>()
        var spans = ArrayList<PolicySpan>()
        var kind = PolicyBlock.Kind.PARAGRAPH
        var level = 0
        var marker: String? = null
        var depth = 0
        var bold = 0
        var italic = 0
        var code = 0
        var quote = 0
        var dropUntil: String? = null
        val hrefs = ArrayList<String?>()
        val lists = ArrayList<IntArray>() // [ordered (1/0), counter]

        fun flush() {
            // Trim the edges and collapse the leftovers of whitespace-only runs.
            val cleaned = ArrayList<PolicySpan>()
            for (s in spans) if (s.text.isNotEmpty()) cleaned.add(s)
            if (cleaned.isNotEmpty()) {
                cleaned[0] = cleaned[0].copy(text = cleaned[0].text.trimStart())
                val last = cleaned.size - 1
                cleaned[last] = cleaned[last].copy(text = cleaned[last].text.trimEnd())
            }
            val kept = cleaned.filter { it.text.isNotEmpty() }
            if (kept.isNotEmpty()) {
                val k = if (kind == PolicyBlock.Kind.PARAGRAPH && quote > 0) PolicyBlock.Kind.QUOTE else kind
                blocks.add(PolicyBlock(k, kept, level, marker, depth))
            }
            spans = ArrayList()
            kind = PolicyBlock.Kind.PARAGRAPH
            level = 0
            marker = null
            depth = 0
        }

        fun addText(raw: String) {
            if (raw.isEmpty()) return
            val decoded = decodeEntities(raw)
            val collapsed = decoded.replace(Regex("[ \t\r\n\u000c]+"), " ")
            if (collapsed.isEmpty()) return
            var text = collapsed
            // Avoid double spaces across span boundaries.
            val prev = spans.lastOrNull()
            if (prev != null && prev.text.endsWith(" ") && text.startsWith(" ")) text = text.substring(1)
            if (prev == null && text.isBlank()) return
            if (text.isEmpty()) return
            spans.add(PolicySpan(text, bold > 0, italic > 0, code > 0, hrefs.lastOrNull { it != null }))
        }

        var i = 0
        val n = html.length
        while (i < n) {
            val lt = html.indexOf('<', i)
            if (lt < 0) {
                if (dropUntil == null) addText(html.substring(i))
                break
            }
            if (lt > i && dropUntil == null) addText(html.substring(i, lt))
            if (html.startsWith("<!--", lt)) {
                val end = html.indexOf("-->", lt + 4)
                i = if (end < 0) n else end + 3
                continue
            }
            val gt = html.indexOf('>', lt + 1)
            if (gt < 0) {
                // A stray "<": treat the rest as text.
                if (dropUntil == null) addText(html.substring(lt))
                break
            }
            val inner = html.substring(lt + 1, gt).trim()
            i = gt + 1
            if (inner.isEmpty() || inner[0] == '!' || inner[0] == '?') continue
            val closing = inner[0] == '/'
            val body = if (closing) inner.substring(1).trim() else inner
            val name = body.takeWhile { !it.isWhitespace() && it != '/' }.lowercase()
            if (name.isEmpty()) continue
            if (dropUntil != null) {
                if (closing && name == dropUntil) dropUntil = null
                continue
            }
            if (!closing && name in DROP_TAGS && !body.endsWith("/")) {
                dropUntil = name
                continue
            }
            if (closing) {
                when (name) {
                    "strong", "b" -> if (bold > 0) bold--
                    "em", "i" -> if (italic > 0) italic--
                    "code", "kbd", "samp", "tt" -> if (code > 0) code--
                    "a" -> if (hrefs.isNotEmpty()) hrefs.removeAt(hrefs.size - 1)
                    "ul", "ol" -> {
                        flush()
                        if (lists.isNotEmpty()) lists.removeAt(lists.size - 1)
                    }
                    "blockquote" -> {
                        flush()
                        if (quote > 0) quote--
                    }
                    else -> if (name in BLOCK_TAGS) flush()
                }
                continue
            }
            when (name) {
                "strong", "b" -> bold++
                "em", "i" -> italic++
                "code", "kbd", "samp", "tt" -> code++
                "a" -> hrefs.add(safeHref(attribute(body, "href")))
                "br" -> {
                    spans.add(PolicySpan("\n", bold > 0, italic > 0, code > 0, null))
                }
                "hr" -> {
                    flush()
                    blocks.add(PolicyBlock(PolicyBlock.Kind.RULE, emptyList()))
                }
                "ul", "ol" -> {
                    flush()
                    lists.add(intArrayOf(if (name == "ol") 1 else 0, 0))
                }
                "li" -> {
                    flush()
                    val top = lists.lastOrNull()
                    kind = PolicyBlock.Kind.LIST_ITEM
                    depth = (lists.size - 1).coerceAtLeast(0)
                    if (top != null && top[0] == 1) {
                        top[1] = top[1] + 1
                        marker = "${top[1]}."
                    } else {
                        marker = "•"
                    }
                }
                "h1", "h2", "h3", "h4", "h5", "h6" -> {
                    flush()
                    kind = PolicyBlock.Kind.HEADING
                    level = name[1] - '0'
                }
                "blockquote" -> {
                    flush()
                    quote++
                }
                else -> if (name in BLOCK_TAGS) flush()
            }
        }
        flush()
        return blocks
    }

    /** Plain text of a document, one block per line (used for accessibility and tests). */
    fun plainText(html: String): String = parse(html).joinToString("\n") { b ->
        (if (b.marker != null) b.marker + " " else "") + b.text
    }

    /** The document HTML for a policy: the sanitised HTML, else its markdown rendered safely. */
    fun htmlFor(policy: AtlasPolicy): String =
        policy.contentHtml ?: policy.contentMarkdown?.let { PolicyMarkdown.toSafeHtml(it) } ?: ""

    private fun attribute(tag: String, name: String): String? {
        val m = Regex("""(?i)\b$name\s*=\s*("([^"]*)"|'([^']*)'|([^\s"'>]+))""").find(tag) ?: return null
        return decodeEntities(m.groupValues[2].ifEmpty { m.groupValues[3].ifEmpty { m.groupValues[4] } })
    }

    private fun safeHref(href: String?): String? {
        val h = href?.trim() ?: return null
        val lower = h.lowercase()
        return if (lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("mailto:")) h else null
    }

    internal fun decodeEntities(s: String): String {
        if (!s.contains('&')) return s
        return Regex("&(#x[0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);").replace(s) { m ->
            val e = m.groupValues[1]
            when {
                e.startsWith("#x") || e.startsWith("#X") -> e.substring(2).toIntOrNull(16)?.let(::codePoint) ?: m.value
                e.startsWith("#") -> e.substring(1).toIntOrNull()?.let(::codePoint) ?: m.value
                else -> when (e.lowercase()) {
                    "amp" -> "&"
                    "lt" -> "<"
                    "gt" -> ">"
                    "quot" -> "\""
                    "apos" -> "'"
                    "nbsp" -> " "
                    "ndash" -> "–"
                    "mdash" -> "—"
                    "hellip" -> "…"
                    "rsquo" -> "’"
                    "lsquo" -> "‘"
                    "rdquo" -> "”"
                    "ldquo" -> "“"
                    "copy" -> "©"
                    else -> m.value
                }
            }
        }
    }

    private fun codePoint(cp: Int): String =
        if (cp in 1..0x10FFFF && cp !in 0xD800..0xDFFF) String(Character.toChars(cp)) else "�"
}

/**
 * A small, safe markdown renderer (headings, paragraphs, lists, bold, italics,
 * code, http(s) links) for a policy delivered as markdown only. Every character
 * of input is HTML-escaped first, so it can never inject markup.
 */
object PolicyMarkdown {
    fun toSafeHtml(markdown: String): String {
        val lines = markdown.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val html = ArrayList<String>()
        val para = ArrayList<String>()
        var list: String? = null
        fun flushPara() {
            if (para.isNotEmpty()) html.add("<p>${inline(para.joinToString(" "))}</p>")
            para.clear()
        }
        fun closeList() {
            list?.let { html.add("</$it>") }
            list = null
        }
        val heading = Regex("^(#{1,6})\\s+(.*)$")
        val bullet = Regex("^\\s*[-*+]\\s+(.*)$")
        val numbered = Regex("^\\s*\\d+[.)]\\s+(.*)$")
        for (line in lines) {
            val h = heading.find(line)
            val b = bullet.find(line)
            val num = numbered.find(line)
            when {
                h != null -> {
                    flushPara(); closeList()
                    val lv = h.groupValues[1].length
                    html.add("<h$lv>${inline(h.groupValues[2])}</h$lv>")
                }
                b != null || num != null -> {
                    flushPara()
                    val kind = if (b != null) "ul" else "ol"
                    if (list != kind) {
                        closeList()
                        html.add("<$kind>")
                        list = kind
                    }
                    html.add("<li>${inline((b ?: num)!!.groupValues[1])}</li>")
                }
                line.isBlank() -> { flushPara(); closeList() }
                else -> { closeList(); para.add(line.trim()) }
            }
        }
        flushPara(); closeList()
        return html.joinToString("\n")
    }

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&#39;")

    private fun inline(text: String): String {
        var out = escape(text)
        out = Regex("`([^`]+)`").replace(out) { "<code>${it.groupValues[1]}</code>" }
        out = Regex("\\*\\*([^*]+)\\*\\*").replace(out) { "<strong>${it.groupValues[1]}</strong>" }
        out = Regex("(^|[^*])\\*([^*\\s][^*]*)\\*").replace(out) { "${it.groupValues[1]}<em>${it.groupValues[2]}</em>" }
        out = Regex("\\[([^\\]]+)\\]\\((https?://[^\\s)]+)\\)").replace(out) {
            "<a href=\"${it.groupValues[2]}\">${it.groupValues[1]}</a>"
        }
        return out
    }
}
