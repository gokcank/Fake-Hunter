package com.gokcank.fakehunter.core

import android.util.Log

/**
 * Executes a block of code safely, suppressing any unexpected hardware/platform exceptions.
 * Crucial for fragmented Chinese Android Head Units and TV boxes where random APIs may crash.
 */
inline fun <T> safeRun(tag: String = "FakeHunter", default: T, block: () -> T): T {
    return try {
        block()
    } catch (t: Throwable) {
        Log.w(tag, "SafeRun caught an exception: ${t.message}", t)
        default
    }
}
