package com.vayunmathur.office.odf

import java.util.GregorianCalendar
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sign
import kotlin.math.sqrt
import kotlin.math.truncate

/**
 * OpenFormula evaluator for spreadsheet cells (H49 / Priority 3).
 *
 * Value-typed engine: cells/expressions evaluate to numbers, strings, booleans
 * or error values (#DIV/0!, #VALUE!, #N/A, #REF!, #NAME?). Supports numeric and
 * string literals, cell references ([.A1]) and ranges ([.A1:.B3]), arithmetic
 * (+ - * / ^), string concat (&), comparisons (= <> < <= > >=), parentheses, and
 * a broad function library: math/stat, logical (incl. IFERROR/IFS/IS*),
 * lookup (VLOOKUP/HLOOKUP/MATCH/INDEX/CHOOSE), text (LEFT/RIGHT/MID/LEN/...),
 * date/time (DATE/TODAY/NOW/YEAR/...), and conditional aggregation
 * (SUMIF/COUNTIF/AVERAGEIF). References resolve against the sheet grid and
 * recursively evaluate dependent formula cells (with cycle protection).
 *
 * Best-effort: covers common cases, not the full OpenFormula spec. Cross-sheet
 * references resolve within the current sheet only (the caller supplies a single
 * sheet). Cells whose formula cannot be evaluated fall back to their cached text.
 */
object OdfFormulaEngine {

    /** Display string for a cell: evaluates the formula if present, else cached text. */
    fun displayValue(sheet: OdfSheet, row: Int, col: Int, workbook: Map<String, OdfSheet> = emptyMap(), sheetName: String = ""): String {
        val cell = sheet.rows.getOrNull(row)?.cells?.getOrNull(col) ?: return ""
        if (cell.formula == null) return cell.text
        return try {
            when (val v = Evaluator(sheet, mutableSetOf(), workbook, sheetName).evaluateCellValue(row, col)) {
                is Value.Num -> if (v.v.isNaN()) "#ERR" else formatWithStyle(v.v, cell.numberFormat)
                is Value.Bool -> if (v.b) "TRUE" else "FALSE"
                is Value.Str -> v.s
                is Value.Err -> v.code
                Value.Blank -> ""
            }
        } catch (_: Exception) {
            cell.text
        }
    }

    /** Formats a numeric value using an ODF number-format descriptor (H50). */
    fun formatWithStyle(v: Double, fmt: OdfNumberFormat?): String {
        if (fmt == null) return formatNumber(v)
        if (fmt.isTime) return formatTime(v)
        if (fmt.isScientific) {
            val decimals = (fmt.decimals ?: 2).coerceIn(0, 10)
            return String.format("%.${decimals}E", v)
        }
        if (fmt.isFraction) return formatFraction(v, fmt.fractionDenominatorDigits)
        var value = v
        if (fmt.percent) value *= 100.0
        val decimals = (fmt.decimals ?: 2).coerceIn(0, 10)
        val pattern = (if (fmt.grouping) "%,." else "%.") + decimals + "f"
        var s = String.format(pattern, value)
        if (fmt.percent) s += "%"
        if (fmt.currencySymbol != null) s = fmt.currencySymbol + s
        return s
    }

    /** Formats a day-fraction serial (0..1) as HH:MM:SS. */
    private fun formatTime(serial: Double): String {
        val totalSeconds = Math.round((serial - floor(serial)) * 86400.0)
        val h = (totalSeconds / 3600) % 24
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return String.format("%02d:%02d:%02d", h, m, s)
    }

    /** Approximates a value as "whole num/den" using the largest denominator with [denomDigits] digits. */
    private fun formatFraction(value: Double, denomDigits: Int): String {
        val maxDen = 10.0.pow(denomDigits.coerceIn(1, 5)).toInt() - 1
        val whole = truncate(value).toLong()
        var frac = abs(value - whole)
        if (frac < 1e-9) return whole.toString()
        var bestN = 0; var bestD = 1; var bestErr = Double.MAX_VALUE
        for (d in 1..maxDen) {
            val n = Math.round(frac * d).toInt()
            val err = abs(frac - n.toDouble() / d)
            if (err < bestErr) { bestErr = err; bestN = n; bestD = d }
        }
        if (bestN == 0) return whole.toString()
        val sign = if (value < 0 && whole == 0L) "-" else ""
        return if (whole != 0L) "$whole $bestN/$bestD" else "$sign$bestN/$bestD"
    }

