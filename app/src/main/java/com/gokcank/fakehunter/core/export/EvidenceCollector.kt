package com.gokcank.fakehunter.core.export

import android.content.Context
import com.gokcank.fakehunter.core.model.AuditReport
import com.gokcank.fakehunter.core.root.RootHelper
import com.gokcank.fakehunter.core.root.ShellRunner
import com.gokcank.fakehunter.core.safeRun
import java.io.File
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object EvidenceCollector {

    fun generateZipFileName(report: AuditReport): String {
        val cleanModel = report.system.model.replace("[^a-zA-Z0-9_]".toRegex(), "_")
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(report.timestampMillis))
        return "FakeHunter_${cleanModel}_$timestamp.zip"
    }

    fun exportEvidenceZip(context: Context, report: AuditReport, outputStream: OutputStream): Boolean {
        return safeRun("ExportEvidenceZip", false) {
            ZipOutputStream(outputStream.buffered()).use { zos ->
                // 1. Plain text summary report
                addZipEntry(zos, "summary_report.txt", report.toFormattedPlainText())

                // 2. JSON summary
                addZipEntry(zos, "summary_report.json", buildJsonSummary(report))

                // 3. System properties (getprop)
                val getprop = ShellRunner.run(arrayOf("getprop"), timeoutMs = 5_000L) ?: ""
                addZipEntry(zos, "system_properties_getprop.txt", getprop)

                // 4. /proc/cpuinfo
                val cpuinfo = safeRun("ReadCpuinfo", "") { File("/proc/cpuinfo").readText() }
                addZipEntry(zos, "proc_cpuinfo.txt", cpuinfo)

                // 5. /proc/meminfo
                val meminfo = safeRun("ReadMeminfo", "") { File("/proc/meminfo").readText() }
                addZipEntry(zos, "proc_meminfo.txt", meminfo)

                // 6. /proc/partitions
                val partitions = if (report.root.isRootGranted) {
                    RootHelper.executeRootCommand("cat /proc/partitions") ?: ""
                } else {
                    safeRun("ReadPartitions", "") { File("/proc/partitions").readText() }
                }
                addZipEntry(zos, "proc_partitions.txt", partitions)

                // 7. /proc/mounts
                val mounts = safeRun("ReadMounts", "") { File("/proc/mounts").readText() }
                addZipEntry(zos, "proc_mounts.txt", mounts)

                // 8. /sys/block/*/size
                val blockSizes = StringBuilder()
                safeRun("BlockSizes", Unit) {
                    val blockDir = File("/sys/block")
                    if (blockDir.exists()) {
                        blockDir.listFiles()?.forEach { dev ->
                            val sizeFile = File(dev, "size")
                            if (sizeFile.exists()) {
                                val sectors = sizeFile.readText().trim().toLongOrNull() ?: 0L
                                val mb = (sectors * 512L) / (1024L * 1024L)
                                blockSizes.appendLine("${dev.name}: $sectors sectors (~$mb MB)")
                            }
                        }
                    }
                }
                addZipEntry(zos, "sys_block_sizes.txt", blockSizes.toString())

                // 9. GPU & Display info
                val gpuInfo = buildString {
                    appendLine("Display ID: ${report.system.displayId}")
                    appendLine("Reported Platform: ${report.system.platform}")
                    appendLine("Reported Board: ${report.system.board}")
                    appendLine("Reported Hardware: ${report.system.hardware}")
                    val glesProp = ShellRunner.run(arrayOf("getprop", "ro.opengles.version"))?.trim() ?: "Unknown"
                    appendLine("GLES Version Prop: $glesProp")
                }
                addZipEntry(zos, "gpu_info.txt", gpuInfo)

                // 10. logcat -d -v time (try standard, fallback to root)
                var logcat = ShellRunner.run(arrayOf("logcat", "-d", "-v", "time"), timeoutMs = 8_000L) ?: ""
                if ((logcat.isBlank() || logcat.length < 200) && report.root.isRootGranted) {
                    logcat = RootHelper.executeRootCommand("logcat -d -v time", timeoutMs = 8_000L) ?: logcat
                }
                addZipEntry(zos, "system_logs_logcat.txt", logcat)

                // 11. dmesg (if root or readable)
                val dmesg = if (report.root.isRootGranted) {
                    RootHelper.executeRootCommand("dmesg", timeoutMs = 8_000L) ?: ""
                } else {
                    ShellRunner.run(arrayOf("dmesg"), timeoutMs = 3_000L) ?: ""
                }
                addZipEntry(zos, "kernel_logs_dmesg.txt", dmesg)

                zos.flush()
            }
            true
        }
    }

    private fun addZipEntry(zos: ZipOutputStream, entryName: String, content: String) {
        val entry = ZipEntry(entryName)
        zos.putNextEntry(entry)
        val bytes = content.toByteArray(StandardCharsets.UTF_8)
        zos.write(bytes, 0, bytes.size)
        zos.closeEntry()
    }

    private fun buildJsonSummary(report: AuditReport): String {
        return buildString {
            appendLine("{")
            appendLine("  \"verdict\": \"${report.verdict.name}\",")
            appendLine("  \"timestamp\": ${report.timestampMillis},")
            appendLine("  \"system\": {")
            appendLine("    \"model\": \"${report.system.model}\",")
            appendLine("    \"manufacturer\": \"${report.system.manufacturer}\",")
            appendLine("    \"platform\": \"${report.system.platform}\",")
            appendLine("    \"board\": \"${report.system.board}\",")
            appendLine("    \"hardware\": \"${report.system.hardware}\",")
            appendLine("    \"reportedRelease\": \"${report.system.reportedRelease}\",")
            appendLine("    \"actualSdkLevel\": ${report.system.actualSdkLevel}")
            appendLine("  },")
            appendLine("  \"ram\": {")
            appendLine("    \"physicalMb\": ${report.ram.physicalMb},")
            appendLine("    \"swapMb\": ${report.ram.swapMb},")
            appendLine("    \"isSwapInflated\": ${report.ram.isSwapInflated}")
            appendLine("  },")
            appendLine("  \"cpu\": {")
            appendLine("    \"reportedCores\": ${report.cpu.reportedCores},")
            appendLine("    \"activeCores\": ${report.cpu.activeCores},")
            appendLine("    \"isFakeCoresDetected\": ${report.cpu.isFakeCoresDetected}")
            appendLine("  },")
            appendLine("  \"storage\": {")
            appendLine("    \"totalDataMb\": ${report.storage.totalDataMb},")
            appendLine("    \"freeDataMb\": ${report.storage.freeDataMb},")
            appendLine("    \"physicalFlashMb\": ${report.storage.physicalFlashMb}")
            appendLine("  },")
            appendLine("  \"storageTest\": {")
            if (report.storageTest != null) {
                appendLine("    \"testedMb\": ${report.storageTest.testedMb},")
                appendLine("    \"writeSpeedMbPerSec\": ${report.storageTest.writeSpeedMbPerSec},")
                appendLine("    \"readSpeedMbPerSec\": ${report.storageTest.readSpeedMbPerSec},")
                appendLine("    \"isCorrupted\": ${report.storageTest.isCorrupted},")
                appendLine("    \"message\": \"${report.storageTest.message.replace("\"", "\\\"")}\"")
            } else {
                appendLine("    \"performed\": false")
            }
            appendLine("  },")
            appendLine("  \"issuesCount\": ${report.issues.size}")
            appendLine("}")
        }
    }
}
