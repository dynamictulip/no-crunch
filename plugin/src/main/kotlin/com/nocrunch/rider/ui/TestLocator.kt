package com.nocrunch.rider.ui

/** Finds places in a test's source: where its method name is, and where its body starts. */
object TestLocator {
    private const val LOOKAHEAD_LINES = 15

    private fun lineStarts(text: CharSequence) = buildList { add(0); text.forEachIndexed { i, c -> if (c == '\n') add(i + 1) } }

    private fun lineOf(starts: List<Int>, offset: Int) = starts.indexOfLast { it <= offset }.coerceAtLeast(0)

    /** Offset of the method's name on [line] or the next few lines (the reported line is often an attribute). */
    private fun nameOffset(text: CharSequence, starts: List<Int>, line: Int, methodName: String?): Int? {
        if (methodName.isNullOrBlank() || line !in starts.indices) return null
        val from = starts[line]
        val to = starts.getOrNull(line + LOOKAHEAD_LINES) ?: text.length
        return Regex("""\b${Regex.escape(methodName)}\s*[(<]""").find(text.subSequence(from, to))?.let { from + it.range.first }
    }

    /**
     * Frameworks report a test's line as the start of its declaration, which is often an attribute like
     * `[TestMethod]`. Finds the method's name on that line or the next few; otherwise the line's first non-blank.
     */
    fun caretOffset(text: CharSequence, line: Int, methodName: String?): Int {
        val starts = lineStarts(text)
        if (line !in starts.indices) return 0
        nameOffset(text, starts, line, methodName)?.let { return it }
        val from = starts[line]
        val end = starts.getOrNull(line + 1) ?: text.length
        return (from until end).firstOrNull { !text[it].isWhitespace() } ?: from
    }

    /**
     * The 0-based line of the test's first statement: the first code after the body's `{`, or the expression after
     * `=>`. Null if the method can't be found.
     */
    fun firstBodyLine(text: CharSequence, line: Int, methodName: String?): Int? {
        val starts = lineStarts(text)
        var i = nameOffset(text, starts, line, methodName) ?: return null
        // Skip the parameter list, which can itself contain parentheses (e.g. default values or attributes).
        var depth = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '(') depth++
            if (c == ')') { depth--; if (depth == 0) { i++; break } }
            i++
        }
        while (i < text.length) {
            if (text[i] == '{') {
                val code = nextCode(text, i + 1) ?: i
                return lineOf(starts, if (text[code] == '}') i else code)
            }
            if (text[i] == '=' && text.getOrNull(i + 1) == '>') {
                return lineOf(starts, nextCode(text, i + 2) ?: i)
            }
            if (text[i] == ';') return null
            i++
        }
        return null
    }

    /**
     * Names of the test method's parameters, in order, from its declaration at [line] (or the next few lines).
     * Attributes, modifiers, types and default values are skipped. Null if the method can't be found.
     */
    fun parameterNames(text: CharSequence, line: Int, methodName: String?): List<String>? {
        val starts = lineStarts(text)
        var i = nameOffset(text, starts, line, methodName) ?: return null
        // Skip generic arguments to the opening parenthesis.
        while (i < text.length && text[i] != '(') i++
        if (i >= text.length) return null
        val open = i
        var depth = 0
        while (i < text.length) {
            if (text[i] in "([{<") depth++
            if (text[i] in ")]}>") { depth--; if (depth == 0) break }
            i++
        }
        // Drop generic arguments (`Dictionary<string, int>`) so their commas don't split parameters.
        var inner = text.subSequence(open + 1, minOf(i, text.length)).toString()
        while (true) { val next = inner.replace(Regex("<[^<>]*>"), ""); if (next == inner) break; inner = next }
        if (inner.isBlank()) return emptyList()
        return NameColouring.topLevel(inner).map { param ->
            val noAttrs = param.replace(Regex("""\[[^\]]*]"""), " ")
            val noDefault = noAttrs.substringBefore('=')
            Regex("""@?[A-Za-z_][A-Za-z0-9_]*""").findAll(noDefault).lastOrNull()?.value?.removePrefix("@") ?: return null
        }
    }

    /** Offset of the next code from [from], skipping whitespace and comments (a breakpoint there wouldn't bind). */
    private fun nextCode(text: CharSequence, from: Int): Int? {
        var i = from
        while (i < text.length) {
            when {
                text[i].isWhitespace() -> i++
                text.startsWith("//", i) -> i = text.indexOf('\n', i).let { if (it < 0) text.length else it }
                text.startsWith("/*", i) -> i = text.indexOf("*/", i + 2).let { if (it < 0) text.length else it + 2 }
                else -> return i
            }
        }
        return null
    }
}
