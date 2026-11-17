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
