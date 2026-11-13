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
import android.text.TextUtils
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import org.json.JSONArray
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
            instance?.showSingleSubtitle(text)
        }
    }

    private lateinit var windowManager: WindowManager
    private lateinit var rootContainer: FrameLayout
    private lateinit var subtitleView: TextView
    private val mainHandler = Handler(Looper.getMainLooper())
    private var serverSocket: ServerSocket? = null
    private val hideRunnable = Runnable { clearAll() }
    private var screenW = 1920
    private var screenH = 1080

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

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        screenW = metrics.widthPixels
        screenH = metrics.heightPixels

        rootContainer = FrameLayout(this)

        subtitleView = createLabelView(20f).apply {
            maxWidth = (screenW * 0.78).toInt()
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            visibility = View.GONE
        }

        val subParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = (screenH * 0.05).toInt()
        }
        rootContainer.addView(subtitleView, subParams)

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        windowManager.addView(rootContainer, params)
    }

    private fun createLabelView(sizeSp: Float): TextView {
        val bg = GradientDrawable().apply {
            setColor(Color.parseColor("#D9111318"))
            cornerRadius = 18f
            setStroke(2, Color.parseColor("#44FFFFFF"))
        }
        return TextView(this).apply {
            textSize = sizeSp
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            textDirection = View.TEXT_DIRECTION_RTL
            background = bg
            setPadding(24, 10, 24, 10)
        }
    }

    private fun clearAll() {
        subtitleView.visibility = View.GONE
        while (rootContainer.childCount > 1) {
            rootContainer.removeViewAt(rootContainer.childCount - 1)
        }
    }

    private fun showSingleSubtitle(raw: String) {
        val clean = raw.trim()
        mainHandler.post {
            mainHandler.removeCallbacks(hideRunnable)
            clearAll()
            if (clean.isNotEmpty()) {
                subtitleView.text = clean
                subtitleView.visibility = View.VISIBLE
                mainHandler.postDelayed(hideRunnable, 7000)
            }
        }
    }

    private fun showPositionedItems(jsonStr: String) {
        mainHandler.post {
            mainHandler.removeCallbacks(hideRunnable)
            clearAll()
            try {
                val arr = JSONArray(jsonStr)
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val text = obj.optString("t", "").trim()
                    if (text.isEmpty()) continue
                    val nx = obj.optDouble("x", 0.5).coerceIn(0.02, 0.88)
                    val ny = obj.optDouble("y", 0.5).coerceIn(0.05, 0.94)

                    val label = createLabelView(16f).apply {
                        this.text = text
                        maxWidth = (screenW * 0.35).toInt()
                        maxLines = 2
                        ellipsize = TextUtils.TruncateAt.END
                    }
                    val lp = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        gravity = Gravity.TOP or Gravity.START
                        leftMargin = (nx * screenW).toInt()
                        topMargin = ((ny - 0.045).coerceAtLeast(0.01) * screenH).toInt()
                    }
                    rootContainer.addView(label, lp)
                }
                if (arr.length() > 0) {
                    mainHandler.postDelayed(hideRunnable, 8000)
                }
            } catch (_: Exception) {
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
                            while (true) {
                                val line = reader.readLine() ?: break
                                if (line.isEmpty()) break
                            }

                            if (requestLine.startsWith("GET ")) {
                                val path = requestLine.split(" ").getOrNull(1) ?: ""
                                val query = path.substringAfter("?", "")
                                val paramsMap = query.split("&").mapNotNull {
                                    val idx = it.indexOf('=')
                                    if (idx > 0) {
                                        it.substring(0, idx) to URLDecoder.decode(it.substring(idx + 1), "UTF-8")
                                    } else null
                                }.toMap()

                                val itemsJson = paramsMap["items"]
                                val textParam = paramsMap["text"]
                                if (!itemsJson.isNullOrBlank()) {
                                    showPositionedItems(itemsJson)
                                } else if (textParam != null) {
                                    showSingleSubtitle(textParam)
                                }
                            }

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
        try { windowManager.removeView(rootContainer) } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
