package com.nocrunch.rider.ui

/**
 * Alphanumeric order: numbers inside names compare by value, so `a, a1, a2, a10` (not `a, a1, a10, a2`).
 * Letters compare ignoring case, with exact case only as a final tie-break.
 */
object NaturalOrder : Comparator<String> {
    private val CHUNK = Regex("""\d+|\D+""")

    override fun compare(a: String, b: String): Int {
        val x = CHUNK.findAll(a).map { it.value }.toList()
        val y = CHUNK.findAll(b).map { it.value }.toList()
        for (i in 0 until minOf(x.size, y.size)) {
            val p = x[i]
            val q = y[i]
            val c = if (p[0].isDigit() && q[0].isDigit()) {
                val pt = p.trimStart('0')
                val qt = q.trimStart('0')
                if (pt.length != qt.length) pt.length.compareTo(qt.length) else pt.compareTo(qt).takeIf { it != 0 } ?: p.length.compareTo(q.length)
            } else p.compareTo(q, ignoreCase = true)
            if (c != 0) return c
        }
        return if (x.size != y.size) x.size.compareTo(y.size) else a.compareTo(b)
    }
}
