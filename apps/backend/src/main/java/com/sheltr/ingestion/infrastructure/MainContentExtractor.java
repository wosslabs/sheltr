package com.sheltr.ingestion.infrastructure;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.NodeTraversor;
import org.jsoup.select.NodeVisitor;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Extracts the main readable text of an HTML page. Selects {@code main}, else {@code article}, else
 * {@code body}, then removes navigation, header, footer, scripts, styles and cookie-consent elements inside
 * that root. Block elements become line breaks, whitespace is collapsed, and blank lines are dropped, so the
 * same HTML always yields the same text.
 */
public class MainContentExtractor {

    private static final Set<String> REMOVED_TAGS = Set.of(
            "nav", "footer", "header", "script", "style", "noscript");

    private static final Set<String> BLOCK_TAGS = Set.of(
            "address", "article", "aside", "blockquote", "br", "dd", "div", "dl", "dt", "figcaption", "figure",
            "h1", "h2", "h3", "h4", "h5", "h6", "hr", "li", "main", "ol", "p", "pre", "section", "table",
            "tbody", "thead", "tfoot", "tr", "ul");

    private static final String WHITESPACE = "[\\s\\u00A0]+";

    public String extract(String html) {
        Document document = Jsoup.parse(html);

        Element root = document.selectFirst("main");
        if (root == null) {
            root = document.selectFirst("article");
        }
        if (root == null) {
            root = document.body();
        }
        removeUnwanted(root);

        StringBuilder raw = new StringBuilder();
        NodeTraversor.traverse(new LineBuilder(raw), root);
        return normalise(raw);
    }

    private static void removeUnwanted(Element root) {
        // Collect first, then remove, so the traversal is not modified while iterating.
        List<Element> unwanted = root.select("*").stream()
                .filter(MainContentExtractor::isUnwanted)
                .toList();
        unwanted.forEach(Element::remove);
    }

    private static boolean isUnwanted(Element element) {
        if (REMOVED_TAGS.contains(element.normalName())) {
            return true;
        }
        return containsCookie(element.className()) || containsCookie(element.id());
    }

    private static boolean containsCookie(String value) {
        return value.toLowerCase(Locale.ROOT).contains("cookie");
    }

    private static String normalise(CharSequence raw) {
        StringBuilder out = new StringBuilder();
        for (String line : raw.toString().split("\n")) {
            String collapsed = line.replaceAll(WHITESPACE, " ").trim();
            if (collapsed.isEmpty()) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append('\n');
            }
            out.append(collapsed);
        }
        return out.toString();
    }

    private static final class LineBuilder implements NodeVisitor {

        private final StringBuilder out;

        LineBuilder(StringBuilder out) {
            this.out = out;
        }

        @Override
        public void head(Node node, int depth) {
            if (node instanceof TextNode textNode) {
                out.append(textNode.text().replaceAll(WHITESPACE, " "));
            } else if (node instanceof Element element && isBlock(element)) {
                out.append('\n');
            }
        }

        @Override
        public void tail(Node node, int depth) {
            if (node instanceof Element element) {
                if (isBlock(element)) {
                    out.append('\n');
                } else if (isCell(element)) {
                    out.append(' ');
                }
            }
        }

        private static boolean isBlock(Element element) {
            return BLOCK_TAGS.contains(element.normalName());
        }

        private static boolean isCell(Element element) {
            String name = element.normalName();
            return name.equals("td") || name.equals("th");
        }
    }
}
