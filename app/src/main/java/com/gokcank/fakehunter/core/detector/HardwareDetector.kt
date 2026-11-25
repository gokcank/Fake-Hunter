package com.gokcank.fakehunter.core.detector

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import com.gokcank.fakehunter.core.model.*
import com.gokcank.fakehunter.core.root.RootHelper
import com.gokcank.fakehunter.core.safeRun
import com.gokcank.fakehunter.core.storage.StorageTester
import java.io.File

object HardwareDetector {

    /** Phases reported to the UI while the full audit chain runs. */
    enum class Phase { SYSTEM, CPU_WAKE, ROOT, FLASH, STORAGE_TEST, DONE }

    /**
     * Runs the full audit chain: identity, RAM, CPU (with wake test), root,
     * raw flash size and the physical storage verification test.
     * Must be called off the main thread.
     */
    fun performAudit(
        context: Context,
        options: AuditOptions = AuditOptions(),
        onProgress: (phase: Phase, percent: Int, detail: String) -> Unit = { _, _, _ -> }
    ): AuditReport {
        val issues = mutableListOf<AuditIssue>()

        onProgress(Phase.SYSTEM, 0, "")
        val systemInfo = detectSystemInfo()
        val ramInfo = detectRamInfo(issues)

        onProgress(Phase.CPU_WAKE, 5, "")
        val cpuInfo = detectCpuInfo(issues, options.wakeTest)

        onProgress(Phase.ROOT, 15, "")
        val rootInfo = RootHelper.inspectRoot(requestRootElevation = options.requestRoot)

        onProgress(Phase.FLASH, 30, "")
        val storageInfo = detectStorageInfo(issues, useRoot = rootInfo.isRootGranted)

        var storageTest: StorageTestResult? = null
        if (options.storageTestMb > 0) {
            onProgress(Phase.STORAGE_TEST, 35, "")
            storageTest = StorageTester.runVerificationTest(context, options.storageTestMb) { pct, msg ->
                onProgress(Phase.STORAGE_TEST, 35 + (pct * 65) / 100, msg)
            }
            if (storageTest.isCorrupted) {
                issues.add(
                    AuditIssue(
                        severity = IssueSeverity.FAKE,
                        title = "Storage Verification Failed",
                        description = storageTest.message
                    )
                )
            }
        }

        checkKnownFraudSignatures(systemInfo, issues)
        checkAndroidVersionSpoofing(systemInfo, issues)

        val verdict = when {
            issues.any { it.severity == IssueSeverity.FAKE } -> Verdict.FAKE_DETECTED
            issues.any { it.severity == IssueSeverity.WARNING } -> Verdict.SUSPICIOUS
            else -> Verdict.VERIFIED
        }

        onProgress(Phase.DONE, 100, "")
        return AuditReport(
            system = systemInfo,
            ram = ramInfo,
            cpu = cpuInfo,
            storage = storageInfo,
            root = rootInfo,
            storageTest = storageTest,
            verdict = verdict,
            issues = issues
        )
    }

    private fun detectSystemInfo(): SystemInfo {
        return SystemInfo(
            model = safeRun("SystemInfo", "Unknown") { Build.MODEL ?: "Unknown" },
            manufacturer = safeRun("SystemInfo", "Unknown") { Build.MANUFACTURER ?: "Unknown" },
            platform = safeRun("SystemInfo", "Unknown") { getSystemProperty("ro.board.platform") },
            board = safeRun("SystemInfo", "Unknown") { Build.BOARD ?: "Unknown" },
            hardware = safeRun("SystemInfo", "Unknown") { Build.HARDWARE ?: "Unknown" },
            reportedRelease = safeRun("SystemInfo", "Unknown") { Build.VERSION.RELEASE ?: "Unknown" },
            actualSdkLevel = Build.VERSION.SDK_INT,
            displayId = safeRun("SystemInfo", "Unknown") { Build.DISPLAY ?: "Unknown" }
        )
    }

