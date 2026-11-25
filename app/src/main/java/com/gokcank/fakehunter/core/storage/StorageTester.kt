package com.gokcank.fakehunter.core.storage

import android.content.Context
import com.gokcank.fakehunter.core.model.StorageTestResult
import com.gokcank.fakehunter.core.safeRun
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.CRC32

object StorageTester {

    private const val BLOCK_SIZE = 1024 * 1024 // 1 MB per block
    private const val TEST_FILENAME = ".fakehunter_storage_verify.tmp"

    fun runVerificationTest(
        context: Context,
        testMb: Int = 100,
        onProgress: (percent: Int, statusText: String) -> Unit
    ): StorageTestResult {
        val targetDir = context.getExternalFilesDir(null) ?: context.filesDir
        val testFile = File(targetDir, TEST_FILENAME)

        var writeSpeed = 0.0
        var readSpeed = 0.0
        var isCorrupted = false
        var message = "Verification successful: All written bytes verified intact."

        try {
            // Check available space
            val freeMb = testFile.parentFile?.freeSpace?.div(1024 * 1024) ?: 0L
            if (freeMb < testMb + 50) {
                return StorageTestResult(
                    testedMb = 0,
                    writeSpeedMbPerSec = 0.0,
                    readSpeedMbPerSec = 0.0,
                    isCorrupted = false,
                    message = "Insufficient free disk space: Only ${freeMb}MB free, needed ${testMb}MB."
                )
            }

            val buffer = ByteArray(BLOCK_SIZE)
            val checksums = LongArray(testMb)

            // 1. Write Phase
            onProgress(0, "Writing test blocks (${testMb} MB)...")
            val writeStart = System.currentTimeMillis()

            FileOutputStream(testFile).use { fos ->
                for (i in 0 until testMb) {
                    // Fill buffer with pseudo-random deterministic pattern
                    val seed = (i * 31 + 7).toByte()
                    for (b in 0 until BLOCK_SIZE) {
                        buffer[b] = (seed + (b % 127)).toByte()
                    }

                    // Compute block CRC32
                    val crc = CRC32()
                    crc.update(buffer, 0, BLOCK_SIZE)
                    checksums[i] = crc.value

                    fos.write(buffer, 0, BLOCK_SIZE)

                    val percent = ((i + 1) * 50) / testMb
                    if (i % 20 == 0 || i == testMb - 1) {
                        onProgress(percent, "Writing block ${i + 1} / $testMb MB...")
                    }
                }
                fos.flush()
            }
            val writeTimeMs = (System.currentTimeMillis() - writeStart).coerceAtLeast(1)
            writeSpeed = (testMb.toDouble() / (writeTimeMs / 1000.0))

            // 2. Read & Verify Phase
            onProgress(50, "Reading & verifying blocks...")
            val readStart = System.currentTimeMillis()

            FileInputStream(testFile).use { fis ->
                for (i in 0 until testMb) {
                    var bytesRead = 0
                    while (bytesRead < BLOCK_SIZE) {
                        val count = fis.read(buffer, bytesRead, BLOCK_SIZE - bytesRead)
                        if (count == -1) break
                        bytesRead += count
                    }

                    if (bytesRead != BLOCK_SIZE) {
                        isCorrupted = true
                        message = "Storage Read Error: Unexpected EOF at block $i ($bytesRead / $BLOCK_SIZE bytes)"
                        break
                    }

                    val crc = CRC32()
                    crc.update(buffer, 0, BLOCK_SIZE)
                    if (crc.value != checksums[i]) {
                        isCorrupted = true
                        message = "DATA CORRUPTION DETECTED at block $i: Checksum mismatch! Hardware memory may be fake or looping."
                        break
                    }

                    val percent = 50 + (((i + 1) * 50) / testMb)
                    if (i % 20 == 0 || i == testMb - 1) {
                        onProgress(percent, "Verifying block ${i + 1} / $testMb MB...")
                    }
                }
            }
            val readTimeMs = (System.currentTimeMillis() - readStart).coerceAtLeast(1)
            readSpeed = (testMb.toDouble() / (readTimeMs / 1000.0))

            onProgress(100, if (isCorrupted) "Corruption Detected!" else "Verification Complete")

        } catch (e: Exception) {
            isCorrupted = true
            message = "Storage test failed with error: ${e.message}"
        } finally {
            // Always clean up test file
            safeRun("StorageTestCleanup", Unit) {
                if (testFile.exists()) {
                    testFile.delete()
                }
            }
        }

        return StorageTestResult(
            testedMb = testMb,
            writeSpeedMbPerSec = Math.round(writeSpeed * 10.0) / 10.0,
            readSpeedMbPerSec = Math.round(readSpeed * 10.0) / 10.0,
            isCorrupted = isCorrupted,
            message = message
        )
    }
}
