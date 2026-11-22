package com.wikiaioffline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZimImageTest {
    @Test fun resolvesInternalReferencesWithoutLosingEncoding() {
        val paths = localZimImagePaths("A/ciências/Fotossíntese", "../../I/Diagrama%20A+B.svg?rev=2#detalhe")
        assertEquals(listOf("I/Diagrama A+B.svg", "I/Diagrama%20A+B.svg"), paths)
        assertEquals(listOf("media/célula.png", "media/c%C3%A9lula.png"),
            localZimImagePaths("articles/cell.html", "/media/c%C3%A9lula.png"))
    }

    @Test fun skipsExternalAndInvalidReferences() {
        listOf(
            "http://example.invalid/x.png", "//example.invalid/x.png", "data:image/png;base64,AAAA",
            "%2f%2fexample.invalid/x.png", "%68ttps://example.invalid/x.png",
            "%2E%2E/%2E%2E/x.png", "../../x.png", "#fragmento", "x%00.png"
        ).forEach { ref ->
            assertTrue(ref, localZimImagePaths("articles/teste.html", ref).isEmpty())
        }
        assertFalse(validZimImagePath("articles/../x.png"))
    }

    @Test fun retainsLocalMathGlyphsAndRemovesActiveSvgContent() {
        val source = """
            <?xml version="1.0"?>
            <!DOCTYPE svg SYSTEM "https://example.invalid/file.dtd">
            <svg viewBox="0 0 100 30" width="2em" onload="fixture-marker" xmlns:xlink="http://www.w3.org/1999/xlink">
              <title>x²</title>
              <defs>
                <path id="glyph" d="M 0 5 L 10 5 L 10 10 Z"/>
                <linearGradient id="color"><stop offset="0" stop-color="#aa0000"/></linearGradient>
                <g id="cycle"><use xlink:href="#cycle"/></g>
              </defs>
              <use href="#glyph"/>
              <rect width="8" height="6" fill="url(#color)" style="stroke: #123456; stroke-width: 1"/>
              <use xlink:href="https://example.invalid/remote.svg#glyph"/>
              <image href="https://example.invalid/hidden.png"/>
              <path fill="url(https://example.invalid/colors.svg#x)" d="M 0 0 Z"/>
              <script>fixture-marker</script>
              <foreignObject><iframe src="https://example.invalid/frame"></iframe></foreignObject>
            </svg>
        """.trimIndent()
        val safe = sanitizeSvg(source.toByteArray()).toString(Charsets.UTF_8)
        assertTrue(safe, safe.contains("viewBox=\"0 0 100 30\""))
        assertTrue(safe, safe.contains("<title>x²</title>"))
        assertTrue(safe, safe.contains("xlink:href=\"#glyph\""))
        assertTrue(safe, safe.contains("<linearGradient"))
        assertTrue(safe, safe.contains("fill=\"url(#color)\""))
        assertTrue(safe, safe.contains("stroke=\"#123456\""))
        for (unsafe in listOf("example.invalid", "onload", "<script", "fixture-marker", "<foreignObject", "DOCTYPE", "href=\"#cycle\"")) {
            assertFalse(safe, unsafe in safe)
        }
    }
}
