package com.ki.companion

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.provider.OpenableColumns
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
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
import kotlin.random.Random

private val KiBackground = Color(0xFF17191C)
private val KiSurface = Color(0xD923282D)
private val KiSurfaceRaised = Color(0xE52A3036)
private val KiAccent = Color(0xFF8FE8FF)
private val KiAccentSoft = Color(0xFFB9F2FF)
private val KiText = Color(0xFFF2F4F6)
private val KiMuted = Color(0xFFA8B0B9)
private val KiError = Color(0xFFFF9D94)

private data class ChatMessage(val role: String, val content: String)
private data class TextAttachment(val name: String, val contentBase64: String)

class MainActivity : ComponentActivity() {
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
        mutableStateOf(if (host.value.isBlank()) "Set up a connection to your PC" else "Ready to connect to Ki")
    }
    val statusIsError = remember { mutableStateOf(false) }
    val busy = remember { mutableStateOf(false) }
    val updateBusy = remember { mutableStateOf(false) }
    val showUpdateDialog = remember { mutableStateOf(false) }
    val updateStatus = remember { mutableStateOf("Check GitHub for the latest Ki Companion release.") }
    val availableUpdate = remember { mutableStateOf<AppUpdate?>(null) }
    val downloadedUpdate = remember { mutableStateOf<java.io.File?>(null) }
    val showSettings = remember { mutableStateOf(false) }
    val showPairingHelp = remember { mutableStateOf(false) }
    val voiceInputEnabled = remember { mutableStateOf(preferences.getBoolean("voice_input_enabled", true)) }
    val voiceOutput = remember { mutableStateOf(preferences.getString("voice_output", "both") ?: "both") }
    val ttsReady = remember { mutableStateOf(false) }
    val attachment = remember { mutableStateOf<TextAttachment?>(null) }
    val messages = remember { mutableStateListOf<ChatMessage>() }
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
        onDispose {
            phoneTts.stop()
            phoneTts.shutdown()
        }
    }

    fun submitPrompt(prompt: String, selectedAttachment: TextAttachment? = null) {
        if (prompt.isBlank() || busy.value) return
        val chatMessages = messages.toList() + ChatMessage("user", prompt)
        val selectedOutput = voiceOutput.value
        input.value = ""
        attachment.value = null
        messages.add(ChatMessage("user", prompt))
        status.value = "Ki is thinking…"
        statusIsError.value = false
        busy.value = true
        scope.launch {
            try {
                val answer = withContext(Dispatchers.IO) {
                    sendChat(
                        host.value,
                        token.value,
                        chatMessages,
                        selectedAttachment,
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
            }
        }
    }

    val speechRecognitionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spokenText = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                .orEmpty()
            if (spokenText.isNotBlank()) {
                input.value = spokenText
                status.value = "Heard you. Sending to Ki…"
                statusIsError.value = false
                submitPrompt(spokenText, attachment.value)
            } else {
                status.value = "I didn't catch that. Tap the microphone and try again."
                statusIsError.value = false
            }
        }
    }

    fun startSpeechRecognition() {
        if (!voiceInputEnabled.value || busy.value) return
        try {
            speechRecognitionLauncher.launch(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                    putExtra(RecognizerIntent.EXTRA_PROMPT, "Talk to Ki")
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                },
            )
        } catch (error: ActivityNotFoundException) {
            status.value = "Speech recognition is unavailable on this phone. Install or enable a speech service."
            statusIsError.value = true
        } catch (error: SecurityException) {
            status.value = "Android did not allow microphone access."
            statusIsError.value = true
        }
    }

    val microphonePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) startSpeechRecognition()
        else {
            status.value = "Allow microphone access in Android settings to talk to Ki."
            statusIsError.value = true
        }
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

    val installPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        val apk = downloadedUpdate.value
        if (apk != null && context.packageManager.canRequestPackageInstalls()) {
            openUpdateInstaller(context, apk) { updateStatus.value = it }
        } else if (apk != null) {
            updateStatus.value = "Allow Ki Companion to install unknown apps, then tap Install update again."
        }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    attachment.value = readTextAttachment(context, uri)
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
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("Your little buddy", color = KiText, fontSize = 23.sp, fontWeight = FontWeight.Bold)
            }
            TextButton(onClick = { showSettings.value = true }) {
                Text("Settings", color = KiAccent, fontWeight = FontWeight.SemiBold)
            }
        }

        KiTravelCard(
            status = status.value,
            isError = statusIsError.value,
            isThinking = busy.value,
            onClick = {
                status.value = if (busy.value) "Ki is thinking about your message…" else "I'm here with you."
                statusIsError.value = false
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
                    MessageBubble(message)
                }
            }
        }

        if (attachment.value != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(KiSurfaceRaised)
                    .padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "📎  ${attachment.value?.name}",
                    color = KiText,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
                TextButton(onClick = { attachment.value = null }) {
                    Text("Remove", color = KiMuted)
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
                    enabled = !busy.value,
                    onClick = {
                        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                            android.content.pm.PackageManager.PERMISSION_GRANTED
                        ) {
                            startSpeechRecognition()
                        } else {
                            microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                ) {
                    Text("Mic", color = KiAccent, fontWeight = FontWeight.SemiBold)
                }
            }
            OutlinedTextField(
                value = input.value,
                onValueChange = { input.value = it },
                placeholder = { Text("Message Ki…", color = KiMuted) },
                modifier = Modifier.weight(1f),
                maxLines = 4,
                shape = RoundedCornerShape(20.dp),
                colors = kiTextFieldColors(),
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                TextButton(
                    enabled = !busy.value,
                    onClick = { filePicker.launch(arrayOf("text/*", "application/json")) },
                ) { Text("＋", color = KiAccent, fontSize = 23.sp) }
                Button(
                    enabled = !busy.value && input.value.isNotBlank(),
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
            "Your messages go to Ki on your PC over your local network.",
            color = KiMuted,
            fontSize = 11.sp,
            modifier = Modifier.align(Alignment.CenterHorizontally),
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
                        "3. Put both devices on the same Wi-Fi/hotspot, allow Python through the Windows private-network firewall, then tap Test connection.\n\n" +
                        "USB file-transfer mode does not create a network connection. The local chat connection is not encrypted; use a trusted network.",
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
                    Text("VOICE INPUT", color = KiAccent, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Talk to Ki", color = KiText, fontWeight = FontWeight.SemiBold)
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
            title = { Text("Ki Companion updates", color = KiText) },
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
                                updateStatus.value = "Allow Ki Companion to install unknown apps in Android settings."
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
        onError("Android blocked the installer. Allow Ki Companion to install unknown apps and try again.")
    } catch (error: IllegalArgumentException) {
        onError("The update APK could not be opened. Check for updates and download it again.")
    }
}

@Composable
private fun KiTravelCard(status: String, isError: Boolean, isThinking: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(116.dp),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = KiSurface),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val travel = remember { Animatable(0.02f) }
            val bob = rememberInfiniteTransition(label = "ki-bob")
                .animateFloat(
                    initialValue = -2f,
                    targetValue = 2f,
                    animationSpec = infiniteRepeatable(tween(420), RepeatMode.Reverse),
                    label = "bob",
                )
            LaunchedEffect(Unit) {
                while (true) {
                    delay(Random.nextLong(500, 1400))
                    travel.animateTo(
                        targetValue = Random.nextFloat() * 0.68f + 0.24f,
                        animationSpec = tween(
                            durationMillis = Random.nextInt(1500, 2800),
                            easing = FastOutSlowInEasing,
                        ),
                    )
                    delay(Random.nextLong(350, 900))
                    travel.animateTo(
                        targetValue = Random.nextFloat() * 0.68f + 0.02f,
                        animationSpec = tween(
                            durationMillis = Random.nextInt(1500, 2800),
                            easing = FastOutSlowInEasing,
                        ),
                    )
                }
            }
            Canvas(modifier = Modifier.fillMaxSize()) {
                val unit = density
                val faceWidth = 36f * unit
                val faceHeight = 40f * unit
                val x = size.width - faceWidth - 20f * unit + (travel.value - 0.5f) * 12f * unit
                val baseY = size.height - faceHeight - 24f * unit + bob.value * unit
                drawRoundRect(
                    color = KiAccent.copy(alpha = 0.12f),
                    topLeft = androidx.compose.ui.geometry.Offset(x - 6f * unit, baseY - 6f * unit),
                    size = androidx.compose.ui.geometry.Size(faceWidth + 12f * unit, faceHeight + 12f * unit),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(15f * unit),
                )
                drawRoundRect(
                    color = Color(0xFF30383F),
                    topLeft = androidx.compose.ui.geometry.Offset(x, baseY),
                    size = androidx.compose.ui.geometry.Size(faceWidth, faceHeight),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(13f * unit),
                )
                drawRoundRect(
                    color = Color(0xFF30383F),
                    topLeft = androidx.compose.ui.geometry.Offset(x + 6f * unit, baseY - 9f * unit),
                    size = androidx.compose.ui.geometry.Size(4f * unit, 10f * unit),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f * unit),
                )
                drawCircle(
                    color = KiAccent,
                    radius = 2.5f * unit,
                    center = androidx.compose.ui.geometry.Offset(x + 8f * unit, baseY - 10f * unit),
                )
                drawRoundRect(
                    color = KiAccentSoft,
                    topLeft = androidx.compose.ui.geometry.Offset(x + 4f * unit, baseY + 5f * unit),
                    size = androidx.compose.ui.geometry.Size(faceWidth - 8f * unit, faceHeight - 13f * unit),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(10f * unit),
                )
                val eyesY = baseY + 18f * unit
                drawCircle(Color(0xFF17242C), radius = 2.3f * unit, center = androidx.compose.ui.geometry.Offset(x + 13f * unit, eyesY))
                drawCircle(Color(0xFF17242C), radius = 2.3f * unit, center = androidx.compose.ui.geometry.Offset(x + 23f * unit, eyesY))
                val mouth = Path().apply {
                    moveTo(x + 15f * unit, baseY + 27f * unit)
                    quadraticTo(x + 18f * unit, baseY + 30f * unit, x + 21f * unit, baseY + 27f * unit)
                }
                drawPath(mouth, color = Color(0xFF17242C), style = Stroke(width = 1.4f * unit))
                drawRoundRect(
                    color = Color(0xFF30383F),
                    topLeft = androidx.compose.ui.geometry.Offset(x - 4f * unit, baseY + 22f * unit),
                    size = androidx.compose.ui.geometry.Size(5f * unit, 12f * unit),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f * unit),
                )
                drawRoundRect(
                    color = Color(0xFF30383F),
                    topLeft = androidx.compose.ui.geometry.Offset(x + faceWidth - unit, baseY + 22f * unit),
                    size = androidx.compose.ui.geometry.Size(5f * unit, 12f * unit),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f * unit),
                )
                val footOffset = if (isThinking) 0f else bob.value * unit
                drawRoundRect(
                    color = Color(0xFF30383F),
                    topLeft = androidx.compose.ui.geometry.Offset(x + 6f * unit, baseY + faceHeight - 1f * unit + footOffset),
                    size = androidx.compose.ui.geometry.Size(10f * unit, 6f * unit),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f * unit),
                )
                drawRoundRect(
                    color = Color(0xFF30383F),
                    topLeft = androidx.compose.ui.geometry.Offset(x + 21f * unit, baseY + faceHeight - 1f * unit - footOffset),
                    size = androidx.compose.ui.geometry.Size(10f * unit, 6f * unit),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f * unit),
                )
            }
            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 16.dp, top = 13.dp, end = 82.dp),
            ) {
                Text("KI IS WITH YOU", color = KiAccent, fontSize = 10.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(
                    status,
                    color = if (isError) KiError else KiText,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                )
                if (!isError) {
                    Text(
                        if (isThinking) "Thinking…" else "Tap me for a little hello",
                        color = KiMuted,
                        fontSize = 11.sp,
                    )
                }
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
                Text("Local only", color = KiAccent, fontSize = 11.sp)
            }
            Button(
                onClick = onScan,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(13.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF3A6975),
                    contentColor = KiText,
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
            .clip(RoundedCornerShape(20.dp))
            .background(KiSurface)
            .padding(20.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("A little company, wherever you go.", color = KiText, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(7.dp))
        Text(
            "Connect to Ki on your PC, then send a message to start chatting.",
            color = KiMuted,
            fontSize = 13.sp,
        )
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val isUser = message.role == "user"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = Modifier
                .widthInCompat()
                .clip(
                    RoundedCornerShape(
                        topStart = 17.dp,
                        topEnd = 17.dp,
                        bottomEnd = if (isUser) 5.dp else 17.dp,
                        bottomStart = if (isUser) 17.dp else 5.dp,
                    ),
                )
                .background(if (isUser) Color(0xFF34434B) else KiSurfaceRaised)
                .padding(horizontal = 13.dp, vertical = 10.dp),
        ) {
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
    val octets = uri.host?.split('.')
        ?: throw IOException("The QR code must contain the PC's private IPv4 address.")
    if (octets.size != 4) throw IOException("The QR code must contain the PC's private IPv4 address.")
    val address = octets.map { component ->
        component.toIntOrNull()?.takeIf { it in 0..255 }
            ?: throw IOException("The QR code contains an invalid private IPv4 address.")
    }
    val privateAddress = address[0] == 10 ||
        (address[0] == 172 && address[1] in 16..31) ||
        (address[0] == 192 && address[1] == 168) ||
        (address[0] == 169 && address[1] == 254)
    if (!privateAddress) throw IOException("The QR code must point to a private local network address.")
    if (token.isBlank() || token.length > 256) throw IOException("The QR code does not contain a valid pairing token.")
    return host to token
}

private fun normalizeHost(value: String): String {
    val host = value.trim().trimEnd('/')
    if (host.isEmpty()) throw IOException("Enter the PC address shown in Ki's dashboard.")
    if (!host.startsWith("http://")) throw IOException("Enter the full http:// address shown in Ki's dashboard.")
    return host
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
            throw IOException(response.optString("error", "The PC is missing the Qwen3:1.7b model."))
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
    speakOnPc: Boolean,
): String {
    val body = JSONObject().apply {
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
                    .put("content_base64", attachment.contentBase64),
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

private fun readResponse(connection: HttpURLConnection, status: Int): String {
    val stream = if (status in 200..299) connection.inputStream else connection.errorStream
    return stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
        ?: throw IOException("Ki returned an empty response (HTTP $status).")
}

private fun openConnection(address: String, token: String, method: String): HttpURLConnection {
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

private suspend fun readTextAttachment(context: Context, uri: Uri): TextAttachment = withContext(Dispatchers.IO) {
    val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: "attachment.txt"
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
