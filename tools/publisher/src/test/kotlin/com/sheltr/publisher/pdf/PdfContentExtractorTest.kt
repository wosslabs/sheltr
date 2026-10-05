package com.sheltr.publisher.pdf

import com.sheltr.publisher.text.PageText
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PdfContentExtractorTest {

    @Test
    fun extractsTextPerPageFromARealPdf() {
        val pdfBytes = buildTestPdf()

        val extractor = PdfContentExtractor()
        val pages: List<PageText> = extractor.extract(pdfBytes)

        assertEquals(2, pages.size)
        assertEquals(1, pages[0].pageNumber)
        assertTrue(pages[0].text.contains("Boil water for one minute."))
        assertEquals(2, pages[1].pageNumber)
        assertTrue(pages[1].text.contains("Evacuate immediately if instructed."))
    }

    private fun buildTestPdf(): ByteArray {
        PDDocument().use { document ->
            addPageWithText(document, "Boil water for one minute.")
            addPageWithText(document, "Evacuate immediately if instructed.")

            val out = ByteArrayOutputStream()
            document.save(out)
            return out.toByteArray()
        }
    }

    private fun addPageWithText(document: PDDocument, text: String) {
        val page = PDPage()
        document.addPage(page)
        PDPageContentStream(document, page).use { stream ->
            stream.beginText()
            stream.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 12f)
            stream.newLineAtOffset(50f, 700f)
            stream.showText(text)
            stream.endText()
        }
    }
}
