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
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.LinearLayout
import android.widget.Toast
import android.text.InputType
import android.view.inputmethod.InputMethodManager
import com.example.transcriptapp.ScreenRecordService
import com.example.transcriptapp.R
import com.example.transcriptapp.utils.RecorderLogger

class OverlayService : android.app.Service() {

	private lateinit var windowManager: WindowManager
	private var overlayView: View? = null
	// Keep a reference to the LayoutParams so we can update flags at runtime
	private var overlayParams: WindowManager.LayoutParams? = null

	private val stateReceiver = object : BroadcastReceiver() {
		override fun onReceive(context: Context, intent: Intent) {
			RecorderLogger.d("OverlayService", "onReceive action=${intent.action}")
			if (intent.action == ScreenRecordService.BROADCAST_STATE) {
				val state = intent.getStringExtra(ScreenRecordService.EXTRA_STATE) ?: return
				updateButtonsForState(state)
			}
			// handle dialog result for split seconds
			if (intent.action == DialogSecondsActivity.ACTION_SET_SPLIT_SECONDS) {
				val secs = intent.getIntExtra(DialogSecondsActivity.EXTRA_SECONDS, 0)
				RecorderLogger.d("OverlayService", "Received ACTION_SET_SPLIT_SECONDS: $secs")
				// update configured seconds and enable/disable auto-split depending on value
				autoSplitSeconds = secs
				autoSplitEnabled = secs > 0
				if (autoSplitEnabled) startAutoSplitIfNeeded(secs.toString()) else stopAutoSplit()
			}
			// ACTION_SET_AUTO_SPLIT handling removed - activity no longer broadcasts auto-split toggle
		}
	}

	// Auto-split scheduler
	private var autoSplitEnabled = false
	private var autoSplitSeconds = 0
	private var scheduler: java.util.concurrent.ScheduledExecutorService? = null
	private var scheduledFuture: java.util.concurrent.ScheduledFuture<*>? = null