    /** True when the (evaluated) cell holds a numeric value (used for right-alignment, H54). */
    fun isNumeric(sheet: OdfSheet, row: Int, col: Int, workbook: Map<String, OdfSheet> = emptyMap(), sheetName: String = ""): Boolean {
        val cell = sheet.rows.getOrNull(row)?.cells?.getOrNull(col) ?: return false
        if (cell.numberValue != null) return true
        if (cell.valueType in setOf("float", "percentage", "currency")) return true
        if (cell.formula != null) {
            return try {
                Evaluator(sheet, mutableSetOf(), workbook, sheetName).evaluateCellValue(row, col).let {
                    it is Value.Num && !it.v.isNaN()
                }
            } catch (_: Exception) { false }
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

    // ---- Value model -------------------------------------------------------

    private sealed class Value {
        data class Num(val v: Double) : Value()
        data class Str(val s: String) : Value()
        data class Bool(val b: Boolean) : Value()
        data class Err(val code: String) : Value()
        object Blank : Value()
    }

    private class FormulaException(val code: String) : Exception(code)

    /** Reference parsed from a [..] token: a single cell (isRange=false) or a rectangular range. */
    private data class Ref(val r1: Int, val c1: Int, val r2: Int, val c2: Int, val isRange: Boolean, val sheet: String? = null)

    private sealed class Arg {
        data class Scalar(val v: Value) : Arg()
        data class RangeRef(val r1: Int, val c1: Int, val r2: Int, val c2: Int, val sheet: String? = null) : Arg()
    }

    // ---- Date helpers (serial = days since 1899-12-30) ---------------------

    private const val MS_PER_DAY = 86_400_000.0

    private fun dateSerial(y: Int, m: Int, d: Int): Double {
        val cal = GregorianCalendar(y, m - 1, d, 0, 0, 0)
        cal.set(GregorianCalendar.MILLISECOND, 0)
        val base = GregorianCalendar(1899, 11, 30, 0, 0, 0)
        base.set(GregorianCalendar.MILLISECOND, 0)
        return ((cal.timeInMillis - base.timeInMillis) / MS_PER_DAY).let { Math.round(it).toDouble() }
    }

    private fun nowSerial(withTime: Boolean): Double {
        val cal = GregorianCalendar()
        val base = GregorianCalendar(1899, 11, 30, 0, 0, 0)
        base.set(GregorianCalendar.MILLISECOND, 0)
        val raw = (cal.timeInMillis - base.timeInMillis) / MS_PER_DAY
        return if (withTime) raw else floor(raw)
    }

    private fun serialToCal(serial: Double): GregorianCalendar {
        val base = GregorianCalendar(1899, 11, 30, 0, 0, 0)
        base.set(GregorianCalendar.MILLISECOND, 0)
        val cal = GregorianCalendar()
        cal.timeInMillis = base.timeInMillis + Math.round(serial * MS_PER_DAY)
        return cal
    }

    private class Evaluator(
        val sheet: OdfSheet,
        val visiting: MutableSet<String>,
        val workbook: Map<String, OdfSheet> = emptyMap(),
        val sheetName: String = ""
    ) {

        /** Public entry: evaluates a cell, converting thrown errors into a Value.Err. */
        fun evaluateCellValue(row: Int, col: Int): Value = try {
            evaluateCellV(row, col)
        } catch (e: FormulaException) {
            Value.Err(e.code)
        }

        /** Resolves a (possibly cross-sheet) cell reference, evaluating on the target sheet. (R4) */
        private fun evalOn(name: String?, row: Int, col: Int): Value {
            if (name == null || name == sheetName) return evaluateCellV(row, col)
            val target = workbook[name] ?: return Value.Err("#REF!")
            return Evaluator(target, visiting, workbook, name).evaluateCellV(row, col)
        }

        private fun evaluateCellV(row: Int, col: Int): Value {
            val key = "$sheetName!$row,$col"
            if (key in visiting) throw FormulaException("#REF!")
            val cell = sheet.rows.getOrNull(row)?.cells?.getOrNull(col) ?: return Value.Blank
            if (cell.formula == null) return rawCellValue(cell)
            visiting.add(key)
            try {
                val expr = normalize(cell.formula)
                return Parser(expr).parseExpression()
            } finally {
                visiting.remove(key)
            }
        }

        private fun rawCellValue(cell: OdfCell): Value {
            cell.numberValue?.let { return Value.Num(it) }
            val t = cell.text
            if (t.isEmpty()) return Value.Blank
            t.toDoubleOrNull()?.let { return Value.Num(it) }
            if (t.equals("TRUE", true)) return Value.Bool(true)
            if (t.equals("FALSE", true)) return Value.Bool(false)
            return Value.Str(t)
        }

        private fun normalize(formula: String): String {
            var f = formula.trim()
            if (f.startsWith("of:")) f = f.substring(3)
            if (f.startsWith("=")) f = f.substring(1)
            return f
        }

        // ---- coercions -----------------------------------------------------

        private fun num(v: Value): Double = when (v) {
            is Value.Num -> v.v
            is Value.Bool -> if (v.b) 1.0 else 0.0
            is Value.Str -> v.s.trim().toDoubleOrNull() ?: throw FormulaException("#VALUE!")
            Value.Blank -> 0.0
            is Value.Err -> throw FormulaException(v.code)
        }

        private fun numOrNull(v: Value): Double? = when (v) {
            is Value.Num -> v.v
            is Value.Bool -> if (v.b) 1.0 else 0.0
            else -> null
        }

        private fun str(v: Value): String = when (v) {
            is Value.Num -> formatNumber(v.v)
            is Value.Bool -> if (v.b) "TRUE" else "FALSE"
            is Value.Str -> v.s
            Value.Blank -> ""
            is Value.Err -> throw FormulaException(v.code)
        }

        private fun truthy(v: Value): Boolean = when (v) {
            is Value.Bool -> v.b
            is Value.Num -> v.v != 0.0
            is Value.Str -> when {
                v.s.equals("TRUE", true) -> true
                v.s.equals("FALSE", true) -> false
                else -> v.s.trim().toDoubleOrNull()?.let { it != 0.0 } ?: throw FormulaException("#VALUE!")
            }
            Value.Blank -> false
            is Value.Err -> throw FormulaException(v.code)
        }

        // ---- range / arg helpers ------------------------------------------

        private fun rangeValues(r1: Int, c1: Int, r2: Int, c2: Int, sheetRef: String? = null): List<Value> {
            val out = ArrayList<Value>()
            val target = if (sheetRef == null || sheetRef == sheetName) sheet else workbook[sheetRef] ?: sheet
            for (r in minOf(r1, r2)..maxOf(r1, r2)) {
                for (c in minOf(c1, c2)..maxOf(c1, c2)) {
                    val cell = target.rows.getOrNull(r)?.cells?.getOrNull(c)
                    if (cell != null && cell.isCovered) continue
                    out.add(evalOn(sheetRef, r, c))
                }
            }
            return out
        }

        private fun argValues(arg: Arg): List<Value> = when (arg) {
            is Arg.Scalar -> listOf(arg.v)
            is Arg.RangeRef -> rangeValues(arg.r1, arg.c1, arg.r2, arg.c2, arg.sheet)
        }

        // A lazily-evaluated function argument (for IF/IFERROR/CHOOSE short-circuiting).
        private inner class ArgThunk(val text: String) {
            fun arg(): Arg = Parser(text).parseArgTop()
            fun value(): Value = when (val a = arg()) {
                is Arg.Scalar -> a.v
                is Arg.RangeRef -> evalOn(a.sheet, a.r1, a.c1)
            }
            fun values(): List<Value> = argValues(arg())
        }

        // ---- comparison ----------------------------------------------------

        private fun compareOp(op: String, l: Value, r: Value): Boolean {
            val ln = cmpNum(l); val rn = cmpNum(r)
            if (ln != null && rn != null) {
                return when (op) {
                    "<" -> ln < rn; "<=" -> ln <= rn; ">" -> ln > rn
                    ">=" -> ln >= rn; "=" -> ln == rn; "<>" -> ln != rn; else -> false
                }
            }
            val cmp = str(l).compareTo(str(r), ignoreCase = true)
            return when (op) {
                "<" -> cmp < 0; "<=" -> cmp <= 0; ">" -> cmp > 0
                ">=" -> cmp >= 0; "=" -> cmp == 0; "<>" -> cmp != 0; else -> false
            }
        }

        private fun cmpNum(v: Value): Double? = when (v) {
            is Value.Num -> v.v
            is Value.Bool -> if (v.b) 1.0 else 0.0
            Value.Blank -> 0.0
            is Value.Err -> throw FormulaException(v.code)
            is Value.Str -> null
        }

        // ---- criteria matching (SUMIF/COUNTIF/AVERAGEIF) -------------------

        private fun matchesCriteria(v: Value, criteria: Value): Boolean {
            val crit = str(criteria)
            var op = "="; var rest = crit.trim()
            for (o in listOf("<=", ">=", "<>", "<", ">", "=")) {
                if (rest.startsWith(o)) { op = o; rest = rest.substring(o.length).trim(); break }
            }
            val restNum = rest.toDoubleOrNull()
            val vNum = numOrNull(v)
            if (restNum != null && vNum != null) {
                return when (op) {
                    "<" -> vNum < restNum; "<=" -> vNum <= restNum; ">" -> vNum > restNum
                    ">=" -> vNum >= restNum; "<>" -> vNum != restNum; else -> vNum == restNum
                }
            }
            val vStr = if (v == Value.Blank) "" else str(v)
            return when (op) {
                "<>" -> !wildcardEquals(vStr, rest)
                "=" -> wildcardEquals(vStr, rest)
                else -> false
            }
        }

        private fun wildcardEquals(value: String, pattern: String): Boolean {
            if (!pattern.contains('*') && !pattern.contains('?')) return value.equals(pattern, true)
            val regex = StringBuilder("^")
            for (ch in pattern) when (ch) {
                '*' -> regex.append(".*")
                '?' -> regex.append('.')
                else -> regex.append(Regex.escape(ch.toString()))
            }
            regex.append('$')
            return Regex(regex.toString(), RegexOption.IGNORE_CASE).matches(value)
        }

        // Recursive-descent parser over the formula string.
        private inner class Parser(val s: String) {
            var pos = 0

            fun parseExpression(): Value = parseComparison()

            /** Top-level parse of a single function argument; preserves range references. */
            fun parseArgTop(): Arg {
                skipWs()
                if (peek() == '[') {
                    val save = pos
                    val ref = parseRefRaw()
                    skipWs()
                    if (pos >= s.length) {
                        return if (ref.isRange) Arg.RangeRef(ref.r1, ref.c1, ref.r2, ref.c2, ref.sheet)
                        else Arg.Scalar(evalOn(ref.sheet, ref.r1, ref.c1))
                    }
                    pos = save
                }
                return Arg.Scalar(parseExpression())
            }

            private fun parseComparison(): Value {
                var left = parseConcat()
                while (true) {
                    skipWs()
                    val op = when {
                        match("<=") -> "<="; match(">=") -> ">="; match("<>") -> "<>"
                        peek() == '<' -> { pos++; "<" }
                        peek() == '>' -> { pos++; ">" }
                        peek() == '=' -> { pos++; "=" }
                        else -> return left
                    }
                    val right = parseConcat()
                    left = Value.Bool(compareOp(op, left, right))
                }
            }

            private fun parseConcat(): Value {
                var v = parseAddSub()
                while (true) {
                    skipWs()
                    if (peek() == '&') { pos++; v = Value.Str(str(v) + str(parseAddSub())) }
                    else return v
                }
            }

            private fun parseAddSub(): Value {
                var v = parseMulDiv()
                while (true) {
                    skipWs()
                    when (peek()) {
                        '+' -> { pos++; v = Value.Num(num(v) + num(parseMulDiv())) }
                        '-' -> { pos++; v = Value.Num(num(v) - num(parseMulDiv())) }
                        else -> return v
                    }
                }
            }

            private fun parseMulDiv(): Value {
                var v = parsePower()
                while (true) {
                    skipWs()
                    when (peek()) {
                        '*' -> { pos++; v = Value.Num(num(v) * num(parsePower())) }
                        '/' -> {
                            pos++
                            val d = num(parsePower())
                            if (d == 0.0) throw FormulaException("#DIV/0!")
                            v = Value.Num(num(v) / d)
                        }
                        else -> return v
                    }
                }
            }

            private fun parsePower(): Value {
                val base = parseUnary()
                skipWs()
                if (peek() == '^') { pos++; return Value.Num(num(base).pow(num(parseUnary()))) }
                return base
            }

            private fun parseUnary(): Value {
                skipWs()
                if (peek() == '-') { pos++; return Value.Num(-num(parseUnary())) }
                if (peek() == '+') { pos++; return parseUnary() }
                return parsePrimary()
            }

            private fun parsePrimary(): Value {
                skipWs()
                val c = peek()
                return when {
                    c == '(' -> { pos++; val v = parseExpression(); skipWs(); if (peek() == ')') pos++; v }
                    c == '[' -> { val ref = parseRefRaw(); evalOn(ref.sheet, ref.r1, ref.c1) }
                    c == '"' -> Value.Str(parseString())
                    c.isDigit() || c == '.' -> Value.Num(parseNumber())
                    c.isLetter() -> parseFunctionOrConst()
                    else -> { pos++; Value.Num(0.0) }
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
                while (pos < s.length) {
                    val ch = s[pos]
                    if (ch == '"') {
                        // Doubled quote -> literal quote (OpenFormula escaping).
                        if (pos + 1 < s.length && s[pos + 1] == '"') { sb.append('"'); pos += 2; continue }
                        pos++; break
                    }
                    sb.append(ch); pos++
                }
                return sb.toString()
            }

            /** Parses a [.A1] / [.A1:.B3] / [Sheet.A1] reference token. */
            private fun parseRefRaw(): Ref {
                pos++ // '['
                val sb = StringBuilder()
                while (pos < s.length && s[pos] != ']') { sb.append(s[pos]); pos++ }
                if (pos < s.length) pos++ // ']'
                val raw = sb.toString().replace("$", "")
                val firstEndpoint = raw.substringBefore(":")
                val sheetRef = firstEndpoint.substringBeforeLast('.', "").removePrefix(".").trim().trim('\'').ifEmpty { null }
                return if (raw.contains(":")) {
                    val (a, b) = raw.split(":", limit = 2)
                    val (r1, c1) = parseCellCoords(a)
                    val (r2, c2) = parseCellCoords(b)
                    Ref(r1, c1, r2, c2, true, sheetRef)
                } else {
                    val (r, c) = parseCellCoords(raw)
                    Ref(r, c, r, c, false, sheetRef)
                }
            }

            /** Returns (rowIndex, colIndex) 0-based. Drops any sheet prefix / leading dot. */
            private fun parseCellCoords(token: String): Pair<Int, Int> {
                val t = token.substringAfterLast('.')
                val m = Regex("([A-Za-z]+)(\\d+)").find(t) ?: return 0 to 0
                val col = colToIndex(m.groupValues[1])
                val row = (m.groupValues[2].toIntOrNull() ?: 1) - 1
                return row to col
            }

            private fun parseFunctionOrConst(): Value {
                val start = pos
                while (pos < s.length && (s[pos].isLetterOrDigit() || s[pos] == '_' || s[pos] == '.')) pos++
                var name = s.substring(start, pos).uppercase()
                if (name.startsWith("ORG.OPENOFFICE.")) name = name.removePrefix("ORG.OPENOFFICE.")
                if (name.startsWith("COM.MICROSOFT.")) name = name.removePrefix("COM.MICROSOFT.")
                skipWs()
                if (peek() != '(') {
                    return when (name) {
                        "TRUE" -> Value.Bool(true)
                        "FALSE" -> Value.Bool(false)
                        "PI" -> Value.Num(Math.PI)
                        else -> throw FormulaException("#NAME?")
                    }
                }
                pos++ // '('
                val argTexts = splitArgs()
                return applyFunction(name, argTexts.map { ArgThunk(it) })
            }

            /** Splits the current argument list, consuming through the matching ')'. */
            private fun splitArgs(): List<String> {
                val parts = mutableListOf<String>()
                val cur = StringBuilder()
                var depth = 0
                var inStr = false
                while (pos < s.length) {
                    val c = s[pos]
                    if (inStr) { cur.append(c); if (c == '"') inStr = false; pos++; continue }
                    when (c) {
                        '"' -> { inStr = true; cur.append(c); pos++ }
                        '(', '[' -> { depth++; cur.append(c); pos++ }
                        ']' -> { depth--; cur.append(c); pos++ }
                        ')' -> { if (depth == 0) { pos++; break } else { depth--; cur.append(c); pos++ } }
                        ',', ';' -> { if (depth == 0) { parts.add(cur.toString()); cur.clear(); pos++ } else { cur.append(c); pos++ } }
                        else -> { cur.append(c); pos++ }
                    }
                }
                if (cur.isNotBlank() || parts.isNotEmpty()) parts.add(cur.toString())
                return parts
            }

            // ---- function library -----------------------------------------

            private fun applyFunction(name: String, a: List<ArgThunk>): Value {
                fun allVals() = a.flatMap { it.values() }
                fun allNums() = allVals().mapNotNull { numOrNull(it) }
                fun n(i: Int) = num(a[i].value())
                fun v(i: Int) = a[i].value()
                fun sArg(i: Int) = str(a[i].value())

                return when (name) {
                    // ---- math / statistics ----
                    "SUM" -> Value.Num(allNums().sum())
                    "AVERAGE" -> allNums().let { if (it.isEmpty()) throw FormulaException("#DIV/0!") else Value.Num(it.average()) }
                    "MIN" -> allNums().minOrNull()?.let { Value.Num(it) } ?: Value.Num(0.0)
                    "MAX" -> allNums().maxOrNull()?.let { Value.Num(it) } ?: Value.Num(0.0)
                    "COUNT" -> Value.Num(allNums().size.toDouble())
                    "COUNTA" -> Value.Num(allVals().count { it != Value.Blank }.toDouble())
                    "COUNTBLANK" -> Value.Num(allVals().count { it == Value.Blank }.toDouble())
                    "PRODUCT" -> allNums().let { if (it.isEmpty()) Value.Num(0.0) else Value.Num(it.fold(1.0) { x, y -> x * y }) }
                    "ABS" -> Value.Num(abs(n(0)))
                    "SQRT" -> Value.Num(sqrt(n(0)))
                    "POWER" -> Value.Num(n(0).pow(n(1)))
                    "MOD" -> { val b = n(1); if (b == 0.0) throw FormulaException("#DIV/0!") else Value.Num(n(0).let { it - floor(it / b) * b }) }
                    "INT" -> Value.Num(floor(n(0)))
                    "TRUNC" -> Value.Num(truncate(n(0)))
                    "SIGN" -> Value.Num(sign(n(0)))
                    "EXP" -> Value.Num(exp(n(0)))
                    "LN" -> Value.Num(ln(n(0)))
                    "LOG10" -> Value.Num(log10(n(0)))
                    "LOG" -> Value.Num(ln(n(0)) / ln(if (a.size > 1) n(1) else 10.0))
                    "ROUND" -> { val f = 10.0.pow(if (a.size > 1) n(1).toInt() else 0); Value.Num((n(0) * f).roundToLong() / f) }
                    "ROUNDUP" -> { val d = if (a.size > 1) n(1).toInt() else 0; val f = 10.0.pow(d); val x = n(0); Value.Num(ceil(abs(x) * f) / f * (if (x < 0) -1.0 else 1.0)) }
                    "ROUNDDOWN" -> { val d = if (a.size > 1) n(1).toInt() else 0; val f = 10.0.pow(d); val x = n(0); Value.Num(floor(abs(x) * f) / f * (if (x < 0) -1.0 else 1.0)) }
                    "CEILING" -> { val step = if (a.size > 1) n(1) else 1.0; if (step == 0.0) Value.Num(0.0) else Value.Num(ceil(n(0) / step) * step) }
                    "FLOOR" -> { val step = if (a.size > 1) n(1) else 1.0; if (step == 0.0) Value.Num(0.0) else Value.Num(floor(n(0) / step) * step) }
                    "MEDIAN" -> allNums().let {
                        if (it.isEmpty()) throw FormulaException("#NUM!")
                        val sorted = it.sorted(); val sz = sorted.size
                        Value.Num(if (sz % 2 == 1) sorted[sz / 2] else (sorted[sz / 2 - 1] + sorted[sz / 2]) / 2.0)
                    }
                    "STDEV" -> allNums().let { if (it.size < 2) throw FormulaException("#DIV/0!") else { val m = it.average(); Value.Num(sqrt(it.sumOf { x -> (x - m).pow(2) } / (it.size - 1))) } }
                    "VAR" -> allNums().let { if (it.size < 2) throw FormulaException("#DIV/0!") else { val m = it.average(); Value.Num(it.sumOf { x -> (x - m).pow(2) } / (it.size - 1)) } }

                    // ---- conditional aggregation ----
                    "SUMIF" -> conditionalAgg(a, sumKind = true) { vals -> vals.sum() }
                    "AVERAGEIF" -> conditionalAgg(a, sumKind = true) { vals -> if (vals.isEmpty()) throw FormulaException("#DIV/0!") else vals.average() }
                    "COUNTIF" -> {
                        val rng = a[0].arg()
                        val crit = v(1)
                        var count = 0
                        forEachRangeCell(rng) { cv -> if (matchesCriteria(cv, crit)) count++ }
                        Value.Num(count.toDouble())
                    }

                    // ---- logical ----
                    "IF" -> if (truthy(v(0))) v(1) else if (a.size > 2) v(2) else Value.Bool(false)
                    "IFS" -> {
                        var i = 0
                        while (i + 1 < a.size) { if (truthy(a[i].value())) return a[i + 1].value(); i += 2 }
                        throw FormulaException("#N/A")
                    }
                    "IFERROR" -> try { val r = v(0); if (r is Value.Err) v(1) else r } catch (_: FormulaException) { v(1) }
                    "IFNA" -> try { val r = v(0); if (r is Value.Err && r.code == "#N/A") v(1) else r } catch (e: FormulaException) { if (e.code == "#N/A") v(1) else throw e }
                    "AND" -> { val f = allVals().mapNotNull { boolOrNull(it) }; Value.Bool(f.isNotEmpty() && f.all { it }) }
                    "OR" -> { val f = allVals().mapNotNull { boolOrNull(it) }; Value.Bool(f.any { it }) }
                    "NOT" -> Value.Bool(!truthy(v(0)))
                    "ISERROR" -> Value.Bool(isError(a[0]) != null)
                    "ISERR" -> Value.Bool(isError(a[0]).let { it != null && it != "#N/A" })
                    "ISNA" -> Value.Bool(isError(a[0]) == "#N/A")
                    "ISNUMBER" -> Value.Bool(safeValue(a[0]) is Value.Num)
                    "ISTEXT" -> Value.Bool(safeValue(a[0]) is Value.Str)
                    "ISBLANK" -> Value.Bool(safeValue(a[0]) == Value.Blank)
                    "ISLOGICAL" -> Value.Bool(safeValue(a[0]) is Value.Bool)
                    "NA" -> throw FormulaException("#N/A")

                    // ---- lookup ----
                    "CHOOSE" -> { val idx = n(0).toInt(); if (idx < 1 || idx >= a.size) throw FormulaException("#VALUE!") else a[idx].value() }
                    "VLOOKUP" -> lookup(a, horizontal = false)
                    "HLOOKUP" -> lookup(a, horizontal = true)
                    "MATCH" -> matchFn(a)
                    "INDEX" -> indexFn(a)

                    // ---- text ----
                    "LEN" -> Value.Num(sArg(0).length.toDouble())
                    "LEFT" -> { val str = sArg(0); val k = if (a.size > 1) n(1).toInt() else 1; Value.Str(str.take(k.coerceAtLeast(0))) }
                    "RIGHT" -> { val str = sArg(0); val k = if (a.size > 1) n(1).toInt() else 1; Value.Str(str.takeLast(k.coerceAtLeast(0))) }
                    "MID" -> { val str = sArg(0); val start = (n(1).toInt() - 1).coerceAtLeast(0); val len = n(2).toInt().coerceAtLeast(0); Value.Str(if (start >= str.length) "" else str.substring(start, minOf(str.length, start + len))) }
                    "UPPER" -> Value.Str(sArg(0).uppercase())
                    "LOWER" -> Value.Str(sArg(0).lowercase())
                    "TRIM" -> Value.Str(sArg(0).trim().replace(Regex("\\s+"), " "))
                    "PROPER" -> Value.Str(sArg(0).split(" ").joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } })
                    "CONCATENATE", "CONCAT" -> Value.Str(allVals().joinToString("") { str(it) })
                    "REPT" -> Value.Str(sArg(0).repeat(n(1).toInt().coerceAtLeast(0)))
                    "EXACT" -> Value.Bool(sArg(0) == sArg(1))
                    "FIND" -> { val start = if (a.size > 2) n(2).toInt() - 1 else 0; val idx = sArg(1).indexOf(sArg(0), start.coerceAtLeast(0)); if (idx < 0) throw FormulaException("#VALUE!") else Value.Num((idx + 1).toDouble()) }
                    "SEARCH" -> { val start = if (a.size > 2) n(2).toInt() - 1 else 0; val idx = sArg(1).indexOf(sArg(0), start.coerceAtLeast(0), ignoreCase = true); if (idx < 0) throw FormulaException("#VALUE!") else Value.Num((idx + 1).toDouble()) }
                    "SUBSTITUTE" -> Value.Str(sArg(0).replace(sArg(1), sArg(2)))
                    "REPLACE" -> { val str = sArg(0); val start = (n(1).toInt() - 1).coerceIn(0, str.length); val len = n(2).toInt().coerceAtLeast(0); val end = minOf(str.length, start + len); Value.Str(str.substring(0, start) + sArg(3) + str.substring(end)) }
                    "VALUE" -> Value.Num(sArg(0).trim().toDoubleOrNull() ?: throw FormulaException("#VALUE!"))
                    "TEXT" -> Value.Str(textFormat(n(0), sArg(1)))

                    // ---- date / time ----
                    "DATE" -> Value.Num(dateSerial(n(0).toInt(), n(1).toInt(), n(2).toInt()))
                    "TODAY" -> Value.Num(nowSerial(false))
                    "NOW" -> Value.Num(nowSerial(true))
                    "YEAR" -> Value.Num(serialToCal(n(0)).get(GregorianCalendar.YEAR).toDouble())
                    "MONTH" -> Value.Num((serialToCal(n(0)).get(GregorianCalendar.MONTH) + 1).toDouble())
                    "DAY" -> Value.Num(serialToCal(n(0)).get(GregorianCalendar.DAY_OF_MONTH).toDouble())
                    "HOUR" -> Value.Num(serialToCal(n(0)).get(GregorianCalendar.HOUR_OF_DAY).toDouble())
                    "MINUTE" -> Value.Num(serialToCal(n(0)).get(GregorianCalendar.MINUTE).toDouble())
                    "SECOND" -> Value.Num(serialToCal(n(0)).get(GregorianCalendar.SECOND).toDouble())
                    "WEEKDAY" -> Value.Num(serialToCal(n(0)).get(GregorianCalendar.DAY_OF_WEEK).toDouble())
                    "TIME" -> Value.Num((n(0) * 3600 + n(1) * 60 + n(2)) / 86400.0)

                    // ---- multi-criteria aggregation (Round 3) ----
                    "SUMIFS" -> {
                        val sumVals = a[0].values()
                        val pairs = (1 until a.size step 2).map { a[it].values() to a[it + 1].value() }
                        var total = 0.0
                        for (k in sumVals.indices) if (pairs.all { (rng, crit) -> k < rng.size && matchesCriteria(rng[k], crit) }) numOrNull(sumVals[k])?.let { total += it }
                        Value.Num(total)
                    }
                    "COUNTIFS" -> {
                        val pairs = (0 until a.size step 2).map { a[it].values() to a[it + 1].value() }
                        val len = pairs.firstOrNull()?.first?.size ?: 0
                        var c = 0
                        for (k in 0 until len) if (pairs.all { (rng, crit) -> k < rng.size && matchesCriteria(rng[k], crit) }) c++
                        Value.Num(c.toDouble())
                    }
                    "AVERAGEIFS" -> {
                        val sumVals = a[0].values()
                        val pairs = (1 until a.size step 2).map { a[it].values() to a[it + 1].value() }
                        var total = 0.0; var cnt = 0
                        for (k in sumVals.indices) if (pairs.all { (rng, crit) -> k < rng.size && matchesCriteria(rng[k], crit) }) numOrNull(sumVals[k])?.let { total += it; cnt++ }
                        if (cnt == 0) throw FormulaException("#DIV/0!") else Value.Num(total / cnt)
                    }
                    "SUMPRODUCT" -> {
                        val arrays = a.map { it.values().map { v -> numOrNull(v) ?: 0.0 } }
                        val len = arrays.minOfOrNull { it.size } ?: 0
                        var total = 0.0
                        for (k in 0 until len) { var p = 1.0; for (arr in arrays) p *= arr[k]; total += p }
                        Value.Num(total)
                    }

                    // ---- extended math (Round 3) ----
                    "SUMSQ" -> Value.Num(allNums().sumOf { it * it })
                    "MROUND" -> { val m = n(1); if (m == 0.0) Value.Num(0.0) else Value.Num(Math.round(n(0) / m) * m) }
                    "EVEN" -> { val x = n(0); val r = ceil(abs(x) / 2.0) * 2.0; Value.Num(if (x < 0) -r else r) }
                    "ODD" -> { val x = n(0); var r = ceil(abs(x)); if (r % 2.0 == 0.0) r += 1.0; if (r < 1.0) r = 1.0; Value.Num(if (x < 0) -r else r) }
                    "GCD" -> { val ints = allNums().map { abs(it).toLong() }; Value.Num((ints.reduceOrNull { x, y -> gcdL(x, y) } ?: 0L).toDouble()) }
                    "LCM" -> { val ints = allNums().map { abs(it).toLong() }; Value.Num((ints.reduceOrNull { x, y -> if (x == 0L || y == 0L) 0L else x / gcdL(x, y) * y } ?: 0L).toDouble()) }
                    "FACT" -> { var r = 1.0; val k = n(0).toInt(); if (k < 0) throw FormulaException("#NUM!"); for (i in 2..k) r *= i; Value.Num(r) }
                    "COMBIN" -> { val nn = n(0).toInt(); val k = n(1).toInt(); if (k < 0 || k > nn) throw FormulaException("#NUM!"); var r = 1.0; for (i in 0 until k) r = r * (nn - i) / (i + 1); Value.Num(Math.round(r).toDouble()) }
                    "RAND" -> Value.Num(Math.random())
                    "RANDBETWEEN" -> { val lo = n(0).toLong(); val hi = n(1).toLong(); Value.Num((lo + (Math.random() * (hi - lo + 1)).toLong()).toDouble()) }
                    "SIN" -> Value.Num(kotlin.math.sin(n(0)))
                    "COS" -> Value.Num(kotlin.math.cos(n(0)))
                    "TAN" -> Value.Num(kotlin.math.tan(n(0)))
                    "ASIN" -> Value.Num(kotlin.math.asin(n(0)))
                    "ACOS" -> Value.Num(kotlin.math.acos(n(0)))
                    "ATAN" -> Value.Num(kotlin.math.atan(n(0)))
                    "ATAN2" -> Value.Num(kotlin.math.atan2(n(1), n(0)))
                    "RADIANS" -> Value.Num(Math.toRadians(n(0)))
                    "DEGREES" -> Value.Num(Math.toDegrees(n(0)))

                    // ---- extended statistics (Round 3) ----
                    "STDEVP" -> allNums().let { if (it.isEmpty()) throw FormulaException("#DIV/0!") else { val m = it.average(); Value.Num(sqrt(it.sumOf { x -> (x - m).pow(2) } / it.size)) } }
                    "VARP" -> allNums().let { if (it.isEmpty()) throw FormulaException("#DIV/0!") else { val m = it.average(); Value.Num(it.sumOf { x -> (x - m).pow(2) } / it.size) } }
                    "MODE" -> allNums().let { ns -> if (ns.isEmpty()) throw FormulaException("#N/A") else ns.groupingBy { it }.eachCount().maxByOrNull { it.value }!!.key.let { Value.Num(it) } }
                    "RANK" -> { val x = n(0); val list = a[1].values().mapNotNull { numOrNull(it) }; val asc = a.size > 2 && truthy(v(2)); val sorted = if (asc) list.sorted() else list.sortedDescending(); val idx = sorted.indexOf(x); if (idx < 0) throw FormulaException("#N/A") else Value.Num((idx + 1).toDouble()) }
                    "LARGE" -> { val list = a[0].values().mapNotNull { numOrNull(it) }.sortedDescending(); val k = n(1).toInt(); if (k < 1 || k > list.size) throw FormulaException("#NUM!") else Value.Num(list[k - 1]) }
                    "SMALL" -> { val list = a[0].values().mapNotNull { numOrNull(it) }.sorted(); val k = n(1).toInt(); if (k < 1 || k > list.size) throw FormulaException("#NUM!") else Value.Num(list[k - 1]) }
                    "PERCENTILE" -> { val list = a[0].values().mapNotNull { numOrNull(it) }.sorted(); val p = n(1); if (list.isEmpty() || p < 0 || p > 1) throw FormulaException("#NUM!"); val rank = p * (list.size - 1); val lo = floor(rank).toInt(); val hi = ceil(rank).toInt(); Value.Num(if (lo == hi) list[lo] else list[lo] + (rank - lo) * (list[hi] - list[lo])) }

                    // ---- extended logical (Round 3) ----
                    "SWITCH" -> {
                        val subject = v(0); var i = 1
                        while (i + 1 < a.size) { if (compareOp("=", subject, a[i].value())) return a[i + 1].value(); i += 2 }
                        if (i < a.size) a[i].value() else throw FormulaException("#N/A")
                    }
                    "XOR" -> { val f = allVals().mapNotNull { boolOrNull(it) }; Value.Bool(f.count { it } % 2 == 1) }

                    // ---- extended text (Round 3) ----
                    "TEXTJOIN" -> { val delim = sArg(0); val ignoreEmpty = truthy(v(1)); val parts = a.drop(2).flatMap { it.values() }.map { str(it) }.filter { !ignoreEmpty || it.isNotEmpty() }; Value.Str(parts.joinToString(delim)) }
                    "CHAR" -> Value.Str(n(0).toInt().toChar().toString())
                    "CODE" -> { val s = sArg(0); if (s.isEmpty()) throw FormulaException("#VALUE!") else Value.Num(s[0].code.toDouble()) }
                    "T" -> { val r = v(0); if (r is Value.Str) r else Value.Str("") }
                    "CLEAN" -> Value.Str(sArg(0).filter { it.code >= 0x20 })
                    "NUMBERVALUE" -> Value.Num(sArg(0).trim().replace(",", "").toDoubleOrNull() ?: throw FormulaException("#VALUE!"))

                    // ---- lookup dimensions (Round 3) ----
                    "ROWS" -> (a[0].arg() as? Arg.RangeRef)?.let { Value.Num((abs(it.r2 - it.r1) + 1).toDouble()) } ?: Value.Num(1.0)
                    "COLUMNS" -> (a[0].arg() as? Arg.RangeRef)?.let { Value.Num((abs(it.c2 - it.c1) + 1).toDouble()) } ?: Value.Num(1.0)

                    // ---- extended date (Round 3) ----
                    "DAYS" -> Value.Num(floor(n(0)) - floor(n(1)))
                    "DATEVALUE" -> Value.Num(floor(n(0)))
                    "EDATE" -> { val c = serialToCal(n(0)); c.add(GregorianCalendar.MONTH, n(1).toInt()); Value.Num(dateSerial(c.get(GregorianCalendar.YEAR), c.get(GregorianCalendar.MONTH) + 1, c.get(GregorianCalendar.DAY_OF_MONTH))) }
                    "EOMONTH" -> { val c = serialToCal(n(0)); c.add(GregorianCalendar.MONTH, n(1).toInt() + 1); c.set(GregorianCalendar.DAY_OF_MONTH, 1); c.add(GregorianCalendar.DAY_OF_MONTH, -1); Value.Num(dateSerial(c.get(GregorianCalendar.YEAR), c.get(GregorianCalendar.MONTH) + 1, c.get(GregorianCalendar.DAY_OF_MONTH))) }
                    "WEEKNUM" -> Value.Num(serialToCal(n(0)).get(GregorianCalendar.WEEK_OF_YEAR).toDouble())
                    "DATEDIF" -> {
                        val c1 = serialToCal(n(0)); val c2 = serialToCal(n(1)); val unit = sArg(2).uppercase()
                        when (unit) {
                            "D" -> Value.Num(floor(n(1)) - floor(n(0)))
                            "M" -> Value.Num(((c2.get(GregorianCalendar.YEAR) - c1.get(GregorianCalendar.YEAR)) * 12 + (c2.get(GregorianCalendar.MONTH) - c1.get(GregorianCalendar.MONTH))).toDouble())
                            "Y" -> Value.Num((c2.get(GregorianCalendar.YEAR) - c1.get(GregorianCalendar.YEAR)).toDouble())
                            else -> throw FormulaException("#NUM!")
                        }
                    }
                    "NETWORKDAYS" -> {
                        var s = floor(n(0)).toInt(); val e = floor(n(1)).toInt(); var cnt = 0
                        val lo = minOf(s, e); val hi = maxOf(s, e)
                        for (d in lo..hi) { val dow = serialToCal(d.toDouble()).get(GregorianCalendar.DAY_OF_WEEK); if (dow != GregorianCalendar.SATURDAY && dow != GregorianCalendar.SUNDAY) cnt++ }
                        Value.Num((if (e < s) -cnt else cnt).toDouble())
                    }
                    "WORKDAY" -> {
                        var d = floor(n(0)).toInt(); var remaining = n(1).toInt(); val step = if (remaining >= 0) 1 else -1
                        while (remaining != 0) { d += step; val dow = serialToCal(d.toDouble()).get(GregorianCalendar.DAY_OF_WEEK); if (dow != GregorianCalendar.SATURDAY && dow != GregorianCalendar.SUNDAY) remaining -= step }
                        Value.Num(d.toDouble())
                    }

                    // ---- financial (Round 3) ----
                    "PMT" -> { val r = n(0); val nper = n(1); val pv = n(2); val fv = if (a.size > 3) n(3) else 0.0; Value.Num(if (r == 0.0) -(pv + fv) / nper else -(pv * (1 + r).pow(nper) + fv) * r / ((1 + r).pow(nper) - 1)) }
                    "FV" -> { val r = n(0); val nper = n(1); val pmt = n(2); val pv = if (a.size > 3) n(3) else 0.0; Value.Num(if (r == 0.0) -(pv + pmt * nper) else -(pv * (1 + r).pow(nper) + pmt * ((1 + r).pow(nper) - 1) / r)) }
                    "PV" -> { val r = n(0); val nper = n(1); val pmt = n(2); val fv = if (a.size > 3) n(3) else 0.0; Value.Num(if (r == 0.0) -(fv + pmt * nper) else -(fv + pmt * ((1 + r).pow(nper) - 1) / r) / (1 + r).pow(nper)) }
                    "NPV" -> { val r = n(0); var total = 0.0; var t = 1; for (i in 1 until a.size) for (cf in a[i].values().mapNotNull { numOrNull(it) }) { total += cf / (1 + r).pow(t); t++ }; Value.Num(total) }
                    "NPER" -> { val r = n(0); val pmt = n(1); val pv = n(2); val fv = if (a.size > 3) n(3) else 0.0; Value.Num(if (r == 0.0) -(pv + fv) / pmt else ln((pmt - fv * r) / (pmt + pv * r)) / ln(1 + r)) }

                    else -> throw FormulaException("#NAME?")
                }
            }

            private fun boolOrNull(v: Value): Boolean? = when (v) {
                is Value.Num -> v.v != 0.0
                is Value.Bool -> v.b
                else -> null
            }

            private fun gcdL(x: Long, y: Long): Long = if (y == 0L) abs(x) else gcdL(y, x % y)

            /** Returns the error code if the thunk evaluates to/throws an error, else null. */
            private fun isError(t: ArgThunk): String? = try {
                (t.value() as? Value.Err)?.code
            } catch (e: FormulaException) { e.code }

            /** Evaluates a thunk, returning Value.Err instead of throwing (for IS* type checks). */
            private fun safeValue(t: ArgThunk): Value = try { t.value() } catch (e: FormulaException) { Value.Err(e.code) }

            private fun forEachRangeCell(arg: Arg, action: (Value) -> Unit) {
                when (arg) {
                    is Arg.Scalar -> action(arg.v)
                    is Arg.RangeRef -> for (r in minOf(arg.r1, arg.r2)..maxOf(arg.r1, arg.r2))
                        for (c in minOf(arg.c1, arg.c2)..maxOf(arg.c1, arg.c2)) {
                            val cell = sheet.rows.getOrNull(r)?.cells?.getOrNull(c)
                            if (cell != null && cell.isCovered) continue
                            action(evaluateCellV(r, c))
                        }
                }
            }

            /** SUMIF/AVERAGEIF: range, criteria, [sumRange]. Aligns sumRange by offset. */
            private fun conditionalAgg(a: List<ArgThunk>, sumKind: Boolean, reduce: (List<Double>) -> Double): Value {
                val rangeArg = a[0].arg()
                val crit = a[1].value()
                val sumArg = if (a.size > 2) a[2].arg() else rangeArg
                val rRef = rangeArg as? Arg.RangeRef
                val sRef = sumArg as? Arg.RangeRef
                val matched = ArrayList<Double>()
                if (rRef != null) {
                    val r0 = minOf(rRef.r1, rRef.r2); val c0 = minOf(rRef.c1, rRef.c2)
                    val rs0 = sRef?.let { minOf(it.r1, it.r2) } ?: r0
                    val cs0 = sRef?.let { minOf(it.c1, it.c2) } ?: c0
                    for (r in r0..maxOf(rRef.r1, rRef.r2)) {
                        for (c in c0..maxOf(rRef.c1, rRef.c2)) {
                            val cell = sheet.rows.getOrNull(r)?.cells?.getOrNull(c)
                            if (cell != null && cell.isCovered) continue
                            if (matchesCriteria(evaluateCellV(r, c), crit)) {
                                val sv = evaluateCellV(rs0 + (r - r0), cs0 + (c - c0))
                                numOrNull(sv)?.let { matched.add(it) }
                            }
                        }
                    }
                }
                if (!sumKind) return Value.Num(matched.size.toDouble())
                return Value.Num(reduce(matched))
            }

            private fun lookup(a: List<ArgThunk>, horizontal: Boolean): Value {
                val key = a[0].value()
                val rng = a[1].arg() as? Arg.RangeRef ?: throw FormulaException("#N/A")
                val index = n2(a, 2).toInt() // 1-based row/col offset into range
                val approx = if (a.size > 3) truthy(a[3].value()) else true
                val r0 = minOf(rng.r1, rng.r2); val r1e = maxOf(rng.r1, rng.r2)
                val c0 = minOf(rng.c1, rng.c2); val c1e = maxOf(rng.c1, rng.c2)
                var foundLine = -1
                if (horizontal) {
                    var best = -1
                    for (c in c0..c1e) {
                        val cv = evaluateCellV(r0, c)
                        if (compareOp("=", cv, key)) { foundLine = c; break }
                        if (approx && cmpNum(cv) != null && cmpNum(key) != null && cmpNum(cv)!! <= cmpNum(key)!!) best = c
                    }
                    if (foundLine < 0) foundLine = best
                    if (foundLine < 0) throw FormulaException("#N/A")
                    val targetRow = r0 + index - 1
                    if (targetRow > r1e) throw FormulaException("#REF!")
                    return evaluateCellV(targetRow, foundLine)
                } else {
                    var best = -1
                    for (r in r0..r1e) {
                        val cv = evaluateCellV(r, c0)
                        if (compareOp("=", cv, key)) { foundLine = r; break }
                        if (approx && cmpNum(cv) != null && cmpNum(key) != null && cmpNum(cv)!! <= cmpNum(key)!!) best = r
                    }
                    if (foundLine < 0) foundLine = best
                    if (foundLine < 0) throw FormulaException("#N/A")
                    val targetCol = c0 + index - 1
                    if (targetCol > c1e) throw FormulaException("#REF!")
                    return evaluateCellV(foundLine, targetCol)
                }
            }

            private fun matchFn(a: List<ArgThunk>): Value {
                val key = a[0].value()
                val rng = a[1].arg() as? Arg.RangeRef ?: throw FormulaException("#N/A")
                val type = if (a.size > 2) n2(a, 2).toInt() else 1
                val cells = ArrayList<Value>()
                for (r in minOf(rng.r1, rng.r2)..maxOf(rng.r1, rng.r2))
                    for (c in minOf(rng.c1, rng.c2)..maxOf(rng.c1, rng.c2))
                        cells.add(evaluateCellV(r, c))
                when (type) {
                    0 -> { val i = cells.indexOfFirst { compareOp("=", it, key) }; if (i < 0) throw FormulaException("#N/A") else return Value.Num((i + 1).toDouble()) }
                    1 -> { // largest value <= key, assumes ascending
                        var best = -1
                        val k = cmpNum(key)
                        for ((i, cv) in cells.withIndex()) { val n = cmpNum(cv); if (k != null && n != null && n <= k) best = i }
                        if (best < 0) throw FormulaException("#N/A") else return Value.Num((best + 1).toDouble())
                    }
                    else -> { // -1: smallest value >= key, assumes descending
                        var best = -1
                        val k = cmpNum(key)
                        for ((i, cv) in cells.withIndex()) { val n = cmpNum(cv); if (k != null && n != null && n >= k) best = i }
                        if (best < 0) throw FormulaException("#N/A") else return Value.Num((best + 1).toDouble())
                    }
                }
            }

            private fun indexFn(a: List<ArgThunk>): Value {
                val rng = a[0].arg() as? Arg.RangeRef ?: return a[0].value()
                val r0 = minOf(rng.r1, rng.r2); val c0 = minOf(rng.c1, rng.c2)
                val rn = if (a.size > 1) n2(a, 1).toInt() else 1
                val cn = if (a.size > 2) n2(a, 2).toInt() else 1
                val targetRow = if (rn <= 0) r0 else r0 + rn - 1
                val targetCol = if (cn <= 0) c0 else c0 + cn - 1
                if (targetRow > maxOf(rng.r1, rng.r2) || targetCol > maxOf(rng.c1, rng.c2)) throw FormulaException("#REF!")
                return evaluateCellV(targetRow, targetCol)
            }

            private fun n2(a: List<ArgThunk>, i: Int): Double = num(a[i].value())

            private fun textFormat(value: Double, fmt: String): String {
                val f = fmt.trim()
                if (f.contains("%")) {
                    val decimals = f.substringAfter('.', "").count { it == '0' || it == '#' }
                    return String.format("%.${decimals}f%%", value * 100)
                }
                val grouping = f.contains(",")
                val decimals = f.substringAfter('.', "").count { it == '0' || it == '#' }
                val pattern = (if (grouping) "%,." else "%.") + decimals + "f"
                return String.format(pattern, value)
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
