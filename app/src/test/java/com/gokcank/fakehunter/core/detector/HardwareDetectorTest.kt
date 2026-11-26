package com.gokcank.fakehunter.core.detector

import com.gokcank.fakehunter.core.model.*
import org.junit.Assert.*
import org.junit.Test

class HardwareDetectorTest {

    private fun baseSystem(
        model: String = "Generic Head Unit",
        manufacturer: String = "Generic",
        platform: String = "qcom",
        board: String = "kona",
        hardware: String = "qcom",
        release: String = "13",
        sdk: Int = 33
    ) = SystemInfo(
        model = model,
        manufacturer = manufacturer,
        platform = platform,
        board = board,
        hardware = hardware,
        reportedRelease = release,
        actualSdkLevel = sdk,
        displayId = "BUILD.123"
    )

    private fun baseRam(
        physicalMb: Long = 3900L,
        swapMb: Long = 1024L,
        isSwapInflated: Boolean = false,
        reportedTotalMb: Long = 3900L
    ) = RamInfo(
        physicalMb = physicalMb,
        swapMb = swapMb,
        isSwapInflated = isSwapInflated,
        reportedTotalMb = reportedTotalMb
    )

    private fun baseCpu(reportedCores: Int = 4, activeCores: Int = 4) = CpuInfo(
        reportedCores = reportedCores,
        activeCores = activeCores,
        coreDetails = (0 until reportedCores).map {
            CoreDetail(index = it, isOnline = true, currentFreqMhz = if (it < activeCores) 1400L else 0L, maxFreqMhz = 1800L)
        },
        isFakeCoresDetected = reportedCores > activeCores
    )

    @Test
    fun benignSamsungTabT380AndAllwinnerH618AreNotFlaggedFake() {
        val issues = mutableListOf<AuditIssue>()

        // "SM-T380" contains substring "t3", which previously caused false-positive FAKE verdicts
        HardwareDetector.evaluateKnownFraudSignatures(
            system = baseSystem(model = "SM-T380", manufacturer = "samsung", platform = "msm8917", board = "gta2slte", hardware = "qcom", release = "9", sdk = 28),
            ram = baseRam(physicalMb = 1950L),
            cpu = baseCpu(reportedCores = 4, activeCores = 4),
            issues = issues
        )

        // Legitimate modern Allwinner H618 TV box must not match Allwinner T3 rule
        HardwareDetector.evaluateKnownFraudSignatures(
            system = baseSystem(model = "Allwinner H618 Box", manufacturer = "Allwinner", platform = "sun50iw9p1", board = "h618", hardware = "sun50iw9", release = "12", sdk = 31),
            ram = baseRam(physicalMb = 3950L),
            cpu = baseCpu(reportedCores = 4, activeCores = 4),
            issues = issues
        )

        assertTrue("Expected zero fraud signature issues, got: $issues", issues.isEmpty())
    }

    @Test
    fun honestQuadCoreLegacyChipsetReportsInfoInsteadOfFake() {
        val issues = mutableListOf<AuditIssue>()
        HardwareDetector.evaluateKnownFraudSignatures(
            system = baseSystem(model = "8227L_demo", platform = "ac8227l", board = "ac8227l", hardware = "mt8227", release = "8.1.0", sdk = 27),
            ram = baseRam(physicalMb = 1980L, reportedTotalMb = 1980L, isSwapInflated = false),
            cpu = baseCpu(reportedCores = 4, activeCores = 4),
            issues = issues
        )

        assertEquals(1, issues.size)
        assertEquals(IssueSeverity.INFO, issues[0].severity)
        assertEquals(IssueCode.CHIPSET_AC8227L_INFO, issues[0].code)
    }

