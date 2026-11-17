package com.nocrunch.rider.ui

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

/** The No Crunch tool-window icon: a cupcake whose frosting shows a tick, a cross, or nothing. */
object CupcakeIcon {
    enum class Variant(val path: String) {
        PLAIN("/icons/cupcake.svg"),
        PASS("/icons/cupcake_pass.svg"),
        FAIL("/icons/cupcake_fail.svg"),
        PAUSED("/icons/cupcake_paused.svg");

        /** IntelliJ picks the `_dark.svg` sibling automatically in dark themes. */
        val icon: Icon by lazy { IconLoader.getIcon(path, CupcakeIcon::class.java) }
    }

    fun variantFor(colour: BarColour, paused: Boolean = false): Variant = if (paused) Variant.PAUSED else when (colour) {
        BarColour.PASSING -> Variant.PASS
        BarColour.FAILING -> Variant.FAIL
        BarColour.RUNNING, BarColour.NEUTRAL -> Variant.PLAIN
    }
}
