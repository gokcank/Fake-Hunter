package com.gokcank.fakehunter.core.storage

import com.gokcank.fakehunter.core.model.StorageTestResult
import org.junit.Assert.*
import org.junit.Test

class StorageTesterTest {

    @Test
    fun fillUniqueBlockDoesNotRepeatAcross256MbBoundary() {
        val seed = 987654321L
        val block0 = ByteArray(65536)
        val block0Again = ByteArray(65536)
        val block1 = ByteArray(65536)
        val block256 = ByteArray(65536)
        val block512 = ByteArray(65536)

        StorageTester.fillUniqueBlock(block0, blockIndex = 0, runSeed = seed)
        StorageTester.fillUniqueBlock(block0Again, blockIndex = 0, runSeed = seed)
        StorageTester.fillUniqueBlock(block1, blockIndex = 1, runSeed = seed)
        StorageTester.fillUniqueBlock(block256, blockIndex = 256, runSeed = seed)
        StorageTester.fillUniqueBlock(block512, blockIndex = 512, runSeed = seed)

        assertArrayEquals("Same blockIndex and seed must produce deterministic bytes", block0, block0Again)
        assertFalse("Block 0 and Block 1 must differ", block0.contentEquals(block1))
        assertFalse("Block 0 and Block 256 must NOT wrap/repeat", block0.contentEquals(block256))
        assertFalse("Block 256 and Block 512 must NOT wrap/repeat", block256.contentEquals(block512))
    }

    @Test
    fun storageTestResultDistinguishesSkippedIoErrorCorruptedAndPassed() {
        val skipped = StorageTestResult(
            testedMb = 0,
            writeSpeedMbPerSec = 0.0,
            readSpeedMbPerSec = 0.0,
            isCorrupted = false,
            hasError = false,
            message = "Insufficient free disk space"
        )
        assertTrue(skipped.wasSkipped)

        val ioError = StorageTestResult(
            testedMb = 0,
            writeSpeedMbPerSec = 0.0,
            readSpeedMbPerSec = 0.0,
            isCorrupted = false,
            hasError = true,
            message = "Permission denied"
        )
        assertFalse("I/O error must not be silently treated as skipped", ioError.wasSkipped)
        assertFalse("I/O error must not be mislabeled as flash corruption", ioError.isCorrupted)

        val corrupted = StorageTestResult(
            testedMb = 100,
            writeSpeedMbPerSec = 12.4,
            readSpeedMbPerSec = 18.2,
            isCorrupted = true,
            hasError = false,
            message = "Checksum mismatch at block 32"
        )
        assertFalse(corrupted.wasSkipped)
        assertTrue(corrupted.isCorrupted)

        val passed = StorageTestResult(
            testedMb = 100,
            writeSpeedMbPerSec = 45.0,
            readSpeedMbPerSec = 120.0,
            isCorrupted = false,
            hasError = false,
            cacheWarning = true,
            message = "OK"
        )
        assertFalse(passed.wasSkipped)
        assertTrue(passed.cacheWarning)
    }
}
