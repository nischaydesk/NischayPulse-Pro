package com.prince.aiassistant

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray

class MainActivity : Activity() {

    companion object {
        const val WEB_CLIENT_ID = "699686277295-1sgitrrfof9cnv4btgeltprdfskdl918.apps.googleusercontent.com"
        private const val AUTH_PREFS = "nischay_auth_prefs"
    }

    private val uiScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var credentialManager: CredentialManager? = null

    private var isDarkMode = true
    private var activeTab = 0 // 0 = Home, 1 = Schedule, 2 = History, 3 = Settings
    private var splashShown = false

    private lateinit var rootFrame: LinearLayout
    private lateinit var contentContainer: FrameLayout
    private lateinit var themeToggleBtn: TextView
    private lateinit var engineBadge: TextView

    // Tab 0 (Home)
    private lateinit var homeView: View
    private lateinit var heroOrbBadge: TextView
    private lateinit var floatingOrbSwitch: Switch
    private lateinit var commandInput: EditText

    // Tab 1 (Schedule)
    private lateinit var scheduleView: View
    private lateinit var scheduledListContainer: LinearLayout

    // Tab 2 (History)
    private lateinit var historyView: View
    private lateinit var historyListContainer: LinearLayout

    // Tab 3 (Settings)
    private lateinit var settingsView: View
    private lateinit var providerSpinner: Spinner
    private lateinit var apiKeyInput: EditText
    private lateinit var personalitySpinner: Spinner
    private lateinit var speedSeekBar: SeekBar
    private lateinit var speedLabel: TextView
    private lateinit var nameEnInput: EditText
    private lateinit var nameHiInput: EditText
    private lateinit var addressInput: EditText
    private lateinit var phoneInput: EditText

