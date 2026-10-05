package com.sheltr.publisher.html

import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class MainContentExtractorTest {

    private val extractor = MainContentExtractor()

    private fun fixture(name: String): String {
        val stream = MainContentExtractorTest::class.java.getResourceAsStream("/html/$name")
        assertNotNull(stream, "fixture $name")
        return stream.use { String(it.readAllBytes(), StandardCharsets.UTF_8) }
    }

    @Test
    fun prefersMainAndDropsChromeAndCookieBanner() {
        val text = extractor.extract(fixture("guide-with-chrome.html"))

        assertContains(text, "Prepare for floods")
        assertContains(text, "Move valuables and important papers to an upper floor.")
        assertContains(text, "Sign up to flood warnings from the Environment Agency.")
        assertFalse(text.contains("Home"))
        assertFalse(text.contains("Footer links"))
        assertFalse(text.contains("Site header text"))
        assertFalse(text.contains("We use cookies"))
        assertFalse(text.contains("tracking"))
        assertFalse(text.contains("window.dataLayer"))
        assertFalse(text.contains("Enable JavaScript"))
    }

    @Test
    fun fallsBackToArticleWhenNoMain() {
        val text = extractor.extract(fixture("article-page.html"))

        assertEquals("Winter storms\nCheck the forecast before you travel.", text)
    }

    @Test
    fun fallsBackToBodyAndDropsNavAndFooter() {
        val text = extractor.extract(fixture("body-only-page.html"))

        assertEquals("Keep a torch and spare batteries at home.", text)
    }

    @Test
    fun outputHasNoWhitespaceRunsOrBlankLines() {
        val text = extractor.extract(fixture("guide-with-chrome.html"))

        assertFalse(Regex("[ \\t]{2,}").containsMatchIn(text))
        assertFalse(Regex("\\n\\s*\\n").containsMatchIn(text))
        assertFalse(text.startsWith(" ") || text.endsWith(" "))
        assertFalse(text.startsWith("\n") || text.endsWith("\n"))
    }

    @Test
    fun isStableForSameInputAndIgnoresSourceFormatting() {
        val html = fixture("guide-with-chrome.html")
        val reformatted = html.replace("\n", "\n\n   ").replace("   ", "\t \t")

        val first = extractor.extract(html)

        assertEquals(first, extractor.extract(html))
        assertEquals(first, extractor.extract(reformatted))
    }

    @Test
    fun returnsEmptyStringWhenNoTextContent() {
        assertEquals("", extractor.extract("<html><body><nav>Only navigation</nav></body></html>"))
    }

    @Test
    fun dropsCookieNavAndHeaderInsideMainAndArticle() {
        val text = extractor.extract(fixture("chrome-inside-main.html"))

        assertContains(text, "Prepare for floods")
        assertContains(text, "Move valuables to an upper floor.")
        assertContains(text, "Article paragraph kept.")
        assertFalse(text.contains("We use cookies to track you."))
        assertFalse(text.contains("Accept all cookies."))
        assertFalse(text.contains("Cookie settings."))
        assertFalse(text.contains("Site header inside main."))
        assertFalse(text.contains("Article header."))
        assertFalse(text.contains("Main navigation links."))
        assertFalse(text.contains("Related links."))
    }

    @Test
    fun dropsCookieNavAndHeaderInsideArticle() {
        val text = extractor.extract(fixture("chrome-inside-article.html"))

        assertEquals("Winter storms\nCheck the forecast before you travel.", text)
    }

    @Test
    fun fallsThroughToArticleWhenMainHasNoTextAfterCleaning() {
        val text = extractor.extract(
            "<html><body>" +
                "<main><nav>Menu only</nav></main>" +
                "<article><p>Article text</p></article>" +
                "</body></html>",
        )

        assertEquals("Article text", text)
    }

    @Test
    fun fallsThroughToBodyWhenMainAndArticleHaveNoTextAfterCleaning() {
        val text = extractor.extract(
            "<html><body>" +
                "<main><nav>Menu only</nav></main>" +
                "<article><header>Site header</header></article>" +
                "<p>Body text</p>" +
                "</body></html>",
        )

        assertEquals("Body text", text)
    }

    @Test
    fun cookieClassedBodyAndMainAreNotRemovedThemselves() {
        assertEquals(
            "Body text",
            extractor.extract("<html><body class=\"cookie-page\"><p>Body text</p></body></html>"),
        )
        assertEquals(
            "Kept text",
            extractor.extract("<html><body><main class=\"cookie-wall\"><p>Kept text</p></main></body></html>"),
        )
    }
}
