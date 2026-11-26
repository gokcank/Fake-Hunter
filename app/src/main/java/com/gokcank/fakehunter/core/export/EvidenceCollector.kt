package com.gokcank.fakehunter.core.export

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
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

    private const val LOG_MAX_CHARS = 256 * 1024 // 256 KB per log entry to prevent OOM on low-RAM head units

    private const val PRIVACY_HEADER =
        "PRIVACY NOTICE: Raw diagnostic files in this archive (system_properties_getprop.txt, " +
            "system_logs_logcat.txt, kernel_logs_dmesg.txt) may contain device serial numbers " +
            "or hardware identifiers. Review before sharing publicly.\n\n"

    fun generateZipFileName(report: AuditReport): String {
        val cleanModel = report.system.model.replace("[^a-zA-Z0-9_]".toRegex(), "_")
            .trim('_')
            .ifEmpty { "Device" }
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(report.timestampMillis))
        return "FakeHunter_${cleanModel}_$timestamp.zip"
    }

    fun exportEvidenceZip(report: AuditReport, outputStream: OutputStream): Boolean {
        return safeRun("ExportEvidenceZip", false) {
            ZipOutputStream(outputStream.buffered()).use { zos ->
                // 1. Plain text summary report (with privacy header)
                addZipEntry(zos, "summary_report.txt", PRIVACY_HEADER + report.toFormattedPlainText())

                // 2. RFC-8259 compliant JSON summary
                addZipEntry(zos, "summary_report.json", buildJsonSummary(report))

                // 3. System properties (getprop, memory-bounded)
                val getprop = ShellRunner.run(arrayOf("getprop"), timeoutMs = 5_000L, maxChars = LOG_MAX_CHARS) ?: ""
                addZipEntry(zos, "system_properties_getprop.txt", getprop)

                // 4. /proc/cpuinfo
                val cpuinfo = readBoundedFile("/proc/cpuinfo")
                addZipEntry(zos, "proc_cpuinfo.txt", cpuinfo)

                // 5. /proc/meminfo
                val meminfo = readBoundedFile("/proc/meminfo")
                addZipEntry(zos, "proc_meminfo.txt", meminfo)

                // 6. /proc/partitions
                val partitions = if (report.root.isRootGranted) {
                    RootHelper.executeRootCommand("cat /proc/partitions", timeoutMs = 4_000L, maxChars = 64 * 1024) ?: ""
                } else {
                    readBoundedFile("/proc/partitions")
                }
                addZipEntry(zos, "proc_partitions.txt", partitions)

                // 7. /proc/mounts
                val mounts = readBoundedFile("/proc/mounts")
                addZipEntry(zos, "proc_mounts.txt", mounts)

                // 8. /sys/block/*/size (shares root fallback & removable separation)
                val blockSizes = collectBlockSizesDump(report)
                addZipEntry(zos, "sys_block_sizes.txt", blockSizes)

                // 9. GPU & Display info
                val gpuInfo = buildString {
                    appendLine("Display ID: ${report.system.displayId}")
                    appendLine("Reported Platform: ${report.system.platform}")
                    appendLine("Reported Board: ${report.system.board}")
                    appendLine("Reported Hardware: ${report.system.hardware}")
                    val glesProp = ShellRunner.run(arrayOf("getprop", "ro.opengles.version"), timeoutMs = 2_000L, maxChars = 512)
                        ?.lineSequence()?.firstOrNull()?.trim() ?: "Unknown"
                    appendLine("GLES Version Prop: $glesProp")
                }
                addZipEntry(zos, "gpu_info.txt", gpuInfo)

                // 10. Bounded logcat (tail 2500 lines first, fallback to bounded full dump for old ROMs)
                var logcat = ShellRunner.run(
                    arrayOf("logcat", "-d", "-v", "time", "-t", "2500"),
                    timeoutMs = 6_000L,
                    maxChars = LOG_MAX_CHARS
                ) ?: ShellRunner.run(
                    arrayOf("logcat", "-d", "-v", "time"),
                    timeoutMs = 6_000L,
                    maxChars = LOG_MAX_CHARS
                ) ?: ""

                if ((logcat.isBlank() || logcat.length < 200) && report.root.isRootGranted) {
                    logcat = RootHelper.executeRootCommand(
                        "logcat -d -v time -t 2500 2>/dev/null || logcat -d -v time 2>/dev/null",
                        timeoutMs = 8_000L,
                        maxChars = LOG_MAX_CHARS
                    ) ?: logcat
                }
                addZipEntry(zos, "system_logs_logcat.txt", logcat)

                // 11. Bounded dmesg (if root or readable)
                val dmesg = if (report.root.isRootGranted) {
                    RootHelper.executeRootCommand("dmesg 2>/dev/null", timeoutMs = 8_000L, maxChars = LOG_MAX_CHARS) ?: ""
                } else {
                    ShellRunner.run(arrayOf("dmesg"), timeoutMs = 3_000L, maxChars = LOG_MAX_CHARS) ?: ""
                }
                addZipEntry(zos, "kernel_logs_dmesg.txt", dmesg)

                zos.flush()
            }
            true
        }
    }

    /**
     * Deletes an incomplete / 0-byte SAF document if ZIP export fails so corrupt remnants
     * are not left behind in the user's storage.
     */
    fun deletePartialSafDocument(context: Context, uri: Uri) {
        val deletedViaDocs = safeRun("DeleteSafDoc", false) {
            DocumentsContract.deleteDocument(context.contentResolver, uri)
        }
        if (!deletedViaDocs) {
            safeRun("DeleteContentUri", 0) {
                context.contentResolver.delete(uri, null, null)
            }
        }
    }

    private fun readBoundedFile(path: String, maxChars: Int = LOG_MAX_CHARS): String {
        return safeRun("ReadBoundedFile", "") {
            val file = File(path)
            if (!file.exists() || !file.canRead()) return@safeRun ""
            file.bufferedReader().use { reader ->
                val sb = StringBuilder()
                val buf = CharArray(4096)
                while (sb.length < maxChars) {
                    val read = reader.read(buf, 0, (maxChars - sb.length).coerceAtMost(buf.size))
                    if (read == -1) break
                    sb.append(buf, 0, read)
                }
                sb.toString()
            }
        }
    }

    private fun collectBlockSizesDump(report: AuditReport): String {
        val sb = StringBuilder()
        if (report.storage.flashChips.isNotEmpty() || report.storage.externalChips.isNotEmpty()) {
            sb.appendLine("# Audited physical & removable block devices:")
            report.storage.flashChips.forEach { chip ->
                sb.appendLine("${chip.name}: ~${chip.sizeMb} MB [internal${if (chip.readViaRoot) ", root" else ""}]")
            }
            report.storage.externalChips.forEach { chip ->
                sb.appendLine("${chip.name}: ~${chip.sizeMb} MB [removable${if (chip.readViaRoot) ", root" else ""}]")
            }
            sb.appendLine()
        }

        sb.appendLine("# Raw /sys/block/*/size:")
        var anyDirect = false
        safeRun("BlockSizes", Unit) {
            val blockDir = File("/sys/block")
            if (blockDir.exists()) {
                blockDir.listFiles()?.sortedBy { it.name }?.forEach { dev ->
                    val sizeFile = File(dev, "size")
                    if (sizeFile.exists() && sizeFile.canRead()) {
                        val sectors = sizeFile.readText().trim().toLongOrNull() ?: 0L
                        val mb = (sectors * 512L) / (1024L * 1024L)
                        sb.appendLine("${dev.name}: $sectors sectors (~$mb MB)")
                        anyDirect = true
                    }
                }
            }
        }
        if (!anyDirect && report.root.isRootGranted) {
            val rootDump = RootHelper.executeRootCommand(
                "for d in /sys/block/*; do [ -f \"\$d/size\" ] && echo \"\${d##*/}: \$(cat \"\$d/size\" 2>/dev/null) sectors\"; done",
                timeoutMs = 5_000L,
                maxChars = 32 * 1024
            )
            if (!rootDump.isNullOrBlank()) {
                sb.appendLine(rootDump.trimEnd())
            }
        }
        return sb.toString()
    }

    private fun addZipEntry(zos: ZipOutputStream, entryName: String, content: String) {
        val entry = ZipEntry(entryName)
        zos.putNextEntry(entry)
        val bytes = content.toByteArray(StandardCharsets.UTF_8)
        zos.write(bytes, 0, bytes.size)
        zos.closeEntry()
    }

    internal fun escapeJsonString(value: String): String {
        val sb = StringBuilder(value.length + 8)
        for (ch in value) {
            when (ch) {
                '\"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> {
                    if (ch.code < 0x20) {
                        sb.append(String.format(Locale.US, "\\u%04x", ch.code))
                    } else {
                        sb.append(ch)
                    }
                }
            }
        }
        return sb.toString()
    }

    private fun jsonQuote(value: String): String = "\"${escapeJsonString(value)}\""

    private fun jsonDouble(value: Double): String =
        if (value.isFinite()) String.format(Locale.US, "%.1f", value) else "0.0"

    internal fun buildJsonSummary(report: AuditReport): String {
        return buildString {
            appendLine("{")
            appendLine("  \"verdict\": ${jsonQuote(report.verdict.name)},")
            appendLine("  \"timestamp\": ${report.timestampMillis},")
            appendLine("  \"system\": {")
            appendLine("    \"model\": ${jsonQuote(report.system.model)},")
            appendLine("    \"manufacturer\": ${jsonQuote(report.system.manufacturer)},")
            appendLine("    \"platform\": ${jsonQuote(report.system.platform)},")
            appendLine("    \"board\": ${jsonQuote(report.system.board)},")
            appendLine("    \"hardware\": ${jsonQuote(report.system.hardware)},")
            appendLine("    \"reportedRelease\": ${jsonQuote(report.system.reportedRelease)},")
            appendLine("    \"actualSdkLevel\": ${report.system.actualSdkLevel},")
            appendLine("    \"displayId\": ${jsonQuote(report.system.displayId)}")
            appendLine("  },")
            appendLine("  \"ram\": {")
            appendLine("    \"physicalMb\": ${report.ram.physicalMb},")
            appendLine("    \"reportedTotalMb\": ${report.ram.reportedTotalMb},")
            appendLine("    \"swapMb\": ${report.ram.swapMb},")
            appendLine("    \"swapBackedByZram\": ${report.ram.swapBackedByZram},")
            appendLine("    \"isRamSpoofed\": ${report.ram.isRamSpoofed},")
            appendLine("    \"isSwapInflated\": ${report.ram.isSwapInflated}")
            appendLine("  },")
            appendLine("  \"cpu\": {")
            appendLine("    \"reportedCores\": ${report.cpu.reportedCores},")
            appendLine("    \"activeCores\": ${report.cpu.activeCores},")
            appendLine("    \"frequenciesReadable\": ${report.cpu.frequenciesReadable},")
            appendLine("    \"isFakeCoresDetected\": ${report.cpu.isFakeCoresDetected}")
            appendLine("  },")
            appendLine("  \"storage\": {")
            appendLine("    \"totalDataMb\": ${report.storage.totalDataMb},")
            appendLine("    \"freeDataMb\": ${report.storage.freeDataMb},")
            appendLine("    \"physicalFlashMb\": ${report.storage.physicalFlashMb},")
            appendLine("    \"externalStorageMb\": ${report.storage.externalStorageMb},")
            appendLine("    \"flashChipsComplete\": ${report.storage.flashChipsComplete},")
            append("    \"internalChips\": [")
            report.storage.flashChips.forEachIndexed { idx, chip ->
                if (idx > 0) append(", ")
                append("{\"name\": ${jsonQuote(chip.name)}, \"sizeMb\": ${chip.sizeMb}, \"readViaRoot\": ${chip.readViaRoot}}")
            }
            appendLine("],")
            append("    \"externalChips\": [")
            report.storage.externalChips.forEachIndexed { idx, chip ->
                if (idx > 0) append(", ")
                append("{\"name\": ${jsonQuote(chip.name)}, \"sizeMb\": ${chip.sizeMb}, \"readViaRoot\": ${chip.readViaRoot}}")
            }
            appendLine("]")
            appendLine("  },")
            appendLine("  \"storageTest\": {")
            if (report.storageTest != null) {
                appendLine("    \"performed\": ${!report.storageTest.wasSkipped},")
                appendLine("    \"testedMb\": ${report.storageTest.testedMb},")
                appendLine("    \"writeSpeedMbPerSec\": ${jsonDouble(report.storageTest.writeSpeedMbPerSec)},")
                appendLine("    \"readSpeedMbPerSec\": ${jsonDouble(report.storageTest.readSpeedMbPerSec)},")
                appendLine("    \"isCorrupted\": ${report.storageTest.isCorrupted},")
                appendLine("    \"hasError\": ${report.storageTest.hasError},")
                appendLine("    \"cacheWarning\": ${report.storageTest.cacheWarning},")
                appendLine("    \"message\": ${jsonQuote(report.storageTest.message)}")
            } else {
                appendLine("    \"performed\": false")
            }
            appendLine("  },")
            appendLine("  \"root\": {")
            appendLine("    \"binaryFound\": ${report.root.isRootBinaryFound},")
            appendLine("    \"granted\": ${report.root.isRootGranted},")
            appendLine("    \"selinuxMode\": ${jsonQuote(report.root.selinuxMode)}")
            appendLine("  },")
            appendLine("  \"issuesCount\": ${report.issues.size},")
            append("  \"issues\": [")
            report.issues.forEachIndexed { idx, issue ->
                if (idx > 0) append(",")
                appendLine()
                append("    {\"severity\": ${jsonQuote(issue.severity.name)}, \"title\": ${jsonQuote(issue.title)}, \"description\": ${jsonQuote(issue.description)}}")
            }
            if (report.issues.isNotEmpty()) appendLine()
            appendLine("  ]")
            append("}")
        }
    }
}
