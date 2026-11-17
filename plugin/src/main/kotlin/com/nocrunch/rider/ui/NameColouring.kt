package com.nocrunch.rider.ui

/** How test names are split into parts that get alternating colours in the tool window. */
enum class NameColouring(val title: String) {
    OFF("Off"),
    SNAKE("Snake Case Parts"),
    PASCAL("Pascal Case Words");

    companion object {
        /** Number of colours the parts cycle through. */
        const val COLOURS = 3

        /**
         * Splits [name] into (text, colour index) segments. Punctuation (`_`, `.`, `/` and so on) makes separate
         * segments with no colour, so data-row arguments such as `(100,10,90)` get a colour per value.
         * Colour indexes cycle 0..[COLOURS]-1 across the parts that do get a colour.
         */
        fun split(name: String, mode: NameColouring): List<Pair<String, Int?>> {
            if (mode == OFF || name.isEmpty()) return listOf(name to null)
            val pieces = mutableListOf<Pair<String, Boolean>>() // text, isWord
            for (chunk in Regex("[^\\p{L}\\p{N}]+|[\\p{L}\\p{N}]+").findAll(name).map { it.value }) {
                if (!chunk.first().isLetterOrDigit()) pieces += chunk to false
                else if (mode == SNAKE) pieces += chunk to true
                else words(chunk).forEach { pieces += it to true }
            }
            var next = 0
            return pieces.map { (text, isWord) -> text to if (isWord) next++ % COLOURS else null }
        }

        /**
         * Splits a theory row's arguments, such as `(quantity: 10, sku: "A, B")`, so each argument name and each value
         * takes the next colour. Values stay whole, including commas inside quotes, brackets or braces. Text that
         * isn't a bracketed argument list comes back as one plain segment.
         */
        fun splitArgs(text: String): List<Pair<String, Int?>> {
            if (text.length < 2 || text.first() != '(' || text.last() != ')') return listOf(text to null)
            val out = mutableListOf<Pair<String, Boolean>>("(" to false)
            topLevelArgs(text.substring(1, text.length - 1)).forEachIndexed { i, arg ->
                if (i > 0) out += arg.separator to false
                val m = NAMED_ARG.matchEntire(arg.text)
                if (m != null) {
                    val (lead, name, colon, value, trail) = m.destructured
                    if (lead.isNotEmpty()) out += lead to false
                    out += name to true
                    out += colon to false
                    out += value to true
                    if (trail.isNotEmpty()) out += trail to false
                } else {
                    val lead = arg.text.takeWhile { it.isWhitespace() }
                    val value = arg.text.trim()
                    if (lead.isNotEmpty()) out += lead to false
                    if (value.isNotEmpty()) out += value to true
                    val trail = arg.text.takeLastWhile { it.isWhitespace() }.takeIf { value.isNotEmpty() }.orEmpty()
                    if (trail.isNotEmpty()) out += trail to false
                }
            }
            out += ")" to false
            var next = 0
            return out.map { (t, coloured) -> t to if (coloured) next++ % COLOURS else null }
        }

        private val NAMED_ARG = Regex("""(\s*)([A-Za-z_][A-Za-z0-9_]*)(\s*:\s*)(.*?)(\s*)""", RegexOption.DOT_MATCHES_ALL)

        private class Arg(val separator: String, val text: String)

        /** The arguments of [text] split on top-level commas, as written (spaces included). */
        fun topLevel(text: String): List<String> = topLevelArgs(text).map { it.text }

        /**
         * Adds parameter names to a row's arguments when the framework left them out (MSTest's `(150)` with
         * `ms` → `(ms: 150)`). Left alone if any argument is already named or the counts don't match.
         */
        fun withNames(text: String, names: List<String>?): String {
            if (names.isNullOrEmpty() || text.length < 2 || text.first() != '(' || text.last() != ')') return text
            val args = topLevelArgs(text.substring(1, text.length - 1)).map { it.text.trim() }
            if (args.size != names.size || args.any { NAMED_ARG.matches(it) }) return text
            return names.zip(args).joinToString(", ", "(", ")") { (n, v) -> "$n: $v" }
        }

        /** Splits on commas that aren't inside quotes, brackets or braces; each arg remembers the comma (and spaces) before it. */
        private fun topLevelArgs(s: String): List<Arg> {
            val out = mutableListOf<Arg>()
            var depth = 0
            var quote: Char? = null
            var start = 0
            var sep = ""
            var i = 0
            while (i < s.length) {
                val c = s[i]
                when {
                    quote != null -> if (c == '\\') i++ else if (c == quote) quote = null
                    c == '"' || c == '\'' -> quote = c
                    c in "([{" -> depth++
                    c in ")]}" -> depth = maxOf(0, depth - 1)
                    c == ',' && depth == 0 -> {
                        out += Arg(sep, s.substring(start, i))
                        var j = i + 1
                        while (j < s.length && s[j] == ' ') j++
                        sep = s.substring(i, j)
                        start = j
                        i = j - 1
                    }
                }
                i++
            }
            out += Arg(sep, s.substring(start))
            return out
        }

        /**
         * PascalCase words. A new word starts at a capital after a lower-case letter, at a capital followed by lower
         * case after a capital or digit (`HTTPResponse` → `HTTP`, `Response`), and at digits after a lower-case
         * letter (`Returns404`). Digits after a capital stay with it, so acronyms like `E2E` and `HTTP2` are one word.
         */
        private fun words(s: String): List<String> {
            val out = mutableListOf<String>()
            var start = 0
            for (i in 1 until s.length) {
                val prev = s[i - 1]
                val c = s[i]
                val nextLower = i + 1 < s.length && s[i + 1].isLowerCase()
                val boundary = (c.isUpperCase() && prev.isLowerCase()) ||
                    (c.isUpperCase() && (prev.isUpperCase() || prev.isDigit()) && nextLower) ||
                    (c.isDigit() && prev.isLowerCase())
                if (boundary) { out += s.substring(start, i); start = i }
            }
            out += s.substring(start)
            return out
        }
    }
}
