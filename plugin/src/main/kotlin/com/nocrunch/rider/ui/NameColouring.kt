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
         * Splits [name] into (text, colour index) segments. Underscores are separate segments with no colour.
         * Colour indexes cycle 0..[COLOURS]-1 across the parts that do get a colour.
         */
        fun split(name: String, mode: NameColouring): List<Pair<String, Int?>> {
            if (mode == OFF || name.isEmpty()) return listOf(name to null)
            val pieces = mutableListOf<Pair<String, Boolean>>() // text, isWord
            for (chunk in Regex("_+|[^_]+").findAll(name).map { it.value }) {
                if (chunk.startsWith('_')) pieces += chunk to false
                else if (mode == SNAKE) pieces += chunk to true
                else words(chunk).forEach { pieces += it to true }
            }
            var next = 0
            return pieces.map { (text, isWord) -> text to if (isWord) next++ % COLOURS else null }
        }

        /** PascalCase words: a new word starts at a capital after a lower-case letter or digit, at the last capital
         *  of an acronym followed by lower case (`HTTPResponse` → `HTTP`, `Response`), and where digits begin. */
        private fun words(s: String): List<String> {
            val out = mutableListOf<String>()
            var start = 0
            for (i in 1 until s.length) {
                val prev = s[i - 1]
                val c = s[i]
                val boundary = (c.isUpperCase() && (prev.isLowerCase() || prev.isDigit())) ||
                    (c.isUpperCase() && prev.isUpperCase() && i + 1 < s.length && s[i + 1].isLowerCase()) ||
                    (c.isDigit() && !prev.isDigit())
                if (boundary) { out += s.substring(start, i); start = i }
            }
            out += s.substring(start)
            return out
        }
    }
}
