package com.filamentvision.core.vision

fun interface NanoClock {
    fun nanoTime(): Long

    companion object {
        val SYSTEM = NanoClock(System::nanoTime)
    }
}
