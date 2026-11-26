package com.gokcank.fakehunter

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gokcank.fakehunter.core.detector.HardwareDetector
import com.gokcank.fakehunter.core.export.EvidenceCollector
import com.gokcank.fakehunter.core.model.AuditOptions
import com.gokcank.fakehunter.core.model.AuditReport
import com.gokcank.fakehunter.core.model.IssueSeverity
import com.gokcank.fakehunter.core.model.Verdict
import com.gokcank.fakehunter.core.qr.QrHelper
import com.gokcank.fakehunter.core.settings.LocaleHelper
import com.gokcank.fakehunter.core.settings.SettingsManager

class MainActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        val lang = SettingsManager.getLanguage(newBase)
        super.attachBaseContext(LocaleHelper.applyLocale(newBase, lang))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            ModernAppTheme {
                var report by remember { mutableStateOf<AuditReport?>(null) }
                var isInspecting by remember { mutableStateOf(false) }
                var progressPercent by remember { mutableStateOf(0) }
                var progressText by remember { mutableStateOf("") }
                var currentPhase by remember { mutableStateOf(HardwareDetector.Phase.SYSTEM) }
                var showAboutDialog by remember { mutableStateOf(false) }
                var showSettingsDialog by remember { mutableStateOf(false) }
                var showQrDialog by remember { mutableStateOf(false) }

                fun runInspection(options: AuditOptions = SettingsManager.getAuditOptions(this@MainActivity)) {
                    if (isInspecting) return
                    isInspecting = true
                    progressPercent = 0
                    Thread {
                        val result = HardwareDetector.performAudit(this@MainActivity, options) { phase, pct, detail ->
                            runOnUiThread {
                                currentPhase = phase
                                progressPercent = pct
                                progressText = phaseLabel(phase, detail)
                            }
                        }
                        runOnUiThread {
                            report = result
                            isInspecting = false
                        }
                    }.start()
                }

                // Storage Access Framework launcher for saving evidence ZIP
                val saveLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.CreateDocument("application/zip")
                ) { uri ->
                    val targetReport = report
                    if (uri != null && targetReport != null) {
                        Thread {
                            val success = try {
                                contentResolver.openOutputStream(uri)?.use { os ->
                                    EvidenceCollector.exportEvidenceZip(targetReport, os)
                                } ?: false
                            } catch (e: Exception) {
                                false
                            }
                            if (!success) {
                                EvidenceCollector.deletePartialSafDocument(this@MainActivity, uri)
                            }
                            runOnUiThread {
                                val msg = if (success) {
                                    getString(R.string.report_saved_success)
                                } else {
                                    getString(R.string.report_save_failed)
                                }
                                Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
                            }
                        }.start()
                    }
                }

                fun triggerSaveZip() {
                    val current = report ?: return
                    Toast.makeText(this@MainActivity, getString(R.string.report_privacy_notice), Toast.LENGTH_LONG).show()
                    val fileName = EvidenceCollector.generateZipFileName(current)
                    saveLauncher.launch(fileName)
                }

                fun copyReport() {
                    val current = report ?: return
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("FakeHunter Report", current.toFormattedPlainText())
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(this@MainActivity, getString(R.string.copied_to_clipboard), Toast.LENGTH_SHORT).show()
                }

                LaunchedEffect(Unit) { runInspection() }

                ModernDashboard(
                    report = report,
                    isInspecting = isInspecting,
                    progressPercent = progressPercent,
                    progressText = progressText,
                    onInspect = { runInspection() },
                    onRootInspect = { runInspection(SettingsManager.getAuditOptions(this@MainActivity).copy(requestRoot = true)) },
                    onSaveZip = { triggerSaveZip() },
                    onOpenQr = { showQrDialog = true },
                    onOpenSettings = { showSettingsDialog = true },
                    onOpenAbout = { showAboutDialog = true },
                    onCopy = { copyReport() }
                )

                if (showAboutDialog) {
                    AboutDialog(onDismiss = { showAboutDialog = false })
                }

                if (showSettingsDialog) {
                    SettingsDialog(
                        context = this@MainActivity,
                        onDismiss = { showSettingsDialog = false },
                        onLanguageChanged = {
                            showSettingsDialog = false
                            recreate()
                        }
                    )
                }

                if (showQrDialog && report != null) {
                    QrSummaryDialog(report = report!!, onDismiss = { showQrDialog = false })
                }
            }
        }
    }

    private fun phaseLabel(phase: HardwareDetector.Phase, detail: String): String {
        val base = when (phase) {
            HardwareDetector.Phase.SYSTEM -> getString(R.string.progress_system)
            HardwareDetector.Phase.CPU_WAKE -> getString(R.string.progress_cpu)
            HardwareDetector.Phase.ROOT -> getString(R.string.progress_root)
            HardwareDetector.Phase.FLASH -> getString(R.string.progress_flash)
            HardwareDetector.Phase.STORAGE_TEST -> getString(R.string.progress_storage)
            HardwareDetector.Phase.DONE -> getString(R.string.progress_done)
        }
        return if (detail.isNotBlank()) "$base $detail" else base
    }
}

