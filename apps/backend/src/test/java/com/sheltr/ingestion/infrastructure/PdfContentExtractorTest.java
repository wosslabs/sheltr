package com.sheltr.ingestion.infrastructure;

import com.sheltr.ingestion.domain.PageText;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PdfContentExtractorTest {

    @Test
    void extractsTextPerPageFromARealPdf() throws IOException {
        byte[] pdfBytes = buildTestPdf();

        PdfContentExtractor extractor = new PdfContentExtractor();
        List<PageText> pages = extractor.extract(pdfBytes);

        assertThat(pages).hasSize(2);
        assertThat(pages.get(0).pageNumber()).isEqualTo(1);
        assertThat(pages.get(0).text()).contains("Boil water for one minute.");
        assertThat(pages.get(1).pageNumber()).isEqualTo(2);
        assertThat(pages.get(1).text()).contains("Evacuate immediately if instructed.");
    }

    private static byte[] buildTestPdf() throws IOException {
        try (PDDocument document = new PDDocument()) {
            addPageWithText(document, "Boil water for one minute.");
            addPageWithText(document, "Evacuate immediately if instructed.");

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    private static void addPageWithText(PDDocument document, String text) throws IOException {
        PDPage page = new PDPage();
        document.addPage(page);
        try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
            stream.beginText();
            stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
            stream.newLineAtOffset(50, 700);
            stream.showText(text);
            stream.endText();
        }
    }
}
