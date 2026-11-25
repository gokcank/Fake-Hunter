package com.gokcank.fakehunter

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.gokcank.fakehunter.core.detector.HardwareDetector
import com.gokcank.fakehunter.core.export.EvidenceCollector
import com.gokcank.fakehunter.core.model.AuditOptions
import com.gokcank.fakehunter.core.model.AuditReport
import com.gokcank.fakehunter.core.model.IssueSeverity
import com.gokcank.fakehunter.core.model.Verdict
import com.gokcank.fakehunter.core.qr.QrHelper
import com.gokcank.fakehunter.core.settings.LocaleHelper
import com.gokcank.fakehunter.core.settings.SettingsManager
import com.gokcank.fakehunter.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var currentReport: AuditReport? = null
    private var isInspecting: Boolean = false

    override fun attachBaseContext(newBase: Context) {
        val lang = SettingsManager.getLanguage(newBase)
        super.attachBaseContext(LocaleHelper.applyLocale(newBase, lang))
    }

    private val saveLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        val report = currentReport
        if (uri != null && report != null) {
            Thread {
                val success = try {
                    contentResolver.openOutputStream(uri)?.use { os ->
                        EvidenceCollector.exportEvidenceZip(this, report, os)
                    } ?: false
                } catch (e: Exception) {
                    false
                }
                runOnUiThread {
                    val msg = if (success) {
                        getString(R.string.report_saved_success)
                    } else {
                        getString(R.string.report_save_failed)
                    }
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                }
            }.start()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnInspect.setOnClickListener {
            runInspection()
        }

        binding.btnSave.setOnClickListener {
            triggerSaveZip()
        }

        binding.btnQr.setOnClickListener {
            showQrDialog()
        }

        binding.btnDeepRootAudit.setOnClickListener {
            runInspection(SettingsManager.getAuditOptions(this).copy(requestRoot = true))
        }

        binding.btnCopy.setOnClickListener {
            copyReport()
        }

        binding.btnSettings.setOnClickListener {
            showSettingsDialog()
        }

        binding.btnAbout.setOnClickListener {
            showAboutDialog()
        }

        runInspection()
    }

    private fun runInspection(options: AuditOptions = SettingsManager.getAuditOptions(this)) {
        if (isInspecting) return
        isInspecting = true
        binding.btnInspect.isEnabled = false
        binding.btnInspect.text = "..."
        binding.btnSave.isEnabled = false
        binding.btnQr.isEnabled = false
        binding.btnCopy.isEnabled = false
        binding.llProgress.visibility = View.VISIBLE
        binding.pbAudit.progress = 0
        binding.tvProgress.text = getString(R.string.progress_system)

        Thread {
            val report = HardwareDetector.performAudit(this, options) { phase, pct, detail ->
                runOnUiThread {
                    binding.pbAudit.progress = pct
                    binding.tvProgress.text = phaseLabel(phase, detail)
                }
            }
            runOnUiThread {
                currentReport = report
                displayReport(report)
                isInspecting = false
                binding.llProgress.visibility = View.GONE
                binding.btnInspect.isEnabled = true
                binding.btnInspect.text = getString(R.string.btn_reinspect)
                binding.btnSave.isEnabled = true
                binding.btnQr.isEnabled = true
                binding.btnCopy.isEnabled = true
            }
        }.start()
    }

    private fun triggerSaveZip() {
        val report = currentReport ?: return
        val fileName = EvidenceCollector.generateZipFileName(report)
        saveLauncher.launch(fileName)
    }

    private fun showQrDialog() {
        val report = currentReport ?: return
        val qrText = QrHelper.generateAuditSummary(report)
        val qrBitmap = QrHelper.generateQrBitmap(qrText, 512) ?: return

        val imageView = ImageView(this).apply {
            setImageBitmap(qrBitmap)
            setBackgroundColor(Color.WHITE)
            val p = (16 * resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p)
            val size = (260 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                gravity = android.view.Gravity.CENTER
            }
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            val p = (16 * resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p)
            addView(TextView(this@MainActivity).apply {
                text = getString(R.string.qr_dialog_desc)
                setTextColor(Color.parseColor("#90A4AE"))
                textSize = 12f
                setPadding(0, 0, 0, (12 * resources.displayMetrics.density).toInt())
            })
            addView(imageView)
        }

        MaterialAlertDialogBuilder(this, R.style.Theme_FakeHunter_Dialog)
            .setTitle(getString(R.string.qr_dialog_title))
            .setView(container)
            .setPositiveButton(getString(R.string.btn_close), null)
            .show()
    }

    private fun showSettingsDialog() {
        val currentLang = SettingsManager.getLanguage(this)
        var selectedLang = currentLang
        var autoRoot = SettingsManager.isAutoRoot(this)
        var storageMb = SettingsManager.getStorageTestMb(this)

        val accentColor = Color.parseColor("#00E5FF")
        val cyanCsl = ColorStateList.valueOf(accentColor)
        val cardStrokeColor = Color.parseColor("#2D333D")

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (16 * resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p)
        }

        // Language radio group
        val tvLangHeader = TextView(this).apply {
            text = getString(R.string.settings_language)
            setTextColor(accentColor)
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        container.addView(tvLangHeader)

        val rgLang = RadioGroup(this).apply {
            val pTop = (4 * resources.displayMetrics.density).toInt()
            setPadding(0, pTop, 0, 0)
        }
        val rbSystem = RadioButton(this).apply {
            text = getString(R.string.settings_lang_system)
            setTextColor(Color.WHITE)
            buttonTintList = cyanCsl
            isChecked = selectedLang == SettingsManager.LANG_SYSTEM
        }
        val rbTr = RadioButton(this).apply {
            text = getString(R.string.settings_lang_tr)
            setTextColor(Color.WHITE)
            buttonTintList = cyanCsl
            isChecked = selectedLang == SettingsManager.LANG_TR
        }
        val rbEn = RadioButton(this).apply {
            text = getString(R.string.settings_lang_en)
            setTextColor(Color.WHITE)
            buttonTintList = cyanCsl
            isChecked = selectedLang == SettingsManager.LANG_EN
        }
        rgLang.addView(rbSystem)
        rgLang.addView(rbTr)
        rgLang.addView(rbEn)
        container.addView(rgLang)

        // Divider
        container.addView(View(this).apply {
            setBackgroundColor(cardStrokeColor)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 2).apply {
                val m = (12 * resources.displayMetrics.density).toInt()
                setMargins(0, m, 0, m)
            }
        })

        // Auto Root CheckBox / Switch
        val cbAutoRoot = CheckBox(this).apply {
            text = getString(R.string.settings_auto_root)
            setTextColor(Color.WHITE)
            buttonTintList = cyanCsl
            isChecked = autoRoot
        }
        container.addView(cbAutoRoot)
        container.addView(TextView(this).apply {
            text = getString(R.string.settings_auto_root_desc)
            setTextColor(Color.parseColor("#90A4AE"))
            textSize = 11f
            val pStart = (28 * resources.displayMetrics.density).toInt()
            setPadding(pStart, 0, 0, 0)
        })

        // Divider
        container.addView(View(this).apply {
            setBackgroundColor(cardStrokeColor)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 2).apply {
                val m = (12 * resources.displayMetrics.density).toInt()
                setMargins(0, m, 0, m)
            }
        })

        // Storage Test Size
        val tvStorageHeader = TextView(this).apply {
            text = getString(R.string.settings_storage_test)
            setTextColor(accentColor)
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        container.addView(tvStorageHeader)
        container.addView(TextView(this).apply {
            text = getString(R.string.settings_storage_test_desc)
            setTextColor(Color.parseColor("#90A4AE"))
            textSize = 11f
            setPadding(0, 2, 0, 4)
        })

        val rgStorage = RadioGroup(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(100, 250, 500).forEach { size ->
            val rb = RadioButton(this).apply {
                text = "$size MB"
                setTextColor(Color.WHITE)
                buttonTintList = cyanCsl
                isChecked = storageMb == size
                setOnClickListener { storageMb = size }
            }
            rgStorage.addView(rb)
        }
        container.addView(rgStorage)

        MaterialAlertDialogBuilder(this, R.style.Theme_FakeHunter_Dialog)
            .setTitle(getString(R.string.settings_title))
            .setView(container)
            .setPositiveButton(getString(R.string.btn_close)) { _, _ ->
                val newLang = when {
                    rbTr.isChecked -> SettingsManager.LANG_TR
                    rbEn.isChecked -> SettingsManager.LANG_EN
                    else -> SettingsManager.LANG_SYSTEM
                }
                val langChanged = newLang != currentLang
                SettingsManager.setLanguage(this, newLang)
                SettingsManager.setAutoRoot(this, cbAutoRoot.isChecked)
                SettingsManager.setStorageTestMb(this, storageMb)
                if (langChanged) {
                    recreate()
                }
            }
            .show()
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
        if (report.cpu.wakeTestPerformed) {
            binding.tvCpuWake.visibility = View.VISIBLE
        } else {
            binding.tvCpuWake.visibility = View.GONE
        }

        // Storage
        binding.tvStorageTotal.text = "Total /data: ${report.storage.totalDataGbFormatted}"
        binding.tvStorageFree.text = "Free Space: ${report.storage.freeDataGbFormatted}"
        if (report.storage.flashChips.isNotEmpty()) {
            binding.tvStorageFlash.visibility = View.VISIBLE
            binding.tvStorageFlash.text = getString(R.string.metric_flash_physical, report.storage.physicalFlashGbFormatted)
        } else {
            binding.tvStorageFlash.visibility = View.VISIBLE
            binding.tvStorageFlash.text = getString(R.string.metric_flash_unreadable)
        }

        // Root Security
        val (rootText, rootColor) = when {
            report.root.isRootGranted -> Pair(getString(R.string.root_status_granted), Color.parseColor("#00E676"))
            report.root.isRootBinaryFound -> Pair(getString(R.string.root_status_binary_only), Color.parseColor("#FFD600"))
            else -> Pair(getString(R.string.root_status_none), Color.parseColor("#90A4AE"))
        }
        binding.tvRootStatus.text = rootText
        binding.tvRootStatus.setTextColor(rootColor)
        binding.tvRootSelinux.text = "SELinux: ${report.root.selinuxMode}"
        if (report.root.rawPartitions.isNotEmpty()) {
            binding.tvRootPartitions.visibility = View.VISIBLE
            binding.tvRootPartitions.text = getString(R.string.root_partitions, report.root.rawPartitions.size)
        } else {
            binding.tvRootPartitions.visibility = View.GONE
        }
        if (report.root.dmesgHighlights.isNotEmpty()) {
            binding.tvRootDmesg.visibility = View.VISIBLE
            binding.tvRootDmesg.text = getString(R.string.root_dmesg_lines, report.root.dmesgHighlights.size)
        } else {
            binding.tvRootDmesg.visibility = View.GONE
        }
        if (report.root.isRootBinaryFound && !report.root.isRootGranted) {
            binding.btnDeepRootAudit.visibility = View.VISIBLE
        } else {
            binding.btnDeepRootAudit.visibility = View.GONE
        }

        // Storage Test Result
        val test = report.storageTest
        when {
            test == null -> {
                binding.tvStorageTestProgress.visibility = View.GONE
                binding.tvStorageTestSize.visibility = View.GONE
                binding.tvStorageTestResult.visibility = View.GONE
            }
            test.wasSkipped -> {
                binding.tvStorageTestProgress.visibility = View.GONE
                binding.tvStorageTestSize.visibility = View.GONE
                binding.tvStorageTestResult.visibility = View.VISIBLE
                binding.tvStorageTestResult.setTextColor(Color.parseColor("#FFD600"))
                binding.tvStorageTestResult.text = getString(R.string.storage_test_skipped, test.message)
            }
            test.isCorrupted -> {
                binding.tvStorageTestProgress.visibility = View.GONE
                binding.tvStorageTestSize.visibility = View.GONE
                binding.tvStorageTestResult.visibility = View.VISIBLE
                binding.tvStorageTestResult.setTextColor(Color.parseColor("#FF1744"))
                binding.tvStorageTestResult.text = "${getString(R.string.storage_test_failed)}: ${test.message}"
            }
            else -> {
                binding.tvStorageTestProgress.visibility = View.GONE
                binding.tvStorageTestSize.visibility = View.VISIBLE
                binding.tvStorageTestSize.text = getString(R.string.storage_test_tested, test.testedMb)
                binding.tvStorageTestResult.visibility = View.VISIBLE
                binding.tvStorageTestResult.setTextColor(Color.parseColor("#00E676"))
                binding.tvStorageTestResult.text = getString(R.string.storage_test_passed, test.writeSpeedMbPerSec, test.readSpeedMbPerSec)
            }
        }

        // Findings
        binding.llFindings.removeAllViews()
        report.issues.forEach { issue ->
            val card = CardView(this).apply {
                radius = 8f * resources.displayMetrics.density
                setCardBackgroundColor(Color.parseColor("#1C2026"))
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 0, 0, (8 * resources.displayMetrics.density).toInt())
                }
                layoutParams = lp
            }

            val inner = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
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

    private fun showAboutDialog() {
        val message = StringBuilder().apply {
            appendLine(getString(R.string.about_version, BuildConfig.VERSION_NAME))
            appendLine()
            appendLine(getString(R.string.about_description))
            appendLine()
            appendLine(getString(R.string.about_privacy_title))
            appendLine(getString(R.string.about_privacy_desc))
            appendLine()
            appendLine(getString(R.string.about_license_text))
            appendLine(getString(R.string.about_github_url))
        }.toString()

        MaterialAlertDialogBuilder(this, R.style.Theme_FakeHunter_Dialog)
            .setTitle(getString(R.string.about_title))
            .setMessage(message)
            .setPositiveButton(getString(R.string.btn_close), null)
            .show()
    }
}
