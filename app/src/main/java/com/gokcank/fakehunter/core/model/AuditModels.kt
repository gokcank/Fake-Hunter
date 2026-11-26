package com.gokcank.fakehunter.core.model

enum class Verdict {
    VERIFIED,
    SUSPICIOUS,
    FAKE_DETECTED
}

enum class IssueSeverity {
    INFO,
    WARNING,
    FAKE
}

enum class IssueCode {
    STORAGE_VERIFICATION_FAILED,
    STORAGE_TEST_INCOMPLETE,
    RAM_SIZE_SPOOFED,
    VIRTUAL_RAM_ACTIVE,
    HIGH_SWAP_ALLOCATION,
    ELEVATED_DISK_SWAP,
    CPU_FREQ_RESTRICTED,
    FAKE_CPU_CORES,
    STORAGE_SIZE_SPOOFED,
    CHIPSET_AC8227L_FAKE,
    CHIPSET_AC8227L_INFO,
    CHIPSET_ALLWINNER_T3_FAKE,
    CHIPSET_ALLWINNER_T3_INFO,
    CHIPSET_MT6580_FAKE,
    CHIPSET_MT6580_INFO,
    CHIPSET_ROCKCHIP_FAKE,
    CHIPSET_ROCKCHIP_INFO,
    ANDROID_VERSION_SPOOFED
}

data class AuditIssue(
    val severity: IssueSeverity,
    val title: String,
    val description: String,
    val code: IssueCode? = null,
    val formatArgs: List<String> = emptyList()
)

/** Options for a full audit run. Every test runs by default. */
data class AuditOptions(
    val wakeTest: Boolean = true,
    val requestRoot: Boolean = true,
    val storageTestMb: Int = 100
)

data class SystemInfo(
    val model: String,
    val manufacturer: String,
    val platform: String,
    val board: String,
    val hardware: String,
    val reportedRelease: String,
    val actualSdkLevel: Int,
    val displayId: String
)

data class RamInfo(
    val physicalMb: Long,
    val swapMb: Long,
    val isSwapInflated: Boolean,
    val reportedTotalMb: Long = 0L,
    val isRamSpoofed: Boolean = false,
    val swapBackedByZram: Boolean = false,
    val hasVirtualRamNotice: Boolean = false
) {
    val physicalGbFormatted: String
        get() = formatGb(physicalMb)

    val swapGbFormatted: String
        get() = formatGb(swapMb)

    val reportedTotalGbFormatted: String
        get() = formatGb(reportedTotalMb)
}

data class CoreDetail(
    val index: Int,
    val isOnline: Boolean,
    val currentFreqMhz: Long,
    val maxFreqMhz: Long
)

data class CpuInfo(
    val reportedCores: Int,
    val activeCores: Int,
    val coreDetails: List<CoreDetail>,
    val isFakeCoresDetected: Boolean,
    val wakeTestPerformed: Boolean = false,
    val frequenciesReadable: Boolean = true
)

/** A whole physical block device (eMMC / UFS / NAND / virtio / removable USB or SD) as seen by the kernel. */
data class FlashChip(
    val name: String,
    val sizeMb: Long,
    val readViaRoot: Boolean,
    val isRemovable: Boolean = false
) {
    val sizeGbFormatted: String
        get() = formatGb(sizeMb)
}

data class StorageInfo(
    val totalDataMb: Long,
    val freeDataMb: Long,
    val flashChips: List<FlashChip> = emptyList(),
    val flashChipsComplete: Boolean = false,
    val externalChips: List<FlashChip> = emptyList()
) {
    val totalDataGbFormatted: String
        get() = formatGb(totalDataMb)

    val freeDataGbFormatted: String
        get() = formatGb(freeDataMb)

    val physicalFlashMb: Long
        get() = flashChips.sumOf { it.sizeMb }

    val physicalFlashGbFormatted: String
        get() = formatGb(physicalFlashMb)

    val externalStorageMb: Long
        get() = externalChips.sumOf { it.sizeMb }

    val externalStorageGbFormatted: String
        get() = formatGb(externalStorageMb)
}

data class RootInfo(
    val isRootBinaryFound: Boolean,
    val isRootGranted: Boolean,
    val selinuxMode: String,
    val rawPartitions: List<String>,
    val dmesgHighlights: List<String>,
    val rootRequested: Boolean = false
)

data class StorageTestResult(
    val testedMb: Int,
    val writeSpeedMbPerSec: Double,
    val readSpeedMbPerSec: Double,
    val isCorrupted: Boolean,
    val message: String,
    val hasError: Boolean = false,
    val cacheWarning: Boolean = false
) {
    val wasSkipped: Boolean
        get() = testedMb == 0 && !isCorrupted && !hasError
}

