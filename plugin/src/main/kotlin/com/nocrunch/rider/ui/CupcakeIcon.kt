package com.nocrunch.rider.ui

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

/**
 * The No Crunch tool-window icon: a cupcake whose frosting shows a tick, a cross, or nothing. Passing and failing fill
 * the frosting, paused fills it amber, and while the engine works the sprinkles pulse in turn (an animated variant).
 */
object CupcakeIcon {
    /** Time each frame of an animated variant stays up. */
    const val FRAME_MILLIS = 150L

    /** One icon per frame; a static variant has a single frame. */
    enum class Variant(val paths: List<String>) {
        PLAIN("/icons/cupcake.svg"),
        PASS("/icons/cupcake_pass.svg"),
        FAIL("/icons/cupcake_fail.svg"),
        PAUSED("/icons/cupcake_paused.svg"),
        WORKING((0..5).map { "/icons/cupcake_progress_$it.svg" });

        constructor(path: String) : this(listOf(path))

        val isAnimated: Boolean get() = paths.size > 1

        /** IntelliJ picks the `_dark.svg` sibling automatically in dark themes. */
        val frames: List<Icon> by lazy { paths.map { IconLoader.getIcon(it, CupcakeIcon::class.java) } }

        val icon: Icon get() = frames.first()
    }

    fun variantFor(colour: BarColour, paused: Boolean = false): Variant = if (paused) Variant.PAUSED else when (colour) {
        BarColour.PASSING -> Variant.PASS
        BarColour.FAILING -> Variant.FAIL
        BarColour.RUNNING -> Variant.WORKING
        BarColour.NEUTRAL -> Variant.PLAIN
    }
}
