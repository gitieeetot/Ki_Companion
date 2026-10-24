package com.ki.companion

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.provider.OpenableColumns
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.URL
import java.util.Base64
import kotlin.random.Random

private val KiBackground = Color(0xFF17191C)
private val KiSurface = Color(0xFF22262B)
private val KiSurfaceRaised = Color(0xFF2A3036)
private val KiAccent = Color(0xFF8FE8FF)
private val KiText = Color(0xFFF2F4F6)
private val KiMuted = Color(0xFFA8B0B9)
private val KiError = Color(0xFFFF9D94)

private data class ChatMessage(val role: String, val content: String)
private data class TextAttachment(val name: String, val contentBase64: String)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(23, 25, 28)
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
    val showConnection = remember { mutableStateOf(host.value.isBlank() || token.value.isBlank()) }
    val showPairingHelp = remember { mutableStateOf(false) }
    val attachment = remember { mutableStateOf<TextAttachment?>(null) }
    val messages = remember { mutableStateListOf<ChatMessage>() }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

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
            .background(KiBackground)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("KI", color = KiAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
                Text("Your companion", color = KiText, fontSize = 23.sp, fontWeight = FontWeight.Bold)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { showUpdateDialog.value = true }) {
                    Text("Updates", color = KiAccent)
                }
                TextButton(onClick = { showPairingHelp.value = true }) {
                    Text("Pairing help", color = KiAccent)
                }
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

        if (showConnection.value) {
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
                onTest = {
                    scope.launch {
                        busy.value = true
                        status.value = "Checking PC and Ollama…"
                        statusIsError.value = false
                        try {
                            val message = withContext(Dispatchers.IO) { checkConnection(host.value, token.value) }
                            status.value = message
                            showConnection.value = false
                        } catch (error: Exception) {
                            status.value = describeNetworkError(error)
                            statusIsError.value = true
                        } finally {
                            busy.value = false
                        }
                    }
                },
            )
        } else {
            TextButton(
                onClick = { showConnection.value = true },
                modifier = Modifier.align(Alignment.Start),
            ) {
                Text("PC connection  ·  Change", color = KiMuted)
            }
        }

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
                    onClick = {
                        val prompt = input.value.trim()
                        input.value = ""
                        val selectedAttachment = attachment.value
                        attachment.value = null
                        messages.add(ChatMessage("user", prompt))
                        status.value = "Ki is thinking…"
                        statusIsError.value = false
                        busy.value = true
                        scope.launch {
                            try {
                                val answer = withContext(Dispatchers.IO) {
                                    sendChat(host.value, token.value, messages.toList(), selectedAttachment)
                                }
                                messages.add(ChatMessage("assistant", answer))
                                status.value = "Ki replied"
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
                    },
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
                        "2. Enable phone access and enter a displayed http:// address and pairing token here.\n\n" +
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
                val faceWidth = 46f * unit
                val faceHeight = 54f * unit
                val x = travel.value * (size.width - faceWidth - 24f * unit) + 12f * unit
                val baseY = size.height - faceHeight - 10f * unit + bob.value * unit
                drawRoundRect(
                    color = Color(0x1439D7FF),
                    topLeft = androidx.compose.ui.geometry.Offset(x - 7f * unit, baseY - 7f * unit),
                    size = androidx.compose.ui.geometry.Size(faceWidth + 14f * unit, faceHeight + 14f * unit),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(18f * unit),
                )
                drawRoundRect(
                    color = Color(0xFF30383F),
                    topLeft = androidx.compose.ui.geometry.Offset(x, baseY),
                    size = androidx.compose.ui.geometry.Size(faceWidth, faceHeight),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(15f * unit),
                )
                drawRoundRect(
                    color = Color(0xFF8FE8FF),
                    topLeft = androidx.compose.ui.geometry.Offset(x + 5f * unit, baseY + 6f * unit),
                    size = androidx.compose.ui.geometry.Size(faceWidth - 10f * unit, faceHeight - 16f * unit),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(12f * unit),
                )
                val eyesY = baseY + 23f * unit
                drawCircle(Color(0xFF17242C), radius = 3f * unit, center = androidx.compose.ui.geometry.Offset(x + 17f * unit, eyesY))
                drawCircle(Color(0xFF17242C), radius = 3f * unit, center = androidx.compose.ui.geometry.Offset(x + 29f * unit, eyesY))
                val mouth = Path().apply {
                    moveTo(x + 19f * unit, baseY + 37f * unit)
                    quadraticTo(x + 23f * unit, baseY + 41f * unit, x + 28f * unit, baseY + 37f * unit)
                }
                drawPath(mouth, color = Color(0xFF17242C), style = Stroke(width = 1.7f * unit))
                val footOffset = if (isThinking) 0f else bob.value * unit
                drawRoundRect(
                    color = Color(0xFF8FE8FF),
                    topLeft = androidx.compose.ui.geometry.Offset(x + 9f * unit, baseY + faceHeight - 5f * unit + footOffset),
                    size = androidx.compose.ui.geometry.Size(10f * unit, 7f * unit),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f * unit),
                )
                drawRoundRect(
                    color = Color(0xFF8FE8FF),
                    topLeft = androidx.compose.ui.geometry.Offset(x + 27f * unit, baseY + faceHeight - 5f * unit - footOffset),
                    size = androidx.compose.ui.geometry.Size(10f * unit, 7f * unit),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f * unit),
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
    onTest: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = KiSurface),
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
): String {
    val body = JSONObject().apply {
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
