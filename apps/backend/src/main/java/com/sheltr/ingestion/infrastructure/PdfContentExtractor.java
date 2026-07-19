package com.sheltr.ingestion.infrastructure;

import com.sheltr.ingestion.domain.PageText;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class PdfContentExtractor {

    public List<PageText> extract(byte[] pdfBytes) {
        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            List<PageText> pages = new ArrayList<>();
            PDFTextStripper stripper = new PDFTextStripper();
            int totalPages = document.getNumberOfPages();
            for (int i = 1; i <= totalPages; i++) {
                stripper.setStartPage(i);
                stripper.setEndPage(i);
                String text = stripper.getText(document);
                pages.add(new PageText(i, text));
            }
            return pages;
        } catch (IOException e) {
            throw new RuntimeException("Failed to extract PDF content", e);
        }
    }
}
