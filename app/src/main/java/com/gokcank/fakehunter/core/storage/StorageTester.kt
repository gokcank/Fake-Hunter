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
    private const val SAFETY_MARGIN_MB = 50

    fun runVerificationTest(
        context: Context,
        testMb: Int = 100,
        physicalRamMb: Long = 0L,
        onProgress: (percent: Int, statusText: String) -> Unit
    ): StorageTestResult {
        if (testMb <= 0) {
            return StorageTestResult(
                testedMb = 0,
                writeSpeedMbPerSec = 0.0,
                readSpeedMbPerSec = 0.0,
                isCorrupted = false,
                message = "Invalid test size (${testMb}MB): must be greater than 0MB."
            )
        }

        val targetDir = resolveWritableDir(context)
        val runSeed = System.nanoTime()
        val testFile = File(targetDir, ".fakehunter_storage_verify_${runSeed}.tmp")
        val cacheWarning = physicalRamMb > 0L && testMb.toLong() < physicalRamMb

        var writeSpeed = 0.0
        var readSpeed = 0.0
        var isCorrupted = false
        var message = "Verification successful: All written bytes verified intact."

        try {
            val requiredMb = testMb.toLong() + SAFETY_MARGIN_MB
            val freeMb = targetDir.freeSpace / (1024L * 1024L)
            if (freeMb < requiredMb) {
                return StorageTestResult(
                    testedMb = 0,
                    writeSpeedMbPerSec = 0.0,
                    readSpeedMbPerSec = 0.0,
                    isCorrupted = false,
                    message = "Insufficient free disk space: Only ${freeMb}MB free, needed ${requiredMb}MB (${testMb}MB test + ${SAFETY_MARGIN_MB}MB safety margin)."
                )
            }

            val buffer = ByteArray(BLOCK_SIZE)
            val checksums = LongArray(testMb)

            // 1. Write Phase (unique per-block deterministic pattern + fsync)
            onProgress(0, "Writing test blocks (${testMb} MB)...")
            val writeStartNs = System.nanoTime()

            FileOutputStream(testFile).use { fos ->
                for (i in 0 until testMb) {
                    fillUniqueBlock(buffer, i, runSeed)

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
                // Force OS page cache flush to physical flash before measuring/verifying
                safeRun("StorageFsync", Unit) { fos.fd.sync() }
            }
            val writeTimeMs = ((System.nanoTime() - writeStartNs) / 1_000_000L).coerceAtLeast(1L)
            writeSpeed = testMb.toDouble() / (writeTimeMs / 1000.0)

            // 2. Read & Verify Phase
            onProgress(50, "Reading & verifying blocks...")
            val readStartNs = System.nanoTime()

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
            val readTimeMs = ((System.nanoTime() - readStartNs) / 1_000_000L).coerceAtLeast(1L)
            readSpeed = testMb.toDouble() / (readTimeMs / 1000.0)

            onProgress(100, if (isCorrupted) "Corruption Detected!" else "Verification Complete")
        } catch (e: Exception) {
            // Separate I/O or permission errors from genuine flash corruption
            return StorageTestResult(
                testedMb = 0,
                writeSpeedMbPerSec = 0.0,
                readSpeedMbPerSec = 0.0,
                isCorrupted = false,
                hasError = true,
                cacheWarning = cacheWarning,
                message = "I/O or permission error during test: ${e.message ?: e.javaClass.simpleName}"
            )
        } finally {
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
            hasError = false,
            cacheWarning = cacheWarning,
            message = message
        )
    }

    private fun resolveWritableDir(context: Context): File {
        val extDir = safeRun("ExtFilesDir", null) { context.getExternalFilesDir(null) }
        if (extDir != null && (extDir.exists() || extDir.mkdirs()) && extDir.canWrite()) {
            return extDir
        }
        return context.filesDir.apply {
            if (!exists()) mkdirs()
        }
    }

    /**
     * Fills a block with a fast deterministic SplitMix64 sequence unique to both
     * the block index (no 256 MB repetition) and the per-run seed.
     */
    internal fun fillUniqueBlock(buffer: ByteArray, blockIndex: Int, runSeed: Long) {
        var state = runSeed xor ((blockIndex.toLong() + 1L) * -0x61c8864680b583ebL)
        var offset = 0
        val len = buffer.size
        while (offset + 8 <= len) {
            state += -0x61c8864680b583ebL
            var z = state
            z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
            z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
            z = z xor (z ushr 31)
            buffer[offset] = z.toByte()
            buffer[offset + 1] = (z ushr 8).toByte()
            buffer[offset + 2] = (z ushr 16).toByte()
            buffer[offset + 3] = (z ushr 24).toByte()
            buffer[offset + 4] = (z ushr 32).toByte()
            buffer[offset + 5] = (z ushr 40).toByte()
            buffer[offset + 6] = (z ushr 48).toByte()
            buffer[offset + 7] = (z ushr 56).toByte()
            offset += 8
        }
        while (offset < len) {
            state = state * 6364136223846793005L + 1442695040888963407L
            buffer[offset++] = (state ushr 56).toByte()
        }
    }
}