	override fun onCreate() {
		super.onCreate()
		windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
		showOverlay()
		registerReceiverSafe()
	}

	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
		// Process incoming intent updates (e.g., seconds update) and keep service alive
		try {
			if (intent?.action == DialogSecondsActivity.ACTION_SET_SPLIT_SECONDS) {
				val secs = intent.getIntExtra(DialogSecondsActivity.EXTRA_SECONDS, 0)
				RecorderLogger.d("OverlayService", "onStartCommand received ACTION_SET_SPLIT_SECONDS: $secs")
				autoSplitSeconds = secs
				autoSplitEnabled = secs > 0
				if (autoSplitEnabled) startAutoSplitIfNeeded(secs.toString()) else stopAutoSplit()
			}
		} catch (t: Throwable) {
			RecorderLogger.e("OverlayService", "onStartCommand failed to process intent", t)
		}
		return START_STICKY
	}

	override fun onDestroy() {
		super.onDestroy()
		hideOverlay()
		unregisterReceiverSafe()
	}

	override fun onBind(intent: Intent?): IBinder? = null

	private fun showOverlay() {
		if (!Settings.canDrawOverlays(this)) {
			Toast.makeText(this, "Cần cấp quyền hiển thị trên ứng dụng khác", Toast.LENGTH_LONG).show()
			val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")).apply {
				addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
			}
			startActivity(intent)
			stopSelf()
			return
		}

		if (overlayView != null) return

		val inflater = getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
		overlayView = inflater.inflate(R.layout.overlay_controls, null)

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
			gravity = Gravity.TOP or Gravity.START
			x = 40
			y = 140
		}

		// store params so other methods can update flags at runtime
		overlayParams = params

		setupDrag(overlayView!!, params)
		setupButtons(overlayView!!)

		try {
			windowManager.addView(overlayView, params)
		} catch (t: Throwable) {
			RecorderLogger.e("OverlayService", "Failed to add overlay view", t)
			stopSelf()
		}

		// Default state is idle
		updateButtonsForState("stopped")
	}

	private fun hideOverlay() {
		try {
			overlayView?.let { windowManager.removeView(it) }
		} catch (_: Throwable) {}
		overlayView = null
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
					params.y = initialY + (event.rawY - touchY).toInt()
					windowManager.updateViewLayout(overlayView, params)
					true
				}
				else -> false
			}
		}
	}

	private fun setupButtons(root: View) {
		val btnRecord = root.findViewById<android.widget.Button>(R.id.btnRecord)
		val btnPause = root.findViewById<android.widget.Button>(R.id.btnPause)
		val btnResume = root.findViewById<android.widget.Button>(R.id.btnResume)
		val btnSplit = root.findViewById<android.widget.Button>(R.id.btnSplit)
		val btnStop = root.findViewById<android.widget.Button>(R.id.btnStop)

	// Auto-split UI removed from overlay; we only track autoSplitSeconds in service state

		// NOTE: keyboard control centralized via controlKeyboard(show, target)

		btnRecord.setOnClickListener {
			RecorderLogger.d("OverlayService", "btnRecord clicked")
			val intent = Intent(this, CapturePermissionActivity::class.java).apply {
				addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
			}
			startActivity(intent)
		}
		btnPause.setOnClickListener {
			RecorderLogger.d("OverlayService", "btnPause clicked")
			startService(Intent(this, ScreenRecordService::class.java).apply { action = ScreenRecordService.ACTION_PAUSE })
		}
		btnResume.setOnClickListener {
			RecorderLogger.d("OverlayService", "btnResume clicked")
			startService(Intent(this, ScreenRecordService::class.java).apply { action = ScreenRecordService.ACTION_RESUME })
		}
		btnSplit.setOnClickListener {
			RecorderLogger.d("OverlayService", "btnSplit clicked")
			startService(Intent(this, ScreenRecordService::class.java).apply { action = ScreenRecordService.ACTION_SPLIT })
		}
		btnStop.setOnClickListener {
			RecorderLogger.d("OverlayService", "btnStop clicked: hiding IME and restoring overlay flags")
			try {
				// central keyboard hide + restore focus behavior (no input target in overlay)
				controlKeyboard(false, null)
			} catch (t: Throwable) {
				RecorderLogger.e("OverlayService", "Error hiding IME on stop", t)
			}
			Toast.makeText(this, "Đã dừng ghi màn hình và đóng điều khiển", Toast.LENGTH_SHORT).show()
			startService(Intent(this, ScreenRecordService::class.java).apply { action = ScreenRecordService.ACTION_STOP })
			stopSelf()
		}

		// No auto-split UI in overlay; autoSplitSeconds managed by service state and broadcasts
	}

	private fun updateButtonsForState(state: String) {
		val root = overlayView ?: return
		val btnRecord = root.findViewById<Button>(R.id.btnRecord)
		val btnPause = root.findViewById<Button>(R.id.btnPause)
		val btnResume = root.findViewById<Button>(R.id.btnResume)
		val btnSplit = root.findViewById<Button>(R.id.btnSplit)
		val btnStop = root.findViewById<Button>(R.id.btnStop)
		val tvState = root.findViewById<android.widget.TextView>(R.id.tvState)

		// Cập nhật text trạng thái
		tvState.text = when (state) {
			"recording" -> "State: Recording"
			"paused" -> "State: Paused"
			else -> "State: Stopped"
		}

		// Logic enable/disable cho nút và đổi màu nền khi disable
		fun setButtonState(btn: Button, enabled: Boolean, color: Int) {
			btn.isEnabled = enabled
			btn.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
				if (enabled) color else android.graphics.Color.LTGRAY
			))
		}

		setButtonState(btnRecord, state == "stopped", android.graphics.Color.parseColor("#388E3C")) // holo_green_dark
		setButtonState(btnPause, state == "recording", android.graphics.Color.parseColor("#F57C00")) // holo_orange_dark
		setButtonState(btnResume, state == "paused", android.graphics.Color.parseColor("#1976D2")) // holo_blue_dark
		setButtonState(btnSplit, state == "recording", android.graphics.Color.parseColor("#8E24AA")) // holo_purple
		setButtonState(btnStop, state == "recording" || state == "paused", android.graphics.Color.parseColor("#D32F2F")) // holo_red_dark

		// Start or stop auto-split based on recording state
		if (state == "recording" && autoSplitEnabled) {
			// only start auto-split if a positive seconds value is configured
			startAutoSplitIfNeeded(autoSplitSeconds.toString())
		} else {
			stopAutoSplit()
		}
	}

	private fun startAutoSplitIfNeeded(secondsText: String?) {
		val secs = secondsText?.toIntOrNull() ?: autoSplitSeconds
		RecorderLogger.d("OverlayService", "startAutoSplitIfNeeded called with secondsText='$secondsText' resolved secs=$secs autoSplitEnabled=$autoSplitEnabled")
		if (secs <= 0) {
			RecorderLogger.d("OverlayService", "startAutoSplitIfNeeded: secs <= 0, will not schedule auto-split")
			return
		}
		autoSplitSeconds = secs
		if (scheduler == null) scheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()
		scheduledFuture?.cancel(false)
		// scheduleAtFixedRate will run every `secs` seconds and send ACTION_SPLIT
		scheduledFuture = scheduler?.scheduleAtFixedRate({
			try {
				val intent = Intent(this, ScreenRecordService::class.java).apply { action = ScreenRecordService.ACTION_SPLIT }
				startService(intent)
				RecorderLogger.d("OverlayService", "Auto-split triggered")
			} catch (t: Throwable) {
				RecorderLogger.e("OverlayService", "Auto-split failed to send split", t)
			}
		}, secs.toLong(), secs.toLong(), java.util.concurrent.TimeUnit.SECONDS)
		RecorderLogger.d("OverlayService", "Scheduled auto-split every ${secs}s")
	}

	private fun stopAutoSplit() {
		scheduledFuture?.cancel(false)
		scheduledFuture = null
		try { scheduler?.shutdownNow() } catch (_: Throwable) {}
		scheduler = null
	}

	// Temporarily make the overlay focusable so EditText can receive IME input
	private fun makeOverlayFocusable() {
		try {
			val params = overlayParams ?: run {
				RecorderLogger.d("OverlayService", "makeOverlayFocusable: overlayParams is null")
				return
			}
			if (overlayView == null) {
				RecorderLogger.d("OverlayService", "makeOverlayFocusable: overlayView is null")
				return
			}
			RecorderLogger.d("OverlayService", "Current flags before makeFocusable=${params.flags}")
			// remove NOT_FOCUSABLE so the window can take focus
			params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
			// ensure soft input mode requests are visible
			params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
			try {
				windowManager.updateViewLayout(overlayView, params)
				RecorderLogger.d("OverlayService", "updateViewLayout called to make overlay focusable; new flags=${params.flags}")
			} catch (t: Throwable) {
				RecorderLogger.e("OverlayService", "Failed updateViewLayout in makeOverlayFocusable", t)
			}
		} catch (t: Throwable) {
			RecorderLogger.e("OverlayService", "Failed to make overlay focusable", t)
		}
	}

	private fun makeOverlayNotFocusable() {
		try {
			val params = overlayParams ?: run {
				RecorderLogger.d("OverlayService", "makeOverlayNotFocusable: overlayParams is null")
				return
			}
			if (overlayView == null) {
				RecorderLogger.d("OverlayService", "makeOverlayNotFocusable: overlayView is null")
				return
			}
			RecorderLogger.d("OverlayService", "Restoring NOT_FOCUSABLE; current flags=${params.flags}")
			// restore NOT_FOCUSABLE and clear any softInputMode override
			params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
			params.softInputMode = 0
			try {
				windowManager.updateViewLayout(overlayView, params)
				RecorderLogger.d("OverlayService", "updateViewLayout called to restore NOT_FOCUSABLE; new flags=${params.flags}")
			} catch (t: Throwable) {
				RecorderLogger.e("OverlayService", "Failed updateViewLayout in makeOverlayNotFocusable", t)
			}
			// No overlay input to clear focus for (auto-split UI removed)
		} catch (t: Throwable) {
			RecorderLogger.e("OverlayService", "Failed to restore overlay not-focusable", t)
		}
	}

	/**
	 * Centralized keyboard control for overlay EditText.
	 * If show==true: make overlay focusable, request focus on target and show IME.
	 * If show==false: hide IME, clear focus and restore overlay to not-focusable.
	 */
	private fun controlKeyboard(show: Boolean, target: View?) {
		try {
			val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
			if (show) {
				RecorderLogger.d("OverlayService", "controlKeyboard: show requested")
				makeOverlayFocusable()
				// small delay for flags to update
				target?.postDelayed({
					try {
						target.requestFocus()
						imm?.showSoftInput(target, InputMethodManager.SHOW_IMPLICIT)
						RecorderLogger.d("OverlayService", "controlKeyboard: showSoftInput called")
					} catch (t: Throwable) {
						RecorderLogger.e("OverlayService", "controlKeyboard: showSoftInput failed", t)
					}
				}, 120)
			} else {
				RecorderLogger.d("OverlayService", "controlKeyboard: hide requested")
				try {
					target?.clearFocus()
					imm?.hideSoftInputFromWindow(target?.windowToken, 0)
				} catch (t: Throwable) {
					RecorderLogger.e("OverlayService", "controlKeyboard: hide failed", t)
				}
				// delay restore to let IME animation finish
				target?.postDelayed({ makeOverlayNotFocusable() }, 120)
			}
		} catch (t: Throwable) {
			RecorderLogger.e("OverlayService", "controlKeyboard: unexpected error", t)
		}
	}

	private fun registerReceiverSafe() {
		val filter = IntentFilter().apply {
			addAction(ScreenRecordService.BROADCAST_STATE)
			addAction(DialogSecondsActivity.ACTION_SET_SPLIT_SECONDS)
		}
		try {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
				registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
			} else {
				@Suppress("DEPRECATION")
				registerReceiver(stateReceiver, filter)
			}
		} catch (t: Throwable) {
			RecorderLogger.e("OverlayService", "Failed to register state receiver", t)
		}
	}

	private fun unregisterReceiverSafe() {
		try {
			unregisterReceiver(stateReceiver)
		} catch (_: Throwable) {}
	}
}

