package com.sheltr.publisher.html

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.select.NodeTraversor
import org.jsoup.select.NodeVisitor

/**
 * Extracts the main readable text of an HTML page. The first of `main`, `article` and `body` whose text is
 * non-empty after cleaning is used; a candidate that is missing or empty is skipped. Within the chosen root,
 * descendants that are navigation, header, footer, scripts, styles or cookie-consent elements are removed. The
 * root itself is never removed. Block elements become line breaks, whitespace is collapsed, and blank lines are
 * dropped, so the same HTML always yields the same text. Returns an empty string when no candidate has text.
 */
class MainContentExtractor {

    fun extract(html: String): String {
        val document = Jsoup.parse(html)
        val candidates = listOfNotNull(document.selectFirst("main"), document.selectFirst("article"), document.body())
        for (candidate in candidates) {
            // Clean a copy so a candidate that turns out empty leaves the document intact for the next one.
            val text = textOf(candidate.clone())
            if (text.isNotEmpty()) {
                return text
            }
        }
        return ""
    }

    private fun textOf(root: Element): String {
        removeUnwanted(root)
        val raw = StringBuilder()
        NodeTraversor.traverse(LineBuilder(raw), root)
        return normalise(raw)
    }

    private fun removeUnwanted(root: Element) {
        // Descendants only. select("*") includes the root itself, so the root is excluded explicitly: a
        // cookie-classed <body> or <main> must not blank the whole page. Collect first, then remove, so the
        // traversal is not modified while iterating.
        val unwanted = root.select("*")
            .filter { it !== root }
            .filter { isUnwanted(it) }
        unwanted.forEach { it.remove() }
    }

    private fun normalise(raw: CharSequence): String {
        val out = StringBuilder()
        for (line in raw.toString().split("\n")) {
            // Same predicate as java.lang.String.trim(): every char <= ' '.
            val collapsed = HORIZONTAL_WHITESPACE.replace(line, " ").trim { it <= ' ' }
            if (collapsed.isEmpty()) {
                continue
            }
            if (out.isNotEmpty()) {
                out.append('\n')
            }
            out.append(collapsed)
        }
        return out.toString()
    }

    private class LineBuilder(private val out: StringBuilder) : NodeVisitor {

        override fun head(node: Node, depth: Int) {
            if (node is TextNode) {
                out.append(HORIZONTAL_WHITESPACE.replace(node.text(), " "))
            } else if (node is Element && isBlock(node)) {
                out.append('\n')
            }
        }

        override fun tail(node: Node, depth: Int) {
            if (node is Element) {
                if (isBlock(node)) {
                    out.append('\n')
                } else if (isCell(node)) {
                    out.append(' ')
                }
            }
        }
    }

    private companion object {
        val REMOVED_TAGS = setOf("nav", "footer", "header", "script", "style", "noscript")

        val BLOCK_TAGS = setOf(
            "address", "article", "aside", "blockquote", "br", "dd", "div", "dl", "dt", "figcaption", "figure",
            "h1", "h2", "h3", "h4", "h5", "h6", "hr", "li", "main", "ol", "p", "pre", "section", "table",
            "tbody", "thead", "tfoot", "tr", "ul",
        )

        val HORIZONTAL_WHITESPACE = Regex("[\\s\\u00A0]+")

        fun isUnwanted(element: Element): Boolean {
            if (element.normalName() in REMOVED_TAGS) {
                return true
            }
            return containsCookie(element.className()) || containsCookie(element.id())
        }

        fun containsCookie(value: String): Boolean = value.lowercase().contains("cookie")

        fun isBlock(element: Element): Boolean = element.normalName() in BLOCK_TAGS

        fun isCell(element: Element): Boolean {
            val name = element.normalName()
            return name == "td" || name == "th"
        }
    }
}
