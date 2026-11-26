package com.gokcank.fakehunter.core.detector

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import com.gokcank.fakehunter.core.model.*
import com.gokcank.fakehunter.core.root.RootHelper
import com.gokcank.fakehunter.core.root.ShellRunner
import com.gokcank.fakehunter.core.safeRun
import com.gokcank.fakehunter.core.storage.StorageTester
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object HardwareDetector {

    /** Phases reported to the UI while the full audit chain runs. */
    enum class Phase { SYSTEM, CPU_WAKE, ROOT, FLASH, STORAGE_TEST, DONE }

    private val NON_FLASH_PREFIXES = listOf("loop", "ram", "zram", "dm-", "md", "sr", "nbd", "vnd", "fd")

    private val REGEX_AC8227 = Regex("(^|[^a-z0-9])(ac8227l?|mt8227l?|8227l)([^a-z0-9]|$)")
    private val REGEX_AC8227_MODEL = Regex("(^|[^a-z0-9])(ac8227l?|mt8227l?)([^a-z0-9]|$)")
    private val REGEX_ALLWINNER_T3 = Regex("(^|[^a-z0-9])(sun8i|sun8iw[0-9]+p[0-9]+|allwinner[_\\s-]?t3|t3[_\\s-]?p[0-9]+)([^a-z0-9]|$)")
    private val REGEX_MT6580 = Regex("(^|[^a-z0-9])mt6580([^a-z0-9]|$)")
    private val REGEX_ROCKCHIP_LEGACY = Regex("(^|[^a-z0-9])rk3[12]2[89]([^a-z0-9]|$)")

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
        val ramInfo = detectRamInfo(context, systemInfo.actualSdkLevel, issues)

        onProgress(Phase.CPU_WAKE, 5, "")
        val cpuInfo = detectCpuInfo(issues, options.wakeTest)

        onProgress(Phase.ROOT, 15, "")
        val rootInfo = RootHelper.inspectRoot(requestRootElevation = options.requestRoot)

        onProgress(Phase.FLASH, 30, "")
        val storageInfo = detectStorageInfo(issues, useRoot = rootInfo.isRootGranted)

        var storageTest: StorageTestResult? = null
        if (options.storageTestMb > 0) {
            onProgress(Phase.STORAGE_TEST, 35, "")
            storageTest = StorageTester.runVerificationTest(
                context = context,
                testMb = options.storageTestMb,
                physicalRamMb = ramInfo.physicalMb
            ) { pct, msg ->
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
            } else if (storageTest.hasError) {
                issues.add(
                    AuditIssue(
                        severity = IssueSeverity.WARNING,
                        title = "Storage Test Could Not Complete",
                        description = storageTest.message
                    )
                )
            }
        }

        evaluateKnownFraudSignatures(systemInfo, ramInfo, cpuInfo, issues)
        evaluateAndroidVersionSpoofing(systemInfo, issues)

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

    private fun detectRamInfo(
        context: Context,
        actualSdkLevel: Int,
        issues: MutableList<AuditIssue>
    ): RamInfo {
        var memTotalKb = 0L
        var swapTotalKb = 0L
        var swapBackedByZram = false

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

        safeRun("SwapInfo", Unit) {
            val swapsFile = File("/proc/swaps")
            if (swapsFile.exists()) {
                val lines = swapsFile.readLines().drop(1).filter { it.isNotBlank() }
                if (lines.isNotEmpty() && lines.all { it.contains("zram", ignoreCase = true) }) {
                    swapBackedByZram = true
                }
            }
        }

        val reportedTotalMb = safeRun("ActivityManagerRam", 0L) {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            if (am != null) {
                val memInfo = ActivityManager.MemoryInfo()
                am.getMemoryInfo(memInfo)
                memInfo.totalMem / (1024L * 1024L)
            } else 0L
        }

        return evaluateRam(
            physicalMb = memTotalKb / 1024L,
            swapMb = swapTotalKb / 1024L,
            reportedTotalMb = reportedTotalMb,
            actualSdkLevel = actualSdkLevel,
            swapBackedByZram = swapBackedByZram,
            issues = issues
        )
    }

    internal fun evaluateRam(
        physicalMb: Long,
        swapMb: Long,
        reportedTotalMb: Long,
        actualSdkLevel: Int,
        swapBackedByZram: Boolean,
        issues: MutableList<AuditIssue>
    ): RamInfo {
        var isRamSpoofed = false
        var isSwapInflated = false
        var hasVirtualRamNotice = false

        // 1. Direct Android Framework vs Linux Kernel RAM contradiction -> FAKE
        if (reportedTotalMb > 0L && physicalMb > 0L && reportedTotalMb > physicalMb + (physicalMb / 4) + 512L) {
            isRamSpoofed = true
            isSwapInflated = true
            issues.add(
                AuditIssue(
                    severity = IssueSeverity.FAKE,
                    title = "RAM Size Spoofed",
                    description = "Android reports ${reportedTotalMb}MB total RAM, but Linux kernel physical RAM is only ${physicalMb}MB."
                )
            )
        }

        // 2. Swap / Virtual RAM evaluation (distinguishes Android 11+ OEM RAM Plus/ZRAM from legacy head unit inflation)
        if (!isRamSpoofed && swapMb > 0L && physicalMb > 0L) {
            val ratio = (swapMb * 100L) / physicalMb
            val isVeryHighSwap = ratio > 100L || (swapMb >= 3000L && physicalMb <= 4200L)

            if (isVeryHighSwap) {
                if (actualSdkLevel >= 30 && swapBackedByZram) {
                    // Android 11+ standard OEM ZRAM / RAM Plus expansion — inform without falsely calling it FAKE
                    hasVirtualRamNotice = true
                    issues.add(
                        AuditIssue(
                            severity = IssueSeverity.INFO,
                            title = "Virtual RAM / ZRAM Expansion Active",
                            description = "Large compressed ZRAM swap (${swapMb}MB, ${ratio}% of ${physicalMb}MB physical RAM) is common on Android 11+ devices. Verify seller claims against the physical RAM figure."
                        )
                    )
                } else {
                    // Legacy Android <= 10 head units or non-ZRAM disk swap inflation
                    isSwapInflated = true
                    issues.add(
                        AuditIssue(
                            severity = IssueSeverity.WARNING,
                            title = "High Swap Allocation",
                            description = "Swap memory (${swapMb}MB) is ${ratio}% of physical RAM (${physicalMb}MB). Budget head units often combine swap with physical RAM to advertise inflated RAM."
                        )
                    )
                }
            } else if (ratio > 85L && actualSdkLevel < 30 && !swapBackedByZram) {
                isSwapInflated = true
                issues.add(
                    AuditIssue(
                        severity = IssueSeverity.WARNING,
                        title = "Elevated Disk Swap Allocation",
                        description = "Disk-backed swap space is ${ratio}% of physical RAM (${swapMb}MB vs ${physicalMb}MB)."
                    )
                )
            }
        }

        return RamInfo(
            physicalMb = physicalMb,
            swapMb = swapMb,
            isSwapInflated = isSwapInflated,
            reportedTotalMb = reportedTotalMb,
            isRamSpoofed = isRamSpoofed,
            swapBackedByZram = swapBackedByZram,
            hasVirtualRamNotice = hasVirtualRamNotice
        )
    }

    private fun detectCpuInfo(issues: MutableList<AuditIssue>, wakeTest: Boolean): CpuInfo {
        val reportedCores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val details = mutableListOf<CoreDetail>()

        if (wakeTest) {
            safeRun("CpuWake", Unit) {
                val keepSpinning = AtomicBoolean(true)
                val startedLatch = CountDownLatch(reportedCores)
                val spinDeadlineNs = System.nanoTime() + 250_000_000L // Hard 250ms cap
                val threads = (1..reportedCores).map { workerIdx ->
                    Thread({
                        startedLatch.countDown()
                        var acc = workerIdx.toLong() + 1L
                        while (keepSpinning.get() && System.nanoTime() < spinDeadlineNs) {
                            acc = acc * 6364136223846793005L + 1442695040888963407L
                        }
                        if (acc == 42L) Thread.yield()
                    }, "FakeHunter-CpuWake-$workerIdx").apply {
                        isDaemon = true
                        start()
                    }
                }
                try {
                    startedLatch.await(50, TimeUnit.MILLISECONDS)
                    Thread.sleep(90)
                    details.addAll(sampleCoreDetails(reportedCores))
                } finally {
                    keepSpinning.set(false)
                    threads.forEach { t -> safeRun("CpuWakeJoin", Unit) { t.join(150) } }
                }
            }
        }

        if (details.isEmpty()) {
            details.addAll(sampleCoreDetails(reportedCores))
        }

        return evaluateCpu(
            reportedCores = reportedCores,
            details = details,
            wakeTestPerformed = wakeTest,
            issues = issues
        )
    }

    private fun sampleCoreDetails(reportedCores: Int): List<CoreDetail> {
        val details = mutableListOf<CoreDetail>()
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
                        curFreq = mhz * 1000L
                        if (maxFreq == 0L) maxFreq = curFreq
                    }
                }
            }

            details.add(
                CoreDetail(
                    index = idx,
                    isOnline = isOnline,
                    currentFreqMhz = curFreq / 1000L,
                    maxFreqMhz = maxFreq / 1000L
                )
            )
        }
        return details
    }

    internal fun evaluateCpu(
        reportedCores: Int,
        details: List<CoreDetail>,
        wakeTestPerformed: Boolean,
        issues: MutableList<AuditIssue>
    ): CpuInfo {
        val activeCores = details.count { it.isOnline && it.currentFreqMhz > 0L }
        val frequenciesReadable = details.any { it.currentFreqMhz > 0L || it.maxFreqMhz > 0L }

        val isFakeCoresDetected = if (!frequenciesReadable) {
            issues.add(
                AuditIssue(
                    severity = IssueSeverity.INFO,
                    title = "CPU Frequencies Restricted",
                    description = "System security restrictions prevented reading core clock frequencies; reported $reportedCores cores could not be verified by active frequency."
                )
            )
            false
        } else if (reportedCores > activeCores) {
            issues.add(
                AuditIssue(
                    severity = IssueSeverity.FAKE,
                    title = "Fake CPU Cores Detected",
                    description = "System reports $reportedCores CPU cores, but only $activeCores generate active clock frequency."
                )
            )
            true
        } else {
            false
        }

        return CpuInfo(
            reportedCores = reportedCores,
            activeCores = activeCores,
            coreDetails = details,
            isFakeCoresDetected = isFakeCoresDetected,
            wakeTestPerformed = wakeTestPerformed,
            frequenciesReadable = frequenciesReadable
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

            totalMb = (totalBlocks * blockSize) / (1024L * 1024L)
            freeMb = (freeBlocks * blockSize) / (1024L * 1024L)
        }

        val (internalChips, externalChips, complete) = detectFlashChips(useRoot)

        // A /data partition can never exceed the internal physical flash chips it lives on.
        val physicalMb = internalChips.sumOf { it.sizeMb }
        if (complete && physicalMb > 0L && totalMb > physicalMb + physicalMb / 50L) {
            issues.add(
                AuditIssue(
                    severity = IssueSeverity.FAKE,
                    title = "Storage Size Spoofed",
                    description = "The /data partition reports ${totalMb}MB, but the internal physical flash chip(s) only hold ${physicalMb}MB."
                )
            )
        }

        return StorageInfo(
            totalDataMb = totalMb,
            freeDataMb = freeMb,
            flashChips = internalChips,
            flashChipsComplete = complete,
            externalChips = externalChips
        )
    }

    internal fun isRemovableBlockDevice(
        name: String,
        removableFlag: String?,
        mmcType: String?,
        canonicalPath: String?
    ): Boolean {
        if (removableFlag?.trim() == "1") return true
        if (name.startsWith("mmcblk") && mmcType?.trim()?.equals("SD", ignoreCase = true) == true) {
            return true
        }
        if (canonicalPath != null && canonicalPath.lowercase().contains("/usb")) {
            return true
        }
        return false
    }

    /**
     * Reads the raw sector count of whole block devices from /sys/block/<dev>/size
     * (512-byte sectors), separating removable USB/SD devices from internal flash chips.
     * Empty removable readers (sectors == 0) are ignored so they never suppress internal checks.
     */
    private fun detectFlashChips(useRoot: Boolean): Triple<List<FlashChip>, List<FlashChip>, Boolean> {
        val internalChips = mutableListOf<FlashChip>()
        val externalChips = mutableListOf<FlashChip>()
        var complete = true

        val directList = safeRun("FlashList", emptyList<String>()) {
            File("/sys/block").list()?.toList() ?: emptyList()
        }

        // If root is granted and direct sysfs access is restricted, collect all block metadata in ONE root call
        val rootSnapshot: Map<String, Triple<Long, String?, String?>> = if (useRoot) {
            readAllBlockMetadataViaRoot()
        } else {
            emptyMap()
        }

        val names = if (directList.isNotEmpty()) directList else rootSnapshot.keys.toList()

        val candidates = names.filter { name ->
            NON_FLASH_PREFIXES.none { name.startsWith(it) } &&
                !name.contains("boot") && !name.contains("rpmb")
        }.sorted()

        if (candidates.isEmpty()) return Triple(emptyList(), emptyList(), false)

        for (name in candidates) {
            val rootEntry = rootSnapshot[name]
            val removableFlag = readSysfsDirect("/sys/block/$name/removable") ?: rootEntry?.second
            val mmcType = if (name.startsWith("mmcblk")) {
                readSysfsDirect("/sys/block/$name/device/type") ?: rootEntry?.third
            } else null
            val canonicalPath = safeRun("FlashPath", null) {
                File("/sys/block/$name").canonicalPath
            }
            val removable = isRemovableBlockDevice(name, removableFlag, mmcType, canonicalPath)

            var viaRoot = false
            var sectors = safeRun("FlashSize", 0L) {
                File("/sys/block/$name/size").readText().trim().toLongOrNull() ?: 0L
            }
            if (sectors == 0L && rootEntry != null && rootEntry.first > 0L) {
                sectors = rootEntry.first
                viaRoot = true
            }

            if (removable) {
                if (sectors > 0L) {
                    externalChips.add(
                        FlashChip(
                            name = name,
                            sizeMb = (sectors * 512L) / (1024L * 1024L),
                            readViaRoot = viaRoot,
                            isRemovable = true
                        )
                    )
                }
                // Empty removable card readers / USB slots have size 0 and must not mark internal complete=false
            } else {
                if (sectors > 0L) {
                    internalChips.add(
                        FlashChip(
                            name = name,
                            sizeMb = (sectors * 512L) / (1024L * 1024L),
                            readViaRoot = viaRoot,
                            isRemovable = false
                        )
                    )
                } else {
                    complete = false
                }
            }
        }

        return Triple(internalChips, externalChips, complete && internalChips.isNotEmpty())
    }

    private fun readSysfsDirect(path: String): String? {
        return safeRun("ReadSysfs", null) {
            val f = File(path)
            if (f.exists() && f.canRead()) f.readText().trim() else null
        }
    }

    private fun readAllBlockMetadataViaRoot(): Map<String, Triple<Long, String?, String?>> {
        val script = """
            for d in /sys/block/*; do
              [ -e "${'$'}d" ] || continue
              n="${'$'}{d##*/}"
              s=${'$'}(cat "${'$'}d/size" 2>/dev/null || echo 0)
              r=${'$'}(cat "${'$'}d/removable" 2>/dev/null || echo "")
              t=${'$'}(cat "${'$'}d/device/type" 2>/dev/null || echo "")
              echo "${'$'}n|${'$'}s|${'$'}r|${'$'}t"
            done
        """.trimIndent()
        val raw = RootHelper.executeRootCommand(script, timeoutMs = 6_000L, maxChars = 64 * 1024) ?: return emptyMap()
        val map = mutableMapOf<String, Triple<Long, String?, String?>>()
        raw.lineSequence().forEach { line ->
            val parts = line.trim().split("|")
            if (parts.size >= 4 && parts[0].isNotBlank()) {
                val sectors = parts[1].trim().toLongOrNull() ?: 0L
                val rem = parts[2].trim().takeIf { it.isNotEmpty() }
                val typ = parts[3].trim().takeIf { it.isNotEmpty() }
                map[parts[0].trim()] = Triple(sectors, rem, typ)
            }
        }
        return map
    }

    internal fun evaluateKnownFraudSignatures(
        system: SystemInfo,
        ram: RamInfo,
        cpu: CpuInfo,
        issues: MutableList<AuditIssue>
    ) {
        val hwStr = listOf(system.platform, system.board, system.hardware)
            .joinToString(" ")
            .lowercase()
        val modelStr = system.model.lowercase()

        val reportedMajorAndroid = parseMajorVersion(system.reportedRelease) ?: 0
        val contradictsQuadCoreLegacy = cpu.reportedCores > 4 ||
            reportedMajorAndroid >= 11 ||
            ram.physicalMb > 4200L ||
            ram.reportedTotalMb > 4200L ||
            ram.isSwapInflated

        when {
            REGEX_AC8227.containsMatchIn(hwStr) || REGEX_AC8227_MODEL.containsMatchIn(modelStr) -> {
                val severity = if (contradictsQuadCoreLegacy) IssueSeverity.FAKE else IssueSeverity.INFO
                val desc = if (contradictsQuadCoreLegacy) {
                    "AutoChips/MediaTek AC8227L (32-bit Quad-Core Cortex-A7) contradicts the reported specs (cores, RAM, or Android version)."
                } else {
                    "Legacy AutoChips/MediaTek AC8227L Quad-Core platform identified. Verify seller claims do not advertise 8-core or Android 11+."
                }
                issues.add(AuditIssue(severity = severity, title = "AC8227L Chipset Signature", description = desc))
            }
            REGEX_ALLWINNER_T3.containsMatchIn(hwStr) -> {
                val severity = if (contradictsQuadCoreLegacy) IssueSeverity.FAKE else IssueSeverity.INFO
                val desc = if (contradictsQuadCoreLegacy) {
                    "Allwinner sun8i/T3 (32-bit Quad-Core Cortex-A7) contradicts the reported specs (cores, RAM, or Android version)."
                } else {
                    "Legacy Allwinner sun8i/T3 Quad-Core platform identified. Verify seller claims do not advertise 8-core or Android 11+."
                }
                issues.add(AuditIssue(severity = severity, title = "Allwinner T3 / sun8i Signature", description = desc))
            }
            REGEX_MT6580.containsMatchIn(hwStr) -> {
                val severity = if (contradictsQuadCoreLegacy) IssueSeverity.FAKE else IssueSeverity.INFO
                val desc = if (contradictsQuadCoreLegacy) {
                    "MediaTek MT6580 (2015 32-bit Quad-Core CPU) contradicts the reported specs (cores, RAM, or Android version)."
                } else {
                    "Legacy MediaTek MT6580 Quad-Core platform identified. Verify seller claims do not advertise modern 8-core hardware."
                }
                issues.add(AuditIssue(severity = severity, title = "MT6580 Legacy Chipset Signature", description = desc))
            }
            REGEX_ROCKCHIP_LEGACY.containsMatchIn(hwStr) -> {
                val severity = if (contradictsQuadCoreLegacy) IssueSeverity.FAKE else IssueSeverity.INFO
                val desc = if (contradictsQuadCoreLegacy) {
                    "Entry-level Rockchip RK3128/RK3228/RK3229 Quad-Core chipset contradicts the reported specs."
                } else {
                    "Entry-level Rockchip Quad-Core platform identified. Frequently resold with inflated specifications."
                }
                issues.add(AuditIssue(severity = severity, title = "Rockchip Legacy Signature", description = desc))
            }
        }
    }

    internal fun expectedAndroidMajorForSdk(sdk: Int): Int? = when (sdk) {
        21, 22 -> 5
        23 -> 6
        24, 25 -> 7
        26, 27 -> 8
        28 -> 9
        29 -> 10
        30 -> 11
        31, 32 -> 12
        33 -> 13
        34 -> 14
        35 -> 15
        36 -> 16
        else -> null
    }

    internal fun parseMajorVersion(release: String): Int? {
        val digits = release.trim().takeWhile { it.isDigit() }
        return digits.toIntOrNull()
    }

    internal fun evaluateAndroidVersionSpoofing(system: SystemInfo, issues: MutableList<AuditIssue>) {
        val sdk = system.actualSdkLevel
        val major = parseMajorVersion(system.reportedRelease) ?: return

        val expectedMajor = expectedAndroidMajorForSdk(sdk)
        if (expectedMajor != null && major > expectedMajor) {
            issues.add(
                AuditIssue(
                    severity = IssueSeverity.FAKE,
                    title = "Android Version Spoofed",
                    description = "Reported Android ${system.reportedRelease} contradicts the actual kernel API level $sdk (Android $expectedMajor)."
                )
            )
        } else if (sdk in 1..20 && major >= 5) {
            issues.add(
                AuditIssue(
                    severity = IssueSeverity.FAKE,
                    title = "Android Version Spoofed",
                    description = "Reported Android ${system.reportedRelease} contradicts the actual legacy kernel API level $sdk."
                )
            )
        }
    }

    private fun getSystemProperty(prop: String): String {
        return ShellRunner.run(arrayOf("getprop", prop), timeoutMs = 2_000L, maxChars = 4096)
            ?.lineSequence()
            ?.firstOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: "Unknown"
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
