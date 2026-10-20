package com.multitask.bubble

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.multitask.bubble.core.BubbleManager
import com.multitask.bubble.core.ShizukuWindowBackend
import com.multitask.bubble.core.WindowBackend
import com.multitask.bubble.service.BubbleOverlayService
import kotlin.concurrent.thread

/**
 * MainActivity featuring Google Material 3 Expressive (M3E) design:
 * - One-Button Proof of Concept Calculator Launch Test
 * - Live ShizukuBackend Logcat / Diagnostics Console with 1-tap Copy
 * - Start / Stop Bubble Overlay Service
 * - Pick Installed App as Bubble (Max 3)
 */
class MainActivity : AppCompatActivity() {

    private lateinit var backend: WindowBackend
    private lateinit var logTextView: TextView
    private val mainHandler = Handler(Looper.getMainLooper())

    private val density by lazy { resources.displayMetrics.density }
    private fun dp(v: Int) = (v * density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        backend = ShizukuWindowBackend(this)

        setContentView(buildM3eLayout())

        // Connect to Shizuku shell service on launch
        if (backend.isReady()) {
            backend.connect {
                backend.setupFreeform()
                updateLogs()
            }
        }

        // Periodic log viewer updater
        startLogPolling()
    }

    override fun onResume() {
        super.onResume()
        updateLogs()
    }

    private fun startLogPolling() {
        mainHandler.postDelayed(object : Runnable {
            override fun run() {
                updateLogs()
                mainHandler.postDelayed(this, 1500)
            }
        }, 1500)
    }

    private fun updateLogs() {
        val shizuku = backend as? ShizukuWindowBackend ?: return
        val currentLog = synchronized(shizuku.logHistory) { shizuku.logHistory.toString() }
        if (::logTextView.isInitialized) {
            logTextView.text = if (currentLog.isNotBlank()) currentLog else "Ready. Waiting for user action or Shizuku connection...\nTag: ShizukuBackend"
        }
    }