    // Bottom 4 Menus
    private lateinit var navHomeBtn: TextView
    private lateinit var navScheduleBtn: TextView
    private lateinit var navHistoryBtn: TextView
    private lateinit var navSettingsBtn: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            credentialManager = CredentialManager.create(this)
        } catch (_: Throwable) {
            credentialManager = null
        }

        isDarkMode = try {
            getSharedPreferences("assistant_settings", MODE_PRIVATE).getBoolean("dark_mode", true)
        } catch (_: Exception) {
            true
        }

        try {
            if (!splashShown) {
                splashShown = true
                showCustomSplashScreen()
            } else {
                routeAfterSplash()
            }
        } catch (e: Throwable) {
            showCrashRecoveryScreen(e)
        }
    }

    private fun showCrashRecoveryScreen(err: Throwable) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#050811"))
            setPadding(32, 32, 32, 32)
        }
        val tv = TextView(this).apply {
            text = "Nischay Pulse Error:\n\n${err.javaClass.simpleName}: ${err.message}"
            textSize = 13f
            setTextColor(Color.parseColor("#FACC15"))
        }
        val btn = Button(this).apply {
            text = "डैशबोर्ड खोलें"
            setOnClickListener {
                try {
                    rebuildEntireScreen()
                } catch (_: Throwable) { }
            }
        }
        box.addView(tv)
        box.addView(btn)
        setContentView(box)
    }

    override fun onResume() {
        super.onResume()
        if (::engineBadge.isInitialized) {
            try {
                updateLiveStates()
            } catch (_: Exception) { }
        }
    }

    private fun makeOrbCircleView(sizeDp: Int, label: String, textSp: Float): TextView {
        val px = (sizeDp * resources.displayMetrics.density).toInt()
        return TextView(this).apply {
            text = label
            textSize = textSp
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(px, px)
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor("#0EA5E9"), Color.parseColor("#6366F1"), Color.parseColor("#10B981"))
            ).apply {
                shape = GradientDrawable.OVAL
                setStroke(6, Color.parseColor("#38BDF8"))
            }
        }
    }

    private fun showCustomSplashScreen() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#050811"))
            setPadding(40, 40, 40, 40)
        }

        val logoCircle = makeOrbCircleView(130, "N", 54f)

        val title = TextView(this).apply {
            text = "Nischay Pulse"
            textSize = 28f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 28, 0, 8)
        }

        val sub = TextView(this).apply {
            text = "Zero-Guessing Autonomous AI Agent"
            textSize = 13.5f
            setTextColor(Color.parseColor("#38BDF8"))
            gravity = Gravity.CENTER
        }

        container.addView(logoCircle)
        container.addView(title)
        container.addView(sub)
        setContentView(container)

        Handler(Looper.getMainLooper()).postDelayed({
            if (!isFinishing && !isDestroyed) {
                try {
                    routeAfterSplash()
                } catch (e: Throwable) {
                    showCrashRecoveryScreen(e)
                }
            }
        }, 1100L)
    }

    private fun routeAfterSplash() {
        val auth = getSharedPreferences(AUTH_PREFS, MODE_PRIVATE)
        val privacyAccepted = auth.getBoolean("privacy_accepted", false)
        val signedInEmail = auth.getString("user_email", "").orEmpty()

        when {
            !privacyAccepted -> showWelcomeAndPrivacyScreen()
            signedInEmail.isBlank() -> showGoogleSignInScreen()
            else -> {
                requestEssentialPermissions()
                rebuildEntireScreen()
            }
        }
    }

    private fun showWelcomeAndPrivacyScreen() {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#050811"))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(34, 48, 34, 48)
        }

        val badge = TextView(this).apply {
            text = "NISCHAY PULSE • PRIVACY & TERMS"
            textSize = 11.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#38BDF8"))
        }

        val heading = TextView(this).apply {
            text = "स्वागत है, Prince भाई ✨"
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(0, 8, 0, 16)
        }

        val policyCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 22, 24, 22)
            background = pillBg("#0F172A", "#1E293B")
        }

        val policyBody = TextView(this).apply {
            text = """
                1. Accessibility व On-Device ऑटोमेशन:
                Nischay Pulse केवल आपके द्वारा दिए गए वॉयस या टेक्स्ट निर्देशों को पूरा करने के लिए स्क्रीन के UI तत्वों को पढ़ता है और टैप/स्क्रॉल करता है।

                2. Zero-Guessing सुरक्षा गारंटी:
                महत्वपूर्ण विकल्प (जैसे SIM चयन, पेमेंट, PIN, पासवर्ड या संदिग्ध बटन) आने पर एजेंट कभी भी अपने मन से तुक्का नहीं मारता—वह आपसे स्पष्ट अनुमति या उत्तर पूछता है।

                3. डेटा गोपनीयता (100% लोकल स्टोरेज):
                आपकी Gemini/Groq API Keys, प्रोफ़ाइल विवरण और टास्क हिस्ट्री केवल आपके फ़ोन की सुरक्षित स्थानीय मेमोरी में रहती है।

                4. सेवा की शर्तें (Terms of Service):
                इस ऐप का उपयोग केवल वैध, सुरक्षित और व्यक्तिगत उत्पादकता कार्यों के लिए किया जाएगा।
            """.trimIndent()
            textSize = 13f
            setTextColor(Color.parseColor("#CBD5E1"))
        }
        policyCard.addView(policyBody)

        val agreeCheck = CheckBox(this).apply {
            text = "मैं Privacy Policy और Terms of Service से सहमत हूँ।"
            textSize = 13.5f
            setTextColor(Color.WHITE)
            setPadding(8, 22, 8, 22)
        }

        val continueBtn = Button(this).apply {
            text = "आगे बढ़ें →"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.parseColor("#0EA5E9"), Color.parseColor("#6366F1"))
            ).apply { cornerRadius = 28f }
            setOnClickListener {
                if (!agreeCheck.isChecked) {
                    Toast.makeText(this@MainActivity, "कृपया पहले सहमति बॉक्स पर टिक करें", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                getSharedPreferences(AUTH_PREFS, MODE_PRIVATE)
                    .edit().putBoolean("privacy_accepted", true).apply()
                showGoogleSignInScreen()
            }
        }

        root.addView(badge)
        root.addView(heading)
        root.addView(policyCard)
        root.addView(agreeCheck)
        root.addView(continueBtn)
        scroll.addView(root)
        setContentView(scroll)
    }

    private fun showGoogleSignInScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#050811"))
            setPadding(36, 48, 36, 48)
        }

        val logoCircle = makeOrbCircleView(110, "N", 46f)

        val title = TextView(this).apply {
            text = "Google अकाउंट से जुड़ें"
            textSize = 23f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 10)
        }

        val desc = TextView(this).apply {
            text = "अपने फ़ोन के Google अकाउंट से तुरंत साइन-इन करें ताकि आपकी प्रोफ़ाइल और सेटिंग्स सुरक्षित रहें।"
            textSize = 13.5f
            setTextColor(Color.parseColor("#94A3B8"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 28)
        }

        val statusTv = TextView(this).apply {
            textSize = 12.5f
            setTextColor(Color.parseColor("#FACC15"))
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(16, 12, 16, 18)
        }

        val googleBtn = Button(this).apply {
            text = "G   Continue with Google"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#0F172A"))
            setPadding(32, 24, 32, 24)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = 32f
            }
            setOnClickListener {
                launchNativeGoogleAccountPicker(statusTv)
            }
        }

        val skipBtn = TextView(this).apply {
            text = "अभी के लिए बिना साइन-इन आगे बढ़ें →"
            textSize = 13f
            setTextColor(Color.parseColor("#38BDF8"))
            gravity = Gravity.CENTER
            setPadding(16, 28, 16, 12)
            setOnClickListener {
                getSharedPreferences(AUTH_PREFS, MODE_PRIVATE)
                    .edit()
                    .putString("user_email", "guest@nischaypulse.local")
                    .putString("user_name", "Prince Bhai")
                    .apply()
                requestEssentialPermissions()
                rebuildEntireScreen()
            }
        }

        root.addView(logoCircle)
        root.addView(title)
        root.addView(desc)
        root.addView(statusTv)
        root.addView(googleBtn)
        root.addView(skipBtn)
        setContentView(root)
    }

    private fun launchNativeGoogleAccountPicker(statusTv: TextView) {
        statusTv.visibility = View.VISIBLE
        val cm = credentialManager
        if (cm == null) {
            statusTv.text = "Credential Manager उपलब्ध नहीं है, नीचे से सीधे आगे बढ़ें।"
            return
        }
        statusTv.text = "Google अकाउंट सूची खुल रही है..."

        try {
            val googleIdOption = GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(false)
                .setServerClientId(WEB_CLIENT_ID)
                .setAutoSelectEnabled(false)
                .build()

            val request = GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOption)
                .build()

            uiScope.launch {
                try {
                    val result = cm.getCredential(
                        request = request,
                        context = this@MainActivity
                    )
                    val credential = result.credential
                    if (credential is CustomCredential &&
                        credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                    ) {
                        val googleCred = GoogleIdTokenCredential.createFrom(credential.data)
                        val email = googleCred.id
                        val displayName = googleCred.displayName ?: email.substringBefore("@")

                        getSharedPreferences(AUTH_PREFS, MODE_PRIVATE)
                            .edit()
                            .putString("user_email", email)
                            .putString("user_name", displayName)
                            .apply()

                        val vault = getSharedPreferences("user_profile_vault", MODE_PRIVATE)
                        if (vault.getString("name_en", "").isNullOrBlank()) {
                            vault.edit().putString("name_en", displayName).apply()
                        }

                        Toast.makeText(this@MainActivity, "स्वागत है, $displayName ✨", Toast.LENGTH_SHORT).show()
                        requestEssentialPermissions()
                        rebuildEntireScreen()
                    } else {
                        statusTv.text = "अकाउंट प्रकार समर्थित नहीं है, पुनः प्रयास करें।"
                    }
                } catch (e: GetCredentialException) {
                    statusTv.text = "साइन-इन संदेश: ${e.message ?: "Google Cloud Clients में SHA-1 जोड़ें या नीचे से आगे बढ़ें"}"
                } catch (e: Throwable) {
                    statusTv.text = "एरर: ${e.message}"
                }
            }
        } catch (e: Throwable) {
            statusTv.text = "Google Sign-In त्रुटि: ${e.message}"
        }
    }

    private fun requestEssentialPermissions() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val perms = mutableListOf(
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.CALL_PHONE,
                    Manifest.permission.READ_CONTACTS
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    perms.add(Manifest.permission.ANSWER_PHONE_CALLS)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    perms.add(Manifest.permission.POST_NOTIFICATIONS)
                }
                val missing = perms.filter {
                    checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
                }
                if (missing.isNotEmpty()) {
                    requestPermissions(missing.toTypedArray(), 101)
                }
            }
        } catch (_: Exception) { }
    }

    private fun isAccessibilityEnabled(): Boolean {
        return try {
            val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            val list = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            list.any { it.resolveInfo.serviceInfo.packageName == packageName }
        } catch (_: Exception) {
            false
        }
    }

    private fun rebuildEntireScreen() {
        val bgMain = if (isDarkMode) Color.parseColor("#050811") else Color.parseColor("#F8FAFC")
        val bgBarHex = if (isDarkMode) "#0B1120" else "#FFFFFF"
        val bgBar = Color.parseColor(bgBarHex)
        val textPrimary = if (isDarkMode) Color.WHITE else Color.parseColor("#0F172A")
        val borderHex = if (isDarkMode) "#1E293B" else "#E2E8F0"

        rootFrame = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgMain)
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(34, 34, 30, 22)
        }

        val brandCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val titleTv = TextView(this).apply {
            text = "Nischay Pulse"
            textSize = 23f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(textPrimary)
        }
        engineBadge = TextView(this).apply {
            textSize = 12f
            setPadding(0, 4, 0, 0)
        }
        brandCol.addView(titleTv)
        brandCol.addView(engineBadge)

        themeToggleBtn = TextView(this).apply {
            text = if (isDarkMode) "☀️ Light" else "🌙 Dark"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(textPrimary)
            setPadding(24, 14, 24, 14)
            background = pillBg(bgBarHex, borderHex)
            setOnClickListener {
                isDarkMode = !isDarkMode
                getSharedPreferences("assistant_settings", MODE_PRIVATE)
                    .edit().putBoolean("dark_mode", isDarkMode).apply()
                rebuildEntireScreen()
            }
        }

        topBar.addView(brandCol)
        topBar.addView(themeToggleBtn)

        contentContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }

        homeView = buildHomeTab()
        scheduleView = buildScheduleTab()
        historyView = buildHistoryTab()
        settingsView = buildSettingsTab()

        contentContainer.addView(homeView)
        contentContainer.addView(scheduleView)
        contentContainer.addView(historyView)
        contentContainer.addView(settingsView)

        val bottomNav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(bgBar)
            setPadding(12, 16, 12, 20)
        }

        navHomeBtn = createNavMenuItem("⚡ Home", 0)
        navScheduleBtn = createNavMenuItem("⏰ Schedule", 1)
        navHistoryBtn = createNavMenuItem("📜 History", 2)
        navSettingsBtn = createNavMenuItem("⚙️ Settings", 3)

        bottomNav.addView(navHomeBtn)
        bottomNav.addView(navScheduleBtn)
        bottomNav.addView(navHistoryBtn)
        bottomNav.addView(navSettingsBtn)

        rootFrame.addView(topBar)
        rootFrame.addView(contentContainer)
        rootFrame.addView(bottomNav)

        setContentView(rootFrame)
        loadSavedSettingsAndProfile()
        switchTab(activeTab)
        updateLiveStates()
    }

    private fun createNavMenuItem(label: String, tabIndex: Int): TextView {
        return TextView(this).apply {
            text = label
            textSize = 12.5f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(8, 16, 8, 16)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { switchTab(tabIndex) }
        }
    }

    private fun switchTab(index: Int) {
        activeTab = index
        homeView.visibility = if (index == 0) View.VISIBLE else View.GONE
        scheduleView.visibility = if (index == 1) View.VISIBLE else View.GONE
        historyView.visibility = if (index == 2) View.VISIBLE else View.GONE
        settingsView.visibility = if (index == 3) View.VISIBLE else View.GONE

        val inactiveText = if (isDarkMode) Color.parseColor("#94A3B8") else Color.parseColor("#64748B")

        listOf(navHomeBtn, navScheduleBtn, navHistoryBtn, navSettingsBtn).forEachIndexed { idx, tv ->
            if (idx == index) {
                tv.setTextColor(Color.WHITE)
                tv.background = pillBg("#0EA5E9", "#38BDF8")
            } else {
                tv.setTextColor(inactiveText)
                tv.background = null
            }
        }
        if (index == 1 || index == 2) updateLiveStates()
    }

    private fun buildHomeTab(): View {
        val textPrimary = if (isDarkMode) Color.WHITE else Color.parseColor("#0F172A")
        val textSub = if (isDarkMode) Color.parseColor("#94A3B8") else Color.parseColor("#475569")
        val surfaceBg = if (isDarkMode) "#0F172A" else "#FFFFFF"
        val borderCol = if (isDarkMode) "#1E293B" else "#CBD5E1"

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(32, 12, 32, 20)
        }

        val toggleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(28, 18, 28, 18)
            background = pillBg(surfaceBg, borderCol)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val toggleLabel = TextView(this).apply {
            text = "फ्लोटिंग 'N' ऑर्ब (ON / OFF)"
            textSize = 14.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(textPrimary)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        floatingOrbSwitch = Switch(this)
        toggleRow.addView(toggleLabel)
        toggleRow.addView(floatingOrbSwitch)

        heroOrbBadge = makeOrbCircleView(160, "⚡ N", 44f).apply {
            (layoutParams as LinearLayout.LayoutParams).apply {
                topMargin = 28
                bottomMargin = 14
            }
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                } else if (!isAccessibilityEnabled()) {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                } else {
                    getSharedPreferences(WhatsAppHelperAccessibilityService.PREFS, MODE_PRIVATE)
                        .edit().putBoolean(WhatsAppHelperAccessibilityService.KEY_SHOW_ORB, true).apply()
                    Toast.makeText(this@MainActivity, "स्क्रीन पर फ़्लोटिंग ऑर्ब दबाकर बोलें", Toast.LENGTH_SHORT).show()
                }
            }
        }

        val hintTv = TextView(this).apply {
            text = "Zero-Guessing मोड सक्रिय • विकल्प आने पर पहले आपसे पूछेगा"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(textSub)
            setPadding(0, 0, 0, 22)
        }

        val chipScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = 18
            }
        }
        val chipRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val quickSamples = listOf(
            "🔦 टॉर्च चालू करो",
            "🎬 इंस्टाग्राम रील 15 सेकंड में स्क्रॉल करो",
            "▶️ यूट्यूब शॉर्ट्स 20 सेकंड में स्क्रॉल करो",
            "📸 स्क्रीनशॉट लो",
            "🔇 साइलेंट मोड"
        )
        quickSamples.forEach { sample ->
            val cleanCmd = sample.substringAfter(" ")
            val chip = TextView(this).apply {
                text = sample
                textSize = 12.5f
                setTextColor(textPrimary)
                setPadding(24, 14, 24, 14)
                background = pillBg(surfaceBg, borderCol)
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = 14
                }
                layoutParams = lp
                setOnClickListener { sendCommandToAgent(cleanCmd) }
            }
            chipRow.addView(chip)
        }
        chipScroll.addView(chipRow)

        val spacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }

        val inputBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(18, 10, 12, 10)
            background = pillBg(surfaceBg, "#0EA5E9")
        }
        commandInput = EditText(this).apply {
            hint = "कोई भी काम लिखें या बोलें..."
            setHintTextColor(textSub)
            setTextColor(textPrimary)
            textSize = 14f
            background = null
            setPadding(16, 14, 16, 14)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val sendBtn = TextView(this).apply {
            text = "➤"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            val btnSize = (44 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(btnSize, btnSize)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#0EA5E9"))
            }
            setOnClickListener {
                val cmd = commandInput.text.toString().trim()
                if (cmd.isNotBlank()) {
                    commandInput.setText("")
                    sendCommandToAgent(cmd)
                }
            }
        }
        inputBar.addView(commandInput)
        inputBar.addView(sendBtn)

        layout.addView(toggleRow)
        layout.addView(heroOrbBadge)
        layout.addView(hintTv)
        layout.addView(chipScroll)
        layout.addView(spacer)
        layout.addView(inputBar)
        return layout
    }

    private fun sendCommandToAgent(cmd: String) {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            Toast.makeText(this, "पहले Overlay परमिशन चालू करें", Toast.LENGTH_SHORT).show()
            return
        }
        if (!isAccessibilityEnabled()) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, "पहले Accessibility चालू करें", Toast.LENGTH_SHORT).show()
            return
        }
        getSharedPreferences(WhatsAppHelperAccessibilityService.PREFS, MODE_PRIVATE)
            .edit()
            .putBoolean(WhatsAppHelperAccessibilityService.KEY_ORB_ENABLED, true)
            .putBoolean(WhatsAppHelperAccessibilityService.KEY_SHOW_ORB, true)
            .putBoolean(WhatsAppHelperAccessibilityService.KEY_ACTIVE, true)
            .putString(WhatsAppHelperAccessibilityService.KEY_GOAL, cmd)
            .apply()
        Toast.makeText(this, "कमांड भेजा गया ⚡", Toast.LENGTH_SHORT).show()
    }

    private fun buildScheduleTab(): View {
        val textPrimary = if (isDarkMode) Color.WHITE else Color.parseColor("#0F172A")
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 16, 32, 32)
        }

        val heading = TextView(this).apply {
            text = "ऑटो-शेड्यूल्ड टास्क्स"
            textSize = 19f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(textPrimary)
            setPadding(0, 0, 0, 16)
        }

        scheduledListContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        root.addView(heading)
        root.addView(scheduledListContainer)
        scroll.addView(root)
        return scroll
    }

    private fun buildHistoryTab(): View {
        val textPrimary = if (isDarkMode) Color.WHITE else Color.parseColor("#0F172A")
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 16, 32, 32)
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 16)
        }

        val heading = TextView(this).apply {
            text = "टास्क और स्क्रीन हिस्ट्री"
            textSize = 19f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(textPrimary)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val clearBtn = TextView(this).apply {
            text = "साफ़ करें"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(22, 10, 22, 10)
            background = pillBg("#EF4444", "#EF4444")
            setOnClickListener {
                getSharedPreferences("nischay_task_history", MODE_PRIVATE)
                    .edit().putString("logs", "[]").apply()
                updateLiveStates()
            }
        }

        headerRow.addView(heading)
        headerRow.addView(clearBtn)

        historyListContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        root.addView(headerRow)
        root.addView(historyListContainer)
        scroll.addView(root)
        return scroll
    }

    private fun buildSettingsTab(): View {
        val textPrimary = if (isDarkMode) Color.WHITE else Color.parseColor("#0F172A")
        val textSub = if (isDarkMode) Color.parseColor("#94A3B8") else Color.parseColor("#475569")
        val surfaceBg = if (isDarkMode) "#0F172A" else "#FFFFFF"
        val borderCol = if (isDarkMode) "#1E293B" else "#CBD5E1"

        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 12, 32, 40)
        }

        val auth = getSharedPreferences(AUTH_PREFS, MODE_PRIVATE)
        val email = auth.getString("user_email", "Not signed in").orEmpty()
        val uName = auth.getString("user_name", "User").orEmpty()

        val accCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(22, 18, 22, 18)
            background = pillBg(surfaceBg, borderCol)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = 20
            }
        }
        val accInfo = TextView(this).apply {
            text = "👤 $uName\n$email"
            textSize = 13f
            setTextColor(textPrimary)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val signOutBtn = TextView(this).apply {
            text = "Sign Out"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(20, 12, 20, 12)
            background = pillBg("#DC2626", "#DC2626")
            setOnClickListener {
                uiScope.launch {
                    try {
                        credentialManager?.clearCredentialState(ClearCredentialStateRequest())
                    } catch (_: Throwable) { }
                    getSharedPreferences(AUTH_PREFS, MODE_PRIVATE)
                        .edit().remove("user_email").remove("user_name").apply()
                    showGoogleSignInScreen()
                }
            }
        }
        accCard.addView(accInfo)
        accCard.addView(signOutBtn)

        val aiTitle = TextView(this).apply {
            text = "AI इंजन व मल्टी-की पूल"
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(textPrimary)
            setPadding(0, 0, 0, 12)
        }

        providerSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("GEMINI", "GROQ"))
            background = pillBg(surfaceBg, borderCol)
            setPadding(16, 12, 16, 12)
        }

        apiKeyInput = createCleanInput("API Keys डालें (1 से 50 तक कॉमा लगाकर: key1, key2)", surfaceBg, borderCol, textPrimary, textSub)

        personalitySpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("Friendly", "Pro Fast", "Formal"))
            background = pillBg(surfaceBg, borderCol)
            setPadding(16, 12, 16, 12)
        }

        speedLabel = TextView(this).apply {
            text = "आवाज़ की स्पीड: 1.02x"
            setTextColor(textSub)
            setPadding(0, 16, 0, 6)
        }

        speedSeekBar = SeekBar(this).apply {
            max = 150
            progress = 52
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    val rate = 0.5f + (p / 100f)
                    speedLabel.text = "आवाज़ की स्पीड: ${String.format("%.2f", rate)}x"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) = Unit
                override fun onStopTrackingTouch(sb: SeekBar?) = Unit
            })
        }

        val profTitle = TextView(this).apply {
            text = "यूज़र प्रोफाइल (ऑटो-फिल के लिए)"
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(textPrimary)
            setPadding(0, 26, 0, 12)
        }

        nameEnInput = createCleanInput("पूरा नाम (English) - वैकल्पिक", surfaceBg, borderCol, textPrimary, textSub)
        nameHiInput = createCleanInput("पूरा नाम (हिंदी) - वैकल्पिक", surfaceBg, borderCol, textPrimary, textSub)
        addressInput = createCleanInput("शहर / पता - वैकल्पिक", surfaceBg, borderCol, textPrimary, textSub)
        phoneInput = createCleanInput("मोबाइल नंबर - वैकल्पिक", surfaceBg, borderCol, textPrimary, textSub)

        val saveBtn = Button(this).apply {
            text = "सेटिंग्स सेव करें"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.parseColor("#0EA5E9"), Color.parseColor("#6366F1"))
            ).apply { cornerRadius = 28f }
            setOnClickListener { saveSettingsAndProfile() }
        }

        root.addView(accCard)
        root.addView(aiTitle)
        root.addView(providerSpinner)
        root.addView(spaceView(12))
        root.addView(apiKeyInput)
        root.addView(personalitySpinner)
        root.addView(speedLabel)
        root.addView(speedSeekBar)
        root.addView(profTitle)
        root.addView(nameEnInput)
        root.addView(nameHiInput)
        root.addView(addressInput)
        root.addView(phoneInput)
        root.addView(spaceView(8))
        root.addView(saveBtn)

        scroll.addView(root)
        return scroll
    }

    private fun updateLiveStates() {
        val accOn = isAccessibilityEnabled()
        val overlayOn = Settings.canDrawOverlays(this)
        val orbEnabled = getSharedPreferences(WhatsAppHelperAccessibilityService.PREFS, MODE_PRIVATE)
            .getBoolean(WhatsAppHelperAccessibilityService.KEY_ORB_ENABLED, true)

        if (accOn && overlayOn) {
            engineBadge.text = "● Autonomous Engine Active"
            engineBadge.setTextColor(Color.parseColor("#10B981"))
        } else {
            engineBadge.text = "● Permission Required (Tap Orb)"
            engineBadge.setTextColor(Color.parseColor("#F59E0B"))
        }

        floatingOrbSwitch.setOnCheckedChangeListener(null)
        floatingOrbSwitch.isChecked = accOn && overlayOn && orbEnabled
        floatingOrbSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                if (!Settings.canDrawOverlays(this)) {
                    floatingOrbSwitch.isChecked = false
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                    return@setOnCheckedChangeListener
                }
                if (!isAccessibilityEnabled()) {
                    floatingOrbSwitch.isChecked = false
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    return@setOnCheckedChangeListener
                }
                getSharedPreferences(WhatsAppHelperAccessibilityService.PREFS, MODE_PRIVATE)
                    .edit()
                    .putBoolean(WhatsAppHelperAccessibilityService.KEY_ORB_ENABLED, true)
                    .putBoolean(WhatsAppHelperAccessibilityService.KEY_SHOW_ORB, true)
                    .apply()
            } else {
                getSharedPreferences(WhatsAppHelperAccessibilityService.PREFS, MODE_PRIVATE)
                    .edit()
                    .putBoolean(WhatsAppHelperAccessibilityService.KEY_ORB_ENABLED, false)
                    .putBoolean(WhatsAppHelperAccessibilityService.KEY_HIDE_ORB, true)
                    .apply()
            }
        }

        val surfaceBg = if (isDarkMode) "#0F172A" else "#FFFFFF"
        val borderCol = if (isDarkMode) "#1E293B" else "#CBD5E1"
        val textPrimary = if (isDarkMode) Color.WHITE else Color.parseColor("#0F172A")
        val textSub = if (isDarkMode) Color.parseColor("#94A3B8") else Color.parseColor("#64748B")

        scheduledListContainer.removeAllViews()
        val tasks = ScheduledTaskReceiver.getAllTasks(this)
        if (tasks.isEmpty()) {
            scheduledListContainer.addView(TextView(this).apply {
                text = "कोई शेड्यूल्ड टास्क नहीं है।\nHome पर लिखें या बोलें: 'अभी से 30 सेकंड बाद टॉर्च चालू करना'"
                textSize = 13.5f
                setTextColor(textSub)
                setPadding(24, 24, 24, 24)
                background = pillBg(surfaceBg, borderCol)
            })
        } else {
            tasks.forEach { item ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(22, 18, 22, 18)
                    background = pillBg(surfaceBg, borderCol)
                }
                val infoTv = TextView(this).apply {
                    text = "⏰ ${item.formattedTime()}\n${item.command}"
                    textSize = 13.5f
                    setTextColor(textPrimary)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                val delBtn = TextView(this).apply {
                    text = "हटाएं"
                    textSize = 12f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.WHITE)
                    setPadding(22, 12, 22, 12)
                    background = pillBg("#EF4444", "#EF4444")
                    setOnClickListener {
                        ScheduledTaskReceiver.cancelAndRemoveTask(this@MainActivity, item.id)
                        updateLiveStates()
                    }
                }
                row.addView(infoTv)
                row.addView(delBtn)
                scheduledListContainer.addView(row)
                scheduledListContainer.addView(spaceView(12))
            }
        }

        historyListContainer.removeAllViews()
        val rawLogs = getSharedPreferences("nischay_task_history", MODE_PRIVATE).getString("logs", "[]").orEmpty()
        val arr = try { JSONArray(rawLogs) } catch (_: Exception) { JSONArray() }
        if (arr.length() == 0) {
            historyListContainer.addView(TextView(this).apply {
                text = "अभी तक कोई टास्क हिस्ट्री नहीं है।"
                textSize = 13.5f
                setTextColor(textSub)
                setPadding(24, 24, 24, 24)
                background = pillBg(surfaceBg, borderCol)
            })
        } else {
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val ok = obj.optBoolean("success", true)
                val icon = if (ok) "✅" else "⚠️"
                val card = TextView(this).apply {
                    text = "$icon ${obj.optString("goal")}\n→ ${obj.optString("result")}\n🕒 ${obj.optString("time")}"
                    textSize = 13f
                    setTextColor(textPrimary)
                    setPadding(22, 18, 22, 18)
                    background = pillBg(surfaceBg, if (ok) "#10B981" else "#F59E0B")
                }
                historyListContainer.addView(card)
                historyListContainer.addView(spaceView(12))
            }
        }
    }

    private fun loadSavedSettingsAndProfile() {
        val s = getSharedPreferences("assistant_settings", MODE_PRIVATE)
        val prov = s.getString("provider", "GEMINI") ?: "GEMINI"
        providerSpinner.setSelection(if (prov == "GROQ") 1 else 0)
        apiKeyInput.setText(s.getString("api_key", ""))
        val speed = s.getFloat("tts_speed", 1.02f)
        speedSeekBar.progress = ((speed - 0.5f) * 100).toInt().coerceIn(0, 150)

        val p = getSharedPreferences("user_profile_vault", MODE_PRIVATE)
        nameEnInput.setText(p.getString("name_en", ""))
        nameHiInput.setText(p.getString("name_hi", ""))
        addressInput.setText(p.getString("address", ""))
        phoneInput.setText(p.getString("phone", ""))
    }

    private fun saveSettingsAndProfile() {
        val rate = 0.5f + (speedSeekBar.progress / 100f)
        getSharedPreferences("assistant_settings", MODE_PRIVATE).edit()
            .putString("provider", providerSpinner.selectedItem.toString())
            .putString("api_key", apiKeyInput.text.toString().trim())
            .putString("personality", personalitySpinner.selectedItem.toString())
            .putFloat("tts_speed", rate)
            .apply()

        getSharedPreferences("user_profile_vault", MODE_PRIVATE).edit()
            .putString("name_en", nameEnInput.text.toString().trim())
            .putString("name_hi", nameHiInput.text.toString().trim())
            .putString("address", addressInput.text.toString().trim())
            .putString("phone", phoneInput.text.toString().trim())
            .apply()

        Toast.makeText(this, "सेटिंग्स सेव हो गईं ✨", Toast.LENGTH_SHORT).show()
    }

    private fun createCleanInput(hintStr: String, fillHex: String, strokeHex: String, textCol: Int, hintCol: Int): EditText {
        return EditText(this).apply {
            hint = hintStr
            setHintTextColor(hintCol)
            setTextColor(textCol)
            textSize = 13.5f
            setPadding(24, 20, 24, 20)
            background = pillBg(fillHex, strokeHex)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = 14
            }
            layoutParams = lp
        }
    }

    private fun pillBg(fillHex: String, strokeHex: String): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor(fillHex))
            cornerRadius = 28f
            setStroke(2, Color.parseColor(strokeHex))
        }
    }

    private fun spaceView(dp: Int): View {
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (dp * resources.displayMetrics.density).toInt()
            )
        }
    }
}