@Composable
fun ModernAppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Color(0xFF121418),
            surface = Color(0xFF1C2026),
            primary = Color(0xFF00E5FF)
        ),
        content = content
    )
}

@Composable
fun ModernDashboard(
    report: AuditReport?,
    isInspecting: Boolean,
    progressPercent: Int,
    progressText: String,
    onInspect: () -> Unit,
    onRootInspect: () -> Unit,
    onSaveZip: () -> Unit,
    onOpenQr: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAbout: () -> Unit,
    onCopy: () -> Unit
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121418))
            .padding(16.dp)
            .verticalScroll(scrollState)
    ) {
        // Top Header
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.app_name),
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Text(
                text = stringResource(R.string.app_subtitle),
                fontSize = 14.sp,
                color = Color(0xFF90A4AE)
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Row 1: Main actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onInspect,
                    enabled = !isInspecting,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF))
                ) {
                    Text(
                        text = if (isInspecting) "..." else stringResource(R.string.btn_reinspect),
                        color = Color.Black,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }

                Button(
                    onClick = onSaveZip,
                    enabled = report != null && !isInspecting,
                    modifier = Modifier.weight(1.2f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2D333D))
                ) {
                    Text(text = stringResource(R.string.btn_save_report), color = Color(0xFF00E5FF), fontSize = 13.sp)
                }

                OutlinedButton(
                    onClick = onOpenQr,
                    enabled = report != null && !isInspecting,
                    modifier = Modifier.weight(0.9f)
                ) {
                    Text(text = stringResource(R.string.btn_qr_code), color = Color.White, fontSize = 13.sp)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Row 2: Secondary actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onCopy,
                    enabled = report != null && !isInspecting,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(text = stringResource(R.string.btn_copy_report), color = Color.White, fontSize = 12.sp)
                }

                OutlinedButton(
                    onClick = onOpenSettings,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(text = stringResource(R.string.btn_settings), color = Color.White, fontSize = 12.sp)
                }

                OutlinedButton(
                    onClick = onOpenAbout,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(text = stringResource(R.string.btn_about), color = Color.White, fontSize = 12.sp)
                }
            }
        }

        // Live progress of the audit chain
        if (isInspecting) {
            Spacer(modifier = Modifier.height(16.dp))
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color(0xFF00E5FF), RoundedCornerShape(12.dp)),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1C2026)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(progressText, color = Color(0xFF00E5FF), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { progressPercent / 100f },
                        modifier = Modifier.fillMaxWidth().height(6.dp),
                        color = Color(0xFF00E5FF)
                    )
                }
            }
        }

        if (report == null) return@Column

        Spacer(modifier = Modifier.height(16.dp))

        // Verdict Banner
        val verdictBgColor = when (report.verdict) {
            Verdict.VERIFIED -> Color(0xFF00E676)
            Verdict.SUSPICIOUS -> Color(0xFFFFD600)
            Verdict.FAKE_DETECTED -> Color(0xFFFF1744)
        }
        val verdictText = when (report.verdict) {
            Verdict.VERIFIED -> stringResource(R.string.status_clean)
            Verdict.SUSPICIOUS -> stringResource(R.string.status_suspicious)
            Verdict.FAKE_DETECTED -> stringResource(R.string.status_fake)
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, verdictBgColor, RoundedCornerShape(12.dp)),
            colors = CardDefaults.cardColors(containerColor = verdictBgColor.copy(alpha = 0.15f)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = verdictText,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = verdictBgColor
                )
                Text(
                    text = "Issues: ${report.issues.size}",
                    color = Color.White,
                    fontSize = 14.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Grid Row 1: System Identity & Memory
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            MetricCard(
                title = stringResource(R.string.header_system),
                modifier = Modifier.weight(1f)
            ) {
                Text("Model: ${report.system.model} (${report.system.manufacturer})", color = Color.White, fontSize = 13.sp)
                Text("Board: ${report.system.board}", color = Color(0xFF90A4AE), fontSize = 12.sp)
                Text("Platform: ${report.system.platform}", color = Color(0xFF90A4AE), fontSize = 12.sp)
                Text("Android: ${report.system.reportedRelease} (API ${report.system.actualSdkLevel})", color = Color.White, fontSize = 13.sp)
            }

            MetricCard(
                title = stringResource(R.string.header_memory),
                modifier = Modifier.weight(1f)
            ) {
                Text("Physical RAM: ${report.ram.physicalGbFormatted}", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text("Swap / ZRAM: ${report.ram.swapGbFormatted}", color = Color(0xFF90A4AE), fontSize = 12.sp)
                when {
                    report.ram.isRamSpoofed ->
                        Text(stringResource(R.string.warn_ram_spoofed), color = Color(0xFFFF1744), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    report.ram.isSwapInflated ->
                        Text(stringResource(R.string.warn_swap_inflated), color = Color(0xFFFFD600), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    report.ram.hasVirtualRamNotice ->
                        Text(stringResource(R.string.ram_virtual_active), color = Color(0xFF00E5FF), fontSize = 12.sp)
                    else ->
                        Text(stringResource(R.string.ram_swap_normal), color = Color(0xFF00E676), fontSize = 12.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Grid Row 2: CPU Cores & Storage
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            MetricCard(
                title = stringResource(R.string.header_cpu),
                modifier = Modifier.weight(1f)
            ) {
                Text("Reported: ${report.cpu.reportedCores} | Active: ${report.cpu.activeCores}", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                when {
                    !report.cpu.frequenciesReadable ->
                        Text(stringResource(R.string.cpu_freq_unreadable), color = Color(0xFFFFD600), fontSize = 12.sp)
                    report.cpu.isFakeCoresDetected ->
                        Text(stringResource(R.string.warn_cores_fake), color = Color(0xFFFF1744), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    else ->
                        Text(stringResource(R.string.cpu_cores_verified), color = Color(0xFF00E676), fontSize = 12.sp)
                }
                if (report.cpu.wakeTestPerformed) {
                    Text(stringResource(R.string.cpu_wake_done), color = Color(0xFF90A4AE), fontSize = 11.sp)
                }
            }

            MetricCard(
                title = stringResource(R.string.header_storage),
                modifier = Modifier.weight(1f)
            ) {
                Text("Total /data: ${report.storage.totalDataGbFormatted}", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text("Free Space: ${report.storage.freeDataGbFormatted}", color = Color(0xFF90A4AE), fontSize = 12.sp)
                if (report.storage.flashChips.isNotEmpty()) {
                    Text(
                        stringResource(R.string.metric_flash_physical, report.storage.physicalFlashGbFormatted),
                        color = Color(0xFF00E5FF), fontSize = 12.sp
                    )
                } else {
                    Text(stringResource(R.string.metric_flash_unreadable), color = Color(0xFF90A4AE), fontSize = 12.sp)
                }
                if (report.storage.externalChips.isNotEmpty()) {
                    Text(
                        stringResource(R.string.metric_external_storage, report.storage.externalStorageGbFormatted),
                        color = Color(0xFF90A4AE), fontSize = 11.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Grid Row 3: Root Security & Storage Verification Test
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            MetricCard(
                title = stringResource(R.string.header_root),
                modifier = Modifier.weight(1f)
            ) {
                val (rootColor, rootText) = when {
                    report.root.isRootGranted -> Pair(Color(0xFF00E676), stringResource(R.string.root_status_granted))
                    report.root.isRootBinaryFound -> Pair(Color(0xFFFFD600), stringResource(R.string.root_status_binary_only))
                    else -> Pair(Color(0xFF90A4AE), stringResource(R.string.root_status_none))
                }
                Text(rootText, color = rootColor, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text("SELinux: ${report.root.selinuxMode}", color = Color(0xFF90A4AE), fontSize = 12.sp)
                if (report.root.rawPartitions.isNotEmpty()) {
                    Text(stringResource(R.string.root_partitions, report.root.rawPartitions.size), color = Color(0xFF00E5FF), fontSize = 12.sp)
                }
                if (report.root.dmesgHighlights.isNotEmpty()) {
                    Text(stringResource(R.string.root_dmesg_lines, report.root.dmesgHighlights.size), color = Color(0xFF00E5FF), fontSize = 12.sp)
                }
                if (report.root.isRootBinaryFound && !report.root.isRootGranted) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Button(
                        onClick = onRootInspect,
                        enabled = !isInspecting,
                        modifier = Modifier.height(32.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2D333D))
                    ) {
                        Text(stringResource(R.string.btn_deep_root_audit), fontSize = 11.sp, color = Color.White)
                    }
                }
            }

            MetricCard(
                title = stringResource(R.string.header_storage_test),
                modifier = Modifier.weight(1f)
            ) {
                Text(stringResource(R.string.storage_test_desc), color = Color(0xFF90A4AE), fontSize = 11.sp)
                Spacer(modifier = Modifier.height(6.dp))
                val test = report.storageTest
                when {
                    test == null -> Unit
                    test.hasError ->
                        Text(stringResource(R.string.storage_test_error, test.message), color = Color(0xFFFFD600), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    test.wasSkipped ->
                        Text(stringResource(R.string.storage_test_skipped, test.message), color = Color(0xFFFFD600), fontSize = 11.sp)
                    test.isCorrupted ->
                        Text(stringResource(R.string.storage_test_failed) + ": " + test.message, color = Color(0xFFFF1744), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    else -> {
                        Text(stringResource(R.string.storage_test_tested, test.testedMb), color = Color(0xFF90A4AE), fontSize = 11.sp)
                        Text(
                            stringResource(R.string.storage_test_passed, test.writeSpeedMbPerSec, test.readSpeedMbPerSec),
                            color = Color(0xFF00E676), fontSize = 11.sp, fontWeight = FontWeight.Bold
                        )
                        if (test.cacheWarning) {
                            Text(stringResource(R.string.storage_test_cache_note), color = Color(0xFF90A4AE), fontSize = 10.sp)
                        }
                    }
                }
            }
        }

        // Findings Section (if any)
        if (report.issues.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            Text("Findings / Bulgular:", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            report.issues.forEach { issue ->
                val issueColor = when (issue.severity) {
                    IssueSeverity.FAKE -> Color(0xFFFF1744)
                    IssueSeverity.WARNING -> Color(0xFFFFD600)
                    IssueSeverity.INFO -> Color(0xFF00E5FF)
                }
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1C2026)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(text = issue.title, color = issueColor, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text(text = issue.description, color = Color(0xFFCFD8DC), fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsDialog(
    context: Context,
    onDismiss: () -> Unit,
    onLanguageChanged: () -> Unit
) {
    var selectedLang by remember { mutableStateOf(SettingsManager.getLanguage(context)) }
    var autoRoot by remember { mutableStateOf(SettingsManager.isAutoRoot(context)) }
    var storageMb by remember { mutableStateOf(SettingsManager.getStorageTestMb(context)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.settings_title),
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Language
                Text(text = stringResource(R.string.settings_language), color = Color(0xFF00E5FF), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = selectedLang == SettingsManager.LANG_SYSTEM,
                        onClick = { selectedLang = SettingsManager.LANG_SYSTEM },
                        colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF00E5FF))
                    )
                    Text(stringResource(R.string.settings_lang_system), color = Color.White, fontSize = 13.sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = selectedLang == SettingsManager.LANG_TR,
                        onClick = { selectedLang = SettingsManager.LANG_TR },
                        colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF00E5FF))
                    )
                    Text(stringResource(R.string.settings_lang_tr), color = Color.White, fontSize = 13.sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = selectedLang == SettingsManager.LANG_EN,
                        onClick = { selectedLang = SettingsManager.LANG_EN },
                        colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF00E5FF))
                    )
                    Text(stringResource(R.string.settings_lang_en), color = Color.White, fontSize = 13.sp)
                }

                HorizontalDivider(color = Color(0xFF2D333D))

                // Auto Root
                Text(text = stringResource(R.string.settings_auto_root), color = Color(0xFF00E5FF), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(text = stringResource(R.string.settings_auto_root_desc), color = Color(0xFF90A4AE), fontSize = 11.sp)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { autoRoot = !autoRoot },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (autoRoot) stringResource(R.string.settings_state_enabled) else stringResource(R.string.settings_state_disabled),
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Switch(
                        checked = autoRoot,
                        onCheckedChange = { autoRoot = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color(0xFF121418),
                            checkedTrackColor = Color(0xFF00E5FF),
                            checkedBorderColor = Color.Transparent,
                            uncheckedThumbColor = Color(0xFF90A4AE),
                            uncheckedTrackColor = Color(0xFF2D333D),
                            uncheckedBorderColor = Color(0xFF90A4AE)
                        )
                    )
                }

                HorizontalDivider(color = Color(0xFF2D333D))

                // Storage Test Size
                Text(text = stringResource(R.string.settings_storage_test), color = Color(0xFF00E5FF), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(text = stringResource(R.string.settings_storage_test_desc), color = Color(0xFF90A4AE), fontSize = 11.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(100, 250, 500).forEach { size ->
                        FilterChip(
                            selected = storageMb == size,
                            onClick = { storageMb = size },
                            label = { Text("$size MB") }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val langChanged = selectedLang != SettingsManager.getLanguage(context)
                    SettingsManager.setLanguage(context, selectedLang)
                    SettingsManager.setAutoRoot(context, autoRoot)
                    SettingsManager.setStorageTestMb(context, storageMb)
                    if (langChanged) {
                        onLanguageChanged()
                    } else {
                        onDismiss()
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF))
            ) {
                Text(stringResource(R.string.btn_close), color = Color.Black, fontWeight = FontWeight.Bold)
            }
        },
        containerColor = Color(0xFF1C2026),
        shape = RoundedCornerShape(16.dp)
    )
}

@Composable
fun QrSummaryDialog(
    report: AuditReport,
    onDismiss: () -> Unit
) {
    val qrText = remember(report) { QrHelper.generateAuditSummary(report) }
    val qrBitmap = remember(qrText) { QrHelper.generateQrBitmap(qrText, 512) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.qr_dialog_title),
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.qr_dialog_desc),
                    color = Color(0xFF90A4AE),
                    fontSize = 12.sp
                )
                if (qrBitmap != null) {
                    Image(
                        bitmap = qrBitmap.asImageBitmap(),
                        contentDescription = "QR Code",
                        modifier = Modifier
                            .size(260.dp)
                            .background(Color.White, RoundedCornerShape(8.dp))
                            .padding(8.dp)
                    )
                } else {
                    Text("Error generating QR", color = Color(0xFFFF1744))
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF))
            ) {
                Text(stringResource(R.string.btn_close), color = Color.Black, fontWeight = FontWeight.Bold)
            }
        },
        containerColor = Color(0xFF1C2026),
        shape = RoundedCornerShape(16.dp)
    )
}

@Composable
fun AboutDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.about_title),
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.about_version, BuildConfig.VERSION_NAME),
                    color = Color(0xFF00E5FF),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp
                )
                Text(
                    text = stringResource(R.string.about_description),
                    color = Color(0xFFCFD8DC),
                    fontSize = 13.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.about_privacy_title),
                    color = Color(0xFF00E676),
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                Text(
                    text = stringResource(R.string.about_privacy_desc),
                    color = Color(0xFF90A4AE),
                    fontSize = 12.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.about_license_text),
                    color = Color(0xFFCFD8DC),
                    fontSize = 12.sp
                )
                Text(
                    text = stringResource(R.string.about_github_url),
                    color = Color(0xFF00E5FF),
                    fontSize = 12.sp
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF))
            ) {
                Text(stringResource(R.string.btn_close), color = Color.Black, fontWeight = FontWeight.Bold)
            }
        },
        containerColor = Color(0xFF1C2026),
        shape = RoundedCornerShape(16.dp)
    )
}

@Composable
fun MetricCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.border(1.dp, Color(0xFF2D333D), RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1C2026)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = title, color = Color(0xFF00E5FF), fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Spacer(modifier = Modifier.height(8.dp))
            content()
        }
    }
}
