package com.example.deskpet

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.net.Uri

// 透明跳转Activity：绕过后台启动限制，先把自己拉前台再启动DeepSeek App
class DeepSeekLauncherActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val appIntent = packageManager.getLaunchIntentForPackage("com.deepseek.chat")
            if (appIntent != null) {
                appIntent.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                )
                startActivity(appIntent)
                finish()
                return
            }
        } catch (e: Exception) {}
        // App没装：打开官网
        try {
            val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.deepseek.com"))
            webIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(webIntent)
        } catch (e: Exception) {}
        finish()
    }
}