    private fun detectRamInfo(issues: MutableList<AuditIssue>): RamInfo {
        var memTotalKb = 0L
        var swapTotalKb = 0L

        safeRun("RamInfo", Unit) {
            val meminfoFile = File("/proc/meminfo")
            if (meminfoFile.exists()) {
                meminfoFile.forEachLine { line ->
                    if (line.startsWith("MemTotal:")) {
                        memTotalKb = parseKb(line)
                    } else if (line.startsWith("SwapTotal:")) {
                        swapTotalKb = parseKb(line)
                    }
                }
            }
        }

        val physicalMb = memTotalKb / 1024
        val swapMb = swapTotalKb / 1024

        var isSwapInflated = false
        // Swap inflation heuristic
        if (swapMb >= 3000 && physicalMb <= 4200) {
            isSwapInflated = true
            issues.add(
                AuditIssue(
                    severity = IssueSeverity.FAKE,
                    title = "Swap Memory Inflation",
                    description = "Physical RAM (${physicalMb}MB) is artificially combined with Swap space (${swapMb}MB) to market the device as higher RAM."
                )
            )
        } else if (swapMb > 0 && physicalMb > 0) {
            val ratio = (swapMb * 100) / physicalMb
            if (ratio > 85) {
                isSwapInflated = true
                issues.add(
                    AuditIssue(
                        severity = IssueSeverity.WARNING,
                        title = "High Swap Allocation",
                        description = "Swap memory is unusually large (${ratio}% of physical RAM), which may mislead standard RAM queries."
                    )
                )
            }
        }

        return RamInfo(
            physicalMb = physicalMb,
            swapMb = swapMb,
            isSwapInflated = isSwapInflated
        )
    }

    private fun detectCpuInfo(issues: MutableList<AuditIssue>, wakeTest: Boolean): CpuInfo {
        var reportedCores = Runtime.getRuntime().availableProcessors()
        val details = mutableListOf<CoreDetail>()
        var activeCores = 0

        // Optionally wake cores with brief calculation
        if (wakeTest) {
            safeRun("CpuWake", Unit) {
                val threads = (1..reportedCores).map {
                    Thread {
                        var x = 0L
                        for (i in 0..100000L) { x += i }
                    }.apply { start() }
                }
                threads.forEach { it.join(500) }
            }
        }

        // Check each core sysfs
        for (idx in 0 until reportedCores) {
            val coreDir = File("/sys/devices/system/cpu/cpu$idx")
            var isOnline = true
            var curFreq = 0L
            var maxFreq = 0L

            safeRun("Core$idx", Unit) {
                val onlineFile = File(coreDir, "online")
                if (onlineFile.exists()) {
                    isOnline = onlineFile.readText().trim() == "1"
                }

                val curFreqFile = File(coreDir, "cpufreq/scaling_cur_freq")
                val curInfoFreqFile = File(coreDir, "cpufreq/cpuinfo_cur_freq")
                val maxFreqFile = File(coreDir, "cpufreq/cpuinfo_max_freq")

                curFreq = when {
                    curFreqFile.exists() -> curFreqFile.readText().trim().toLongOrNull() ?: 0L
                    curInfoFreqFile.exists() -> curInfoFreqFile.readText().trim().toLongOrNull() ?: 0L
                    else -> 0L
                }

                if (maxFreqFile.exists()) {
                    maxFreq = maxFreqFile.readText().trim().toLongOrNull() ?: 0L
                }

                // Fallback to /proc/cpuinfo for virtualized x86 cores
                if (curFreq == 0L) {
                    val mhz = getCpuInfoMhzForCore(idx)
                    if (mhz > 0) {
                        curFreq = mhz * 1000
                        if (maxFreq == 0L) maxFreq = curFreq
                    }
                }
            }

            if (isOnline && curFreq > 0) {
                activeCores++
            }

            details.add(
                CoreDetail(
                    index = idx,
                    isOnline = isOnline,
                    currentFreqMhz = curFreq / 1000,
                    maxFreqMhz = maxFreq / 1000
                )
            )
        }

        val isFakeCoresDetected = reportedCores > activeCores && activeCores > 0
        if (isFakeCoresDetected) {
            issues.add(
                AuditIssue(
                    severity = IssueSeverity.FAKE,
                    title = "Fake CPU Cores Detected",
                    description = "System reports $reportedCores CPU cores, but only $activeCores generate active clock frequency."
                )
            )
        }

        return CpuInfo(
            reportedCores = reportedCores,
            activeCores = activeCores,
            coreDetails = details,
            isFakeCoresDetected = isFakeCoresDetected,
            wakeTestPerformed = wakeTest
        )
    }

