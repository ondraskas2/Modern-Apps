package com.vayunmathur.office.util

import kotlinx.serialization.Serializable

/**
 * A convergent, operation-based sequence CRDT used to merge concurrent edits to a document without
 * a central authority and without last-writer-wins clobbering.
 *
 * The document is represented as an ordered sequence of opaque **lines** (we split the serialized
 * flat-ODF at tag boundaries, so each element/paragraph is roughly one line — see [OfficeCrdtCodec]).
 * Each line is an RGA element with a globally-unique id `(lamport, device)` and an `origin` (the id
 * of the element it was inserted after). Deletions are tombstones. Merging is:
 *   - **element set union** by id (idempotent),
 *   - **deletion is monotonic** (a tombstone, once set, stays — delete wins),
 *   - **ordering** via RGA linearization (children of the same origin ordered by id descending),
 * which is commutative and associative, so all replicas that have seen the same ops converge to the
 * same sequence regardless of arrival order.
 *
 * Editing is snapshot-driven: [update] diffs the CRDT's current rendered lines against a new set of
 * lines (LCS) and emits the insert/delete ops needed, mutating the state. Concurrent edits to the
 * *same* line are preserved as two lines (no data loss) rather than one silently winning.
 */
class DocumentCrdt(
    val device: String,
    var clock: Long = 0,
    private val elements: LinkedHashMap<String, CrdtElement> = LinkedHashMap(),
) {
    @Serializable
    data class CrdtElement(
        val lamport: Long,
        val device: String,
        val originLamport: Long, // -1 => head
        val originDevice: String, // "" => head
        var content: String,
        var deleted: Boolean = false,
    )

    @Serializable
    data class State(val device: String, val clock: Long, val elements: List<CrdtElement>)

    private fun key(lamport: Long, dev: String) = "$lamport:$dev"

    fun toState(): State = State(device, clock, elements.values.map { it.copy() })

    /** Merges a batch of remote ops (elements) into this replica. Commutative + idempotent. */
    fun apply(ops: List<CrdtElement>) {
        for (op in ops) {
            clock = maxOf(clock, op.lamport)
            val k = key(op.lamport, op.device)
            val cur = elements[k]
            if (cur == null) {
                elements[k] = op.copy()
            } else {
                // Same element id: deletion is monotonic; content is immutable per id in our diff
                // model (edits are delete+insert), so we only ever need to OR the tombstone.
                if (op.deleted) cur.deleted = true
            }
        }
    }

    /** RGA linearization of all elements (including tombstones), in convergent order. */
    private fun linearize(): List<CrdtElement> {
        val children = HashMap<String, MutableList<CrdtElement>>()
        for (e in elements.values) {
            val ok = if (e.originLamport < 0) HEAD else key(e.originLamport, e.originDevice)
            children.getOrPut(ok) { mutableListOf() }.add(e)
        }
        // Concurrent inserts after the same origin: higher id first (RGA tie-break) — deterministic.
        val cmp = compareByDescending<CrdtElement> { it.lamport }.thenByDescending { it.device }
        for (l in children.values) l.sortWith(cmp)
        val result = ArrayList<CrdtElement>(elements.size)
        // Iterative pre-order DFS to avoid deep recursion on large documents.
        val stack = ArrayDeque<CrdtElement>()
        children[HEAD]?.asReversed()?.forEach { stack.addLast(it) }
        while (stack.isNotEmpty()) {
            val e = stack.removeLast()
            result.add(e)
            children[key(e.lamport, e.device)]?.asReversed()?.forEach { stack.addLast(it) }
        }
        return result
    }

    /** The current merged document as an ordered list of live (non-deleted) lines. */
    fun render(): List<String> = linearize().filter { !it.deleted }.map { it.content }

    /**
     * Diffs the current rendered lines against [newLines] and produces the ops that transform one
     * into the other, applying them to this replica. Returns the ops to broadcast (may be empty).
     */
    fun update(newLines: List<String>): List<CrdtElement> {
        val live = linearize().filter { !it.deleted }
        val oldLines = live.map { it.content }
        val n = oldLines.size
        val m = newLines.size

        // Trim the common prefix/suffix so the (expensive) LCS runs only over the changed middle.
        // This makes a typical single-character edit O(1)-ish instead of O(n*m).
        var pre = 0
        while (pre < n && pre < m && oldLines[pre] == newLines[pre]) pre++
        var suf = 0
        while (suf < (n - pre) && suf < (m - pre) && oldLines[n - 1 - suf] == newLines[m - 1 - suf]) suf++

        val oldMid = oldLines.subList(pre, n - suf)
        val newMid = newLines.subList(pre, m - suf)
        val a = oldMid.size
        val b = newMid.size

        // LCS over the middle only.
        val dp = Array(a + 1) { IntArray(b + 1) }
        for (i in a - 1 downTo 0) {
            for (j in b - 1 downTo 0) {
                dp[i][j] = if (oldMid[i] == newMid[j]) dp[i + 1][j + 1] + 1
                else maxOf(dp[i + 1][j], dp[i][j + 1])
            }
        }
        val newMidToOldMid = HashMap<Int, Int>()
        run {
            var i = 0; var j = 0
            while (i < a && j < b) {
                when {
                    oldMid[i] == newMid[j] -> { newMidToOldMid[j] = i; i++; j++ }
                    dp[i + 1][j] >= dp[i][j + 1] -> i++
                    else -> j++
                }
            }
        }
        val matchedOldMid = newMidToOldMid.values.toHashSet()

        val ops = ArrayList<CrdtElement>()
        // Deletions: middle old lines not matched become tombstones (prefix/suffix are untouched).
        for (idx in oldMid.indices) {
            if (idx !in matchedOldMid) {
                val e = live[pre + idx]
                if (!e.deleted) {
                    e.deleted = true
                    ops.add(e.copy())
                }
            }
        }
        // Insertions: walk the new middle; inserts chain after the running "left" element, which
        // starts at the last prefix element (or head).
        var leftLamport = if (pre > 0) live[pre - 1].lamport else -1L
        var leftDevice = if (pre > 0) live[pre - 1].device else ""
        for (nj in newMid.indices) {
            val oIdx = newMidToOldMid[nj]
            if (oIdx != null) {
                val e = live[pre + oIdx]
                leftLamport = e.lamport; leftDevice = e.device
            } else {
                clock++
                val e = CrdtElement(
                    lamport = clock, device = device,
                    originLamport = if (leftLamport < 0) -1 else leftLamport,
                    originDevice = leftDevice,
                    content = newMid[nj],
                )
                elements[key(clock, device)] = e
                ops.add(e.copy())
                leftLamport = clock; leftDevice = device
            }
        }
        return ops
    }

    companion object {
        private const val HEAD = "HEAD"

        fun fromState(state: State): DocumentCrdt {
            val map = LinkedHashMap<String, CrdtElement>()
            for (e in state.elements) map["${e.lamport}:${e.device}"] = e.copy()
            return DocumentCrdt(state.device, state.clock, map)
        }
    }
}

/**
 * Splits/joins the serialized flat-ODF document into CRDT "lines". The serializer emits the whole
 * document with essentially no newlines, so we split at adjacent-tag boundaries (`><`), which puts
 * each element/paragraph on its own line. Joining is exact and lossless (we only ever split at
 * boundaries we introduced), so non-conflicting concurrent edits recombine into valid flat-ODF.
 */
object OfficeCrdtCodec {
    private const val SEP = "\u0001"
    fun toLines(flatOdf: String): List<String> = flatOdf.replace("><", ">$SEP<").split(SEP)
    fun fromLines(lines: List<String>): String = lines.joinToString("")
}
