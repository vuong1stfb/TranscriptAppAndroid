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
import android.widget.LinearLayout
import android.widget.Toast
import com.example.transcriptapp.ScreenRecordService
import com.example.transcriptapp.R
import com.example.transcriptapp.utils.RecorderLogger

class OverlayService : android.app.Service() {

	private lateinit var windowManager: WindowManager
	private var overlayView: View? = null

	private val stateReceiver = object : BroadcastReceiver() {
		override fun onReceive(context: Context, intent: Intent) {
			if (intent.action == ScreenRecordService.BROADCAST_STATE) {
				val state = intent.getStringExtra(ScreenRecordService.EXTRA_STATE) ?: return
				updateButtonsForState(state)
			}
		}
	}

	override fun onCreate() {
		super.onCreate()
		windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
		showOverlay()
		registerReceiverSafe()
	}

	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
		// Keep service alive; overlay is already shown in onCreate
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

		btnRecord.setOnClickListener {
			val intent = Intent(this, CapturePermissionActivity::class.java).apply {
				addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
			}
			startActivity(intent)
		}
		btnPause.setOnClickListener {
			startService(Intent(this, ScreenRecordService::class.java).apply { action = ScreenRecordService.ACTION_PAUSE })
		}
		btnResume.setOnClickListener {
			startService(Intent(this, ScreenRecordService::class.java).apply { action = ScreenRecordService.ACTION_RESUME })
		}
		btnSplit.setOnClickListener {
			startService(Intent(this, ScreenRecordService::class.java).apply { action = ScreenRecordService.ACTION_SPLIT })
		}
		btnStop.setOnClickListener {
			Toast.makeText(this, "Đã dừng ghi màn hình và đóng điều khiển", Toast.LENGTH_SHORT).show()
			startService(Intent(this, ScreenRecordService::class.java).apply { action = ScreenRecordService.ACTION_STOP })
			stopSelf()
		}
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
	}

	private fun registerReceiverSafe() {
		val filter = IntentFilter(ScreenRecordService.BROADCAST_STATE)
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

