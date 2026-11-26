package com.gokcank.fakehunter

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
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
import com.gokcank.fakehunter.databinding.ActivityMainBinding
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class MainActivity : AppCompatActivity(), AuditCoordinator.Listener {

    private lateinit var binding: ActivityMainBinding
    private var currentReport: AuditReport? = null
    private var activeDialog: AlertDialog? = null

    override fun attachBaseContext(newBase: Context) {
        val lang = SettingsManager.getLanguage(newBase)
        super.attachBaseContext(LocaleHelper.applyLocale(newBase, lang))
    }

    private val saveLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) {
            AuditCoordinator.exportZipToSafUri(this, uri) { success ->
                val msg = if (success) {
                    getString(R.string.report_saved_success)
                } else {
                    getString(R.string.report_save_failed)
                }
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyEdgeToEdgeInsets()
        applyResponsiveCardGrid()

        binding.btnInspect.setOnClickListener {
            AuditCoordinator.startFullAudit(this, SettingsManager.getAuditOptions(this))
        }

        binding.btnSave.setOnClickListener {
            triggerSaveZip()
        }

        binding.btnQr.setOnClickListener {
            showQrDialog()
        }

        binding.btnDeepRootAudit.setOnClickListener {
            AuditCoordinator.startRootOnlyAudit(this)
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

        AuditCoordinator.ensureInitialAudit(this, SettingsManager.getAuditOptions(this))
    }

    override fun onStart() {
        super.onStart()
        AuditCoordinator.registerListener(this)
    }

    override fun onStop() {
        AuditCoordinator.unregisterListener(this)
        super.onStop()
    }

    override fun onDestroy() {
        activeDialog?.dismiss()
        activeDialog = null
        super.onDestroy()
    }

    override fun onStateChanged(state: AuditCoordinator.State) {
        currentReport = state.report
        val busy = state.isInspecting || state.isExportingZip

        binding.btnInspect.isEnabled = !busy
        binding.btnInspect.text = if (state.isInspecting) "..." else getString(R.string.btn_reinspect)

        binding.btnSave.isEnabled = state.report != null && !busy
        binding.btnSave.text = if (state.isExportingZip) {
            getString(R.string.btn_saving_report)
        } else {
            getString(R.string.btn_save_report)
        }

        binding.btnQr.isEnabled = state.report != null && !state.isInspecting
        binding.btnCopy.isEnabled = state.report != null && !state.isInspecting
        binding.btnDeepRootAudit.isEnabled = !busy

        if (busy) {
            binding.llProgress.visibility = View.VISIBLE
            if (state.isExportingZip) {
                binding.pbAudit.isIndeterminate = true
                binding.tvProgress.text = getString(R.string.btn_saving_report)
            } else {
                binding.pbAudit.isIndeterminate = false
                binding.pbAudit.progress = state.progressPercent
                binding.tvProgress.text = phaseLabel(state.phase, state.progressDetail)
            }
        } else {
            binding.llProgress.visibility = View.GONE
        }

        if (state.report != null) {
            binding.llResultsContainer.visibility = View.VISIBLE
            displayReport(state.report)
        } else {
            binding.llResultsContainer.visibility = View.GONE
        }
    }

    private fun applyEdgeToEdgeInsets() {
        val basePad = (16 * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(binding.llRootContent) { view, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(
                basePad + insets.left,
                basePad + insets.top,
                basePad + insets.right,
                basePad + insets.bottom
            )
            WindowInsetsCompat.CONSUMED
        }
    }

    private fun applyResponsiveCardGrid() {
        val isWide = resources.configuration.screenWidthDp >= 600
        val gap = (8 * resources.displayMetrics.density).toInt()
        val vGap = (12 * resources.displayMetrics.density).toInt()

        val rows = listOf(
            Triple(binding.llGridRow1, binding.cardSystem, binding.cardMemory),
            Triple(binding.llGridRow2, binding.cardCpu, binding.cardStorage),
            Triple(binding.llGridRow3, binding.cardRoot, binding.cardStorageTest)
        )

        rows.forEach { (row, first, second) ->
            row.orientation = if (isWide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            first.layoutParams = if (isWide) {
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = gap
                }
            } else {
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = vGap
                }
            }
            second.layoutParams = if (isWide) {
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = gap
                }
            } else {
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            }
        }
    }

    private fun triggerSaveZip() {
        val report = currentReport ?: return
        if (AuditCoordinator.state.isExportingZip) return
        Toast.makeText(this, getString(R.string.report_privacy_notice), Toast.LENGTH_LONG).show()
        val fileName = EvidenceCollector.generateZipFileName(report)
        try {
            saveLauncher.launch(fileName)
        } catch (_: ActivityNotFoundException) {
            AuditCoordinator.exportZipFallback(this) { savedFile ->
                val msg = if (savedFile != null) {
                    getString(R.string.report_saved_fallback, savedFile.absolutePath)
                } else {
                    getString(R.string.report_save_failed)
                }
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showManagedDialog(dialog: AlertDialog) {
        activeDialog?.dismiss()
        activeDialog = dialog
        dialog.show()
    }

    private fun showQrDialog() {
        val report = currentReport ?: return
        val density = resources.displayMetrics.density
        val maxBoxPx = (minOf(230f, (resources.configuration.screenHeightDp * 0.48f).coerceAtLeast(150f)) * density).toInt()

        val statusView = TextView(this).apply {
            text = getString(R.string.qr_generating)
            setTextColor(Color.parseColor("#00E5FF"))
            textSize = 13f
            gravity = Gravity.CENTER
        }

        val imageView = ImageView(this).apply {
            setBackgroundColor(Color.WHITE)
            contentDescription = getString(R.string.cd_qr_code)
            val p = (10 * density).toInt()
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(maxBoxPx, maxBoxPx).apply {
                gravity = Gravity.CENTER
            }
            visibility = View.GONE
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val p = (16 * density).toInt()
            setPadding(p, p, p, p)
            addView(TextView(this@MainActivity).apply {
                text = getString(R.string.qr_dialog_desc)
                setTextColor(Color.parseColor("#90A4AE"))
                textSize = 12f
                setPadding(0, 0, 0, (12 * density).toInt())
            })
            addView(statusView)
            addView(imageView)
        }

        val scrollView = ScrollView(this).apply {
            addView(container)
        }

        val dialog = MaterialAlertDialogBuilder(this, R.style.Theme_FakeHunter_Dialog)
            .setTitle(getString(R.string.qr_dialog_title))
            .setView(scrollView)
            .setPositiveButton(getString(R.string.btn_close), null)
            .create()

        showManagedDialog(dialog)

        Thread({
            val qrText = QrHelper.generateAuditSummary(report)
            val qrBitmap = QrHelper.generateQrBitmap(qrText, 512)
            runOnUiThread {
                if (!isFinishing && !isDestroyed && dialog.isShowing) {
                    if (qrBitmap != null) {
                        statusView.visibility = View.GONE
                        imageView.setImageBitmap(qrBitmap)
                        imageView.visibility = View.VISIBLE
                    } else {
                        statusView.setTextColor(Color.parseColor("#FF1744"))
                        statusView.text = getString(R.string.qr_error_failed)
                    }
                }
            }
        }, "FakeHunter-ClassicQr").apply {
            isDaemon = true
            start()
        }
    }

    private fun showSettingsDialog() {
        val currentLang = SettingsManager.getLanguage(this)
        val autoRoot = SettingsManager.isAutoRoot(this)
        var storageMb = SettingsManager.getStorageTestMb(this)

        val accentColor = Color.parseColor("#00E5FF")
        val cyanCsl = ColorStateList.valueOf(accentColor)
        val cardStrokeColor = Color.parseColor("#2D333D")
        val density = resources.displayMetrics.density

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (16 * density).toInt()
            setPadding(p, p, p, p)
        }

        val scrollView = ScrollView(this).apply {
            addView(container)
        }

        val tvLangHeader = TextView(this).apply {
            text = getString(R.string.settings_language)
            setTextColor(accentColor)
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        container.addView(tvLangHeader)

        val rgLang = RadioGroup(this).apply {
            val pTop = (4 * density).toInt()
            setPadding(0, pTop, 0, 0)
        }
        val rbSystem = RadioButton(this).apply {
            text = getString(R.string.settings_lang_system)
            setTextColor(Color.WHITE)
            buttonTintList = cyanCsl
            isChecked = currentLang == SettingsManager.LANG_SYSTEM
        }
        val rbTr = RadioButton(this).apply {
            text = getString(R.string.settings_lang_tr)
            setTextColor(Color.WHITE)
            buttonTintList = cyanCsl
            isChecked = currentLang == SettingsManager.LANG_TR
        }
        val rbEn = RadioButton(this).apply {
            text = getString(R.string.settings_lang_en)
            setTextColor(Color.WHITE)
            buttonTintList = cyanCsl
            isChecked = currentLang == SettingsManager.LANG_EN
        }
        rgLang.addView(rbSystem)
        rgLang.addView(rbTr)
        rgLang.addView(rbEn)
        container.addView(rgLang)

        container.addView(View(this).apply {
            setBackgroundColor(cardStrokeColor)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 2).apply {
                val m = (12 * density).toInt()
                setMargins(0, m, 0, m)
            }
        })

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
            val pStart = (28 * density).toInt()
            setPadding(pStart, 0, 0, 0)
        })

        container.addView(View(this).apply {
            setBackgroundColor(cardStrokeColor)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 2).apply {
                val m = (12 * density).toInt()
                setMargins(0, m, 0, m)
            }
        })

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
                text = getString(R.string.settings_storage_mb_option, size)
                setTextColor(Color.WHITE)
                buttonTintList = cyanCsl
                isChecked = storageMb == size
                setOnClickListener { storageMb = size }
            }
            rgStorage.addView(rb)
        }
        container.addView(rgStorage)

        val dialog = MaterialAlertDialogBuilder(this, R.style.Theme_FakeHunter_Dialog)
            .setTitle(getString(R.string.settings_title))
            .setView(scrollView)
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .setPositiveButton(getString(R.string.btn_save)) { _, _ ->
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
            .create()

        showManagedDialog(dialog)
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
        val (verdictText, verdictColor) = when (report.verdict) {
            Verdict.VERIFIED -> Pair(getString(R.string.status_clean), Color.parseColor("#00E676"))
            Verdict.SUSPICIOUS -> Pair(getString(R.string.status_suspicious), Color.parseColor("#FFD600"))
            Verdict.FAKE_DETECTED -> Pair(getString(R.string.status_fake), Color.parseColor("#FF1744"))
        }
        binding.tvVerdict.text = verdictText
        binding.tvVerdict.setTextColor(verdictColor)
        binding.cardVerdict.strokeColor = verdictColor
        binding.cardVerdict.setCardBackgroundColor(
            Color.argb(38, Color.red(verdictColor), Color.green(verdictColor), Color.blue(verdictColor))
        )
        binding.tvIssuesCount.text = getString(R.string.issues_count_label, report.issues.size)

        // System
        binding.tvSystemModel.text = getString(R.string.metric_model, report.system.model, report.system.manufacturer)
        binding.tvSystemBoard.text = getString(R.string.metric_board, report.system.board)
        binding.tvSystemPlatform.text = getString(R.string.metric_platform, report.system.platform)
        binding.tvSystemAndroid.text = getString(R.string.metric_android, report.system.reportedRelease, report.system.actualSdkLevel)

        // RAM
        binding.tvRamPhysical.text = getString(R.string.metric_ram_physical, report.ram.physicalGbFormatted)
        binding.tvRamSwap.text = getString(R.string.metric_ram_swap, report.ram.swapGbFormatted)
        when {
            report.ram.isRamSpoofed -> {
                binding.tvRamWarning.text = getString(R.string.warn_ram_spoofed)
                binding.tvRamWarning.setTextColor(Color.parseColor("#FF1744"))
            }
            report.ram.isSwapInflated -> {
                binding.tvRamWarning.text = getString(R.string.warn_swap_inflated)
                binding.tvRamWarning.setTextColor(Color.parseColor("#FFD600"))
            }
            report.ram.hasVirtualRamNotice -> {
                binding.tvRamWarning.text = getString(R.string.ram_virtual_active)
                binding.tvRamWarning.setTextColor(Color.parseColor("#00E5FF"))
            }
            else -> {
                binding.tvRamWarning.text = getString(R.string.ram_swap_normal)
                binding.tvRamWarning.setTextColor(Color.parseColor("#00E676"))
            }
        }

        // CPU
        binding.tvCpuCores.text = getString(R.string.metric_cores, report.cpu.reportedCores, report.cpu.activeCores)
        val maxMhz = report.cpu.coreDetails.maxOfOrNull { maxOf(it.maxFreqMhz, it.currentFreqMhz) } ?: 0L
        if (maxMhz > 0L) {
            binding.tvCpuMaxFreq.visibility = View.VISIBLE
            binding.tvCpuMaxFreq.text = getString(R.string.metric_cpu_max_freq, maxMhz)
        } else {
            binding.tvCpuMaxFreq.visibility = View.GONE
        }
        when {
            !report.cpu.frequenciesReadable -> {
                binding.tvCpuWarning.text = getString(R.string.cpu_freq_unreadable)
                binding.tvCpuWarning.setTextColor(Color.parseColor("#FFD600"))
            }
            report.cpu.isFakeCoresDetected -> {
                binding.tvCpuWarning.text = getString(R.string.warn_cores_fake)
                binding.tvCpuWarning.setTextColor(Color.parseColor("#FF1744"))
            }
            else -> {
                binding.tvCpuWarning.text = getString(R.string.cpu_cores_verified)
                binding.tvCpuWarning.setTextColor(Color.parseColor("#00E676"))
            }
        }
        binding.tvCpuWake.visibility = if (report.cpu.wakeTestPerformed) View.VISIBLE else View.GONE

        // Storage
        binding.tvStorageTotal.text = getString(R.string.metric_storage_total, report.storage.totalDataGbFormatted)
        binding.tvStorageFree.text = getString(R.string.metric_storage_free, report.storage.freeDataGbFormatted)
        if (report.storage.flashChips.isNotEmpty()) {
            binding.tvStorageFlash.visibility = View.VISIBLE
            binding.tvStorageFlash.text = getString(R.string.metric_flash_physical, report.storage.physicalFlashGbFormatted)
            val chipSummary = report.storage.flashChips.joinToString(", ") { "${it.name}: ${it.sizeGbFormatted}" }
            binding.tvStorageChipDetail.visibility = View.VISIBLE
            binding.tvStorageChipDetail.text = getString(R.string.metric_chip_detail, chipSummary)
        } else {
            binding.tvStorageFlash.visibility = View.VISIBLE
            binding.tvStorageFlash.text = getString(R.string.metric_flash_unreadable)
            binding.tvStorageChipDetail.visibility = View.GONE
        }
        if (report.storage.externalChips.isNotEmpty()) {
            binding.tvStorageExternal.visibility = View.VISIBLE
            binding.tvStorageExternal.text = getString(R.string.metric_external_storage, report.storage.externalStorageGbFormatted)
        } else {
            binding.tvStorageExternal.visibility = View.GONE
        }

        // Root Security
        val (rootText, rootColor) = when {
            report.root.isRootGranted -> Pair(getString(R.string.root_status_granted), Color.parseColor("#00E676"))
            report.root.isRootBinaryFound -> Pair(getString(R.string.root_status_binary_only), Color.parseColor("#FFD600"))
            else -> Pair(getString(R.string.root_status_none), Color.parseColor("#90A4AE"))
        }
        binding.tvRootStatus.text = rootText
        binding.tvRootStatus.setTextColor(rootColor)
        binding.tvRootSelinux.text = getString(R.string.metric_selinux, report.root.selinuxMode)
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
        binding.btnDeepRootAudit.visibility =
            if (report.root.isRootBinaryFound && !report.root.isRootGranted) View.VISIBLE else View.GONE

        // Storage Test Result
        val test = report.storageTest
        when {
            test == null -> {
                binding.tvStorageTestSize.visibility = View.GONE
                binding.tvStorageTestResult.visibility = View.GONE
                binding.tvStorageTestCacheNote.visibility = View.GONE
            }
            test.hasError -> {
                binding.tvStorageTestSize.visibility = View.GONE
                binding.tvStorageTestCacheNote.visibility = View.GONE
                binding.tvStorageTestResult.visibility = View.VISIBLE
                binding.tvStorageTestResult.setTextColor(Color.parseColor("#FFD600"))
                binding.tvStorageTestResult.text = getString(R.string.storage_test_error, test.message)
            }
            test.wasSkipped -> {
                binding.tvStorageTestSize.visibility = View.GONE
                binding.tvStorageTestCacheNote.visibility = View.GONE
                binding.tvStorageTestResult.visibility = View.VISIBLE
                binding.tvStorageTestResult.setTextColor(Color.parseColor("#FFD600"))
                binding.tvStorageTestResult.text = getString(R.string.storage_test_skipped, test.message)
            }
            test.isCorrupted -> {
                binding.tvStorageTestSize.visibility = View.GONE
                binding.tvStorageTestCacheNote.visibility = View.GONE
                binding.tvStorageTestResult.visibility = View.VISIBLE
                binding.tvStorageTestResult.setTextColor(Color.parseColor("#FF1744"))
                binding.tvStorageTestResult.text = getString(R.string.storage_test_failed, test.message)
            }
            else -> {
                binding.tvStorageTestSize.visibility = View.VISIBLE
                binding.tvStorageTestSize.text = getString(R.string.storage_test_tested, test.testedMb)
                binding.tvStorageTestResult.visibility = View.VISIBLE
                binding.tvStorageTestResult.setTextColor(Color.parseColor("#00E676"))
                binding.tvStorageTestResult.text = getString(R.string.storage_test_passed, test.writeSpeedMbPerSec, test.readSpeedMbPerSec)
                binding.tvStorageTestCacheNote.visibility = if (test.cacheWarning) View.VISIBLE else View.GONE
            }
        }

        // Findings (hidden completely when no issues exist)
        binding.llFindings.removeAllViews()
        if (report.issues.isEmpty()) {
            binding.tvFindingsHeader.visibility = View.GONE
            binding.llFindings.visibility = View.GONE
        } else {
            binding.tvFindingsHeader.visibility = View.VISIBLE
            binding.llFindings.visibility = View.VISIBLE
            val density = resources.displayMetrics.density
            report.issues.forEach { issue ->
                val card = MaterialCardView(this).apply {
                    radius = 8f * density
                    setCardBackgroundColor(Color.parseColor("#1C2026"))
                    strokeColor = Color.parseColor("#2D333D")
                    strokeWidth = (1 * density).toInt()
                    cardElevation = 0f
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(0, 0, 0, (8 * density).toInt())
                    }
                }

                val inner = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    val p = (12 * density).toInt()
                    setPadding(p, p, p, p)
                }

                val issueColor = when (issue.severity) {
                    IssueSeverity.FAKE -> Color.parseColor("#FF1744")
                    IssueSeverity.WARNING -> Color.parseColor("#FFD600")
                    IssueSeverity.INFO -> Color.parseColor("#00E5FF")
                }

                val tvTitle = TextView(this).apply {
                    text = IssueFormatter.resolveTitle(this@MainActivity, issue)
                    setTextColor(issueColor)
                    textSize = 14f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                }

                val tvDesc = TextView(this).apply {
                    text = IssueFormatter.resolveDescription(this@MainActivity, issue)
                    setTextColor(Color.parseColor("#CFD8DC"))
                    textSize = 12f
                }

                inner.addView(tvTitle)
                inner.addView(tvDesc)
                card.addView(inner)
                binding.llFindings.addView(card)
            }
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
        val density = resources.displayMetrics.density
        val p = (20 * density).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(p, p, p, p)
        }

        val scrollView = ScrollView(this).apply {
            addView(container)
        }

        container.addView(TextView(this).apply {
            text = getString(R.string.about_subtitle)
            setTextColor(Color.parseColor("#90A4AE"))
            textSize = 12f
        })

        container.addView(TextView(this).apply {
            text = getString(R.string.about_version, BuildConfig.VERSION_NAME)
            setTextColor(Color.parseColor("#00E5FF"))
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, (4 * density).toInt(), 0, 0)
        })

        container.addView(TextView(this).apply {
            text = getString(R.string.about_description)
            setTextColor(Color.parseColor("#CFD8DC"))
            textSize = 13f
            val m = (8 * density).toInt()
            setPadding(0, m, 0, m)
        })

        container.addView(TextView(this).apply {
            text = getString(R.string.about_privacy_title)
            setTextColor(Color.parseColor("#00E676"))
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, (4 * density).toInt(), 0, 0)
        })

        container.addView(TextView(this).apply {
            text = getString(R.string.about_privacy_desc)
            setTextColor(Color.parseColor("#90A4AE"))
            textSize = 12f
            setPadding(0, (4 * density).toInt(), 0, (8 * density).toInt())
        })

        container.addView(TextView(this).apply {
            text = getString(R.string.about_license_text)
            setTextColor(Color.parseColor("#CFD8DC"))
            textSize = 12f
        })

        container.addView(TextView(this).apply {
            text = getString(R.string.about_github_url)
            setTextColor(Color.parseColor("#00E5FF"))
            textSize = 12f
            setPadding(0, (4 * density).toInt(), 0, 0)
        })

        val dialog = MaterialAlertDialogBuilder(this, R.style.Theme_FakeHunter_Dialog)
            .setTitle(getString(R.string.about_title))
            .setView(scrollView)
            .setPositiveButton(getString(R.string.btn_close), null)
            .create()

        showManagedDialog(dialog)
    }
}
