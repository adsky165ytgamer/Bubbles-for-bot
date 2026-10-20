package com.multitask.bubble.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.multitask.bubble.R
import com.multitask.bubble.core.BubbleManager
import com.multitask.bubble.core.ShizukuWindowBackend
import com.multitask.bubble.core.WindowBackend
import kotlin.concurrent.thread

class BubbleOverlayService : Service() {

    companion object {
        const val ACTION_ADD = "com.multitask.bubble.ADD"
        const val EXTRA_PKG = "pkg"
    }

    private lateinit var wm: WindowManager
    private lateinit var backend: WindowBackend
    private lateinit var manager: BubbleManager
    private val ui = Handler(Looper.getMainLooper())

    private val bubbleViews = mutableMapOf<String, View>()
    private var closeZone: View? = null
    private var recentsChip: View? = null
    private val density by lazy { resources.displayMetrics.density }
    private fun dp(v: Int) = (v * density).toInt()

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        backend = ShizukuWindowBackend(this)
        manager = BubbleManager(this, backend) { ui.post { render() } }
        startAsForeground()
        backend.connect {
            backend.setupFreeform()
            ui.post { render() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_ADD) {
            val pkg = intent.getStringExtra(EXTRA_PKG) ?: return START_STICKY
            // shell commands block, so keep them off the main thread
            thread { manager.add(pkg) }
        }
        return START_STICKY
    }

    private fun startAsForeground() {
        val id = "bubble_service"
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(id, "Bubbles", NotificationManager.IMPORTANCE_LOW)
        )
        val n = NotificationCompat.Builder(this, id)
            .setContentTitle("Bubble Manager running")
            .setSmallIcon(R.mipmap.ic_launcher)
            .build()
        startForeground(101, n)
    }

    private fun overlayParams(w: Int, h: Int, x: Int, y: Int) = WindowManager.LayoutParams(
        w, h,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START; this.x = x; this.y = y }

    /** Rebuilds bubble icons from manager state. Simple and fine for max 3 bubbles. */
    private fun render() {
        bubbleViews.values.forEach { runCatching { wm.removeView(it) } }
        bubbleViews.clear()
        recentsChip?.let { runCatching { wm.removeView(it) } }; recentsChip = null

        val screenW = resources.displayMetrics.widthPixels
        manager.bubbles.forEachIndexed { i, b -> addBubbleView(b, screenW - dp(56), dp(120) + i * dp(60)) }
        if (manager.recents.isNotEmpty()) addRecentsChip(screenW - dp(56), dp(120) + manager.bubbles.size * dp(60))
    }

    private fun addBubbleView(b: BubbleManager.Bubble, x: Int, y: Int) {
        val size = dp(48)
        val iv = ImageView(this).apply {
            setImageDrawable(runCatching { packageManager.getApplicationIcon(b.pkg) }.getOrNull())
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
                setStroke(dp(if (b.expanded) 3 else 1), if (b.expanded) Color.CYAN else Color.GRAY)
            }
            setPadding(dp(6), dp(6), dp(6), dp(6))
            elevation = dp(6).toFloat()
        }
        val params = overlayParams(size, size, x, y)

        var startX = 0; var startY = 0; var touchX = 0f; var touchY = 0f; var moved = false
        iv.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x; startY = params.y; touchX = e.rawX; touchY = e.rawY; moved = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - touchX; val dy = e.rawY - touchY
                    if (Math.abs(dx) > dp(6) || Math.abs(dy) > dp(6)) {
                        if (!moved) { moved = true; showCloseZone() }
                        params.x = startX + dx.toInt(); params.y = startY + dy.toInt()
                        wm.updateViewLayout(iv, params)
                        highlightCloseZone(isOverClose(e.rawX, e.rawY))
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        thread { manager.toggle(b.pkg) }
                    } else {
                        val overClose = isOverClose(e.rawX, e.rawY)
                        hideCloseZone()
                        if (overClose) thread { manager.dismiss(b.pkg) } else snapToEdge(iv, params)
                    }
                    true
                }
                else -> false
            }
        }
        wm.addView(iv, params)
        bubbleViews[b.pkg] = iv
    }

    private fun snapToEdge(v: View, p: WindowManager.LayoutParams) {
        val w = resources.displayMetrics.widthPixels
        p.x = if (p.x + v.width / 2 < w / 2) dp(4) else w - v.width - dp(4)
        wm.updateViewLayout(v, p)
    }

    private fun addRecentsChip(x: Int, y: Int) {
        val tv = TextView(this).apply {
            text = "+"; textSize = 20f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.DKGRAY) }
        }
        tv.setOnClickListener {
            // v1: reopen the most recent dismissed app. TODO: show a small popup list of manager.recents
            manager.recents.firstOrNull()?.let { pkg -> thread { manager.add(pkg) } }
        }
        wm.addView(tv, overlayParams(dp(48), dp(48), x, y))
        recentsChip = tv
    }

    private fun showCloseZone() {
        if (closeZone != null) return
        val tv = TextView(this).apply {
            text = "X"; textSize = 22f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xAA000000.toInt()) }
        }
        val s = resources.displayMetrics
        wm.addView(tv, overlayParams(dp(64), dp(64), s.widthPixels / 2 - dp(32), s.heightPixels - dp(160)))
        closeZone = tv
    }

    private fun highlightCloseZone(on: Boolean) {
        (closeZone?.background as? GradientDrawable)?.setColor(if (on) 0xFFD32F2F.toInt() else 0xAA000000.toInt())
    }

    private fun isOverClose(rawX: Float, rawY: Float): Boolean {
        val s = resources.displayMetrics
        val cx = s.widthPixels / 2f; val cy = s.heightPixels - dp(160) + dp(32).toFloat()
        return Math.hypot((rawX - cx).toDouble(), (rawY - cy).toDouble()) < dp(60)
    }

    private fun hideCloseZone() {
        closeZone?.let { runCatching { wm.removeView(it) } }; closeZone = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        bubbleViews.values.forEach { runCatching { wm.removeView(it) } }
        hideCloseZone()
        recentsChip?.let { runCatching { wm.removeView(it) } }
        super.onDestroy()
    }
}