    @Test
    fun spoofedLegacyChipsetsContradictingSpecsReportFake() {
        val cases = listOf(
            Triple(baseSystem(platform = "ac8227l", release = "13", sdk = 27), baseCpu(reportedCores = 8, activeCores = 4), IssueCode.CHIPSET_AC8227L_FAKE),
            Triple(baseSystem(platform = "sun8i", board = "t3_p1", release = "12", sdk = 29), baseCpu(reportedCores = 8, activeCores = 4), IssueCode.CHIPSET_ALLWINNER_T3_FAKE),
            Triple(baseSystem(hardware = "mt6580", release = "11", sdk = 27), baseCpu(reportedCores = 8, activeCores = 4), IssueCode.CHIPSET_MT6580_FAKE),
            Triple(baseSystem(platform = "rk3228", release = "13", sdk = 28), baseCpu(reportedCores = 4, activeCores = 4), IssueCode.CHIPSET_ROCKCHIP_FAKE)
        )

        for ((sys, cpu, expectedCode) in cases) {
            val issues = mutableListOf<AuditIssue>()
            HardwareDetector.evaluateKnownFraudSignatures(sys, baseRam(physicalMb = 1950L), cpu, issues)
            assertEquals("Expected 1 issue for $expectedCode", 1, issues.size)
            assertEquals(IssueSeverity.FAKE, issues[0].severity)
            assertEquals(expectedCode, issues[0].code)
        }
    }

    @Test
    fun ramEvaluationDistinguishesSpoofedFrameworkFromAndroid11ZramAndLegacySwap() {
        // 1. Direct framework RAM spoofing (claims 8 GB on 2 GB kernel MemTotal)
        val spoofIssues = mutableListOf<AuditIssue>()
        val spoofRam = HardwareDetector.evaluateRam(
            physicalMb = 1950L,
            swapMb = 512L,
            reportedTotalMb = 8192L,
            actualSdkLevel = 29,
            swapBackedByZram = true,
            issues = spoofIssues
        )
        assertTrue(spoofRam.isRamSpoofed)
        assertEquals(IssueCode.RAM_SIZE_SPOOFED, spoofIssues.single().code)
        assertEquals(IssueSeverity.FAKE, spoofIssues.single().severity)

        // 2. Legitimate Android 13 phone with 4 GB RAM + 4 GB ZRAM (RAM Plus) -> INFO, not FAKE
        val zramIssues = mutableListOf<AuditIssue>()
        val zramInfo = HardwareDetector.evaluateRam(
            physicalMb = 3900L,
            swapMb = 4096L,
            reportedTotalMb = 3900L,
            actualSdkLevel = 33,
            swapBackedByZram = true,
            issues = zramIssues
        )
        assertFalse(zramInfo.isRamSpoofed)
        assertFalse(zramInfo.isSwapInflated)
        assertTrue(zramInfo.hasVirtualRamNotice)
        assertEquals(IssueSeverity.INFO, zramIssues.single().severity)
        assertEquals(IssueCode.VIRTUAL_RAM_ACTIVE, zramIssues.single().code)

        // 3. Legacy Android 9 head unit with 2 GB RAM + 3 GB disk swap -> WARNING
        val legacyIssues = mutableListOf<AuditIssue>()
        val legacyInfo = HardwareDetector.evaluateRam(
            physicalMb = 1950L,
            swapMb = 3072L,
            reportedTotalMb = 1950L,
            actualSdkLevel = 28,
            swapBackedByZram = false,
            issues = legacyIssues
        )
        assertTrue(legacyInfo.isSwapInflated)
        assertEquals(IssueSeverity.WARNING, legacyIssues.single().severity)
        assertEquals(IssueCode.HIGH_SWAP_ALLOCATION, legacyIssues.single().code)
    }

