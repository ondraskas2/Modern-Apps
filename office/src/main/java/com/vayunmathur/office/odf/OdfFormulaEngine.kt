package com.vayunmathur.office.odf

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * Minimal OpenFormula evaluator for spreadsheet cells (H49).
 *
 * Supports: numeric literals, cell references ([.A1]) and ranges ([.A1:.B3]),
 * arithmetic (+ - * / ^), comparisons (= <> < <= > >=), parentheses, and the
 * functions SUM, AVERAGE, MIN, MAX, COUNT, COUNTA, IF, ABS, ROUND, SQRT,
 * POWER, MOD, AND, OR, NOT. References resolve against the sheet grid and
 * recursively evaluate dependent formula cells (with cycle protection).
 *
 * This is a best-effort engine: it covers common cases, not the full
 * OpenFormula spec. Cells whose formula cannot be evaluated fall back to their
 * cached text value.
 */
object OdfFormulaEngine {

    /** Display string for a cell: evaluates the formula if present, else cached text. */
    fun displayValue(sheet: OdfSheet, row: Int, col: Int): String {
        val cell = sheet.rows.getOrNull(row)?.cells?.getOrNull(col) ?: return ""
        if (cell.formula == null) return cell.text
        return try {
            val v = Evaluator(sheet, mutableSetOf()).evaluateCell(row, col)
            if (v.isNaN()) cell.text.ifEmpty { "#ERR" } else formatWithStyle(v, cell.numberFormat)
        } catch (_: Exception) {
            cell.text
        }
    }

    /** Formats a numeric value using an ODF number-format descriptor (H50). */
    fun formatWithStyle(v: Double, fmt: OdfNumberFormat?): String {
        if (fmt == null) return formatNumber(v)
        var value = v
        if (fmt.percent) value *= 100.0
        val decimals = (fmt.decimals ?: 2).coerceIn(0, 10)
        val pattern = (if (fmt.grouping) "%,." else "%.") + decimals + "f"
        var s = String.format(pattern, value)
        if (fmt.percent) s += "%"
        if (fmt.currencySymbol != null) s = fmt.currencySymbol + s
        return s
    }

    /** True when the (evaluated) cell holds a numeric value (used for right-alignment, H54). */
    fun isNumeric(sheet: OdfSheet, row: Int, col: Int): Boolean {
        val cell = sheet.rows.getOrNull(row)?.cells?.getOrNull(col) ?: return false
        if (cell.numberValue != null) return true
        if (cell.valueType in setOf("float", "percentage", "currency")) return true
        if (cell.formula != null) {
            return try { !Evaluator(sheet, mutableSetOf()).evaluateCell(row, col).isNaN() } catch (_: Exception) { false }
        }
        return cell.text.toDoubleOrNull() != null
    }

    fun formatNumber(v: Double): String {
        if (v.isNaN()) return "#ERR"
        if (v.isInfinite()) return "#DIV/0!"
        return if (v == v.toLong().toDouble()) v.toLong().toString() else "%.4f".format(v).trimEnd('0').trimEnd('.')
    }

    private fun colToIndex(col: String): Int {
        var n = 0
        for (c in col) { if (c.isLetter()) n = n * 26 + (c.uppercaseChar() - 'A' + 1) }
        return n - 1
    }

    private class Evaluator(val sheet: OdfSheet, val visiting: MutableSet<Long>) {

        fun evaluateCell(row: Int, col: Int): Double {
            val key = row * 1_000_000L + col
            if (key in visiting) return Double.NaN // cycle
            val cell = sheet.rows.getOrNull(row)?.cells?.getOrNull(col) ?: return 0.0
            if (cell.formula == null) {
                return cell.numberValue ?: cell.text.toDoubleOrNull() ?: 0.0
            }
            visiting.add(key)
            try {
                val expr = normalize(cell.formula)
                return Parser(expr).parseExpression()
            } finally {
                visiting.remove(key)
            }
        }

        private fun normalize(formula: String): String {
            var f = formula.trim()
            if (f.startsWith("of:")) f = f.substring(3)
            if (f.startsWith("=")) f = f.substring(1)
            return f
        }

