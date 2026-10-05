package com.sheltr.publisher.pdf

import com.sheltr.publisher.text.PageText
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import java.io.IOException

/** Extracts text from a PDF with PDFBox, one [PageText] per page, numbered from 1. */
class PdfContentExtractor {

    fun extract(pdfBytes: ByteArray): List<PageText> {
        try {
            Loader.loadPDF(pdfBytes).use { document ->
                val pages = mutableListOf<PageText>()
                val stripper = PDFTextStripper()
                val totalPages = document.numberOfPages
                for (i in 1..totalPages) {
                    stripper.startPage = i
                    stripper.endPage = i
                    val text = stripper.getText(document)
                    pages.add(PageText(i, text))
                }
                return pages
            }
        } catch (e: IOException) {
            throw RuntimeException("Failed to extract PDF content", e)
        }
    }
}
