package com.gokcank.fakehunter

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gokcank.fakehunter.core.detector.HardwareDetector
import com.gokcank.fakehunter.core.model.AuditReport
import com.gokcank.fakehunter.core.model.IssueSeverity
import com.gokcank.fakehunter.core.model.StorageTestResult
import com.gokcank.fakehunter.core.model.Verdict
import com.gokcank.fakehunter.core.storage.StorageTester

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            ModernAppTheme {
                var report by remember { mutableStateOf(HardwareDetector.performAudit(this@MainActivity)) }
                var isInspecting by remember { mutableStateOf(false) }
                var isStorageTesting by remember { mutableStateOf(false) }
                var storageTestProgress by remember { mutableStateOf(0) }
                var storageTestStatus by remember { mutableStateOf("") }
                var storageTestResult by remember { mutableStateOf<StorageTestResult?>(null) }

                fun runInspection(wake: Boolean = false, requestRoot: Boolean = false) {
                    isInspecting = true
                    Thread {
                        val result = HardwareDetector.performAudit(this@MainActivity, wakeTest = wake, requestRoot = requestRoot)
                        runOnUiThread {
                            report = result
                            isInspecting = false
                        }
                    }.start()
                }

                fun runStorageTest(testMb: Int = 500) {
                    isStorageTesting = true
                    storageTestResult = null
                    storageTestProgress = 0
                    storageTestStatus = "Initializing..."
                    Thread {
                        val result = StorageTester.runVerificationTest(this@MainActivity, testMb = testMb) { pct, msg ->
                            runOnUiThread {
                                storageTestProgress = pct
                                storageTestStatus = msg
                            }
                        }
                        runOnUiThread {
                            storageTestResult = result
                            isStorageTesting = false
                        }
                    }.start()
                }

                fun copyReport() {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("FakeHunter Report", report.toFormattedPlainText())
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(this@MainActivity, getString(R.string.copied_to_clipboard), Toast.LENGTH_SHORT).show()
                }

                ModernDashboard(
                    report = report,
                    isInspecting = isInspecting,
                    isStorageTesting = isStorageTesting,
                    storageTestProgress = storageTestProgress,
                    storageTestStatus = storageTestStatus,
                    storageTestResult = storageTestResult,
                    onInspect = { runInspection(wake = false) },
                    onWakeInspect = { runInspection(wake = true) },
                    onRootInspect = { runInspection(requestRoot = true) },
                    onRunStorageTest = { runStorageTest(500) },
                    onCopy = { copyReport() }
                )
            }
        }
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
    report: AuditReport,
    isInspecting: Boolean,
    isStorageTesting: Boolean,
    storageTestProgress: Int,
    storageTestStatus: String,
    storageTestResult: StorageTestResult?,
    onInspect: () -> Unit,
    onWakeInspect: () -> Unit,
    onRootInspect: () -> Unit,
    onRunStorageTest: () -> Unit,
    onCopy: () -> Unit
) {
    val scrollState = rememberScrollState()
    var showAboutDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121418))
            .padding(16.dp)
            .verticalScroll(scrollState)
    ) {
        // Top Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
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
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onInspect,
                    enabled = !isInspecting && !isStorageTesting,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF))
                ) {
                    Text(
                        text = if (isInspecting) "..." else stringResource(R.string.btn_reinspect),
                        color = Color.Black,
                        fontWeight = FontWeight.Bold
                    )
                }

                OutlinedButton(onClick = onCopy) {
                    Text(text = stringResource(R.string.btn_copy_report), color = Color.White)
                }

                OutlinedButton(onClick = { showAboutDialog = true }) {
                    Text(text = stringResource(R.string.btn_about), color = Color.White)
                }
            }
        }

        // About Dialog
        if (showAboutDialog) {
            AlertDialog(
                onDismissRequest = { showAboutDialog = false },
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
                            text = stringResource(R.string.about_version, "1.0.0"),
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
                        onClick = { showAboutDialog = false },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF))
                    ) {
                        Text(stringResource(R.string.btn_close), color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                },
                containerColor = Color(0xFF1C2026),
                shape = RoundedCornerShape(16.dp)
            )
        }

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
                if (report.ram.isSwapInflated) {
                    Text(stringResource(R.string.warn_swap_inflated), color = Color(0xFFFF1744), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                } else {
                    Text("Swap Normal", color = Color(0xFF00E676), fontSize = 12.sp)
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
                if (report.cpu.isFakeCoresDetected) {
                    Text(stringResource(R.string.warn_cores_fake), color = Color(0xFFFF1744), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                } else {
                    Text("All reported cores verified", color = Color(0xFF00E676), fontSize = 12.sp)
                }
                Spacer(modifier = Modifier.height(4.dp))
                Button(
                    onClick = onWakeInspect,
                    modifier = Modifier.height(32.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2D333D))
                ) {
                    Text("Core Wake Test", fontSize = 11.sp, color = Color.White)
                }
            }

            MetricCard(
                title = stringResource(R.string.header_storage),
                modifier = Modifier.weight(1f)
            ) {
                Text("Total /data: ${report.storage.totalDataGbFormatted}", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text("Free Space: ${report.storage.freeDataGbFormatted}", color = Color(0xFF90A4AE), fontSize = 12.sp)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Grid Row 3: Root Security & Storage Verification Test
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Root Card
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
                    Text("Kernel Partitions: ${report.root.rawPartitions.size} devices", color = Color(0xFF00E5FF), fontSize = 12.sp)
                }
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

            // Physical Storage Verification Test Card
            MetricCard(
                title = stringResource(R.string.header_storage_test),
                modifier = Modifier.weight(1f)
            ) {
                Text(stringResource(R.string.storage_test_desc), color = Color(0xFF90A4AE), fontSize = 11.sp)
                Spacer(modifier = Modifier.height(6.dp))

                if (isStorageTesting) {
                    LinearProgressIndicator(
                        progress = { storageTestProgress / 100f },
                        modifier = Modifier.fillMaxWidth().height(6.dp),
                        color = Color(0xFF00E5FF)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(storageTestStatus, color = Color(0xFF00E5FF), fontSize = 11.sp)
                } else {
                    Button(
                        onClick = onRunStorageTest,
                        enabled = !isInspecting,
                        modifier = Modifier.height(32.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF))
                    ) {
                        Text(stringResource(R.string.btn_start_test), fontSize = 11.sp, color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                }

                if (storageTestResult != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    val resColor = if (storageTestResult.isCorrupted) Color(0xFFFF1744) else Color(0xFF00E676)
                    val resText = if (storageTestResult.isCorrupted) {
                        stringResource(R.string.storage_test_failed) + ": " + storageTestResult.message
                    } else {
                        stringResource(R.string.storage_test_passed, storageTestResult.writeSpeedMbPerSec, storageTestResult.readSpeedMbPerSec)
                    }
                    Text(resText, color = resColor, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // Findings Section (if any)
        if (report.issues.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            Text("Findings / Bulgular:", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            report.issues.forEach { issue ->
                val issueColor = if (issue.severity == IssueSeverity.FAKE) Color(0xFFFF1744) else Color(0xFFFFD600)
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