    private fun detectStorageInfo(issues: MutableList<AuditIssue>, useRoot: Boolean): StorageInfo {
        var totalMb = 0L
        var freeMb = 0L

        safeRun("StorageInfo", Unit) {
            val dataDir = Environment.getDataDirectory()
            val stat = StatFs(dataDir.path)
            val blockSize = stat.blockSizeLong
            val totalBlocks = stat.blockCountLong
            val freeBlocks = stat.availableBlocksLong

            totalMb = (totalBlocks * blockSize) / (1024 * 1024)
            freeMb = (freeBlocks * blockSize) / (1024 * 1024)
        }

        val (chips, complete) = detectFlashChips(useRoot)

        // A partition can never be larger than the physical chips it lives on.
        val physicalMb = chips.sumOf { it.sizeMb }
        if (complete && physicalMb > 0 && totalMb > physicalMb + physicalMb / 50) {
            issues.add(
                AuditIssue(
                    severity = IssueSeverity.FAKE,
                    title = "Storage Size Spoofed",
                    description = "The /data partition reports ${totalMb}MB, but the physical flash chip(s) only hold ${physicalMb}MB."
                )
            )
        }

        return StorageInfo(
            totalDataMb = totalMb,
            freeDataMb = freeMb,
            flashChips = chips,
            flashChipsComplete = complete
        )
    }

    private val NON_FLASH_PREFIXES = listOf("loop", "ram", "zram", "dm-", "md", "sr", "nbd", "vnd", "fd")

    /**
     * Reads the raw sector count of every whole block device from /sys/block/<dev>/size
     * (512-byte sectors). Tries without root first, then with root if granted.
     * Returns the chips and whether every candidate device could be read.
     */
    private fun detectFlashChips(useRoot: Boolean): Pair<List<FlashChip>, Boolean> {
        val chips = mutableListOf<FlashChip>()
        var complete = true

        val names = safeRun("FlashList", emptyList<String>()) {
            File("/sys/block").list()?.toList() ?: emptyList()
        }.ifEmpty {
            if (useRoot) {
                RootHelper.executeRootCommand("ls /sys/block")
                    ?.lineSequence()?.map { it.trim() }?.filter { it.isNotEmpty() }?.toList()
                    ?: emptyList()
            } else emptyList()
        }

        val candidates = names.filter { name ->
            NON_FLASH_PREFIXES.none { name.startsWith(it) } &&
                !name.contains("boot") && !name.contains("rpmb")
        }.sorted()

        if (candidates.isEmpty()) return Pair(emptyList(), false)

        for (name in candidates) {
            var viaRoot = false
            var sectors = safeRun("FlashSize", 0L) {
                File("/sys/block/$name/size").readText().trim().toLongOrNull() ?: 0L
            }
            if (sectors == 0L && useRoot) {
                sectors = RootHelper.executeRootCommand("cat /sys/block/$name/size")
                    ?.trim()?.toLongOrNull() ?: 0L
                viaRoot = sectors > 0L
            }
            if (sectors > 0L) {
                chips.add(FlashChip(name = name, sizeMb = (sectors * 512L) / (1024L * 1024L), readViaRoot = viaRoot))
            } else {
                complete = false
            }
        }

        return Pair(chips, complete && chips.isNotEmpty())
    }

