package com.example.deskpet

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings

/**
 * 开机自启接收器：手机重启后自动拉起桌宠
 * 直接启动 MainActivity（它会检查权限并自动启动悬浮窗服务）
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED -> {
                // 只有已授予悬浮窗权限才自动启动（避免开机弹权限框烦人）
                if (Settings.canDrawOverlays(context)) {
                    val i = Intent(context, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(i)
                }
            }
        }
    }
}
