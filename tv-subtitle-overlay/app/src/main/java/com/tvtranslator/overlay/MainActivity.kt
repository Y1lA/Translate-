package com.tvtranslator.overlay

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var ipText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#121316"))
            setPadding(64, 64, 64, 64)
        }

        val title = TextView(this).apply {
            text = "مترجم الألعاب — Google TV"
            textSize = 28f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }

        ipText = TextView(this).apply {
            text = "عنوان الشاشة: http://${getLocalIp()}:8765"
            textSize = 20f
            setTextColor(Color.parseColor("#9DD2FF"))
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 16)
        }

        statusText = TextView(this).apply {
            textSize = 18f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 32)
        }

        val startBtn = Button(this).apply {
            text = "تشغيل خدمة الترجمة"
            textSize = 18f
            isFocusable = true
            setOnClickListener { startOverlay() }
        }

        val testBtn = Button(this).apply {
            text = "إرسال جملة تجريبية"
            textSize = 18f
            isFocusable = true
            setOnClickListener {
                OverlayService.updateSubtitle("تجربة الترجمة العربية فوق الشاشة!")
            }
        }

        val stopBtn = Button(this).apply {
            text = "إيقاف الخدمة"
            textSize = 18f
            isFocusable = true
            setOnClickListener {
                stopService(Intent(this@MainActivity, OverlayService::class.java))
                updateStatus()
            }
        }

        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 12, 0, 12) }

        root.addView(title)
        root.addView(ipText)
        root.addView(statusText)
        root.addView(startBtn, params)
        root.addView(testBtn, params)
        root.addView(stopBtn, params)

        setContentView(root)
        startBtn.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun updateStatus() {
        val hasPerm = Settings.canDrawOverlays(this)
        val running = OverlayService.isRunning
        statusText.text = when {
            !hasPerm -> "الصلاحية: يلزم تفعيل الظهور فوق التطبيقات (أو عبر ADB)"
            running -> "الحالة: تعمل الآن — يمكنك التحويل إلى مدخل HDMI"
            else -> "الحالة: متوقفة — اضغط تشغيل خدمة الترجمة"
        }
    }

    private fun startOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            return
        }
        val svc = Intent(this, OverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(svc)
        } else {
            startService(svc)
        }
        statusText.postDelayed({ updateStatus() }, 300)
    }

    private fun getLocalIp(): String {
        return try {
            NetworkInterface.getNetworkInterfaces().toList()
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { !it.isLoopbackAddress && it is Inet4Address }
                ?.hostAddress ?: "غير متصل بالواي فاي"
        } catch (_: Exception) {
            "غير معروف"
        }
    }
}
