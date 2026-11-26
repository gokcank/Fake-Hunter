package com.gokcank.fakehunter.core.qr

import android.graphics.Bitmap
import android.graphics.Color
import com.gokcank.fakehunter.core.model.AuditReport
import com.gokcank.fakehunter.core.safeRun
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

object QrHelper {

    fun generateAuditSummary(report: AuditReport): String {
        return buildString {
            appendLine("[FAKE HUNTER REPORT]")
            appendLine("Verdict: ${report.verdict.name}")
            appendLine("Model: ${report.system.model} (${report.system.manufacturer})")
            appendLine("OS: Android ${report.system.reportedRelease} (API ${report.system.actualSdkLevel})")
            appendLine("RAM: ${report.ram.physicalGbFormatted} (Swap: ${report.ram.swapGbFormatted})")
            if (report.ram.isRamSpoofed) {
                appendLine("[!] RAM SPOOFED!")
            } else if (report.ram.isSwapInflated) {
                appendLine("[!] HIGH SWAP!")
            }
            appendLine("CPU: ${report.cpu.reportedCores} rep / ${report.cpu.activeCores} act${if (!report.cpu.frequenciesReadable) " (unreadable)" else ""}")
            if (report.cpu.isFakeCoresDetected) appendLine("[!] FAKE CORES DETECTED!")
            appendLine("Storage: ${report.storage.totalDataGbFormatted} data")
            if (report.storage.flashChips.isNotEmpty()) {
                appendLine("Flash: ${report.storage.physicalFlashGbFormatted}")
            }
            if (report.storage.externalChips.isNotEmpty()) {
                appendLine("Ext USB/SD: ${report.storage.externalStorageGbFormatted}")
            }
            if (report.storageTest != null && !report.storageTest.wasSkipped) {
                when {
                    report.storageTest.isCorrupted -> appendLine("[!] STORAGE CORRUPT!")
                    report.storageTest.hasError -> appendLine("Storage Test: I/O ERROR")
                    else -> appendLine("Storage Test: PASSED (${report.storageTest.writeSpeedMbPerSec} MB/s W)")
                }
            }
            val rootLabel = when {
                report.root.isRootGranted -> "Granted"
                report.root.isRootBinaryFound -> "Binary found"
                else -> "None"
            }
            appendLine("Root: $rootLabel")
            appendLine("Issues: ${report.issues.size}")
        }.trimEnd()
    }

    fun generateQrBitmap(content: String, size: Int = 512): Bitmap? {
        return safeRun("QrGenerate", null) {
            val hints = hashMapOf<EncodeHintType, Any>(
                EncodeHintType.CHARACTER_SET to "UTF-8",
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to 1
            )
            val writer = QRCodeWriter()
            val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size, hints)
            val width = bitMatrix.width
            val height = bitMatrix.height
            val pixels = IntArray(width * height)
            for (y in 0 until height) {
                val rowOffset = y * width
                for (x in 0 until width) {
                    pixels[rowOffset + x] = if (bitMatrix.get(x, y)) Color.BLACK else Color.WHITE
                }
            }
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
                setPixels(pixels, 0, width, 0, 0, width, height)
            }
        }
    }
}
