package com.ariyan.ai

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

const val DEFAULT_MODEL = "gemini-3.8-flash"

val FALLBACK_MODELS = listOf(
    "gemini-3.8-flash",
    "gemini-3.7-flash",
    "gemini-3.5-flash",
    "gemini-3.5-flash-lite"
)

val Context.dataStore by preferencesDataStore(name = "ariyan_settings")
val KEY_API = stringPreferencesKey("api_key")
val KEY_MODEL = stringPreferencesKey("model")

data class ChatMessage(
    val text: String,
    val fromUser: Boolean,
    val isError: Boolean = false
)

class ApiException(val code: Int, message: String) : Exception(message)

val http: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .build()

suspend fun callGemini(
    apiKey: String,
    model: String,
    history: List<ChatMessage>,
    systemText: String
): String = withContext(Dispatchers.IO) {
    val contents = JSONArray()
    history.takeLast(12).forEach { m ->
        contents.put(
            JSONObject()
                .put("role", if (m.fromUser) "user" else "model")
                .put("parts", JSONArray().put(JSONObject().put("text", m.text)))
        )
    }
    val body = JSONObject()
        .put(
            "systemInstruction",
            JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemText)))
        )
        .put("contents", contents)
        .put("generationConfig", JSONObject().put("responseMimeType", "application/json"))

    val request = Request.Builder()
        .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
        .addHeader("x-goog-api-key", apiKey)
        .post(body.toString().toRequestBody("application/json".toMediaType()))
        .build()

    http.newCall(request).execute().use { resp ->
        val raw = resp.body?.string() ?: ""
        if (!resp.isSuccessful) {
            val msg = try {
                JSONObject(raw).getJSONObject("error").getString("message")
            } catch (e: Exception) {
                raw.take(200)
            }
            throw ApiException(resp.code, "${resp.code}: $msg")
        }
        val parts = JSONObject(raw)
            .getJSONArray("candidates")
            .getJSONObject(0)
            .getJSONObject("content")
            .getJSONArray("parts")
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            sb.append(parts.getJSONObject(i).optString("text"))
        }
        sb.toString().trim()
    }
}

suspend fun askGemini(
    apiKey: String,
    model: String,
    history: List<ChatMessage>,
    systemText: String
): String {
    val models = (listOf(model) + FALLBACK_MODELS).distinct()
    var lastError: Exception? = null
    for (m in models) {
        attemptLoop@ for (attempt in 1..2) {
            try {
                return callGemini(apiKey, m, history, systemText)
            } catch (e: ApiException) {
                lastError = e
                when (e.code) {
                    503, 429, 500 -> {
                        if (attempt == 1) delay(1500)
                    }
                    404 -> break@attemptLoop
                    else -> throw e
                }
            }
        }
    }
    throw lastError ?: Exception("Unknown error")
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AriyanApp()
                }
            }
        }
    }
}

