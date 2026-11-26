package com.gokcank.fakehunter

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gokcank.fakehunter.core.detector.AuditCoordinator
import com.gokcank.fakehunter.core.detector.HardwareDetector
import com.gokcank.fakehunter.core.export.EvidenceCollector
import com.gokcank.fakehunter.core.model.AuditReport
import com.gokcank.fakehunter.core.model.IssueFormatter
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
        val darkBarColor = android.graphics.Color.parseColor("#121418")
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(darkBarColor),
            navigationBarStyle = SystemBarStyle.dark(darkBarColor)
        )
        super.onCreate(savedInstanceState)

        AuditCoordinator.ensureInitialAudit(
            this,
            SettingsManager.getAuditOptions(this)
        )

        setContent {
            ModernAppTheme {
                var coordState by remember { mutableStateOf(AuditCoordinator.state) }
                var showAboutDialog by remember { mutableStateOf(false) }
                var showSettingsDialog by remember { mutableStateOf(false) }
                var showQrDialog by remember { mutableStateOf(false) }

                DisposableEffect(Unit) {
                    val listener = object : AuditCoordinator.Listener {
                        override fun onStateChanged(state: AuditCoordinator.State) {
                            coordState = state
                        }
                    }
                    AuditCoordinator.registerListener(listener)
                    onDispose {
                        AuditCoordinator.unregisterListener(listener)
                    }
                }

                val saveLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.CreateDocument("application/zip")
                ) { uri ->
                    if (uri != null) {
                        AuditCoordinator.exportZipToSafUri(this@MainActivity, uri) { success ->
                            val msg = if (success) {
                                getString(R.string.report_saved_success)
                            } else {
                                getString(R.string.report_save_failed)
                            }
                            Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
                        }
                    }
                }

                fun triggerSaveZip() {
                    val current = coordState.report ?: return
                    if (coordState.isExportingZip) return
                    Toast.makeText(this@MainActivity, getString(R.string.report_privacy_notice), Toast.LENGTH_LONG).show()
                    val fileName = EvidenceCollector.generateZipFileName(current)
                    try {
                        saveLauncher.launch(fileName)
                    } catch (_: ActivityNotFoundException) {
                        // Fallback for stripped head units without DocumentsUI
                        AuditCoordinator.exportZipFallback(this@MainActivity) { savedFile ->
                            val msg = if (savedFile != null) {
                                getString(R.string.report_saved_fallback, savedFile.absolutePath)
                            } else {
                                getString(R.string.report_save_failed)
                            }
                            Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
                        }
                    }
                }

                fun copyReport() {
                    val current = coordState.report ?: return
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("FakeHunter Report", current.toFormattedPlainText())
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(this@MainActivity, getString(R.string.copied_to_clipboard), Toast.LENGTH_SHORT).show()
                }

                val progressText = phaseLabel(coordState.phase, coordState.progressDetail)

                ModernDashboard(
                    report = coordState.report,
                    isInspecting = coordState.isInspecting,
                    isExportingZip = coordState.isExportingZip,
                    progressPercent = coordState.progressPercent,
                    progressText = progressText,
                    onInspect = {
                        AuditCoordinator.startFullAudit(
                            this@MainActivity,
                            SettingsManager.getAuditOptions(this@MainActivity)
                        )
                    },
                    onRootInspect = {
                        AuditCoordinator.startRootOnlyAudit(this@MainActivity)
                    },
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

                if (showQrDialog && coordState.report != null) {
                    QrSummaryDialog(
                        report = coordState.report!!,
                        onDismiss = { showQrDialog = false }
                    )
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
    isExportingZip: Boolean,
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
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val isWideScreen = configuration.screenWidthDp >= 600
    val scrollState = rememberScrollState()
    val progressDesc = stringResource(R.string.cd_audit_progress)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121418))
            .windowInsetsPadding(WindowInsets.safeDrawing)
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

            // Row 1: Main actions (equal 1:1:1 weights matching Classic)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onInspect,
                    enabled = !isInspecting && !isExportingZip,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF))
                ) {
                    Text(
                        text = if (isInspecting) "..." else stringResource(R.string.btn_reinspect),
                        color = Color.Black,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }

                Button(
                    onClick = onSaveZip,
                    enabled = report != null && !isInspecting && !isExportingZip,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2D333D))
                ) {
                    Text(
                        text = if (isExportingZip) stringResource(R.string.btn_saving_report) else stringResource(R.string.btn_save_report),
                        color = Color(0xFF00E5FF),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                OutlinedButton(
                    onClick = onOpenQr,
                    enabled = report != null && !isInspecting,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(text = stringResource(R.string.btn_qr_code), color = Color.White, fontSize = 12.sp)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Row 2: Secondary actions (equal 1:1:1 weights)
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

        // Live progress of the audit chain or ZIP export
        if (isInspecting || isExportingZip) {
            Spacer(modifier = Modifier.height(16.dp))
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color(0xFF00E5FF), RoundedCornerShape(12.dp)),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1C2026)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    val label = if (isExportingZip) stringResource(R.string.btn_saving_report) else progressText
                    Text(label, color = Color(0xFF00E5FF), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(8.dp))
                    if (isExportingZip) {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .semantics { contentDescription = progressDesc },
                            color = Color(0xFF00E5FF)
                        )
                    } else {
                        LinearProgressIndicator(
                            progress = { progressPercent / 100f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .semantics { contentDescription = progressDesc },
                            color = Color(0xFF00E5FF)
                        )
                    }
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
                    text = stringResource(R.string.issues_count_label, report.issues.size),
                    color = Color.White,
                    fontSize = 14.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        val systemCard: @Composable (Modifier) -> Unit = { mod ->
            MetricCard(title = stringResource(R.string.header_system), modifier = mod) {
                Text(stringResource(R.string.metric_model, report.system.model, report.system.manufacturer), color = Color.White, fontSize = 13.sp)
                Text(stringResource(R.string.metric_board, report.system.board), color = Color(0xFF90A4AE), fontSize = 12.sp)
                Text(stringResource(R.string.metric_platform, report.system.platform), color = Color(0xFF90A4AE), fontSize = 12.sp)
                Text(stringResource(R.string.metric_android, report.system.reportedRelease, report.system.actualSdkLevel), color = Color.White, fontSize = 13.sp)
            }
        }

        val memoryCard: @Composable (Modifier) -> Unit = { mod ->
            MetricCard(title = stringResource(R.string.header_memory), modifier = mod) {
                Text(stringResource(R.string.metric_ram_physical, report.ram.physicalGbFormatted), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.metric_ram_swap, report.ram.swapGbFormatted), color = Color(0xFF90A4AE), fontSize = 12.sp)
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

        val cpuCard: @Composable (Modifier) -> Unit = { mod ->
            MetricCard(title = stringResource(R.string.header_cpu), modifier = mod) {
                Text(stringResource(R.string.metric_cores, report.cpu.reportedCores, report.cpu.activeCores), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                val maxMhz = report.cpu.coreDetails.maxOfOrNull { maxOf(it.maxFreqMhz, it.currentFreqMhz) } ?: 0L
                if (maxMhz > 0L) {
                    Text(stringResource(R.string.metric_cpu_max_freq, maxMhz), color = Color(0xFF90A4AE), fontSize = 12.sp)
                }
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
        }

        val storageCard: @Composable (Modifier) -> Unit = { mod ->
            MetricCard(title = stringResource(R.string.header_storage), modifier = mod) {
                Text(stringResource(R.string.metric_storage_total, report.storage.totalDataGbFormatted), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.metric_storage_free, report.storage.freeDataGbFormatted), color = Color(0xFF90A4AE), fontSize = 12.sp)
                if (report.storage.flashChips.isNotEmpty()) {
                    Text(
                        stringResource(R.string.metric_flash_physical, report.storage.physicalFlashGbFormatted),
                        color = Color(0xFF00E5FF), fontSize = 12.sp
                    )
                    val chipSummary = report.storage.flashChips.joinToString(", ") { "${it.name}: ${it.sizeGbFormatted}" }
                    Text(stringResource(R.string.metric_chip_detail, chipSummary), color = Color(0xFF90A4AE), fontSize = 11.sp)
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

        val rootCard: @Composable (Modifier) -> Unit = { mod ->
            MetricCard(title = stringResource(R.string.header_root), modifier = mod) {
                val (rootColor, rootText) = when {
                    report.root.isRootGranted -> Pair(Color(0xFF00E676), stringResource(R.string.root_status_granted))
                    report.root.isRootBinaryFound -> Pair(Color(0xFFFFD600), stringResource(R.string.root_status_binary_only))
                    else -> Pair(Color(0xFF90A4AE), stringResource(R.string.root_status_none))
                }
                Text(rootText, color = rootColor, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.metric_selinux, report.root.selinuxMode), color = Color(0xFF90A4AE), fontSize = 12.sp)
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
                        enabled = !isInspecting && !isExportingZip,
                        modifier = Modifier.height(34.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2D333D))
                    ) {
                        Text(stringResource(R.string.btn_deep_root_audit), fontSize = 11.sp, color = Color(0xFF00E5FF), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        val storageTestCard: @Composable (Modifier) -> Unit = { mod ->
            MetricCard(title = stringResource(R.string.header_storage_test), modifier = mod) {
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
                        Text(stringResource(R.string.storage_test_failed, test.message), color = Color(0xFFFF1744), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    else -> {
                        Text(stringResource(R.string.storage_test_tested, test.testedMb), color = Color(0xFF90A4AE), fontSize = 11.sp)
                        Text(
                            stringResource(R.string.storage_test_passed, test.writeSpeedMbPerSec, test.readSpeedMbPerSec),
                            color = Color(0xFF00E676), fontSize = 11.sp, fontWeight = FontWeight.Bold
                        )
                        if (test.cacheWarning) {
                            Text(stringResource(R.string.storage_test_cache_note), color = Color(0xFF90A4AE), fontSize = 11.sp)
                        }
                    }
                }
            }
        }

        if (isWideScreen) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                systemCard(Modifier.weight(1f))
                memoryCard(Modifier.weight(1f))
            }
            Spacer(modifier = Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                cpuCard(Modifier.weight(1f))
                storageCard(Modifier.weight(1f))
            }
            Spacer(modifier = Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                rootCard(Modifier.weight(1f))
                storageTestCard(Modifier.weight(1f))
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                systemCard(Modifier.fillMaxWidth())
                memoryCard(Modifier.fillMaxWidth())
                cpuCard(Modifier.fillMaxWidth())
                storageCard(Modifier.fillMaxWidth())
                rootCard(Modifier.fillMaxWidth())
                storageTestCard(Modifier.fillMaxWidth())
            }
        }

        // Findings Section (only when issues exist)
        if (report.issues.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(stringResource(R.string.findings_header), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
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
                        .padding(vertical = 4.dp)
                        .border(1.dp, Color(0xFF2D333D), RoundedCornerShape(8.dp)),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1C2026)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = IssueFormatter.resolveTitle(context, issue),
                            color = issueColor,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        Text(
                            text = IssueFormatter.resolveDescription(context, issue),
                            color = Color(0xFFCFD8DC),
                            fontSize = 12.sp
                        )
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
    var storageMb by remember { mutableIntStateOf(SettingsManager.getStorageTestMb(context)) }

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
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = stringResource(R.string.settings_language),
                    color = Color(0xFF00E5FF),
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )

                val langOptions = listOf(
                    SettingsManager.LANG_SYSTEM to stringResource(R.string.settings_lang_system),
                    SettingsManager.LANG_TR to stringResource(R.string.settings_lang_tr),
                    SettingsManager.LANG_EN to stringResource(R.string.settings_lang_en)
                )
                langOptions.forEach { (code, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = selectedLang == code,
                                role = Role.RadioButton,
                                onClick = { selectedLang = code }
                            )
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selectedLang == code,
                            onClick = null,
                            colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF00E5FF))
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(label, color = Color.White, fontSize = 13.sp)
                    }
                }

                HorizontalDivider(color = Color(0xFF2D333D))

                Text(
                    text = stringResource(R.string.settings_auto_root),
                    color = Color(0xFF00E5FF),
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                Text(
                    text = stringResource(R.string.settings_auto_root_desc),
                    color = Color(0xFF90A4AE),
                    fontSize = 11.sp
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = autoRoot,
                            role = Role.Switch,
                            onValueChange = { autoRoot = it }
                        )
                        .padding(vertical = 4.dp),
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
                        onCheckedChange = null,
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

                Text(
                    text = stringResource(R.string.settings_storage_test),
                    color = Color(0xFF00E5FF),
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                Text(
                    text = stringResource(R.string.settings_storage_test_desc),
                    color = Color(0xFF90A4AE),
                    fontSize = 11.sp
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(100, 250, 500).forEach { size ->
                        FilterChip(
                            selected = storageMb == size,
                            onClick = { storageMb = size },
                            label = { Text(stringResource(R.string.settings_storage_mb_option, size)) }
                        )
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.btn_cancel), color = Color(0xFF90A4AE), fontWeight = FontWeight.SemiBold)
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
                Text(stringResource(R.string.btn_save), color = Color.Black, fontWeight = FontWeight.Bold)
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
    val configuration = LocalConfiguration.current
    val qrBoxDp = minOf(230f, (configuration.screenHeightDp * 0.48f).coerceAtLeast(150f)).dp
    var qrBitmap by remember(report) { mutableStateOf<Bitmap?>(null) }
    var isGenerating by remember(report) { mutableStateOf(true) }

    DisposableEffect(report) {
        isGenerating = true
        var cancelled = false
        val mainHandler = Handler(Looper.getMainLooper())
        Thread({
            val summary = QrHelper.generateAuditSummary(report)
            val bmp = QrHelper.generateQrBitmap(summary, 512)
            mainHandler.post {
                if (!cancelled) {
                    qrBitmap = bmp
                    isGenerating = false
                }
            }
        }, "FakeHunter-QrGen").apply {
            isDaemon = true
            start()
        }
        onDispose {
            cancelled = true
        }
    }

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
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.qr_dialog_desc),
                    color = Color(0xFF90A4AE),
                    fontSize = 12.sp
                )
                when {
                    isGenerating -> {
                        Text(
                            text = stringResource(R.string.qr_generating),
                            color = Color(0xFF00E5FF),
                            fontSize = 13.sp
                        )
                    }
                    qrBitmap != null -> {
                        Image(
                            bitmap = qrBitmap!!.asImageBitmap(),
                            contentDescription = stringResource(R.string.cd_qr_code),
                            modifier = Modifier
                                .size(qrBoxDp)
                                .background(Color.White, RoundedCornerShape(8.dp))
                                .padding(8.dp)
                        )
                    }
                    else -> {
                        Text(
                            text = stringResource(R.string.qr_error_failed),
                            color = Color(0xFFFF1744),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
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
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.about_subtitle),
                    color = Color(0xFF90A4AE),
                    fontSize = 12.sp
                )
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
