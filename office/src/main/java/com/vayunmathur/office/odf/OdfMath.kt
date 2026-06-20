package com.vayunmathur.office.odf

import com.vayunmathur.office.odf.MathNode.Fenced
import com.vayunmathur.office.odf.MathNode.Frac
import com.vayunmathur.office.odf.MathNode.Root
import com.vayunmathur.office.odf.MathNode.Row
import com.vayunmathur.office.odf.MathNode.Sqrt
import com.vayunmathur.office.odf.MathNode.Sub
import com.vayunmathur.office.odf.MathNode.SubSup
import com.vayunmathur.office.odf.MathNode.Sup
import com.vayunmathur.office.odf.MathNode.Token
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

/** Parsed MathML presentation tree for layout rendering (D34). */
sealed class MathNode {
    data class Row(val children: List<MathNode>) : MathNode()
    data class Token(val text: String, val isOperator: Boolean = false) : MathNode()
    data class Frac(val numerator: MathNode, val denominator: MathNode) : MathNode()
    data class Sup(val base: MathNode, val exponent: MathNode) : MathNode()
    data class Sub(val base: MathNode, val subscript: MathNode) : MathNode()
    data class SubSup(val base: MathNode, val subscript: MathNode, val superscript: MathNode) : MathNode()
    data class Sqrt(val radicand: MathNode) : MathNode()
    data class Root(val radicand: MathNode, val index: MathNode) : MathNode()
    data class Fenced(val open: String, val close: String, val body: MathNode) : MathNode()
}

object OdfMath {

    /** Parses an embedded math object's content.xml (MathML). Returns null if unparseable. */
    fun parse(xml: String): MathNode? {
        return try {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = true
            val parser = factory.newPullParser()
            parser.setInput(xml.reader())
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.name == "math") {
                    return Row(parseChildren(parser, "math"))
                }
                event = parser.next()
            }
            null
        } catch (_: Exception) { null }
    }

    private fun attr(parser: XmlPullParser, name: String): String? {
        for (i in 0 until parser.attributeCount) if (parser.getAttributeName(i) == name) return parser.getAttributeValue(i)
        return null
    }

    /** Parses all child element nodes until the matching end tag of [endTag] at the current depth. */
    private fun parseChildren(parser: XmlPullParser, endTag: String): List<MathNode> {
        val nodes = mutableListOf<MathNode>()
        val depth = parser.depth
        var event = parser.next()
        while (!(event == XmlPullParser.END_TAG && parser.depth == depth && parser.name == endTag)) {
            if (event == XmlPullParser.START_TAG) parseElement(parser)?.let { nodes.add(it) }
            if (event == XmlPullParser.END_DOCUMENT) break
            event = parser.next()
        }
        return nodes
    }

    /** Parses a single element (assumes parser positioned at its START_TAG). Consumes through its END_TAG. */
    private fun parseElement(parser: XmlPullParser): MathNode? {
        return when (parser.name) {
            "mi", "mn", "mo", "mtext", "ms" -> {
                val isOp = parser.name == "mo"
                Token(readText(parser, parser.name), isOp)
            }
            "mspace" -> { skip(parser); Token(" ") }
            "mrow", "mstyle", "mpadded", "merror", "math" -> {
                val tag = parser.name
                Row(parseChildren(parser, tag))
            }
            "mfrac" -> {
                val kids = parseChildren(parser, "mfrac")
                Frac(kids.getOrElse(0) { Row(emptyList()) }, kids.getOrElse(1) { Row(emptyList()) })
            }
            "msup" -> {
                val kids = parseChildren(parser, "msup")
                Sup(kids.getOrElse(0) { Row(emptyList()) }, kids.getOrElse(1) { Row(emptyList()) })
            }
            "msub" -> {
                val kids = parseChildren(parser, "msub")
                Sub(kids.getOrElse(0) { Row(emptyList()) }, kids.getOrElse(1) { Row(emptyList()) })
            }
            "msubsup" -> {
                val kids = parseChildren(parser, "msubsup")
                SubSup(kids.getOrElse(0) { Row(emptyList()) }, kids.getOrElse(1) { Row(emptyList()) }, kids.getOrElse(2) { Row(emptyList()) })
            }
            "msqrt" -> Sqrt(Row(parseChildren(parser, "msqrt")))
            "mroot" -> {
                val kids = parseChildren(parser, "mroot")
                Root(kids.getOrElse(0) { Row(emptyList()) }, kids.getOrElse(1) { Token("") })
            }
            "mfenced" -> {
                val open = attr(parser, "open") ?: "("
                val close = attr(parser, "close") ?: ")"
                Fenced(open, close, Row(parseChildren(parser, "mfenced")))
            }
            "semantics" -> Row(parseChildren(parser, "semantics"))
            "annotation", "annotation-xml" -> { skip(parser); null }
            else -> { val tag = parser.name; Row(parseChildren(parser, tag)) }
        }
    }

    private fun readText(parser: XmlPullParser, endTag: String): String {
        val sb = StringBuilder()
        val depth = parser.depth
        var event = parser.next()
        while (!(event == XmlPullParser.END_TAG && parser.depth == depth && parser.name == endTag)) {
            if (event == XmlPullParser.TEXT) sb.append(parser.text)
            if (event == XmlPullParser.END_DOCUMENT) break
            event = parser.next()
        }
        return sb.toString().trim()
    }

    private fun skip(parser: XmlPullParser) {
        val depth = parser.depth
        var event = parser.next()
        while (!(event == XmlPullParser.END_TAG && parser.depth == depth)) {
            if (event == XmlPullParser.END_DOCUMENT) break
            event = parser.next()
        }
    }

    /** Flattens a node to plain text (fallback when layout isn't desired). */
    fun toText(node: MathNode): String = when (node) {
        is MathNode.Row -> node.children.joinToString("") { toText(it) }
        is MathNode.Token -> node.text
        is MathNode.Frac -> "(${toText(node.numerator)})/(${toText(node.denominator)})"
        is MathNode.Sup -> "${toText(node.base)}^${toText(node.exponent)}"
        is MathNode.Sub -> "${toText(node.base)}_${toText(node.subscript)}"
        is MathNode.SubSup -> "${toText(node.base)}_${toText(node.subscript)}^${toText(node.superscript)}"
        is MathNode.Sqrt -> "\u221A(${toText(node.radicand)})"
        is MathNode.Root -> "root[${toText(node.index)}](${toText(node.radicand)})"
        is MathNode.Fenced -> "${node.open}${toText(node.body)}${node.close}"
    }
}
