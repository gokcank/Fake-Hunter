package com.gokcank.fakehunter.core.export

import com.gokcank.fakehunter.core.model.*
import com.gokcank.fakehunter.core.qr.QrHelper
import com.gokcank.fakehunter.core.root.RootHelper
import com.gokcank.fakehunter.core.safeRun
import com.gokcank.fakehunter.core.settings.LocaleHelper
import com.gokcank.fakehunter.core.settings.SettingsManager
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale
import java.util.concurrent.CancellationException

class AuditSerializationTest {

    private fun sampleAdversarialReport(): AuditReport {
        return AuditReport(
            system = SystemInfo(
                model = "Car \"T3\"\\Unit\nLine2\tTab\u0001",
                manufacturer = "OEM\\Brand",
                platform = "ac8227l",
                board = "full_8227l",
                hardware = "mt8227",
                reportedRelease = "14",
                actualSdkLevel = 27,
                displayId = "ALPS.\"FAKE\".2026"
            ),
            ram = RamInfo(
                physicalMb = 1980L,
                swapMb = 3072L,
                isSwapInflated = true,
                reportedTotalMb = 8192L,
                isRamSpoofed = true,
                swapBackedByZram = false
            ),
            cpu = CpuInfo(
                reportedCores = 8,
                activeCores = 4,
                coreDetails = (0 until 8).map {
                    CoreDetail(it, isOnline = it < 4, currentFreqMhz = if (it < 4) 1200L else 0L, maxFreqMhz = 1300L)
                },
                isFakeCoresDetected = true,
                wakeTestPerformed = true,
                frequenciesReadable = true
            ),
            storage = StorageInfo(
                totalDataMb = 58000L,
                freeDataMb = 52000L,
                flashChips = listOf(FlashChip("mmcblk0", 14910L, readViaRoot = true, isRemovable = false)),
                flashChipsComplete = true,
                externalChips = listOf(FlashChip("sda", 61000L, readViaRoot = false, isRemovable = true))
            ),
            root = RootInfo(
                isRootBinaryFound = true,
                isRootGranted = true,
                selinuxMode = "Permissive",
                rawPartitions = listOf("179 0 15267840 mmcblk0"),
                dmesgHighlights = listOf("mmc0: new HS200 MMC card at address 0001"),
                rootRequested = true
            ),
            storageTest = StorageTestResult(
                testedMb = 100,
                writeSpeedMbPerSec = 9.5,
                readSpeedMbPerSec = 14.2,
                isCorrupted = true,
                hasError = false,
                cacheWarning = true,
                message = "Mismatch at \"block 16\"\\loop"
            ),
            verdict = Verdict.FAKE_DETECTED,
            issues = listOf(
                AuditIssue(
                    severity = IssueSeverity.FAKE,
                    title = "RAM \"Spoofed\"",
                    description = "Claimed 8GB\nActual 2GB",
                    code = IssueCode.RAM_SIZE_SPOOFED
                )
            ),
            timestampMillis = 1795651200000L
        )
    }

    @Test
    fun escapeJsonStringEscapesQuotesSlashesAndControlCharacters() {
        val raw = "A\"B\\C\nD\rE\tF\bG\u000CH\u0002 Türkçe ğüşıöç"
        val escaped = EvidenceCollector.escapeJsonString(raw)
        assertEquals("A\\\"B\\\\C\\nD\\rE\\tF\\bG\\fH\\u0002 Türkçe ğüşıöç", escaped)
    }

    @Test
    fun buildJsonSummaryHandlesAdversarialInputsAndIncludesAllSections() {
        val json = EvidenceCollector.buildJsonSummary(sampleAdversarialReport())

        // Must contain no raw control characters inside string values (only \n formatting newlines)
        assertFalse(json.any { it.code < 0x20 && it != '\n' })
        assertTrue(json.contains("\"verdict\": \"FAKE_DETECTED\""))
        assertTrue(json.contains("\"model\": \"Car \\\"T3\\\"\\\\Unit\\nLine2\\tTab\\u0001\""))
        assertTrue(json.contains("\"internalChips\": [{\"name\": \"mmcblk0\", \"sizeMb\": 14910, \"readViaRoot\": true}]"))
        assertTrue(json.contains("\"externalChips\": [{\"name\": \"sda\", \"sizeMb\": 61000, \"readViaRoot\": false}]"))
        assertTrue(json.contains("\"issuesCount\": 1"))
        assertTrue(json.contains("\"severity\": \"FAKE\""))
    }

