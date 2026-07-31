package com.example.deskpet

import android.app.*
import android.content.Context
import android.content.Intent
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

    private fun startAppWatcher() {
        val handler = Handler(Looper.getMainLooper())
        handler.postDelayed(object : Runnable {
            override fun run() {
                try {
                    val current = getForegroundPackage()
                    if (current != null && current != lastForegroundApp &&
                        !current.startsWith("com.example.deskpet")) {
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
    private var touchStartTime = 0L
    private var hasMoved = false
    private var draggingNotified = false
    private var menuOpen = false
    private var chatOpen = false

    // JS桥接：桌宠5连戳后移动到右下角画圈
    inner class AndroidBridge {
        private val uiHandler = Handler(Looper.getMainLooper())

        @android.webkit.JavascriptInterface
        fun moveToCorner() {
            uiHandler.post {
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

        // 跳转DeepSeek App，没装就打开官网
        @android.webkit.JavascriptInterface
        fun openLink() {
            uiHandler.post {
                try {
                    val appIntent = packageManager.getLaunchIntentForPackage("com.deepseek.chat")
                    if (appIntent != null) {
                        appIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(appIntent)
                        return@post
                    }
                } catch (e: Exception) {}
                try {
                    val webIntent = Intent(
                        Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://www.deepseek.com")
                    )
                    webIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(webIntent)
                } catch (e: Exception) {}
            }
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
                val w = Math.min((dm.widthPixels * 0.80).toInt(), dpToPx(360))
                val h = (dm.heightPixels * 0.45).toInt()
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
                params?.width = dpToPx(PET_SIZE_DP)
                params?.height = dpToPx(PET_HEIGHT_DP)
                params?.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                params?.x = 50
                params?.y = 300
                try { windowManager?.updateViewLayout(overlayView, params) } catch (e: Exception) {}
            }
        }
    }

    private fun createTouchListener(): View.OnTouchListener {
        return View.OnTouchListener { _, event ->
            // 菜单或聊天打开时：把触摸完全交给WebView（按钮/输入框才能用）
            if (menuOpen || chatOpen) return@OnTouchListener false
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
                    if (hasMoved) {
                        overlayView?.evaluateJavascript(
                            "window.petEngine && window.petEngine.setDragging(false)", null
                        )
                    }
                    if (!hasMoved) {
                        when {
                            elapsed > 600 -> onLongPress()
                            System.currentTimeMillis() - lastTapTime < 300 -> onDoubleTap()
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
        overlayView?.evaluateJavascript(
            "window.petEngine && window.petEngine.onTap()", null
        )
    }

    private fun onDoubleTap() {
        overlayView?.evaluateJavascript(
            "window.petEngine && window.petEngine.onDoubleTap()", null
        )
    }

    private fun onLongPress() {
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
