package com.multitask.bubble.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.res.ColorStateList
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Point
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.material.color.MaterialColors
import com.multitask.bubble.MainActivity
import com.multitask.bubble.R
import com.multitask.bubble.core.BubbleManager
import com.multitask.bubble.core.DiagLog
import com.multitask.bubble.core.ShizukuWindowBackend
import java.util.concurrent.Executors
import kotlin.math.hypot

class BubbleOverlayService : Service() {

    companion object {
        const val ACTION_ADD = "com.multitask.bubble.ADD"
        const val ACTION_STOP = "com.multitask.bubble.STOP"
        const val EXTRA_PKG = "pkg"

        private const val TAG = "BubbleService"
        private const val CHANNEL_ID = "bubble_service"
        private const val NOTIFICATION_ID = 101
        private const val BUBBLE_DP = 56
        private const val CLOSE_ZONE_DP = 80
        private const val CLOSE_ZONE_BOTTOM_DP = 96
        private const val CLOSE_HIT_RADIUS_DP = 56
        private const val FIRST_SLOT_TOP_DP = 160
        private const val SLOT_STEP_DP = 64

        @Volatile
        var running: Boolean = false
            private set

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, BubbleOverlayService::class.java))
        }

        fun add(context: Context, pkg: String) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, BubbleOverlayService::class.java)
                    .setAction(ACTION_ADD)
                    .putExtra(EXTRA_PKG, pkg)
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BubbleOverlayService::class.java))
        }
    }

    private class BubbleHandle(
        val root: View,
        val icon: ImageView,
        val params: WindowManager.LayoutParams
    )

    private lateinit var themed: Context
    private lateinit var wm: WindowManager
    private lateinit var backend: ShizukuWindowBackend
    private lateinit var manager: BubbleManager

    private val ui = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val handles = mutableMapOf<String, BubbleHandle>()
    private val positions = mutableMapOf<String, Point>()
    private val pendingAdds = mutableListOf<String>()
    private var ready = false

    private var chip: BubbleHandle? = null
    private var closeZone: View? = null
    private var recentsPanel: View? = null

    private val density by lazy { resources.displayMetrics.density }
    private fun dp(value: Int) = (value * density).toInt()

    override fun onCreate() {
        super.onCreate()
        running = true
        themed = ContextThemeWrapper(this, R.style.Theme_BubbleManager)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        backend = ShizukuWindowBackend(applicationContext)
        manager = BubbleManager(applicationContext, backend) { ui.post { render() } }

        startAsForeground()
        render() // bubbles restored from the last session show up right away

        backend.connect {
            worker.execute {
                backend.setupFreeform()
                ui.post {
                    ready = true
                    pendingAdds.forEach { pkg -> worker.execute { manager.add(pkg) } }
                    pendingAdds.clear()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSelf()
            ACTION_ADD -> intent.getStringExtra(EXTRA_PKG)?.let { pkg ->
                if (ready) worker.execute { manager.add(pkg) } else pendingAdds.add(pkg)
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        hideRecents()
        hideCloseZone()
        handles.keys.toList().forEach { removeHandle(it) }
        chip?.let { runCatching { wm.removeView(it.root) } }
        chip = null
        worker.shutdown()
        backend.disconnect()
        super.onDestroy()
    }

    // ---- foreground notification

    private fun startAsForeground() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, BubbleOverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setSmallIcon(R.drawable.ic_bubble)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(R.drawable.ic_stop, getString(R.string.notif_stop), stop)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    // ---- rendering (main thread only)

    private fun overlayParams(width: Int, height: Int, x: Int, y: Int) = WindowManager.LayoutParams(
        width, height,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        this.x = x
        this.y = y
    }

    /** Updates views in place so a dragged bubble keeps its position when state changes. */
    private fun render() {
        val current = manager.snapshot()
        val live = current.map { it.pkg }.toSet()
        handles.keys.filter { it !in live }.forEach { removeHandle(it) }

        current.forEachIndexed { index, bubble ->
            val handle = handles[bubble.pkg]
                ?: createBubble(bubble.pkg, index)?.also { handles[bubble.pkg] = it }
                ?: return@forEachIndexed
            handle.icon.setBackgroundResource(
                if (bubble.expanded) R.drawable.bg_bubble_active else R.drawable.bg_bubble
            )
        }
        updateChip(manager.recentsSnapshot().isNotEmpty(), current.size)
    }

    private fun createBubble(pkg: String, index: Int): BubbleHandle? {
        val root = LayoutInflater.from(themed).inflate(R.layout.view_bubble, null)
        val icon = root.findViewById<ImageView>(R.id.bubble_icon)
        icon.setImageDrawable(runCatching { packageManager.getApplicationIcon(pkg) }.getOrNull())
        root.contentDescription = getString(R.string.cd_bubble, labelOf(pkg))

        val size = dp(BUBBLE_DP)
        val screen = resources.displayMetrics
        val position = positions.getOrPut(pkg) {
            Point(screen.widthPixels - size, dp(FIRST_SLOT_TOP_DP) + index * dp(SLOT_STEP_DP))
        }
        val params = overlayParams(size, size, position.x, position.y)
        attachDrag(root, params, pkg)
        return try {
            wm.addView(root, params)
            BubbleHandle(root, icon, params)
        } catch (e: Exception) {
            DiagLog.log(TAG, "Could not show bubble for $pkg: ${e.message}")
            null
        }
    }

    private fun removeHandle(pkg: String) {
        handles.remove(pkg)?.let { runCatching { wm.removeView(it.root) } }
        positions.remove(pkg)
    }

    private fun labelOf(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    // ---- drag, tap and drop-to-close

    private fun attachDrag(view: View, params: WindowManager.LayoutParams, pkg: String) {
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var startX = 0
        var startY = 0
        var downX = 0f
        var downY = 0f
        var dragging = false

        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    downX = event.rawX
                    downY = event.rawY
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!dragging && hypot(dx, dy) > slop) {
                        dragging = true
                        showCloseZone()
                    }
                    if (dragging) {
                        params.x = startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        wm.updateViewLayout(view, params)
                        setCloseHot(isOverClose(event.rawX, event.rawY))
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging) {
                        worker.execute { manager.toggle(pkg) }
                    } else {
                        val dropped = isOverClose(event.rawX, event.rawY)
                        hideCloseZone()
                        if (dropped) {
                            worker.execute { manager.dismiss(pkg) }
                        } else {
                            snapToEdge(view, params)
                            positions[pkg] = Point(params.x, params.y)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    hideCloseZone()
                    true
                }
                else -> false
            }
        }
    }

    private fun snapToEdge(view: View, params: WindowManager.LayoutParams) {
        val width = resources.displayMetrics.widthPixels
        params.x = if (params.x + params.width / 2 < width / 2) 0 else width - params.width
        wm.updateViewLayout(view, params)
    }

    private fun closeZoneTop(): Int =
        resources.displayMetrics.heightPixels - dp(CLOSE_ZONE_DP) - dp(CLOSE_ZONE_BOTTOM_DP)

    private fun isOverClose(rawX: Float, rawY: Float): Boolean {
        val centerX = resources.displayMetrics.widthPixels / 2f
        val centerY = closeZoneTop() + dp(CLOSE_ZONE_DP) / 2f
        return hypot(rawX - centerX, rawY - centerY) < dp(CLOSE_HIT_RADIUS_DP)
    }

    private fun showCloseZone() {
        if (closeZone != null) return
        val view = LayoutInflater.from(themed).inflate(R.layout.view_close_zone, null)
        val size = dp(CLOSE_ZONE_DP)
        val x = (resources.displayMetrics.widthPixels - size) / 2
        runCatching { wm.addView(view, overlayParams(size, size, x, closeZoneTop())) }
            .onSuccess { closeZone = view }
    }

    private fun setCloseHot(hot: Boolean) {
        val zone = closeZone ?: return
        zone.findViewById<View>(R.id.close_bg).setBackgroundResource(
            if (hot) R.drawable.bg_close_zone_hot else R.drawable.bg_close_zone
        )
        val iconAttr = if (hot) {
            com.google.android.material.R.attr.colorOnError
        } else {
            com.google.android.material.R.attr.colorOnErrorContainer
        }
        zone.findViewById<ImageView>(R.id.close_icon).imageTintList =
            ColorStateList.valueOf(MaterialColors.getColor(themed, iconAttr, 0))
        val scale = if (hot) 1.2f else 1f
        zone.animate().scaleX(scale).scaleY(scale).setDuration(100).start()
    }

    private fun hideCloseZone() {
        closeZone?.let { runCatching { wm.removeView(it) } }
        closeZone = null
    }

    // ---- recents chip and panel

    private fun updateChip(visible: Boolean, bubbleCount: Int) {
        if (!visible) {
            hideRecents()
            chip?.let { runCatching { wm.removeView(it.root) } }
            chip = null
            return
        }
        val size = dp(BUBBLE_DP)
        val x = resources.displayMetrics.widthPixels - size
        val y = dp(FIRST_SLOT_TOP_DP) + bubbleCount * dp(SLOT_STEP_DP)

        chip?.let {
            it.params.x = x
            it.params.y = y
            wm.updateViewLayout(it.root, it.params)
            return
        }
        val root = LayoutInflater.from(themed).inflate(R.layout.view_bubble, null)
        val icon = root.findViewById<ImageView>(R.id.bubble_icon)
        icon.setImageResource(R.drawable.ic_add)
        icon.setBackgroundResource(R.drawable.bg_chip)
        icon.imageTintList = ColorStateList.valueOf(
            MaterialColors.getColor(themed, com.google.android.material.R.attr.colorOnSecondaryContainer, 0)
        )
        root.contentDescription = getString(R.string.cd_recents)
        root.setOnClickListener { if (recentsPanel == null) showRecents() else hideRecents() }

        val params = overlayParams(size, size, x, y)
        runCatching { wm.addView(root, params) }.onSuccess { chip = BubbleHandle(root, icon, params) }
    }

    private fun showRecents() {
        val recents = manager.recentsSnapshot()
        val anchor = chip ?: return
        if (recents.isEmpty()) return

        val inflater = LayoutInflater.from(themed)
        val panel = inflater.inflate(R.layout.view_recents_panel, null)
        val list = panel.findViewById<LinearLayout>(R.id.recents_list)
        recents.forEach { pkg ->
            val row = inflater.inflate(R.layout.item_app, list, false)
            row.findViewById<ImageView>(R.id.app_icon)
                .setImageDrawable(runCatching { packageManager.getApplicationIcon(pkg) }.getOrNull())
            row.findViewById<TextView>(R.id.app_label).text = labelOf(pkg)
            row.findViewById<TextView>(R.id.app_package).visibility = View.GONE
            row.setOnClickListener {
                hideRecents()
                worker.execute { manager.add(pkg) }
            }
            list.addView(row)
        }
        panel.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE) {
                hideRecents()
                true
            } else {
                false
            }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(BUBBLE_DP + 8)
            y = anchor.params.y
        }
        runCatching { wm.addView(panel, params) }.onSuccess { recentsPanel = panel }
    }

    private fun hideRecents() {
        recentsPanel?.let { runCatching { wm.removeView(it) } }
        recentsPanel = null
    }
}