    @Test
    fun generateZipFileNameSanitizesUnsafeCharactersAndEmptyNames() {
        val report = sampleAdversarialReport()
        val fileName = EvidenceCollector.generateZipFileName(report)
        assertTrue(fileName.startsWith("FakeHunter_Car_"))
        assertTrue(fileName.endsWith(".zip"))
        assertFalse(fileName.contains("\"") || fileName.contains("\\") || fileName.contains("\n"))

        val blankModelReport = report.copy(system = report.system.copy(model = "///???"))
        val fallbackName = EvidenceCollector.generateZipFileName(blankModelReport)
        assertTrue(fallbackName.startsWith("FakeHunter_Device_"))
    }

    @Test
    fun qrSummaryUsesPlainAsciiMarkersAndReflectsExternalStorageAndErrors() {
        val report = sampleAdversarialReport().copy(
            system = sampleAdversarialReport().system.copy(model = "AC8227L HeadUnit", manufacturer = "Alps")
        )
        val qrText = QrHelper.generateAuditSummary(report)

        assertTrue(qrText.startsWith("[FAKE HUNTER REPORT]"))
        assertTrue(qrText.contains("[!] RAM SPOOFED!"))
        assertTrue(qrText.contains("[!] FAKE CORES DETECTED!"))
        assertTrue(qrText.contains("[!] STORAGE CORRUPT!"))
        assertTrue(qrText.contains("Ext USB/SD: 59.6 GB"))
        assertTrue("QR payload should remain pure printable ASCII", qrText.all { it == '\n' || it.code in 32..126 })

        val ioErrReport = report.copy(
            storageTest = StorageTestResult(0, 0.0, 0.0, isCorrupted = false, hasError = true, message = "EIO")
        )
        assertTrue(QrHelper.generateAuditSummary(ioErrReport).contains("Storage Test: I/O ERROR"))
    }

    @Test
    fun parseBatchedRootOutputExtractsPartitionsAndMatchingDmesgLines() {
        val combined = """
            uid=0(root) gid=0(root)
            ===FH_PARTITIONS===
            major minor  #blocks  name

             179        0   15267840 mmcblk0
             179        1    1048576 mmcblk0p1
            ===FH_DMESG===
            [    0.000000] Booting Linux on physical CPU 0x0
            [    1.120000] input: gpio-keys as /devices/platform/gpio-keys
            [    2.340000] mmc0: new HS200 MMC card at address 0001
        """.trimIndent()

        val partitions = mutableListOf<String>()
        val dmesg = mutableListOf<String>()
        RootHelper.parseBatchedRootOutput(combined, partitions, dmesg)

        assertEquals(listOf("179        0   15267840 mmcblk0", "179        1    1048576 mmcblk0p1"), partitions)
        assertEquals(2, dmesg.size)
        assertTrue(dmesg.any { it.contains("CPU") })
        assertTrue(dmesg.any { it.contains("mmc0") })
    }

    @Test
    fun localeHelperRestoresRealSystemLocaleAfterLanguageOverride() {
        val fakeOsLocale = Locale("de", "DE")
        val tr = LocaleHelper.resolveTargetLocale(SettingsManager.LANG_TR) { fakeOsLocale }
        val en = LocaleHelper.resolveTargetLocale(SettingsManager.LANG_EN) { fakeOsLocale }
        val sys = LocaleHelper.resolveTargetLocale(SettingsManager.LANG_SYSTEM) { fakeOsLocale }

        assertEquals("tr", tr.language)
        assertEquals("en", en.language)
        assertEquals("de", sys.language)
        assertEquals("DE", sys.country)
    }

    @Test
    fun safeRunCatchesStandardExceptionsButRethrowsCancellationAndJvmErrors() {
        val recovered = safeRun("TestTag", "fallback") {
            throw IllegalStateException("expected failure")
        }
        assertEquals("fallback", recovered)

        assertThrows(CancellationException::class.java) {
            safeRun("CancelTag", "fallback") {
                throw CancellationException("cancelled")
            }
        }

        assertThrows(AssertionError::class.java) {
            safeRun("ErrorTag", "fallback") {
                throw AssertionError("fatal jvm error must not be swallowed")
            }
        }
    }
}
