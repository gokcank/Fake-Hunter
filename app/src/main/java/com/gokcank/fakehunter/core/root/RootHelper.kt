package com.gokcank.fakehunter.core.root

import com.gokcank.fakehunter.core.model.RootInfo
import com.gokcank.fakehunter.core.safeRun
import java.io.File

object RootHelper {

    private val SU_BINARY_PATHS = arrayOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/system/sd/xbin/su",
        "/system/bin/failsafe/su",
        "/data/local/xbin/su",
        "/data/local/bin/su",
        "/data/local/su"
    )

    fun isRootBinaryPresent(): Boolean {
        return safeRun("RootCheck", false) {
            SU_BINARY_PATHS.any { File(it).exists() } || canExecuteSu()
        }
    }

    private fun canExecuteSu(): Boolean {
        return safeRun("CanExecuteSu", false) {
            val process = Runtime.getRuntime().exec(arrayOf("which", "su"))
            val output = process.inputStream.bufferedReader().use { it.readLine() }
            output?.isNotBlank() == true
        }
    }

    fun executeRootCommand(command: String): String? {
        return safeRun("RootExec", null) {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()
            if (process.exitValue() == 0) output else null
        }
    }

    fun inspectRoot(requestRootElevation: Boolean = false): RootInfo {
        val hasBinary = isRootBinaryPresent()
        var isGranted = false
        var selinux = "Unknown"
        val partitions = mutableListOf<String>()
        val dmesgLogs = mutableListOf<String>()

        safeRun("Selinux", Unit) {
            val process = Runtime.getRuntime().exec(arrayOf("getenforce"))
            selinux = process.inputStream.bufferedReader().use { it.readLine()?.trim() ?: "Unknown" }
        }

        if (hasBinary && requestRootElevation) {
            // Check if root elevation is granted
            val idOutput = executeRootCommand("id")
            if (idOutput != null && idOutput.contains("uid=0")) {
                isGranted = true

                // 1. Raw /proc/partitions
                val partOutput = executeRootCommand("cat /proc/partitions")
                if (!partOutput.isNullOrBlank()) {
                    partOutput.lineSequence()
                        .filter { it.isNotBlank() && !it.startsWith("major") }
                        .take(15)
                        .forEach { partitions.add(it.trim()) }
                }

                // 2. dmesg for CPU/chipset hints
                val dmesgOutput = executeRootCommand("dmesg | grep -iE 'cpu|memory|ac8227|allwinner|sun8i|mt6580'")
                if (!dmesgOutput.isNullOrBlank()) {
                    dmesgOutput.lineSequence()
                        .filter { it.isNotBlank() }
                        .take(8)
                        .forEach { dmesgLogs.add(it.trim()) }
                }
            }
        }

        return RootInfo(
            isRootBinaryFound = hasBinary,
            isRootGranted = isGranted,
            selinuxMode = selinux,
            rawPartitions = partitions,
            dmesgHighlights = dmesgLogs
        )
    }
}
