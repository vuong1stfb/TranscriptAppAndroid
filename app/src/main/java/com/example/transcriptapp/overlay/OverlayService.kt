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
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Spinner
import android.widget.Toast
import java.util.Locale
import android.view.inputmethod.InputMethodManager
import com.example.transcriptapp.ScreenRecordService
import com.example.transcriptapp.R
import com.example.transcriptapp.utils.RecorderLogger

class OverlayService : android.app.Service() {

	private lateinit var windowManager: WindowManager
	private var overlayView: View? = null
	// Keep a reference to the LayoutParams so we can update flags at runtime
	private var overlayParams: WindowManager.LayoutParams? = null
	private var overlayVisible = false

	private val stateReceiver = object : BroadcastReceiver() {
		override fun onReceive(context: Context, intent: Intent) {
			RecorderLogger.d("OverlayService", "onReceive action=${intent.action}")
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
		// Process incoming intent updates (e.g., seconds update) and keep service alive
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

		if (overlayView != null && overlayVisible) return

		val inflater = getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
		if (overlayView == null) {
			overlayView = inflater.inflate(R.layout.overlay_controls, null)
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
				WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
				WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
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
			overlayVisible = true
		} catch (t: Throwable) {
			RecorderLogger.e("OverlayService", "Failed to add overlay view", t)
			stopSelf()
		}

		// Default state is idle
		updateButtonsForState("stopped")
	}

	private fun hideOverlay() {
		try {
			if (overlayVisible) {
				overlayView?.let { windowManager.removeView(it) }
				overlayVisible = false
			}
		} catch (_: Throwable) {}
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
		val btnStop = root.findViewById<android.widget.Button>(R.id.btnStop)
		val btnCloseOptions = root.findViewById<ImageButton>(R.id.btnCloseOptions)
		val spnLanguage = root.findViewById<Spinner>(R.id.spnLanguage)
		val etChunkMs = root.findViewById<EditText>(R.id.etChunkMs)
		val spnSampleRate = root.findViewById<Spinner>(R.id.spnSampleRate)
		val chkShowOriginal = root.findViewById<CheckBox>(R.id.chkShowOriginal)
		val chkShowTranslation = root.findViewById<CheckBox>(R.id.chkShowTranslation)
		val chkTranslatePartial = root.findViewById<CheckBox>(R.id.chkTranslatePartial)
		val chkManualCommit = root.findViewById<CheckBox>(R.id.chkManualCommit)
		val vadRow = root.findViewById<android.widget.LinearLayout>(R.id.vadRow)
		val etVadThreshold = root.findViewById<EditText>(R.id.etVadThreshold)
		val etMinSpeechMs = root.findViewById<EditText>(R.id.etMinSpeechMs)
		val etMinSilenceMs = root.findViewById<EditText>(R.id.etMinSilenceMs)
		val etVadSilenceSecs = root.findViewById<EditText>(R.id.etVadSilenceSecs)

		// Auto-split UI removed from overlay

		// NOTE: keyboard control centralized via controlKeyboard(show, target)

		btnRecord.setOnClickListener {
			RecorderLogger.d("OverlayService", "btnRecord clicked")
			val intent = Intent(this, CapturePermissionActivity::class.java).apply {
				addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
			}
			startActivity(intent)
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
		btnCloseOptions.setOnClickListener {
			RecorderLogger.d("OverlayService", "Close options clicked")
			hideOverlay()
			stopSelf()
		}

		// Options + VAD settings
		val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
		val showOriginal = prefs.getBoolean(SubtitleOverlayService.EXTRA_SHOW_ORIGINAL, true)
		val showTranslation = prefs.getBoolean(SubtitleOverlayService.EXTRA_SHOW_TRANSLATION, true)
		val translatePartial = prefs.getBoolean(SubtitleOverlayService.EXTRA_TRANSLATE_PARTIAL, false)
		val commitStrategy = prefs.getString(KEY_COMMIT_STRATEGY, DEFAULT_COMMIT_STRATEGY) ?: DEFAULT_COMMIT_STRATEGY
		val manualCommit = commitStrategy == "manual"
		val languageCode = prefs.getString(KEY_LANGUAGE_CODE, "") ?: ""
		val chunkMs = prefs.getInt(KEY_CHUNK_MS, DEFAULT_CHUNK_MS)
		val sampleRate = prefs.getInt(KEY_SAMPLE_RATE, DEFAULT_SAMPLE_RATE)
		val vadThreshold = prefs.getFloat(KEY_VAD_THRESHOLD, DEFAULT_VAD_THRESHOLD)
		val minSpeechMs = prefs.getInt(KEY_MIN_SPEECH_MS, DEFAULT_MIN_SPEECH_MS)
		val minSilenceMs = prefs.getInt(KEY_MIN_SILENCE_MS, DEFAULT_MIN_SILENCE_MS)
		val vadSilenceSecs = prefs.getFloat(KEY_VAD_SILENCE_SECS, DEFAULT_VAD_SILENCE_SECS)

		chkShowOriginal.isChecked = showOriginal
		chkShowTranslation.isChecked = showTranslation
		chkTranslatePartial.isChecked = translatePartial
		chkManualCommit.isChecked = manualCommit
		etChunkMs.setText(chunkMs.toString())

		broadcastOptions(showOriginal, showTranslation, translatePartial)

		vadRow.visibility = if (manualCommit) View.GONE else View.VISIBLE

		val languageOptions = listOf(
			"" to "None",
			"vi" to "VI",
			"en" to "EN",
			"ko" to "KO",
			"ja" to "JA",
			"zh" to "ZH"
		)
		val adapter = ArrayAdapter(
			root.context,
			R.layout.spinner_item,
			languageOptions.map { it.second }
		).apply {
			setDropDownViewResource(R.layout.spinner_dropdown_item)
		}
		spnLanguage.adapter = adapter
		val selectedIndex = languageOptions.indexOfFirst { it.first == languageCode }.takeIf { it >= 0 } ?: 0
		spnLanguage.setSelection(selectedIndex, false)
		RecorderLogger.d("OverlayService", "language_code current=$languageCode")
		spnLanguage.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
			override fun onItemSelected(
				parent: android.widget.AdapterView<*>,
				view: android.view.View?,
				position: Int,
				id: Long
			) {
				val code = languageOptions.getOrNull(position)?.first ?: ""
				prefs.edit().putString(KEY_LANGUAGE_CODE, code).apply()
				RecorderLogger.d("OverlayService", "language_code=$code (apply on next start)")
			}

			override fun onNothingSelected(parent: android.widget.AdapterView<*>) = Unit
		}

		setupVadEdit(etChunkMs) {
			val value = parseInt(it, DEFAULT_CHUNK_MS).coerceIn(200, 10000)
			prefs.edit().putInt(KEY_CHUNK_MS, value).apply()
			etChunkMs.setText(value.toString())
			RecorderLogger.d("OverlayService", "chunk_ms=$value (apply on next start)")
		}

		val sampleRateOptions = listOf(16000, 22050, 24000)
		val safeSampleRate = if (sampleRateOptions.contains(sampleRate)) sampleRate else DEFAULT_SAMPLE_RATE
		if (safeSampleRate != sampleRate) {
			prefs.edit().putInt(KEY_SAMPLE_RATE, safeSampleRate).apply()
		}
		val sampleAdapter = ArrayAdapter(
			root.context,
			R.layout.spinner_item,
			sampleRateOptions.map { "pcm_$it" }
		).apply {
			setDropDownViewResource(R.layout.spinner_dropdown_item)
		}
		spnSampleRate.adapter = sampleAdapter
		val sampleIndex = sampleRateOptions.indexOf(safeSampleRate).takeIf { it >= 0 } ?: sampleRateOptions.size - 1
		spnSampleRate.setSelection(sampleIndex, false)
		spnSampleRate.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
			override fun onItemSelected(
				parent: android.widget.AdapterView<*>,
				view: android.view.View?,
				position: Int,
				id: Long
			) {
				val rate = sampleRateOptions.getOrNull(position) ?: DEFAULT_SAMPLE_RATE
				prefs.edit().putInt(KEY_SAMPLE_RATE, rate).apply()
				RecorderLogger.d("OverlayService", "sample_rate=$rate (apply on next start)")
			}

			override fun onNothingSelected(parent: android.widget.AdapterView<*>) = Unit
		}

		etVadThreshold.setText(String.format(Locale.US, "%.2f", vadThreshold))
		etMinSpeechMs.setText(minSpeechMs.toString())
		etMinSilenceMs.setText(minSilenceMs.toString())
		etVadSilenceSecs.setText(String.format(Locale.US, "%.2f", vadSilenceSecs))

		chkShowOriginal.setOnCheckedChangeListener { _, checked ->
			prefs.edit().putBoolean(SubtitleOverlayService.EXTRA_SHOW_ORIGINAL, checked).apply()
			broadcastOptions(checked, chkShowTranslation.isChecked, chkTranslatePartial.isChecked)
		}
		chkShowTranslation.setOnCheckedChangeListener { _, checked ->
			prefs.edit().putBoolean(SubtitleOverlayService.EXTRA_SHOW_TRANSLATION, checked).apply()
			broadcastOptions(chkShowOriginal.isChecked, checked, chkTranslatePartial.isChecked)
		}
		chkTranslatePartial.setOnCheckedChangeListener { _, checked ->
			prefs.edit().putBoolean(SubtitleOverlayService.EXTRA_TRANSLATE_PARTIAL, checked).apply()
			broadcastOptions(chkShowOriginal.isChecked, chkShowTranslation.isChecked, checked)
		}
		chkManualCommit.setOnCheckedChangeListener { _, checked ->
			val nextStrategy = if (checked) "manual" else "vad"
			prefs.edit().putString(KEY_COMMIT_STRATEGY, nextStrategy).apply()
			vadRow.visibility = if (checked) View.GONE else View.VISIBLE
			RecorderLogger.d("OverlayService", "commit_strategy=$nextStrategy (apply on next start)")
		}

		setupVadEdit(etVadThreshold) {
			val value = parseFloat(it, DEFAULT_VAD_THRESHOLD).coerceIn(0.0f, 1.0f)
			prefs.edit().putFloat(KEY_VAD_THRESHOLD, value).apply()
			etVadThreshold.setText(String.format(Locale.US, "%.2f", value))
			broadcastVadUpdated()
		}
		setupVadEdit(etVadSilenceSecs) {
			val value = parseFloat(it, DEFAULT_VAD_SILENCE_SECS).coerceIn(0.05f, 2.0f)
			prefs.edit().putFloat(KEY_VAD_SILENCE_SECS, value).apply()
			etVadSilenceSecs.setText(String.format(Locale.US, "%.2f", value))
			broadcastVadUpdated()
		}
		setupVadEdit(etMinSpeechMs) {
			val value = parseInt(it, DEFAULT_MIN_SPEECH_MS).coerceIn(20, 2000)
			prefs.edit().putInt(KEY_MIN_SPEECH_MS, value).apply()
			etMinSpeechMs.setText(value.toString())
			broadcastVadUpdated()
		}
		setupVadEdit(etMinSilenceMs) {
			val value = parseInt(it, DEFAULT_MIN_SILENCE_MS).coerceIn(20, 5000)
			prefs.edit().putInt(KEY_MIN_SILENCE_MS, value).apply()
			etMinSilenceMs.setText(value.toString())
			broadcastVadUpdated()
		}
	}

