/**
 * openCloud Android client application
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License version 2,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package eu.opencloud.android.ocnote

import org.json.JSONArray
import org.json.JSONObject

/**
 * Best-effort, read-only rendering of an .ocnote document (tiptap/ProseMirror JSON) as sanitized HTML.
 *
 * Unknown nodes are rendered through their children and unknown marks are ignored, so a newer editor's
 * content degrades to readable text instead of failing. The output is meant for a WebView with JavaScript
 * and network access disabled; it additionally carries a restrictive CSP. Every text and attribute is
 * escaped, CSS values and URLs are validated against allow-lists.
 */
object OcNoteHtmlRenderer {

    class InvalidNoteException(message: String, cause: Throwable? = null) : Exception(message, cause)

    private const val MAX_DEPTH = 64

    @Throws(InvalidNoteException::class)
    fun render(json: String, darkMode: Boolean = false): String {
        val root = try {
            JSONObject(json)
        } catch (e: Exception) {
            throw InvalidNoteException("Not a valid note", e)
        }
        if (root.optString("type") != "doc") throw InvalidNoteException("Not a note document")

        val body = StringBuilder()
        renderChildren(root, body, 0)
        return page(body.toString(), darkMode)
    }

    private fun page(body: String, darkMode: Boolean): String {
        val fg = if (darkMode) "#e6e6e6" else "#1b1b1b"
        val bg = if (darkMode) "#121212" else "#ffffff"
        val muted = if (darkMode) "#2a2a2a" else "#f0f0f0"
        val border = if (darkMode) "#555" else "#c8c8c8"
        val link = if (darkMode) "#8ab4f8" else "#1a5fb4"
        return """<!doctype html><html><head><meta charset="utf-8">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src data:; style-src 'unsafe-inline'">
<meta name="viewport" content="width=device-width, initial-scale=1">
<style>
body{margin:0;padding:16px;color:$fg;background:$bg;font-family:sans-serif;line-height:1.5;word-wrap:break-word;overflow-wrap:anywhere}
a{color:$link} blockquote{margin:8px 0;padding-left:12px;border-left:4px solid $border}
pre{background:$muted;padding:12px;overflow-x:auto;border-radius:4px} code{background:$muted;padding:1px 4px;border-radius:3px;font-family:monospace}
pre code{padding:0;background:none} hr{border:0;border-top:1px solid $border}
table{border-collapse:collapse;max-width:100%} td,th{border:1px solid $border;padding:6px 8px;vertical-align:top} th{background:$muted}
img{max-width:100%;height:auto} ul.tasks{list-style:none;padding-left:4px} ul.tasks li{margin:2px 0;display:flex;align-items:baseline} ul.tasks li>span{margin-right:8px} ul.tasks li p{margin:0}
p{margin:0 0 8px 0;min-height:1em} h1,h2,h3,h4,h5,h6{margin:16px 0 8px 0;line-height:1.25}
</style></head><body>$body</body></html>"""
    }

    private fun renderChildren(node: JSONObject, out: StringBuilder, depth: Int) {
        val content = node.optJSONArray("content") ?: return
        for (i in 0 until content.length()) {
            content.optJSONObject(i)?.let { renderNode(it, out, depth + 1) }
        }
    }

    private fun renderNode(node: JSONObject, out: StringBuilder, depth: Int) {
        if (depth > MAX_DEPTH) return
        val attrs = node.optJSONObject("attrs")
        when (node.optString("type")) {
            "text" -> renderText(node, out)
            "paragraph" -> block("p", alignStyle(attrs), node, out, depth)
            "heading" -> {
                val level = (attrs?.optInt("level", 1) ?: 1).coerceIn(1, 6)
                block("h$level", alignStyle(attrs), node, out, depth)
            }
            "bulletList" -> block("ul", "", node, out, depth)
            "orderedList" -> {
                val start = attrs?.optInt("start", 1) ?: 1
                out.append("<ol").append(if (start != 1) " start=\"$start\"" else "").append('>')
                renderChildren(node, out, depth)
                out.append("</ol>")
            }
            "listItem" -> block("li", "", node, out, depth)
            "taskList" -> {
                out.append("<ul class=\"tasks\">")
                renderChildren(node, out, depth)
                out.append("</ul>")
            }
            "taskItem" -> {
                val checked = attrs?.optBoolean("checked", false) == true
                out.append("<li><span aria-label=\"").append(if (checked) "done" else "to do").append("\">")
                    .append(if (checked) "&#9745; " else "&#9744; ").append("</span><div>")
                renderChildren(node, out, depth)
                out.append("</div></li>")
            }
            "blockquote" -> block("blockquote", "", node, out, depth)
            "codeBlock" -> {
                out.append("<pre><code>")
                renderChildren(node, out, depth)
                out.append("</code></pre>")
            }
            "horizontalRule" -> out.append("<hr>")
            "hardBreak" -> out.append("<br>")
            "image" -> renderImage(attrs, out)
            "table" -> {
                out.append("<table><tbody>")
                renderChildren(node, out, depth)
                out.append("</tbody></table>")
            }
            "tableRow" -> block("tr", "", node, out, depth)
            "tableHeader", "tableCell" -> {
                val tag = if (node.optString("type") == "tableHeader") "th" else "td"
                out.append('<').append(tag)
                attrs?.let {
                    val colspan = it.optInt("colspan", 1)
                    val rowspan = it.optInt("rowspan", 1)
                    if (colspan in 2..100) out.append(" colspan=\"$colspan\"")
                    if (rowspan in 2..100) out.append(" rowspan=\"$rowspan\"")
                }
                out.append('>')
                renderChildren(node, out, depth)
                out.append("</").append(tag).append('>')
            }
            "mention" -> {
                val label = attrs?.optString("label").orEmpty().ifEmpty { attrs?.optString("id").orEmpty() }
                out.append('@').append(escape(label))
            }
            // Unknown or wrapper nodes: keep their text
            else -> renderChildren(node, out, depth)
        }
    }

