package com.nocrunch.rider.ui

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.ui.JBColor
import java.awt.Color

/**
 * Colours for name parts, taken from the editor's colour scheme so they follow the theme: keyword, method and type,
 * as they appear in C# code. Rider's own C# keys are tried first, then the platform's general ones. A slot the scheme
 * leaves unset, or that matches an earlier slot or the plain text colour, falls back to a built-in colour.
 */
object ThemeColours {
    private val SLOTS: List<List<() -> TextAttributesKey>> = listOf(
        listOf({ TextAttributesKey.find("ReSharper.KEYWORD") }, { DefaultLanguageHighlighterColors.KEYWORD }),
        listOf({ TextAttributesKey.find("ReSharper.METHOD_IDENTIFIER") }, { DefaultLanguageHighlighterColors.FUNCTION_DECLARATION }),
        listOf({ TextAttributesKey.find("ReSharper.CLASS_IDENTIFIER") }, { DefaultLanguageHighlighterColors.CLASS_NAME }),
    )

    /** Blue, purple, teal: used when the scheme doesn't give a usable colour. */
    private val FALLBACK = listOf(
        JBColor(Color(0x2F, 0x6F, 0xC9), Color(0x6A, 0xA7, 0xF8)),
        JBColor(Color(0x9A, 0x4F, 0x8E), Color(0xC7, 0x7D, 0xBB)),
        JBColor(Color(0x24, 0x87, 0x74), Color(0x5F, 0xB8, 0xA6)),
    )

    private var cachedFor: Pair<EditorColorsScheme, Long>? = null
    private var cached: List<Color> = FALLBACK

    /** The three colours for the current scheme (recomputed when the scheme changes). */
    fun nameParts(): List<Color> {
        val scheme = EditorColorsManager.getInstance().globalScheme
        val stamp = scheme to EditorColorsManager.getInstance().schemeModificationCounter
        if (cachedFor != stamp) {
            cached = resolve(scheme)
            cachedFor = stamp
        }
        return cached
    }

    private fun resolve(scheme: EditorColorsScheme): List<Color> {
        val plain = scheme.defaultForeground
        val chosen = mutableListOf<Color>()
        SLOTS.forEachIndexed { i, keys ->
            val colour = keys.asSequence()
                .mapNotNull { runCatching { scheme.getAttributes(it())?.foregroundColor }.getOrNull() }
                .firstOrNull { c -> c != plain && chosen.none { it == c } }
            chosen += colour ?: FALLBACK[i]
        }
        return chosen
    }
}
