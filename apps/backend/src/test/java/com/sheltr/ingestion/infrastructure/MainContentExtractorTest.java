package com.sheltr.ingestion.infrastructure;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class MainContentExtractorTest {

    private final MainContentExtractor extractor = new MainContentExtractor();

    private static String fixture(String name) throws IOException {
        try (InputStream in = MainContentExtractorTest.class.getResourceAsStream("/html/" + name)) {
            assertThat(in).as("fixture %s", name).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void prefersMainAndDropsChromeAndCookieBanner() throws Exception {
        String text = extractor.extract(fixture("guide-with-chrome.html"));

        assertThat(text).contains("Prepare for floods");
        assertThat(text).contains("Move valuables and important papers to an upper floor.");
        assertThat(text).contains("Sign up to flood warnings from the Environment Agency.");
        assertThat(text).doesNotContain("Home");
        assertThat(text).doesNotContain("Footer links");
        assertThat(text).doesNotContain("Site header text");
        assertThat(text).doesNotContain("We use cookies");
        assertThat(text).doesNotContain("tracking");
        assertThat(text).doesNotContain("window.dataLayer");
        assertThat(text).doesNotContain("Enable JavaScript");
    }

    @Test
    void fallsBackToArticleWhenNoMain() throws Exception {
        String text = extractor.extract(fixture("article-page.html"));

        assertThat(text).isEqualTo("Winter storms\nCheck the forecast before you travel.");
    }

    @Test
    void fallsBackToBodyAndDropsNavAndFooter() throws Exception {
        String text = extractor.extract(fixture("body-only-page.html"));

        assertThat(text).isEqualTo("Keep a torch and spare batteries at home.");
    }

    @Test
    void outputHasNoWhitespaceRunsOrBlankLines() throws Exception {
        String text = extractor.extract(fixture("guide-with-chrome.html"));

        assertThat(text).doesNotContainPattern("[ \\t]{2,}");
        assertThat(text).doesNotContainPattern("\\n\\s*\\n");
        assertThat(text).doesNotStartWith(" ").doesNotEndWith(" ");
        assertThat(text).doesNotStartWith("\n").doesNotEndWith("\n");
    }

    @Test
    void isStableForSameInputAndIgnoresSourceFormatting() throws Exception {
        String html = fixture("guide-with-chrome.html");
        String reformatted = html.replace("\n", "\n\n   ").replace("   ", "\t \t");

        String first = extractor.extract(html);

        assertThat(extractor.extract(html)).isEqualTo(first);
        assertThat(extractor.extract(reformatted)).isEqualTo(first);
    }

    @Test
    void returnsEmptyStringWhenNoTextContent() {
        assertThat(extractor.extract("<html><body><nav>Only navigation</nav></body></html>")).isEmpty();
    }

    @Test
    void dropsCookieNavAndHeaderInsideMainAndArticle() throws Exception {
        String text = extractor.extract(fixture("chrome-inside-main.html"));

        assertThat(text).contains("Prepare for floods").contains("Move valuables to an upper floor.");
        assertThat(text).contains("Article paragraph kept.");
        assertThat(text).doesNotContain("We use cookies to track you.");
        assertThat(text).doesNotContain("Accept all cookies.");
        assertThat(text).doesNotContain("Cookie settings.");
        assertThat(text).doesNotContain("Site header inside main.");
        assertThat(text).doesNotContain("Article header.");
        assertThat(text).doesNotContain("Main navigation links.");
        assertThat(text).doesNotContain("Related links.");
    }

    @Test
    void dropsCookieNavAndHeaderInsideArticle() throws Exception {
        String text = extractor.extract(fixture("chrome-inside-article.html"));

        assertThat(text).isEqualTo("Winter storms\nCheck the forecast before you travel.");
    }

    @Test
    void fallsThroughToArticleWhenMainHasNoTextAfterCleaning() {
        String text = extractor.extract("<html><body>"
                + "<main><nav>Menu only</nav></main>"
                + "<article><p>Article text</p></article>"
                + "</body></html>");

        assertThat(text).isEqualTo("Article text");
    }

    @Test
    void fallsThroughToBodyWhenMainAndArticleHaveNoTextAfterCleaning() {
        String text = extractor.extract("<html><body>"
                + "<main><nav>Menu only</nav></main>"
                + "<article><header>Site header</header></article>"
                + "<p>Body text</p>"
                + "</body></html>");

        assertThat(text).isEqualTo("Body text");
    }

    @Test
    void cookieClassedBodyAndMainAreNotRemovedThemselves() {
        assertThat(extractor.extract("<html><body class=\"cookie-page\"><p>Body text</p></body></html>"))
                .isEqualTo("Body text");
        assertThat(extractor.extract("<html><body><main class=\"cookie-wall\"><p>Kept text</p></main></body></html>"))
                .isEqualTo("Kept text");
    }
}
