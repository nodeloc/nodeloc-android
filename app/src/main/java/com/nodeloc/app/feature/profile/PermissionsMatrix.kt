package com.nodeloc.app.feature.profile

/**
 * 用户组与权限, read from the site's own `/permissions` page.
 *
 * There is no JSON for it: the comparison is computed server-side from the
 * live permission table and site settings (web/permissions.go) and only ever
 * rendered as HTML — the admin JSON is admin-only. The page is translated
 * before it is served, so its words are already in the reader's language and
 * are shown as they come rather than re-translated here.
 *
 * Only the narrow-screen markup (`.pm-m`) is read: it is the one-column-at-a-
 * time layout the app draws, and it carries per column what the wide table
 * leaves to its header (the "比X多 N 项" line, the 新 marks).
 */
data class PermMatrix(val tiers: PermTable, val roles: PermTable?)

data class PermTable(
    /** The reader's own column; a guest's is the guest column. */
    val mine: Int,
    val columns: List<PermColumn>,
    val intro: String,
    val groups: List<PermGroup>,
)

data class PermColumn(
    val index: Int,
    val name: String,
    /** The glyph's path on a 32 grid, evenodd — tier glyphs and role glyphs alike. */
    val iconPath: String?,
    /** "800 声望 · 比铂金多 4 项". */
    val sub: String,
    /** "你的段位" / "你的身份" when this column is the reader's. */
    val mineLabel: String?,
)

data class PermGroup(val title: String, val rows: List<PermRow>)

data class PermRow(
    val key: String,
    val label: String,
    val note: String?,
    /** Column index → the "新" mark's text, for columns where this row opens up or widens. */
    val fresh: Map<Int, String>,
    val values: Map<Int, PermValue>,
)

sealed interface PermValue {
    data object Yes : PermValue
    data object No : PermValue
    data class Text(val text: String) : PermValue
}

object PermissionsHtml {

    /** Null when the page has no `.pm-m` block: the markup moved, and a blank table would hide that. */
    fun parse(html: String): PermMatrix? {
        val root = MiniHtml.parse(html)
        val tables = root.findAll { it.hasClass("pm-m") }.mapNotNull(::table)
        val tiers = tables.firstOrNull() ?: return null
        return PermMatrix(tiers, tables.getOrNull(1))
    }

    private fun table(block: HtmlNode): PermTable? {
        val chips = block.findAll { it.hasClass("pm-chip") }
        val cards = block.findAll { it.hasClass("pm-card") }.associateBy { it.attrs["data-c"]?.toIntOrNull() }
        val columns = chips.mapIndexedNotNull { position, chip ->
            val index = chip.attrs["data-pm-pick"]?.toIntOrNull() ?: position
            val card = cards[index]
            PermColumn(
                index = index,
                name = chip.text { it.hasClass("pm-ic") },
                iconPath = chip.find { it.tag == "path" }?.attrs?.get("d"),
                sub = card?.find { it.hasClass("pm-card-sub") }?.text().orEmpty(),
                mineLabel = card?.find { it.hasClass("pm-mine") }?.text()?.takeIf { it.isNotEmpty() },
            )
        }
        if (columns.isEmpty()) return null
        val mine = block.attrs["data-pick"]?.toIntOrNull()
            ?: chips.indexOfFirst { it.attrs["aria-pressed"] == "true" }.takeIf { it >= 0 }
            ?: 0

        val groups = mutableListOf<PermGroup>()
        var title = ""
        var rows = mutableListOf<PermRow>()
        fun flush() {
            if (rows.isNotEmpty() || title.isNotEmpty()) groups += PermGroup(title, rows)
            rows = mutableListOf()
        }
        block.find { it.hasClass("pm-list") }?.childElements()?.forEach { child ->
            when {
                child.hasClass("pm-lgroup") -> {
                    flush()
                    title = child.text()
                }
                child.hasClass("pm-li") -> row(child)?.let { rows += it }
            }
        }
        flush()
        return PermTable(
            mine = mine,
            columns = columns,
            intro = block.find { it.hasClass("pm-m-intro") }?.text().orEmpty(),
            groups = groups.filter { it.rows.isNotEmpty() },
        )
    }

    private fun row(item: HtmlNode): PermRow? {
        val labelBox = item.find { it.hasClass("pm-li-label") } ?: return null
        val label = labelBox.childElements().firstOrNull { !it.hasClass("pm-new") }?.text()
            ?: labelBox.text { it.hasClass("pm-new") }
        val fresh = labelBox.findAll { it.hasClass("pm-new") }
            .mapNotNull { mark -> mark.attrs["data-c"]?.toIntOrNull()?.let { it to mark.text() } }
            .toMap()
        val values = item.findAll { it.hasClass("pm-lv") }.mapNotNull { cell ->
            val index = cell.attrs["data-c"]?.toIntOrNull() ?: return@mapNotNull null
            val value = when {
                cell.find { it.hasClass("pm-yes") } != null -> PermValue.Yes
                cell.find { it.hasClass("pm-no") } != null || cell.hasClass("is-no") -> PermValue.No
                // The tick and dash carry a screen-reader word beside them; plain text has none.
                else -> PermValue.Text(cell.text { it.hasClass("sr-only") })
            }
            index to value
        }.toMap()
        return PermRow(
            key = item.attrs["data-pm-row"].orEmpty(),
            label = label,
            note = item.find { it.hasClass("pm-rn") }?.text()?.takeIf { it.isNotEmpty() },
            fresh = fresh,
            values = values,
        )
    }
}

/** An element of [MiniHtml]'s tree; children are elements or text runs. */
class HtmlNode(val tag: String, val attrs: Map<String, String>) {
    val children = mutableListOf<Any>()

