package com.tvtranslator.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.URLDecoder
import kotlin.concurrent.thread

class OverlayService : Service() {

    companion object {
        @Volatile
        var isRunning = false
        private var instance: OverlayService? = null

        fun updateSubtitle(text: String) {
            instance?.showText(text)
        }
    }

    private lateinit var windowManager: WindowManager
    private lateinit var subtitleView: TextView
    private val mainHandler = Handler(Looper.getMainLooper())
    private var serverSocket: ServerSocket? = null
    private val hideRunnable = Runnable { subtitleView.visibility = View.GONE }

    override fun onCreate() {
        super.onCreate()
        instance = this
        isRunning = true
        startForegroundNotification()
        setupOverlayView()
        startHttpServer()
    }

    private fun startForegroundNotification() {
        val channelId = "tv_subtitle_overlay"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "خدمة الترجمة",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
            .setContentTitle("مترجم الألعاب يعمل")
            .setContentText("بانتظار الترجمة على المنفذ 8765")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()

        startForeground(101, notification)
    }

    private fun setupOverlayView() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val bg = GradientDrawable().apply {
            setColor(Color.parseColor("#CC000000"))
            cornerRadius = 24f
        }

        subtitleView = TextView(this).apply {
            text = ""
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            textDirection = View.TEXT_DIRECTION_RTL
            background = bg
            setPadding(36, 18, 36, 18)
            visibility = View.GONE
        }

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = 56
        }

        windowManager.addView(subtitleView, params)
    }

    private fun showText(raw: String) {
        val clean = raw.trim()
        mainHandler.post {
            mainHandler.removeCallbacks(hideRunnable)
            if (clean.isEmpty()) {
                subtitleView.visibility = View.GONE
            } else {
                subtitleView.text = clean
                subtitleView.visibility = View.VISIBLE
                mainHandler.postDelayed(hideRunnable, 8000)
            }
        }
    }

    private fun startHttpServer() {
        thread(isDaemon = true) {
            try {
                serverSocket = ServerSocket(8765)
                while (isRunning) {
                    val client = serverSocket?.accept() ?: break
                    thread(isDaemon = true) {
                        client.use { sock ->
                            val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8))
                            val requestLine = reader.readLine() ?: return@use
                            var contentLength = 0
                            while (true) {
                                val line = reader.readLine() ?: break
                                if (line.isEmpty()) break
                                if (line.lowercase().startsWith("content-length:")) {
                                    contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
                                }
                            }

                            var subtitle = ""
                            if (requestLine.startsWith("GET ")) {
                                val path = requestLine.split(" ").getOrNull(1) ?: ""
                                val query = path.substringAfter("?", "")
                                val textParam = query.split("&")
                                    .firstOrNull { it.startsWith("text=") }
                                    ?.substringAfter("text=") ?: ""
                                subtitle = URLDecoder.decode(textParam, "UTF-8")
                            } else if (requestLine.startsWith("POST ") && contentLength > 0) {
                                val buf = CharArray(contentLength)
                                var readTotal = 0
                                while (readTotal < contentLength) {
                                    val r = reader.read(buf, readTotal, contentLength - readTotal)
                                    if (r <= 0) break
                                    readTotal += r
                                }
                                subtitle = String(buf, 0, readTotal)
                            }

                            showText(subtitle)

                            val response = "HTTP/1.1 200 OK\r\nContent-Type: text/plain; charset=utf-8\r\nAccess-Control-Allow-Origin: *\r\n\r\nOK"
                            sock.getOutputStream().write(response.toByteArray(Charsets.UTF_8))
                            sock.getOutputStream().flush()
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    override fun onDestroy() {
        isRunning = false
        instance = null
        try { serverSocket?.close() } catch (_: Exception) {}
        try { windowManager.removeView(subtitleView) } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