data class AuditReport(
    val system: SystemInfo,
    val ram: RamInfo,
    val cpu: CpuInfo,
    val storage: StorageInfo,
    val root: RootInfo,
    val storageTest: StorageTestResult?,
    val verdict: Verdict,
    val issues: List<AuditIssue>,
    val timestampMillis: Long = System.currentTimeMillis()
) {
    fun toFormattedPlainText(): String {
        val sb = StringBuilder()
        sb.appendLine("=== FAKE HUNTER HARDWARE AUDIT REPORT ===")
        sb.appendLine("Date: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date(timestampMillis))}")
        sb.appendLine("Verdict: ${verdict.name}")
        sb.appendLine()
        sb.appendLine("[System]")
        sb.appendLine("Model: ${system.model} (${system.manufacturer})")
        sb.appendLine("Platform: ${system.platform} | Board: ${system.board} | Hardware: ${system.hardware}")
        sb.appendLine("Android: ${system.reportedRelease} (API ${system.actualSdkLevel})")
        sb.appendLine("Build: ${system.displayId}")
        sb.appendLine()
        sb.appendLine("[Memory]")
        sb.appendLine("Physical RAM: ${ram.physicalGbFormatted} (${ram.physicalMb} MB)")
        if (ram.reportedTotalMb > 0) {
            sb.appendLine("Framework Reported RAM: ${ram.reportedTotalGbFormatted} (${ram.reportedTotalMb} MB) | Spoofed: ${ram.isRamSpoofed}")
        }
        sb.appendLine("Swap / ZRAM: ${ram.swapGbFormatted} (${ram.swapMb} MB) | ZRAM: ${ram.swapBackedByZram} | Inflated: ${ram.isSwapInflated}")
        sb.appendLine()
        sb.appendLine("[CPU]")
        sb.appendLine("Cores: ${cpu.reportedCores} reported / ${cpu.activeCores} active (wake test: ${cpu.wakeTestPerformed}, freq readable: ${cpu.frequenciesReadable})")
        cpu.coreDetails.forEach { core ->
            sb.appendLine("  Core ${core.index}: online=${core.isOnline}, cur=${core.currentFreqMhz} MHz, max=${core.maxFreqMhz} MHz")
        }
        sb.appendLine()
        sb.appendLine("[Storage]")
        sb.appendLine("/data total: ${storage.totalDataGbFormatted} | free: ${storage.freeDataGbFormatted}")
        if (storage.flashChips.isNotEmpty()) {
            sb.appendLine("Physical internal flash (raw): ${storage.physicalFlashGbFormatted} (complete: ${storage.flashChipsComplete})")
            storage.flashChips.forEach { chip ->
                sb.appendLine("  ${chip.name}: ${chip.sizeGbFormatted} (${chip.sizeMb} MB)${if (chip.readViaRoot) " [root]" else ""}")
            }
        } else {
            sb.appendLine("Physical internal flash (raw): not readable")
        }
        if (storage.externalChips.isNotEmpty()) {
            sb.appendLine("External removable storage (USB/SD, excluded from internal): ${storage.externalStorageGbFormatted}")
            storage.externalChips.forEach { chip ->
                sb.appendLine("  ${chip.name}: ${chip.sizeGbFormatted} (${chip.sizeMb} MB) [removable]${if (chip.readViaRoot) " [root]" else ""}")
            }
        }
        sb.appendLine()
        sb.appendLine("[Storage Verification Test]")
        if (storageTest == null) {
            sb.appendLine("Not performed")
        } else {
            sb.appendLine("Tested: ${storageTest.testedMb} MB | Write: ${storageTest.writeSpeedMbPerSec} MB/s | Read: ${storageTest.readSpeedMbPerSec} MB/s")
            sb.appendLine("Corrupted: ${storageTest.isCorrupted} | Error: ${storageTest.hasError} | CacheWarning: ${storageTest.cacheWarning} | ${storageTest.message}")
        }
        sb.appendLine()
        sb.appendLine("[Root & Kernel]")
        sb.appendLine("Root requested: ${root.rootRequested} | Binary: ${root.isRootBinaryFound} | Granted: ${root.isRootGranted}")
        sb.appendLine("SELinux: ${root.selinuxMode}")
        if (root.rawPartitions.isNotEmpty()) {
            sb.appendLine("Kernel partitions (${root.rawPartitions.size}):")
            root.rawPartitions.forEach { sb.appendLine("  $it") }
        }
        if (root.dmesgHighlights.isNotEmpty()) {
            sb.appendLine("dmesg highlights:")
            root.dmesgHighlights.forEach { sb.appendLine("  $it") }
        }
        if (issues.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("[Findings]")
            issues.forEach { issue ->
                sb.appendLine("[${issue.severity.name}] ${issue.title}: ${issue.description}")
            }
        }
        return sb.toString()
    }
}

private fun formatGb(mb: Long): String = String.format(java.util.Locale.US, "%.1f GB", mb / 1024.0)
