package com.prince.aiassistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class AiAssistantManager(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()
) {
    companion object {
        const val GEMINI_MODEL = "gemini-3.5-flash-lite"
        private var currentKeyIndex = 0
        private val cachedGroqModelsByKey = mutableMapOf<String, List<String>>()
        private val conversationHistory = mutableListOf<String>()
        private val userFormMemory = mutableMapOf<String, String>()
        private val webKnowledgeCache = mutableMapOf<String, String>()
    }

    enum class Provider { GEMINI, GROQ }

    data class SubTask(
        val title: String,
        val appToOpen: String? = null,
        val instruction: String
    )

    data class ParsedCommand(
        val intent: String,
        val startReply: String,
        val doneReply: String,
        val target: String? = null,
        val volumeLevel: String = "STEP",
        val reelIntervalSeconds: Int = 15,
        val reelDurationMinutes: Int = 0,
        val autoLikeReels: Boolean = false,
        val scheduledCommand: String = "",
        val isDailySchedule: Boolean = false,
        val delaySeconds: Int = 0,
        val subTasks: List<SubTask> = emptyList(),
        val hour: Int = 7,
        val minute: Int = 0,
        val seconds: Int = 60
    )

    data class AgentStep(
        val action: String,
        val index: Int = -1,
        val normX: Int = -1,
        val normY: Int = -1,
        val text: String = "",
        val pressEnter: Boolean = false,
        val questionForUser: String = "",
        val milestoneAchieved: String = "",
        val extractedNote: String = "",
        val goalVerified: Boolean = false,
        val statusLabel: String = ""
    )

    fun preloadSavedProfile(nameEn: String, nameHi: String, address: String, phone: String) {
        userFormMemory.clear()
        if (nameEn.isNotBlank()) userFormMemory["Full Name (English)"] = nameEn
        if (nameHi.isNotBlank()) userFormMemory["Full Name (Hindi)"] = nameHi
        if (address.isNotBlank()) userFormMemory["Address / City"] = address
        if (phone.isNotBlank()) userFormMemory["Mobile Number"] = phone
    }

    fun getSavedPhoneNumber(): String = userFormMemory["Mobile Number"].orEmpty().trim()

    fun saveDirectUserReply(questionAsked: String, typedOrSpokenValue: String): String {
        val clean = typedOrSpokenValue.trim()
        userFormMemory[questionAsked.take(35)] = clean
        if (clean.count { it.isDigit() } >= 10) {
            userFormMemory["Mobile Number"] = clean.filter { it.isDigit() }
        }
        return clean
    }

    fun parseUserKeyList(rawKeysInput: String): List<String> {
        return rawKeysInput.split(",", "\n", " ")
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }

    private fun getRotatedApiKey(rawKeysInput: String): String {
        val keys = parseUserKeyList(rawKeysInput)
        if (keys.isEmpty()) return ""
        return keys[currentKeyIndex % keys.size]
    }

    private fun rotateToNextKey(rawKeysInput: String) {
        val keys = parseUserKeyList(rawKeysInput)
        if (keys.size > 1) {
            currentKeyIndex = (currentKeyIndex + 1) % keys.size
        }
    }

    fun extractAndSaveCleanFormValue(
        provider: Provider,
        apiKey: String,
        questionAsked: String,
        rawSpokenAnswer: String
    ): String {
        val prompt = """
            Extract ONLY the clean choice or value from the user's reply.
            QUESTION: "$questionAsked" | REPLY: "$rawSpokenAnswer"
            Strip filler words. Keep phone/PIN/OTP digits exact. If user says skip/नहीं, return "SKIP".
            JSON ONLY: {"fieldKey":"short key","value":"..."}
        """.trimIndent()

        return try {
            val raw = executeBlockingRetry(apiKey) { key ->
                if (provider == Provider.GEMINI) callGeminiText(key, GEMINI_MODEL, prompt) else callGroq(key, prompt)
            }
            val start = raw.indexOf('{')
            val end = raw.lastIndexOf('}')
            val json = JSONObject(raw.substring(start, end + 1))
            val key = json.optString("fieldKey", questionAsked.take(30))
            val v = json.optString("value", "").trim()
            if (v.equals("SKIP", true)) {
                userFormMemory[key] = "SKIP"
                "स्किप किया"
            } else {
                userFormMemory[key] = v
                if (v.count { it.isDigit() } >= 10) {
                    userFormMemory["Mobile Number"] = v.filter { it.isDigit() }
                }
                v
            }
        } catch (_: Exception) {
            val fallback = rawSpokenAnswer
                .replace("मेरा नाम", "")
                .replace("मेरा नंबर", "")
                .replace("मेरा पिन", "")
                .replace("रखो", "")
                .replace("है", "")
                .trim()
            userFormMemory[questionAsked.take(30)] = fallback
            fallback
        }
    }

    // Web Search Fallback when stuck on an unknown app or complex workflow
    fun fetchWebStepsForUnknownTask(goal: String): String {
        val cacheKey = goal.trim().lowercase()
        webKnowledgeCache[cacheKey]?.let { return it }
        return try {
            val q = URLEncoder.encode("how to $goal android app steps", "UTF-8")
            val req = Request.Builder()
                .url("https://api.duckduckgo.com/?q=$q&format=json&no_html=1&skip_disambig=1")
                .header("User-Agent", "NischayPulse/2.0")
                .get()
                .build()
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return ""
                val json = JSONObject(res.body?.string().orEmpty())
                val abstractText = json.optString("AbstractText").trim()
                val related = json.optJSONArray("RelatedTopics")
                val snippets = mutableListOf<String>()
                if (abstractText.isNotBlank()) snippets.add(abstractText)
                if (related != null) {
                    for (i in 0 until minOf(3, related.length())) {
                        val txt = related.optJSONObject(i)?.optString("Text").orEmpty().trim()
                        if (txt.isNotBlank()) snippets.add(txt)
                    }
                }
                val result = snippets.joinToString(" | ").take(600)
                if (result.isNotBlank()) webKnowledgeCache[cacheKey] = result
                result
            }
        } catch (_: Exception) {
            ""
        }
    }

    suspend fun ask(
        provider: Provider,
        apiKey: String,
        userText: String,
        personality: String = "Friendly",
        isTriggeredFromSchedule: Boolean = false
    ): Result<ParsedCommand> = withContext(Dispatchers.IO) {
        runCatching {
            val keys = parseUserKeyList(apiKey)
            require(keys.isNotEmpty()) { "पहले Settings में अपनी API Key डालें!" }
            require(userText.isNotBlank()) { "संदेश खाली है" }

            conversationHistory.add("User: $userText")
            if (conversationHistory.size > 12) conversationHistory.removeAt(0)

            val nowStr = SimpleDateFormat("dd MMM yyyy, hh:mm:ss a (HH:mm:ss)", Locale.getDefault()).format(Date())

            val prompt = """
                तुम Nischay Pulse हो — एक सर्वगुण संपन्न ऑटोनॉमस एंड्रॉइड AI एजेंट। अंदाज़: $personality।
                समय: $nowStr | SCHEDULE_TRIGGERED_NOW: $isTriggeredFromSchedule
                पिछली बातचीत: ${conversationHistory.takeLast(8).joinToString(" | ")}

                सभी संभावित स्थितियों के लिए सख़्त नियम:
                1. REEL_AUTO_SCROLL (रील्स / शॉर्ट्स ऑटो-स्क्रॉल + ऑटो-लाइक + ऑटो-स्टॉप):
                   - जब यूज़र रील या शॉर्ट्स स्क्रॉल करने को कहे (जैसे "इंस्टाग्राम पर हर 25 सेकंड में रील स्क्रॉल करो और लाइक करते जाओ 5 मिनट तक" या "यूट्यूब शॉर्ट्स चलाओ"), तो intent = "REEL_AUTO_SCROLL" चुनें!
                   - "target": "Instagram" (या "YouTube")
                   - "reelIntervalSeconds": हर स्क्रॉल के बीच के सेकंड (डिफ़ॉल्ट 15)
                   - "reelDurationMinutes": जितने मिनट तक चलाना है (जैसे 5 मिनट -> 5, न बोला हो तो 0)
                   - "autoLikeReels": यदि यूज़र ने "लाइक करते जाओ" या "like" बोला है तो true, वरना false।
                2. SCHEDULE_LOOP से बचाव:
                   - यदि SCHEDULE_TRIGGERED_NOW == true है, तो कभी भी "SCHEDULE_TASK" न चुनें—सीधे असली काम शुरू करें!
                   - "SCHEDULE_TASK" केवल भविष्य के किसी तय समय/देरी (जैसे "सुबह 6 बजे", "10 मिनट बाद") के लिए चुनें।
                3. YOUTUBE_SEARCH शुद्ध कीवर्ड:
                   - जब यूज़र गाना या वीडियो चलाने को कहे, तो intent = "YOUTUBE_SEARCH" चुनें और "target" में केवल शुद्ध गाने/वीडियो का नाम रखें—"यूट्यूब पर", "गाना चलाओ", "बजाओ" जैसे कमांड शब्द बिल्कुल हटा दें!
                4. VOLUME का सटीक नियंत्रण:
                   - "VOLUME_UP" / "VOLUME_DOWN" के साथ "volumeLevel" भरें:
                     * "फुल", "पूरा बढ़ा", "100%", "तेज़" -> "FULL"
                     * "आधा", "50%", "मीडियम" -> "HALF"
                     * "पूरा कम", "बिलकुल कम", "0%" -> "ZERO"
                     * सामान्य थोड़ा बढ़ाना/घटाना -> "STEP"
                5. मल्टी-ऐप कार्य (AGENT_TASK):
                   - छोटे-छोटे साफ़ subTasks में तोड़ें। यदि संदेश भेजना या शेयर करना है, तो केवल ऐप खोलने पर योजना खत्म न करें—अंतिम subTask में अनिवार्य रूप से लिखें: "संबंधित चैट खोलें, निकाला गया पूरा डेटा टाइप करें और Send बटन दबाएं"।
                6. उत्तर की लंबाई:
                   - एक्शन में "startReply" 3-5 शब्दों का छोटा रखें।
                   - "CHAT" में "startReply" में पूरा, स्पष्ट और विस्तृत जवाब दें और "doneReply": "" रखें।

                Intent:
                1. "REEL_AUTO_SCROLL" 2. "SCHEDULE_TASK" 3. "DIRECT_CALL"
                4. "TORCH_ON" / "TORCH_OFF" 5. "SET_ALARM" / "SET_TIMER"
                6. "READ_SCREEN" 7. "SCROLL_DOWN" / "SCROLL_UP" 8. "GO_BACK" / "GO_HOME"
                9. "TAKE_SCREENSHOT" 10. "VOLUME_UP" / "VOLUME_DOWN" / "SILENT_MODE"
                11. "YOUTUBE_SEARCH" 12. "AGENT_TASK" 13. "CHAT"

                केवल वैध JSON:
                {"intent":"CHAT","startReply":"...","doneReply":"...","target":null,"volumeLevel":"STEP","reelIntervalSeconds":15,"reelDurationMinutes":0,"autoLikeReels":false,"scheduledCommand":"","isDailySchedule":false,"delaySeconds":0,"subTasks":[{"title":"...","appToOpen":null,"instruction":"..."}],"hour":7,"minute":0,"seconds":60}
                यूज़र: ${JSONObject.quote(userText)}
            """.trimIndent()

            val raw = executeWithAutoRetry(apiKey) { key ->
                when (provider) {
                    Provider.GEMINI -> callGeminiText(key, GEMINI_MODEL, prompt)
                    Provider.GROQ -> callGroq(key, prompt)
                }
            }
            val parsed = parseInitialCommand(raw, userText)
            conversationHistory.add("NischayPulse: ${parsed.startReply}")
            parsed
        }
    }

    fun summarizeScreen(provider: Provider, apiKey: String, screenText: String): String {
        val keys = parseUserKeyList(apiKey)
        if (keys.isEmpty()) return "पहले Settings में अपनी API Key डालें!"
        val prompt = """
            नीचे स्क्रीन का टेक्स्ट है। सरल हिंदी में संक्षेप में बताओ कि स्क्रीन पर क्या दिख रहा है (शॉपिंग में असली कम वाली सेलिंग प्राइस बताओ)।
            केवल JSON: {"reply":"..."}
            स्क्रीन: ${screenText.take(2500)}
        """.trimIndent()
        val raw = executeBlockingRetry(apiKey) { key ->
            if (provider == Provider.GEMINI) callGeminiText(key, GEMINI_MODEL, prompt) else callGroq(key, prompt)
        }
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        return if (start >= 0 && end > start) JSONObject(raw.substring(start, end + 1)).optString("reply", "स्क्रीन पढ़ ली गई है।") else raw
    }

    fun decideNextAutonomousStep(
        provider: Provider,
        apiKey: String,
        overallGoal: String,
        currentSubTaskInstruction: String,
        completedSubTasks: List<String>,
        completedMilestones: List<String>,
        extractedNotes: List<String>,
        blockedSignatures: List<String>,
        screenElements: String,
        screenshotBase64: String?,
        history: List<String>,
        screenChanged: Boolean,
        repeatedActionCount: Int,
        staleScreenStreak: Int,
        isBottomLocked: Boolean,
        realActionsCount: Int,
        webHelpHint: String = ""
    ): AgentStep {
        val keys = parseUserKeyList(apiKey)
        if (keys.isEmpty()) {
            return AgentStep("FAILED_STUCK", text = "पहले Settings में अपनी API Key डालें!")
        }
        val knownDetails = if (userFormMemory.isEmpty()) "None" else userFormMemory.entries.joinToString("; ") { "${it.key} => ${it.value}" }

        val prompt = """
            You are Nischay Pulse, a strict ZERO-GUESSING Autonomous Android Agent.
            COORDINATES: Normalized 0..1000 (0,0 top-left; 500,500 center; 1000,1000 bottom-right).

            OVERALL GOAL: $overallGoal
            CURRENT STEP: $currentSubTaskInstruction
            ACTIONS DONE: $realActionsCount | SCREEN CHANGED: $screenChanged | SAME SCREEN STREAK: $staleScreenStreak | PAGE_BOTTOM_LOCKED: $isBottomLocked
            COMPLETED SUBTASKS: ${completedSubTasks.joinToString(" -> ").ifBlank { "None" }}
            COMPLETED MILESTONES (DO NOT REPEAT): ${completedMilestones.joinToString(" | ").ifBlank { "None" }}
            EXTRACTED DATA IN MEMORY: ${extractedNotes.joinToString(" | ").ifBlank { "None" }}
            BLOCKED / ALREADY TRIED (NEVER REPEAT): ${blockedSignatures.joinToString(" | ").ifBlank { "None" }}
            KNOWN USER DETAILS & ANSWERS: $knownDetails
            WEB SEARCH / GUIDE HINT: ${webHelpHint.ifBlank { "None" }}
            RECENT HISTORY: ${history.takeLast(8).joinToString(" -> ")}

            LIVE UI NODES ([index] TYPE (normX,normY): "Label"):
            $screenElements

            STRICT ZERO-GUESSING & PROBLEM-SOLVING RULES:
            1. ZERO-GUESSING RULE (CRITICAL — NEVER ASSUME OR GUESS):
               - If the screen shows MULTIPLE OPTIONS or a CHOICE (e.g., SIM 1 vs SIM 2, Multiple Accounts, Multiple Contacts with similar names, Payment methods, Download quality 720p/1080p, or Confirm/Delete dialog) and the user has NOT already answered it in KNOWN USER DETAILS:
                 -> DO NOT GUESS OR AUTO-PICK! Immediately return `"ASK_USER"` with a clear Hindi question listing the visible options in `"questionForUser"` (e.g., "स्क्रीन पर SIM 1 और SIM 2 दिख रहे हैं, किससे कॉल करूँ?").
               - If an `INPUT_BOX` asks for Phone Number, PIN, Password, OTP, or missing personal info not in KNOWN USER DETAILS:
                 -> Immediately return `"ASK_USER"` with a short Hindi question in `"questionForUser"`.
            2. PAGE_BOTTOM_LOCKED == true:
               - NEVER choose "SCROLL_DOWN"! Use the app's "Search" icon/bar, tap a relevant option, or use "BACK".
            3. ZERO DUPLICATE MESSAGES OR ACTIONS:
               - Once you type a message and click Send, NEVER type or send it again—immediately return "SUBTASK_DONE" with "goalVerified": true!
               - Never click any button in BLOCKED / ALREADY TRIED or re-type into an already-filled box (`[CURRENT_TEXT: "..."]`).
            4. CLEAN SEARCH KEYWORDS & INSTALL/UPDATE:
               - When typing into any Search box, type ONLY pure keywords (never "बजाओ", "खोलो", "सर्च करो").
            5. ALWAYS SAVE EXTRACTED SCREEN FACTS:
               - Whenever you read requested info from screen (model, RAM, storage, price, train, calculation), store it in `"extractedNote"` so it carries into the next app.
            6. SPINNER & LOADING PATIENCE: If UI nodes < 4 OR show "LOADING_SPINNER" / "Loading", return "WAIT" with a Hindi statusLabel.

            Actions: OPEN_APP, OPEN_CAMERA, CLICK_INDEX, TAP_NORM, TYPE_TEXT, HIDE_KEYBOARD, WAIT, ASK_USER, SPEAK_AND_DONE, SCROLL_DOWN, SCROLL_UP, BACK, SUBTASK_DONE, FAILED_STUCK.
            JSON ONLY:
            {"action":"...","index":-1,"normX":-1,"normY":-1,"text":"","pressEnter":false,"questionForUser":"","milestoneAchieved":"","extractedNote":"","goalVerified":false,"statusLabel":"..."}
        """.trimIndent()

        val raw = executeBlockingRetry(apiKey) { key ->
            if (provider == Provider.GEMINI) {
                callGeminiVision(key, GEMINI_MODEL, prompt, screenshotBase64)
            } else {
                callGroq(key, prompt)
            }
        }
        return parseAgentStep(raw)
    }

    private suspend fun executeWithAutoRetry(
        rawKeys: String,
        block: (String) -> String
    ): String {
        val keys = parseUserKeyList(rawKeys)
        require(keys.isNotEmpty()) { "पहले Settings में अपनी API Key डालें!" }
        val maxAttempts = keys.size.coerceAtLeast(2)
        var lastErr: Exception? = null
        repeat(maxAttempts) { attempt ->
            val keyToUse = getRotatedApiKey(rawKeys)
            try {
                return block(keyToUse)
            } catch (e: Exception) {
                lastErr = e
                val msg = e.message.orEmpty()
                if (msg.contains("401")) throw e
                if (msg.contains("429") || msg.contains("503")) {
                    rotateToNextKey(rawKeys)
                    delay(400L * (attempt + 1))
                } else {
                    delay(250L * (attempt + 1))
                }
            }
        }
        throw lastErr ?: IllegalStateException("AI से संपर्क नहीं हो पाया")
    }

    private fun executeBlockingRetry(
        rawKeys: String,
        block: (String) -> String
    ): String {
        val keys = parseUserKeyList(rawKeys)
        require(keys.isNotEmpty()) { "पहले Settings में अपनी API Key डालें!" }
        val maxAttempts = keys.size.coerceAtLeast(2)
        var lastErr: Exception? = null
        repeat(maxAttempts) { attempt ->
            val keyToUse = getRotatedApiKey(rawKeys)
            try {
                return block(keyToUse)
            } catch (e: Exception) {
                lastErr = e
                val msg = e.message.orEmpty()
                if (msg.contains("401")) throw e
                if (msg.contains("429") || msg.contains("503")) {
                    rotateToNextKey(rawKeys)
                    Thread.sleep(400L * (attempt + 1))
                } else {
                    Thread.sleep(250L * (attempt + 1))
                }
            }
        }
        throw lastErr ?: IllegalStateException("AI से संपर्क नहीं हो पाया")
    }

    private fun callGeminiText(apiKey: String, model: String, prompt: String): String {
        return callGeminiVision(apiKey, model, prompt, null)
    }

    private fun callGeminiVision(apiKey: String, model: String, prompt: String, imageBase64: String?): String {
        val geminiPool = listOf(
            "gemini-3.5-flash-lite",
            "gemini-3.1-flash-lite",
            "gemini-2.5-flash-lite",
            "gemini-3.8-flash",
            "gemini-3.7-flash",
            "gemini-3.6-flash",
            "gemini-3.5-flash"
        )
        var lastError: Exception? = null

        for (candidateModel in geminiPool) {
            try {
                val url = "https://generativelanguage.googleapis.com/v1beta/models/$candidateModel:generateContent?key=$apiKey"
                val partsArray = JSONArray().put(JSONObject().put("text", prompt))
                if (!imageBase64.isNullOrBlank()) {
                    partsArray.put(
                        JSONObject().put(
                            "inlineData",
                            JSONObject()
                                .put("mimeType", "image/jpeg")
                                .put("data", imageBase64)
                        )
                    )
                }
                val genConfig = JSONObject()
                    .put("temperature", 0.05)
                    .put("maxOutputTokens", 2048)
                    .put("responseMimeType", "application/json")
                    .put("thinkingConfig", JSONObject().put("thinkingBudget", 256))

                val body = JSONObject()
                    .put("contents", JSONArray().put(JSONObject().put("parts", partsArray)))
                    .put("generationConfig", genConfig)
                    .toString()

                return post(url, body, null)
                    .let { JSONObject(it).getJSONArray("candidates").getJSONObject(0)
                        .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text") }
            } catch (e: Exception) {
                lastError = e
                val msg = e.message.orEmpty()
                if (msg.contains("401")) throw e
                if (msg.contains("400")) {
                    try {
                        val url = "https://generativelanguage.googleapis.com/v1beta/models/$candidateModel:generateContent?key=$apiKey"
                        val partsArray = JSONArray().put(JSONObject().put("text", prompt))
                        if (!imageBase64.isNullOrBlank()) {
                            partsArray.put(
                                JSONObject().put(
                                    "inlineData",
                                    JSONObject().put("mimeType", "image/jpeg").put("data", imageBase64)
                                )
                            )
                        }
                        val plainConfig = JSONObject()
                            .put("temperature", 0.05)
                            .put("maxOutputTokens", 2048)
                            .put("responseMimeType", "application/json")
                        val plainBody = JSONObject()
                            .put("contents", JSONArray().put(JSONObject().put("parts", partsArray)))
                            .put("generationConfig", plainConfig)
                            .toString()
                        return post(url, plainBody, null)
                            .let { JSONObject(it).getJSONArray("candidates").getJSONObject(0)
                                .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text") }
                    } catch (inner: Exception) {
                        lastError = inner
                    }
                }
            }
        }
        throw lastError ?: IllegalStateException("लिमिट पूरी हो गई है (429)।")
    }

    private fun resolveSmartGroqModels(apiKey: String): List<String> {
        cachedGroqModelsByKey[apiKey]?.let { if (it.isNotEmpty()) return it }

        val smartPriority = listOf(
            "openai/gpt-oss-120b",
            "qwen/qwen3.8-27b",
            "qwen/qwen3-32b",
            "llama-3.3-70b-versatile",
            "openai/gpt-oss-20b"
        )

        return try {
            val req = Request.Builder()
                .url("https://api.groq.com/openai/v1/models")
                .get()
                .header("Authorization", "Bearer $apiKey")
                .build()
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return smartPriority
                val arr = JSONObject(res.body?.string().orEmpty()).getJSONArray("data")
                val liveIds = mutableSetOf<String>()
                for (i in 0 until arr.length()) {
                    val id = arr.getJSONObject(i).getString("id")
                    if (!id.contains("whisper", true) &&
                        !id.contains("guard", true) &&
                        !id.contains("orpheus", true) &&
                        !id.contains("tts", true)
                    ) {
                        liveIds.add(id)
                    }
                }
                val ordered = smartPriority.filter { it in liveIds } + liveIds.filter { it !in smartPriority }
                if (ordered.isNotEmpty()) {
                    cachedGroqModelsByKey[apiKey] = ordered
                    ordered
                } else {
                    smartPriority
                }
            }
        } catch (_: Exception) {
            smartPriority
        }
    }

    private fun callGroq(apiKey: String, prompt: String): String {
        val models = resolveSmartGroqModels(apiKey)
        var lastError: Exception? = null

        for (modelId in models) {
            try {
                val body = JSONObject()
                    .put("model", modelId)
                    .put("temperature", 0.05)
                    .put("max_tokens", 2048)
                    .put("response_format", JSONObject().put("type", "json_object"))
                    .put("messages", JSONArray()
                        .put(JSONObject().put("role", "system").put("content", "You are Nischay Pulse, a strict Zero-Guessing Autonomous Android UI Agent using 0..1000 normalized coordinates. Never guess when options exist; ask user via ASK_USER. Output ONLY valid JSON."))
                        .put(JSONObject().put("role", "user").put("content", prompt)))
                    .toString()
                return post("https://api.groq.com/openai/v1/chat/completions", body, "Bearer $apiKey")
                    .let { JSONObject(it).getJSONArray("choices").getJSONObject(0)
                        .getJSONObject("message").getString("content") }
            } catch (e: Exception) {
                lastError = e
                val msg = e.message.orEmpty()
                if (msg.contains("401")) throw e
                if (msg.contains("404")) {
                    cachedGroqModelsByKey.remove(apiKey)
                }
            }
        }
        throw lastError ?: IllegalStateException("Groq के सभी उपलब्ध मॉडल व्यस्त हैं।")
    }

    private fun post(url: String, json: String, authorization: String?): String {
        val requestBuilder = Request.Builder()
            .url(url)
            .post(json.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("Accept", "application/json")
        if (authorization != null) requestBuilder.header("Authorization", authorization)
        client.newCall(requestBuilder.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                error(when (response.code) {
                    429 -> "लिमिट पूरी हो गई है (429)।"
                    503 -> "सर्वर अभी व्यस्त है (503)।"
                    401 -> "API Key गलत है (401)।"
                    400 -> "अनुरोध फॉर्मेट एरर (400)।"
                    404 -> "मॉडल उपलब्ध नहीं है (404)।"
                    else -> "API एरर (${response.code})"
                })
            }
            return text
        }
    }

    private fun parseInitialCommand(modelText: String, originalQuery: String): ParsedCommand {
        val cleaned = modelText.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start < 0 || end <= start) return ParsedCommand("CHAT", cleaned, "")
        val json = JSONObject(cleaned.substring(start, end + 1))

        val tasks = mutableListOf<SubTask>()
        val tasksArr = json.optJSONArray("subTasks")
        if (tasksArr != null) {
            for (i in 0 until tasksArr.length()) {
                val obj = tasksArr.optJSONObject(i) ?: continue
                val title = obj.optString("title").trim()
                val app = obj.optString("appToOpen").takeIf { it.isNotBlank() && it != "null" }
                val instr = obj.optString("instruction", title).trim()
                if (title.isNotBlank()) tasks.add(SubTask(title, app, instr))
            }
        }
        val intent = json.optString("intent", "CHAT").uppercase()
        if (intent == "AGENT_TASK" && tasks.isEmpty()) {
            val targetApp = json.optString("target").takeIf { it.isNotBlank() && it != "null" }
            tasks.add(SubTask(originalQuery, targetApp, originalQuery))
        }

        val startRep = json.optString("startReply").ifBlank { json.optString("reply").ifBlank { "ठीक है।" } }
        val doneRep = json.optString("doneReply").ifBlank { "काम पूरा हो गया है।" }

        return ParsedCommand(
            intent = intent,
            startReply = startRep,
            doneReply = doneRep,
            target = json.optString("target").takeIf { it.isNotBlank() && it != "null" },
            volumeLevel = json.optString("volumeLevel", "STEP").uppercase(),
            reelIntervalSeconds = json.optInt("reelIntervalSeconds", 15).coerceIn(4, 120),
            reelDurationMinutes = json.optInt("reelDurationMinutes", 0).coerceIn(0, 180),
            autoLikeReels = json.optBoolean("autoLikeReels", false),
            scheduledCommand = json.optString("scheduledCommand", originalQuery).ifBlank { originalQuery },
            isDailySchedule = json.optBoolean("isDailySchedule", false),
            delaySeconds = json.optInt("delaySeconds", 0),
            subTasks = tasks,
            hour = json.optInt("hour", 7),
            minute = json.optInt("minute", 0),
            seconds = json.optInt("seconds", 60)
        )
    }

    private fun parseAgentStep(modelText: String): AgentStep {
        val cleaned = modelText.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start < 0 || end <= start) {
            return AgentStep("WAIT", statusLabel = "स्क्रीन लोड हो रही है...")
        }
        return try {
            val json = JSONObject(cleaned.substring(start, end + 1))
            AgentStep(
                action = json.optString("action", "WAIT").uppercase(),
                index = json.optInt("index", -1),
                normX = json.optInt("normX", -1),
                normY = json.optInt("normY", -1),
                text = json.optString("text", ""),
                pressEnter = json.optBoolean("pressEnter", false),
                questionForUser = json.optString("questionForUser", ""),
                milestoneAchieved = json.optString("milestoneAchieved", ""),
                extractedNote = json.optString("extractedNote", ""),
                goalVerified = json.optBoolean("goalVerified", false),
                statusLabel = json.optString("statusLabel", "आगे बढ़ रहा हूँ...")
            )
        } catch (_: Exception) {
            AgentStep("WAIT", statusLabel = "स्क्रीन लोड हो रही है...")
        }
    }
}
