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
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.URLDecoder
import kotlin.concurrent.thread

class OverlayService : Service() {

    companion object {
        @Volatile
        var isRunning = false
        @Volatile
        var currentMode = "menus"
        @Volatile
        var savedKey = ""
        @Volatile
        var savedModel = "gemma-4-26b-a4b-it"
        @Volatile
        var savedGame = "GTA 6"
        private var instance: OverlayService? = null

        fun updateSubtitle(text: String) {
            instance?.showSingleSubtitle(text)
        }
    }

    private lateinit var windowManager: WindowManager
    private lateinit var subtitleView: TextView
    private val itemViews = mutableListOf<View>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var serverSocket: ServerSocket? = null
    private val hideRunnable = Runnable { clearAll() }
    private var screenW = 1920
    private var screenH = 1080
    private var lastItemsJson: String = ""
    private var lastSingleText: String = ""

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

    private fun overlayType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
    }

    private fun baseFlags(): Int {
        return WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
    }

    private fun setupOverlayView() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        if (metrics.widthPixels > 0 && metrics.heightPixels > 0) {
            screenW = metrics.widthPixels
            screenH = metrics.heightPixels
        }

        val subBg = GradientDrawable().apply {
            setColor(Color.parseColor("#B80A0C10"))
            cornerRadius = 16f
        }
        subtitleView = TextView(this).apply {
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#F8FAFC"))
            setShadowLayer(5f, 0f, 2f, Color.BLACK)
            gravity = Gravity.CENTER
            textDirection = View.TEXT_DIRECTION_RTL
            background = subBg
            setPadding(30, 12, 30, 12)
            setLineSpacing(6f, 1.0f)
            maxWidth = (screenW * 0.76).toInt()
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            alpha = 0f
            visibility = View.GONE
        }

        val subParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            baseFlags(),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = (screenH * 0.055).toInt()
        }

        windowManager.addView(subtitleView, subParams)
    }

    private fun createMenuPill(text: String): TextView {
        val bg = GradientDrawable().apply {
            setColor(Color.parseColor("#CC0E1117"))
            cornerRadius = 12f
            setStroke(1, Color.parseColor("#38FFFFFF"))
        }
        return TextView(this).apply {
            this.text = text
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#F1F5F9"))
            setShadowLayer(3f, 0f, 1f, Color.BLACK)
            gravity = Gravity.CENTER
            textDirection = View.TEXT_DIRECTION_RTL
            background = bg
            setPadding(16, 6, 16, 6)
            maxWidth = (screenW * 0.28).toInt()
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
    }

    private fun clearAll() {
        lastItemsJson = ""
        lastSingleText = ""
        subtitleView.animate().cancel()
        subtitleView.visibility = View.GONE
        subtitleView.alpha = 0f
        for (v in itemViews) {
            try { windowManager.removeView(v) } catch (_: Exception) {}
        }
        itemViews.clear()
    }

    private fun resetHideTimer() {
        mainHandler.removeCallbacks(hideRunnable)
        mainHandler.postDelayed(hideRunnable, 9000)
    }

    private fun showSingleSubtitle(raw: String) {
        val clean = raw.trim()
        mainHandler.post {
            if (clean.isEmpty()) {
                clearAll()
                return@post
            }
            if (clean == lastSingleText && subtitleView.visibility == View.VISIBLE) {
                resetHideTimer()
                return@post
            }
            clearAll()
            lastSingleText = clean
            subtitleView.text = clean
            subtitleView.alpha = 0f
            subtitleView.visibility = View.VISIBLE
            subtitleView.animate().alpha(1f).setDuration(150).start()
            resetHideTimer()
        }
    }

    private fun showPositionedItems(jsonStr: String) {
        mainHandler.post {
            if (jsonStr == lastItemsJson && itemViews.isNotEmpty()) {
                resetHideTimer()
                return@post
            }
            clearAll()
            try {
                val arr = JSONArray(jsonStr)
                val placedRects = mutableListOf<IntArray>()

                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val text = obj.optString("t", "").trim()
                    if (text.isEmpty()) continue
                    val nx = obj.optDouble("x", 0.5).coerceIn(0.02, 0.84)
                    val ny = obj.optDouble("y", 0.5).coerceIn(0.04, 0.92)

                    val px = (nx * screenW).toInt()
                    var py = ((ny - 0.036).coerceAtLeast(0.01) * screenH).toInt()

                    // منع تداخل الملصقات عمودياً إذا كانت متقاربة جداً
                    for (r in placedRects) {
                        if (kotlin.math.abs(px - r[0]) < (screenW * 0.16).toInt() &&
                            kotlin.math.abs(py - r[1]) < 42) {
                            py = (r[1] - 44).coerceAtLeast(10)
                        }
                    }
                    placedRects.add(intArrayOf(px, py))

                    val label = createMenuPill(text).apply { alpha = 0f }
                    val lp = WindowManager.LayoutParams(
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        overlayType(),
                        baseFlags(),
                        PixelFormat.TRANSLUCENT
                    ).apply {
                        gravity = Gravity.TOP or Gravity.LEFT
                        x = px
                        y = py
                    }
                    windowManager.addView(label, lp)
                    label.animate().alpha(1f).setDuration(140).start()
                    itemViews.add(label)
                }
                if (itemViews.isNotEmpty()) {
                    lastItemsJson = jsonStr
                    resetHideTimer()
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

                            var bodyText = "OK"
                            var contentType = "text/plain; charset=utf-8"

                            if (requestLine.startsWith("GET ")) {
                                val path = requestLine.split(" ").getOrNull(1) ?: ""
                                val query = path.substringAfter("?", "")
                                val paramsMap = query.split("&").mapNotNull {
                                    val idx = it.indexOf('=')
                                    if (idx > 0) {
                                        it.substring(0, idx) to URLDecoder.decode(it.substring(idx + 1), "UTF-8")
                                    } else null
                                }.toMap()

                                when {
                                    paramsMap["setconfig"] != null -> {
                                        paramsMap["mode"]?.let { currentMode = if (it == "subtitles") "subtitles" else "menus" }
                                        paramsMap["key"]?.let { savedKey = it }
                                        paramsMap["model"]?.let { if (it.isNotBlank()) savedModel = it }
                                        paramsMap["game"]?.let { if (it.isNotBlank()) savedGame = it }
                                        bodyText = "SAVED"
                                    }
                                    paramsMap["getconfig"] != null -> {
                                        val cfg = JSONObject().apply {
                                            put("mode", currentMode)
                                            put("key", savedKey)
                                            put("model", savedModel)
                                            put("game", savedGame)
                                        }
                                        bodyText = cfg.toString()
                                        contentType = "application/json; charset=utf-8"
                                    }
                                    paramsMap["setmode"] != null -> {
                                        currentMode = if (paramsMap["setmode"] == "subtitles") "subtitles" else "menus"
                                        bodyText = currentMode
                                    }
                                    paramsMap["getmode"] != null -> {
                                        bodyText = currentMode
                                    }
                                    paramsMap["keepalive"] != null -> {
                                        mainHandler.post { resetHideTimer() }
                                    }
                                    paramsMap["clear"] != null -> {
                                        mainHandler.post { clearAll() }
                                    }
                                    !paramsMap["items"].isNullOrBlank() -> {
                                        showPositionedItems(paramsMap["items"]!!)
                                    }
                                    paramsMap["text"] != null -> {
                                        showSingleSubtitle(paramsMap["text"]!!)
                                    }
                                }
                            }

                            val response = "HTTP/1.1 200 OK\r\nContent-Type: $contentType\r\nAccess-Control-Allow-Origin: *\r\n\r\n$bodyText"
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
        mainHandler.post {
            clearAll()
            try { windowManager.removeView(subtitleView) } catch (_: Exception) {}
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
