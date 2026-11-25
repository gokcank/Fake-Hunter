package com.gokcank.fakehunter.core.root

import com.gokcank.fakehunter.core.model.RootInfo
import com.gokcank.fakehunter.core.safeRun
import java.io.File

object RootHelper {

    private val SU_BINARY_PATHS = arrayOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/su/bin/su",
        "/system/sd/xbin/su",
        "/system/bin/failsafe/su",
        "/data/local/xbin/su",
        "/data/local/bin/su",
        "/data/local/su",
        "/debug_ramdisk/su"
    )

    /** Time the user has to answer the root manager popup. */
    private const val GRANT_TIMEOUT_MS = 20_000L
    private const val COMMAND_TIMEOUT_MS = 10_000L

    fun isRootBinaryPresent(): Boolean {
        return safeRun("RootCheck", false) {
            SU_BINARY_PATHS.any { File(it).exists() } || canExecuteSu()
        }
    }

    private fun canExecuteSu(): Boolean {
        val output = ShellRunner.run(arrayOf("which", "su"), timeoutMs = 3_000L)
        return output?.trim()?.isNotEmpty() == true
    }

    /**
     * Runs a command as root. The script is fed through su's stdin instead of `su -c`,
     * so pipes and redirects work even with old or stripped su binaries found on head units.
     */
    fun executeRootCommand(command: String, timeoutMs: Long = COMMAND_TIMEOUT_MS): String? {
        return ShellRunner.run(arrayOf("su"), stdin = "$command\nexit\n", timeoutMs = timeoutMs)
    }

    /** True when su actually runs commands as uid 0 (triggers the root manager popup). */
    fun requestGrant(): Boolean {
        val idOutput = executeRootCommand("id", GRANT_TIMEOUT_MS)
        return idOutput?.contains("uid=0") == true
    }

    fun inspectRoot(requestRootElevation: Boolean): RootInfo {
        val hasBinary = isRootBinaryPresent()
        var isGranted = false
        val partitions = mutableListOf<String>()
        val dmesgLogs = mutableListOf<String>()

        val selinux = ShellRunner.run(arrayOf("getenforce"), timeoutMs = 3_000L)
            ?.trim()?.takeIf { it.isNotEmpty() } ?: "Unknown"

        if (hasBinary && requestRootElevation && requestGrant()) {
            isGranted = true

            // 1. Raw kernel partition table
            executeRootCommand("cat /proc/partitions")?.lineSequence()
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() && !it.startsWith("major") }
                ?.forEach { partitions.add(it) }

            // 2. Kernel boot log lines that reveal the real chipset / memory
            executeRootCommand("dmesg | grep -iE 'cpu|memory|mmc|ufs|emmc|ac8227|allwinner|sun8i|sun50i|mt6580|rockchip|machine'")
                ?.lineSequence()
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.take(40)
                ?.forEach { dmesgLogs.add(it) }
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
}