    private fun buildM3eLayout(): View {
        val root = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0F111A"))
            isFillViewport = true
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(44), dp(20), dp(32))
        }

        // Header Title & M3E Hero Badge
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val title = TextView(this).apply {
            text = "Bubble Manager"
            setTextColor(Color.WHITE)
            textSize = 28f
            typeface = Typeface.DEFAULT_BOLD
        }
        val subtitle = TextView(this).apply {
            text = "Universal Floating App Bubbles • Android 10+ (Shizuku UID 2000)"
            setTextColor(Color.parseColor("#80D8FF"))
            textSize = 13f
            setPadding(0, dp(4), 0, dp(16))
        }
        header.addView(title)
        header.addView(subtitle)
        container.addView(header)

        // Card 1: One-Button Calculator Proof of Concept Test (Step 2 in build order)
        val pocCard = buildM3Card(Color.parseColor("#171D2E"), dp(16)).apply {
            strokeColor = Color.parseColor("#00E5FF")
            strokeWidth = dp(2)
        }
        val pocContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        val pocTitle = TextView(this).apply {
            text = "Step 1: One-Button Calculator Test"
            setTextColor(Color.WHITE)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
        }
        val pocDesc = TextView(this).apply {
            text = "Directly triggers launch(\"com.google.android.calculator\", bounds) via Shizuku shell. Check live logcat below if window does not appear."
            setTextColor(Color.parseColor("#B0BEC5"))
            textSize = 12f
            setPadding(0, dp(4), 0, dp(14))
        }
        val calcButton = MaterialButton(this).apply {
            text = "LAUNCH CALCULATOR FREEFORM"
            setTextColor(Color.BLACK)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setBackgroundColor(Color.parseColor("#00E5FF"))
            cornerRadius = dp(24)
            setOnClickListener {
                ensureReadyThen {
                    runCalculatorTest()
                }
            }
        }
        pocContent.addView(pocTitle)
        pocContent.addView(pocDesc)
        pocContent.addView(calcButton)
        pocCard.addView(pocContent)
        container.addView(pocCard)

        container.addView(buildSpacer(dp(16)))

        // Card 2: Floating Overlay Service Controls
        val serviceCard = buildM3Card(Color.parseColor("#151824"), dp(16))
        val serviceContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        val serviceTitle = TextView(this).apply {
            text = "Floating Bubbles Service"
            setTextColor(Color.WHITE)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
        }
        val serviceDesc = TextView(this).apply {
            text = "Runs collapsed edge icons (max 3), tap to expand/collapse, drag to X at bottom to close (am force-stop), and '+' recents chip."
            setTextColor(Color.parseColor("#B0BEC5"))
            textSize = 12f
            setPadding(0, dp(4), 0, dp(14))
        }

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val startBtn = MaterialButton(this).apply {
            text = "Start Service"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setBackgroundColor(Color.parseColor("#7C4DFF"))
            cornerRadius = dp(20)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(8)
            }
            setOnClickListener {
                ensureReadyThen {
                    startService(Intent(this@MainActivity, BubbleOverlayService::class.java))
                    Toast.makeText(this@MainActivity, "Bubble Overlay Service Started", Toast.LENGTH_SHORT).show()
                }
            }
        }

        val addBtn = MaterialButton(this).apply {
            text = "Add Bubble"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setBackgroundColor(Color.parseColor("#263238"))
            strokeColor = ColorStateList_valueOf(Color.parseColor("#00E5FF"))
            strokeWidth = dp(1)
            cornerRadius = dp(20)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(8)
            }
            setOnClickListener {
                ensureReadyThen {
                    pickApp()
                }
            }
        }

        btnRow.addView(startBtn)
        btnRow.addView(addBtn)

        serviceContent.addView(serviceTitle)
        serviceContent.addView(serviceDesc)
        serviceContent.addView(btnRow)
        serviceCard.addView(serviceContent)
        container.addView(serviceCard)

        container.addView(buildSpacer(dp(16)))

        // Card 3: Live Logcat & Diagnostics (tag ShizukuBackend)
        val logCard = buildM3Card(Color.parseColor("#0B0D13"), dp(16)).apply {
            strokeColor = Color.parseColor("#252B3D")
            strokeWidth = dp(1)
        }
        val logContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        val logHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val logTitle = TextView(this).apply {
            text = "Live Logcat (tag: ShizukuBackend)"
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val copyLogBtn = MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            text = "Copy Logcat"
            textSize = 11f
            setTextColor(Color.parseColor("#00E5FF"))
            setOnClickListener {
                val text = logTextView.text.toString()
                val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cb.setPrimaryClip(ClipData.newPlainText("ShizukuBackend Logcat", text))
                Toast.makeText(this@MainActivity, "Logcat copied to clipboard!", Toast.LENGTH_SHORT).show()
            }
        }

        logHeader.addView(logTitle)
        logHeader.addView(copyLogBtn)
        logContent.addView(logHeader)

        logTextView = TextView(this).apply {
            text = "Initializing Shizuku backend..."
            setTextColor(Color.parseColor("#80D8FF"))
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setLineSpacing(dp(2).toFloat(), 1f)
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(8).toFloat()
                setColor(Color.parseColor("#040508"))
            }
            background = bg
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }

        logContent.addView(logTextView)
        logCard.addView(logContent)
        container.addView(logCard)

        root.addView(container)
        return root
    }

    private fun runCalculatorTest() {
        val s = resources.displayMetrics
        val w = s.widthPixels
        val h = s.heightPixels
        val bounds = Rect((w * 0.08).toInt(), (h * 0.12).toInt(), (w * 0.92).toInt(), (h * 0.72).toInt())

        Toast.makeText(this, "Testing freeform launch of calculator...", Toast.LENGTH_SHORT).show()

        thread {
            backend.connect {
                backend.setupFreeform()
                // Test Google calculator or AOSP calculator or first math app
                val pm = packageManager
                val calcApp = pm.getInstalledApplications(0).find {
                    it.packageName.contains("calculator", ignoreCase = true)
                }?.packageName ?: "com.google.android.calculator"

                val taskId = backend.launch(calcApp, bounds)
                mainHandler.post {
                    updateLogs()
                    if (taskId > 0) {
                        Toast.makeText(this@MainActivity, "Calculator launched! TaskId: $taskId", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this@MainActivity, "Launch command sent. If no window appeared, check Logcat below.", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun ensureReadyThen(action: () -> Unit) {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            Toast.makeText(this, "Grant overlay permission, then try again", Toast.LENGTH_LONG).show()
            return
        }
        backend.requestPermission { granted ->
            if (granted) {
                backend.connect {
                    backend.setupFreeform()
                    mainHandler.post { action() }
                }
            } else {
                Toast.makeText(this, "Start Shizuku and allow this app", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun pickApp() {
        val pm = packageManager
        val apps = pm.getInstalledApplications(0)
            .filter { pm.getLaunchIntentForPackage(it.packageName) != null && it.packageName != packageName }
            .sortedBy { pm.getApplicationLabel(it).toString().lowercase() }
        val names = apps.map { pm.getApplicationLabel(it).toString() }.toTypedArray()

        MaterialAlertDialogBuilder(this)
            .setTitle("Pick an app (Max ${BubbleManager.MAX_BUBBLES})")
            .setItems(names) { _, i ->
                val intent = Intent(this, BubbleOverlayService::class.java).apply {
                    action = BubbleOverlayService.ACTION_ADD
                    putExtra(BubbleOverlayService.EXTRA_PKG, apps[i].packageName)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
                Toast.makeText(this, "Added ${names[i]} to bubbles", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun buildM3Card(bgColor: Int, cornerRadiusDp: Int): MaterialCardView {
        return MaterialCardView(this).apply {
            setCardBackgroundColor(bgColor)
            radius = dp(cornerRadiusDp).toFloat()
            cardElevation = dp(4).toFloat()
        }
    }

    private fun buildSpacer(heightPx: Int): View {
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, heightPx)
        }
    }

    private fun ColorStateList_valueOf(color: Int): android.content.res.ColorStateList {
        return android.content.res.ColorStateList.valueOf(color)
    }
}
