package com.sheltr.android

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element

/**
 * Guards against a translated locale silently missing a key (which would make Android fall back to the
 * default `values/strings.xml` entry at runtime -- i.e. untranslated English text shown to a Spanish/French
 * user -- rather than failing a build). Parses the three `strings.xml` files with a real XML parser and
 * compares `<string name="...">` key sets; it does not just check that the locale files exist.
 */
class StringResourceParityTest {

    private fun stringKeys(resourceFile: File): Set<String> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(resourceFile)
        val stringNodes = document.getElementsByTagName("string")
        return (0 until stringNodes.length)
            .map { (stringNodes.item(it) as Element).getAttribute("name") }
            .toSet()
    }

    @Test
    fun everyDefaultStringKeyExistsInEverySupportedLocale() {
        val resRoot = File("src/main/res")
        val defaultKeys = stringKeys(File(resRoot, "values/strings.xml"))
        val spanishKeys = stringKeys(File(resRoot, "values-es/strings.xml"))
        val frenchKeys = stringKeys(File(resRoot, "values-fr/strings.xml"))

        assertEquals("values-es/strings.xml is missing or has extra keys vs. the default", defaultKeys, spanishKeys)
        assertEquals("values-fr/strings.xml is missing or has extra keys vs. the default", defaultKeys, frenchKeys)
    }
}
