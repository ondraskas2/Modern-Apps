package com.vayunmathur.office.util

import com.vayunmathur.library.ui.odf.OdfContentBlock
import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.library.ui.odf.OdfParagraph
import com.vayunmathur.library.ui.odf.OdfSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextDocCodecTest {

    private fun textDoc(vararg paras: String) = OdfDocument.TextDocument(
        title = "t",
        content = paras.map { OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan(it)))) }
    )

    private fun paraTexts(doc: OdfDocument.TextDocument): List<String> =
        doc.content.mapNotNull { (it as? OdfContentBlock.Paragraph)?.paragraph?.spans?.joinToString("") { s -> s.text } }

    @Test
    fun roundtrip_preserves_paragraphs_and_bold() {
        val doc = OdfDocument.TextDocument(
            title = "t",
            content = listOf(
                OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan("Hello ", bold = true), OdfSpan("world")))),
                OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan("second"))))
            )
        )
        val rebuilt = TextDocCodec.fromCells(TextDocCodec.toCells(doc), doc)
        assertEquals(listOf("Hello world", "second"), paraTexts(rebuilt))
        val firstSpans = (rebuilt.content[0] as OdfContentBlock.Paragraph).paragraph.spans
        assertTrue(firstSpans.first { it.text.startsWith("Hello") }.bold)
        assertTrue(!firstSpans.first { it.text == "world" }.bold)
    }

    @Test
    fun concurrent_edits_same_paragraph_merge_without_duplication() {
        val base = textDoc("Hello World")
        val a = DocumentCrdt("A")
        val baseOps = a.update(TextDocCodec.toCells(base))
        val b = DocumentCrdt("B")
        b.apply(baseOps)
        // A inserts "Brave " mid-paragraph; B appends "!" — both within the SAME paragraph.
        val opsA = a.update(TextDocCodec.toCells(textDoc("Hello Brave World")))
        val opsB = b.update(TextDocCodec.toCells(textDoc("Hello World!")))
        a.apply(opsB); b.apply(opsA)
        assertEquals(a.render(), b.render())
        val merged = TextDocCodec.fromCells(a.render(), base)
        val text = paraTexts(merged).joinToString("\u0000")
        assertTrue("has A's edit", text.contains("Brave"))
        assertTrue("has B's edit", text.contains("!"))
        assertEquals("still a single paragraph (char-level merge, no duplication)", 1, merged.content.size)
    }

    @Test
    fun concurrent_new_paragraphs_merge() {
        val base = textDoc("intro")
        val a = DocumentCrdt("A")
        val baseOps = a.update(TextDocCodec.toCells(base))
        val b = DocumentCrdt("B")
        b.apply(baseOps)
        val opsA = a.update(TextDocCodec.toCells(textDoc("intro", "from-A")))
        val opsB = b.update(TextDocCodec.toCells(textDoc("intro", "from-B")))
        a.apply(opsB); b.apply(opsA)
        assertEquals(a.render(), b.render())
        val merged = paraTexts(TextDocCodec.fromCells(a.render(), base))
        assertTrue(merged.contains("from-A"))
        assertTrue(merged.contains("from-B"))
        assertTrue(merged.contains("intro"))
    }
}
