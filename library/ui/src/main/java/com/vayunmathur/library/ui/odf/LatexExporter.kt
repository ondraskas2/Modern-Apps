package com.vayunmathur.library.ui.odf

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

/**
 * Exports an [OdfDocument.TextDocument] to a LaTeX (article-class) source string. Maps headings to
 * sectioning commands, run formatting to text commands, lists to itemize/enumerate, tables to
 * tabular, and formula blocks (MathML) to display math via a compact MathML→LaTeX converter.
 */
object LatexExporter {

    fun export(doc: OdfDocument.TextDocument): String {
        val sb = StringBuilder()
        sb.append("\\documentclass{article}\n")
        sb.append("\\usepackage[utf8]{inputenc}\n\\usepackage{amsmath}\n\\usepackage{graphicx}\n\\usepackage{hyperref}\n")
        if (doc.title.isNotBlank()) sb.append("\\title{${esc(doc.title)}}\n")
        sb.append("\\begin{document}\n")
        if (doc.title.isNotBlank()) sb.append("\\maketitle\n")

        var listMode: ListType? = null
        fun closeList() { if (listMode != null) { sb.append(if (listMode == ListType.NUMBERED) "\\end{enumerate}\n" else "\\end{itemize}\n"); listMode = null } }

        for (block in doc.content) when (block) {
            is OdfContentBlock.Paragraph -> {
                val p = block.paragraph
                if (p.style == ParagraphStyle.LIST_ITEM) {
                    val want = if (p.listType == ListType.NUMBERED) ListType.NUMBERED else ListType.BULLET
                    if (listMode != want) { closeList(); sb.append(if (want == ListType.NUMBERED) "\\begin{enumerate}\n" else "\\begin{itemize}\n"); listMode = want }
                    sb.append("  \\item ").append(runsLatex(p.spans)).append("\n")
                } else {
                    closeList()
                    when (p.style) {
                        ParagraphStyle.HEADING1 -> sb.append("\\section{${runsLatex(p.spans)}}\n")
                        ParagraphStyle.HEADING2 -> sb.append("\\subsection{${runsLatex(p.spans)}}\n")
                        ParagraphStyle.HEADING3 -> sb.append("\\subsubsection{${runsLatex(p.spans)}}\n")
                        ParagraphStyle.HEADING4 -> sb.append("\\paragraph{${runsLatex(p.spans)}}\n")
                        else -> { val t = runsLatex(p.spans); if (t.isNotBlank()) sb.append(t).append("\n\n") else sb.append("\n") }
                    }
                }
            }
            is OdfContentBlock.Table -> { closeList(); sb.append(tableLatex(block.table)) }
            is OdfContentBlock.Formula -> { closeList(); sb.append("\\[\n").append(MathmlToLatex.convert(block.mathml)).append("\n\\]\n") }
            is OdfContentBlock.TableOfContents -> { closeList(); sb.append("\\tableofcontents\n") }
            is OdfContentBlock.PageBreak -> { closeList(); sb.append("\\newpage\n") }
            else -> {}
        }
        closeList()
        sb.append("\\end{document}\n")
        return sb.toString()
    }

    private fun runsLatex(spans: List<OdfSpan>): String = spans.joinToString("") { runLatex(it) }

    private fun runLatex(s: OdfSpan): String {
        if (s.text.isEmpty()) return ""
        var t = esc(s.text)
        if (s.bold) t = "\\textbf{$t}"
        if (s.italic) t = "\\emph{$t}"
        if (s.underline) t = "\\underline{$t}"
        if (s.strikethrough) t = "\\sout{$t}"
        if (s.superscript) t = "\\textsuperscript{$t}"
        if (s.subscript) t = "\\textsubscript{$t}"
        if (s.href != null) t = "\\href{${s.href}}{$t}"
        return t
    }

    private fun tableLatex(table: OdfTable): String {
        val cols = table.rows.maxOfOrNull { r -> r.cells.count { !it.isCovered } } ?: 0
        if (cols == 0) return ""
        val sb = StringBuilder("\\begin{tabular}{|${"l|".repeat(cols)}}\n\\hline\n")
        for (row in table.rows) {
            val cells = row.cells.filter { !it.isCovered }.map { c -> c.paragraphs.joinToString(" ") { runsLatex(it.spans) } }
            sb.append(cells.joinToString(" & ")).append(" \\\\\n\\hline\n")
        }
        sb.append("\\end{tabular}\n\n")
        return sb.toString()
    }

