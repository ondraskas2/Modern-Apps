package com.vayunmathur.office.odf

import com.vayunmathur.library.ui.odf.OdfCell
import com.vayunmathur.library.ui.odf.OdfRow
import com.vayunmathur.library.ui.odf.OdfSheet
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Focused checks for the OpenFormula engine, exercising representative functions added in
 * Phase 1 (info/logical, math, bitwise, stats, date, lookup) plus percent literals.
 */
class OdfFormulaEngineTest {

    /**
     * Evaluates [formula] placed at (atRow, atCol) on a grid seeded with [data] (0-based
     * cell -> numeric value), returning the engine's display string.
     */
    private fun evalFormula(
        formula: String,
        data: Map<Pair<Int, Int>, Double> = emptyMap(),
        atRow: Int = 5,
        atCol: Int = 5
    ): String {
        val maxR = ((data.keys.map { it.first } + atRow).max()) + 1
        val maxC = ((data.keys.map { it.second } + atCol).max()) + 1
        val rows = (0 until maxR).map { r ->
            OdfRow((0 until maxC).map { c ->
                when {
                    r == atRow && c == atCol -> OdfCell(text = "", formula = "of:=$formula")
                    data.containsKey(r to c) -> OdfCell(text = data[r to c]!!.toString(), numberValue = data[r to c])
                    else -> OdfCell(text = "")
                }
            })
        }
        return OdfFormulaEngine.displayValue(OdfSheet("S", rows), atRow, atCol)
    }

    @Test fun sumBaseline() { assertEquals("6", evalFormula("SUM(1;2;3)")) }

    @Test fun percentLiteral() { assertEquals("5", evalFormula("50%*10")) }

    @Test fun quotient() { assertEquals("3", evalFormula("QUOTIENT(17;5)")) }

    @Test fun bitand() { assertEquals("8", evalFormula("BITAND(12;10)")) }

    @Test fun isoWeekNum() { assertEquals("1", evalFormula("ISOWEEKNUM(DATE(2020;1;1))")) }

    @Test fun yearFrac() { assertEquals("1", evalFormula("YEARFRAC(DATE(2020;1;1);DATE(2021;1;1))")) }

    @Test fun rowSelf() { assertEquals("6", evalFormula("ROW()")) } // atRow=5 (0-based) -> 6

    @Test fun offsetResolvesCell() {
        assertEquals("99", evalFormula("OFFSET([.C3];0;0)", data = mapOf((2 to 2) to 99.0)))
    }

    @Test fun quotientDivByZeroErrors() { assertEquals("#DIV/0!", evalFormula("QUOTIENT(1;0)")) }
}
