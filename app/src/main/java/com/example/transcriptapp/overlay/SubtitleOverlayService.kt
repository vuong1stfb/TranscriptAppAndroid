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
import android.view.ViewConfiguration
import android.view.WindowManager
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.text.Html
import android.text.Spanned
import com.example.transcriptapp.ScreenRecordService
import com.example.transcriptapp.R
import com.example.transcriptapp.service.translate.GoogleTranslateService
import com.example.transcriptapp.utils.RecorderLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.max

class SubtitleOverlayService : android.app.Service() {

    companion object {
        const val ACTION_SHOW_SUBTITLE = "com.example.transcriptapp.ACTION_SHOW_SUBTITLE"
        const val ACTION_HIDE_SUBTITLE = "com.example.transcriptapp.ACTION_HIDE_SUBTITLE"
        const val ACTION_UPDATE_OPTIONS = "com.example.transcriptapp.ACTION_UPDATE_TRANSCRIPT_OPTIONS"
        const val EXTRA_SUBTITLE_TEXT = "subtitle_text"
        const val EXTRA_IS_PARTIAL = "extra_is_partial"
        const val EXTRA_SHOW_ORIGINAL = "extra_show_original"
        const val EXTRA_SHOW_TRANSLATION = "extra_show_translation"
        const val EXTRA_TRANSLATE_PARTIAL = "extra_translate_partial"
        private const val TAG = "SubtitleOverlayService"
        private const val PREFS_NAME = "realtime_prefs"
    }

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var segmentsContainer: LinearLayout? = null
    private var scrollView: ScrollView? = null
    private val translateService: GoogleTranslateService = GoogleTranslateService()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val translateScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private data class Segment(
        var original: String,
        var translated: String? = null,
        var isPartial: Boolean = true,
        var lastTranslatedSource: String? = null,
        var translateJob: Job? = null
    )

    private data class SegmentView(
        val root: View,
        val originalView: TextView,
        val translatedView: TextView
    )

    private val segments = mutableListOf<Segment>()
    private val segmentViews = mutableListOf<SegmentView>()

    private var showOriginal = true
    private var showTranslation = true
    private var translatePartial = false
    private val maxSegments = 120
    private val minWidthPx by lazy { dpToPx(180f) }
    private val minHeightPx by lazy { dpToPx(28f) }
    private val maxWidthPx by lazy { (resources.displayMetrics.widthPixels * 0.98f).toInt() }
    private val maxHeightPx by lazy { (resources.displayMetrics.heightPixels * 0.98f).toInt() }

