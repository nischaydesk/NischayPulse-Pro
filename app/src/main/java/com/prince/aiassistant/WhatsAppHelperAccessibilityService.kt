package com.prince.aiassistant

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.MediaStore
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.telecom.TelecomManager
import android.text.InputType
import android.util.Base64
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs

class WhatsAppHelperAccessibilityService : AccessibilityService(), SensorEventListener {

    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private val manager = AiAssistantManager()

    private var tts: TextToSpeech? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private var isTtsSpeaking = false
    private var wakeLock: PowerManager.WakeLock? = null

    private var windowManager: WindowManager? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var rootContainer: LinearLayout? = null
    private var proOrbView: ProAiOrbView? = null
    private var dismissZoneView: TextView? = null
    private var isOverDismissZone = false

    private var miniCornerPill: TextView? = null
    private var isMinimizedToPill = false

    private var subtitleCard: LinearLayout? = null
    private var userSubText: TextView? = null
    private var aiSubText: TextView? = null
    private var treeTimelineContainer: LinearLayout? = null

    private var quickReplyContainer: LinearLayout? = null
    private var quickReplyInput: EditText? = null
    private var quickReplyRunBtn: TextView? = null

    private val subTaskQueue = mutableListOf<AiAssistantManager.SubTask>()
    private val completedTasks = mutableListOf<String>()
    private val completedMilestones = mutableListOf<String>()
    private val extractedNotes = mutableListOf<String>()
    private val blockedActionSignatures = mutableListOf<String>()
    private val completedActionLabels = mutableSetOf<String>()
    private var activeSubTaskIndex = 0
    private var stepsInCurrentSubTask = 0
    private var realActionsCount = 0
    private var overallUserGoal = ""
    private var currentTaskDoneReply = "काम पूरा हो गया है।"

    // Precision Reel / Shorts Auto-Scroll Engine (Package-Locked)
    private var isReelAutoScrollActive = false
    private var reelTargetPackage = "com.instagram.android"
    private var reelScrollIntervalMs = 15000L
    private var reelEndTimeMs = 0L
    private var autoLikeEveryReel = false
    private var cachedWebHint = ""

    private var pendingFormQuestion: String? = null

    private var isAgentRunning = false
    private var isThinking = false
    private var totalSteps = 0
    private var retryCount = 0
    private var lastScreenHash = 0
    private var lastActionSignature = ""
    private var repeatedActionCount = 0
    private var staleScreenStreak = 0
    private var staleScrollDownCount = 0
    private var staleScrollUpCount = 0
    private var isBottomLocked = false
    private var hasSentFinalMessage = false
    private var lastWaitSpokenTime = 0L
    private val subTaskHistory = mutableListOf<String>()
    private val indexedNodes = mutableListOf<AccessibilityNodeInfo>()

    private var sensorManager: SensorManager? = null
    private var proximitySensor: Sensor? = null
    private var isWaveCutActive = false
    private var wasNear = false
    private var waveCount = 0
    private var firstWaveTime = 0L

