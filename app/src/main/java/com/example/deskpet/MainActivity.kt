package com.example.deskpet

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_CODE_OVERLAY = 100
        private const val REQUEST_CODE_NOTIFICATION = 101
        private const val REQUEST_CODE_USAGE = 102
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(android.R.layout.simple_list_item_1)

        checkPermissionsAndStart()
    }

    private fun checkPermissionsAndStart() {
        val permissionsNeeded = mutableListOf<String>()

        // SYSTEM_ALERT_WINDOW
        if (!Settings.canDrawOverlays(this)) {
            permissionsNeeded.add("悬浮窗权限")
        }

        // POST_NOTIFICATIONS (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsNeeded.add("通知权限")
            }
        }

        // PACKAGE_USAGE_STATS
        val appOps = getSystemService(APP_OPS_SERVICE) as android.app.AppOpsManager
        val mode = appOps.checkOpNoThrow(
            android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(), packageName
        )
        if (mode != android.app.AppOpsManager.MODE_ALLOWED) {
            permissionsNeeded.add("使用情况访问权限")
        }

        if (permissionsNeeded.isEmpty()) {
            startOverlayService()
        } else {
            showPermissionDialog(permissionsNeeded)
        }
    }

    private fun showPermissionDialog(missing: List<String>) {
        AlertDialog.Builder(this)
            .setTitle("需要以下权限")
            .setMessage("为了让桌宠正常工作，请授予以下权限：\n\n" + missing.joinToString("\n"))
            .setPositiveButton("去授权") { _, _ ->
                // Open overlay permission settings as main entry
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:$packageName")
                )
                startActivityForResult(intent, REQUEST_CODE_OVERLAY)
            }
            .setNegativeButton("取消") { _, _ -> finish() }
            .show()
    }

    private fun startOverlayService() {
        val intent = Intent(this, OverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        Toast.makeText(this, "桌宠已启动！", Toast.LENGTH_SHORT).show()
        finish()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_OVERLAY) {
            checkPermissionsAndStart()
        }
    }
}
