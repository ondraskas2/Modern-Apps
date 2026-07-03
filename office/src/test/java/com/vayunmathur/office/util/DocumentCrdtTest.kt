package com.vayunmathur.office.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentCrdtTest {

    private fun basePair(base: List<String>): Pair<DocumentCrdt, DocumentCrdt> {
        val a = DocumentCrdt("A")
        val ops = a.update(base)
        val b = DocumentCrdt("B")
        b.apply(ops)
        assertEquals(a.render(), b.render())
        return a to b
    }

    @Test
    fun update_from_empty_renders_input() {
        val a = DocumentCrdt("A")
        a.update(listOf("l1", "l2", "l3"))
        assertEquals(listOf("l1", "l2", "l3"), a.render())
    }

    @Test
    fun idempotent_apply() {
        val a = DocumentCrdt("A")
        val ops = a.update(listOf("x", "y"))
        val b = DocumentCrdt("B")
        b.apply(ops)
        b.apply(ops) // applying twice must not duplicate
        assertEquals(listOf("x", "y"), b.render())
    }

    @Test
    fun concurrent_edits_to_different_lines_merge() {
        val (a, b) = basePair(listOf("a", "b", "c"))
        // A edits line "a" -> "a2"; B edits line "c" -> "c2" (edit = delete + insert).
        val opsA = a.update(listOf("a2", "b", "c"))
        val opsB = b.update(listOf("a", "b", "c2"))
        a.apply(opsB)
        b.apply(opsA)
        // Both replicas converge and both edits survive.
        assertEquals(a.render(), b.render())
        assertTrue(a.render().contains("a2"))
        assertTrue(a.render().contains("c2"))
        assertTrue(a.render().contains("b"))
    }

    @Test
    fun concurrent_inserts_at_same_position_both_survive_and_converge() {
        val (a, b) = basePair(listOf("start", "end"))
        val opsA = a.update(listOf("start", "A-line", "end"))
        val opsB = b.update(listOf("start", "B-line", "end"))
        a.apply(opsB)
        b.apply(opsA)
        assertEquals(a.render(), b.render()) // deterministic order on both sides
        assertTrue(a.render().containsAll(listOf("start", "end", "A-line", "B-line")))
    }

    @Test
    fun apply_order_independent() {
        val a = DocumentCrdt("A")
        val op1 = a.update(listOf("1"))
        val op2 = a.update(listOf("1", "2"))
        val op3 = a.update(listOf("1", "2", "3"))
        val b = DocumentCrdt("B")
        // Apply in a scrambled order.
        b.apply(op3); b.apply(op1); b.apply(op2)
        assertEquals(a.render(), b.render())
    }

    @Test
    fun concurrent_delete_and_insert_converge() {
        val (a, b) = basePair(listOf("keep", "drop"))
        val opsA = a.update(listOf("keep")) // A deletes "drop"
        val opsB = b.update(listOf("keep", "drop", "new")) // B adds "new"
        a.apply(opsB)
        b.apply(opsA)
        assertEquals(a.render(), b.render())
        assertTrue(a.render().contains("keep"))
        assertTrue(a.render().contains("new"))
        assertTrue(!a.render().contains("drop")) // delete wins
    }

    @Test
    fun state_roundtrip_preserves_render() {
        val a = DocumentCrdt("A")
        a.update(listOf("p", "q", "r"))
        val restored = DocumentCrdt.fromState(a.toState())
        assertEquals(a.render(), restored.render())
        // Continues to work after restore.
        restored.update(listOf("p", "q", "r", "s"))
        assertEquals(listOf("p", "q", "r", "s"), restored.render())
    }

    @Test
    fun codec_roundtrips_flat_odf() {
        val xml = "<office:document><body><text:p>Hello</text:p><text:p/></body></office:document>"
        val lines = OfficeCrdtCodec.toLines(xml)
        assertTrue(lines.size > 1) // actually split into element lines
        assertEquals(xml, OfficeCrdtCodec.fromLines(lines))
    }

    @Test
    fun codec_merge_preserves_valid_join() {
        val xml = "<r><a/><b/><c/></r>"
        val a = DocumentCrdt("A")
        val baseOps = a.update(OfficeCrdtCodec.toLines(xml))
        val b = DocumentCrdt("B")
        b.apply(baseOps)
        // A inserts <x/> after <a/>, B inserts <y/> after <b/>
        val opsA = a.update(OfficeCrdtCodec.toLines("<r><a/><x/><b/><c/></r>"))
        val opsB = b.update(OfficeCrdtCodec.toLines("<r><a/><b/><y/><c/></r>"))
        a.apply(opsB); b.apply(opsA)
        assertEquals(a.render(), b.render())
        val merged = OfficeCrdtCodec.fromLines(a.render())
        assertTrue(merged.contains("<x/>"))
        assertTrue(merged.contains("<y/>"))
    }
}
