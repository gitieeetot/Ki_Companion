package com.ki.companion

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.provider.OpenableColumns
import android.speech.RecognizerIntent
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.core.content.FileProvider
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URISyntaxException
import java.net.UnknownHostException
import java.net.URL
import java.util.Base64
import java.util.Locale

private val KiBackground = Color(0xFF17191C)
private val KiSurface = Color(0xD923282D)
private val KiSurfaceRaised = Color(0xE52A3036)
private val KiAccent = Color(0xFF8FE8FF)
private val KiAccentSoft = Color(0xFFB9F2FF)
private val KiText = Color(0xFFF2F4F6)
private val KiMuted = Color(0xFFA8B0B9)
private val KiError = Color(0xFFFF9D94)

private data class ChatMessage(val role: String, val content: String, val voicePrompt: Boolean = false)
private data class ChatModel(val name: String, val parameterSize: String, val sizeBytes: Long)
private data class TextAttachment(val name: String, val contentBase64: String, val isImage: Boolean = false)
private data class AgentSession(
    val id: String,
    val title: String,
    val project: String,
    val status: String,
    val gitAvailable: Boolean,
)
private data class AgentPermission(
    val id: String,
    val sessionId: String,
    val title: String,
    val type: String,
    val patterns: List<String>,
)
private data class AgentMessage(val role: String, val content: String)
private data class AgentGitResult(val status: String, val output: String)
private data class AgentEvent(
    val type: String,
    val sessionId: String,
    val project: String,
    val tool: String,
    val permissionTitle: String,
    val time: Long,
)
private data class SshConnectionInfo(val host: String, val username: String, val port: Int)

class MainActivity : ComponentActivity() {
    companion object {
        const val EXTRA_ALERT_SESSION = "opencode_alert_session"
        const val EXTRA_ALERT_PERMISSION = "opencode_alert_permission"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(34, 42, 49)
        window.navigationBarColor = android.graphics.Color.rgb(23, 25, 28)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = KiBackground) {
                    KiCompanionScreen(this)
                }
            }
        }
    }
}

