package com.nocrunch.rider.ui

import java.security.MessageDigest

/**
 * Hash of a source file's text, to tell whether an open document still matches the file the engine collected coverage
 * against: SHA-256 (lower-case hex) of the text with CRLF turned into LF. The engine computes the same value.
 */
object ContentHash {
    fun of(text: CharSequence): String {
        val bytes = text.toString().replace("\r\n", "\n").toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