    private fun checkKnownFraudSignatures(system: SystemInfo, issues: MutableList<AuditIssue>) {
        val checkStr = "${system.platform}_${system.board}_${system.hardware}_${system.model}".lowercase()
        when {
            checkStr.contains("8227") -> {
                issues.add(
                    AuditIssue(
                        severity = IssueSeverity.FAKE,
                        title = "AC8227L Chipset Signature",
                        description = "AutoChips/MediaTek 32-bit Quad-Core Cortex-A7 chipset detected. Often spoofed as 8-core, Android 13/14 with 8GB RAM."
                    )
                )
            }
            checkStr.contains("sun8i") || checkStr.contains("t3") || checkStr.contains("allwinner") -> {
                issues.add(
                    AuditIssue(
                        severity = IssueSeverity.FAKE,
                        title = "Allwinner T3 Signature",
                        description = "Allwinner Quad-Core Cortex-A7 platform detected. Frequently marketed as 8-core multimedia head unit."
                    )
                )
            }
            checkStr.contains("mt6580") -> {
                issues.add(
                    AuditIssue(
                        severity = IssueSeverity.FAKE,
                        title = "MT6580 Legacy Chipset Signature",
                        description = "MediaTek MT6580 32-bit Quad-Core CPU (2015 era) detected. Incompatible with advertised modern 8-core claims."
                    )
                )
            }
            checkStr.contains("rk3128") || checkStr.contains("rk3228") -> {
                issues.add(
                    AuditIssue(
                        severity = IssueSeverity.WARNING,
                        title = "Rockchip Legacy Signature",
                        description = "Entry-level Rockchip Quad-Core chipset detected. Frequently faked as 8-core 4K streaming box."
                    )
                )
            }
        }
    }

    private fun checkAndroidVersionSpoofing(system: SystemInfo, issues: MutableList<AuditIssue>) {
        val sdk = system.actualSdkLevel
        val release = system.reportedRelease.trim()

        if (sdk in 1..27) { // Android 8.1 or older
            val major = release.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
            if (major >= 9) {
                issues.add(
                    AuditIssue(
                        severity = IssueSeverity.FAKE,
                        title = "Android Version Spoofed",
                        description = "Reported Android $release contradicts the actual kernel API level $sdk (Android 8.1 Oreo or older)."
                    )
                )
            }
        } else if (sdk in 28..29) { // Android 9 - 10
            val major = release.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
            if (major >= 12) {
                issues.add(
                    AuditIssue(
                        severity = IssueSeverity.FAKE,
                        title = "Android Version Spoofed",
                        description = "Reported Android $release contradicts the actual kernel API level $sdk (Android 10 Q or older)."
                    )
                )
            }
        }
    }

    private fun getSystemProperty(prop: String): String {
        return safeRun("SysProp", "Unknown") {
            val process = Runtime.getRuntime().exec(arrayOf("getprop", prop))
            process.inputStream.bufferedReader().use { it.readLine()?.trim() ?: "Unknown" }
        }
    }

    private fun parseKb(line: String): Long {
        val parts = line.split("\\s+".toRegex())
        return parts.getOrNull(1)?.toLongOrNull() ?: 0L
    }

    private fun getCpuInfoMhzForCore(coreIndex: Int): Long {
        return safeRun("CpuInfoMhz", 0L) {
            val cpuinfo = File("/proc/cpuinfo")
            if (!cpuinfo.exists()) return@safeRun 0L
            var currentProcessor = -1
            var resultMhz = 0L
            cpuinfo.useLines { lines ->
                for (line in lines) {
                    val trimmed = line.trim()
                    if (trimmed.startsWith("processor")) {
                        currentProcessor = trimmed.substringAfter(":").trim().toIntOrNull() ?: -1
                    } else if (currentProcessor == coreIndex && trimmed.startsWith("cpu MHz")) {
                        val mhzStr = trimmed.substringAfter(":").trim()
                        resultMhz = mhzStr.toDoubleOrNull()?.toLong() ?: 0L
                        break
                    }
                }
            }
            resultMhz
        }
    }
}