    private val subtitleReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_SHOW_SUBTITLE -> {
                    val text = intent.getStringExtra(EXTRA_SUBTITLE_TEXT) ?: return
                    val isPartial = intent.getBooleanExtra(EXTRA_IS_PARTIAL, false)
                    showSubtitle(text, isPartial)
                }
                ACTION_HIDE_SUBTITLE -> {
                    hideSubtitle()
                }
                ACTION_UPDATE_OPTIONS -> {
                    val newShowOriginal = intent.getBooleanExtra(EXTRA_SHOW_ORIGINAL, showOriginal)
                    val newShowTranslation = intent.getBooleanExtra(EXTRA_SHOW_TRANSLATION, showTranslation)
                    val newTranslatePartial = intent.getBooleanExtra(EXTRA_TRANSLATE_PARTIAL, translatePartial)
                    updateOptions(newShowOriginal, newShowTranslation, newTranslatePartial)
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
                        val isPartial = it.getBooleanExtra(EXTRA_IS_PARTIAL, false)
                        showSubtitle(text, isPartial)
                    }
                }
                ACTION_HIDE_SUBTITLE -> {
                    hideSubtitle()
                }
                ACTION_UPDATE_OPTIONS -> {
                    val newShowOriginal = it.getBooleanExtra(EXTRA_SHOW_ORIGINAL, showOriginal)
                    val newShowTranslation = it.getBooleanExtra(EXTRA_SHOW_TRANSLATION, showTranslation)
                    val newTranslatePartial = it.getBooleanExtra(EXTRA_TRANSLATE_PARTIAL, translatePartial)
                    updateOptions(newShowOriginal, newShowTranslation, newTranslatePartial)
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        hideOverlay()
        unregisterSubtitleReceiver()
        segments.forEach { it.translateJob?.cancel() }
        translateScope.coroutineContext.cancel()
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
        segmentsContainer = overlayView?.findViewById(R.id.segmentsContainer)
        scrollView = overlayView?.findViewById(R.id.scrollTranscript)
        loadOptionsFromPrefs()

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            dpToPx(220f),
            dpToPx(140f),
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            x = 0
            y = 200
        }

        overlayParams = params
        val dragHandle = overlayView?.findViewById<View>(R.id.dragHandle)
        if (dragHandle != null) {
            setupDragHandle(dragHandle, params)
        } else {
            setupDragHandle(overlayView!!, params)
        }
        setupResize(overlayView!!)

        try {
            windowManager.addView(overlayView, params)
            RecorderLogger.d(TAG, "Subtitle overlay created successfully")
        } catch (t: Throwable) {
            RecorderLogger.e(TAG, "Failed to add subtitle overlay view", t)
            stopSelf()
        }
    }

    private fun showSubtitle(text: String, isPartial: Boolean) {
        if (text.isBlank()) return
        RecorderLogger.d(TAG, "Show segment len=${text.length} partial=$isPartial")
        overlayView?.visibility = View.VISIBLE

        val index = if (isPartial) {
            if (segments.isEmpty() || !segments.last().isPartial) {
                addSegment(Segment(original = text, isPartial = true))
                segments.lastIndex
            } else {
                segments.last().original = text
                segments.lastIndex
            }
        } else {
            if (segments.isNotEmpty() && segments.last().isPartial) {
                segments.last().original = text
                segments.last().isPartial = false
                segments.lastIndex
            } else {
                addSegment(Segment(original = text, isPartial = false))
                segments.lastIndex
            }
        }

        updateSegmentView(index)
        maybeTranslateSegment(index, text, isPartial)
        trimSegmentsIfNeeded()
        scrollToBottom()
    }

    private fun hideSubtitle() {
        RecorderLogger.d(TAG, "Hiding subtitle")
        overlayView?.visibility = View.GONE
    }

    private fun hideOverlay() {
        try {
            overlayView?.let { windowManager.removeView(it) }
        } catch (_: Throwable) {}
        overlayView = null
        overlayParams = null
        segmentsContainer = null
        scrollView = null
    }

    private fun setupDragHandle(handle: View, params: WindowManager.LayoutParams) {
        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f
        var downX = 0f
        var downY = 0f
        var downTime = 0L
        var moved = false
        val touchSlop = ViewConfiguration.get(handle.context).scaledTouchSlop

        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    downX = event.rawX
                    downY = event.rawY
                    downTime = event.eventTime
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!moved) {
                        val dx = event.rawX - downX
                        val dy = event.rawY - downY
                        if (dx * dx + dy * dy > touchSlop * touchSlop) {
                            moved = true
                        }
                    }
                    params.x = initialX + (event.rawX - touchX).toInt()
                    // For BOTTOM gravity, Y coordinate is distance from bottom,
                    // so we need to invert the Y movement to fix drag direction
                    params.y = initialY - (event.rawY - touchY).toInt()
                    windowManager.updateViewLayout(overlayView, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val elapsed = event.eventTime - downTime
                    if (!moved && elapsed < 220) {
                        RecorderLogger.d(TAG, "Drag dot tap -> stop + close")
                        stopRecordingAndClose()
                        return@setOnTouchListener true
                    }
                    false
                }
                else -> false
            }
        }
    }

    private fun setupResize(view: View) {
        val handle = view.findViewById<View>(R.id.resizeHandle) ?: return
        var downX = 0f
        var downY = 0f
        var downTime = 0L
        var moved = false
        val touchSlop = ViewConfiguration.get(handle.context).scaledTouchSlop
        handle.setOnTouchListener { _, event ->
            val params = overlayParams ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    resizeStartWidth = params.width
                    resizeStartHeight = params.height
                    resizeStartX = event.rawX
                    resizeStartY = event.rawY
                    downX = event.rawX
                    downY = event.rawY
                    downTime = event.eventTime
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - resizeStartX).toInt()
                    val dy = (event.rawY - resizeStartY).toInt()
                    val newWidth = (resizeStartWidth + dx).coerceIn(minWidthPx, maxWidthPx)
                    val newHeight = (resizeStartHeight + dy).coerceIn(minHeightPx, maxHeightPx)
                    params.width = newWidth
                    params.height = newHeight
                    windowManager.updateViewLayout(overlayView, params)
                    if (!moved) {
                        val deltaX = event.rawX - downX
                        val deltaY = event.rawY - downY
                        if (deltaX * deltaX + deltaY * deltaY > touchSlop * touchSlop) {
                            moved = true
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val elapsed = event.eventTime - downTime
                    if (!moved && elapsed < 220) {
                        RecorderLogger.d(TAG, "Resize dot tap -> stop + close")
                        stopRecordingAndClose()
                        return@setOnTouchListener true
                    }
                    false
                }
                else -> false
            }
        }
    }

    private fun addSegment(segment: Segment) {
        val container = segmentsContainer ?: return
        val inflater = getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        val itemView = inflater.inflate(R.layout.transcript_segment_item, container, false)
        val originalView = itemView.findViewById<TextView>(R.id.tvSegmentOriginal)
        val translatedView = itemView.findViewById<TextView>(R.id.tvSegmentTranslated)
        val holder = SegmentView(itemView, originalView, translatedView)

        segments.add(segment)
        segmentViews.add(holder)
        container.addView(itemView)
        updateSegmentView(segments.lastIndex)
    }

    private fun updateSegmentView(index: Int) {
        if (index < 0 || index >= segments.size || index >= segmentViews.size) return
        val segment = segments[index]
        val holder = segmentViews[index]

        val decodedOriginal = decodeHtml(segment.original)
        holder.originalView.text = decodedOriginal
        holder.originalView.alpha = if (segment.isPartial) 1.0f else 1.0f
        holder.translatedView.text = decodeHtml(segment.translated ?: "")

        val showOriginalLine = showOriginal
        val showTranslatedLine = showTranslation && !segment.translated.isNullOrBlank()

        holder.originalView.visibility = if (showOriginalLine) View.VISIBLE else View.GONE
        holder.translatedView.visibility = if (showTranslatedLine) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }

    private fun refreshSegmentVisibility() {
        for (i in segments.indices) {
            updateSegmentView(i)
        }
    }

    private fun maybeTranslateSegment(index: Int, text: String, isPartial: Boolean) {
        if (!showTranslation) return
        if (isPartial && !translatePartial) return
        if (index < 0 || index >= segments.size) return

        val segment = segments[index]
        if (segment.lastTranslatedSource == text && !segment.translated.isNullOrBlank()) return

        segment.lastTranslatedSource = text
        segment.translateJob?.cancel()
        segment.translateJob = translateScope.launch {
            try {
                val needsTranslate = translateService.isTranslationNeeded(text)
                if (!needsTranslate) return@launch
                val translatedText = translateService.translateText(text, "vi", "auto") ?: return@launch
                segment.translated = translatedText
                mainHandler.post { updateSegmentView(index) }
                RecorderLogger.d(TAG, "Translated segment #$index len=${translatedText.length}")
            } catch (e: Exception) {
                RecorderLogger.e(TAG, "Translate segment #$index failed", e)
            }
        }
    }

    private fun translateMissingSegments() {
        segments.forEachIndexed { index, segment ->
            if (segment.translated.isNullOrBlank() && (!segment.isPartial || translatePartial)) {
                maybeTranslateSegment(index, segment.original, segment.isPartial)
            }
        }
    }

    private fun translateCurrentPartialIfNeeded() {
        val lastIndex = segments.lastIndex
        if (lastIndex >= 0) {
            val segment = segments[lastIndex]
            if (segment.isPartial) {
                maybeTranslateSegment(lastIndex, segment.original, true)
            }
        }
    }

    private fun trimSegmentsIfNeeded() {
        val container = segmentsContainer ?: return
        while (segments.size > maxSegments) {
            segments.removeAt(0)
            segmentViews.removeAt(0)
            try {
                container.removeViewAt(0)
            } catch (_: Throwable) {}
        }
    }

    private fun scrollToBottom() {
        val scroll = scrollView ?: return
        val doScroll = {
            val child = scroll.getChildAt(0)
            val target = if (child != null) {
                max(0, child.measuredHeight - scroll.height + scroll.paddingBottom)
            } else {
                scroll.scrollY
            }
            scroll.scrollTo(0, target)
        }
        scroll.post { doScroll() }
        scroll.postDelayed({ doScroll() }, 60)
    }

    private fun decodeHtml(text: String): Spanned {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            Html.fromHtml(text, Html.FROM_HTML_MODE_LEGACY)
        } else {
            @Suppress("DEPRECATION")
            Html.fromHtml(text)
        }
    }

    private fun dpToPx(dp: Float): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    private var resizeStartWidth = 0
    private var resizeStartHeight = 0
    private var resizeStartX = 0f
    private var resizeStartY = 0f

    private fun stopRecordingAndClose() {
        try {
            startService(Intent(this, ScreenRecordService::class.java).apply {
                action = ScreenRecordService.ACTION_STOP
            })
        } catch (t: Throwable) {
            RecorderLogger.e(TAG, "Failed to stop recording", t)
        }
        try {
            startService(Intent(this, OverlayService::class.java))
        } catch (t: Throwable) {
            RecorderLogger.e(TAG, "Failed to show control overlay", t)
        }
        stopSelf()
    }


    private fun registerSubtitleReceiver() {
        try {
            val filter = IntentFilter().apply {
                addAction(ACTION_SHOW_SUBTITLE)
                addAction(ACTION_HIDE_SUBTITLE)
                addAction(ACTION_UPDATE_OPTIONS)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(subtitleReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(subtitleReceiver, filter)
            }
            RecorderLogger.d(TAG, "Subtitle receiver registered")
        } catch (t: Throwable) {
            RecorderLogger.e(TAG, "Failed to register subtitle receiver", t)
        }
    }

    private fun loadOptionsFromPrefs() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val newShowOriginal = prefs.getBoolean(EXTRA_SHOW_ORIGINAL, true)
        val newShowTranslation = prefs.getBoolean(EXTRA_SHOW_TRANSLATION, true)
        val newTranslatePartial = prefs.getBoolean(EXTRA_TRANSLATE_PARTIAL, false)
        updateOptions(newShowOriginal, newShowTranslation, newTranslatePartial)
    }

    private fun updateOptions(
        newShowOriginal: Boolean,
        newShowTranslation: Boolean,
        newTranslatePartial: Boolean
    ) {
        showOriginal = newShowOriginal
        showTranslation = newShowTranslation
        translatePartial = newTranslatePartial
        refreshSegmentVisibility()
        if (showTranslation) {
            translateMissingSegments()
            if (translatePartial) {
                translateCurrentPartialIfNeeded()
            }
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
