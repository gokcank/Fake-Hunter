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
import com.gokcank.fakehunter.core.model.Verdict

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            ModernAppTheme {
                var report by remember { mutableStateOf(HardwareDetector.performAudit(this@MainActivity)) }
                var isInspecting by remember { mutableStateOf(false) }

                fun runInspection(wake: Boolean = false) {
                    isInspecting = true
                    Thread {
                        val result = HardwareDetector.performAudit(this@MainActivity, wakeTest = wake)
                        runOnUiThread {
                            report = result
                            isInspecting = false
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
                    onInspect = { runInspection(wake = false) },
                    onWakeInspect = { runInspection(wake = true) },
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
    onInspect: () -> Unit,
    onWakeInspect: () -> Unit,
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
                    enabled = !isInspecting,
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
            }
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

        // Grid of 4 Cards (Landscape 2x2 layout)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Card 1: System Identity
            MetricCard(
                title = stringResource(R.string.header_system),
                modifier = Modifier.weight(1f)
            ) {
                Text("Model: ${report.system.model} (${report.system.manufacturer})", color = Color.White, fontSize = 13.sp)
                Text("Board: ${report.system.board}", color = Color(0xFF90A4AE), fontSize = 12.sp)
                Text("Platform: ${report.system.platform}", color = Color(0xFF90A4AE), fontSize = 12.sp)
                Text("Android: ${report.system.reportedRelease} (API ${report.system.actualSdkLevel})", color = Color.White, fontSize = 13.sp)
            }

            // Card 2: Memory
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

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Card 3: CPU Cores
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

            // Card 4: Storage
            MetricCard(
                title = stringResource(R.string.header_storage),
                modifier = Modifier.weight(1f)
            ) {
                Text("Total /data: ${report.storage.totalDataGbFormatted}", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text("Free Space: ${report.storage.freeDataGbFormatted}", color = Color(0xFF90A4AE), fontSize = 12.sp)
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
