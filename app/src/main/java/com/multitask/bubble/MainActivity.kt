package com.multitask.bubble

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.color.MaterialColors
import com.multitask.bubble.core.BubbleManager
import com.multitask.bubble.core.DiagLog
import com.multitask.bubble.core.ShizukuWindowBackend
import com.multitask.bubble.databinding.ActivityMainBinding
import com.multitask.bubble.databinding.ViewStatusRowBinding
import com.multitask.bubble.service.BubbleOverlayService
import com.multitask.bubble.ui.AppPickerSheet
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var backend: ShizukuWindowBackend
    private val ui = Handler(Looper.getMainLooper())

    private val onBinderReceived = Shizuku.OnBinderReceivedListener { ui.post { refreshStatus() } }
    private val onBinderDead = Shizuku.OnBinderDeadListener { ui.post { refreshStatus() } }

    private var afterNotificationPrompt: (() -> Unit)? = null
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // The service still runs if the user declines; only its notification stays hidden.
            afterNotificationPrompt?.invoke()
            afterNotificationPrompt = null
        }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        backend = ShizukuWindowBackend(applicationContext)

        applyInsets()
        bindStaticText()
        bindActions()
    }

    override fun onStart() {
        super.onStart()
        Shizuku.addBinderReceivedListenerSticky(onBinderReceived)
        Shizuku.addBinderDeadListener(onBinderDead)
        DiagLog.listener = { text -> ui.post { showLog(text) } }
        showLog(DiagLog.snapshot())
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    override fun onStop() {
        DiagLog.listener = null
        Shizuku.removeBinderReceivedListener(onBinderReceived)
        Shizuku.removeBinderDeadListener(onBinderDead)
        super.onStop()
    }

    // ---- setup

    private fun applyInsets() {
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, binding.root).apply {
            isAppearanceLightStatusBars = !night
            isAppearanceLightNavigationBars = !night
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.content.updatePadding(
                left = bars.left + dp(20),
                top = bars.top + dp(24),
                right = bars.right + dp(20),
                bottom = bars.bottom + dp(24)
            )
            insets
        }
    }

    private fun bindStaticText() {
        binding.rowShizuku.title.setText(R.string.status_shizuku)
        binding.rowOverlay.title.setText(R.string.status_overlay)
        binding.rowService.title.setText(R.string.status_service)
        binding.txtHint.text = getString(R.string.hint_limit, BubbleManager.MAX_BUBBLES)
    }

    private fun bindActions() {
        binding.rowOverlay.root.setOnClickListener { openOverlaySettings() }

        binding.btnStart.setOnClickListener {
            withReady { withNotificationPermission { BubbleOverlayService.start(this); refreshSoon() } }
        }
        binding.btnAdd.setOnClickListener {
            withReady {
                AppPickerSheet(this) { pkg ->
                    BubbleOverlayService.add(this, pkg)
                    Toast.makeText(this, getString(R.string.toast_added, labelOf(pkg)), Toast.LENGTH_SHORT).show()
                    refreshSoon()
                }.show()
            }
        }
        binding.btnStop.setOnClickListener {
            BubbleOverlayService.stop(this)
            refreshSoon()
        }
        binding.btnCopy.setOnClickListener {
            val text = DiagLog.snapshot()
            if (text.isBlank()) return@setOnClickListener
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.section_diag), text))
            Toast.makeText(this, R.string.diag_copied, Toast.LENGTH_SHORT).show()
        }
        binding.btnClear.setOnClickListener { DiagLog.clear() }
    }

    // ---- status

    private fun refreshStatus() {
        val shizukuRunning = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        val shizukuReady = backend.isReady()
        setRow(
            binding.rowShizuku, shizukuReady,
            when {
                !shizukuRunning -> R.string.status_shizuku_off
                !shizukuReady -> R.string.status_shizuku_no_permission
                else -> R.string.status_shizuku_ready
            }
        )
        val overlayAllowed = Settings.canDrawOverlays(this)
        setRow(
            binding.rowOverlay, overlayAllowed,
            if (overlayAllowed) R.string.status_overlay_on else R.string.status_overlay_off
        )
        val serviceRunning = BubbleOverlayService.running
        setRow(
            binding.rowService, serviceRunning,
            if (serviceRunning) R.string.status_service_on else R.string.status_service_off
        )
    }

    private fun refreshSoon() {
        ui.postDelayed({ refreshStatus() }, 500)
    }

    private fun setRow(row: ViewStatusRowBinding, ok: Boolean, detail: Int) {
        row.icon.setImageResource(if (ok) R.drawable.ic_check_circle else R.drawable.ic_error)
        val tintAttr = if (ok) androidx.appcompat.R.attr.colorPrimary else androidx.appcompat.R.attr.colorError
        row.icon.imageTintList = ColorStateList.valueOf(MaterialColors.getColor(this, tintAttr, 0))
        row.detail.setText(detail)
    }

    private fun showLog(text: String) {
        binding.txtLog.text = text.ifBlank { getString(R.string.diag_empty) }
    }

    // ---- permissions

    private fun withReady(action: () -> Unit) {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.toast_overlay_needed, Toast.LENGTH_LONG).show()
            openOverlaySettings()
            return
        }
        if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
            Toast.makeText(this, R.string.toast_shizuku_off, Toast.LENGTH_LONG).show()
            return
        }
        backend.requestPermission { granted ->
            ui.post {
                refreshStatus()
                if (granted) action() else Toast.makeText(this, R.string.toast_shizuku_denied, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun withNotificationPermission(action: () -> Unit) {
        val needed = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        if (needed) {
            afterNotificationPrompt = action
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            action()
        }
    }

    private fun openOverlaySettings() {
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    }

    private fun labelOf(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)
}
