package com.gokcank.fakehunter

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import com.gokcank.fakehunter.core.detector.HardwareDetector
import com.gokcank.fakehunter.core.model.AuditReport
import com.gokcank.fakehunter.core.model.IssueSeverity
import com.gokcank.fakehunter.core.model.Verdict
import com.gokcank.fakehunter.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var currentReport: AuditReport? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnInspect.setOnClickListener {
            runInspection(wake = false)
        }

        binding.btnWakeTest.setOnClickListener {
            runInspection(wake = true)
        }

        binding.btnCopy.setOnClickListener {
            copyReport()
        }

        runInspection(wake = false)
    }

    private fun runInspection(wake: Boolean) {
        binding.btnInspect.isEnabled = false
        binding.btnInspect.text = "..."

        Thread {
            val report = HardwareDetector.performAudit(this, wakeTest = wake)
            runOnUiThread {
                currentReport = report
                displayReport(report)
                binding.btnInspect.isEnabled = true
                binding.btnInspect.text = getString(R.string.btn_reinspect)
            }
        }.start()
    }

    private fun displayReport(report: AuditReport) {
        // Verdict
        val (verdictText, verdictColor) = when (report.verdict) {
            Verdict.VERIFIED -> Pair(getString(R.string.status_clean), Color.parseColor("#00E676"))
            Verdict.SUSPICIOUS -> Pair(getString(R.string.status_suspicious), Color.parseColor("#FFD600"))
            Verdict.FAKE_DETECTED -> Pair(getString(R.string.status_fake), Color.parseColor("#FF1744"))
        }
        binding.tvVerdict.text = verdictText
        binding.tvVerdict.setTextColor(verdictColor)
        binding.tvIssuesCount.text = "Issues: ${report.issues.size}"

        // System
        binding.tvSystemModel.text = "Model: ${report.system.model} (${report.system.manufacturer})"
        binding.tvSystemBoard.text = "Board: ${report.system.board} | ${report.system.platform}"
        binding.tvSystemAndroid.text = "Android: ${report.system.reportedRelease} (API ${report.system.actualSdkLevel})"

        // RAM
        binding.tvRamPhysical.text = "Physical RAM: ${report.ram.physicalGbFormatted}"
        binding.tvRamSwap.text = "Swap / ZRAM: ${report.ram.swapGbFormatted}"
        if (report.ram.isSwapInflated) {
            binding.tvRamWarning.text = getString(R.string.warn_swap_inflated)
            binding.tvRamWarning.setTextColor(Color.parseColor("#FF1744"))
        } else {
            binding.tvRamWarning.text = "Swap Normal"
            binding.tvRamWarning.setTextColor(Color.parseColor("#00E676"))
        }

        // CPU
        binding.tvCpuCores.text = "Reported: ${report.cpu.reportedCores} | Active: ${report.cpu.activeCores}"
        if (report.cpu.isFakeCoresDetected) {
            binding.tvCpuWarning.text = getString(R.string.warn_cores_fake)
            binding.tvCpuWarning.setTextColor(Color.parseColor("#FF1744"))
        } else {
            binding.tvCpuWarning.text = "All reported cores verified"
            binding.tvCpuWarning.setTextColor(Color.parseColor("#00E676"))
        }

        // Storage
        binding.tvStorageTotal.text = "Total /data: ${report.storage.totalDataGbFormatted}"
        binding.tvStorageFree.text = "Free Space: ${report.storage.freeDataGbFormatted}"

        // Findings
        binding.llFindings.removeAllViews()
        report.issues.forEach { issue ->
            val card = CardView(this).apply {
                radius = 8f * resources.displayMetrics.density
                setCardBackgroundColor(Color.parseColor("#1C2026"))
                val lp = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 0, 0, (8 * resources.displayMetrics.density).toInt())
                }
                layoutParams = lp
            }

            val inner = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                val p = (12 * resources.displayMetrics.density).toInt()
                setPadding(p, p, p, p)
            }

            val issueColor = if (issue.severity == IssueSeverity.FAKE) {
                Color.parseColor("#FF1744")
            } else {
                Color.parseColor("#FFD600")
            }

            val tvTitle = TextView(this).apply {
                text = issue.title
                setTextColor(issueColor)
                textSize = 14f
                setTypeface(null, android.graphics.Typeface.BOLD)
            }

            val tvDesc = TextView(this).apply {
                text = issue.description
                setTextColor(Color.parseColor("#CFD8DC"))
                textSize = 12f
            }

            inner.addView(tvTitle)
            inner.addView(tvDesc)
            card.addView(inner)
            binding.llFindings.addView(card)
        }
    }

    private fun copyReport() {
        val report = currentReport ?: return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("FakeHunter Report", report.toFormattedPlainText())
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, getString(R.string.copied_to_clipboard), Toast.LENGTH_SHORT).show()
    }
}
