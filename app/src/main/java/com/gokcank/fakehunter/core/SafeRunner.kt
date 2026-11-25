package com.gokcank.fakehunter.core

import android.util.Log

/**
 * Executes a block of code safely, suppressing any unexpected hardware/platform exceptions.
 * Crucial for fragmented Chinese Android Head Units and TV boxes where random APIs may crash.
 */
inline fun <T> safeRun(tag: String = "FakeHunter", default: T, block: () -> T): T {
    return try {
        block()
    } catch (e: java.util.concurrent.CancellationException) {
        throw e
    } catch (e: Exception) {
        try {
            Log.w(tag, "SafeRun caught an exception: ${e.message}", e)
        } catch (_: RuntimeException) {
            // Allow execution in plain JVM unit tests where android.util.Log is not mocked
        }
        default
    }
}