    private fun block(tag: String, style: String, node: JSONObject, out: StringBuilder, depth: Int) {
        out.append('<').append(tag)
        if (style.isNotEmpty()) out.append(" style=\"").append(style).append('"')
        out.append('>')
        renderChildren(node, out, depth)
        out.append("</").append(tag).append('>')
    }

    private fun alignStyle(attrs: JSONObject?): String {
        val align = attrs?.optString("textAlign").orEmpty()
        return if (align in setOf("left", "right", "center", "justify")) "text-align:$align" else ""
    }

    private fun renderText(node: JSONObject, out: StringBuilder) {
        val text = escape(node.optString("text"))
        val marks = node.optJSONArray("marks")
        if (marks == null || marks.length() == 0) {
            out.append(text)
            return
        }
        // Tags to open, outermost first; they are closed in reverse order so the HTML nests properly
        val inline = mutableListOf<String>()
        val css = StringBuilder()
        var href: String? = null
        for (i in 0 until marks.length()) {
            val mark = marks.optJSONObject(i) ?: continue
            val attrs = mark.optJSONObject("attrs")
            when (mark.optString("type")) {
                "bold" -> inline += "strong"
                "italic" -> inline += "em"
                "underline" -> inline += "u"
                "strike" -> inline += "s"
                "code" -> inline += "code"
                "subscript" -> inline += "sub"
                "superscript" -> inline += "sup"
                "link" -> href = safeHref(attrs?.optString("href"))
                "highlight" -> css.append("background-color:")
                    .append(safeColor(attrs?.optString("color")) ?: "#fff3a3").append(';')
                "textStyle" -> {
                    safeColor(attrs?.optString("color"))?.let { css.append("color:").append(it).append(';') }
                    safeColor(attrs?.optString("backgroundColor"))?.let {
                        css.append("background-color:").append(it).append(';')
                    }
                    safeFontSize(attrs?.optString("fontSize"))?.let {
                        css.append("font-size:").append(it).append(';')
                    }
                }
            }
        }
        val opens = mutableListOf<String>()
        val closes = mutableListOf<String>()
        if (href != null) {
            opens += "<a href=\"${escape(href)}\">"
            closes += "</a>"
        }
        if (css.isNotEmpty()) {
            opens += "<span style=\"$css\">"
            closes += "</span>"
        }
        inline.forEach { tag ->
            opens += "<$tag>"
            closes += "</$tag>"
        }
        opens.forEach { out.append(it) }
        out.append(text)
        closes.asReversed().forEach { out.append(it) }
    }

    private fun renderImage(attrs: JSONObject?, out: StringBuilder) {
        val src = attrs?.optString("src").orEmpty()
        val alt = attrs?.optString("alt").orEmpty().ifEmpty { attrs?.optString("title").orEmpty() }
        // Nothing is fetched while offline-viewing: only images embedded in the note are shown
        if (src.startsWith("data:image/", ignoreCase = true) && !src.startsWith("data:image/svg", ignoreCase = true)) {
            out.append("<img src=\"").append(escape(src)).append("\" alt=\"").append(escape(alt)).append("\">")
        } else {
            out.append("<p><em>[image").append(if (alt.isNotEmpty()) ": ${escape(alt)}" else "").append("]</em></p>")
        }
    }

    private val COLOR = Regex("^(#[0-9a-fA-F]{3,8}|[a-zA-Z]{3,20}|rgba?\\([0-9\\s,.%]{5,30}\\)|hsla?\\([0-9\\s,.%deg]{5,30}\\))$")
    private val FONT_SIZE = Regex("^[0-9]{1,3}(\\.[0-9]{1,2})?(px|pt|em|rem|%)$")

    private fun safeColor(value: String?): String? = value?.trim()?.takeIf { COLOR.matches(it) }
    private fun safeFontSize(value: String?): String? = value?.trim()?.takeIf { FONT_SIZE.matches(it) }

    private fun safeHref(value: String?): String? {
        val href = value?.trim().orEmpty()
        val lower = href.lowercase()
        return href.takeIf { lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("mailto:") }
    }

    private fun escape(text: String): String {
        val sb = StringBuilder(text.length + 16)
        for (c in text) {
            when (c) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&#39;")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }
}