    private fun esc(s: String): String {
        val sb = StringBuilder()
        for (c in s) when (c) {
            '\\' -> sb.append("\\textbackslash{}")
            '&', '%', '$', '#', '_', '{', '}' -> sb.append("\\$c")
            '~' -> sb.append("\\textasciitilde{}")
            '^' -> sb.append("\\textasciicircum{}")
            else -> sb.append(c)
        }
        return sb.toString()
    }
}

/** Compact MathML (Presentation) to LaTeX converter used by [LatexExporter] for formula blocks. */
internal object MathmlToLatex {

    fun convert(mathml: String): String {
        return try {
            val f = XmlPullParserFactory.newInstance(); f.isNamespaceAware = true
            val p = f.newPullParser(); p.setInput(mathml.reader())
            var e = p.eventType
            while (e != XmlPullParser.END_DOCUMENT) {
                if (e == XmlPullParser.START_TAG && p.name == "math") return node(p, "math").trim()
                if (e == XmlPullParser.START_TAG && p.name == "mrow") return node(p, "mrow").trim()
                e = p.next()
            }
            ""
        } catch (_: Exception) { "" }
    }

    private fun children(p: XmlPullParser, endTag: String): List<String> {
        val out = ArrayList<String>()
        val depth = p.depth
        var e = p.next()
        while (!(e == XmlPullParser.END_TAG && p.depth == depth && p.name == endTag)) {
            if (e == XmlPullParser.END_DOCUMENT) break
            if (e == XmlPullParser.START_TAG) out.add(node(p, p.name))
            e = p.next()
        }
        return out
    }

    private fun leaf(p: XmlPullParser, endTag: String): String {
        val sb = StringBuilder(); val depth = p.depth
        var e = p.next()
        while (!(e == XmlPullParser.END_TAG && p.depth == depth && p.name == endTag)) {
            if (e == XmlPullParser.END_DOCUMENT) break
            if (e == XmlPullParser.TEXT) sb.append(p.text)
            e = p.next()
        }
        return sb.toString().trim()
    }

    private fun node(p: XmlPullParser, name: String): String = when (name) {
        "math", "mrow", "mstyle" -> children(p, name).joinToString(" ")
        "mi", "mn" -> leaf(p, name)
        "mo" -> op(leaf(p, name))
        "mtext" -> "\\text{${leaf(p, name)}}"
        "mfrac" -> children(p, name).let { "\\frac{${it.getOrElse(0){""}}}{${it.getOrElse(1){""}}}" }
        "msup" -> children(p, name).let { "{${it.getOrElse(0){""}}}^{${it.getOrElse(1){""}}}" }
        "msub" -> children(p, name).let { "{${it.getOrElse(0){""}}}_{${it.getOrElse(1){""}}}" }
        "msubsup" -> children(p, name).let { "{${it.getOrElse(0){""}}}_{${it.getOrElse(1){""}}}^{${it.getOrElse(2){""}}}" }
        "msqrt" -> "\\sqrt{${children(p, name).joinToString(" ")}}"
        "mroot" -> children(p, name).let { "\\sqrt[${it.getOrElse(1){""}}]{${it.getOrElse(0){""}}}" }
        "munderover" -> children(p, name).let { "${it.getOrElse(0){""}}_{${it.getOrElse(1){""}}}^{${it.getOrElse(2){""}}}" }
        "munder" -> children(p, name).let { "${it.getOrElse(0){""}}_{${it.getOrElse(1){""}}}" }
        "mover" -> children(p, name).let { "${it.getOrElse(0){""}}^{${it.getOrElse(1){""}}}" }
        "mtable" -> "\\begin{matrix} ${children(p, name).joinToString(" \\\\ ")} \\end{matrix}"
        "mtr" -> children(p, name).joinToString(" & ")
        "mtd" -> children(p, name).joinToString(" ")
        "mfenced" -> "\\left( ${children(p, name).joinToString(" ")} \\right)"
        else -> children(p, name).joinToString(" ")
    }

    private fun op(o: String): String = when (o) {
        "\u2211" -> "\\sum"; "\u220F" -> "\\prod"; "\u222B" -> "\\int"; "\u221A" -> "\\sqrt"
        "\u00D7" -> "\\times"; "\u00F7" -> "\\div"; "\u2264" -> "\\leq"; "\u2265" -> "\\geq"
        "\u2260" -> "\\neq"; "\u00B1" -> "\\pm"; "\u221E" -> "\\infty"; "\u2192" -> "\\to"
        "\u03B1" -> "\\alpha"; "\u03B2" -> "\\beta"; "\u03C0" -> "\\pi"; "\u03B8" -> "\\theta"
        else -> o
    }
}