    fun hasClass(name: String): Boolean =
        attrs["class"]?.split(' ', '\t', '\n')?.any { it == name } == true

    fun childElements(): List<HtmlNode> = children.filterIsInstance<HtmlNode>()

    fun find(predicate: (HtmlNode) -> Boolean): HtmlNode? {
        for (child in childElements()) {
            if (predicate(child)) return child
            child.find(predicate)?.let { return it }
        }
        return null
    }

    fun findAll(predicate: (HtmlNode) -> Boolean): List<HtmlNode> {
        val out = mutableListOf<HtmlNode>()
        fun walk(node: HtmlNode) {
            node.childElements().forEach {
                if (predicate(it)) out += it
                walk(it)
            }
        }
        walk(this)
        return out
    }

    /** Visible text, whitespace collapsed as a browser would, leaving out subtrees [skip] matches. */
    fun text(skip: (HtmlNode) -> Boolean = { false }): String {
        val builder = StringBuilder()
        fun walk(node: HtmlNode) {
            node.children.forEach {
                when (it) {
                    is String -> builder.append(it)
                    is HtmlNode -> if (!skip(it)) walk(it)
                }
            }
        }
        walk(this)
        return builder.toString().replace(WHITESPACE, " ").trim()
    }

    private companion object {
        val WHITESPACE = Regex("\\s+")
    }
}

/**
 * A small, forgiving HTML tree builder — enough for the server's own,
 * well-formed template output. The post parser in core/html is built for
 * post bodies and their quirks, and a full HTML library for one page is not
 * worth the dependency.
 */
object MiniHtml {
    private val VOID = setOf(
        "area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "source", "track", "wbr",
    )
    private val RAW = setOf("script", "style", "textarea", "title")

    fun parse(html: String): HtmlNode {
        val root = HtmlNode("#root", emptyMap())
        val stack = ArrayDeque<HtmlNode>().apply { addLast(root) }
        var i = 0
        val n = html.length
        while (i < n) {
            val c = html[i]
            if (c != '<') {
                val end = html.indexOf('<', i).let { if (it < 0) n else it }
                stack.last().children += decode(html.substring(i, end))
                i = end
                continue
            }
            when {
                html.startsWith("<!--", i) -> {
                    val end = html.indexOf("-->", i + 4)
                    i = if (end < 0) n else end + 3
                }
                html.startsWith("</", i) -> {
                    val end = html.indexOf('>', i).let { if (it < 0) n else it }
                    val name = html.substring(i + 2, end).trim().lowercase()
                    val at = stack.indexOfLast { it.tag == name }
                    if (at > 0) while (stack.size > at) stack.removeLast()
                    i = end + 1
                }
                i + 1 < n && (html[i + 1] == '!' || html[i + 1] == '?') -> {
                    val end = html.indexOf('>', i)
                    i = if (end < 0) n else end + 1
                }
                i + 1 < n && html[i + 1].isLetter() -> i = startTag(html, i, stack)
                else -> {
                    stack.last().children += "<"
                    i++
                }
            }
        }
        return root
    }

    private fun startTag(html: String, from: Int, stack: ArrayDeque<HtmlNode>): Int {
        val n = html.length
        var i = from + 1
        val nameStart = i
        while (i < n && !html[i].isWhitespace() && html[i] != '>' && html[i] != '/') i++
        val name = html.substring(nameStart, i).lowercase()
        val attrs = LinkedHashMap<String, String>()
        var selfClosing = false
        while (i < n) {
            while (i < n && html[i].isWhitespace()) i++
            if (i >= n) break
            if (html[i] == '>') { i++; break }
            if (html[i] == '/') {
                selfClosing = true
                i++
                continue
            }
            val attrStart = i
            while (i < n && !html[i].isWhitespace() && html[i] != '=' && html[i] != '>' && html[i] != '/') i++
            val attr = html.substring(attrStart, i).lowercase()
            while (i < n && html[i].isWhitespace()) i++
            var value = ""
            if (i < n && html[i] == '=') {
                i++
                while (i < n && html[i].isWhitespace()) i++
                if (i < n && (html[i] == '"' || html[i] == '\'')) {
                    val quote = html[i]
                    val end = html.indexOf(quote, i + 1).let { if (it < 0) n else it }
                    value = html.substring(i + 1, end)
                    i = end + 1
                } else {
                    val valueStart = i
                    while (i < n && !html[i].isWhitespace() && html[i] != '>') i++
                    value = html.substring(valueStart, i)
                }
            }
            if (attr.isNotEmpty()) attrs[attr] = decode(value)
        }
        val node = HtmlNode(name, attrs)
        stack.last().children += node
        if (name in RAW && !selfClosing) {
            val close = html.indexOf("</$name", i, ignoreCase = true).let { if (it < 0) n else it }
            if (name == "title" || name == "textarea") node.children += decode(html.substring(i, close))
            val end = html.indexOf('>', close).let { if (it < 0) n else it }
            return end + 1
        }
        if (!selfClosing && name !in VOID) stack.addLast(node)
        return i
    }

    private val ENTITY = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);")

    fun decode(text: String): String {
        if ('&' !in text) return text
        return ENTITY.replace(text) { match ->
            val body = match.groupValues[1]
            when {
                body.startsWith("#x") || body.startsWith("#X") ->
                    body.substring(2).toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: match.value
                body.startsWith("#") -> body.substring(1).toIntOrNull()?.let { String(Character.toChars(it)) } ?: match.value
                else -> when (body) {
                    "amp" -> "&"
                    "lt" -> "<"
                    "gt" -> ">"
                    "quot" -> "\""
                    "apos" -> "'"
                    "nbsp" -> " "
                    else -> match.value
                }
            }
        }
    }
}
