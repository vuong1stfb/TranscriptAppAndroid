package com.example.transcriptapp.overlay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import com.example.transcriptapp.R
import com.example.transcriptapp.utils.RecorderLogger

class SubtitleOverlayService : android.app.Service() {

    companion object {
        const val ACTION_SHOW_SUBTITLE = "com.example.transcriptapp.ACTION_SHOW_SUBTITLE"
        const val ACTION_HIDE_SUBTITLE = "com.example.transcriptapp.ACTION_HIDE_SUBTITLE"
        const val EXTRA_SUBTITLE_TEXT = "subtitle_text"
        private const val TAG = "SubtitleOverlayService"
    }

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private var subtitleTextView: TextView? = null

    private val subtitleReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_SHOW_SUBTITLE -> {
                    val text = intent.getStringExtra(EXTRA_SUBTITLE_TEXT) ?: return
                    showSubtitle(text)
                }
                ACTION_HIDE_SUBTITLE -> {
                    hideSubtitle()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createOverlay()
        registerSubtitleReceiver()
        RecorderLogger.d(TAG, "SubtitleOverlayService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Handle subtitle actions from intent
        intent?.let {
            when (it.action) {
                ACTION_SHOW_SUBTITLE -> {
                    val text = it.getStringExtra(EXTRA_SUBTITLE_TEXT)
                    if (!text.isNullOrEmpty()) {
                        showSubtitle(text)
                    }
                }
                ACTION_HIDE_SUBTITLE -> {
                    hideSubtitle()
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        hideOverlay()
        unregisterSubtitleReceiver()
        RecorderLogger.d(TAG, "SubtitleOverlayService destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            RecorderLogger.w(TAG, "Cannot create subtitle overlay: No overlay permission")
            Toast.makeText(this, "Cần cấp quyền hiển thị trên ứng dụng khác cho subtitle", Toast.LENGTH_LONG).show()
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            stopSelf()
            return
        }

        if (overlayView != null) return

        val inflater = getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        overlayView = inflater.inflate(R.layout.subtitle_overlay, null)
        subtitleTextView = overlayView?.findViewById(R.id.tvSubtitle)

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
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            x = 0
            y = 200
        }

        setupDrag(overlayView!!, params)

        try {
            windowManager.addView(overlayView, params)
            RecorderLogger.d(TAG, "Subtitle overlay created successfully")
        } catch (t: Throwable) {
            RecorderLogger.e(TAG, "Failed to add subtitle overlay view", t)
            stopSelf()
        }
    }

    private fun showSubtitle(text: String) {
        RecorderLogger.d(TAG, "Showing subtitle: $text")
        
        // TODO: Future Google Translate integration
        // Add translation logic here before displaying:
        // val translatedText = GoogleTranslateService.translate(text, targetLanguage)
        // val displayText = translatedText ?: text
        
        subtitleTextView?.apply {
            this.text = text
            visibility = View.VISIBLE
        }
    }

    private fun hideSubtitle() {
        RecorderLogger.d(TAG, "Hiding subtitle")
        subtitleTextView?.visibility = View.GONE
    }

    private fun hideOverlay() {
        try {
            overlayView?.let { windowManager.removeView(it) }
        } catch (_: Throwable) {}
        overlayView = null
        subtitleTextView = null
    }

    private fun setupDrag(view: View, params: WindowManager.LayoutParams) {
        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f
        
        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - touchX).toInt()
                    // For BOTTOM gravity, Y coordinate is distance from bottom,
                    // so we need to invert the Y movement to fix drag direction
                    params.y = initialY - (event.rawY - touchY).toInt()
                    windowManager.updateViewLayout(overlayView, params)
                    true
                }
                else -> false
            }
        }
    }

    private fun registerSubtitleReceiver() {
        try {
            val filter = IntentFilter().apply {
                addAction(ACTION_SHOW_SUBTITLE)
                addAction(ACTION_HIDE_SUBTITLE)
            }
            registerReceiver(subtitleReceiver, filter)
            RecorderLogger.d(TAG, "Subtitle receiver registered")
        } catch (t: Throwable) {
            RecorderLogger.e(TAG, "Failed to register subtitle receiver", t)
        }
    }

    private fun unregisterSubtitleReceiver() {
        try {
            unregisterReceiver(subtitleReceiver)
            RecorderLogger.d(TAG, "Subtitle receiver unregistered")
        } catch (t: Throwable) {
            RecorderLogger.e(TAG, "Failed to unregister subtitle receiver", t)
        }
    }
}