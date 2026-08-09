package com.example.deskpet

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.view.*
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebSettings
import androidx.core.app.NotificationCompat

class OverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: WebView? = null
    private var params: WindowManager.LayoutParams? = null

    companion object {
        private const val CHANNEL_ID = "pet_overlay_channel"
        private const val NOTIFICATION_ID = 1001
        private const val PET_SIZE_DP = 100
        private const val PET_HEIGHT_DP = 135
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("\uD83D\uDC3E 我在这里陪你~"))
        setupOverlay()
        startAppWatcher()
        // 充电状态监听：充电时显示充电CG
        val filter = IntentFilter()
        filter.addAction(Intent.ACTION_POWER_CONNECTED)
        filter.addAction(Intent.ACTION_POWER_DISCONNECTED)
        filter.addAction(Intent.ACTION_BATTERY_LOW)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(powerReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(powerReceiver, filter)
        }
        uiHandler.postDelayed({ notifyCharging() }, 2500)
    }

    private val uiHandler = Handler(Looper.getMainLooper())

    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_POWER_CONNECTED -> overlayView?.evaluateJavascript(
                    "window.petEngine && window.petEngine.setCharging(true)", null)
                Intent.ACTION_POWER_DISCONNECTED -> overlayView?.evaluateJavascript(
                    "window.petEngine && window.petEngine.setCharging(false)", null)
                Intent.ACTION_BATTERY_LOW -> overlayView?.evaluateJavascript(
                    "window.petEngine && window.petEngine.lowBattery()", null)
            }
        }
    }

    private fun notifyCharging() {
        try {
            val bm = getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
            val charging = bm.isCharging
            overlayView?.evaluateJavascript(
                "window.petEngine && window.petEngine.setCharging($charging)", null
            )
        } catch (e: Exception) {}
    }

    // === 前台App监控：切换App时桌宠有反应 ===
    private var lastForegroundApp: String? = null
    private val appNames = mapOf(
        "com.xingin.xhs" to "小红书",
        "com.ss.android.ugc.aweme" to "抖音",
        "com.tencent.mm" to "微信",
        "com.deepseek.chat" to "DeepSeek",
        "com.psyche.kelivo" to "Kelivo",
        "com.ai.assistance.operit" to "Operit",
        "com.android.settings" to "设置",
        "com.coloros.filemanager" to "文件管理"
    )

    private var ghostMode = false
    private var entertainmentMode = false
    // 游戏/追番类App（自动进入半透明陪玩模式）
    private fun isEntertainment(pkg: String): Boolean {
        if (pkg.contains("tmgp") || pkg.contains("mihoyo") || pkg.contains("supercell") ||
            pkg.contains("kakaogames") || pkg.contains("netease.tm") || pkg.contains("tencent.tmgp")) return true
        if (pkg.contains("bili") || pkg.contains("qiyi") || pkg.contains("qqlive") ||
            pkg.contains("youku") || pkg.contains("mgtv") || pkg.contains("hunantv") ||
            pkg.contains("kiwi") || pkg.contains("gifmaker") || pkg.contains("aweme") ||
            pkg.contains("kuaishou") || pkg.contains("douyu") || pkg.contains("huya") ||
            pkg.contains("bilibili") || pkg.contains("iqiyi") || pkg.contains("acfun") ||
            pkg.contains("xhs") || pkg.contains("weibo") || pkg.contains("zhihu")) return true
        return false
    }
    private fun setPetAlpha(a: Float) {
        uiHandler.post {
            try { params?.alpha = a; windowManager?.updateViewLayout(overlayView, params) } catch (e: Exception) {}
        }
    }
    private fun startAppWatcher() {
        val handler = Handler(Looper.getMainLooper())
        handler.postDelayed(object : Runnable {
            override fun run() {
                try {
                    val current = getForegroundPackage()
                    if (current != null && current != lastForegroundApp &&
                        !current.startsWith("com.example.deskpet")) {
                        val wasEntertainment = entertainmentMode
                        entertainmentMode = isEntertainment(current)
                        if (entertainmentMode != wasEntertainment) {
                            if (entertainmentMode) {
                                // 游戏/追番：自动半透明+陪玩模式（不妨碍）
                                if (!ghostMode) setPetAlpha(0.45f)
                                overlayView?.evaluateJavascript(
                                    "window.petEngine && window.petEngine.onEntertainment(true)", null
                                )
                            } else {
                                // 退出娱乐：恢复（隐身模式则保持隐身）
                                if (!ghostMode) setPetAlpha(1.0f)
                                overlayView?.evaluateJavascript(
                                    "window.petEngine && window.petEngine.onEntertainment(false)", null
                                )
                            }
                        }
                        lastForegroundApp = current
                        val name = appNames[current] ?: "别的App"
                        overlayView?.evaluateJavascript(
                            "window.petEngine && window.petEngine.onAppSwitch('$name')", null
                        )
                    }
                } catch (e: Exception) {}
                handler.postDelayed(this, 3000)
            }
        }, 3000)
    }

    private fun getForegroundPackage(): String? {
        try {
            val um = getSystemService(Context.USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
            val end = System.currentTimeMillis()
            val begin = end - 10000
            val events = um.queryEvents(begin, end)
            val event = android.app.usage.UsageEvents.Event()
            var pkg: String? = null
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    pkg = event.packageName
                }
            }
            return pkg
        } catch (e: Exception) {
            return null
        }
    }

    private fun setupOverlay() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        params = WindowManager.LayoutParams(
            dpToPx(PET_SIZE_DP),
            dpToPx(PET_HEIGHT_DP),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 50
            y = 300
        }

        overlayView = WebView(this).apply {
            setBackgroundColor(0x00000000)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = true
                cacheMode = WebSettings.LOAD_DEFAULT
            }
            webViewClient = WebViewClient()
            loadUrl("file:///android_asset/pet.html")
            setOnTouchListener(createTouchListener())
            attachKeyboardListener()
            // 聊天模式按返回键关闭聊天窗
            setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP && chatOpen) {
                    overlayView?.evaluateJavascript(
                        "window.closeChatView && window.closeChatView()", null
                    )
                    true
                } else {
                    false
                }
            }
            addJavascriptInterface(AndroidBridge(), "AndroidBridge")
        }

        windowManager?.addView(overlayView, params)
    }

    // === GESTURE HANDLING ===
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var lastTapTime = 0L
    private var lastDoubleTapTime = 0L
    private var touchStartTime = 0L
    private var hasMoved = false
    private var draggingNotified = false
    private var menuOpen = false
    private var chatOpen = false
    private var keyboardVisible = false
    // 聊天窗拖动状态
    private var chatDragging = false
    private var dragStartX = 0
    private var dragStartY = 0
    private var dragTouchX = 0f
    private var dragTouchY = 0f

    private var savedX = 50
    private var savedY = 300

    // JS桥接：桌宠5连戳后移动到右下角画圈
    inner class AndroidBridge {
        private val uiHandler = Handler(Looper.getMainLooper())

        @android.webkit.JavascriptInterface
        fun moveToCorner() {
            uiHandler.post {
                savedX = params?.x ?: 50
                savedY = params?.y ?: 300
                val dm = resources.displayMetrics
                val sw = dm.widthPixels
                val sh = dm.heightPixels
                val w = params?.width ?: 0
                val h = params?.height ?: 0
                params?.x = sw - w - 12
                params?.y = sh - h - 160
                try { windowManager?.updateViewLayout(overlayView, params) } catch (e: Exception) {}
            }
        }

        // 角落模式结束：回到原来的位置
        @android.webkit.JavascriptInterface
        fun moveBack() {
            uiHandler.post {
                params?.x = savedX
                params?.y = savedY
                try { windowManager?.updateViewLayout(overlayView, params) } catch (e: Exception) {}
            }
        }
        // 连续小步移动（带屏幕边界限制）：配合JS蹦跳动画实现"一蹦一跳跑走"
        @android.webkit.JavascriptInterface
        fun moveStep(dx: Int, dy: Int) {
            uiHandler.post {
                val dm = resources.displayMetrics
                val sw = dm.widthPixels
                val sh = dm.heightPixels
                val w = params?.width ?: 0
                val h = params?.height ?: 0
                val nx = ((params?.x ?: 0) + dx).coerceIn(0, (sw - w).coerceAtLeast(0))
                val ny = ((params?.y ?: 0) + dy).coerceIn(0, (sh - h).coerceAtLeast(0))
                params?.x = nx
                params?.y = ny
                try { windowManager?.updateViewLayout(overlayView, params) } catch (e: Exception) {}
            }
        }
        // 振动反馈（JS调用）
        @android.webkit.JavascriptInterface
        fun vibrate(ms: Int) {
            try {
                val v = getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
                if (v != null && v.hasVibrator()) v.vibrate(ms.toLong())
            } catch (e: Exception) {}
        }
        // 双击逃跑：随机移动到屏幕其他位置
        @android.webkit.JavascriptInterface
        fun moveRandom() {
            uiHandler.post {
                val dm = resources.displayMetrics
                val sw = dm.widthPixels
                val sh = dm.heightPixels
                val w = params?.width ?: 0
                val h = params?.height ?: 0
                val nx = (50 + Math.random() * (sw - w - 100)).toInt()
                val ny = (100 + Math.random() * (sh - h - 200)).toInt()
                params?.x = nx
                params?.y = ny
                try { windowManager?.updateViewLayout(overlayView, params) } catch (e: Exception) {}
            }
        }

        // 跳转DeepSeek App：先透明Activity直启，1.5秒后确认没起来就弹通知
        @android.webkit.JavascriptInterface
        fun openLink() {
            uiHandler.post {
                try {
                    val i = Intent(this@OverlayService, DeepSeekLauncherActivity::class.java)
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(i)
                } catch (e: Exception) {}
                // 延迟检查DeepSeek是否真的到了前台，没到就弹通知兜底
                uiHandler.postDelayed({
                    try {
                        var top = ""
                        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
                        val now = System.currentTimeMillis()
                        val stats = usm.queryEvents(now - 3000, now)
                        val ev = android.app.usage.UsageEvents.Event()
                        var lastPkg: String? = null
                        while (stats.hasNextEvent()) {
                            stats.getNextEvent(ev)
                            if (ev.eventType == android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED || ev.eventType == android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND) {
                                lastPkg = ev.packageName
                            }
                        }
                        top = lastPkg ?: ""
                        if (top != "com.deepseek.chat") showDeepSeekNotification()
                    } catch (e: Exception) {
                        showDeepSeekNotification()
                    }
                }, 1600)
            }
        }

        // 通知兜底：点通知先拉起桌宠MainActivity（前台化），再自动跳DeepSeek
        // （ColorOS会拦截一切后台启动Activity，但自己前台后启动其他App不受限）
        private fun showDeepSeekNotification() {
            try {
                val selfIntent = Intent(this@OverlayService, MainActivity::class.java)
                    .putExtra("gotoDeepSeek", true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val contentIntent = PendingIntent.getActivity(
                    this@OverlayService, 2002, selfIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val noti = NotificationCompat.Builder(this@OverlayService, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle("\uD83D\uDC0B 点我打开DeepSeek！")
                    .setContentText("系统不让直接跳，点一下就进DeepSeek App～")
                    .setContentIntent(contentIntent)
                    .setAutoCancel(true)
                    .build()
                getSystemService(NotificationManager::class.java).notify(2002, noti)
            } catch (e: Exception) {}
        }

        // 菜单开关：打开时把触摸交给WebView（按钮才能点），关闭时手势接管
        @android.webkit.JavascriptInterface
        fun notifyMenu(open: Boolean) {
            menuOpen = open
        }

        // 聊天窗开关：打开时触摸全部交给WebView（按钮/键盘可用）
        @android.webkit.JavascriptInterface
        fun notifyChat(open: Boolean) {
            chatOpen = open
        }

        // 打开聊天小窗：放大窗口+允许键盘（必须回UI线程）
        @android.webkit.JavascriptInterface
        fun openChat() {
            uiHandler.post {
                chatOpen = true
                val dm = resources.displayMetrics
                val w = Math.min((dm.widthPixels * 0.86).toInt(), dpToPx(400))
                val h = (dm.heightPixels * 0.52).toInt()
                params?.width = w
                params?.height = h
                params?.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                params?.x = (dm.widthPixels - w) / 2
                params?.y = 30
                try { windowManager?.updateViewLayout(overlayView, params) } catch (e: Exception) {}
            }
        }

        // 关闭聊天小窗：缩回桌宠（必须回UI线程）
        @android.webkit.JavascriptInterface
        fun closeChat() {
            uiHandler.post {
                chatOpen = false
                keyboardVisible = false
                params?.width = dpToPx(PET_SIZE_DP)
                params?.height = dpToPx(PET_HEIGHT_DP)
                params?.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                params?.x = 50
                params?.y = 300
                try { windowManager?.updateViewLayout(overlayView, params) } catch (e: Exception) {}
            }
        }
        // 手动隐身模式切换（菜单👻按钮）
        @android.webkit.JavascriptInterface
        fun toggleGhost() {
            uiHandler.post {
                ghostMode = !ghostMode
                setPetAlpha(if (ghostMode) 0.22f else 1.0f)
                overlayView?.evaluateJavascript(
                    "window.petEngine && window.petEngine.onGhost(" + ghostMode + ")", null
                )
            }
        }
        // 聊天窗右下角拖拽缩放（必须回UI线程）
        @android.webkit.JavascriptInterface
        fun resizeChatBy(dx: Int, dy: Int) {
            uiHandler.post {
                if (!chatOpen) return@post
                val dm = resources.displayMetrics
                var w = (params?.width ?: dpToPx(PET_SIZE_DP)) + dx
                var h = (params?.height ?: dpToPx(PET_HEIGHT_DP)) + dy
                w = w.coerceIn(dpToPx(180), (dm.widthPixels * 0.95).toInt())
                h = h.coerceIn(dpToPx(220), (dm.heightPixels * 0.85).toInt())
                params?.width = w
                params?.height = h
                try { windowManager?.updateViewLayout(overlayView, params) } catch (e: Exception) {}
            }
        }
    }

    // 键盘弹出时把聊天窗移到键盘上方，保证对话区可见
    private fun attachKeyboardListener() {
        overlayView?.viewTreeObserver?.addOnGlobalLayoutListener {
            try {
                if (!chatOpen) return@addOnGlobalLayoutListener
                val ov = overlayView ?: return@addOnGlobalLayoutListener
                val rect = android.graphics.Rect()
                ov.getWindowVisibleDisplayFrame(rect)
                val kbH = ov.rootView.height - rect.bottom
                if (kbH > 150) {
                    if (keyboardVisible) return@addOnGlobalLayoutListener
                    keyboardVisible = true
                    val dm = resources.displayMetrics
                    val w = params?.width ?: 0
                    var h = params?.height ?: 0
                    val maxH = rect.height() - 100
                    if (h > maxH) {
                        h = maxH
                        params?.height = h
                    }
                    params?.y = rect.top + rect.height() - h - 10
                    params?.x = rect.left + (rect.width() - w) / 2
                    windowManager?.updateViewLayout(overlayView, params)
                } else if (keyboardVisible) {
                    keyboardVisible = false
                    val dm = resources.displayMetrics
                    val w = Math.min((dm.widthPixels * 0.86).toInt(), dpToPx(400))
                    val h = (dm.heightPixels * 0.52).toInt()
                    params?.width = w
                    params?.height = h
                    params?.x = (dm.widthPixels - w) / 2
                    params?.y = 30
                    windowManager?.updateViewLayout(overlayView, params)
                }
            } catch (e: Exception) {}
        }
    }

    private fun createTouchListener(): View.OnTouchListener {
        return View.OnTouchListener { _, event ->
            // 菜单打开时：把触摸完全交给WebView（按钮才能点）
            if (menuOpen) return@OnTouchListener false
            // 聊天窗打开时：标题栏区域可拖动窗口，其余交给WebView
            if (chatOpen) {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        val headH = ((overlayView?.height ?: 0) * 0.16f).toInt().coerceAtLeast(dpToPx(40))
                        // 标题栏区域可拖动，但右上角✕按钮区域放行给WebView
                        val xBtnW = dpToPx(52)
                        if (event.y < headH && event.x < (overlayView?.width ?: 0) - xBtnW) {
                            chatDragging = true
                            dragStartX = params?.x ?: 0
                            dragStartY = params?.y ?: 0
                            dragTouchX = event.rawX
                            dragTouchY = event.rawY
                            return@OnTouchListener true
                        }
                        return@OnTouchListener false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (chatDragging) {
                            params?.x = dragStartX + (event.rawX - dragTouchX).toInt()
                            params?.y = dragStartY + (event.rawY - dragTouchY).toInt()
                            windowManager?.updateViewLayout(overlayView, params)
                            return@OnTouchListener true
                        }
                        return@OnTouchListener false
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (chatDragging) {
                            chatDragging = false
                            return@OnTouchListener true
                        }
                        return@OnTouchListener false
                    }
                }
                return@OnTouchListener false
            }
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params?.x ?: 0
                    initialY = params?.y ?: 0
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    touchStartTime = System.currentTimeMillis()
                    hasMoved = false
                    draggingNotified = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    // 隐身模式：不能移动，只能长按
                    if (ghostMode) return@OnTouchListener true
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                        hasMoved = true
                        if (!draggingNotified) {
                            draggingNotified = true
                            overlayView?.evaluateJavascript(
                                "window.petEngine && window.petEngine.setDragging(true)", null
                            )
                        }
                        params?.x = initialX + dx
                        params?.y = initialY + dy
                        windowManager?.updateViewLayout(overlayView, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val elapsed = System.currentTimeMillis() - touchStartTime
                    // 隐身模式：只认长按（弹菜单显形），单击/双击全部忽略
                    if (ghostMode) {
                        if (!hasMoved && elapsed > 600) onLongPress()
                        return@OnTouchListener true
                    }
                    if (hasMoved) {
                        overlayView?.evaluateJavascript(
                            "window.petEngine && window.petEngine.setDragging(false)", null
                        )
                    }
                    if (!hasMoved) {
                        when {
                            elapsed > 600 -> onLongPress()
                            System.currentTimeMillis() - lastTapTime < 150 && System.currentTimeMillis() - lastDoubleTapTime > 600 -> {
                                lastTapTime = System.currentTimeMillis()
                                lastDoubleTapTime = System.currentTimeMillis()
                                onDoubleTap()
                            }
                            else -> {
                                lastTapTime = System.currentTimeMillis()
                                onTap()
                            }
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun onTap() {
        try {
            val v = getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
            if (v != null && v.hasVibrator()) v.vibrate(18)
        } catch (e: Exception) {}
        overlayView?.evaluateJavascript(
            "window.petEngine && window.petEngine.onTap()", null
        )
    }

    private fun onDoubleTap() {
        try {
            val v = getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
            if (v != null && v.hasVibrator()) v.vibrate(30)
        } catch (e: Exception) {}
        overlayView?.evaluateJavascript(
            "window.petEngine && window.petEngine.onDoubleTap()", null
        )
    }

    private fun onLongPress() {
        try {
            val v = getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
            if (v != null && v.hasVibrator()) v.vibrate(45)
        } catch (e: Exception) {}
        overlayView?.evaluateJavascript(
            "window.petEngine && window.petEngine.onLongPress()", null
        )
    }

    // === NOTIFICATION ===
    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("\uD83D\uDC3E")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Pet",
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    // === UTILS ===
    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    override fun onDestroy() {
        overlayView?.let {
            windowManager?.removeView(it)
            it.destroy()
        }
        overlayView = null
        super.onDestroy()
    }
}