        // Recursive-descent parser over the formula string.
        private inner class Parser(val s: String) {
            var pos = 0

            fun parseExpression(): Double = parseComparison()

            private fun parseComparison(): Double {
                var left = parseAddSub()
                while (true) {
                    skipWs()
                    val op = when {
                        match("<=") -> "<="; match(">=") -> ">="; match("<>") -> "<>"
                        peek() == '<' -> { pos++; "<" }
                        peek() == '>' -> { pos++; ">" }
                        peek() == '=' -> { pos++; "=" }
                        else -> return left
                    }
                    val right = parseAddSub()
                    val b = when (op) {
                        "<" -> left < right; "<=" -> left <= right; ">" -> left > right
                        ">=" -> left >= right; "=" -> left == right; "<>" -> left != right
                        else -> false
                    }
                    left = if (b) 1.0 else 0.0
                }
            }

            private fun parseAddSub(): Double {
                var v = parseMulDiv()
                while (true) {
                    skipWs()
                    when (peek()) {
                        '+' -> { pos++; v += parseMulDiv() }
                        '-' -> { pos++; v -= parseMulDiv() }
                        else -> return v
                    }
                }
            }

            private fun parseMulDiv(): Double {
                var v = parsePower()
                while (true) {
                    skipWs()
                    when (peek()) {
                        '*' -> { pos++; v *= parsePower() }
                        '/' -> { pos++; v /= parsePower() }
                        else -> return v
                    }
                }
            }

            private fun parsePower(): Double {
                val base = parseUnary()
                skipWs()
                if (peek() == '^') { pos++; return base.pow(parseUnary()) }
                return base
            }

            private fun parseUnary(): Double {
                skipWs()
                if (peek() == '-') { pos++; return -parseUnary() }
                if (peek() == '+') { pos++; return parseUnary() }
                return parsePrimary()
            }

            private fun parsePrimary(): Double {
                skipWs()
                val c = peek()
                when {
                    c == '(' -> { pos++; val v = parseExpression(); skipWs(); if (peek() == ')') pos++; return v }
                    c == '[' -> { val list = parseReference(); return list.firstOrNull() ?: 0.0 }
                    c == '"' -> { parseString(); return Double.NaN } // strings -> NaN numerically
                    c.isDigit() || c == '.' -> return parseNumber()
                    c.isLetter() -> return parseFunctionOrConst()
                    else -> { pos++; return 0.0 }
                }
            }

            private fun parseNumber(): Double {
                val start = pos
                while (pos < s.length && (s[pos].isDigit() || s[pos] == '.' || s[pos] == 'E' || s[pos] == 'e')) pos++
                return s.substring(start, pos).toDoubleOrNull() ?: 0.0
            }

            private fun parseString(): String {
                pos++ // opening quote
                val sb = StringBuilder()
                while (pos < s.length && s[pos] != '"') { sb.append(s[pos]); pos++ }
                if (pos < s.length) pos++ // closing quote
                return sb.toString()
            }

            /** Parses a [.A1] reference or [.A1:.B3] range into a list of numeric values. */
            private fun parseReference(): List<Double> {
                pos++ // '['
                val sb = StringBuilder()
                while (pos < s.length && s[pos] != ']') { sb.append(s[pos]); pos++ }
                if (pos < s.length) pos++ // ']'
                val inner = sb.toString().replace("$", "").replace(".", "")
                return if (inner.contains(":")) {
                    val (a, b) = inner.split(":", limit = 2)
                    expandRange(a, b)
                } else {
                    listOf(cellValue(inner))
                }
            }

            private fun parseCellArg(): List<Double> {
                skipWs()
                return if (peek() == '[') parseReference() else listOf(parseExpression())
            }

            private fun parseFunctionOrConst(): Double {
                val start = pos
                while (pos < s.length && (s[pos].isLetterOrDigit() || s[pos] == '_' || s[pos] == '.')) pos++
                var name = s.substring(start, pos).uppercase()
                if (name.startsWith("ORG.OPENOFFICE.")) name = name.removePrefix("ORG.OPENOFFICE.")
                skipWs()
                if (peek() != '(') {
                    return when (name) { "TRUE" -> 1.0; "FALSE" -> 0.0; "PI" -> Math.PI; else -> Double.NaN }
                }
                pos++ // '('
                val args = mutableListOf<List<Double>>()
                skipWs()
                if (peek() != ')') {
                    args.add(parseCellArg())
                    skipWs()
                    while (peek() == ';' || peek() == ',') { pos++; args.add(parseCellArg()); skipWs() }
                }
                if (peek() == ')') pos++
                return applyFunction(name, args)
            }

            private fun applyFunction(name: String, args: List<List<Double>>): Double {
                val flat = args.flatten()
                val nums = flat.filter { !it.isNaN() }
                return when (name) {
                    "SUM" -> nums.sum()
                    "AVERAGE" -> if (nums.isEmpty()) Double.NaN else nums.average()
                    "MIN" -> nums.minOrNull() ?: Double.NaN
                    "MAX" -> nums.maxOrNull() ?: Double.NaN
                    "COUNT" -> nums.size.toDouble()
                    "COUNTA" -> flat.size.toDouble()
                    "ABS" -> abs(flat.firstOrNull() ?: 0.0)
                    "SQRT" -> sqrt(flat.firstOrNull() ?: 0.0)
                    "ROUND" -> {
                        val v = args.getOrNull(0)?.firstOrNull() ?: 0.0
                        val d = (args.getOrNull(1)?.firstOrNull() ?: 0.0).toInt()
                        val factor = 10.0.pow(d)
                        (v * factor).roundToLong() / factor
                    }
                    "POWER" -> (args.getOrNull(0)?.firstOrNull() ?: 0.0).pow(args.getOrNull(1)?.firstOrNull() ?: 0.0)
                    "MOD" -> {
                        val a = args.getOrNull(0)?.firstOrNull() ?: 0.0
                        val b = args.getOrNull(1)?.firstOrNull() ?: 1.0
                        if (b == 0.0) Double.NaN else a % b
                    }
                    "IF" -> {
                        val cond = args.getOrNull(0)?.firstOrNull() ?: 0.0
                        if (cond != 0.0 && !cond.isNaN()) args.getOrNull(1)?.firstOrNull() ?: 0.0
                        else args.getOrNull(2)?.firstOrNull() ?: 0.0
                    }
                    "AND" -> if (nums.isNotEmpty() && nums.all { it != 0.0 }) 1.0 else 0.0
                    "OR" -> if (nums.any { it != 0.0 }) 1.0 else 0.0
                    "NOT" -> if ((flat.firstOrNull() ?: 0.0) == 0.0) 1.0 else 0.0
                    else -> Double.NaN
                }
            }

            private fun cellValue(ref: String): Double {
                val m = Regex("([A-Za-z]+)(\\d+)").find(ref) ?: return 0.0
                val col = colToIndex(m.groupValues[1])
                val row = (m.groupValues[2].toIntOrNull() ?: 1) - 1
                return evaluateCell(row, col)
            }

            private fun expandRange(a: String, b: String): List<Double> {
                val ma = Regex("([A-Za-z]+)(\\d+)").find(a) ?: return emptyList()
                val mb = Regex("([A-Za-z]+)(\\d+)").find(b) ?: return emptyList()
                val c1 = colToIndex(ma.groupValues[1]); val r1 = (ma.groupValues[2].toIntOrNull() ?: 1) - 1
                val c2 = colToIndex(mb.groupValues[1]); val r2 = (mb.groupValues[2].toIntOrNull() ?: 1) - 1
                val out = mutableListOf<Double>()
                for (r in minOf(r1, r2)..maxOf(r1, r2)) {
                    for (c in minOf(c1, c2)..maxOf(c1, c2)) {
                        val cell = sheet.rows.getOrNull(r)?.cells?.getOrNull(c)
                        if (cell != null && !cell.isCovered && (cell.text.isNotEmpty() || cell.formula != null || cell.numberValue != null)) {
                            out.add(evaluateCell(r, c))
                        }
                    }
                }
                return out
            }

            private fun peek(): Char = if (pos < s.length) s[pos] else '\u0000'
            private fun skipWs() { while (pos < s.length && s[pos].isWhitespace()) pos++ }
            private fun match(token: String): Boolean {
                skipWs()
                if (s.startsWith(token, pos)) { pos += token.length; return true }
                return false
            }
        }
    }
}
