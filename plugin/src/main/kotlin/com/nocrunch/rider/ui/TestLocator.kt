package com.nocrunch.rider.ui

/** Where to put the caret so Rider's test actions see the test method. */
object TestLocator {
    private const val LOOKAHEAD_LINES = 15

    /**
     * Frameworks report a test's line as the start of its declaration, which is often an attribute like
     * `[TestMethod]`. Finds the method's name on that line or the next few; otherwise the line's first non-blank.
     */
    fun caretOffset(text: CharSequence, line: Int, methodName: String?): Int {
        val lineStarts = buildList { add(0); text.forEachIndexed { i, c -> if (c == '\n') add(i + 1) } }
        if (line !in lineStarts.indices) return 0
        val from = lineStarts[line]
        if (!methodName.isNullOrBlank()) {
            val to = lineStarts.getOrNull(line + LOOKAHEAD_LINES) ?: text.length
            Regex("""\b${Regex.escape(methodName)}\s*[(<]""").find(text.subSequence(from, to))?.let { return from + it.range.first }
        }
        val end = lineStarts.getOrNull(line + 1) ?: text.length
        val indent = (from until end).firstOrNull { !text[it].isWhitespace() } ?: from
        return indent
    }
}