@Composable
private fun KiCompanionScreen(context: Context) {
    val preferences = remember { context.getSharedPreferences("ki_companion", Context.MODE_PRIVATE) }
    val host = remember { mutableStateOf(preferences.getString("host", "") ?: "") }
    val token = remember { mutableStateOf(preferences.getString("token", "") ?: "") }
    val input = remember { mutableStateOf("") }
    val status = remember {
        mutableStateOf(if (host.value.isBlank()) "Set up a connection to your PC" else "Ready to chat with Ki on your PC")
    }
    val statusIsError = remember { mutableStateOf(false) }
    val busy = remember { mutableStateOf(false) }
    val speechPreparing = remember { mutableStateOf(false) }
    val speechListening = remember { mutableStateOf(false) }
    val speechRecognizer = remember { mutableStateOf<SpeechRecognizer?>(null) }
    val voiceEditMessage = remember { mutableStateOf<ChatMessage?>(null) }
    val voiceEditText = remember { mutableStateOf("") }
    val showSshDialog = remember { mutableStateOf(false) }
    val sshUsername = remember { mutableStateOf(preferences.getString("ssh_username", "") ?: "") }
    val sshPort = remember { mutableStateOf(preferences.getString("ssh_port", "22") ?: "22") }
    val sshHost = remember { mutableStateOf(preferences.getString("ssh_host", "") ?: "") }
    val chatModels = remember { mutableStateListOf<ChatModel>() }
    val selectedModel = remember {
        mutableStateOf(preferences.getString("chat_model", "qwen3:0.6b") ?: "qwen3:0.6b")
    }
    val modelMenuExpanded = remember { mutableStateOf(false) }
    val modelLoading = remember { mutableStateOf(false) }
    val updateBusy = remember { mutableStateOf(false) }
    val showUpdateDialog = remember { mutableStateOf(false) }
    val updateStatus = remember { mutableStateOf("Check GitHub for the latest Tot release.") }
    val availableUpdate = remember { mutableStateOf<AppUpdate?>(null) }
    val downloadedUpdate = remember { mutableStateOf<java.io.File?>(null) }
    val showSettings = remember { mutableStateOf(false) }
    val showPairingHelp = remember { mutableStateOf(false) }
    val floatingTot = remember {
        mutableStateOf(preferences.getBoolean(TotOverlayService.KEY_ENABLED, false))
    }
    val overlayPermission = remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    val voiceInputEnabled = remember { mutableStateOf(preferences.getBoolean("voice_input_enabled", true)) }
    val voiceOutput = remember { mutableStateOf(preferences.getString("voice_output", "both") ?: "both") }
    val ttsReady = remember { mutableStateOf(false) }
    val attachment = remember { mutableStateOf<TextAttachment?>(null) }
    val imageOcrText = remember { mutableStateOf("") }
    val showImageTextReview = remember { mutableStateOf(false) }
    val imageOcrBusy = remember { mutableStateOf(false) }
    val messages = remember { mutableStateListOf<ChatMessage>() }
    val agentSessions = remember { mutableStateListOf<AgentSession>() }
    val agentPermissions = remember { mutableStateListOf<AgentPermission>() }
    val agentEvents = remember { mutableStateListOf<AgentEvent>() }
    val agentMessages = remember { mutableStateListOf<AgentMessage>() }
    val selectedAgent = remember { mutableStateOf<AgentSession?>(null) }
    val lastLoadedAgentSessionId = remember { mutableStateOf("") }
    val lastLoadedAgentIdleEvent = remember { mutableStateOf(0L) }
    val agentGitStatus = remember { mutableStateOf("") }
    val agentGitOutput = remember { mutableStateOf("") }
    val pendingGitAction = remember { mutableStateOf<String?>(null) }
    val gitCommitMessage = remember { mutableStateOf("") }
    val agentInput = remember { mutableStateOf("") }
    val agentBusy = remember { mutableStateOf(false) }
    val showAgentPanel = remember { mutableStateOf(false) }
    val confirmAgentPermission = remember { mutableStateOf<AgentPermission?>(null) }
    val backgroundPermissionAlerts = remember {
        mutableStateOf(preferences.getBoolean("background_permission_alerts", false))
    }
    val alertSessionId = remember {
        mutableStateOf((context as? Activity)?.intent?.getStringExtra(MainActivity.EXTRA_ALERT_SESSION).orEmpty())
    }
    val alertPermissionId = remember {
        mutableStateOf((context as? Activity)?.intent?.getStringExtra(MainActivity.EXTRA_ALERT_PERMISSION).orEmpty())
    }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val phoneTts = remember(context) {
        TextToSpeech(context) { result ->
            ttsReady.value = result == TextToSpeech.SUCCESS
        }
    }

    LaunchedEffect(ttsReady.value) {
        if (ttsReady.value) {
            val result = phoneTts.setLanguage(Locale.getDefault())
            if (result == TextToSpeech.LANG_MISSING_DATA ||
                result == TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                ttsReady.value = false
            }
        }
    }

    DisposableEffect(phoneTts) {
        phoneTts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            private fun setActivity(activity: String) {
                Handler(Looper.getMainLooper()).post {
                    TotOverlayService.setActivity(context, activity)
                }
            }

            override fun onStart(utteranceId: String?) {
                setActivity("speaking")
            }

            override fun onDone(utteranceId: String?) {
                setActivity("happy")
            }

            override fun onError(utteranceId: String?) {
                setActivity("idle")
            }
        })
        onDispose {
            phoneTts.stop()
            phoneTts.shutdown()
            speechRecognizer.value?.destroy()
            speechRecognizer.value = null
            TotOverlayService.setActivity(context, "idle")
        }
    }

    DisposableEffect(context) {
        val activity = context as? ComponentActivity
        if (activity == null) {
            onDispose {}
        } else {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    floatingTot.value = preferences.getBoolean(TotOverlayService.KEY_ENABLED, false)
                    overlayPermission.value = Settings.canDrawOverlays(context)
                    if (
                        backgroundPermissionAlerts.value &&
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                        android.content.pm.PackageManager.PERMISSION_GRANTED
                    ) {
                        backgroundPermissionAlerts.value = false
                        setBackgroundPermissionAlerts(context, false)
                    }
                }
            }
            activity.lifecycle.addObserver(observer)
            onDispose { activity.lifecycle.removeObserver(observer) }
        }
    }

    LaunchedEffect(Unit) {
        if (preferences.getBoolean(TotOverlayService.KEY_ENABLED, false)) {
            if (Settings.canDrawOverlays(context)) {
                try {
                    TotOverlayService.start(context)
                } catch (error: IllegalStateException) {
                    preferences.edit().putBoolean(TotOverlayService.KEY_ENABLED, false).apply()
                    floatingTot.value = false
                    status.value = error.message ?: "Android couldn't restore Tot's floating buddy."
                    statusIsError.value = true
                } catch (error: SecurityException) {
                    preferences.edit().putBoolean(TotOverlayService.KEY_ENABLED, false).apply()
                    floatingTot.value = false
                    status.value = "Android blocked Tot's floating buddy. Check overlay permission and try again."
                    statusIsError.value = true
                }
            } else {
                preferences.edit().putBoolean(TotOverlayService.KEY_ENABLED, false).apply()
                floatingTot.value = false
                overlayPermission.value = false
            }
        }
    }

    fun submitPrompt(
        prompt: String,
        selectedAttachment: TextAttachment? = null,
        isVoicePrompt: Boolean = false,
    ) {
        if ((prompt.isBlank() && selectedAttachment == null) || busy.value) return
        val messageText = prompt.ifBlank {
            if (selectedAttachment?.isImage == true) "Please describe this image."
            else "Please review the attached file."
        }
        val chatMessages = messages.toList() + ChatMessage("user", messageText, isVoicePrompt)
        val selectedOutput = voiceOutput.value
        val requestModel = selectedModel.value
        input.value = ""
        attachment.value = null
        messages.add(ChatMessage("user", messageText, isVoicePrompt))
        status.value = "Ki is thinking…"
        statusIsError.value = false
        busy.value = true
        TotOverlayService.setActivity(context, "thinking")
        scope.launch {
            var phoneWillSpeak = false
            try {
                val answer = withContext(Dispatchers.IO) {
                    sendChat(
                        host.value,
                        token.value,
                        chatMessages,
                        selectedAttachment,
                        requestModel,
                        selectedOutput in setOf("pc", "both"),
                    )
                }
                messages.add(ChatMessage("assistant", answer))
                status.value = "Ki replied"
                if (selectedOutput in setOf("phone", "both")) {
                    if (ttsReady.value) {
                        val result = phoneTts.speak(answer, TextToSpeech.QUEUE_FLUSH, null, "ki-reply")
                        if (result == TextToSpeech.ERROR) {
                            status.value = "Ki replied, but phone speech could not start."
                            statusIsError.value = true
                        } else {
                            phoneWillSpeak = true
                        }
                    } else {
                        status.value = "Ki replied, but phone speech is not ready."
                        statusIsError.value = true
                    }
                }
            } catch (error: Exception) {
                messages.removeAt(messages.lastIndex)
                input.value = prompt
                attachment.value = selectedAttachment
                status.value = describeNetworkError(error)
                statusIsError.value = true
            } finally {
                busy.value = false
                if (!phoneWillSpeak) TotOverlayService.setActivity(context, "idle")
            }
        }
    }

    fun refreshChatModels() {
        if (modelLoading.value || host.value.isBlank() || token.value.isBlank()) return
        modelLoading.value = true
        scope.launch {
            try {
                val installed = withContext(Dispatchers.IO) {
                    fetchChatModels(host.value, token.value)
                }
                chatModels.clear()
                chatModels.addAll(installed)
                val selected = installed.firstOrNull {
                    it.name.equals(selectedModel.value, ignoreCase = true)
                } ?: installed.firstOrNull()
                if (selected != null && selected.name != selectedModel.value) {
                    selectedModel.value = selected.name
                    preferences.edit().putString("chat_model", selected.name).apply()
                }
                status.value = if (installed.isEmpty()) {
                    "Ollama has no compatible small chat models installed yet."
                } else {
                    "Found ${installed.size} local chat model${if (installed.size == 1) "" else "s"}."
                }
                statusIsError.value = installed.isEmpty()
            } catch (error: Exception) {
                status.value = describeNetworkError(error)
                statusIsError.value = true
            } finally {
                modelLoading.value = false
            }
        }
    }

    fun openTermuxForSsh() {
        val username = sshUsername.value.trim()
        val port = sshPort.value.toIntOrNull()
        if (!username.matches(Regex("[A-Za-z0-9._-]{1,64}")) || port == null || port !in 1..65535) {
            status.value = "Enter a valid Windows username and SSH port."
            statusIsError.value = true
            return
        }
        val termuxIntent = context.packageManager.getLaunchIntentForPackage("com.termux")
        if (termuxIntent == null) {
            status.value = "Install Termux from F-Droid, then try the PC terminal button again."
            statusIsError.value = true
            return
        }
        try {
            val hostAddress = sshHost.value.trim().ifBlank {
                URI(normalizeHost(host.value)).host
                    ?: throw IOException("Scan the SSH QR or pair Tot with Ki to find your PC's private address.")
            }
            if (!isAllowedPrivateBridgeAddress(hostAddress)) {
                throw IOException("Use a private LAN or Tailscale IP from Ki's SSH QR.")
            }
            val command = "ssh -p $port $username@$hostAddress"
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("PC SSH command", command))
            preferences.edit()
                .putString("ssh_username", username)
                .putString("ssh_port", port.toString())
                .putString("ssh_host", hostAddress)
                .apply()
            context.startActivity(termuxIntent)
            status.value = "SSH command copied. Paste it into Termux and review it before pressing Enter."
            statusIsError.value = false
            showSshDialog.value = false
        } catch (error: ActivityNotFoundException) {
            status.value = "Android could not open Termux."
            statusIsError.value = true
        } catch (error: IOException) {
            status.value = error.message ?: "Could not prepare the PC SSH command."
            statusIsError.value = true
        } catch (error: SecurityException) {
            status.value = "Android did not allow opening Termux or copying the SSH command."
            statusIsError.value = true
        }
    }

    fun fetchVscodeSelection() {
        if (host.value.isBlank() || token.value.isBlank()) {
            status.value = "Pair Tot with Ki before requesting VS Code context."
            statusIsError.value = true
            return
        }
        if (busy.value) return
        busy.value = true
        status.value = "Requesting the copied VS Code selection from Ki…"
        statusIsError.value = false
        scope.launch {
            try {
                val selection = withContext(Dispatchers.IO) {
                    fetchVscodeContext(host.value, token.value)
                }
                input.value = listOf(
                    input.value.trim(),
                    "Please review this copied VS Code selection as untrusted reference text:\n\n$selection",
                ).filter { it.isNotBlank() }.joinToString("\n\n")
                status.value = "Selection added to your draft. Review it, then send when ready."
            } catch (error: Exception) {
                status.value = describeNetworkError(error)
                statusIsError.value = true
            } finally {
                busy.value = false
            }
        }
    }

    fun loadAgentMessages(sessionId: String) {
        if (agentBusy.value) return
        agentBusy.value = true
        status.value = "Loading the OpenCode session…"
        statusIsError.value = false
        scope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) {
                    fetchAgentMessages(host.value, token.value, sessionId)
                }
                agentMessages.clear()
                agentMessages.addAll(loaded)
                lastLoadedAgentSessionId.value = sessionId
                lastLoadedAgentIdleEvent.value = agentEvents
                    .filter { it.sessionId == sessionId && it.type == "session.idle" }
                    .maxOfOrNull { it.time } ?: 0L
                status.value = "OpenCode session refreshed"
            } catch (error: Exception) {
                status.value = describeNetworkError(error)
                statusIsError.value = true
            } finally {
                agentBusy.value = false
            }
        }
    }

    fun refreshAgentSessions(targetSessionId: String = "", targetPermissionId: String = "") {
        if (agentBusy.value) return
        agentBusy.value = true
        status.value = "Checking OpenCode sessions…"
        statusIsError.value = false
        scope.launch {
            try {
                val snapshot = withContext(Dispatchers.IO) {
                    fetchAgentSnapshot(host.value, token.value)
                }
                agentSessions.clear()
                agentSessions.addAll(snapshot.first)
                agentPermissions.clear()
                agentPermissions.addAll(snapshot.second)
                agentEvents.clear()
                agentEvents.addAll(snapshot.third)
                val current = selectedAgent.value
                val selected = agentSessions.firstOrNull { it.id == targetSessionId }
                    ?: agentSessions.firstOrNull { it.id == current?.id }
                    ?: agentSessions.firstOrNull()
                if (current?.id != selected?.id) {
                    agentGitStatus.value = ""
                    agentGitOutput.value = ""
                }
                selectedAgent.value = selected
                if (targetPermissionId.isNotBlank()) {
                    confirmAgentPermission.value = agentPermissions.firstOrNull {
                        it.id == targetPermissionId && it.sessionId == selected?.id
                    }
                }
                showAgentPanel.value = true
                if (selected != null) {
                    val loaded = withContext(Dispatchers.IO) {
                        fetchAgentMessages(host.value, token.value, selected.id)
                    }
                    agentMessages.clear()
                    agentMessages.addAll(loaded)
                    lastLoadedAgentSessionId.value = selected.id
                    lastLoadedAgentIdleEvent.value = agentEvents
                        .filter { it.sessionId == selected.id && it.type == "session.idle" }
                        .maxOfOrNull { it.time } ?: 0L
                } else {
                    agentMessages.clear()
                    lastLoadedAgentSessionId.value = ""
                    lastLoadedAgentIdleEvent.value = 0L
                }
                status.value = if (agentSessions.isEmpty()) {
                    "No OpenCode sessions seen yet. Start OpenCode with Ki's monitor enabled."
                } else {
                    "OpenCode activity loaded"
                }
            } catch (error: Exception) {
                status.value = describeNetworkError(error)
                statusIsError.value = true
            } finally {
                agentBusy.value = false
            }
        }
    }

    fun sendAgentAction(action: String, sessionId: String, text: String = "", permission: AgentPermission? = null, response: String = "") {
        if (agentBusy.value) return
        agentBusy.value = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    sendAgentAction(
                        host.value,
                        token.value,
                        action,
                        sessionId,
                        text,
                        permission?.id.orEmpty(),
                        response,
                    )
                }
                val snapshot = withContext(Dispatchers.IO) {
                    fetchAgentSnapshot(host.value, token.value)
                }
                agentSessions.clear()
                agentSessions.addAll(snapshot.first)
                agentPermissions.clear()
                agentPermissions.addAll(snapshot.second)
                agentEvents.clear()
                agentEvents.addAll(snapshot.third)
                val selected = agentSessions.firstOrNull { it.id == sessionId }
                selectedAgent.value = selected
                if (selected != null) {
                    val loaded = withContext(Dispatchers.IO) {
                        fetchAgentMessages(host.value, token.value, selected.id)
                    }
                    agentMessages.clear()
                    agentMessages.addAll(loaded)
                    lastLoadedAgentSessionId.value = selected.id
                    lastLoadedAgentIdleEvent.value = agentEvents
                        .filter { it.sessionId == selected.id && it.type == "session.idle" }
                        .maxOfOrNull { it.time } ?: 0L
                }
                agentInput.value = if (action == "prompt" || action == "answer") "" else agentInput.value
                status.value = when (action) {
                    "permission" -> if (response == "allow_once") "Allowed this one OpenCode request" else "Denied the OpenCode request"
                    else -> "Sent your message to OpenCode"
                }
                statusIsError.value = false
            } catch (error: Exception) {
                status.value = describeNetworkError(error)
                statusIsError.value = true
            } finally {
                agentBusy.value = false
            }
        }
    }

    fun runAgentGit(operation: String, commitMessage: String = "") {
        val session = selectedAgent.value ?: return
        if (agentBusy.value) return
        agentBusy.value = true
        status.value = "Running Git ${operation.replace('_', ' ')}…"
        statusIsError.value = false
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    sendAgentGitAction(
                        host.value,
                        token.value,
                        session.id,
                        operation,
                        commitMessage,
                    )
                }
                agentGitStatus.value = result.status
                agentGitOutput.value = result.output
                status.value = "Git ${operation.replace('_', ' ')} completed"
            } catch (error: Exception) {
                status.value = describeNetworkError(error)
                statusIsError.value = true
            } finally {
                agentBusy.value = false
            }
        }
    }

    LaunchedEffect(alertSessionId.value, alertPermissionId.value) {
        if (alertSessionId.value.isNotBlank() && alertPermissionId.value.isNotBlank()) {
            refreshAgentSessions(alertSessionId.value, alertPermissionId.value)
        }
    }

    fun readImageTextOnPhone() {
        val selectedImage = attachment.value?.takeIf { it.isImage } ?: return
        imageOcrBusy.value = true
        status.value = "Reading text on Tot…"
        statusIsError.value = false
        scope.launch {
            try {
                val recognized = withContext(Dispatchers.IO) {
                    recognizeImageText(selectedImage)
                }
                if (recognized.isBlank()) {
                    status.value = "No readable text found. You can still send the image to Ki."
                } else {
                    imageOcrText.value = recognized
                    showImageTextReview.value = true
                    status.value = "Text found. Review it before sending to Ki."
                }
            } catch (error: Exception) {
                status.value = error.message ?: "Tot could not read text from that image."
                statusIsError.value = true
            } finally {
                imageOcrBusy.value = false
            }
        }
    }

    fun shareImageWithLens() {
        val selectedImage = attachment.value?.takeIf { it.isImage } ?: return
        try {
            val imageFile = File(context.cacheDir, "shared-images/tot-image.jpg")
            imageFile.parentFile?.mkdirs()
            imageFile.writeBytes(Base64.getDecoder().decode(selectedImage.contentBase64))
            val imageUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                imageFile,
            )
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "image/jpeg"
                putExtra(Intent.EXTRA_STREAM, imageUri)
                clipData = ClipData.newUri(context.contentResolver, selectedImage.name, imageUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(shareIntent, "Choose Google Lens or another image app"))
            status.value = "Choose Google Lens in the Android share menu."
            statusIsError.value = false
        } catch (error: ActivityNotFoundException) {
            status.value = "No app can open the Android image share menu."
            statusIsError.value = true
        } catch (error: IOException) {
            status.value = error.message ?: "Could not prepare the image for Google Lens."
            statusIsError.value = true
        } catch (error: IllegalArgumentException) {
            status.value = "Android could not share this image safely."
            statusIsError.value = true
        } catch (error: SecurityException) {
            status.value = "Android did not allow sharing this image."
            statusIsError.value = true
        }
    }

    fun startSpeechRecognition() {
        if (!voiceInputEnabled.value || busy.value || speechPreparing.value || speechListening.value) return
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            status.value = "Speech recognition is unavailable on this phone. Install or enable a speech service."
            statusIsError.value = true
            return
        }
        try {
            val recognizer = speechRecognizer.value
                ?: SpeechRecognizer.createSpeechRecognizer(context).also { created ->
                    created.setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) {
                            speechPreparing.value = false
                            speechListening.value = true
                            status.value = "Listening…"
                            statusIsError.value = false
                            TotOverlayService.setActivity(context, "listening")
                        }

                        override fun onBeginningOfSpeech() {
                            speechPreparing.value = false
                            speechListening.value = true
                            status.value = "Listening…"
                            TotOverlayService.setActivity(context, "listening")
                        }

                        override fun onEndOfSpeech() {
                            speechListening.value = false
                            status.value = "Transcribing your message…"
                            TotOverlayService.setActivity(context, "thinking")
                        }

                        override fun onResults(results: Bundle?) {
                            speechPreparing.value = false
                            speechListening.value = false
                            val spokenText = results
                                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                ?.firstOrNull()
                                .orEmpty()
                            if (spokenText.isNotBlank()) {
                                status.value = "Sending your voice message…"
                                statusIsError.value = false
                                submitPrompt(spokenText, isVoicePrompt = true)
                            } else {
                                status.value = "I didn't catch that. Tap the mic to retry."
                                statusIsError.value = false
                                TotOverlayService.setActivity(context, "idle")
                            }
                        }

                        override fun onError(error: Int) {
                            speechPreparing.value = false
                            speechListening.value = false
                            status.value = when (error) {
                                SpeechRecognizer.ERROR_AUDIO -> "Microphone audio failed. Check phone microphone access and retry."
                                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Allow microphone access in Android settings, then retry."
                                SpeechRecognizer.ERROR_NETWORK,
                                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "The phone's speech service needs a working network. Check its settings and retry."
                                SpeechRecognizer.ERROR_NO_MATCH,
                                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "I didn't catch that. Tap the mic to retry."
                                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "The phone's speech service is busy. Wait a moment and retry."
                                else -> "Speech recognition stopped. Tap the mic to retry."
                            }
                            statusIsError.value = error !in setOf(
                                SpeechRecognizer.ERROR_NO_MATCH,
                                SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                            )
                            TotOverlayService.setActivity(context, "idle")
                        }

                        override fun onBufferReceived(buffer: ByteArray?) = Unit
                        override fun onRmsChanged(rmsdB: Float) = Unit
                        override fun onPartialResults(partialResults: Bundle?) = Unit
                        override fun onEvent(eventType: Int, params: Bundle?) = Unit
                    })
                    speechRecognizer.value = created
                }
            speechPreparing.value = true
            speechListening.value = false
            status.value = "Preparing microphone…"
            statusIsError.value = false
            TotOverlayService.setActivity(context, "idle")
            recognizer.startListening(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                },
            )
        } catch (error: ActivityNotFoundException) {
            speechPreparing.value = false
            status.value = "Speech recognition is unavailable on this phone. Install or enable a speech service."
            statusIsError.value = true
        } catch (error: IllegalStateException) {
            speechPreparing.value = false
            status.value = "The phone's speech service could not start. Wait a moment and retry."
            statusIsError.value = true
        } catch (error: SecurityException) {
            speechPreparing.value = false
            status.value = "Android did not allow microphone access."
            statusIsError.value = true
        }
    }

    val microphonePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) startSpeechRecognition()
        else {
            status.value = "Allow microphone access in Android settings to talk to Tot."
            statusIsError.value = true
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            backgroundPermissionAlerts.value = true
            setBackgroundPermissionAlerts(context, true)
            status.value = "Background OpenCode permission alerts enabled."
            statusIsError.value = false
        } else {
            backgroundPermissionAlerts.value = false
            status.value = "Android notification access was not granted; background alerts remain off."
            statusIsError.value = true
        }
    }

    fun setBackgroundAlerts(enabled: Boolean) {
        if (
            enabled &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        backgroundPermissionAlerts.value = enabled
        setBackgroundPermissionAlerts(context, enabled)
        status.value = if (enabled) {
            "Background alerts are on; Android checks periodically and may delay them."
        } else {
            "Background OpenCode permission alerts are off."
        }
        statusIsError.value = false
    }

    val qrScanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        val contents = result.contents ?: return@rememberLauncherForActivityResult
        try {
            val pairing = parsePairingCode(contents)
            host.value = pairing.first
            token.value = pairing.second
            preferences.edit().putString("host", host.value).putString("token", token.value).apply()
            status.value = "QR scanned. Tap Test connection to finish pairing."
            statusIsError.value = false
        } catch (error: IOException) {
            status.value = error.message ?: "That QR code is not a Ki pairing code."
            statusIsError.value = true
        }
    }

    val sshQrScanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        val contents = result.contents ?: return@rememberLauncherForActivityResult
        try {
            val connection = parseSshQr(contents)
            sshHost.value = connection.host
            sshUsername.value = connection.username
            sshPort.value = connection.port.toString()
            preferences.edit()
                .putString("ssh_host", connection.host)
                .putString("ssh_username", connection.username)
                .putString("ssh_port", connection.port.toString())
                .apply()
            status.value = "SSH QR scanned. Review the host, username, and port before opening Termux."
            statusIsError.value = false
        } catch (error: IOException) {
            status.value = error.message ?: "That is not a Ki SSH connection QR."
            statusIsError.value = true
        }
    }

    val installPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        val apk = downloadedUpdate.value
        if (apk != null && context.packageManager.canRequestPackageInstalls()) {
            openUpdateInstaller(context, apk) { updateStatus.value = it }
        } else if (apk != null) {
            updateStatus.value = "Allow Tot to install unknown apps, then tap Install update again."
        }
    }

    fun updateFloatingTot(enabled: Boolean) {
        if (!enabled) {
            TotOverlayService.stop(context)
            floatingTot.value = false
            preferences.edit().putBoolean(TotOverlayService.KEY_ENABLED, false).apply()
            status.value = "Tot is resting. Wake him again from Home whenever you like."
            statusIsError.value = false
            return
        }
        try {
            TotOverlayService.start(context)
            floatingTot.value = true
            preferences.edit().putBoolean(TotOverlayService.KEY_ENABLED, true).apply()
            status.value = "Tot is awake. Drag him anywhere and tap for a hello."
            statusIsError.value = false
        } catch (error: IllegalStateException) {
            floatingTot.value = false
            status.value = error.message ?: "Android couldn't start Tot's floating buddy."
            statusIsError.value = true
        } catch (error: SecurityException) {
            floatingTot.value = false
            status.value = "Android blocked Tot's floating buddy. Check overlay permission and try again."
            statusIsError.value = true
        }
    }

    val overlayPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        overlayPermission.value = Settings.canDrawOverlays(context)
        if (overlayPermission.value) {
            updateFloatingTot(true)
        } else {
            status.value = "Allow display over other apps to wake Tot above your other apps."
            statusIsError.value = true
        }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    attachment.value = readAttachment(context, uri)
                    status.value = "Attached ${attachment.value?.name}"
                    statusIsError.value = false
                } catch (error: IOException) {
                    status.value = error.message ?: "Could not read that file."
                    statusIsError.value = true
                } catch (error: SecurityException) {
                    status.value = error.message ?: "Android did not allow Ki to read that file."
                    statusIsError.value = true
                }
            }
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    LaunchedEffect(host.value, token.value) {
        if (host.value.isNotBlank() && token.value.isNotBlank()) refreshChatModels()
    }

    LaunchedEffect(showAgentPanel.value) {
        while (showAgentPanel.value) {
            if (!agentBusy.value && host.value.isNotBlank() && token.value.isNotBlank()) {
                try {
                    val snapshot = withContext(Dispatchers.IO) {
                        fetchAgentSnapshot(host.value, token.value)
                    }
                    agentSessions.clear()
                    agentSessions.addAll(snapshot.first)
                    agentPermissions.clear()
                    agentPermissions.addAll(snapshot.second)
                    agentEvents.clear()
                    agentEvents.addAll(snapshot.third)
                    val current = selectedAgent.value
                    val selected = agentSessions.firstOrNull { it.id == current?.id }
                        ?: agentSessions.firstOrNull()
                    selectedAgent.value = selected
                    val latestIdleEvent = snapshot.third
                        .filter { it.sessionId == selected?.id && it.type == "session.idle" }
                        .maxOfOrNull { it.time } ?: 0L
                    if (
                        selected != null &&
                        selected.status == "idle" &&
                        latestIdleEvent > 0L &&
                        (
                            lastLoadedAgentSessionId.value != selected.id ||
                                latestIdleEvent > lastLoadedAgentIdleEvent.value ||
                                current?.status != "idle"
                            )
                    ) {
                        val loaded = withContext(Dispatchers.IO) {
                            fetchAgentMessages(host.value, token.value, selected.id)
                        }
                        agentMessages.clear()
                        agentMessages.addAll(loaded)
                        lastLoadedAgentSessionId.value = selected.id
                        lastLoadedAgentIdleEvent.value = latestIdleEvent
                    }
                } catch (error: Exception) {
                    status.value = describeNetworkError(error)
                    statusIsError.value = true
                }
            }
            delay(8_000)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF222A31), KiBackground, Color(0xFF141619)),
                ),
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TotMark(Modifier.size(42.dp))
            Column(
                modifier = Modifier.weight(1f).padding(start = 10.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                Text("Tot", color = KiText, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("YOUR POCKET COMPANION", color = KiMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }
            TextButton(
                enabled = !busy.value && !agentBusy.value,
                onClick = { refreshAgentSessions() },
            ) {
                Text("Agents", color = KiAccent, fontWeight = FontWeight.SemiBold)
            }
            TextButton(onClick = {
                showSettings.value = true
                refreshChatModels()
            }) {
                Text("Settings", color = KiAccent, fontWeight = FontWeight.SemiBold)
            }
        }

        TotWakeCard(
            isAwake = floatingTot.value,
            canDisplayOverApps = overlayPermission.value,
            onClick = {
                if (floatingTot.value) {
                    updateFloatingTot(false)
                } else if (Settings.canDrawOverlays(context)) {
                    updateFloatingTot(true)
                } else {
                    overlayPermissionLauncher.launch(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}"),
                        ),
                    )
                }
            },
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                enabled = !busy.value,
                onClick = { fetchVscodeSelection() },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, KiAccent.copy(alpha = 0.35f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = KiText),
            ) {
                Text("VS Code", fontWeight = FontWeight.SemiBold)
            }
            OutlinedButton(
                onClick = { showSshDialog.value = true },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, Color(0xFFE0A64B).copy(alpha = 0.45f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = KiText),
            ) {
                Text("Termux SSH", fontWeight = FontWeight.SemiBold)
            }
        }

        ConnectionStatusCard(
            status = status.value,
            isError = statusIsError.value,
            isThinking = busy.value,
            onClick = {
                showSettings.value = true
            },
        )

        if (messages.isEmpty()) {
            EmptyChatCard(modifier = Modifier.weight(1f))
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(20.dp))
                    .background(KiSurface)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                itemsIndexed(messages) { _, message ->
                    MessageBubble(
                        message = message,
                        onEditVoice = {
                            voiceEditText.value = message.content
                            voiceEditMessage.value = message
                        },
                        onRetryVoice = {
                            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                                android.content.pm.PackageManager.PERMISSION_GRANTED
                            ) {
                                startSpeechRecognition()
                            } else {
                                microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                        onDeleteVoice = {
                            messages.remove(message)
                            status.value = "Removed from Tot on this phone. It may still be in Ki's PC chat."
                            statusIsError.value = false
                        },
                    )
                }
            }
        }

        if (attachment.value != null) {
            val selectedAttachment = attachment.value
            if (selectedAttachment?.isImage == true) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = KiSurfaceRaised),
                    border = BorderStroke(1.dp, KiAccent.copy(alpha = 0.22f)),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Image ready", color = KiText, fontWeight = FontWeight.SemiBold)
                                Text(selectedAttachment.name, color = KiMuted, fontSize = 11.sp, maxLines = 1)
                            }
                            TextButton(onClick = { attachment.value = null }) {
                                Text("Remove", color = KiMuted)
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(
                                enabled = !busy.value && !imageOcrBusy.value,
                                onClick = { readImageTextOnPhone() },
                                modifier = Modifier.weight(1f),
                            ) {
                                if (imageOcrBusy.value) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        color = KiAccent,
                                        strokeWidth = 2.dp,
                                    )
                                } else {
                                    Text("Read text", color = KiAccent, fontWeight = FontWeight.SemiBold)
                                }
                            }
                            TextButton(
                                enabled = !busy.value && !imageOcrBusy.value,
                                onClick = { shareImageWithLens() },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("Google Lens", color = KiAccent, fontWeight = FontWeight.SemiBold)
                            }
                        }
                        Button(
                            enabled = !busy.value && !imageOcrBusy.value,
                            onClick = {
                                val prompt = input.value.trim().ifBlank {
                                    "Please describe this image and help me identify anything important."
                                }
                                submitPrompt(prompt, selectedAttachment)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = KiAccent,
                                contentColor = Color(0xFF101417),
                            ),
                        ) {
                            Text("Ask Ki about image · uses PC", fontWeight = FontWeight.SemiBold)
                        }
                        Text(
                            "Read text stays on this phone. Sending the image to Ki uses your PC’s vision model.",
                            color = KiMuted,
                            fontSize = 10.sp,
                        )
                    }
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(KiSurfaceRaised)
                        .padding(start = 12.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "📎  ${selectedAttachment?.name}",
                        color = KiText,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                    )
                    TextButton(onClick = { attachment.value = null }) {
                        Text("Remove", color = KiMuted)
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (voiceInputEnabled.value) {
                TextButton(
                    enabled = !busy.value && !speechPreparing.value,
                    onClick = {
                        if (speechListening.value) {
                            speechRecognizer.value?.stopListening()
                            speechListening.value = false
                            status.value = "Finishing transcription…"
                            TotOverlayService.setActivity(context, "thinking")
                        } else if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                            android.content.pm.PackageManager.PERMISSION_GRANTED
                        ) {
                            startSpeechRecognition()
                        } else {
                            microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                ) {
                    when {
                        speechPreparing.value -> CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = KiAccent,
                            strokeWidth = 2.dp,
                        )
                        speechListening.value -> Text("Stop", color = KiError, fontWeight = FontWeight.SemiBold)
                        else -> Text("Mic", color = KiAccent, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            OutlinedTextField(
                value = input.value,
                onValueChange = { input.value = it },
                placeholder = { Text("Message Tot…", color = KiMuted) },
                modifier = Modifier.weight(1f),
                maxLines = 4,
                shape = RoundedCornerShape(20.dp),
                colors = kiTextFieldColors(),
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                TextButton(
                    enabled = !busy.value,
                    onClick = { filePicker.launch(arrayOf("text/*", "application/json", "image/*")) },
                ) { Text("Attach", color = KiAccent, fontWeight = FontWeight.SemiBold) }
                Button(
                    enabled = !busy.value && (input.value.isNotBlank() || attachment.value != null),
                    onClick = { submitPrompt(input.value.trim(), attachment.value) },
                    shape = CircleShape,
                    contentPadding = ButtonDefaults.ContentPadding,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = KiAccent,
                        contentColor = Color(0xFF101417),
                        disabledContainerColor = KiSurfaceRaised,
                        disabledContentColor = KiMuted,
                    ),
                ) {
                    Text(if (busy.value) "…" else "↑", fontSize = 21.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Text(
            "Tot connects to Ki over trusted Wi-Fi or your private Tailscale network.",
            color = KiMuted,
            fontSize = 11.sp,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
    }

    voiceEditMessage.value?.let { message ->
        AlertDialog(
            onDismissRequest = { voiceEditMessage.value = null },
            containerColor = KiSurface,
            title = { Text("Edit spoken message", color = KiText, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "This prompt was already sent. Editing sends your correction to Ki as a new message.",
                        color = KiMuted,
                        fontSize = 11.sp,
                    )
                    OutlinedTextField(
                        value = voiceEditText.value,
                        onValueChange = { voiceEditText.value = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 6,
                        colors = kiTextFieldColors(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !busy.value && voiceEditText.value.isNotBlank(),
                    onClick = {
                        val correctedText = voiceEditText.value.trim()
                        voiceEditMessage.value = null
                        submitPrompt(correctedText, isVoicePrompt = true)
                    },
                ) { Text("Send correction", color = KiAccent, fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = {
                TextButton(onClick = { voiceEditMessage.value = null }) {
                    Text("Cancel", color = KiMuted)
                }
            },
        )
    }

    if (showSshDialog.value) {
        AlertDialog(
            onDismissRequest = { showSshDialog.value = false },
            containerColor = KiSurface,
            title = { Text("Open your PC terminal", color = KiText, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Scan the SSH QR shown in Ki's phone companion, or use the PC address from pairing. Tot copies the command and opens Termux; review and run it yourself. Your PC must have OpenSSH Server enabled and be reachable on your private network or Tailscale.",
                        color = KiMuted,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                    )
                    OutlinedButton(
                        onClick = {
                            sshQrScanner.launch(
                                ScanOptions()
                                    .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                    .setPrompt("Scan the separate SSH QR from Ki on your PC")
                                    .setBeepEnabled(false),
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(13.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = KiText),
                    ) {
                        Text("Scan SSH connection QR")
                    }
                    OutlinedTextField(
                        value = sshUsername.value,
                        onValueChange = { sshUsername.value = it.take(64) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Windows username") },
                        singleLine = true,
                        colors = kiTextFieldColors(),
                    )
                    OutlinedTextField(
                        value = sshHost.value,
                        onValueChange = { sshHost.value = it.take(64) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Private PC IP address") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        colors = kiTextFieldColors(),
                    )
                    OutlinedTextField(
                        value = sshPort.value,
                        onValueChange = { value ->
                            if (value.length <= 5 && value.all(Char::isDigit)) sshPort.value = value
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("SSH port") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = kiTextFieldColors(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { openTermuxForSsh() }) {
                    Text("Copy command & open Termux", color = KiAccent, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSshDialog.value = false }) {
                    Text("Cancel", color = KiMuted)
                }
            },
        )
    }

    if (showImageTextReview.value) {
        AlertDialog(
            onDismissRequest = { showImageTextReview.value = false },
            containerColor = KiSurface,
            title = { Text("Review text from your image", color = KiText, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Recognized on Tot. Edit it if needed; only this text will be sent to Ki.",
                        color = KiMuted,
                        fontSize = 12.sp,
                    )
                    OutlinedTextField(
                        value = imageOcrText.value,
                        onValueChange = { imageOcrText.value = it },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 300.dp),
                        maxLines = 10,
                        colors = kiTextFieldColors(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !busy.value && imageOcrText.value.isNotBlank(),
                    onClick = {
                        val recognizedText = imageOcrText.value.trim()
                        showImageTextReview.value = false
                        attachment.value = null
                        submitPrompt(
                            "Please help me understand this text from my image:\n\n$recognizedText",
                        )
                    },
                ) {
                    Text("Send text to Ki", color = KiAccent, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showImageTextReview.value = false }) {
                    Text("Cancel", color = KiMuted)
                }
            },
        )
    }

    if (showAgentPanel.value) {
        AlertDialog(
            onDismissRequest = { showAgentPanel.value = false },
            containerColor = KiSurface,
            title = {
                Text("OpenCode sessions", color = KiText, fontWeight = FontWeight.Bold)
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 560.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "Sessions and messages are read from OpenCode on your PC. Permission actions apply only to the selected request.",
                        color = KiMuted,
                        fontSize = 11.sp,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Background permission alerts", color = KiText, fontSize = 12.sp)
                            Text(
                                "Optional; checks about every 15 minutes. Android or Tailscale may delay delivery. Tap an alert to review; nothing is auto-approved.",
                                color = KiMuted,
                                fontSize = 10.sp,
                            )
                        }
                        Switch(
                            checked = backgroundPermissionAlerts.value,
                            onCheckedChange = { setBackgroundAlerts(it) },
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Recent sessions", color = KiText, fontWeight = FontWeight.SemiBold)
                        TextButton(
                            enabled = !agentBusy.value,
                            onClick = { refreshAgentSessions() },
                        ) { Text("Refresh", color = KiAccent) }
                    }
                    if (agentSessions.isEmpty()) {
                        Text(
                            "No sessions yet. Enable the OpenCode monitor on Ki's PC, then restart OpenCode.",
                            color = KiMuted,
                            fontSize = 12.sp,
                        )
                    }
                    agentSessions.forEach { session ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !agentBusy.value) {
                                    selectedAgent.value = session
                                    agentMessages.clear()
                                    lastLoadedAgentSessionId.value = ""
                                    lastLoadedAgentIdleEvent.value = 0L
                                    agentGitStatus.value = ""
                                    agentGitOutput.value = ""
                                    loadAgentMessages(session.id)
                                },
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (selectedAgent.value?.id == session.id) KiSurfaceRaised else KiBackground,
                            ),
                            border = BorderStroke(
                                1.dp,
                                if (selectedAgent.value?.id == session.id) {
                                    KiAccent.copy(alpha = 0.35f)
                                } else {
                                    Color.White.copy(alpha = 0.06f)
                                },
                            ),
                        ) {
                            Column(Modifier.padding(10.dp)) {
                                Text(session.title.ifBlank { "OpenCode session" }, color = KiText, fontWeight = FontWeight.SemiBold)
                                Text(
                                    listOf(session.project, session.status).filter { it.isNotBlank() }.joinToString(" · "),
                                    color = KiMuted,
                                    fontSize = 10.sp,
                                )
                            }
                        }
                    }
                    val selected = selectedAgent.value
                    if (selected != null) {
                        val sessionEvents = agentEvents.filter { it.sessionId == selected.id }.takeLast(8).reversed()
                        if (sessionEvents.isNotEmpty()) {
                            Text("Recent activity", color = KiText, fontWeight = FontWeight.SemiBold)
                            Card(
                                shape = RoundedCornerShape(14.dp),
                                colors = CardDefaults.cardColors(containerColor = KiBackground),
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f)),
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxWidth().padding(11.dp),
                                    verticalArrangement = Arrangement.spacedBy(7.dp),
                                ) {
                                    sessionEvents.forEach { event ->
                                        val activity = when (event.type) {
                                            "session.created" -> "Session started"
                                            "session.idle" -> "Agent is waiting"
                                            "session.error" -> "Session error"
                                            "permission.asked" -> "Permission requested: ${event.permissionTitle}"
                                            "permission.replied" -> "Permission answered"
                                            "tool.execute.before" -> "Started ${event.tool.ifBlank { "a tool" }}"
                                            "tool.execute.after" -> "Finished ${event.tool.ifBlank { "a tool" }}"
                                            else -> event.type.replace('.', ' ')
                                        }
                                        Text(activity, color = KiMuted, fontSize = 10.sp)
                                    }
                                }
                            }
                        }
                        Text("Git for this project", color = KiText, fontWeight = FontWeight.SemiBold)
                        if (!selected.gitAvailable) {
                            Text(
                                "This OpenCode session has no verified local project folder.",
                                color = KiMuted,
                                fontSize = 11.sp,
                            )
                        } else {
                            Text(
                                "Git stays inside this session's project. Stage, commit, pull, and push each need your confirmation.",
                                color = KiMuted,
                                fontSize = 10.sp,
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                OutlinedButton(
                                    enabled = !agentBusy.value,
                                    onClick = { runAgentGit("status") },
                                    modifier = Modifier.weight(1f),
                                ) { Text("Status") }
                                OutlinedButton(
                                    enabled = !agentBusy.value,
                                    onClick = { runAgentGit("diff") },
                                    modifier = Modifier.weight(1f),
                                ) { Text("Review diff") }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                OutlinedButton(
                                    enabled = !agentBusy.value,
                                    onClick = { pendingGitAction.value = "stage_all" },
                                    modifier = Modifier.weight(1f),
                                ) { Text("Stage all") }
                                OutlinedButton(
                                    enabled = !agentBusy.value,
                                    onClick = {
                                        gitCommitMessage.value = ""
                                        pendingGitAction.value = "commit"
                                    },
                                    modifier = Modifier.weight(1f),
                                ) { Text("Commit") }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                OutlinedButton(
                                    enabled = !agentBusy.value,
                                    onClick = { pendingGitAction.value = "pull" },
                                    modifier = Modifier.weight(1f),
                                ) { Text("Pull") }
                                OutlinedButton(
                                    enabled = !agentBusy.value,
                                    onClick = { pendingGitAction.value = "push" },
                                    modifier = Modifier.weight(1f),
                                ) { Text("Push") }
                            }
                            if (agentGitStatus.value.isNotBlank()) {
                                Card(
                                    shape = RoundedCornerShape(12.dp),
                                    colors = CardDefaults.cardColors(containerColor = KiBackground),
                                ) {
                                    Column(
                                        modifier = Modifier.fillMaxWidth().padding(10.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        Text("Repository status", color = KiAccent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                        Text(agentGitStatus.value, color = KiText, fontSize = 10.sp)
                                        if (agentGitOutput.value.isNotBlank()) {
                                            Text(agentGitOutput.value, color = KiMuted, fontSize = 9.sp)
                                        }
                                    }
                                }
                            }
                        }
                        Text("Latest messages", color = KiText, fontWeight = FontWeight.SemiBold)
                        if (agentMessages.isEmpty()) {
                            Text("Tap Refresh to read this session's recent messages.", color = KiMuted, fontSize = 11.sp)
                        }
                        agentMessages.takeLast(8).forEach { message ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (message.role == "assistant") KiSurfaceRaised else KiBackground,
                                ),
                                border = BorderStroke(
                                    1.dp,
                                    if (message.role == "assistant") {
                                        KiAccent.copy(alpha = 0.12f)
                                    } else {
                                        Color.White.copy(alpha = 0.05f)
                                    },
                                ),
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxWidth().padding(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text(
                                        if (message.role == "assistant") "AGENT" else "YOU",
                                        color = if (message.role == "assistant") KiAccent else KiMuted,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 1.sp,
                                    )
                                    Text(message.content, color = KiText, fontSize = 11.sp)
                                }
                            }
                        }
                        val pending = agentPermissions.filter { it.sessionId == selected.id }
                        if (pending.isNotEmpty()) {
                            Text("Waiting for permission", color = KiText, fontWeight = FontWeight.SemiBold)
                            pending.forEach { permission ->
                                Card(
                                    shape = RoundedCornerShape(12.dp),
                                    colors = CardDefaults.cardColors(containerColor = KiSurfaceRaised),
                                    border = BorderStroke(1.dp, KiError.copy(alpha = 0.3f)),
                                ) {
                                    Column(
                                        Modifier.fillMaxWidth().padding(10.dp),
                                        verticalArrangement = Arrangement.spacedBy(5.dp),
                                    ) {
                                        Text(permission.title, color = KiText, fontSize = 12.sp)
                                        if (permission.patterns.isNotEmpty()) {
                                            Text(permission.patterns.joinToString("\n"), color = KiMuted, fontSize = 10.sp)
                                        }
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            TextButton(
                                                enabled = !agentBusy.value,
                                                onClick = { confirmAgentPermission.value = permission },
                                            ) { Text("Allow once", color = KiAccent) }
                                            TextButton(
                                                enabled = !agentBusy.value,
                                                onClick = {
                                                    sendAgentAction(
                                                        "permission",
                                                        selected.id,
                                                        permission = permission,
                                                        response = "deny",
                                                    )
                                                },
                                            ) { Text("Deny", color = KiError) }
                                        }
                                    }
                                }
                            }
                        }
                        OutlinedTextField(
                            value = agentInput.value,
                            onValueChange = { if (it.length <= 2_000) agentInput.value = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Reply or instruct this agent") },
                            minLines = 2,
                            maxLines = 4,
                            colors = kiTextFieldColors(),
                        )
                        Button(
                            enabled = !agentBusy.value && agentInput.value.isNotBlank(),
                            onClick = {
                                sendAgentAction("answer", selected.id, text = agentInput.value.trim())
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = KiAccent,
                                contentColor = Color(0xFF101417),
                            ),
                        ) { Text("Send to OpenCode") }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAgentPanel.value = false }) {
                    Text("Done", color = KiAccent)
                }
            },
        )
    }

    confirmAgentPermission.value?.let { permission ->
        AlertDialog(
            onDismissRequest = { confirmAgentPermission.value = null },
            containerColor = KiSurface,
            title = { Text("Allow this one request?", color = KiText, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(permission.title, color = KiText)
                    if (permission.patterns.isNotEmpty()) {
                        Text(permission.patterns.joinToString("\n"), color = KiMuted, fontSize = 11.sp)
                    }
                    Text("This is a one-time approval. Tot will not remember or automatically approve future requests.", color = KiMuted, fontSize = 11.sp)
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !agentBusy.value,
                    onClick = {
                        confirmAgentPermission.value = null
                        sendAgentAction(
                            "permission",
                            permission.sessionId,
                            permission = permission,
                            response = "allow_once",
                        )
                    },
                ) { Text("Allow once", color = KiAccent, fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = {
                TextButton(onClick = { confirmAgentPermission.value = null }) {
                    Text("Cancel", color = KiMuted)
                }
            },
        )
    }

    pendingGitAction.value?.let { operation ->
        val operationLabel = when (operation) {
            "stage_all" -> "Stage all changes"
            "commit" -> "Commit staged changes"
            "pull" -> "Pull updates"
            "push" -> "Push commits"
            else -> "Run Git action"
        }
        AlertDialog(
            onDismissRequest = { pendingGitAction.value = null },
            containerColor = KiSurface,
            title = { Text(operationLabel, color = KiText, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (operation == "commit") {
                        OutlinedTextField(
                            value = gitCommitMessage.value,
                            onValueChange = { if (it.length <= 200) gitCommitMessage.value = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Commit message") },
                            singleLine = true,
                            colors = kiTextFieldColors(),
                        )
                    }
                    Text(
                        when (operation) {
                            "stage_all" -> "Stage all non-ignored changes in this repository?"
                            "commit" -> "Commit only changes already staged in this repository?"
                            "pull" -> "Pull the current branch using fast-forward only? Pull is blocked if the repository has local changes."
                            "push" -> "Push the current branch to its configured upstream?"
                            else -> "Continue with this Git action?"
                        },
                        color = KiMuted,
                        fontSize = 12.sp,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !agentBusy.value &&
                        (operation != "commit" || gitCommitMessage.value.isNotBlank()),
                    onClick = {
                        val message = gitCommitMessage.value.trim()
                        pendingGitAction.value = null
                        runAgentGit(operation, message)
                    },
                ) { Text("Confirm", color = KiAccent, fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = {
                TextButton(onClick = { pendingGitAction.value = null }) {
                    Text("Cancel", color = KiMuted)
                }
            },
        )
    }

    if (showPairingHelp.value) {
        AlertDialog(
            onDismissRequest = { showPairingHelp.value = false },
            containerColor = KiSurface,
            title = { Text("Connect to Ki on your PC", color = KiText) },
            text = {
                Text(
                    "1. On the PC, open Ki → Dashboard → Device & activity → Phone companion.\n\n" +
                        "2. Tap Scan QR here and point your camera at the code shown by the PC dashboard. You can also enter the address and pairing token manually.\n\n" +
                            "3. Connect both devices to the same trusted Wi-Fi/hotspot, or install Tailscale on both and sign in to the same private tailnet. Allow Python through the Windows private-network firewall, then tap Test connection.\n\n" +
                            "USB file-transfer mode does not create a network connection. Do not open or forward Ki's port to the public internet. The Wi-Fi connection uses HTTP, so use only a trusted network; Tailscale encrypts traffic between your devices.",
                    color = KiMuted,
                )
            },
            confirmButton = {
                TextButton(onClick = { showPairingHelp.value = false }) {
                    Text("Got it", color = KiAccent)
                }
            },
        )
    }

    if (showSettings.value) {
        AlertDialog(
            onDismissRequest = { showSettings.value = false },
            containerColor = KiSurface,
            title = { Text("Settings", color = KiText, fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text("PC CONNECTION", color = KiAccent, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                    ConnectionCard(
                        host = host.value,
                        token = token.value,
                        onHostChange = {
                            host.value = it
                            preferences.edit().putString("host", it).apply()
                        },
                        onTokenChange = {
                            token.value = it
                            preferences.edit().putString("token", it).apply()
                        },
                        busy = busy.value,
                        onScan = {
                            qrScanner.launch(
                                ScanOptions()
                                    .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                    .setPrompt("Scan the QR code from Ki on your PC")
                                    .setBeepEnabled(false),
                            )
                        },
                        onTest = {
                            scope.launch {
                                busy.value = true
                                status.value = "Checking PC and Ollama…"
                                statusIsError.value = false
                                try {
                                    status.value = withContext(Dispatchers.IO) {
                                        checkConnection(host.value, token.value)
                                    }
                                } catch (error: Exception) {
                                    status.value = describeNetworkError(error)
                                    statusIsError.value = true
                                } finally {
                                    busy.value = false
                                }
                            }
                        },
                    )
                    Text("LOCAL CHAT MODEL", color = KiAccent, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                    Text(
                        "Tot uses free, locally installed Ollama models. Only small models are listed; Ki switches its selected model when needed.",
                        color = KiMuted,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                    )
                    Text("WHO'S WHO", color = KiAccent, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                    Text(
                        "Tot is your expressive Android companion: it lives on your phone, helps with chat, voice, images, and Termux shortcuts, and shares requests with your paired PC.\n\n" +
                            "Ki is your Windows assistant: it runs local models and PC features on your laptop. Tot does not independently control the PC; Ki phone access and Windows approvals stay in your hands.",
                        color = KiMuted,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(modifier = Modifier.weight(1f)) {
                            TextButton(
                                enabled = chatModels.isNotEmpty(),
                                onClick = { modelMenuExpanded.value = true },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    chatModels.firstOrNull {
                                        it.name.equals(selectedModel.value, ignoreCase = true)
                                    }?.let(::chatModelLabel) ?: selectedModel.value,
                                    color = KiText,
                                    maxLines = 1,
                                )
                            }
                            DropdownMenu(
                                expanded = modelMenuExpanded.value,
                                onDismissRequest = { modelMenuExpanded.value = false },
                            ) {
                                chatModels.forEach { model ->
                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Text(model.name, color = KiText, fontWeight = FontWeight.SemiBold)
                                                Text(
                                                    listOf(model.parameterSize, formatModelSize(model.sizeBytes))
                                                        .filter { it.isNotBlank() }
                                                        .joinToString(" · "),
                                                    color = KiMuted,
                                                    fontSize = 10.sp,
                                                )
                                            }
                                        },
                                        onClick = {
                                            selectedModel.value = model.name
                                            preferences.edit().putString("chat_model", model.name).apply()
                                            modelMenuExpanded.value = false
                                            status.value = "Tot will use ${model.name} for its next text reply."
                                            statusIsError.value = false
                                        },
                                    )
                                }
                            }
                        }
                        TextButton(
                            enabled = !modelLoading.value,
                            onClick = { refreshChatModels() },
                        ) {
                            if (modelLoading.value) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    color = KiAccent,
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Text("Refresh", color = KiAccent)
                            }
                        }
                    }
                    if (chatModels.isEmpty()) {
                        Text(
                            if (host.value.isBlank() || token.value.isBlank()) {
                                "Pair Tot with Ki first, then refresh to see models installed on the PC."
                            } else {
                                "No compatible model listed. On the PC, run `ollama pull qwen3:0.6b` or `ollama pull llama3.2:1b`, then refresh."
                            },
                            color = KiMuted,
                            fontSize = 10.sp,
                        )
                    }
                    Text("VOICE INPUT", color = KiAccent, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Talk to Tot", color = KiText, fontWeight = FontWeight.SemiBold)
                            Text(
                                "Uses Android's speech service. Audio may be processed by your phone's speech provider.",
                                color = KiMuted,
                                fontSize = 11.sp,
                            )
                        }
                        Switch(
                            checked = voiceInputEnabled.value,
                            onCheckedChange = {
                                voiceInputEnabled.value = it
                                preferences.edit().putBoolean("voice_input_enabled", it).apply()
                            },
                        )
                    }
                    Text("SPEAK REPLIES", color = KiAccent, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                    listOf(
                        "both" to "Phone and Windows PC",
                        "phone" to "Phone only",
                        "pc" to "Windows PC only",
                        "off" to "Text only",
                    ).forEach { (value, title) ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = voiceOutput.value == value,
                                onClick = {
                                    voiceOutput.value = value
                                    preferences.edit().putString("voice_output", value).apply()
                                },
                                colors = androidx.compose.material3.RadioButtonDefaults.colors(
                                    selectedColor = KiAccent,
                                ),
                            )
                            Text(title, color = KiText, fontSize = 14.sp)
                        }
                    }
                    Text(
                        "Text replies appear in this app and Ki's Windows chat. Spoken replies use Android text-to-speech or Ki's selected Windows voice.",
                        color = KiMuted,
                        fontSize = 11.sp,
                    )
                    TextButton(onClick = {
                        showSettings.value = false
                        showPairingHelp.value = true
                    }) { Text("Pairing help", color = KiAccent) }
                    TextButton(onClick = {
                        showSettings.value = false
                        showUpdateDialog.value = true
                    }) { Text("Check for app updates", color = KiAccent) }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSettings.value = false }) {
                    Text("Done", color = KiAccent)
                }
            },
        )
    }

    if (showUpdateDialog.value) {
        AlertDialog(
            onDismissRequest = { if (!updateBusy.value) showUpdateDialog.value = false },
            containerColor = KiSurface,
            title = { Text("Tot updates", color = KiText) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Installed version ${BuildConfig.VERSION_NAME}", color = KiMuted)
                    Text(updateStatus.value, color = if (statusIsError.value) KiError else KiText)
                    availableUpdate.value?.let { update ->
                        Text(
                            "Version ${update.versionName} is ready on GitHub.",
                            color = KiAccent,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (update.notes.isNotBlank()) {
                            Text(update.notes, color = KiMuted, fontSize = 12.sp, maxLines = 8)
                        }
                    }
                    Text(
                        "Ki downloads the APK from this project's GitHub Releases. Android will ask you to confirm installation.",
                        color = KiMuted,
                        fontSize = 12.sp,
                    )
                }
            },
            confirmButton = {
                when {
                    updateBusy.value -> TextButton(onClick = {}) {
                        Text("Please wait…", color = KiMuted)
                    }
                    downloadedUpdate.value != null -> TextButton(
                        onClick = {
                            val apk = downloadedUpdate.value ?: return@TextButton
                            if (context.packageManager.canRequestPackageInstalls()) {
                                openUpdateInstaller(context, apk) { updateStatus.value = it }
                            } else {
                                updateStatus.value = "Allow Tot to install unknown apps in Android settings."
                                installPermissionLauncher.launch(
                                    Intent(
                                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                        Uri.parse("package:${context.packageName}"),
                                    ),
                                )
                            }
                        },
                    ) { Text("Install update", color = KiAccent) }
                    availableUpdate.value != null -> TextButton(
                        onClick = {
                            val update = availableUpdate.value ?: return@TextButton
                            scope.launch {
                                updateBusy.value = true
                                updateStatus.value = "Downloading version ${update.versionName}…"
                                try {
                                    downloadedUpdate.value = withContext(Dispatchers.IO) {
                                        downloadAndVerifyUpdate(context, update)
                                    }
                                    updateStatus.value = "Download verified. Android will confirm the installation."
                                } catch (error: Exception) {
                                    updateStatus.value = error.message ?: "Could not download the update."
                                    statusIsError.value = true
                                } finally {
                                    updateBusy.value = false
                                }
                            }
                        },
                    ) { Text("Download update", color = KiAccent) }
                    else -> TextButton(
                        onClick = {
                            scope.launch {
                                updateBusy.value = true
                                availableUpdate.value = null
                                downloadedUpdate.value = null
                                statusIsError.value = false
                                updateStatus.value = "Checking GitHub Releases…"
                                try {
                                    when (val result = withContext(Dispatchers.IO) { checkForAppUpdate() }) {
                                        is UpdateCheckResult.Available -> {
                                            availableUpdate.value = result.update
                                            updateStatus.value = "A newer version is available."
                                        }
                                        UpdateCheckResult.UpToDate -> {
                                            updateStatus.value = "You have the latest released version."
                                        }
                                    }
                                } catch (error: Exception) {
                                    updateStatus.value = error.message ?: "Could not check GitHub for updates."
                                    statusIsError.value = true
                                } finally {
                                    updateBusy.value = false
                                }
                            }
                        },
                    ) { Text("Check for updates", color = KiAccent) }
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !updateBusy.value,
                    onClick = { showUpdateDialog.value = false },
                ) { Text("Close", color = KiMuted) }
            },
        )
    }
}

private fun openUpdateInstaller(context: Context, apk: java.io.File, onError: (String) -> Unit) {
    try {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(updateApkUri(context, apk), "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    } catch (error: ActivityNotFoundException) {
        onError("Android could not open the package installer.")
    } catch (error: SecurityException) {
        onError("Android blocked the installer. Allow Tot to install unknown apps and try again.")
    } catch (error: IllegalArgumentException) {
        onError("The update APK could not be opened. Check for updates and download it again.")
    }
}

@Composable
private fun TotMark(modifier: Modifier = Modifier) {
    val animation = rememberInfiniteTransition(label = "tot-face")
    val pulse = animation.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 850),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "tot-face-pulse",
    )
    Canvas(modifier = modifier) {
        val panelLeft = size.width * 0.06f
        val panelTop = size.height * 0.12f
        val panelWidth = size.width * 0.88f
        val panelHeight = size.height * 0.70f
        val armSwing = (pulse.value - 0.5f) * size.height * 0.10f
        val limbColor = KiAccent
        val limbWidth = size.minDimension * 0.055f
        val armY = panelTop + panelHeight * 0.63f
        val handRadius = size.minDimension * 0.05f
        drawLine(
            limbColor,
            Offset(panelLeft + panelWidth * 0.05f, armY),
            Offset(panelLeft - size.width * 0.035f, armY - armSwing),
            limbWidth,
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
        )
        drawLine(
            limbColor,
            Offset(panelLeft + panelWidth * 0.95f, armY),
            Offset(panelLeft + panelWidth + size.width * 0.035f, armY + armSwing),
            limbWidth,
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
        )
        drawCircle(limbColor, handRadius, Offset(panelLeft - size.width * 0.035f, armY - armSwing))
        drawCircle(limbColor, handRadius, Offset(panelLeft + panelWidth + size.width * 0.035f, armY + armSwing))
        val legTop = panelTop + panelHeight * 0.88f
        val legBottom = size.height * 0.91f
        val legOffset = panelWidth * 0.20f
        drawLine(limbColor, Offset(size.width * 0.5f - legOffset, legTop), Offset(size.width * 0.5f - legOffset - armSwing * 0.18f, legBottom), limbWidth, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        drawLine(limbColor, Offset(size.width * 0.5f + legOffset, legTop), Offset(size.width * 0.5f + legOffset + armSwing * 0.18f, legBottom), limbWidth, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        drawLine(limbColor, Offset(size.width * 0.5f - legOffset - armSwing * 0.18f - handRadius, legBottom), Offset(size.width * 0.5f - legOffset - armSwing * 0.18f + handRadius, legBottom), limbWidth, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        drawLine(limbColor, Offset(size.width * 0.5f + legOffset + armSwing * 0.18f - handRadius, legBottom), Offset(size.width * 0.5f + legOffset + armSwing * 0.18f + handRadius, legBottom), limbWidth, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        drawRoundRect(
            color = Color(0xFF303236),
            topLeft = Offset(panelLeft, panelTop),
            size = Size(panelWidth, panelHeight),
            cornerRadius = CornerRadius(size.minDimension * 0.035f),
        )
        val eyeY = panelTop + panelHeight * 0.43f
        val eyeRadius = size.minDimension * 0.045f
        val blink = pulse.value > 0.94f
        if (blink) {
            drawLine(Color(0xFFF0F0F0), Offset(panelLeft + panelWidth * 0.30f - eyeRadius, eyeY), Offset(panelLeft + panelWidth * 0.30f + eyeRadius, eyeY), eyeRadius * 0.45f)
            drawLine(Color(0xFFF0F0F0), Offset(panelLeft + panelWidth * 0.70f - eyeRadius, eyeY), Offset(panelLeft + panelWidth * 0.70f + eyeRadius, eyeY), eyeRadius * 0.45f)
        } else {
            drawCircle(Color(0xFFF0F0F0), eyeRadius, Offset(panelLeft + panelWidth * 0.30f, eyeY))
            drawCircle(Color(0xFFF0F0F0), eyeRadius, Offset(panelLeft + panelWidth * 0.70f, eyeY))
        }
        val mouthY = panelTop + panelHeight * 0.66f
        drawLine(
            color = Color(0xFFF0F0F0),
            start = Offset(panelLeft + panelWidth * 0.39f, mouthY),
            end = Offset(panelLeft + panelWidth * 0.61f, mouthY),
            strokeWidth = size.minDimension * 0.035f,
        )
        val ringCenter = Offset(size.width * 0.50f, size.height * 0.93f)
        drawCircle(
            color = Color(0xFFE0A64B).copy(alpha = 0.25f + pulse.value * 0.30f),
            radius = size.minDimension * (0.105f + pulse.value * 0.025f),
            center = ringCenter,
        )
        drawCircle(
            color = Color(0xFFE0A64B),
            radius = size.minDimension * 0.075f,
            center = ringCenter,
            style = Stroke(width = size.minDimension * 0.028f),
        )
    }
}

@Composable
private fun TotWakeCard(
    isAwake: Boolean,
    canDisplayOverApps: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = KiSurface),
        border = BorderStroke(1.dp, KiAccent.copy(alpha = if (isAwake) 0.3f else 0.12f)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(13.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TotMark(Modifier.size(48.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    if (isAwake) "Tot is with you" else "Tot is taking a nap",
                    color = KiText,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    when {
                        isAwake -> "Floating above your apps · tap to tuck him away"
                        canDisplayOverApps -> "Bring your little buddy over other apps"
                        else -> "Allow overlay access to wake Tot over other apps"
                    },
                    color = KiMuted,
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                )
            }
            Button(
                onClick = onClick,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isAwake) KiSurfaceRaised else KiAccent,
                    contentColor = if (isAwake) KiText else Color(0xFF101417),
                ),
            ) {
                Text(
                    if (isAwake) "Let rest" else "Wake Tot",
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

@Composable
private fun ConnectionStatusCard(status: String, isError: Boolean, isThinking: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = KiSurface),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(8.dp).clip(CircleShape)
                    .background(if (isError) KiError else KiAccent),
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("KI · WINDOWS PC", color = KiAccent, fontSize = 9.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.Bold)
                Text(
                    if (isThinking) "Ki is thinking…" else status,
                    color = if (isError) KiError else KiText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                )
            }
        }
    }
}

@Composable
private fun ConnectionCard(
    host: String,
    token: String,
    onHostChange: (String) -> Unit,
    onTokenChange: (String) -> Unit,
    busy: Boolean,
    onScan: () -> Unit,
    onTest: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = KiSurface),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
    ) {
        Column(
            modifier = Modifier.padding(13.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("PC connection", color = KiText, fontWeight = FontWeight.SemiBold)
                Text("Private network", color = KiAccent, fontSize = 11.sp)
            }
            Button(
                onClick = onScan,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(13.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = KiAccent,
                    contentColor = Color(0xFF101417),
                ),
            ) {
                Text("Scan PC pairing QR")
            }
            OutlinedTextField(
                value = host,
                onValueChange = onHostChange,
                label = { Text("PC address  ·  http://192.168.1.20:8765") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(13.dp),
                colors = kiTextFieldColors(),
            )
            OutlinedTextField(
                value = token,
                onValueChange = onTokenChange,
                label = { Text("Pairing token") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(13.dp),
                colors = kiTextFieldColors(),
            )
            Button(
                enabled = !busy && host.isNotBlank() && token.isNotBlank(),
                onClick = onTest,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(13.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = KiAccent,
                    contentColor = Color(0xFF101417),
                    disabledContainerColor = KiSurfaceRaised,
                    disabledContentColor = KiMuted,
                ),
            ) {
                Text(if (busy) "Connecting…" else "Test connection")
            }
        }
    }
}

@Composable
private fun EmptyChatCard(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(KiSurface)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TotMark(Modifier.size(62.dp))
        Text("A little company, wherever you go.", color = KiText, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "Connect to Ki on your PC, then send a message to start chatting.",
            color = KiMuted,
            fontSize = 13.sp,
            lineHeight = 19.sp,
        )
    }
}

@Composable
private fun MessageBubble(
    message: ChatMessage,
    onEditVoice: () -> Unit,
    onRetryVoice: () -> Unit,
    onDeleteVoice: () -> Unit,
) {
    val isUser = message.role == "user"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = Modifier
                .widthInCompat()
                .clip(RoundedCornerShape(17.dp))
                .background(if (isUser) Color(0xFF34434B) else KiSurfaceRaised),
        ) {
            Column(modifier = Modifier.padding(horizontal = 13.dp, vertical = 10.dp)) {
                Text(
                    if (isUser) "YOU" else "KI",
                    color = if (isUser) KiAccent else KiMuted,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                )
                Spacer(Modifier.height(3.dp))
                Text(message.content, color = KiText, fontSize = 14.sp)
            }
            if (message.voicePrompt) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, bottom = 2.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onEditVoice) { Text("Edit", color = KiAccent, fontSize = 11.sp) }
                    TextButton(onClick = onRetryVoice) { Text("Retry", color = KiAccent, fontSize = 11.sp) }
                    TextButton(onClick = onDeleteVoice) { Text("Delete", color = KiMuted, fontSize = 11.sp) }
                }
            }
        }
    }
}

private fun Modifier.widthInCompat(): Modifier = this.then(
    Modifier.fillMaxWidth(0.88f),
)

@Composable
private fun kiTextFieldColors() = androidx.compose.material3.TextFieldDefaults.colors(
    focusedTextColor = KiText,
    unfocusedTextColor = KiText,
    focusedContainerColor = KiSurfaceRaised,
    unfocusedContainerColor = KiSurfaceRaised,
    focusedIndicatorColor = KiAccent,
    unfocusedIndicatorColor = Color(0xFF454D55),
    cursorColor = KiAccent,
    focusedLabelColor = KiAccent,
    unfocusedLabelColor = KiMuted,
)

private fun parsePairingCode(contents: String): Pair<String, String> {
    if (contents.length > 2_048) throw IOException("That QR code is too large to be a Ki pairing code.")
    val pairing = try {
        JSONObject(contents)
    } catch (error: JSONException) {
        throw IOException("That QR code is not a Ki pairing code.", error)
    }
    val host = pairing.optString("host").trim().trimEnd('/')
    val token = pairing.optString("token").trim()
    val uri = try {
        URI(host)
    } catch (error: URISyntaxException) {
        throw IOException("The QR code contains an invalid PC address.", error)
    }
    if (uri.scheme != "http" || uri.port != 8765 || uri.rawUserInfo != null ||
        uri.rawQuery != null || uri.rawFragment != null || uri.rawPath !in listOf("", "/")
    ) {
        throw IOException("Use a Ki pairing QR code for the local PC bridge.")
    }
    if (!isAllowedPrivateBridgeAddress(uri.host.orEmpty())) {
        throw IOException("The QR code must point to a private local-network or Tailscale address.")
    }
    if (token.isBlank() || token.length > 256) throw IOException("The QR code does not contain a valid pairing token.")
    return host to token
}

private fun parseSshQr(contents: String): SshConnectionInfo {
    if (contents.length > 2_048) throw IOException("That QR code is too large to be an SSH connection code.")
    val payload = try {
        JSONObject(contents)
    } catch (error: JSONException) {
        throw IOException("That QR code is not a Ki SSH connection code.", error)
    }
    if (
        payload.length() != 4 ||
        !payload.has("type") ||
        !payload.has("host") ||
        !payload.has("username") ||
        !payload.has("port")
    ) {
        throw IOException("The SSH QR must contain only a host, username, port, and Ki SSH code marker.")
    }
    val host = payload.optString("host").trim()
    val username = payload.optString("username").trim()
    val rawPort = payload.opt("port")
    val port = when (rawPort) {
        is Int -> rawPort
        is Long -> rawPort.takeIf { it in 1L..65535L }?.toInt()
        else -> null
    }
    if (payload.optString("type") != "ki-ssh") {
        throw IOException("Scan the separate SSH connection QR shown in Ki's PC dialog.")
    }
    if (!isAllowedPrivateBridgeAddress(host)) {
        throw IOException("The SSH QR must use a private LAN or Tailscale IPv4 address.")
    }
    if (!username.matches(Regex("[A-Za-z0-9._-]{1,64}"))) {
        throw IOException("The SSH QR contains an invalid Windows username.")
    }
    if (port == null || port !in 1..65535) {
        throw IOException("The SSH QR contains an invalid port.")
    }
    return SshConnectionInfo(host, username, port)
}

internal fun normalizeHost(value: String): String {
    val host = value.trim().trimEnd('/')
    if (host.isEmpty()) throw IOException("Enter the PC address shown in Ki's dashboard.")
    if (!host.startsWith("http://")) throw IOException("Enter the full http:// address shown in Ki's dashboard.")
    val uri = try {
        URI(host)
    } catch (error: URISyntaxException) {
        throw IOException("Enter a valid PC address shown in Ki's dashboard.", error)
    }
    if (uri.port != 8765 || uri.rawUserInfo != null || uri.rawQuery != null ||
        uri.rawFragment != null || uri.rawPath !in listOf("", "/") ||
        !isAllowedPrivateBridgeAddress(uri.host.orEmpty())
    ) {
        throw IOException("Use the private local-network or Tailscale address shown in Ki's dashboard.")
    }
    return host
}

private fun isAllowedPrivateBridgeAddress(host: String): Boolean {
    val octets = host.split('.')
    if (octets.size != 4) return false
    val address = octets.map { it.toIntOrNull()?.takeIf { part -> part in 0..255 } ?: return false }
    val tailscaleAddress = address[0] == 100 && address[1] in 64..127
    return tailscaleAddress || address[0] == 10 ||
        (address[0] == 172 && address[1] in 16..31) ||
        (address[0] == 192 && address[1] == 168) ||
        (address[0] == 169 && address[1] == 254)
}

private fun checkConnection(hostValue: String, token: String): String {
    val connection = openConnection("${normalizeHost(hostValue)}/api/status", token, "GET")
    try {
        val code = connection.responseCode
        val body = readResponse(connection, code)
        val response = JSONObject(body)
        if (code !in 200..299) throw IOException(response.optString("error", "Connection failed (HTTP $code)."))
        if (!response.optBoolean("ollama_available", true)) {
            throw IOException(response.optString("error", "The PC bridge is reachable, but Ollama is not running."))
        }
        if (!response.optBoolean("model_available", true)) {
            throw IOException(response.optString("error", "The PC is missing a compatible local chat model."))
        }
        return "Connected to Ki and Ollama."
    } finally {
        connection.disconnect()
    }
}

private fun sendChat(
    hostValue: String,
    token: String,
    messages: List<ChatMessage>,
    attachment: TextAttachment?,
    model: String,
    speakOnPc: Boolean,
): String {
    val body = JSONObject().apply {
        put("model", model)
        put("speak_on_pc", speakOnPc)
        put("messages", JSONArray().also { array ->
            messages.takeLast(20).forEach { message ->
                array.put(JSONObject().put("role", message.role).put("content", message.content))
            }
        })
        if (attachment != null) {
            put(
                "attachment",
                JSONObject()
                    .put("name", attachment.name)
                    .put("content_base64", attachment.contentBase64)
                    .put("kind", if (attachment.isImage) "image" else "text"),
            )
        }
    }
    val connection = openConnection("${normalizeHost(hostValue)}/api/chat", token, "POST")
    try {
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = connection.responseCode
        val responseText = readResponse(connection, code)
        val response = JSONObject(responseText)
        if (code !in 200..299) throw IOException(response.optString("error", "Ki could not answer (HTTP $code)."))
        return response.optString("answer").takeIf { it.isNotBlank() }
            ?: throw IOException("Ki returned an empty reply.")
    } finally {
        connection.disconnect()
    }
}

private fun fetchChatModels(hostValue: String, token: String): List<ChatModel> {
    val connection = openConnection("${normalizeHost(hostValue)}/api/models", token, "GET")
    try {
        val code = connection.responseCode
        val responseText = readResponse(connection, code)
        val response = JSONObject(responseText)
        if (code !in 200..299) {
            throw IOException(response.optString("error", "Could not read local Ollama models (HTTP $code)."))
        }
        val modelsJson = response.optJSONArray("models") ?: JSONArray()
        return (0 until modelsJson.length()).mapNotNull { index ->
            val item = modelsJson.optJSONObject(index) ?: return@mapNotNull null
            val name = item.optString("name")
            if (name.isBlank()) return@mapNotNull null
            ChatModel(
                name = name,
                parameterSize = item.optString("parameter_size"),
                sizeBytes = item.optLong("size_bytes"),
            )
        }
    } finally {
        connection.disconnect()
    }
}

private fun fetchVscodeContext(hostValue: String, token: String): String {
    val connection = openConnection("${normalizeHost(hostValue)}/api/vscode/context", token, "GET")
    try {
        val code = connection.responseCode
        val responseText = readResponse(connection, code)
        val response = JSONObject(responseText)
        if (code !in 200..299) {
            throw IOException(response.optString("error", "Could not read the copied VS Code selection (HTTP $code)."))
        }
        return response.optString("text").takeIf { it.isNotBlank() }
            ?: throw IOException("Ki returned an empty VS Code selection.")
    } finally {
        connection.disconnect()
    }
}

private fun chatModelLabel(model: ChatModel): String = listOf(
    model.name,
    model.parameterSize,
    formatModelSize(model.sizeBytes),
).filter { it.isNotBlank() }.joinToString(" · ")

private fun formatModelSize(sizeBytes: Long): String = when {
    sizeBytes <= 0 -> ""
    sizeBytes >= 1024L * 1024 * 1024 -> String.format(Locale.ROOT, "%.1f GB", sizeBytes / (1024.0 * 1024 * 1024))
    else -> "${(sizeBytes / (1024 * 1024)).coerceAtLeast(1)} MB"
}

private fun fetchAgentSnapshot(hostValue: String, token: String): Triple<List<AgentSession>, List<AgentPermission>, List<AgentEvent>> {
    val connection = openConnection("${normalizeHost(hostValue)}/api/agents", token, "GET")
    try {
        val code = connection.responseCode
        val responseText = readResponse(connection, code)
        val response = JSONObject(responseText)
        if (code !in 200..299) throw IOException(response.optString("error", "Could not read OpenCode sessions (HTTP $code)."))
        val sessionsJson = response.optJSONArray("sessions") ?: JSONArray()
        val sessions = (0 until sessionsJson.length()).mapNotNull { index ->
            val item = sessionsJson.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("id")
            if (id.isBlank()) return@mapNotNull null
            AgentSession(
                id = id,
                title = item.optString("title"),
                project = item.optString("project"),
                status = item.optString("status"),
                gitAvailable = item.optBoolean("git_available"),
            )
        }
        val permissionsJson = response.optJSONArray("permissions") ?: JSONArray()
        val permissions = (0 until permissionsJson.length()).mapNotNull { index ->
            val item = permissionsJson.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("id")
            val sessionId = item.optString("session_id")
            if (id.isBlank() || sessionId.isBlank()) return@mapNotNull null
            val patternsJson = item.optJSONArray("patterns") ?: JSONArray()
            AgentPermission(
                id = id,
                sessionId = sessionId,
                title = item.optString("title", "OpenCode permission request"),
                type = item.optString("type"),
                patterns = (0 until patternsJson.length())
                    .mapNotNull { patternIndex -> patternsJson.optString(patternIndex).takeIf(String::isNotBlank) }
                    .take(12),
            )
        }
        val eventsJson = response.optJSONArray("events") ?: JSONArray()
        val events = (0 until eventsJson.length()).mapNotNull { index ->
            val item = eventsJson.optJSONObject(index) ?: return@mapNotNull null
            val sessionId = item.optString("session_id")
            if (sessionId.isBlank()) return@mapNotNull null
            val permission = item.optJSONObject("permission")
            AgentEvent(
                type = item.optString("type"),
                sessionId = sessionId,
                project = item.optString("project_name"),
                tool = item.optString("tool"),
                permissionTitle = permission?.optString("title").orEmpty(),
                time = (item.optDouble("time") * 1000).toLong(),
            )
        }
        return Triple(sessions, permissions, events)
    } finally {
        connection.disconnect()
    }
}

private fun fetchAgentMessages(hostValue: String, token: String, sessionId: String): List<AgentMessage> {
    if (!sessionId.matches(Regex("[A-Za-z0-9_-]{1,200}"))) {
        throw IOException("That OpenCode session ID is invalid.")
    }
    val connection = openConnection(
        "${normalizeHost(hostValue)}/api/agents/${Uri.encode(sessionId)}/messages",
        token,
        "GET",
    )
    try {
        val code = connection.responseCode
        val responseText = readResponse(connection, code)
        val response = JSONObject(responseText)
        if (code !in 200..299) throw IOException(response.optString("error", "Could not read OpenCode messages (HTTP $code)."))
        val messagesJson = response.optJSONArray("messages") ?: JSONArray()
        return (0 until messagesJson.length()).mapNotNull { index ->
            val item = messagesJson.optJSONObject(index) ?: return@mapNotNull null
            val content = item.optString("content").take(6_000)
            if (content.isBlank()) return@mapNotNull null
            AgentMessage(
                role = if (item.optString("role") == "assistant") "assistant" else "user",
                content = content,
            )
        }.takeLast(20)
    } finally {
        connection.disconnect()
    }
}

private fun sendAgentAction(
    hostValue: String,
    token: String,
    action: String,
    sessionId: String,
    text: String,
    permissionId: String,
    response: String,
) {
    val body = JSONObject().apply {
        put("action", action)
        put("session_id", sessionId)
        if (text.isNotBlank()) put("text", text)
        if (permissionId.isNotBlank()) put("permission_id", permissionId)
        if (response.isNotBlank()) put("response", response)
    }
    val connection = openConnection("${normalizeHost(hostValue)}/api/agents/action", token, "POST")
    try {
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = connection.responseCode
        val responseText = readResponse(connection, code)
        val responseJson = JSONObject(responseText)
        if (code !in 200..299) {
            throw IOException(responseJson.optString("error", "OpenCode action failed (HTTP $code)."))
        }
    } finally {
        connection.disconnect()
    }
}

private fun sendAgentGitAction(
    hostValue: String,
    token: String,
    sessionId: String,
    operation: String,
    commitMessage: String,
): AgentGitResult {
    val body = JSONObject().apply {
        put("session_id", sessionId)
        put("operation", operation)
        if (commitMessage.isNotBlank()) put("commit_message", commitMessage)
    }
    val connection = openConnection("${normalizeHost(hostValue)}/api/agents/git", token, "POST")
    try {
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = connection.responseCode
        val responseText = readResponse(connection, code)
        val response = JSONObject(responseText)
        if (code !in 200..299) {
            throw IOException(response.optString("error", "Git operation failed (HTTP $code)."))
        }
        return AgentGitResult(
            status = response.optString("status"),
            output = response.optString("output"),
        )
    } finally {
        connection.disconnect()
    }
}

internal fun readResponse(connection: HttpURLConnection, status: Int): String {
    val stream = if (status in 200..299) connection.inputStream else connection.errorStream
    return stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
        ?: throw IOException("Ki returned an empty response (HTTP $status).")
}

internal fun openConnection(address: String, token: String, method: String): HttpURLConnection {
    val connection = URL(address).openConnection(Proxy.NO_PROXY) as HttpURLConnection
    connection.requestMethod = method
    connection.connectTimeout = 8_000
    connection.readTimeout = 300_000
    connection.setRequestProperty("Authorization", "Bearer $token")
    connection.setRequestProperty("Accept", "application/json")
    return connection
}

private fun describeNetworkError(error: Exception): String {
    var current: Throwable? = error
    while (current != null) {
        when (current) {
            is UnknownHostException -> return "Can't find that PC address. Check the IP shown in Ki's dashboard."
            is ConnectException -> return "Can't reach the PC. Check the Wi-Fi, Ki's Phone companion switch, and Windows private-network firewall."
            is SocketTimeoutException -> return "Ki timed out. Check the connection; if chat was generating, wait and try again."
        }
        current = current.cause
    }
    return error.message?.takeIf { it.isNotBlank() } ?: "Could not connect. Check the PC address and pairing token."
}

private suspend fun readAttachment(context: Context, uri: Uri): TextAttachment = withContext(Dispatchers.IO) {
    val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: "attachment.txt"
    val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    val imageAttachment = extension in setOf("jpg", "jpeg", "png", "webp") ||
        context.contentResolver.getType(uri)?.startsWith("image/") == true
    if (imageAttachment) {
        val sourceBytes = context.contentResolver.openInputStream(uri)?.use { stream ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8_192)
            var total = 0
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                total += count
                if (total > 20_000_000) throw IOException("Choose an image smaller than 20 MB.")
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } ?: throw IOException("Could not open the selected image.")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(sourceBytes, 0, sourceBytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 ||
            bounds.outWidth.toLong() * bounds.outHeight.toLong() > 50_000_000L
        ) {
            throw IOException("That image is too large or could not be read.")
        }
        var sampleSize = 1
        while (maxOf(bounds.outWidth / sampleSize, bounds.outHeight / sampleSize) > 1280) {
            sampleSize *= 2
        }
        val bitmap = BitmapFactory.decodeByteArray(
            sourceBytes,
            0,
            sourceBytes.size,
            BitmapFactory.Options().apply { inSampleSize = sampleSize },
        ) ?: throw IOException("Android could not resize that image.")
        try {
            val output = java.io.ByteArrayOutputStream()
            var quality = 82
            do {
                output.reset()
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)
                quality -= 10
            } while (output.size() > 1_500_000 && quality >= 42)
            if (output.size() > 1_500_000) {
                throw IOException("That image is still too large after resizing. Choose a smaller image.")
            }
            return@withContext TextAttachment(
                name = if (extension in setOf("jpg", "jpeg", "png", "webp")) name else "tot-image.jpg",
                contentBase64 = Base64.getEncoder().encodeToString(output.toByteArray()),
                isImage = true,
            )
        } finally {
            bitmap.recycle()
        }
    }
    val bytes = context.contentResolver.openInputStream(uri)?.use { stream ->
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8_192)
        var total = 0
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            total += count
            if (total > 512_000) throw IOException("Choose a text file no larger than 512 KB.")
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    } ?: throw IOException("Could not open the selected file.")
    val text = bytes.toString(Charsets.UTF_8)
    if (text.contains('\u0000')) throw IOException("Only UTF-8 text files are supported.")
    TextAttachment(name, Base64.getEncoder().encodeToString(bytes))
}

private suspend fun recognizeImageText(attachment: TextAttachment): String {
    if (!attachment.isImage) throw IOException("Choose an image before reading text.")
    val imageBytes = try {
        Base64.getDecoder().decode(attachment.contentBase64)
    } catch (error: IllegalArgumentException) {
        throw IOException("The selected image could not be decoded.", error)
    }
    val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
        ?: throw IOException("Tot could not open this image for text recognition.")
    val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    return try {
        recognizer.process(InputImage.fromBitmap(bitmap, 0)).await().text.trim()
    } finally {
        recognizer.close()
        bitmap.recycle()
    }
}
