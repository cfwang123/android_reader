package com.whj.reader

import android.net.Uri
import android.os.Bundle
import android.widget.SeekBar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.whj.reader.data.AppSettings
import com.whj.reader.data.DataBackup
import com.whj.reader.data.LocaleHelper
import com.whj.reader.databinding.ActivitySettingsBinding
import com.whj.reader.model.AppLanguage
import com.whj.reader.model.EdgeSwipeAction
import com.whj.reader.model.KeepScreenMode
import com.whj.reader.ui.AppTheme
import com.whj.reader.ui.AppThemeSkin
import com.whj.reader.util.AppUpdate
import com.whj.reader.util.Toasts
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private var pendingInstallAfterPermission: java.io.File? = null
    private var downloadCancel: AtomicBoolean? = null
    private var initialThemeKey: String = "green"

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) confirmAndImport(uri)
    }

    /** 未知来源安装权限返回后继续安装已下载 APK */
    private val installPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        val file = pendingInstallAfterPermission
        pendingInstallAfterPermission = null
        if (file != null && file.isFile && AppUpdate.canInstallPackages(this)) {
            launchInstall(file)
        } else if (file != null) {
            Toasts.show(this, R.string.update_cancelled)
        }
    }

    /**
     * Seek 0..24 → 分钟映射：
     * 0=不退出，1=5，2=10，3=15，4=20，5=25，6=30，… 每格 +5，最大 120 分钟(24)
     */
    private fun progressToMinutes(p: Int): Int =
        if (p <= 0) 0 else (p * 5).coerceAtMost(120)

    private fun minutesToProgress(m: Int): Int =
        when {
            m <= 0 -> 0
            else -> ((m + 4) / 5).coerceIn(1, 24)
        }

    private fun formatIdleScreenOff(minutes: Int): String =
        if (minutes <= 0) getString(R.string.settings_idle_screen_off_never)
        else getString(R.string.settings_idle_screen_off_value, minutes)

    private fun keepScreenLabel(mode: KeepScreenMode): String =
        when (mode) {
            KeepScreenMode.OFF -> getString(R.string.settings_keep_screen_off)
            KeepScreenMode.ALWAYS -> getString(R.string.settings_keep_screen_always)
            KeepScreenMode.TTS_ONLY -> getString(R.string.settings_keep_screen_tts)
        }

    private fun edgeLabel(action: EdgeSwipeAction): String =
        when (action) {
            EdgeSwipeAction.RATE -> getString(R.string.edge_action_rate)
            EdgeSwipeAction.FONT -> getString(R.string.edge_action_font)
            EdgeSwipeAction.NONE -> getString(R.string.edge_action_none)
        }

    private fun languageLabel(lang: AppLanguage): String =
        when (lang) {
            AppLanguage.ZH -> getString(R.string.lang_zh)
            AppLanguage.EN -> getString(R.string.lang_en)
        }

    private fun refreshEdgeLabels() {
        binding.tvLeftEdge.text = edgeLabel(AppSettings.leftEdgeAction(this))
        binding.tvRightEdge.text = edgeLabel(AppSettings.rightEdgeAction(this))
    }

    private fun refreshLanguageLabel() {
        binding.tvLanguage.text = languageLabel(AppSettings.appLanguage(this))
    }

    private fun themeLabel(skin: AppThemeSkin): String = getString(skin.labelRes)

    private fun refreshThemeLabel() {
        val skin = AppThemeSkin.fromKey(AppSettings.uiThemeKey(this))
        binding.tvUiTheme.text = themeLabel(skin)
    }

    private fun pickUiTheme() {
        val skins = AppThemeSkin.entries.toTypedArray()
        val options = skins.map { themeLabel(it) }.toTypedArray()
        val current = AppSettings.uiThemeKey(this)
        val checked = skins.indexOfFirst { it.key == current }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_ui_theme)
            .setSingleChoiceItems(options, checked) { dialog, which ->
                val skin = skins[which]
                dialog.dismiss()
                if (skin.key == current) return@setSingleChoiceItems
                AppSettings.setUiThemeKey(this, skin.key)
                refreshThemeLabel()
                Toasts.show(this, R.string.ui_theme_switched)
                // 立即重建本页；返回书架时 MainActivity 也会检测并 recreate
                recreate()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun pickEdgeAction(
        title: String,
        current: EdgeSwipeAction,
        onPicked: (EdgeSwipeAction) -> Unit,
    ) {
        val options = arrayOf(
            getString(R.string.edge_action_rate),
            getString(R.string.edge_action_font),
            getString(R.string.edge_action_none),
        )
        val values = arrayOf(
            EdgeSwipeAction.RATE,
            EdgeSwipeAction.FONT,
            EdgeSwipeAction.NONE,
        )
        val checked = values.indexOf(current).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(title)
            .setSingleChoiceItems(options, checked) { dialog, which ->
                onPicked(values[which])
                refreshEdgeLabels()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun pickLanguage() {
        val values = arrayOf(AppLanguage.ZH, AppLanguage.EN)
        val options = arrayOf(getString(R.string.lang_zh), getString(R.string.lang_en))
        val current = AppSettings.appLanguage(this)
        val checked = values.indexOf(current).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_language)
            .setSingleChoiceItems(options, checked) { dialog, which ->
                val next = values[which]
                dialog.dismiss()
                if (next == current) return@setSingleChoiceItems
                AppSettings.setAppLanguage(this, next)
                LocaleHelper.apply(next)
                Toasts.show(this, R.string.lang_switched)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppTheme.apply(this)
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        initialThemeKey = AppSettings.uiThemeKey(this)
        refreshThemeLabel()
        binding.rowUiTheme.setOnClickListener { pickUiTheme() }

        refreshLanguageLabel()
        binding.rowLanguage.setOnClickListener { pickLanguage() }

        fun refreshKeepScreenLabel() {
            binding.tvKeepScreen.text = keepScreenLabel(AppSettings.keepScreenMode(this))
        }
        refreshKeepScreenLabel()
        binding.rowKeepScreen.setOnClickListener {
            val values = arrayOf(
                KeepScreenMode.OFF,
                KeepScreenMode.ALWAYS,
                KeepScreenMode.TTS_ONLY,
            )
            val options = arrayOf(
                getString(R.string.settings_keep_screen_off),
                getString(R.string.settings_keep_screen_always),
                getString(R.string.settings_keep_screen_tts),
            )
            val current = AppSettings.keepScreenMode(this)
            val checked = values.indexOf(current).coerceAtLeast(0)
            AlertDialog.Builder(this)
                .setTitle(R.string.settings_keep_screen_on)
                .setSingleChoiceItems(options, checked) { dialog, which ->
                    AppSettings.setKeepScreenMode(this, values[which])
                    refreshKeepScreenLabel()
                    dialog.dismiss()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        binding.switchAutoScroll.isChecked = AppSettings.autoScroll(this)
        binding.switchAutoScroll.setOnCheckedChangeListener { _, checked ->
            AppSettings.setAutoScroll(this, checked)
        }

        val idleScreenMin = AppSettings.idleScreenOffMinutes(this)
        binding.seekIdleScreenOff.progress = minutesToProgress(idleScreenMin)
        binding.tvIdleScreenOff.text = formatIdleScreenOff(idleScreenMin)
        binding.seekIdleScreenOff.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val m = progressToMinutes(progress)
                binding.tvIdleScreenOff.text = formatIdleScreenOff(m)
                if (fromUser) AppSettings.setIdleScreenOffMinutes(this@SettingsActivity, m)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        val rate = AppSettings.ttsRate(this)
        binding.seekDefaultRate.progress = ((rate - 0.5f) / 0.1f).toInt().coerceIn(0, 20)
        binding.tvDefaultRate.text = String.format("%.1fx", rate)
        binding.seekDefaultRate.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val r = 0.5f + progress * 0.1f
                binding.tvDefaultRate.text = String.format("%.1fx", r)
                if (fromUser) AppSettings.setTtsRate(this@SettingsActivity, r)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.switchVolumeKeyPage.isChecked = AppSettings.volumeKeyPageTurn(this)
        binding.switchVolumeKeyPage.setOnCheckedChangeListener { _, checked ->
            AppSettings.setVolumeKeyPageTurn(this, checked)
        }
        binding.rowVolumeKeyPage.setOnClickListener {
            binding.switchVolumeKeyPage.isChecked = !binding.switchVolumeKeyPage.isChecked
        }

        refreshEdgeLabels()
        binding.rowLeftEdge.setOnClickListener {
            pickEdgeAction(
                getString(R.string.settings_left_edge),
                AppSettings.leftEdgeAction(this),
            ) { AppSettings.setLeftEdgeAction(this, it) }
        }
        binding.rowRightEdge.setOnClickListener {
            pickEdgeAction(
                getString(R.string.settings_right_edge),
                AppSettings.rightEdgeAction(this),
            ) { AppSettings.setRightEdgeAction(this, it) }
        }

        binding.btnExportData.setOnClickListener { exportData() }
        binding.btnImportData.setOnClickListener {
            importLauncher.launch(
                arrayOf(
                    "application/octet-stream",
                    "application/x-sqlite3",
                    "application/vnd.sqlite3",
                    "*/*",
                ),
            )
        }

        binding.tvAppVersion.text =
            getString(R.string.settings_app_version, AppUpdate.currentVersionName())
        binding.btnCheckUpdate.setOnClickListener { checkForUpdate() }
        binding.btnLicense.setOnClickListener { showLicenseDialog() }
    }

    private fun showLicenseDialog() {
        val text = runCatching {
            assets.open("LICENSE").bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: getString(R.string.settings_license_load_fail)
        val pad = (20 * resources.displayMetrics.density).toInt()
        val maxH = (resources.displayMetrics.heightPixels * 0.65f).toInt()
        val scroll = android.widget.ScrollView(this).apply {
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        val tv = android.widget.TextView(this).apply {
            this.text = text
            textSize = 12f
            setTextIsSelectable(true)
            setTextColor(getColor(R.color.text_primary))
            typeface = android.graphics.Typeface.MONOSPACE
        }
        scroll.addView(
            tv,
            android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        scroll.layoutParams = android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            maxH,
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_license_title)
            .setView(scroll)
            .setPositiveButton(R.string.close, null)
            .show()
    }

    private fun checkForUpdate() {
        binding.btnCheckUpdate.isEnabled = false
        Toasts.show(this, R.string.update_checking)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { AppUpdate.checkLatest() }
            binding.btnCheckUpdate.isEnabled = true
            if (isFinishing || isDestroyed) return@launch
            when (result) {
                is AppUpdate.CheckResult.UpToDate -> {
                    Toasts.show(
                        this@SettingsActivity,
                        getString(R.string.update_up_to_date, result.latest),
                    )
                }
                is AppUpdate.CheckResult.UpdateAvailable -> {
                    showUpdateDialog(result.info)
                }
                is AppUpdate.CheckResult.Error -> {
                    Toasts.show(
                        this@SettingsActivity,
                        getString(R.string.update_check_fail, result.message),
                        android.widget.Toast.LENGTH_LONG,
                    )
                }
            }
        }
    }

    private fun showUpdateDialog(info: AppUpdate.ReleaseInfo) {
        val notes = info.body.trim().ifBlank { "—" }.let { body ->
            if (body.length > 1200) body.take(1200) + "…" else body
        }
        val sizeLabel = formatBytes(info.sizeBytes)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.update_available_title, info.versionName))
            .setMessage(
                getString(
                    R.string.update_available_msg,
                    AppUpdate.currentVersionName(),
                    info.versionName,
                    sizeLabel,
                    notes,
                ),
            )
            .setPositiveButton(R.string.update_download) { _, _ ->
                downloadAndInstall(info)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun downloadAndInstall(info: AppUpdate.ReleaseInfo) {
        val cancel = AtomicBoolean(false)
        downloadCancel = cancel
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.settings_check_update)
            .setMessage(getString(R.string.update_downloading, 0))
            .setNegativeButton(R.string.cancel) { _, _ ->
                cancel.set(true)
            }
            .setCancelable(false)
            .create()
        dialog.show()

        lifecycleScope.launch {
            val destDir = AppUpdate.updateCacheDir(this@SettingsActivity)
            val result = withContext(Dispatchers.IO) {
                AppUpdate.downloadApk(
                    info = info,
                    destDir = destDir,
                    cancel = cancel,
                ) { pct ->
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed && dialog.isShowing) {
                            dialog.setMessage(getString(R.string.update_downloading, pct))
                        }
                    }
                }
            }
            if (isFinishing || isDestroyed) return@launch
            if (dialog.isShowing) dialog.dismiss()
            downloadCancel = null
            result.onSuccess { file ->
                ensureInstallPermissionThenInstall(file)
            }.onFailure { e ->
                if (e is InterruptedException || cancel.get()) {
                    Toasts.show(this@SettingsActivity, R.string.update_cancelled)
                } else {
                    Toasts.show(
                        this@SettingsActivity,
                        getString(R.string.update_download_fail, e.message ?: ""),
                        android.widget.Toast.LENGTH_LONG,
                    )
                }
            }
        }
    }

    private fun ensureInstallPermissionThenInstall(apk: java.io.File) {
        if (AppUpdate.canInstallPackages(this)) {
            launchInstall(apk)
            return
        }
        pendingInstallAfterPermission = apk
        AlertDialog.Builder(this)
            .setTitle(R.string.update_install_permission_title)
            .setMessage(R.string.update_install_permission_msg)
            .setPositiveButton(R.string.update_go_settings) { _, _ ->
                installPermissionLauncher.launch(AppUpdate.installPermissionSettingsIntent(this))
            }
            .setNegativeButton(R.string.cancel) { _, _ ->
                pendingInstallAfterPermission = null
            }
            .show()
    }

    private fun launchInstall(apk: java.io.File) {
        try {
            Toasts.show(this, R.string.update_installing)
            AppUpdate.installApk(this, apk)
        } catch (e: Exception) {
            Toasts.show(
                this,
                getString(R.string.update_download_fail, e.message ?: ""),
                android.widget.Toast.LENGTH_LONG,
            )
        }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "—"
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1) String.format("%.1f MB", mb) else "${bytes / 1024} KB"
    }

    private fun exportData() {
        Toasts.show(this, R.string.backup_exporting)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { DataBackup.exportToFile(this@SettingsActivity) }
            }
            result.onSuccess { r ->
                Toasts.show(
                    this@SettingsActivity,
                    getString(
                        R.string.backup_export_ok,
                        r.file.absolutePath,
                        r.folderCount,
                        r.bookCount,
                        r.progressCount,
                        r.bookmarkCount,
                    ),
                    android.widget.Toast.LENGTH_LONG,
                )
            }.onFailure { e ->
                Toasts.show(
                    this@SettingsActivity,
                    getString(R.string.backup_export_fail, e.message ?: ""),
                    android.widget.Toast.LENGTH_LONG,
                )
            }
        }
    }

    private fun confirmAndImport(uri: Uri) {
        AlertDialog.Builder(this)
            .setTitle(R.string.backup_import_confirm_title)
            .setMessage(R.string.backup_import_confirm_msg)
            .setPositiveButton(R.string.confirm) { _, _ -> doImport(uri) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun doImport(uri: Uri) {
        Toasts.show(this, R.string.backup_importing)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { DataBackup.importFromUri(this@SettingsActivity, uri) }
            }
            result.onSuccess { r ->
                Toasts.show(
                    this@SettingsActivity,
                    getString(
                        R.string.backup_import_ok,
                        r.folderCount,
                        r.bookCount,
                        r.progressCount,
                        r.bookmarkCount,
                    ),
                    android.widget.Toast.LENGTH_LONG,
                )
            }.onFailure { e ->
                Toasts.show(
                    this@SettingsActivity,
                    getString(R.string.backup_import_fail, e.message ?: ""),
                    android.widget.Toast.LENGTH_LONG,
                )
            }
        }
    }
}
