package com.filamentvision.core.frame

/**
 * Read-only ARGB32 pixel access. [ints] takes ownership of its array without copying it:
 * the caller must stop mutating that array after transfer. Adapters copy external arrays
 * once before transfer; downstream frame consumers share this view.
 */
class PixelBuffer private constructor(private val values: IntArray) {
    val count: Int get() = values.size

    operator fun get(index: Int): Int = values[index]

    companion object {
        fun ints(ownedValues: IntArray): PixelBuffer = PixelBuffer(ownedValues)
    }
}