	private fun updateButtonsForState(state: String) {
		val root = overlayView ?: return
		val btnRecord = root.findViewById<Button>(R.id.btnRecord)
		val btnStop = root.findViewById<Button>(R.id.btnStop)
		val tvState = root.findViewById<android.widget.TextView>(R.id.tvState)

		// Cập nhật text trạng thái
		tvState.text = when (state) {
			"recording" -> "State: Recording"
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
		setButtonState(btnStop, state == "recording", android.graphics.Color.parseColor("#D32F2F")) // holo_red_dark

		if (state == "recording") {
			hideOverlay()
		} else {
			showOverlay()
		}
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

	private fun setupVadEdit(editText: EditText, onCommit: (String) -> Unit) {
		editText.setOnClickListener {
			controlKeyboard(true, editText)
		}
		editText.setOnFocusChangeListener { _, hasFocus ->
			if (hasFocus) {
				controlKeyboard(true, editText)
			} else {
				controlKeyboard(false, editText)
				onCommit(editText.text?.toString() ?: "")
			}
		}
	}

	private fun parseFloat(value: String, fallback: Float): Float {
		return value.toFloatOrNull() ?: fallback
	}

	private fun parseInt(value: String, fallback: Int): Int {
		return value.toIntOrNull() ?: fallback
	}

	private fun broadcastOptions(showOriginal: Boolean, showTranslation: Boolean, translatePartial: Boolean) {
		val intent = Intent(SubtitleOverlayService.ACTION_UPDATE_OPTIONS).apply {
			putExtra(SubtitleOverlayService.EXTRA_SHOW_ORIGINAL, showOriginal)
			putExtra(SubtitleOverlayService.EXTRA_SHOW_TRANSLATION, showTranslation)
			putExtra(SubtitleOverlayService.EXTRA_TRANSLATE_PARTIAL, translatePartial)
		}
		sendBroadcast(intent)
		startService(Intent(this, SubtitleOverlayService::class.java).apply {
			action = SubtitleOverlayService.ACTION_UPDATE_OPTIONS
			putExtra(SubtitleOverlayService.EXTRA_SHOW_ORIGINAL, showOriginal)
			putExtra(SubtitleOverlayService.EXTRA_SHOW_TRANSLATION, showTranslation)
			putExtra(SubtitleOverlayService.EXTRA_TRANSLATE_PARTIAL, translatePartial)
		})
	}

	private fun broadcastVadUpdated() {
		RecorderLogger.d("OverlayService", "VAD updated (will apply on next start)")
	}

	companion object {
		private const val PREFS_NAME = "realtime_prefs"
		private const val KEY_COMMIT_STRATEGY = "commit_strategy"
		private const val KEY_LANGUAGE_CODE = "language_code"
		private const val DEFAULT_COMMIT_STRATEGY = "vad"
		private const val KEY_CHUNK_MS = "chunk_ms"
		private const val DEFAULT_CHUNK_MS = 1000
		private const val KEY_SAMPLE_RATE = "sample_rate"
		private const val DEFAULT_SAMPLE_RATE = 24000
		private const val KEY_VAD_THRESHOLD = "vad_threshold"
		private const val KEY_MIN_SPEECH_MS = "min_speech_duration_ms"
		private const val KEY_MIN_SILENCE_MS = "min_silence_duration_ms"
		private const val KEY_VAD_SILENCE_SECS = "vad_silence_threshold_secs"

		private const val DEFAULT_VAD_THRESHOLD = 0.7f
		private const val DEFAULT_MIN_SPEECH_MS = 60
		private const val DEFAULT_MIN_SILENCE_MS = 120
		private const val DEFAULT_VAD_SILENCE_SECS = 0.3f
	}
}