@Composable
fun AriyanApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var showSettings by remember { mutableStateOf(false) }
    var apiKey by remember { mutableStateOf("") }
    var model by remember { mutableStateOf(DEFAULT_MODEL) }
    var langBn by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf("মাইক চাপুন আর কথা বলুন") }
    var listening by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var ttsReady by remember { mutableStateOf(false) }
    var pendingCmd by remember { mutableStateOf<Command?>(null) }
    var retryCmd by remember { mutableStateOf<Command?>(null) }
    var confirmCall by remember { mutableStateOf<Pair<String, String>?>(null) }
    val messages = remember { mutableStateListOf<ChatMessage>() }
    val listState = rememberLazyListState()
    val appNames = remember { launchableApps(context).map { it.first } }

    LaunchedEffect(Unit) {
        val prefs = context.dataStore.data.first()
        apiKey = prefs[KEY_API] ?: ""
        model = prefs[KEY_MODEL] ?: DEFAULT_MODEL
        if (apiKey.isBlank()) showSettings = true
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    val tts = remember {
        TextToSpeech(context) { st -> ttsReady = (st == TextToSpeech.SUCCESS) }
    }
    DisposableEffect(Unit) {
        onDispose {
            tts.stop()
            tts.shutdown()
        }
    }

    fun speak(text: String) {
        if (!ttsReady) return
        val locale = if (langBn) Locale("bn", "BD") else Locale.US
        val r = tts.setLanguage(locale)
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            status = "এই ভাষার ভয়েস ফোনে নেই। ফোন Settings > Text-to-speech থেকে ইনস্টল করুন"
            return
        }
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "ariyan")
    }

    fun has(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun showError(msg: String) {
        messages.add(ChatMessage(msg, false, true))
        status = msg
    }

    val permsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val c = pendingCmd
        pendingCmd = null
        if (c != null) {
            if (result.values.all { it }) {
                retryCmd = c
            } else {
                status = "অনুমতি না দিলে এই কাজ করা যাবে না"
            }
        }
    }

    fun handleContactAction(cmd: Command) {
        val needed = mutableListOf<String>()
        if (cmd.number.isBlank() && !has(Manifest.permission.READ_CONTACTS)) {
            needed.add(Manifest.permission.READ_CONTACTS)
        }
        if (cmd.action == "call" && !has(Manifest.permission.CALL_PHONE)) {
            needed.add(Manifest.permission.CALL_PHONE)
        }
        if (needed.isNotEmpty()) {
            pendingCmd = cmd
            permsLauncher.launch(needed.toTypedArray())
            return
        }
        var label = cmd.contact
        var number = cmd.number
        if (number.isBlank()) {
            val found = findContact(context, cmd.contact, cmd.contactAlt)
            if (found == null) {
                showError("কন্টাক্টে \"${cmd.contact}\" পাওয়া যায়নি")
                return
            }
            label = found.first
            number = found.second
        }
        if (cmd.action == "call") {
            confirmCall = Pair(label.ifBlank { number }, number)
        } else {
            val err = composeSms(context, number, cmd.text)
            if (err != null) showError(err)
        }
    }

    fun runCommand(cmd: Command) {
        val err: String? = when (cmd.action) {
            "open_app" -> openApp(context, cmd.app)
            "flashlight" -> setFlashlight(context, cmd.on)
            "alarm" -> setAlarm(context, cmd.hour, cmd.minute, "Ariyan")
            "timer" -> setTimer(context, cmd.seconds, "Ariyan")
            "web_search" -> openUri(context, googleSearchUrl(cmd.query))
            "youtube_search" -> openUri(context, youtubeSearchUrl(cmd.query))
            "settings" -> openSettings(context, cmd.setting)
            "call", "sms" -> {
                handleContactAction(cmd)
                null
            }
            else -> null
        }
        if (err != null) showError(err)
    }

    LaunchedEffect(retryCmd) {
        val c = retryCmd
        if (c != null) {
            retryCmd = null
            runCommand(c)
        }
    }

    fun ask(question: String) {
        messages.add(ChatMessage(question, true))
        busy = true
        status = "ভাবছি..."
        scope.launch {
            try {
                val raw = askGemini(
                    apiKey,
                    model,
                    messages.filter { !it.isError },
                    buildSystemPrompt(appNames)
                )
                val cmd = parseCommand(raw)
                val say = cmd.say.ifBlank { if (cmd.action == "chat") raw else "ঠিক আছে" }
                messages.add(ChatMessage(say, false))
                status = "মাইক চাপুন আর কথা বলুন"
                speak(say)
                runCommand(cmd)
            } catch (e: Exception) {
                messages.add(ChatMessage("ত্রুটি: ${e.message}", false, true))
                status = "সমস্যা হয়েছে"
            }
            busy = false
        }
    }

    val recognizer = remember {
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            SpeechRecognizer.createSpeechRecognizer(context)
        } else {
            null
        }
    }

    DisposableEffect(recognizer) {
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                status = "শুনছি..."
            }

            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                listening = false
            }

            override fun onError(error: Int) {
                listening = false
                status = "বুঝতে পারিনি, আবার চেষ্টা করুন (কোড $error)"
            }

            override fun onResults(results: Bundle?) {
                listening = false
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                if (!text.isNullOrBlank()) {
                    ask(text)
                } else {
                    status = "কিছু শোনা যায়নি"
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        onDispose { recognizer?.destroy() }
    }

    fun startListening() {
        if (recognizer == null) {
            status = "এই ফোনে Speech Recognition নেই"
            return
        }
        if (apiKey.isBlank()) {
            showSettings = true
            return
        }
        tts.stop()
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (langBn) "bn-BD" else "en-US")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        listening = true
        recognizer.startListening(intent)
    }

    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startListening() else status = "মাইক্রোফোনের অনুমতি দিন"
    }

    fun onMicClick() {
        if (busy) return
        if (listening) {
            recognizer?.stopListening()
            return
        }
        if (has(Manifest.permission.RECORD_AUDIO)) {
            startListening()
        } else {
            micLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    if (showSettings) {
        SettingsScreen(
            apiKey = apiKey,
            model = model,
            onSave = { k, m ->
                apiKey = k
                model = m
                showSettings = false
                scope.launch {
                    context.dataStore.edit {
                        it[KEY_API] = k
                        it[KEY_MODEL] = m
                    }
                }
            },
            onClose = { showSettings = false }
        )
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Ariyan AI",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(onClick = { langBn = !langBn }) {
                    Text(if (langBn) "বাংলা" else "English")
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { showSettings = true }) {
                    Text("Settings")
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(messages) { m -> Bubble(m) }
            }

            Text(
                status,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                fontSize = 14.sp
            )

            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Button(
                    onClick = { onMicClick() },
                    shape = CircleShape,
                    modifier = Modifier.size(88.dp),
                    contentPadding = PaddingValues(0.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (listening) Color(0xFFD32F2F)
                        else MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text(if (listening) "Stop" else "Mic", fontSize = 18.sp)
                }
            }
        }
    }

    confirmCall?.let { (name, number) ->
        AlertDialog(
            onDismissRequest = { confirmCall = null },
            title = { Text("কল করবেন?") },
            text = { Text("$name\n$number") },
            confirmButton = {
                Button(onClick = {
                    confirmCall = null
                    val e = startCall(context, number)
                    if (e != null) showError(e)
                }) { Text("কল করুন") }
            },
            dismissButton = {
                OutlinedButton(onClick = { confirmCall = null }) { Text("না") }
            }
        )
    }
}

@Composable
fun Bubble(m: ChatMessage) {
    val bg = when {
        m.isError -> MaterialTheme.colorScheme.errorContainer
        m.fromUser -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val fg = when {
        m.isError -> MaterialTheme.colorScheme.onErrorContainer
        m.fromUser -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (m.fromUser) Arrangement.End else Arrangement.Start
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(bg)
                .padding(12.dp)
        ) {
            Text(m.text, color = fg)
        }
    }
}

@Composable
fun SettingsScreen(
    apiKey: String,
    model: String,
    onSave: (String, String) -> Unit,
    onClose: () -> Unit
) {
    var k by remember { mutableStateOf(apiKey) }
    var m by remember { mutableStateOf(model) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Settings", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text("Gemini API Key (aistudio.google.com থেকে নিন)")
        OutlinedTextField(
            value = k,
            onValueChange = { k = it.trim() },
            label = { Text("API Key") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = m,
            onValueChange = { m = it.trim() },
            label = { Text("Model") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Row {
            Button(onClick = { onSave(k, m.ifBlank { DEFAULT_MODEL }) }) {
                Text("Save")
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = onClose) {
                Text("Back")
            }
        }
    }
}