    @Test
    fun cpuEvaluationSeparatesSelinuxBlockedCpufreqFromFakePhantomCores() {
        // SELinux blocks all cpufreq nodes -> frequenciesReadable = false, must NOT claim fake cores
        val restrictedIssues = mutableListOf<AuditIssue>()
        val unreadableDetails = (0 until 8).map { CoreDetail(it, isOnline = true, currentFreqMhz = 0L, maxFreqMhz = 0L) }
        val restrictedCpu = HardwareDetector.evaluateCpu(8, unreadableDetails, wakeTestPerformed = true, issues = restrictedIssues)
        assertFalse(restrictedCpu.frequenciesReadable)
        assertFalse(restrictedCpu.isFakeCoresDetected)
        assertEquals(IssueCode.CPU_FREQ_RESTRICTED, restrictedIssues.single().code)
        assertEquals(IssueSeverity.INFO, restrictedIssues.single().severity)

        // 4 real cores + 4 phantom cores with 0 MHz while cpufreq IS readable -> FAKE
        val fakeIssues = mutableListOf<AuditIssue>()
        val mixedDetails = (0 until 8).map { idx ->
            if (idx < 4) CoreDetail(idx, isOnline = true, currentFreqMhz = 1300L, maxFreqMhz = 1500L)
            else CoreDetail(idx, isOnline = false, currentFreqMhz = 0L, maxFreqMhz = 0L)
        }
        val fakeCpu = HardwareDetector.evaluateCpu(8, mixedDetails, wakeTestPerformed = true, issues = fakeIssues)
        assertTrue(fakeCpu.frequenciesReadable)
        assertTrue(fakeCpu.isFakeCoresDetected)
        assertEquals(IssueCode.FAKE_CPU_CORES, fakeIssues.single().code)
        assertEquals(IssueSeverity.FAKE, fakeIssues.single().severity)
    }

    @Test
    fun removableBlockDeviceDetectionSeparatesUsbAndSdFromInternalFlash() {
        assertFalse(
            HardwareDetector.isRemovableBlockDevice(
                name = "mmcblk0",
                removableFlag = "0",
                mmcType = "MMC",
                canonicalPath = "/sys/devices/platform/soc/11230000.mmc/mmc_host/mmc0/mmc0:0001/block/mmcblk0"
            )
        )
        assertTrue(
            HardwareDetector.isRemovableBlockDevice(
                name = "mmcblk1",
                removableFlag = "0",
                mmcType = "SD",
                canonicalPath = "/sys/devices/platform/soc/11240000.mmc/mmc_host/mmc1/mmc1:aaaa/block/mmcblk1"
            )
        )
        assertTrue(
            HardwareDetector.isRemovableBlockDevice(
                name = "sda",
                removableFlag = "1",
                mmcType = null,
                canonicalPath = "/sys/devices/platform/soc/11200000.usb/xhci-hcd.0/usb1/1-1/block/sda"
            )
        )
        assertTrue(
            HardwareDetector.isRemovableBlockDevice(
                name = "sdb",
                removableFlag = "0",
                mmcType = null,
                canonicalPath = "/sys/devices/platform/usb_bus/usb2/2-1/block/sdb"
            )
        )
    }

    @Test
    fun androidVersionSpoofingDetectsMismatchesAcrossSdk21Through36() {
        val spoofPairs = listOf(
            22 to "8.1",
            27 to "14",
            29 to "13",
            30 to "12",
            31 to "14",
            33 to "15",
            34 to "16"
        )
        for ((sdk, claimedRelease) in spoofPairs) {
            val issues = mutableListOf<AuditIssue>()
            HardwareDetector.evaluateAndroidVersionSpoofing(baseSystem(release = claimedRelease, sdk = sdk), issues)
            assertEquals("Expected FAKE for SDK=$sdk claiming Android $claimedRelease", 1, issues.size)
            assertEquals(IssueCode.ANDROID_VERSION_SPOOFED, issues[0].code)
            assertEquals(IssueSeverity.FAKE, issues[0].severity)
        }

        val genuinePairs = listOf(
            21 to "5.0.2",
            27 to "8.1.0",
            29 to "10",
            30 to "11",
            32 to "12L",
            33 to "13",
            34 to "14",
            35 to "15"
        )
        for ((sdk, honestRelease) in genuinePairs) {
            val issues = mutableListOf<AuditIssue>()
            HardwareDetector.evaluateAndroidVersionSpoofing(baseSystem(release = honestRelease, sdk = sdk), issues)
            assertTrue("Unexpected issue for genuine SDK=$sdk / Android $honestRelease: $issues", issues.isEmpty())
        }
    }
}
