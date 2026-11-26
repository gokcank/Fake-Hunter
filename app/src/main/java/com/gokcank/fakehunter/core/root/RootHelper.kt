package com.gokcank.fakehunter.core.root

import com.gokcank.fakehunter.core.model.RootInfo
import com.gokcank.fakehunter.core.safeRun
import java.io.File

object RootHelper {

    private val SU_BINARY_PATHS = arrayOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/vendor/bin/su",
        "/product/bin/su",
        "/system_ext/bin/su",
        "/odm/bin/su",
        "/sbin/su",
        "/su/bin/su",
        "/system/sd/xbin/su",
        "/system/bin/failsafe/su",
        "/data/local/xbin/su",
        "/data/local/bin/su",
        "/data/local/su",
        "/debug_ramdisk/su"
    )

    private val DMESG_HIGHLIGHT_REGEX = Regex(
        "cpu|memory|mmc|ufs|emmc|ac8227|allwinner|sun8i|sun50i|mt6580|rockchip|machine",
        RegexOption.IGNORE_CASE
    )

    private const val MARKER_PART = "===FH_PARTITIONS==="
    private const val MARKER_DMESG = "===FH_DMESG==="

    /** Time the user has to answer the root manager popup + run initial root probe. */
    private const val GRANT_TIMEOUT_MS = 22_000L
    private const val COMMAND_TIMEOUT_MS = 10_000L
    private const val ROOT_MAX_CHARS = 256 * 1024

    fun isRootBinaryPresent(): Boolean {
        return safeRun("RootCheck", false) {
            SU_BINARY_PATHS.any { File(it).exists() } || canLocateSuInPath()
        }
    }

    private fun canLocateSuInPath(): Boolean {
        val pathEnv = System.getenv("PATH") ?: ""
        if (pathEnv.split(":").any { dir -> dir.isNotBlank() && File(dir, "su").exists() }) {
            return true
        }
        val output = ShellRunner.run(arrayOf("sh", "-c", "command -v su || which su"), timeoutMs = 2_500L, maxChars = 1024)
        return output?.trim()?.isNotEmpty() == true
    }

    /**
     * Runs a command as root. The script is fed through su's stdin instead of `su -c`,
     * so pipes and multi-line commands work even on stripped head-unit su binaries.
     */
    fun executeRootCommand(
        command: String,
        timeoutMs: Long = COMMAND_TIMEOUT_MS,
        maxChars: Int = ROOT_MAX_CHARS
    ): String? {
        return ShellRunner.run(
            command = arrayOf("su"),
            stdin = "$command\nexit\n",
            timeoutMs = timeoutMs,
            maxChars = maxChars
        )
    }

    fun inspectRoot(requestRootElevation: Boolean): RootInfo {
        val hasBinary = isRootBinaryPresent()
        var isGranted = false
        val partitions = mutableListOf<String>()
        val dmesgLogs = mutableListOf<String>()

        val selinux = ShellRunner.run(arrayOf("getenforce"), timeoutMs = 3_000L, maxChars = 256)
            ?.lineSequence()?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: "Unknown"

        if (hasBinary && requestRootElevation) {
            // Combine id + partitions + dmesg in ONE single root session to avoid multiple su prompts
            // and filter dmesg in Kotlin rather than relying on shell grep -E (missing on some API 21 ROMs).
            val batchScript = buildString {
                appendLine("id")
                appendLine("echo '$MARKER_PART'")
                appendLine("cat /proc/partitions 2>/dev/null")
                appendLine("echo '$MARKER_DMESG'")
                appendLine("dmesg 2>/dev/null")
            }
            val combined = executeRootCommand(batchScript, timeoutMs = GRANT_TIMEOUT_MS, maxChars = ROOT_MAX_CHARS)
            if (combined != null && combined.contains("uid=0")) {
                isGranted = true
                parseBatchedRootOutput(combined, partitions, dmesgLogs)
            }
        }

        return RootInfo(
            isRootBinaryFound = hasBinary,
            isRootGranted = isGranted,
            selinuxMode = selinux,
            rawPartitions = partitions,
            dmesgHighlights = dmesgLogs,
            rootRequested = requestRootElevation
        )
    }

    internal fun parseBatchedRootOutput(
        combined: String,
        partitions: MutableList<String>,
        dmesgLogs: MutableList<String>
    ) {
        val afterPart = combined.substringAfter(MARKER_PART, "")
        val partSection = afterPart.substringBefore(MARKER_DMESG)
        val dmesgSection = afterPart.substringAfter(MARKER_DMESG, "")

        partSection.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("major") }
            .forEach { partitions.add(it) }

        dmesgSection.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && DMESG_HIGHLIGHT_REGEX.containsMatchIn(it) }
            .take(40)
            .forEach { dmesgLogs.add(it) }
    }
}
