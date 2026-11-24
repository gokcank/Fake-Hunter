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

data class AuditIssue(
    val severity: IssueSeverity,
    val title: String,
    val description: String
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
    val isSwapInflated: Boolean
) {
    val physicalGbFormatted: String
        get() = String.format("%.1f GB", physicalMb / 1024.0)

    val swapGbFormatted: String
        get() = String.format("%.1f GB", swapMb / 1024.0)
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
    val isFakeCoresDetected: Boolean
)

data class StorageInfo(
    val totalDataMb: Long,
    val freeDataMb: Long
) {
    val totalDataGbFormatted: String
        get() = String.format("%.1f GB", totalDataMb / 1024.0)

    val freeDataGbFormatted: String
        get() = String.format("%.1f GB", freeDataMb / 1024.0)
}

data class RootInfo(
    val isRootBinaryFound: Boolean,
    val isRootGranted: Boolean,
    val selinuxMode: String,
    val rawPartitions: List<String>,
    val dmesgHighlights: List<String>
)

data class StorageTestResult(
    val testedMb: Int,
    val writeSpeedMbPerSec: Double,
    val readSpeedMbPerSec: Double,
    val isCorrupted: Boolean,
    val message: String
)

data class AuditReport(
    val system: SystemInfo,
    val ram: RamInfo,
    val cpu: CpuInfo,
    val storage: StorageInfo,
    val root: RootInfo,
    val verdict: Verdict,
    val issues: List<AuditIssue>
) {
    fun toFormattedPlainText(): String {
        val sb = StringBuilder()
        sb.appendLine("=== FAKE HUNTER HARDWARE AUDIT REPORT ===")
        sb.appendLine("Verdict: ${verdict.name}")
        sb.appendLine("Model: ${system.model} (${system.manufacturer})")
        sb.appendLine("Platform: ${system.platform} | Board: ${system.board}")
        sb.appendLine("Android: ${system.reportedRelease} (API ${system.actualSdkLevel})")
        sb.appendLine("RAM: ${ram.physicalGbFormatted} | Swap: ${ram.swapGbFormatted} (Inflated: ${ram.isSwapInflated})")
        sb.appendLine("CPU Cores: ${cpu.reportedCores} reported / ${cpu.activeCores} active")
        sb.appendLine("Storage (/data): ${storage.totalDataGbFormatted} (Free: ${storage.freeDataGbFormatted})")
        sb.appendLine("Root Access: Granted=${root.isRootGranted} (Binary=${root.isRootBinaryFound}, SELinux=${root.selinuxMode})")
        if (issues.isNotEmpty()) {
            sb.appendLine("\nFindings:")
            issues.forEach { issue ->
                sb.appendLine("[${issue.severity.name}] ${issue.title}: ${issue.description}")
            }
        }
        return sb.toString()
    }
}
