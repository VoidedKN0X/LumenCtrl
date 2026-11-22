package com.vknxworks.lumenctrl.data

/**
 * Rules for importing a settings file: it may only make privacy stricter. Plain Kotlin (no Android), so it can be
 * unit-tested.
 */
object ImportRules {
    /** A privacy switch that is on now stays on; a file can only turn it on. */
    fun keepOn(current: Boolean, incoming: Boolean): Boolean = current || incoming

    /**
     * The stricter of [current] and [incoming], where [order] runs from least to most private. A value that isn't
     * in [order] never wins over the current one.
     */
    fun <T> stricterOf(current: T, incoming: T, order: List<T>): T {
        val now = order.indexOf(current)
        val next = order.indexOf(incoming)
        if (next < 0) return current
        return if (next > now) incoming else current
    }
}
