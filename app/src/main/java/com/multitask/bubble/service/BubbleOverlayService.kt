package com.multitask.bubble.service

import android.animation.ValueAnimator
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.EditText
import android.widget.ImageView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.multitask.bubble.MainActivity
import com.multitask.bubble.R
import com.multitask.bubble.core.BubbleManager
import com.multitask.bubble.core.DiagLog
import com.multitask.bubble.core.ShizukuWindowBackend
import com.multitask.bubble.ui.AppList
import com.multitask.bubble.ui.AppListAdapter
import com.multitask.bubble.ui.BubbleWindow
import com.multitask.bubble.ui.EmbeddedWindow
import com.multitask.bubble.ui.FreeformWindow
import java.util.concurrent.Executors
import kotlin.concurrent.thread
import kotlin.math.hypot

class BubbleOverlayService : Service() {

    companion object {
        const val ACTION_ADD = "com.multitask.bubble.ADD"
        const val ACTION_STOP = "com.multitask.bubble.STOP"
        const val EXTRA_PKG = "pkg"

        private const val TAG = "BubbleService"
        private const val FEATURE_SECONDARY_DISPLAYS = "android.software.activities_on_secondary_displays"
        private const val CHANNEL_ID = "bubble_service"
        private const val NOTIFICATION_ID = 101
        private const val CHIP_KEY = "__chip__"
        private const val POSITIONS_PREFS = "bubble_positions"
        private const val BUBBLE_DP = 56
        private const val CLOSE_ZONE_DP = 80
        private const val CLOSE_ZONE_BOTTOM_DP = 96
        private const val CLOSE_HIT_RADIUS_DP = 56
        private const val FIRST_SLOT_TOP_DP = 160
        private const val SLOT_STEP_DP = 64
        private const val DRAWER_MAX_WIDTH_DP = 360

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
    private val windows = mutableMapOf<String, BubbleWindow>()
    private val positions = mutableMapOf<String, Point>()
    private val pendingAdds = mutableListOf<String>()
    private var ready = false

    // Embedded bubbles need secondary-display support. If the ROM lacks it, or a launch on our
    // virtual display fails, every bubble falls back to the system's freeform windows.
    private var embeddedBroken = false
    private val embeddedSupported by lazy { packageManager.hasSystemFeature(FEATURE_SECONDARY_DISPLAYS) }

    private var arranging = false
    private var topWindow: String? = null
    private var chip: BubbleHandle? = null
    private var closeZone: View? = null
    private var drawer: View? = null

    private val density by lazy { resources.displayMetrics.density }
    private fun dp(value: Int) = (value * density).toInt()
    private val positionPrefs by lazy { getSharedPreferences(POSITIONS_PREFS, MODE_PRIVATE) }

    override fun onCreate() {
        super.onCreate()
        running = true
        themed = ContextThemeWrapper(this, R.style.Theme_BubbleManager)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        backend = ShizukuWindowBackend(applicationContext)
        manager = BubbleManager(applicationContext) { render() }

        startAsForeground()
        render() // bubbles restored from the last session show up right away

        backend.connect {
            worker.execute {
                backend.prepare()
                ui.post {
                    ready = true
                    DiagLog.log(
                        TAG,
                        if (embeddedSupported) "Mode: embedded bubbles"
                        else "Mode: freeform fallback (no secondary display support)"
                    )
                    pendingAdds.toList().also { pendingAdds.clear() }.forEach { openBubble(it) }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSelf()
            ACTION_ADD -> intent.getStringExtra(EXTRA_PKG)?.let { openBubble(it) }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        drawer?.let { runCatching { wm.removeView(it) } }
        drawer = null
        closeZone?.let { runCatching { wm.removeView(it) } }
        closeZone = null
        windows.values.forEach { it.release() }
        windows.clear()
        handles.values.forEach { runCatching { wm.removeView(it.root) } }
        handles.clear()
        chip?.let { runCatching { wm.removeView(it.root) } }
        chip = null
        worker.execute { backend.disconnect() } // queued after the force-stops above
        worker.shutdown()
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

    // ---- bubble actions (main thread only)

    private fun createWindow(pkg: String): BubbleWindow =
        if (embeddedSupported && !embeddedBroken) {
            EmbeddedWindow(
                context = themed, wm = wm, backend = backend, worker = worker, pkg = pkg,
                onMinimize = { minimizeBubble(pkg) },
                onClose = { closeBubble(pkg) },
                onFailed = { onEmbeddedFailed(pkg) },
                onFocus = { raiseWindow(pkg) },
                onDragEnd = { x, y -> onWindowDragEnd(pkg, x, y) }
            )
        } else {
            FreeformWindow(this, backend, worker, pkg)
        }

    private fun openBubble(pkg: String) {
        if (!ready) {
            if (pkg !in pendingAdds) pendingAdds.add(pkg)
            return
        }
        val change = manager.open(pkg)
        change.evicted.forEach { windows.remove(it)?.release() }
        change.collapsed.forEach { windows[it]?.hide() }
        windows.getOrPut(pkg) { createWindow(pkg) }.show()
        topWindow = pkg
        if (arranging) arrangeWindows()
    }

    private fun minimizeBubble(pkg: String) {
        manager.collapse(pkg)
        windows[pkg]?.hide()
        if (arranging) arrangeWindows()
    }

    private fun closeBubble(pkg: String) {
        manager.dismiss(pkg)
        windows.remove(pkg)?.release()
        if (arranging) arrangeWindows()
    }

    private fun onBubbleTap(pkg: String) {
        val open = manager.snapshot().firstOrNull { it.pkg == pkg }?.expanded == true
        if (open) minimizeBubble(pkg) else openBubble(pkg)
    }

    private fun onEmbeddedFailed(pkg: String) {
        embeddedBroken = true
        windows.remove(pkg)?.release()
        ui.postDelayed({
            if (manager.snapshot().any { it.pkg == pkg }) openBubble(pkg)
        }, 400)
    }

    private fun raiseWindow(pkg: String) {
        if (topWindow == pkg) return
        windows[pkg]?.raise()
        topWindow = pkg
    }

    // ---- arrange mode: tile the open windows neatly, in bubble order

    private fun openPackages() = manager.snapshot().filter { it.expanded }.map { it.pkg }

    private fun toggleArrange() = setArranging(!arranging)

    private fun setArranging(on: Boolean) {
        if (on && openPackages().isEmpty()) {
            DiagLog.log(TAG, "Open at least one window before arranging")
            return
        }
        arranging = on
        styleChip()
        DiagLog.log(TAG, if (on) "Arrange mode on. Drag a window to reorder, long press the chip to leave" else "Arrange mode off")
        if (on) arrangeWindows()
    }

    private fun arrangeWindows() {
        val open = openPackages()
        if (open.isEmpty()) {
            setArranging(false)
            return
        }
        val slots = slotRects(open.size)
        open.forEachIndexed { index, pkg -> windows[pkg]?.setBounds(slots[index], animate = true) }
    }

    /** A dropped window takes the nearest slot and the others shuffle to keep a neat order. */
    private fun onWindowDragEnd(pkg: String, centerX: Int, centerY: Int) {
        raiseWindow(pkg)
        if (!arranging) return
        val open = openPackages()
        if (pkg !in open) return
        val slots = slotRects(open.size)
        val nearest = slots.indices.minByOrNull { index ->
            hypot((slots[index].centerX() - centerX).toDouble(), (slots[index].centerY() - centerY).toDouble())
        } ?: return
        val targetPkg = open[nearest]
        if (targetPkg != pkg) {
            val targetIndex = manager.snapshot().indexOfFirst { it.pkg == targetPkg }
            manager.moveTo(pkg, targetIndex)
        }
        arrangeWindows()
    }

    /** One window fills the area. Two stack in portrait or sit side by side in landscape. */
    private fun slotRects(count: Int): List<Rect> {
        if (count <= 0) return emptyList()
        val metrics = resources.displayMetrics
        val gap = dp(8)
        val area = Rect(gap, dp(36), metrics.widthPixels - dp(BUBBLE_DP) - gap, metrics.heightPixels - dp(24))
        val portrait = metrics.heightPixels >= metrics.widthPixels
        val columns = if (count == 1 || (count == 2 && portrait)) 1 else 2
        val rows = (count + columns - 1) / columns
        val cellWidth = (area.width() - gap * (columns - 1)) / columns
        val cellHeight = (area.height() - gap * (rows - 1)) / rows
        return List(count) { index ->
            val row = index / columns
            val column = index % columns
            val alone = columns > 1 && index == count - 1 && count % columns == 1
            val left = area.left + column * (cellWidth + gap)
            val top = area.top + row * (cellHeight + gap)
            Rect(left, top, left + if (alone) area.width() else cellWidth, top + cellHeight)
        }
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
        ensureChip()
    }

    private fun positionFor(key: String, fallback: Point): Point = positions.getOrPut(key) {
        val saved = positionPrefs.getString(key, null)?.split(",")
        val x = saved?.getOrNull(0)?.toIntOrNull()
        val y = saved?.getOrNull(1)?.toIntOrNull()
        if (x != null && y != null) clampToScreen(Point(x, y)) else fallback
    }

    private fun clampToScreen(point: Point): Point {
        val metrics = resources.displayMetrics
        val size = dp(BUBBLE_DP)
        return Point(point.x.coerceIn(0, metrics.widthPixels - size), point.y.coerceIn(0, metrics.heightPixels - size))
    }

    private fun savePosition(key: String, point: Point) {
        positions[key] = point
        positionPrefs.edit().putString(key, "${point.x},${point.y}").apply()
    }

    private fun createBubble(pkg: String, index: Int): BubbleHandle? {
        val root = LayoutInflater.from(themed).inflate(R.layout.view_bubble, null)
        val icon = root.findViewById<ImageView>(R.id.bubble_icon)
        icon.setImageDrawable(runCatching { packageManager.getApplicationIcon(pkg) }.getOrNull())
        root.contentDescription = getString(R.string.cd_bubble, labelOf(pkg))

        val size = dp(BUBBLE_DP)
        val screen = resources.displayMetrics
        val position = positionFor(pkg, Point(screen.widthPixels - size, dp(FIRST_SLOT_TOP_DP) + index * dp(SLOT_STEP_DP)))
        val params = overlayParams(size, size, position.x, position.y)
        attachDrag(root, params, pkg, closable = true, onTap = { onBubbleTap(pkg) })
        return try {
            popIn(root)
            wm.addView(root, params)
            BubbleHandle(root, icon, params)
        } catch (e: Exception) {
            DiagLog.log(TAG, "Could not show bubble for $pkg: ${e.message}")
            null
        }
    }

    private fun removeHandle(pkg: String) {
        handles.remove(pkg)?.let { popOutAndRemove(it.root) }
        positions.remove(pkg)
        positionPrefs.edit().remove(pkg).apply()
    }

    private fun labelOf(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    // ---- animations

    private fun popIn(view: View) {
        view.scaleX = 0f
        view.scaleY = 0f
        view.post {
            view.animate().scaleX(1f).scaleY(1f).setDuration(260)
                .setInterpolator(OvershootInterpolator(1.4f)).start()
        }
    }

    private fun popOutAndRemove(view: View) {
        view.animate().scaleX(0f).scaleY(0f).alpha(0f).setDuration(160)
            .withEndAction { runCatching { wm.removeView(view) } }.start()
    }

    // ---- drag, tap, long press and drop-to-close

    private fun attachDrag(
        view: View,
        params: WindowManager.LayoutParams,
        key: String,
        closable: Boolean,
        onTap: () -> Unit,
        onLongPress: (() -> Unit)? = null
    ) {
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var startX = 0
        var startY = 0
        var downX = 0f
        var downY = 0f
        var dragging = false
        var longPressed = false
        val longPressCheck = Runnable {
            if (!dragging) {
                longPressed = true
                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                onLongPress?.invoke()
            }
        }

        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    downX = event.rawX
                    downY = event.rawY
                    dragging = false
                    longPressed = false
                    if (onLongPress != null) {
                        ui.postDelayed(longPressCheck, ViewConfiguration.getLongPressTimeout().toLong())
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!dragging && !longPressed && hypot(dx, dy) > slop) {
                        dragging = true
                        ui.removeCallbacks(longPressCheck)
                        if (closable) showCloseZone()
                    }
                    if (dragging) {
                        params.x = startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        runCatching { wm.updateViewLayout(view, params) }
                        if (closable) setCloseHot(isOverClose(event.rawX, event.rawY))
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    ui.removeCallbacks(longPressCheck)
                    when {
                        longPressed -> Unit
                        !dragging -> onTap()
                        else -> {
                            val dropped = closable && isOverClose(event.rawX, event.rawY)
                            hideCloseZone()
                            if (dropped) {
                                closeBubble(key)
                            } else if (key == CHIP_KEY) {
                                val screen = resources.displayMetrics
                                val targetX = params.x.coerceIn(0, screen.widthPixels - params.width)
                                val targetY = params.y.coerceIn(0, screen.heightPixels - params.height)
                                savePosition(key, Point(targetX, targetY))
                                params.x = targetX
                                params.y = targetY
                                runCatching { wm.updateViewLayout(view, params) }
                            } else {
                                snapToEdge(view, params, key)
                            }
                        }
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    ui.removeCallbacks(longPressCheck)
                    hideCloseZone()
                    true
                }
                else -> false
            }
        }
    }

    private fun snapToEdge(view: View, params: WindowManager.LayoutParams, key: String) {
        val screen = resources.displayMetrics
        val targetX = if (params.x + params.width / 2 < screen.widthPixels / 2) 0 else screen.widthPixels - params.width
        val targetY = params.y.coerceIn(0, screen.heightPixels - params.height)
        savePosition(key, Point(targetX, targetY))
        val fromX = params.x
        val fromY = params.y
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 180
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val t = it.animatedValue as Float
                params.x = (fromX + (targetX - fromX) * t).toInt()
                params.y = (fromY + (targetY - fromY) * t).toInt()
                runCatching { wm.updateViewLayout(view, params) }
            }
            start()
        }
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
        view.alpha = 0f
        view.scaleX = 0.6f
        view.scaleY = 0.6f
        runCatching { wm.addView(view, overlayParams(size, size, x, closeZoneTop())) }
            .onSuccess {
                closeZone = view
                view.post { view.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180).start() }
            }
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
        val zone = closeZone ?: return
        closeZone = null
        zone.animate().alpha(0f).scaleX(0.6f).scaleY(0.6f).setDuration(120)
            .withEndAction { runCatching { wm.removeView(zone) } }.start()
    }

    // ---- chip: movable app drawer button, long press arranges windows

    private fun ensureChip() {
        if (chip != null) return
        val root = LayoutInflater.from(themed).inflate(R.layout.view_bubble, null)
        val icon = root.findViewById<ImageView>(R.id.bubble_icon)
        icon.setImageResource(R.drawable.ic_apps)
        root.contentDescription = getString(R.string.cd_drawer)

        val size = dp(BUBBLE_DP)
        val screen = resources.displayMetrics
        val position = positionFor(
            CHIP_KEY,
            Point(screen.widthPixels - size, dp(FIRST_SLOT_TOP_DP) + BubbleManager.MAX_BUBBLES * dp(SLOT_STEP_DP))
        )
        val params = overlayParams(size, size, position.x, position.y)
        attachDrag(
            root, params, CHIP_KEY, closable = false,
            onTap = { toggleDrawer() },
            onLongPress = { toggleArrange() }
        )
        try {
            popIn(root)
            wm.addView(root, params)
            chip = BubbleHandle(root, icon, params)
            styleChip()
        } catch (e: Exception) {
            DiagLog.log(TAG, "Could not show the chip: ${e.message}")
        }
    }

    private fun styleChip() {
        val icon = chip?.icon ?: return
        icon.setBackgroundResource(if (arranging) R.drawable.bg_chip_active else R.drawable.bg_chip)
        val attr = if (arranging) {
            com.google.android.material.R.attr.colorOnPrimary
        } else {
            com.google.android.material.R.attr.colorOnSecondaryContainer
        }
        icon.imageTintList = ColorStateList.valueOf(MaterialColors.getColor(themed, attr, 0))
    }

    // ---- app drawer (our own overlay, because a service cannot show a bottom sheet)

    private fun toggleDrawer() = if (drawer == null) showDrawer() else hideDrawer()

    private fun showDrawer() {
        val view = LayoutInflater.from(themed).inflate(R.layout.view_app_drawer, null)
        val adapter = AppListAdapter { entry ->
            hideDrawer()
            openBubble(entry.packageName)
        }
        view.findViewById<RecyclerView>(R.id.list).apply {
            layoutManager = LinearLayoutManager(themed)
            this.adapter = adapter
        }
        view.findViewById<EditText>(R.id.search)
            .doAfterTextChanged { adapter.filter(it?.toString().orEmpty()) }
        view.findViewById<View?>(R.id.btn_close_drawer)?.setOnClickListener { hideDrawer() }
        view.isFocusableInTouchMode = true
        view.requestFocus()
        view.setOnKeyListener { _, keyCode, event ->
            if (keyCode == android.view.KeyEvent.KEYCODE_BACK && event.action == android.view.KeyEvent.ACTION_UP) {
                hideDrawer()
                true
            } else {
                false
            }
        }
        view.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE) {
                hideDrawer()
                true
            } else {
                false
            }
        }

        val screen = resources.displayMetrics
        val params = WindowManager.LayoutParams(
            minOf(dp(DRAWER_MAX_WIDTH_DP), (screen.widthPixels * 0.92f).toInt()),
            (screen.heightPixels * 0.62f).toInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Focusable so the search field can open the keyboard.
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        }
        view.alpha = 0f
        view.translationY = dp(24).toFloat()
        try {
            wm.addView(view, params)
        } catch (e: Exception) {
            DiagLog.log(TAG, "Could not open the app drawer: ${e.message}")
            return
        }
        drawer = view
        view.post {
            view.animate().alpha(1f).translationY(0f).setDuration(220)
                .setInterpolator(DecelerateInterpolator()).start()
        }

        val recents = manager.recentsSnapshot()
        thread {
            val apps = AppList.load(this, recents)
            ui.post { adapter.submit(apps) }
        }
    }

    private fun hideDrawer() {
        val view = drawer ?: return
        drawer = null
        view.animate().alpha(0f).translationY(dp(24).toFloat()).setDuration(160)
            .withEndAction { runCatching { wm.removeView(view) } }.start()
    }
}