    private val backgroundWatchdog = object : Runnable {
        override fun run() {
            if (!isAgentRunning && !isListening && !isReelAutoScrollActive) {
                checkExternalTrigger()
            }
            handler.postDelayed(this, 450L)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as? WindowManager
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        proximitySensor = sensorManager?.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        syncSavedProfileToMemory()
        postKeepAliveNotification()

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                selectBestHumanHindiVoice()
                val speed = getSharedPreferences("assistant_settings", MODE_PRIVATE).getFloat("tts_speed", 1.02f)
                tts?.setSpeechRate(speed)
                tts?.setPitch(0.96f)
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        isTtsSpeaking = true
                    }
                    override fun onDone(utteranceId: String?) {
                        isTtsSpeaking = false
                        if (utteranceId == "ask_form_q" && pendingFormQuestion != null) {
                            handler.postDelayed({
                                if (pendingFormQuestion != null && !isTtsSpeaking && quickReplyInput?.hasFocus() != true) {
                                    startSiriVoiceListening(true)
                                }
                            }, 280L)
                        }
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        isTtsSpeaking = false
                    }
                })
            }
        }
        val orbEnabled = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_ORB_ENABLED, true)
        if (orbEnabled) {
            createProFloatingOrb()
        }
        handler.removeCallbacks(backgroundWatchdog)
        handler.post(backgroundWatchdog)
    }

    private fun logHistoryEntry(goal: String, summary: String, success: Boolean) {
        if (goal.isBlank()) return
        try {
            val prefs = getSharedPreferences("nischay_task_history", MODE_PRIVATE)
            val arr = try { JSONArray(prefs.getString("logs", "[]")) } catch (_: Exception) { JSONArray() }
            val timeStr = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()).format(Date())
            val obj = JSONObject()
                .put("goal", goal)
                .put("result", summary)
                .put("time", timeStr)
                .put("success", success)
            val newArr = JSONArray().put(obj)
            for (i in 0 until minOf(39, arr.length())) {
                newArr.put(arr.getJSONObject(i))
            }
            prefs.edit().putString("logs", newArr.toString()).apply()
        } catch (_: Exception) { }
    }

    private fun postKeepAliveNotification() {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val ch = NotificationChannel("nischay_pulse_engine", "Nischay Pulse Engine", NotificationManager.IMPORTANCE_LOW)
                nm.createNotificationChannel(ch)
            }
            val notif = NotificationCompat.Builder(this, "nischay_pulse_engine")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Nischay Pulse सक्रिय है")
                .setContentText("ऑटोनॉमस इंजन बैकग्राउंड में तैयार है")
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
            nm.notify(9091, notif)
        } catch (_: Exception) { }
    }

    private fun acquireAgentWakeLock() {
        try {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NischayPulse::AgentLock")
            }
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(20 * 60 * 1000L)
            }
        } catch (_: Exception) { }
    }

    private fun releaseAgentWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) { }
    }

    private fun selectBestHumanHindiVoice() {
        val engine = tts ?: return
        engine.language = Locale("hi", "IN")
        try {
            val allVoices = engine.voices ?: return
            val hindiVoices = allVoices.filter {
                it.locale.language.equals("hi", true) &&
                    !it.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
            }
            val best = hindiVoices
                .sortedWith(
                    compareByDescending<Voice> { it.quality }
                        .thenByDescending { it.name.contains("network", true) || it.name.contains("neural", true) }
                        .thenByDescending { it.name.contains("hid", true) || it.name.contains("hie", true) }
                )
                .firstOrNull()
            if (best != null) engine.voice = best
        } catch (_: Exception) { }
    }

    private fun humanizeHindiSpeech(rawText: String): String {
        return rawText
            .replace("...", "… ")
            .replace("—", ", ")
            .replace(" - ", ", ")
            .trim()
    }

    private fun getPhysicalScreenSize(): Pair<Int, Int> {
        val wm = windowManager ?: getSystemService(WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            Pair(bounds.width().coerceAtLeast(1), bounds.height().coerceAtLeast(1))
        } else {
            val pt = Point()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealSize(pt)
            Pair(pt.x.coerceAtLeast(1), pt.y.coerceAtLeast(1))
        }
    }

    private fun normToRealX(normX: Int): Int {
        val (w, _) = getPhysicalScreenSize()
        return ((normX.coerceIn(0, 1000) * w) / 1000f).toInt()
    }

    private fun normToRealY(normY: Int): Int {
        val (_, h) = getPhysicalScreenSize()
        return ((normY.coerceIn(0, 1000) * h) / 1000f).toInt()
    }

    private fun realToNormX(realX: Int): Int {
        val (w, _) = getPhysicalScreenSize()
        return ((realX * 1000f) / w).toInt().coerceIn(0, 1000)
    }

    private fun realToNormY(realY: Int): Int {
        val (_, h) = getPhysicalScreenSize()
        return ((realY * 1000f) / h).toInt().coerceIn(0, 1000)
    }

    private fun syncSavedProfileToMemory() {
        val p = getSharedPreferences("user_profile_vault", MODE_PRIVATE)
        manager.preloadSavedProfile(
            nameEn = p.getString("name_en", "").orEmpty(),
            nameHi = p.getString("name_hi", "").orEmpty(),
            address = p.getString("address", "").orEmpty(),
            phone = p.getString("phone", "").orEmpty()
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isAgentRunning && !isReelAutoScrollActive) checkExternalTrigger()
    }

    override fun onInterrupt() {
        stopReelAutoScroll()
        stopAgent(false)
    }

    override fun onDestroy() {
        handler.removeCallbacks(backgroundWatchdog)
        stopReelAutoScroll()
        releaseAgentWakeLock()
        stopWaveCallCutSensor()
        removeProOverlay()
        try {
            speechRecognizer?.destroy()
            tts?.shutdown()
        } catch (_: Exception) { }
        super.onDestroy()
    }

    private fun checkExternalTrigger() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_HIDE_ORB, false)) {
            prefs.edit().putBoolean(KEY_HIDE_ORB, false).putBoolean(KEY_ORB_ENABLED, false).apply()
            stopReelAutoScroll()
            stopAgent(false)
            removeProOverlay()
            return
        }
        if (prefs.getBoolean(KEY_SHOW_ORB, false)) {
            prefs.edit().putBoolean(KEY_SHOW_ORB, false).putBoolean(KEY_ORB_ENABLED, true).apply()
            if (rootContainer == null) createProFloatingOrb()
            updateTreeTimeline("Nischay Pulse तैयार है", "ऑर्ब दबाकर बोलें ✨", emptyList(), -1)
        }
        val active = prefs.getBoolean(KEY_ACTIVE, false)
        val newGoal = prefs.getString(KEY_GOAL, null)
        val fromSchedule = prefs.getBoolean("from_schedule", false)
        if (active && !newGoal.isNullOrBlank()) {
            prefs.edit().remove(KEY_ACTIVE).remove(KEY_GOAL).remove("from_schedule").apply()
            if (rootContainer == null) createProFloatingOrb()
            processUserVoiceOrTextCommand(newGoal, isTriggeredFromSchedule = fromSchedule)
        }
    }

    private fun vibrateGentle(ms: Long = 35L) {
        try {
            val v = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v?.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                v?.vibrate(ms)
            }
        } catch (_: Exception) { }
    }

    private fun minimizeToCornerPill(pillText: String) {
        handler.post {
            isMinimizedToPill = true
            subtitleCard?.visibility = View.GONE
            miniCornerPill?.text = pillText
            miniCornerPill?.visibility = View.VISIBLE
        }
    }

    private fun expandFromCornerPill() {
        handler.post {
            isMinimizedToPill = false
            miniCornerPill?.visibility = View.GONE
            subtitleCard?.visibility = View.VISIBLE
        }
    }

    private fun setOverlayFocusable(focusable: Boolean) {
        handler.post {
            val container = rootContainer ?: return@post
            val lp = overlayParams ?: return@post
            lp.flags = if (focusable) {
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            } else {
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            }
            try {
                windowManager?.updateViewLayout(container, lp)
            } catch (_: Exception) { }
        }
    }

    private fun showMiniQuickReplyBox(show: Boolean, hintText: String = "यहाँ टाइप करें...") {
        handler.post {
            if (show) {
                expandFromCornerPill()
                quickReplyInput?.setText("")
                quickReplyInput?.hint = hintText
                quickReplyContainer?.visibility = View.VISIBLE
                setOverlayFocusable(true)
            } else {
                try {
                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                    quickReplyInput?.windowToken?.let { imm?.hideSoftInputFromWindow(it, 0) }
                } catch (_: Exception) { }
                quickReplyContainer?.visibility = View.GONE
                setOverlayFocusable(false)
            }
        }
    }

    private fun showBottomDismissZone(show: Boolean) {
        handler.post {
            if (show) {
                if (dismissZoneView != null) {
                    dismissZoneView?.visibility = View.VISIBLE
                    return@post
                }
                val sizePx = (64 * resources.displayMetrics.density).toInt()
                val closeCircle = TextView(this).apply {
                    text = "✕"
                    textSize = 24f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.parseColor("#DDDC2626"))
                        setStroke(4, Color.WHITE)
                    }
                    elevation = 30f
                }
                val params = WindowManager.LayoutParams(
                    sizePx, sizePx,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                    y = (42 * resources.displayMetrics.density).toInt()
                }
                try {
                    windowManager?.addView(closeCircle, params)
                    dismissZoneView = closeCircle
                } catch (_: Exception) { }
            } else {
                try {
                    dismissZoneView?.let { windowManager?.removeView(it) }
                } catch (_: Exception) { }
                dismissZoneView = null
                isOverDismissZone = false
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createProFloatingOrb() {
        handler.post {
            try {
                removeProOverlay()
                val density = resources.displayMetrics.density
                val container = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.TOP
                    setPadding(6, 6, 6, 6)
                }

                val sizePx = (58 * density).toInt()
                val orb = ProAiOrbView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(sizePx, sizePx)
                    elevation = 24f
                }

                val cornerPill = TextView(this).apply {
                    text = "⚡ सक्रिय • टैप करें"
                    textSize = 11.5f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.parseColor("#00E5FF"))
                    visibility = View.GONE
                    setPadding(16, 10, 16, 10)
                    val lp = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        marginStart = 8
                        topMargin = 10
                    }
                    layoutParams = lp
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#E6090D16"))
                        cornerRadius = 40f
                        setStroke(2, Color.parseColor("#00E5FF"))
                    }
                    setOnClickListener {
                        if (isReelAutoScrollActive) {
                            stopReelAutoScroll()
                            updateTreeTimeline(null, "🛑 रील्स ऑटो-स्क्रॉल रोक दिया गया।", emptyList(), -1)
                        } else {
                            expandFromCornerPill()
                        }
                    }
                }

                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(20, 14, 20, 14)
                    visibility = View.GONE
                    alpha = 0.94f
                    val lp = LinearLayout.LayoutParams(
                        (245 * density).toInt(),
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { marginStart = 10 }
                    layoutParams = lp
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#EB090D16"))
                        cornerRadius = 28f
                        setStroke(2, Color.parseColor("#00E5FF"))
                    }
                    setOnClickListener {
                        if (isAgentRunning && pendingFormQuestion == null) {
                            minimizeToCornerPill("⚡ काम जारी है • देखें")
                        }
                    }
                }

                val userTv = TextView(this).apply {
                    textSize = 12.5f
                    maxLines = 10
                    setTextColor(Color.parseColor("#38BDF8"))
                }

                val aiTv = TextView(this).apply {
                    textSize = 12f
                    maxLines = 12
                    setPadding(0, 4, 0, 6)
                    setTextColor(Color.parseColor("#E879F9"))
                }

                val replyRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    visibility = View.GONE
                    setPadding(0, 6, 0, 8)
                }

                val replyEt = EditText(this).apply {
                    hint = "यहाँ टाइप करें..."
                    textSize = 12.5f
                    setTextColor(Color.WHITE)
                    setHintTextColor(Color.parseColor("#94A3B8"))
                    inputType = InputType.TYPE_CLASS_TEXT
                    setSingleLine(true)
                    setPadding(16, 10, 16, 10)
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#1E293B"))
                        cornerRadius = 18f
                        setStroke(2, Color.parseColor("#38BDF8"))
                    }
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginEnd = (6 * density).toInt()
                    }
                    setOnFocusChangeListener { _, hasFocus ->
                        if (hasFocus && isListening) {
                            try { speechRecognizer?.cancel() } catch (_: Exception) { }
                            isListening = false
                        }
                    }
                }

                val runBtn = TextView(this).apply {
                    text = "▶ Run"
                    textSize = 12f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.BLACK)
                    gravity = Gravity.CENTER
                    setPadding(18, 12, 18, 12)
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#10B981"))
                        cornerRadius = 18f
                    }
                    setOnClickListener {
                        val typed = replyEt.text?.toString()?.trim().orEmpty()
                        if (typed.isNotBlank() && pendingFormQuestion != null) {
                            try { speechRecognizer?.cancel() } catch (_: Exception) { }
                            isListening = false
                            handleFormAnswerFromUser(typed, isDirectTyped = true)
                        }
                    }
                }

                replyRow.addView(replyEt)
                replyRow.addView(runBtn)

                val divider = View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 2).apply {
                        bottomMargin = 6
                    }
                    setBackgroundColor(Color.parseColor("#1E293B"))
                }

                val treeBox = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                }

                card.addView(userTv)
                card.addView(aiTv)
                card.addView(replyRow)
                card.addView(divider)
                card.addView(treeBox)

                container.addView(orb)
                container.addView(cornerPill)
                container.addView(card)

                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    x = 12
                    y = 90
                }

                var initialX = 0
                var initialY = 0
                var initialTouchX = 0f
                var initialTouchY = 0f
                var isDragging = false

                orb.setOnTouchListener { _, event ->
                    val (screenW, screenH) = getPhysicalScreenSize()
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            initialX = params.x
                            initialY = params.y
                            initialTouchX = event.rawX
                            initialTouchY = event.rawY
                            isDragging = false
                            isOverDismissZone = false
                            true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val dx = (event.rawX - initialTouchX).toInt()
                            val dy = (event.rawY - initialTouchY).toInt()
                            if (abs(dx) > 12 || abs(dy) > 12) {
                                if (!isDragging) {
                                    isDragging = true
                                    showBottomDismissZone(true)
                                }
                                params.x = initialX + dx
                                params.y = initialY + dy
                                windowManager?.updateViewLayout(container, params)

                                val inBottomZone = event.rawY > screenH * 0.80f &&
                                    abs(event.rawX - (screenW / 2f)) < (screenW * 0.22f)
                                if (inBottomZone && !isOverDismissZone) {
                                    isOverDismissZone = true
                                    vibrateGentle(45L)
                                    dismissZoneView?.animate()?.scaleX(1.28f)?.scaleY(1.28f)?.setDuration(140L)?.start()
                                } else if (!inBottomZone && isOverDismissZone) {
                                    isOverDismissZone = false
                                    dismissZoneView?.animate()?.scaleX(1.0f)?.scaleY(1.0f)?.setDuration(140L)?.start()
                                }
                            }
                            true
                        }
                        MotionEvent.ACTION_UP -> {
                            val shouldDismiss = isDragging && isOverDismissZone
                            showBottomDismissZone(false)
                            if (shouldDismiss) {
                                vibrateGentle(60L)
                                getSharedPreferences(PREFS, MODE_PRIVATE)
                                    .edit().putBoolean(KEY_ORB_ENABLED, false).apply()
                                stopReelAutoScroll()
                                stopAgent(false)
                                removeProOverlay()
                            } else if (!isDragging) {
                                if (isReelAutoScrollActive) {
                                    stopReelAutoScroll()
                                    updateTreeTimeline(null, "🛑 रील्स ऑटो-स्क्रॉल रोका गया।", emptyList(), -1)
                                } else if (isAgentRunning && pendingFormQuestion == null) {
                                    stopAgent(false)
                                } else {
                                    startSiriVoiceListening(pendingFormQuestion != null)
                                }
                            }
                            true
                        }
                        else -> false
                    }
                }

                windowManager?.addView(container, params)
                overlayParams = params
                rootContainer = container
                proOrbView = orb
                miniCornerPill = cornerPill
                subtitleCard = card
                userSubText = userTv
                aiSubText = aiTv
                quickReplyContainer = replyRow
                quickReplyInput = replyEt
                quickReplyRunBtn = runBtn
                treeTimelineContainer = treeBox
            } catch (_: Exception) { }
        }
    }

    private fun setOrbVisualState(mode: ProAiOrbView.State) {
        handler.post {
            proOrbView?.setOrbState(mode)
        }
    }

    private fun updateTreeTimeline(userMsg: String?, aiMsg: String?, steps: List<String>, activeIndex: Int) {
        handler.post {
            if (!isMinimizedToPill) {
                subtitleCard?.visibility = View.VISIBLE
            }
            if (!userMsg.isNullOrBlank()) {
                userSubText?.text = "आप: $userMsg"
                userSubText?.visibility = View.VISIBLE
            }
            if (!aiMsg.isNullOrBlank()) {
                aiSubText?.text = "Nischay Pulse: $aiMsg"
                aiSubText?.visibility = View.VISIBLE
            }

            val box = treeTimelineContainer ?: return@post
            if (steps.isNotEmpty()) {
                box.visibility = View.VISIBLE
                box.removeAllViews()
                val density = resources.displayMetrics.density

                steps.forEachIndexed { idx, stepLabel ->
                    val isDone = idx < activeIndex
                    val isCurrent = idx == activeIndex
                    val isLast = idx == steps.lastIndex

                    val branchColor = when {
                        isDone -> Color.parseColor("#10B981")
                        isCurrent -> Color.parseColor("#00E5FF")
                        else -> Color.parseColor("#475569")
                    }

                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                    }

                    val vLine = View(this).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            (3 * density).toInt(),
                            (24 * density).toInt()
                        ).apply {
                            marginStart = (8 * density).toInt()
                            bottomMargin = if (isLast) (6 * density).toInt() else 0
                        }
                        setBackgroundColor(branchColor)
                    }

                    val hLine = View(this).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            (12 * density).toInt(),
                            (3 * density).toInt()
                        ).apply {
                            marginEnd = (6 * density).toInt()
                        }
                        setBackgroundColor(branchColor)
                    }

                    val labelTv = TextView(this).apply {
                        textSize = 11.5f
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        when {
                            isDone -> {
                                text = "✅  $stepLabel"
                                setTextColor(Color.parseColor("#34D399"))
                            }
                            isCurrent -> {
                                text = "⚡  $stepLabel..."
                                setTextColor(Color.parseColor("#FACC15"))
                            }
                            else -> {
                                text = "○  $stepLabel"
                                setTextColor(Color.parseColor("#94A3B8"))
                            }
                        }
                    }

                    row.addView(vLine)
                    row.addView(hLine)
                    row.addView(labelTv)
                    box.addView(row)
                }
            } else {
                box.visibility = View.GONE
            }

            handler.removeCallbacks(hideBubbleRunnable)
            if (!isAgentRunning && !isListening && !isReelAutoScrollActive && pendingFormQuestion == null) {
                handler.postDelayed(hideBubbleRunnable, 8500L)
            }
        }
    }

    private val hideBubbleRunnable = Runnable {
        if (!isAgentRunning && !isListening && !isReelAutoScrollActive && pendingFormQuestion == null) {
            subtitleCard?.visibility = View.GONE
            miniCornerPill?.visibility = View.GONE
            isMinimizedToPill = false
        }
    }

    private fun playSystemPulseTone() {
        try {
            val toneGen = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 55)
            toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 110)
            handler.postDelayed({
                try { toneGen.release() } catch (_: Exception) { }
            }, 250L)
        } catch (_: Exception) { }
    }

    private fun speakOnce(sentence: String) {
        if (sentence.isBlank()) return
        handler.post {
            aiSubText?.text = "Nischay Pulse: $sentence"
            isTtsSpeaking = true
            val speed = getSharedPreferences("assistant_settings", MODE_PRIVATE).getFloat("tts_speed", 1.02f)
            tts?.setPitch(0.96f)
            tts?.setSpeechRate(speed)
            tts?.speak(humanizeHindiSpeech(sentence), TextToSpeech.QUEUE_FLUSH, null, "once_speech")
        }
    }

    // Package-Locked Reel & Shorts Auto-Scroll Engine
    private fun startSilentReelAutoScroll(
        appName: String,
        intervalSeconds: Int,
        durationMinutes: Int = 0,
        autoLike: Boolean = false
    ) {
        stopAgent(false)
        stopReelAutoScroll()
        acquireAgentWakeLock()

        val isYt = appName.equals("YouTube", true)
        val targetApp = if (isYt) "YouTube" else "Instagram"
        reelTargetPackage = if (isYt) "com.google.android.youtube" else "com.instagram.android"

        openAppByName(targetApp)
        isReelAutoScrollActive = true
        autoLikeEveryReel = autoLike
        reelScrollIntervalMs = (intervalSeconds.coerceIn(4, 120)) * 1000L
        reelEndTimeMs = if (durationMinutes > 0) System.currentTimeMillis() + (durationMinutes * 60_000L) else 0L

        val labelMode = if (isYt) "Shorts" else "Reels"
        val pillLabel = buildString {
            append("⚡ $labelMode • ${intervalSeconds}s")
            if (autoLike) append(" ❤️")
            if (durationMinutes > 0) append(" (${durationMinutes}m)")
            append(" ✕")
        }
        setOrbVisualState(ProAiOrbView.State.THINKING)
        minimizeToCornerPill(pillLabel)

        handler.postDelayed({
            if (!isReelAutoScrollActive) return@postDelayed
            val activePkg = rootInActiveWindow?.packageName?.toString().orEmpty()
            if (activePkg == reelTargetPackage) {
                if (isYt) {
                    clickNodeByTextOrDesc(rootInActiveWindow, listOf("shorts", "शॉर्ट्स"))
                } else {
                    clickNodeByTextOrDesc(rootInActiveWindow, listOf("reels", "रील्स"))
                }
                if (autoLikeEveryReel) {
                    handler.postDelayed({ performDoubleTapLike() }, 1800L)
                }
            }
        }, 2300L)

        handler.postDelayed(reelAutoScrollRunnable, reelScrollIntervalMs)
    }

    private fun performDoubleTapLike() {
        val activePkg = rootInActiveWindow?.packageName?.toString().orEmpty()
        if (!isReelAutoScrollActive || activePkg != reelTargetPackage || isScreenShowingSponsoredAd(rootInActiveWindow)) return
        val (w, h) = getPhysicalScreenSize()
        val cx = w * 0.48f
        val cy = h * 0.48f
        tapAtCoordinates(cx, cy)
        handler.postDelayed({
            if (isReelAutoScrollActive && rootInActiveWindow?.packageName?.toString() == reelTargetPackage) {
                tapAtCoordinates(cx, cy)
            }
        }, 110L)
    }

    private fun stopReelAutoScroll() {
        isReelAutoScrollActive = false
        autoLikeEveryReel = false
        reelEndTimeMs = 0L
        handler.removeCallbacks(reelAutoScrollRunnable)
        releaseAgentWakeLock()
        expandFromCornerPill()
        setOrbVisualState(ProAiOrbView.State.IDLE)
    }

    private val reelAutoScrollRunnable = object : Runnable {
        override fun run() {
            if (!isReelAutoScrollActive) return

            if (reelEndTimeMs > 0L && System.currentTimeMillis() >= reelEndTimeMs) {
                stopReelAutoScroll()
                performGlobalAction(GLOBAL_ACTION_HOME)
                logHistoryEntry(overallUserGoal, "तय समय तक Reels/Shorts स्क्रॉल पूरा हुआ", true)
                updateTreeTimeline(null, "✅ तय समय पूरा हो गया, होम स्क्रीन पर आ गए हैं।", emptyList(), -1)
                speakOnce("तय समय पूरा हो गया है।")
                return
            }

            // Strict Foreground Package Check: Never swipe if user went to Home screen or another app!
            val activePkg = rootInActiveWindow?.packageName?.toString().orEmpty()
            if (activePkg != reelTargetPackage) {
                miniCornerPill?.text = "⏸️ ऐप खोलें • रील्स रुकी है ✕"
                handler.postDelayed(this, 2500L)
                return
            } else {
                val labelMode = if (reelTargetPackage.contains("youtube")) "Shorts" else "Reels"
                miniCornerPill?.text = "⚡ $labelMode • ${reelScrollIntervalMs / 1000}s ✕"
            }

            performSwipe(true)

            handler.postDelayed({
                if (!isReelAutoScrollActive) return@postDelayed
                if (rootInActiveWindow?.packageName?.toString() != reelTargetPackage) return@postDelayed
                if (isScreenShowingSponsoredAd(rootInActiveWindow)) {
                    performSwipe(true)
                } else if (autoLikeEveryReel) {
                    performDoubleTapLike()
                }
            }, 1600L)

            handler.postDelayed(this, reelScrollIntervalMs)
        }
    }

    private fun isScreenShowingSponsoredAd(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        val t = node.text?.toString()?.trim().orEmpty()
        val d = node.contentDescription?.toString()?.trim().orEmpty()
        if (t.equals("Sponsored", true) || d.equals("Sponsored", true) || t.contains("प्रायोजित")) {
            return true
        }
        for (i in 0 until node.childCount) {
            if (isScreenShowingSponsoredAd(node.getChild(i))) return true
        }
        return false
    }

    private fun clickNodeByTextOrDesc(node: AccessibilityNodeInfo?, targets: List<String>): Boolean {
        if (node == null) return false
        val t = node.text?.toString()?.trim()?.lowercase().orEmpty()
        val d = node.contentDescription?.toString()?.trim()?.lowercase().orEmpty()
        if (targets.any { t == it || d == it || d.contains(it) }) {
            if (clickSelfOrParent(node)) return true
        }
        for (i in 0 until node.childCount) {
            if (clickNodeByTextOrDesc(node.getChild(i), targets)) return true
        }
        return false
    }

    private fun startSiriVoiceListening(isAnsweringFormQuestion: Boolean = false) {
        if (isListening) return
        handler.post {
            try {
                tts?.stop()
                isTtsSpeaking = false
                expandFromCornerPill()

                setOrbVisualState(ProAiOrbView.State.LISTENING)
                if (!isAnsweringFormQuestion) {
                    updateTreeTimeline("सुन रहा हूँ... बोलिए 🎙️", "तैयार हूँ...", emptyList(), -1)
                } else {
                    updateTreeTimeline("🎙️ बोलें या नीचे टाइप करके Run दबाएं...", null, subTaskQueue.map { it.title }, activeSubTaskIndex)
                }
                speechRecognizer?.destroy()
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                    setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) { isListening = true }
                        override fun onBeginningOfSpeech() = Unit
                        override fun onRmsChanged(rmsdB: Float) {
                            proOrbView?.setVoiceLevel(rmsdB)
                        }
                        override fun onBufferReceived(buffer: ByteArray?) = Unit
                        override fun onEndOfSpeech() {
                            isListening = false
                            setOrbVisualState(ProAiOrbView.State.THINKING)
                            playSystemPulseTone()
                        }
                        override fun onError(error: Int) {
                            isListening = false
                            setOrbVisualState(ProAiOrbView.State.IDLE)
                            if (isAnsweringFormQuestion) {
                                updateTreeTimeline(null, "नीचे बॉक्स में लिखकर ▶ Run दबाएं या ऑर्ब दबाकर बोलें।", subTaskQueue.map { it.title }, activeSubTaskIndex)
                            } else {
                                updateTreeTimeline(null, "आवाज़ साफ़ नहीं आई, फिर दबाएं।", emptyList(), -1)
                            }
                        }
                        override fun onPartialResults(partialResults: Bundle?) {
                            val p = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                            if (p.isNotBlank()) updateTreeTimeline(p, null, if (isAnsweringFormQuestion) subTaskQueue.map { it.title } else emptyList(), activeSubTaskIndex)
                        }
                        override fun onResults(results: Bundle?) {
                            isListening = false
                            setOrbVisualState(ProAiOrbView.State.THINKING)
                            val speech = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                            if (speech.isNotBlank()) {
                                if (isAnsweringFormQuestion && pendingFormQuestion != null) {
                                    handleFormAnswerFromUser(speech, isDirectTyped = false)
                                } else {
                                    processUserVoiceOrTextCommand(speech)
                                }
                            } else {
                                setOrbVisualState(ProAiOrbView.State.IDLE)
                            }
                        }
                        override fun onEvent(eventType: Int, params: Bundle?) = Unit
                    })
                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
                        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3500L)
                        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
                        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 4000L)
                    }
                    startListening(intent)
                }
            } catch (_: Exception) {
                isListening = false
                setOrbVisualState(ProAiOrbView.State.IDLE)
            }
        }
    }

    private fun handleFormAnswerFromUser(rawAnswer: String, isDirectTyped: Boolean) {
        val q = pendingFormQuestion ?: return
        pendingFormQuestion = null
        showMiniQuickReplyBox(false)

        val settingsPrefs = getSharedPreferences("assistant_settings", MODE_PRIVATE)
        val apiKey = settingsPrefs.getString("api_key", "").orEmpty()
        val providerName = settingsPrefs.getString("provider", "GEMINI") ?: "GEMINI"
        val provider = try { AiAssistantManager.Provider.valueOf(providerName) } catch (_: Exception) { AiAssistantManager.Provider.GEMINI }

        updateTreeTimeline(rawAnswer, "समझ लिया, आगे बढ़ रहा हूँ...", subTaskQueue.map { it.title }, activeSubTaskIndex)
        setOrbVisualState(ProAiOrbView.State.THINKING)

        executor.execute {
            val cleanedSaved = if (isDirectTyped) {
                manager.saveDirectUserReply(q, rawAnswer)
            } else {
                manager.extractAndSaveCleanFormValue(provider, apiKey, q, rawAnswer)
            }
            handler.post {
                completedMilestones.add("User choice/answer: $cleanedSaved")
                updateTreeTimeline(null, "✅ $cleanedSaved", subTaskQueue.map { it.title }, activeSubTaskIndex)
                scheduleNextLoop(450L)
            }
        }
    }

    private fun tryOfflineZeroApiCommand(userText: String): Boolean {
        val low = userText.trim().lowercase()
        if (low.contains("बाद") || low.contains("बजे") || low.contains("schedule") || low.contains("रोज़") || low.contains("रोज")) {
            return false
        }

        return when {
            (low.contains("रील") || low.contains("reel") || low.contains("shorts") || low.contains("शॉर्ट्स")) &&
                (low.contains("स्क्रॉल") || low.contains("scroll") || low.contains("चला")) -> {
                val app = if (low.contains("youtube") || low.contains("यूट्यूब") || low.contains("shorts") || low.contains("शॉर्ट्स")) "YouTube" else "Instagram"
                val sec = Regex("(\\d+)\\s*(सेकंड|sec|second)").find(low)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 15
                val mins = Regex("(\\d+)\\s*(मिनट|min|minute)").find(low)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
                val like = low.contains("लाइक") || low.contains("like")
                updateTreeTimeline(userText, "$app रील्स शुरू (${sec}s)", emptyList(), -1)
                startSilentReelAutoScroll(app, sec, mins, like)
                true
            }
            (low.contains("टॉर्च") || low.contains("torch") || low.contains("flashlight")) &&
                (low.contains("चालू") || low.contains("ऑन") || low.contains("on") || low.contains("जला")) -> {
                toggleTorch(true)
                logHistoryEntry(userText, "टॉर्च चालू की गई", true)
                updateTreeTimeline(userText, "टॉर्च चालू कर दी गई है।", listOf("टॉर्च चालू करना (0-API)"), 1)
                speakOnce("टॉर्च चालू कर दी गई है।")
                setOrbVisualState(ProAiOrbView.State.IDLE)
                true
            }
            (low.contains("टॉर्च") || low.contains("torch") || low.contains("flashlight")) &&
                (low.contains("बंद") || low.contains("ऑफ") || low.contains("off") || low.contains("बुझा")) -> {
                toggleTorch(false)
                logHistoryEntry(userText, "टॉर्च बंद की गई", true)
                updateTreeTimeline(userText, "टॉर्च बंद कर दी गई है।", listOf("टॉर्च बंद करना (0-API)"), 1)
                speakOnce("टॉर्च बंद कर दी गई है।")
                setOrbVisualState(ProAiOrbView.State.IDLE)
                true
            }
            low == "होम" || low.contains("होम स्क्रीन") || low.contains("go home") -> {
                performGlobalAction(GLOBAL_ACTION_HOME)
                logHistoryEntry(userText, "होम स्क्रीन पर गए", true)
                updateTreeTimeline(userText, "होम स्क्रीन पर आ गए हैं।", listOf("होम स्क्रीन (0-API)"), 1)
                speakOnce("होम स्क्रीन पर आ गए हैं।")
                setOrbVisualState(ProAiOrbView.State.IDLE)
                true
            }
            low == "बैक" || low == "वापस जाओ" || low == "go back" -> {
                performGlobalAction(GLOBAL_ACTION_BACK)
                logHistoryEntry(userText, "वापस गए", true)
                updateTreeTimeline(userText, "वापस आ गए हैं।", listOf("वापस जाना (0-API)"), 1)
                speakOnce("वापस आ गए हैं।")
                setOrbVisualState(ProAiOrbView.State.IDLE)
                true
            }
            low.contains("स्क्रीनशॉट") || low.contains("screenshot") -> {
                takeSystemScreenshot()
                logHistoryEntry(userText, "स्क्रीनशॉट लिया गया", true)
                updateTreeTimeline(userText, "स्क्रीनशॉट ले लिया गया है।", listOf("स्क्रीनशॉट लेना (0-API)"), 1)
                speakOnce("स्क्रीनशॉट ले लिया गया है।")
                setOrbVisualState(ProAiOrbView.State.IDLE)
                true
            }
            low.contains("साइलेंट") || low.contains("silent") || low.contains("म्यूट") -> {
                setSmartVolume("ZERO")
                logHistoryEntry(userText, "साइलेंट मोड चालू किया", true)
                updateTreeTimeline(userText, "फोन साइलेंट मोड पर है।", listOf("साइलेंट मोड (0-API)"), 1)
                speakOnce("फोन साइलेंट मोड पर है।")
                setOrbVisualState(ProAiOrbView.State.IDLE)
                true
            }
            (low.contains("वॉल्यूम") || low.contains("आवाज़") || low.contains("volume")) &&
                (low.contains("बढ़ा") || low.contains("फुल") || low.contains("पूरा") || low.contains("up")) -> {
                val lvl = if (low.contains("फुल") || low.contains("पूरा") || low.contains("100") || low.contains("तेज़")) "FULL"
                          else if (low.contains("आधा") || low.contains("50")) "HALF" else "STEP"
                setSmartVolume(lvl)
                val msg = if (lvl == "FULL") "वॉल्यूम पूरा फुल कर दिया गया है।" else "वॉल्यूम बढ़ा दिया गया है।"
                logHistoryEntry(userText, msg, true)
                updateTreeTimeline(userText, msg, listOf("$msg (0-API)"), 1)
                speakOnce(msg)
                setOrbVisualState(ProAiOrbView.State.IDLE)
                true
            }
            (low.contains("वॉल्यूम") || low.contains("आवाज़") || low.contains("volume")) &&
                (low.contains("कम") || low.contains("धीम") || low.contains("down")) -> {
                val lvl = if (low.contains("पूरा कम") || low.contains("बिलकुल") || low.contains("0")) "ZERO"
                          else if (low.contains("आधा") || low.contains("50")) "HALF" else "STEP_DOWN"
                setSmartVolume(lvl)
                val msg = if (lvl == "ZERO") "वॉल्यूम पूरा कम कर दिया गया है।" else "वॉल्यूम कम कर दिया गया है।"
                logHistoryEntry(userText, msg, true)
                updateTreeTimeline(userText, msg, listOf("$msg (0-API)"), 1)
                speakOnce(msg)
                setOrbVisualState(ProAiOrbView.State.IDLE)
                true
            }
            low.contains("नीचे स्क्रॉल") || low == "scroll down" -> {
                performSwipe(true)
                updateTreeTimeline(userText, "नीचे स्क्रॉल कर दिया गया है।", listOf("नीचे स्क्रॉल (0-API)"), 1)
                setOrbVisualState(ProAiOrbView.State.IDLE)
                true
            }
            low.contains("ऊपर स्क्रॉल") || low == "scroll up" -> {
                performSwipe(false)
                updateTreeTimeline(userText, "ऊपर स्क्रॉल कर दिया गया है।", listOf("ऊपर स्क्रॉल (0-API)"), 1)
                setOrbVisualState(ProAiOrbView.State.IDLE)
                true
            }
            else -> false
        }
    }

    private fun processUserVoiceOrTextCommand(userText: String, isTriggeredFromSchedule: Boolean = false) {
        syncSavedProfileToMemory()
        overallUserGoal = userText
        cachedWebHint = ""
        showMiniQuickReplyBox(false)

        if (tryOfflineZeroApiCommand(userText)) {
            return
        }

        val settingsPrefs = getSharedPreferences("assistant_settings", MODE_PRIVATE)
        val apiKey = settingsPrefs.getString("api_key", "").orEmpty()

        if (manager.parseUserKeyList(apiKey).isEmpty()) {
            setOrbVisualState(ProAiOrbView.State.IDLE)
            val needKeyMsg = "AI स्मार्ट काम के लिए पहले Settings मेन्यू में अपनी Gemini API Key डालें।"
            updateTreeTimeline(userText, "🔑 $needKeyMsg", emptyList(), -1)
            speakOnce(needKeyMsg)
            return
        }

        setOrbVisualState(ProAiOrbView.State.THINKING)
        updateTreeTimeline(userText, "⚡ योजना बना रहा हूँ...", emptyList(), -1)
        val providerName = settingsPrefs.getString("provider", "GEMINI") ?: "GEMINI"
        val personality = settingsPrefs.getString("personality", "Friendly") ?: "Friendly"
        val speed = settingsPrefs.getFloat("tts_speed", 1.02f)
        tts?.setSpeechRate(speed)

        val provider = try { AiAssistantManager.Provider.valueOf(providerName) } catch (_: Exception) { AiAssistantManager.Provider.GEMINI }

        serviceScope.launch {
            val result = manager.ask(provider, apiKey, userText, personality, isTriggeredFromSchedule)
            result.onSuccess { cmd ->
                val titles = if (cmd.subTasks.isNotEmpty()) cmd.subTasks.map { it.title } else listOf(cmd.startReply)
                currentTaskDoneReply = cmd.doneReply
                updateTreeTimeline(userText, cmd.startReply, titles, 0)

                when (cmd.intent) {
                    "REEL_AUTO_SCROLL" -> {
                        startSilentReelAutoScroll(
                            cmd.target ?: "Instagram",
                            cmd.reelIntervalSeconds,
                            cmd.reelDurationMinutes,
                            cmd.autoLikeReels
                        )
                    }
                    "SCHEDULE_TASK" -> {
                        speakOnce(cmd.startReply)
                        val item = ScheduledTaskReceiver.addAndScheduleTask(
                            context = this@WhatsAppHelperAccessibilityService,
                            command = cmd.scheduledCommand,
                            hour = cmd.hour,
                            minute = cmd.minute,
                            delaySeconds = cmd.delaySeconds,
                            isDaily = cmd.isDailySchedule
                        )
                        val confirmMsg = "शेड्यूल हो गया: ${item.formattedTime()} पर चलेगा।"
                        completeSimpleTask(listOf("⏰ ${item.formattedTime()}", item.command), confirmMsg)
                    }
                    "DIRECT_CALL" -> {
                        speakOnce(cmd.startReply)
                        executeDirectCall(cmd.target ?: userText, cmd.doneReply)
                    }
                    "TORCH_ON" -> { toggleTorch(true); completeSimpleTask(titles, cmd.doneReply) }
                    "TORCH_OFF" -> { toggleTorch(false); completeSimpleTask(titles, cmd.doneReply) }
                    "SET_ALARM" -> { setSystemAlarm(cmd.hour, cmd.minute, cmd.target ?: "Alarm"); completeSimpleTask(titles, cmd.doneReply) }
                    "SET_TIMER" -> { setSystemTimer(cmd.seconds, cmd.target ?: "Timer"); completeSimpleTask(titles, cmd.doneReply) }
                    "READ_SCREEN" -> readCurrentScreen(provider, apiKey, userText, titles)
                    "SCROLL_DOWN" -> { performSwipe(true); completeSimpleTask(titles, cmd.doneReply) }
                    "SCROLL_UP" -> { performSwipe(false); completeSimpleTask(titles, cmd.doneReply) }
                    "GO_BACK" -> { performGlobalAction(GLOBAL_ACTION_BACK); completeSimpleTask(titles, cmd.doneReply) }
                    "GO_HOME" -> { performGlobalAction(GLOBAL_ACTION_HOME); completeSimpleTask(titles, cmd.doneReply) }
                    "TAKE_SCREENSHOT" -> { takeSystemScreenshot(); completeSimpleTask(titles, cmd.doneReply) }
                    "VOLUME_UP" -> {
                        setSmartVolume(if (cmd.volumeLevel == "STEP") "STEP" else cmd.volumeLevel)
                        completeSimpleTask(titles, cmd.doneReply)
                    }
                    "VOLUME_DOWN" -> {
                        setSmartVolume(if (cmd.volumeLevel == "STEP") "STEP_DOWN" else cmd.volumeLevel)
                        completeSimpleTask(titles, cmd.doneReply)
                    }
                    "SILENT_MODE" -> { setSmartVolume("ZERO"); completeSimpleTask(titles, cmd.doneReply) }
                    "YOUTUBE_SEARCH" -> {
                        speakOnce(cmd.startReply)
                        openYouTubeSearchAndPlay(cmd.target ?: userText)
                    }
                    "AGENT_TASK" -> {
                        speakOnce(cmd.startReply)
                        startSequentialSubTasks(cmd.subTasks)
                    }
                    else -> {
                        speakOnce(cmd.startReply)
                        logHistoryEntry(userText, cmd.startReply, true)
                        setOrbVisualState(ProAiOrbView.State.IDLE)
                        handler.postDelayed({
                            updateTreeTimeline(null, null, titles, titles.size)
                        }, 300L)
                    }
                }
            }.onFailure {
                setOrbVisualState(ProAiOrbView.State.IDLE)
                logHistoryEntry(userText, "एरर: ${it.message}", false)
                updateTreeTimeline(userText, "एरर: ${it.message}", emptyList(), -1)
            }
        }
    }

    private fun completeSimpleTask(titles: List<String>, doneMsg: String) {
        logHistoryEntry(overallUserGoal, doneMsg, true)
        handler.postDelayed({
            setOrbVisualState(ProAiOrbView.State.IDLE)
            updateTreeTimeline(null, doneMsg, titles, titles.size)
            speakOnce(doneMsg)
        }, 550L)
    }

    private fun openYouTubeSearchAndPlay(cleanQuery: String) {
        val searchUri = Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(cleanQuery.trim())}")
        val intent = Intent(Intent.ACTION_VIEW, searchUri).apply {
            setPackage("com.google.android.youtube")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try { startActivity(intent) } catch (_: Exception) {
            startActivity(Intent(Intent.ACTION_VIEW, searchUri).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
        }
        startSequentialSubTasks(
            listOf(AiAssistantManager.SubTask("YouTube पर \"$cleanQuery\" चलाना", null, "Click the first video result for '$cleanQuery' and verify playback"))
        )
    }

    private fun takeSystemScreenshot() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
        }
    }

    private fun setSmartVolume(level: String) {
        try {
            val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val maxVol = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            when (level.uppercase()) {
                "FULL" -> audio.setStreamVolume(AudioManager.STREAM_MUSIC, maxVol, AudioManager.FLAG_SHOW_UI)
                "HALF" -> audio.setStreamVolume(AudioManager.STREAM_MUSIC, (maxVol / 2).coerceAtLeast(1), AudioManager.FLAG_SHOW_UI)
                "ZERO" -> audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, AudioManager.FLAG_SHOW_UI)
                "STEP_DOWN" -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                else -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            }
        } catch (_: Exception) { }
    }

    @SuppressLint("MissingPermission")
    private fun executeDirectCall(targetNameOrNumber: String, doneMsg: String) {
        val callTasks = listOf(
            AiAssistantManager.SubTask("कॉन्टैक्ट से नंबर निकालना", null, "Done"),
            AiAssistantManager.SubTask("सीधी कॉल मिलाना", null, "Done"),
            AiAssistantManager.SubTask("SIM विकल्प या कॉल की पुष्टि", null, "If SIM 1 / SIM 2 choice dialog appears on screen, MUST ask user via ASK_USER which SIM to use; otherwise verify call started")
        )
        val titles = callTasks.map { it.title }
        updateTreeTimeline(null, null, titles, 0)

        val number = if (targetNameOrNumber.count { it.isDigit() } >= 6) {
            targetNameOrNumber.filter { it.isDigit() || it == '+' }
        } else {
            findContactNumber(targetNameOrNumber)
        }

        if (number.isNullOrBlank()) {
            setOrbVisualState(ProAiOrbView.State.IDLE)
            val notFound = "\"$targetNameOrNumber\" का नंबर नहीं मिला।"
            logHistoryEntry(overallUserGoal, notFound, false)
            updateTreeTimeline(null, notFound, emptyList(), -1)
            speakOnce(notFound)
            return
        }

        updateTreeTimeline(null, "नंबर मिला: $number", titles, 1)
        startWaveCallCutSensor()

        val hasCallPerm = ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        val callUri = Uri.parse("tel:${Uri.encode(number)}")
        val callIntent = Intent(if (hasCallPerm) Intent.ACTION_CALL else Intent.ACTION_DIAL, callUri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(callIntent)

        currentTaskDoneReply = doneMsg
        handler.postDelayed({
            startSequentialSubTasks(listOf(callTasks[2]))
        }, 750L)
    }

    private fun findContactNumber(name: String): String? {
        return try {
            val cursor = contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%${name.trim()}%"),
                null
            )
            cursor?.use {
                if (it.moveToFirst()) it.getString(1)?.replace(" ", "") else null
            }
        } catch (_: Exception) { null }
    }

    private fun toggleTorch(turnOn: Boolean) {
        try {
            val camManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            camManager.cameraIdList.firstOrNull()?.let { camManager.setTorchMode(it, turnOn) }
        } catch (_: Exception) { }
    }

    private fun setSystemAlarm(hour: Int, minute: Int, label: String) {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_MESSAGE, label)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try { startActivity(intent) } catch (_: Exception) { }
    }

    private fun setSystemTimer(seconds: Int, label: String) {
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            putExtra(AlarmClock.EXTRA_MESSAGE, label)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try { startActivity(intent) } catch (_: Exception) { }
    }

    private fun readCurrentScreen(provider: AiAssistantManager.Provider, apiKey: String, userText: String, titles: List<String>) {
        val root = rootInActiveWindow ?: return
        indexedNodes.clear()
        val sb = StringBuilder()
        collectNodes(root, sb)
        executor.execute {
            val summary = manager.summarizeScreen(provider, apiKey, sb.toString())
            logHistoryEntry(userText, summary, true)
            handler.post {
                setOrbVisualState(ProAiOrbView.State.IDLE)
                updateTreeTimeline(userText, summary, titles, titles.size)
                speakOnce(summary)
            }
        }
    }

    private fun startSequentialSubTasks(tasks: List<AiAssistantManager.SubTask>) {
        if (tasks.isEmpty()) return
        acquireAgentWakeLock()
        subTaskQueue.clear()
        subTaskQueue.addAll(tasks)
        completedTasks.clear()
        completedMilestones.clear()
        extractedNotes.clear()
        blockedActionSignatures.clear()
        completedActionLabels.clear()
        activeSubTaskIndex = 0
        totalSteps = 0
        realActionsCount = 0
        isBottomLocked = false
        hasSentFinalMessage = false
        pendingFormQuestion = null
        showMiniQuickReplyBox(false)
        isAgentRunning = true
        isThinking = false
        setOrbVisualState(ProAiOrbView.State.THINKING)

        beginCurrentSubTask()
    }

    private fun beginCurrentSubTask() {
        if (!isAgentRunning) return
        if (activeSubTaskIndex >= subTaskQueue.size) {
            if (realActionsCount == 0 || !verifyGoalOnActualScreen("")) {
                stopAgentWithHonestReason("पूरा लक्ष्य अभी सत्यापित नहीं हो पाया।")
            } else {
                stopAgent(true)
            }
            return
        }

        stepsInCurrentSubTask = 0
        subTaskHistory.clear()
        blockedActionSignatures.clear()
        lastScreenHash = 0
        lastActionSignature = ""
        repeatedActionCount = 0
        staleScreenStreak = 0
        staleScrollDownCount = 0
        staleScrollUpCount = 0
        isBottomLocked = false
        hasSentFinalMessage = false
        retryCount = 0

        val currentTask = subTaskQueue[activeSubTaskIndex]
        val titles = subTaskQueue.map { it.title }
        updateTreeTimeline(null, null, titles, activeSubTaskIndex)

        val lowerInstr = "${currentTask.title} ${currentTask.instruction}".lowercase()

        when {
            lowerInstr.contains("volume full") || lowerInstr.contains("वॉल्यूम फुल") || lowerInstr.contains("पूरा बढ़ा") -> {
                setSmartVolume("FULL")
                realActionsCount++
                handler.postDelayed({ completeAndAdvanceSubTask() }, 350L)
                return
            }
            lowerInstr.contains("volume up") || lowerInstr.contains("वॉल्यूम बढ़ा") -> {
                setSmartVolume("STEP")
                realActionsCount++
                handler.postDelayed({ completeAndAdvanceSubTask() }, 350L)
                return
            }
            lowerInstr.contains("volume down") || lowerInstr.contains("वॉल्यूम कम") -> {
                setSmartVolume("STEP_DOWN")
                realActionsCount++
                handler.postDelayed({ completeAndAdvanceSubTask() }, 350L)
                return
            }
            lowerInstr.contains("home screen") || lowerInstr.contains("होम स्क्रीन") || lowerInstr.contains("go home") -> {
                performGlobalAction(GLOBAL_ACTION_HOME)
                realActionsCount++
                handler.postDelayed({ completeAndAdvanceSubTask() }, 450L)
                return
            }
            lowerInstr.contains("torch on") || lowerInstr.contains("टॉर्च चालू") -> {
                toggleTorch(true)
                realActionsCount++
                handler.postDelayed({ completeAndAdvanceSubTask() }, 300L)
                return
            }
            lowerInstr.contains("torch off") || lowerInstr.contains("टॉर्च बंद") -> {
                toggleTorch(false)
                realActionsCount++
                handler.postDelayed({ completeAndAdvanceSubTask() }, 300L)
                return
            }
        }

        if (!currentTask.appToOpen.isNullOrBlank()) {
            val opened = openAppByName(currentTask.appToOpen)
            if (opened) {
                completedMilestones.add("Opened ${currentTask.appToOpen}")
            }
            val isPureOpenStep = (lowerInstr.contains("खोल") || lowerInstr.contains("open")) &&
                !lowerInstr.contains("भेज") && !lowerInstr.contains("शेयर") && !lowerInstr.contains("send") && !lowerInstr.contains("पढ़")
            if (opened && subTaskQueue.size > 1 && activeSubTaskIndex < subTaskQueue.lastIndex && isPureOpenStep) {
                realActionsCount++
                handler.postDelayed({ completeAndAdvanceSubTask() }, 1400L)
            } else {
                scheduleNextLoop(1500L)
            }
        } else {
            scheduleNextLoop(450L)
        }
    }

    private fun completeAndAdvanceSubTask() {
        if (activeSubTaskIndex < subTaskQueue.size) {
            completedTasks.add(subTaskQueue[activeSubTaskIndex].title)
        }
        activeSubTaskIndex++
        val titles = subTaskQueue.map { it.title }
        updateTreeTimeline(null, null, titles, activeSubTaskIndex)

        if (activeSubTaskIndex >= subTaskQueue.size) {
            if (realActionsCount == 0) {
                stopAgentWithHonestReason("यह काम पूरा नहीं हो पाया।")
            } else {
                stopAgent(true)
            }
        } else {
            beginCurrentSubTask()
        }
    }

    private fun openSystemCamera() {
        try {
            val camIntent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(camIntent)
        } catch (_: Exception) {
            openAppByName("camera")
        }
    }

    private fun openAppByName(appName: String): Boolean {
        return try {
            val cleanQuery = appName.trim().lowercase()
            if (cleanQuery == "camera" || cleanQuery == "कैमरा") {
                openSystemCamera()
                return true
            }
            val pm = packageManager

            val directPackages = mapOf(
                "flipkart" to "com.flipkart.android",
                "फ्लिपकार्ट" to "com.flipkart.android",
                "amazon" to "in.amazon.mShop.android.shopping",
                "अमेज़न" to "in.amazon.mShop.android.shopping",
                "whatsapp" to "com.whatsapp",
                "व्हाट्सएप" to "com.whatsapp",
                "instagram" to "com.instagram.android",
                "इंस्टाग्राम" to "com.instagram.android",
                "youtube" to "com.google.android.youtube",
                "यूट्यूब" to "com.google.android.youtube",
                "chrome" to "com.android.chrome",
                "क्रोम" to "com.android.chrome",
                "google" to "com.google.android.googlequicksearchbox",
                "गूगल" to "com.google.android.googlequicksearchbox",
                "chatgpt" to "com.openai.chatgpt",
                "where is my train" to "com.whereismytrain.android",
                "messages" to "com.google.android.apps.messaging",
                "मैसेज" to "com.google.android.apps.messaging",
                "settings" to "com.android.settings",
                "सेटिंग" to "com.android.settings",
                "calculator" to "com.google.android.calculator",
                "कैलकुलेटर" to "com.google.android.calculator",
                "clock" to "com.google.android.deskclock",
                "अलार्म" to "com.google.android.deskclock"
            )

            for ((key, pkg) in directPackages) {
                if (cleanQuery.contains(key)) {
                    pm.getLaunchIntentForPackage(pkg)?.let {
                        it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        startActivity(it)
                        return true
                    }
                }
            }

            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
            val apps = pm.queryIntentActivities(mainIntent, 0)
            val exactMatch = apps.firstOrNull { it.loadLabel(pm).toString().trim().equals(cleanQuery, true) }
            val startsWithMatch = apps.firstOrNull { it.loadLabel(pm).toString().trim().lowercase().startsWith(cleanQuery) }
            val partialMatch = apps.firstOrNull { it.loadLabel(pm).toString().lowercase().contains(cleanQuery) }

            val chosen = exactMatch ?: startsWithMatch ?: partialMatch
            if (chosen != null) {
                pm.getLaunchIntentForPackage(chosen.activityInfo.packageName)?.let {
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    startActivity(it)
                    return true
                }
            }
            false
        } catch (_: Exception) { false }
    }

    // Only standard OS runtime permission prompts (Zero-Guessing: never auto-pick SIMs or app choices!)
    private fun tryZeroApiOsPermissionOnly(): Boolean {
        val osPermissionLabels = listOf("while using the app", "only this time", "allow", "अनुमति दें")
        for (idx in indexedNodes.indices) {
            val node = indexedNodes[idx]
            val pkg = node.packageName?.toString().orEmpty()
            if (!pkg.contains("permissioncontroller") && !pkg.contains("packageinstaller")) continue
            val label = (node.text?.toString() ?: node.contentDescription?.toString() ?: "").trim().lowercase()
            if (label.isBlank()) continue
            val sig = "OS_PERM_idx=$idx"
            if (sig in blockedActionSignatures) continue

            if (osPermissionLabels.any { label == it }) {
                val r = Rect()
                node.getBoundsInScreen(r)
                showVisualTouchDot(r.centerX(), r.centerY())
                val clicked = clickSelfOrParent(node)
                if (!clicked && r.centerX() > 0 && r.centerY() > 0) {
                    performGhostTouch(r.centerX(), r.centerY())
                }
                blockedActionSignatures.add(sig)
                realActionsCount++
                aiSubText?.text = "Nischay Pulse: ⚡ परमिशन दी ($label)"
                return true
            }
        }
        return false
    }

    private fun scheduleNextLoop(delayMs: Long) {
        handler.removeCallbacks(agentLoopRunnable)
        handler.postDelayed(agentLoopRunnable, delayMs)
    }

    private val agentLoopRunnable = Runnable { runAgentStep() }

    private fun runAgentStep() {
        if (!isAgentRunning || isThinking || pendingFormQuestion != null) return
        if (totalSteps >= 95 || activeSubTaskIndex >= subTaskQueue.size) {
            stopAgentWithHonestReason("स्टेप्स की सीमा पूरी हो गई, काम अधूरा रह गया।")
            return
        }
        if (stepsInCurrentSubTask >= 45) {
            stopAgentWithHonestReason("इस चरण में आगे कोई नया विकल्प नहीं मिल रहा है।")
            return
        }

        val allRoots = mutableListOf<AccessibilityNodeInfo>()
        try {
            windows?.forEach { win -> win.root?.let { allRoots.add(it) } }
        } catch (_: Exception) { }
        if (allRoots.isEmpty()) {
            rootInActiveWindow?.let { allRoots.add(it) }
        }
        if (allRoots.isEmpty()) {
            scheduleNextLoop(500L)
            return
        }

        indexedNodes.clear()
        val sb = StringBuilder()
        allRoots.asReversed().forEach { r -> collectNodes(r, sb) }
        val screenDesc = sb.toString()

        if (tryZeroApiOsPermissionOnly()) {
            totalSteps++
            stepsInCurrentSubTask++
            scheduleNextLoop(650L)
            return
        }

        val currentHash = screenDesc.hashCode()
        val screenChanged = (lastScreenHash == 0) || (currentHash != lastScreenHash)
        lastScreenHash = currentHash

        if (screenChanged) {
            staleScreenStreak = 0
            staleScrollDownCount = 0
            staleScrollUpCount = 0
            isBottomLocked = false
        } else {
            staleScreenStreak++
        }

        isThinking = true
        totalSteps++
        stepsInCurrentSubTask++

        val goalLow = overallUserGoal.lowercase()
        val isCameraOrPhotoTask = goalLow.contains("फोटो") || goalLow.contains("photo") || goalLow.contains("कैमरा") || goalLow.contains("camera") || goalLow.contains("वीडियो रिकॉर्ड")
        val needVisionScreenshot = isCameraOrPhotoTask || indexedNodes.size < 5 || repeatedActionCount >= 1 || staleScreenStreak >= 2

        if (needVisionScreenshot) {
            captureLiveScreenBase64 { screenshotB64 ->
                executeAutonomousDecision(screenDesc, screenshotB64, screenChanged)
            }
        } else {
            executeAutonomousDecision(screenDesc, null, screenChanged)
        }
    }

    private fun captureLiveScreenBase64(onCaptured: (String?) -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val prevVis = rootContainer?.visibility ?: View.VISIBLE
                rootContainer?.visibility = View.INVISIBLE

                takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    executor,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(screenshot: ScreenshotResult) {
                            handler.post { rootContainer?.visibility = prevVis }
                            try {
                                val hwBitmap = Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                                val softBitmap = hwBitmap?.copy(Bitmap.Config.ARGB_8888, false)
                                screenshot.hardwareBuffer.close()
                                hwBitmap?.recycle()

                                if (softBitmap != null) {
                                    val targetW = (softBitmap.width * 0.40f).toInt().coerceAtLeast(300)
                                    val targetH = (softBitmap.height * 0.40f).toInt().coerceAtLeast(520)
                                    val scaled = Bitmap.createScaledBitmap(softBitmap, targetW, targetH, true)
                                    softBitmap.recycle()

                                    val stream = ByteArrayOutputStream()
                                    scaled.compress(Bitmap.CompressFormat.JPEG, 42, stream)
                                    scaled.recycle()
                                    val b64 = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
                                    onCaptured(b64)
                                } else {
                                    onCaptured(null)
                                }
                            } catch (_: Exception) {
                                onCaptured(null)
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            handler.post { rootContainer?.visibility = prevVis }
                            onCaptured(null)
                        }
                    }
                )
                return
            } catch (_: Exception) { }
        }
        onCaptured(null)
    }

    private fun verifyGoalOnActualScreen(screenElementsText: String): Boolean {
        if (realActionsCount == 0) return false
        val goalLow = overallUserGoal.lowercase()
        val screenLow = screenElementsText.lowercase()
        val isFinalSubTask = activeSubTaskIndex >= subTaskQueue.lastIndex

        if (isFinalSubTask && (goalLow.contains("भेज") || goalLow.contains("शेयर") || goalLow.contains("send") || goalLow.contains("share") || goalLow.contains("मैसेज"))) {
            val didSend = hasSentFinalMessage || completedActionLabels.any {
                it.contains("send") || it.contains("भेज") || it.contains("share") || it.contains("post")
            }
            if (!didSend) return false
        }

        if (isFinalSubTask && (goalLow.contains("देखो") || goalLow.contains("पढ़") || goalLow.contains("बताओ") || goalLow.contains("निकाल"))) {
            if (extractedNotes.isEmpty() && !goalLow.contains("कॉल")) return false
        }

        if (goalLow.contains("सर्च") || goalLow.contains("search")) {
            val stopWords = setOf("flipkart", "फ्लिपकार्ट", "amazon", "अमेज़न", "youtube", "यूट्यूब", "instagram", "इंस्टाग्राम", "ऐप", "खोलो", "सर्च", "बॉक्स", "में", "करो", "और", "जब", "रिजल्ट", "आ", "जाए", "तो", "थोड़ा", "नीचे", "स्क्रॉल", "करके", "कीमत", "नाम", "पढ़कर", "बताओ", "ट्रेनें", "बीच", "की")
            val keywords = goalLow.split(" ", ",", "।", "\"")
                .map { it.trim() }
                .filter { it.length >= 3 && it !in stopWords }
            if (keywords.isNotEmpty() && screenLow.isNotBlank()) {
                val matched = keywords.any { screenLow.contains(it) }
                if (!matched) return false
            }
        }
        return true
    }

    private fun isPhoneOrPinInputContext(screenDesc: String, node: AccessibilityNodeInfo?): Boolean {
        val screenLow = screenDesc.lowercase()
        val nodeHint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) node?.hintText?.toString()?.lowercase().orEmpty() else ""
        val nodeText = node?.text?.toString()?.lowercase().orEmpty()
        val keywords = listOf("phone number", "mobile number", "फ़ोन नंबर", "मोबाइल नंबर", "pin", "पिन", "password", "पासवर्ड", "otp", "ओटीपी")
        return keywords.any { screenLow.contains(it) || nodeHint.contains(it) || nodeText.contains(it) }
    }

    private fun isSingleActionButton(label: String): Boolean {
        val low = label.trim().lowercase()
        val singleWords = setOf(
            "like", "follow", "subscribe", "save", "send", "submit", "post", "create",
            "switch camera", "flip camera", "front camera", "rear camera",
            "record", "start recording", "shutter",
            "पसंद", "फॉलो", "भेजें", "सेव", "कैमरा बदलें"
        )
        return singleWords.any { low == it || low.contains(it) }
    }

    private fun promptUserWithVoiceAndMiniBox(question: String, hint: String) {
        pendingFormQuestion = question
        val titles = subTaskQueue.map { it.title }
        updateTreeTimeline(null, "❓ $question", titles, activeSubTaskIndex)
        showMiniQuickReplyBox(true, hint)
        isTtsSpeaking = true
        tts?.speak(question, TextToSpeech.QUEUE_FLUSH, null, "ask_form_q")
    }

    private fun executeAutonomousDecision(screenDesc: String, screenshotB64: String?, screenChanged: Boolean) {
        val currentSubTask = subTaskQueue[activeSubTaskIndex]
        val settingsPrefs = getSharedPreferences("assistant_settings", MODE_PRIVATE)
        val apiKey = settingsPrefs.getString("api_key", "").orEmpty()
        val providerName = settingsPrefs.getString("provider", "GEMINI") ?: "GEMINI"
        val provider = try { AiAssistantManager.Provider.valueOf(providerName) } catch (_: Exception) { AiAssistantManager.Provider.GEMINI }

        executor.execute {
            try {
                // Unknown-App Web Search Fallback when stuck 3+ times on same screen
                if (staleScreenStreak >= 3 && cachedWebHint.isBlank()) {
                    cachedWebHint = manager.fetchWebStepsForUnknownTask(overallUserGoal)
                }

                val decision = manager.decideNextAutonomousStep(
                    provider = provider,
                    apiKey = apiKey,
                    overallGoal = overallUserGoal,
                    currentSubTaskInstruction = currentSubTask.instruction,
                    completedSubTasks = completedTasks,
                    completedMilestones = completedMilestones,
                    extractedNotes = extractedNotes,
                    blockedSignatures = blockedActionSignatures,
                    screenElements = screenDesc,
                    screenshotBase64 = screenshotB64,
                    history = subTaskHistory,
                    screenChanged = screenChanged,
                    repeatedActionCount = repeatedActionCount,
                    staleScreenStreak = staleScreenStreak,
                    isBottomLocked = isBottomLocked,
                    realActionsCount = realActionsCount,
                    webHelpHint = cachedWebHint
                )
                retryCount = 0
                handler.post {
                    isThinking = false
                    if (!isAgentRunning) return@post

                    if (decision.milestoneAchieved.isNotBlank() && !completedMilestones.contains(decision.milestoneAchieved)) {
                        completedMilestones.add(decision.milestoneAchieved)
                    }
                    if (decision.extractedNote.isNotBlank() && !extractedNotes.contains(decision.extractedNote)) {
                        extractedNotes.add(decision.extractedNote)
                    }

                    val isScrollOrWait = decision.action in setOf("SCROLL_DOWN", "SCROLL_UP", "WAIT", "HIDE_KEYBOARD", "BACK")
                    val sig = "${decision.action}_idx=${decision.index}_xy=${decision.normX},${decision.normY}_txt=${decision.text.take(15)}"

                    if (!isScrollOrWait && sig in blockedActionSignatures) {
                        subTaskHistory.add("ALREADY_TRIED_BLOCKED: '$sig' failed/done! Try Search icon, Scroll, or BACK.")
                        scheduleNextLoop(450L)
                        return@post
                    }

                    if (!isScrollOrWait && sig == lastActionSignature && !screenChanged) {
                        repeatedActionCount++
                    } else {
                        repeatedActionCount = 0
                    }
                    lastActionSignature = sig

                    if (!isScrollOrWait && repeatedActionCount >= 1) {
                        if (!blockedActionSignatures.contains(sig)) blockedActionSignatures.add(sig)
                        subTaskHistory.add("STUCK_ON_ELEMENT: '$sig' did NOT change screen! Use another path (Search bar, Scroll, or Back).")
                        scheduleNextLoop(450L)
                        return@post
                    }

                    subTaskHistory.add("${decision.action}(idx=${decision.index}, '${decision.text.take(25)}')")
                    if (decision.statusLabel.isNotBlank()) {
                        aiSubText?.text = "Nischay Pulse: ${decision.statusLabel}"
                    }

                    when (decision.action) {
                        "WAIT" -> {
                            val waitMsg = decision.statusLabel.ifBlank { "पेज लोड हो रहा है, रुको..." }
                            aiSubText?.text = "Nischay Pulse: ⏳ $waitMsg"
                            val now = System.currentTimeMillis()
                            if (now - lastWaitSpokenTime > 7000L) {
                                lastWaitSpokenTime = now
                                speakOnce(waitMsg)
                            }
                            scheduleNextLoop(1500L)
                        }
                        "HIDE_KEYBOARD" -> {
                            performGlobalAction(GLOBAL_ACTION_BACK)
                            realActionsCount++
                            scheduleNextLoop(700L)
                        }
                        "OPEN_APP" -> {
                            staleScrollDownCount = 0
                            staleScrollUpCount = 0
                            isBottomLocked = false
                            if (decision.text.isNotBlank()) {
                                openAppByName(decision.text)
                                realActionsCount++
                            }
                            scheduleNextLoop(1500L)
                        }
                        "OPEN_CAMERA" -> {
                            staleScrollDownCount = 0
                            staleScrollUpCount = 0
                            isBottomLocked = false
                            openSystemCamera()
                            realActionsCount++
                            scheduleNextLoop(1400L)
                        }
                        "TAP_NORM" -> {
                            staleScrollDownCount = 0
                            staleScrollUpCount = 0
                            if (decision.normX in 0..1000 && decision.normY in 0..1000) {
                                val rx = normToRealX(decision.normX)
                                val ry = normToRealY(decision.normY)
                                performGhostTouch(rx, ry)
                                realActionsCount++
                            }
                            scheduleNextLoop(950L)
                        }
                        "TYPE_TEXT" -> {
                            if (hasSentFinalMessage) {
                                completeAndAdvanceSubTask()
                                return@post
                            }
                            staleScrollDownCount = 0
                            staleScrollUpCount = 0
                            val rx = if (decision.normX in 0..1000) normToRealX(decision.normX) else -1
                            val ry = if (decision.normY in 0..1000) normToRealY(decision.normY) else -1
                            val chosenNode = indexedNodes.getOrNull(decision.index)
                            val node = if (chosenNode != null && chosenNode.isEditable) {
                                chosenNode
                            } else {
                                findInputNodeNear(rx, ry)
                                    ?: indexedNodes.firstOrNull { it.isEditable && it.text.isNullOrBlank() }
                                    ?: indexedNodes.firstOrNull { it.isEditable }
                            }

                            val existingBoxText = node?.text?.toString()?.trim().orEmpty()
                            if (existingBoxText.isNotBlank() && existingBoxText.equals(decision.text.trim(), true)) {
                                blockedActionSignatures.add(sig)
                                completedMilestones.add("Typed: ${decision.text.take(25)}")
                                scheduleNextLoop(400L)
                                return@post
                            }

                            if (isPhoneOrPinInputContext(screenDesc, node) && decision.text.isBlank()) {
                                val savedPhone = manager.getSavedPhoneNumber()
                                val screenLow = screenDesc.lowercase()
                                val isPhoneScreen = screenLow.contains("phone") || screenLow.contains("mobile") || screenLow.contains("फ़ोन")
                                if (isPhoneScreen && savedPhone.length >= 10 && node != null) {
                                    val r = Rect()
                                    node.getBoundsInScreen(r)
                                    showVisualTouchDot(r.centerX(), r.centerY())
                                    node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                                    typeTextIntoNode(node, savedPhone, decision.pressEnter)
                                    blockedActionSignatures.add(sig)
                                    completedMilestones.add("Filled saved phone number")
                                    realActionsCount++
                                    scheduleNextLoop(850L)
                                    return@post
                                } else {
                                    promptUserWithVoiceAndMiniBox(
                                        "कृपया नंबर या पिन बताएं, या नीचे टाइप करके Run दबाएं",
                                        "नंबर / पिन / जानकारी लिखें..."
                                    )
                                    return@post
                                }
                            }

                            if (node != null) {
                                val r = Rect()
                                node.getBoundsInScreen(r)
                                showVisualTouchDot(r.centerX(), r.centerY())
                                node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                                typeTextIntoNode(node, decision.text, decision.pressEnter)
                                blockedActionSignatures.add(sig)
                                completedMilestones.add("Typed '${decision.text.take(25)}'")
                                realActionsCount++
                            } else if (rx > 0 && ry > 0) {
                                performGhostTouch(rx, ry)
                                realActionsCount++
                            }
                            scheduleNextLoop(if (decision.pressEnter) 1100L else 750L)
                        }
                        "CLICK_INDEX" -> {
                            staleScrollDownCount = 0
                            staleScrollUpCount = 0
                            val node = indexedNodes.getOrNull(decision.index)
                            var delayAfterClick = 850L
                            if (node != null) {
                                val nodeLabel = (node.text?.toString() ?: node.contentDescription?.toString() ?: "").trim()
                                val normLabel = nodeLabel.lowercase()

                                if (normLabel.isNotBlank() && normLabel in completedActionLabels && isSingleActionButton(nodeLabel)) {
                                    subTaskHistory.add("ALREADY_COMPLETED_ACTION: '$nodeLabel' already done!")
                                    scheduleNextLoop(400L)
                                    return@post
                                }

                                val rect = Rect()
                                node.getBoundsInScreen(rect)
                                showVisualTouchDot(rect.centerX(), rect.centerY())
                                val nativeOk = clickSelfOrParent(node)
                                val touchX = if (rect.centerX() > 0) rect.centerX() else if (decision.normX in 0..1000) normToRealX(decision.normX) else -1
                                val touchY = if (rect.centerY() > 0) rect.centerY() else if (decision.normY in 0..1000) normToRealY(decision.normY) else -1
                                if (!nativeOk && touchX > 0 && touchY > 0) {
                                    performGhostTouch(touchX, touchY)
                                }
                                realActionsCount++

                                val (sw, sh) = getPhysicalScreenSize()
                                val isBottomRightSendIcon = rect.left > sw * 0.78 && rect.top > sh * 0.45 &&
                                    completedMilestones.any { it.startsWith("Typed") }
                                val isSendAction = normLabel.contains("send") || normLabel.contains("भेज") || isBottomRightSendIcon

                                if (isSendAction) {
                                    hasSentFinalMessage = true
                                    completedActionLabels.add("send")
                                    completedMilestones.add("Message sent successfully")
                                    handler.postDelayed({ completeAndAdvanceSubTask() }, 650L)
                                    return@post
                                }

                                if (normLabel.isNotBlank()) {
                                    completedActionLabels.add(normLabel)
                                }
                                if (isSingleActionButton(nodeLabel)) {
                                    blockedActionSignatures.add(sig)
                                    completedMilestones.add("Completed action: $nodeLabel")
                                    delayAfterClick = 1350L
                                }
                            } else if (decision.normX in 0..1000 && decision.normY in 0..1000) {
                                performGhostTouch(normToRealX(decision.normX), normToRealY(decision.normY))
                                realActionsCount++
                            }
                            scheduleNextLoop(delayAfterClick)
                        }
                        "SPEAK_AND_DONE" -> {
                            if (!verifyGoalOnActualScreen(screenDesc)) {
                                subTaskHistory.add("REJECTED_PREMATURE_DONE: Goal is NOT complete yet! Execute remaining steps.")
                                scheduleNextLoop(500L)
                            } else {
                                if (decision.text.isNotBlank()) {
                                    currentTaskDoneReply = decision.text
                                }
                                completeAndAdvanceSubTask()
                            }
                        }
                        "ASK_USER" -> {
                            staleScrollDownCount = 0
                            staleScrollUpCount = 0
                            val q = decision.questionForUser.ifBlank { "कृपया विकल्प चुनें या जानकारी बताएं" }
                            promptUserWithVoiceAndMiniBox(q, "यहाँ लिखें या बोलें...")
                        }
                        "SCROLL_DOWN" -> {
                            staleScrollUpCount = 0
                            if (isBottomLocked) {
                                subTaskHistory.add("SCROLL_DOWN_DISABLED: Page bottom already reached! Do NOT scroll down—use BACK or Search.")
                                scheduleNextLoop(450L)
                                return@post
                            }
                            if (!screenChanged) staleScrollDownCount++ else staleScrollDownCount = 0
                            if (staleScrollDownCount >= 2) {
                                isBottomLocked = true
                                staleScrollDownCount = 0
                                subTaskHistory.add("BOTTOM_REACHED_LOCKED: No more items below! Use BACK or Search bar now.")
                                scheduleNextLoop(450L)
                            } else {
                                performSwipe(true)
                                realActionsCount++
                                scheduleNextLoop(750L)
                            }
                        }
                        "SCROLL_UP" -> {
                            staleScrollDownCount = 0
                            if (!screenChanged) staleScrollUpCount++ else staleScrollUpCount = 0
                            if (staleScrollUpCount >= 3) {
                                subTaskHistory.add("TOP_REACHED: Screen is already at the very top.")
                                scheduleNextLoop(450L)
                            } else {
                                performSwipe(false)
                                realActionsCount++
                                scheduleNextLoop(750L)
                            }
                        }
                        "BACK" -> {
                            staleScrollDownCount = 0
                            staleScrollUpCount = 0
                            isBottomLocked = false
                            performGlobalAction(GLOBAL_ACTION_BACK)
                            realActionsCount++
                            scheduleNextLoop(750L)
                        }
                        "SUBTASK_DONE" -> {
                            if (decision.goalVerified && verifyGoalOnActualScreen(screenDesc)) {
                                if (decision.text.isNotBlank()) {
                                    currentTaskDoneReply = decision.text
                                }
                                completeAndAdvanceSubTask()
                            } else {
                                subTaskHistory.add("REJECTED_PREMATURE_SUBTASK_DONE: Keep working until step is verified!")
                                scheduleNextLoop(500L)
                            }
                        }
                        "FAILED_STUCK" -> {
                            val reason = decision.text.ifBlank { "यह काम पूरा नहीं हो पाया, विकल्प नहीं मिला।" }
                            stopAgentWithHonestReason(reason)
                        }
                        else -> stopAgentWithHonestReason("यह काम पूरा नहीं हो पाया।")
                    }
                }
            } catch (e: Exception) {
                handler.post {
                    isThinking = false
                    retryCount++
                    val titles = subTaskQueue.map { it.title }
                    updateTreeTimeline(null, "🔄 ${e.message ?: "री-ट्राई"} ($retryCount)...", titles, activeSubTaskIndex)
                    scheduleNextLoop(900L)
                }
            }
        }
    }

    private fun stopAgentWithHonestReason(honestMessage: String) {
        handler.removeCallbacks(agentLoopRunnable)
        isAgentRunning = false
        isThinking = false
        pendingFormQuestion = null
        showMiniQuickReplyBox(false)
        expandFromCornerPill()
        releaseAgentWakeLock()
        rootContainer?.visibility = View.VISIBLE
        setOrbVisualState(ProAiOrbView.State.IDLE)
        logHistoryEntry(overallUserGoal, honestMessage, false)
        val titles = subTaskQueue.map { it.title }
        updateTreeTimeline(null, "⚠️ $honestMessage", titles, activeSubTaskIndex)
        speakOnce(honestMessage)
    }

    private fun performGhostTouch(x: Int, y: Int) {
        handler.post {
            rootContainer?.visibility = View.INVISIBLE
            showVisualTouchDot(x, y)
            handler.postDelayed({
                tapAtCoordinates(x.toFloat(), y.toFloat())
                handler.postDelayed({
                    rootContainer?.visibility = View.VISIBLE
                }, 180L)
            }, 30L)
        }
    }

    private fun findInputNodeNear(x: Int, y: Int): AccessibilityNodeInfo? {
        if (x <= 0 || y <= 0) return null
        return indexedNodes.filter { it.isEditable }.minByOrNull { node ->
            val r = Rect()
            node.getBoundsInScreen(r)
            abs(r.centerX() - x) + abs(r.centerY() - y)
        }
    }

    private fun typeTextIntoNode(node: AccessibilityNodeInfo, value: String, pressEnter: Boolean) {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (pressEnter && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            handler.postDelayed({
                node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            }, 140L)
        }
    }

    private fun showVisualTouchDot(x: Int, y: Int) {
        if (x <= 0 || y <= 0) return
        handler.post {
            try {
                val dotSize = (36 * resources.displayMetrics.density).toInt()
                val dot = View(this).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.parseColor("#8800E5FF"))
                        setStroke(5, Color.parseColor("#00E5FF"))
                    }
                }
                val params = WindowManager.LayoutParams(
                    dotSize, dotSize,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    this.x = x - dotSize / 2
                    this.y = y - dotSize / 2
                }
                windowManager?.addView(dot, params)
                dot.animate().scaleX(1.45f).scaleY(1.45f).alpha(0f).setDuration(300L).withEndAction {
                    try { windowManager?.removeView(dot) } catch (_: Exception) { }
                }.start()
            } catch (_: Exception) { }
        }
    }

    private fun tapAtCoordinates(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 150))
            .build()
        dispatchGesture(gesture, null, null)
    }

    private fun collectNodes(node: AccessibilityNodeInfo?, sb: StringBuilder) {
        if (node == null || indexedNodes.size >= 160) return
        if (node.isVisibleToUser) {
            val text = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) node.hintText?.toString()?.trim().orEmpty() else ""
            var label = text.ifBlank { desc }.ifBlank { hint }

            if (label.isBlank() && node.isClickable && !node.isEditable) {
                label = findSiblingText(node)
            }
            if (node.isCheckable && label.isBlank()) {
                label = findSiblingText(node.parent)
            }

            val isSpinner = node.className?.toString()?.contains("ProgressBar", true) == true
            if (isSpinner && label.isBlank()) {
                label = "LOADING_SPINNER (चकरी घूम रही है)"
            }

            val hasUsefulInfo = label.isNotBlank() || node.isEditable || node.isCheckable || isSpinner
            if (hasUsefulInfo) {
                val idx = indexedNodes.size
                indexedNodes.add(node)
                val rect = Rect()
                node.getBoundsInScreen(rect)
                val nx = realToNormX(rect.centerX())
                val ny = realToNormY(rect.centerY())

                val type = when {
                    isSpinner -> "SPINNER"
                    node.isEditable -> "INPUT_BOX"
                    node.isCheckable -> "SWITCH"
                    node.isClickable -> "BUTTON"
                    else -> "TEXT"
                }
                val stateTag = if (node.isCheckable) {
                    if (node.isChecked) " [STATE: ON]" else " [STATE: OFF]"
                } else ""
                val currentValTag = if (node.isEditable) {
                    if (text.isNotBlank()) " [CURRENT_TEXT: \"$text\"]" else " [EMPTY_BOX]"
                } else ""

                val cleanLabel = label.replace("\n", " ").take(80)
                sb.append("[$idx] $type ($nx,$ny): \"$cleanLabel\"$stateTag$currentValTag\n")
            }
        }
        for (i in 0 until node.childCount) {
            collectNodes(node.getChild(i), sb)
        }
    }

    private fun findSiblingText(parent: AccessibilityNodeInfo?): String {
        if (parent == null) return ""
        val parts = mutableListOf<String>()
        for (i in 0 until parent.childCount) {
            val child = parent.getChild(i) ?: continue
            val t = child.text?.toString()?.trim().orEmpty()
            if (t.isNotBlank() && !t.equals("On", true) && !t.equals("Off", true)) parts.add(t)
            for (j in 0 until child.childCount) {
                val grand = child.getChild(j)?.text?.toString()?.trim().orEmpty()
                if (grand.isNotBlank() && !grand.equals("On", true) && !grand.equals("Off", true)) parts.add(grand)
            }
        }
        return parts.distinct().joinToString(" - ")
    }

    private fun clickSelfOrParent(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < 5) {
            if (current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            current = current.parent
            depth++
        }
        return false
    }

    private fun performSwipe(down: Boolean) {
        val (w, h) = getPhysicalScreenSize()
        val x = w / 2f
        val startY = if (down) h * 0.78f else h * 0.44f
        val endY = if (down) h * 0.34f else h * 0.84f
        val path = Path().apply { moveTo(x, startY); lineTo(x, endY) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
            .build()
        dispatchGesture(gesture, null, null)
    }

    private fun stopAgent(completed: Boolean) {
        handler.removeCallbacks(agentLoopRunnable)
        isAgentRunning = false
        isThinking = false
        pendingFormQuestion = null
        showMiniQuickReplyBox(false)
        expandFromCornerPill()
        releaseAgentWakeLock()
        rootContainer?.visibility = View.VISIBLE
        setOrbVisualState(ProAiOrbView.State.IDLE)
        val titles = subTaskQueue.map { it.title }
        if (completed) {
            val finalSummary = if (extractedNotes.isNotEmpty()) {
                "$currentTaskDoneReply (${extractedNotes.joinToString("; ")})"
            } else {
                currentTaskDoneReply
            }
            logHistoryEntry(overallUserGoal, finalSummary, true)
            updateTreeTimeline(null, "✅ $finalSummary", titles, titles.size)
            speakOnce(finalSummary)
        } else {
            logHistoryEntry(overallUserGoal, "एजेंट रोका गया", false)
            updateTreeTimeline(null, "🛑 एजेंट रोका गया", titles, activeSubTaskIndex)
        }
    }

    private fun startWaveCallCutSensor() {
        proximitySensor?.let {
            isWaveCutActive = true
            wasNear = false
            waveCount = 0
            firstWaveTime = 0L
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    private fun stopWaveCallCutSensor() {
        if (isWaveCutActive) {
            isWaveCutActive = false
            sensorManager?.unregisterListener(this)
        }
    }

    @SuppressLint("MissingPermission")
    override fun onSensorChanged(event: SensorEvent?) {
        if (!isWaveCutActive || event?.sensor?.type != Sensor.TYPE_PROXIMITY) return
        val maxRange = proximitySensor?.maximumRange ?: 5f
        val distance = event.values[0]
        val isNear = distance == 0f || distance < maxRange

        if (isNear && !wasNear) {
            wasNear = true
        } else if (!isNear && wasNear) {
            wasNear = false
            val now = System.currentTimeMillis()
            if (waveCount == 0 || now - firstWaveTime > 3000L) {
                waveCount = 1
                firstWaveTime = now
            } else {
                waveCount++
                if (waveCount >= 2) {
                    var ended = false
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                            ContextCompat.checkSelfPermission(this, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED
                        ) {
                            val tm = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
                            @Suppress("DEPRECATION")
                            ended = tm.endCall()
                        }
                    } catch (_: Exception) { }

                    if (!ended) {
                        ended = clickEndCallButtonOnScreen(rootInActiveWindow)
                    }

                    val msg = if (ended) "कॉल काट दी गई है।" else "कॉल काटने के लिए Phone परमिशन Allow करें।"
                    updateTreeTimeline(null, msg, emptyList(), -1)
                    speakOnce(msg)
                    stopWaveCallCutSensor()
                }
            }
        }
    }

    private fun clickEndCallButtonOnScreen(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        val text = node.text?.toString().orEmpty()
        val desc = node.contentDescription?.toString().orEmpty()
        val combined = "$text $desc".lowercase()
        if (combined.contains("end call") || combined.contains("disconnect") || combined.contains("hang up")) {
            if (clickSelfOrParent(node)) return true
        }
        for (i in 0 until node.childCount) {
            if (clickEndCallButtonOnScreen(node.getChild(i))) return true
        }
        return false
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun removeProOverlay() {
        showBottomDismissZone(false)
        try { rootContainer?.let { windowManager?.removeView(it) } } catch (_: Exception) { }
        rootContainer = null
        proOrbView = null
        miniCornerPill = null
        overlayParams = null
    }

    companion object {
        const val PREFS = "universal_agent_prefs"
        const val KEY_ACTIVE = "agent_active"
        const val KEY_GOAL = "agent_goal"
        const val KEY_SHOW_ORB = "show_siri_orb"
        const val KEY_HIDE_ORB = "hide_siri_orb"
        const val KEY_ORB_ENABLED = "orb_enabled"
    }
}
