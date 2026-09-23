package com.chelayel.airelay.agent

/**
 * A unified diff of two texts, for showing what an edit did. Plain LCS on
 * lines; files past [MAX_LINES] on either side get a one-line summary instead,
 * because the point is a glance at the change, not a byte-exact patch.
 */
object Diff {
    const val MAX_LINES = 4000
    private const val CONTEXT = 3

    fun unified(before: String, after: String, path: String): String {
        val a = before.lines().let { if (before.endsWith("\n")) it.dropLast(1) else it }
        val b = after.lines().let { if (after.endsWith("\n")) it.dropLast(1) else it }
        if (a.size > MAX_LINES || b.size > MAX_LINES) return "--- $path\n+++ $path\n@@ ${a.size} → ${b.size} lines (too large to diff) @@\n"
        val ops = ops(a, b)
        val out = StringBuilder("--- $path\n+++ $path\n")
        var i = 0
        while (i < ops.size) {
            if (ops[i].kind == ' ') { i++; continue }
            // A hunk: from CONTEXT before the first change to CONTEXT after the last.
            val start = maxOf(0, i - CONTEXT)
            var end = i
            var lastChange = i
            while (end < ops.size && end - lastChange <= CONTEXT * 2) {
                if (ops[end].kind != ' ') lastChange = end
                end++
            }
            end = minOf(ops.size, lastChange + CONTEXT + 1)
            val slice = ops.subList(start, end)
            val aStart = slice.first().aIndex + 1
            val bStart = slice.first().bIndex + 1
            val aLen = slice.count { it.kind != '+' }
            val bLen = slice.count { it.kind != '-' }
            out.append("@@ -$aStart,$aLen +$bStart,$bLen @@\n")
            for (op in slice) out.append(op.kind).append(op.text).append('\n')
            i = end
        }
        return out.toString()
    }

    private class Op(val kind: Char, val text: String, val aIndex: Int, val bIndex: Int)

    private fun ops(a: List<String>, b: List<String>): List<Op> {
        // Trim the common head and tail first; most edits are local.
        var head = 0
        while (head < a.size && head < b.size && a[head] == b[head]) head++
        var tail = 0
        while (tail < a.size - head && tail < b.size - head && a[a.size - 1 - tail] == b[b.size - 1 - tail]) tail++
        val am = a.subList(head, a.size - tail)
        val bm = b.subList(head, b.size - tail)
        val lcs = Array(am.size + 1) { IntArray(bm.size + 1) }
        for (i in am.indices.reversed()) for (j in bm.indices.reversed()) {
            lcs[i][j] = if (am[i] == bm[j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
        }
        val out = ArrayList<Op>(a.size + b.size)
        for (k in 0 until head) out.add(Op(' ', a[k], k, k))
        var i = 0; var j = 0
        while (i < am.size && j < bm.size) {
            when {
                am[i] == bm[j] -> { out.add(Op(' ', am[i], head + i, head + j)); i++; j++ }
                lcs[i + 1][j] >= lcs[i][j + 1] -> { out.add(Op('-', am[i], head + i, head + j)); i++ }
                else -> { out.add(Op('+', bm[j], head + i, head + j)); j++ }
            }
        }
        while (i < am.size) { out.add(Op('-', am[i], head + i, head + j)); i++ }
        while (j < bm.size) { out.add(Op('+', bm[j], head + i, head + j)); j++ }
        for (k in 0 until tail) out.add(Op(' ', a[a.size - tail + k], a.size - tail + k, b.size - tail + k))
        return out
    }
}
