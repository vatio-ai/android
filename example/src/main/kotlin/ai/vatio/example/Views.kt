package ai.vatio.example

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.vatio.VatioAttachment
import ai.vatio.VatioChat
import ai.vatio.VatioException
import ai.vatio.VatioFeedback
import ai.vatio.VatioMessage
import ai.vatio.VatioUpload
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(modifier: Modifier = Modifier, onAsk: (String) -> Unit) {
    Column(modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Inicio") })
        Column(Modifier.weight(1f)) {
            Text("Tu app", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(16.dp, 8.dp))
            ListItem(headlineContent = { Text("Pedidos") })
            ListItem(headlineContent = { Text("Pagos") })
            ListItem(headlineContent = { Text("Perfil") })
            Text(
                "Escribe una pregunta abajo: la app pasa a la pestaña Asistente y el agente responde ahí.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
        AskBar(onSubmit = onAsk)
    }
}

@Composable
fun AskBar(placeholder: String = "Pregúntale al asistente", onSubmit: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val submit = {
        val question = text.trim()
        if (question.isNotEmpty()) {
            text = ""
            onSubmit(question)
        }
    }
    Surface(tonalElevation = 3.dp, modifier = Modifier.imePadding()) {
        Row(Modifier.fillMaxWidth().padding(12.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text, onValueChange = { text = it }, placeholder = { Text(placeholder) },
                singleLine = true, modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { submit() }),
            )
            IconButton(onClick = submit, enabled = text.isNotBlank()) {
                Icon(Icons.AutoMirrored.Filled.Send, "Enviar")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationScreen(store: ChatStore, chat: VatioChat, modifier: Modifier = Modifier) {
    val messages by chat.messages.collectAsStateWithLifecycle()
    val isTyping by chat.isTyping.collectAsStateWithLifecycle()
    val status by chat.status.collectAsStateWithLifecycle()
    val lastError by chat.lastError.collectAsStateWithLifecycle()
    val feedback by chat.feedback.collectAsStateWithLifecycle()
    val list = rememberLazyListState()

    // Messages, the feedback card, the typing row and the error: the last
    // index is always the bottom.
    val rows = messages.size + 3
    LaunchedEffect(messages.lastOrNull()?.id, isTyping, feedback) { list.animateScrollToItem(rows - 1) }

    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Asistente") },
            navigationIcon = { StatusDot(status, Modifier.padding(start = 16.dp)) },
            actions = { TextButton(onClick = { chat.startOver() }, enabled = messages.isNotEmpty()) { Text("Nueva") } },
        )
        LazyColumn(
            state = list,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (messages.isEmpty()) {
                item {
                    Text(
                        if (store.isConfigured) "Todavía no hay conversación." else "Configura workspace y token en Ajustes.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(top = 40.dp),
                    )
                }
            }
            items(messages, key = { it.id }) { message ->
                Bubble(
                    message = message,
                    paints = message.role == VatioMessage.Role.Assistant && message.id !in store.settled,
                    onProgress = { },
                    onFinish = { store.settle(listOf(message)) },
                )
            }
            item(key = "feedback") {
                feedback?.takeIf { it.isOpen || it.rating != null }?.let { FeedbackCard(chat, it) }
            }
            item(key = "typing") {
                if (isTyping) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(
                            "Escribiendo…", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
            item(key = "error") {
                (store.sendError ?: lastError?.message)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }
        Composer { text, files -> store.send(text, files) }
    }
}

@Composable
fun Bubble(message: VatioMessage, paints: Boolean, onProgress: () -> Unit, onFinish: () -> Unit) {
    val visitor = message.isFromVisitor
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (visitor) Arrangement.End else Arrangement.Start) {
        Column(
            Modifier
                .widthIn(max = 300.dp)
                .alpha(if (message.isPending) 0.6f else 1f)
                .clip(RoundedCornerShape(16.dp))
                .background(if (visitor) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                .padding(12.dp, 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val color = if (visitor) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
            message.attachments.forEach { AttachmentView(it, color) }
            // A file-only message carries a stand-in ("Image"); the file above
            // already says it.
            if (!message.isMediaLabel && message.content.isNotEmpty()) {
                TypewriterText(message.content, paints, color, onProgress, onFinish)
            }
        }
    }
}

@Composable
fun StatusDot(status: VatioChat.Status, modifier: Modifier = Modifier) {
    val (color, label) = when (status) {
        VatioChat.Status.IDLE -> Color.Gray to "sin conversación"
        VatioChat.Status.CONNECTING -> Color(0xFFFF9800) to "conectando"
        VatioChat.Status.CONNECTED -> Color(0xFF4CAF50) to "en vivo"
        VatioChat.Status.RECONNECTING -> Color(0xFFFF9800) to "reconectando"
        VatioChat.Status.CLOSED -> Color.Gray to "cerrado"
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Text(label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 4.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(store: ChatStore, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var workspace by remember { mutableStateOf(store.workspace) }
    var token by remember { mutableStateOf(store.token) }
    var baseUrl by remember { mutableStateOf(store.baseUrl) }
    var agent by remember { mutableStateOf<String?>(null) }

    Column(modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Ajustes") })
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(workspace, { workspace = it }, label = { Text("workspace (slug)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(token, { token = it }, label = { Text("vatpub_…") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(baseUrl, { baseUrl = it }, label = { Text("https://vatio.ai") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text(
                "Agrega android-app://${context.packageName} a allowed_origins en vatio.yml. " +
                    "Desde el emulador, tu máquina es http://10.0.2.2:3000.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = {
                store.configure(workspace, token, baseUrl)
                agent = "Verificando…"
                scope.launch {
                    agent = try {
                        val config = store.chat.vatio.config()
                        "Conectado a ${config.agentName} (${config.environment})"
                    } catch (e: VatioException) {
                        "${e.code}: ${e.message}"
                    }
                }
            }) { Text("Guardar y conectar") }
            agent?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

/**
 * Shows a reply that arrived whole as if it were being written, the way the
 * web widget does: the SDK delivers the complete message, and the effect is
 * purely on screen. Faster the more text is left, so a long answer never
 * keeps the reader waiting. Tap to show it all at once.
 */
@Composable
fun TypewriterText(text: String, paints: Boolean, color: Color, onProgress: () -> Unit = {}, onFinish: () -> Unit = {}) {
    var shown by remember(text) { mutableIntStateOf(if (paints) 0 else text.length) }

    LaunchedEffect(text, paints) {
        if (!paints) {
            shown = text.length
            return@LaunchedEffect
        }
        var last = System.nanoTime()
        while (shown < text.length) {
            delay(16)
            val now = System.nanoTime()
            val elapsed = (now - last) / 1e9
            last = now
            // Characters per second, as in the widget: behind / 0.4s, 170...900.
            val rate = ((text.length - shown) / 0.4).coerceIn(170.0, 900.0)
            shown = minOf(text.length, shown + maxOf(1, (rate * elapsed).roundToInt()))
            onProgress()
        }
        onFinish()
    }

    Text(
        markdown(text.take(shown)),
        color = color,
        modifier = Modifier.clickable(enabled = shown < text.length) {
            shown = text.length
            onFinish()
        },
    )
}

/**
 * Enough markdown for a chat bubble: **bold**, *italic*, `code` and
 * [links](https://…). The content is untrusted, so it only ever becomes
 * styled text.
 */
fun markdown(source: String): AnnotatedString = buildAnnotatedString {
    val pattern = Regex("""\*\*(.+?)\*\*|\*(.+?)\*|`([^`]+)`|\[([^\]]+)]\((https?://[^)\s]+)\)""")
    var index = 0
    for (match in pattern.findAll(source)) {
        append(source, index, match.range.first)
        val (bold, italic, code, label, url) = match.destructured
        when {
            bold.isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(bold) }
            italic.isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(italic) }
            code.isNotEmpty() -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(code) }
            else -> withLink(LinkAnnotation.Url(url)) {
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(label) }
            }
        }
        index = match.range.last + 1
    }
    append(source, index, source.length)
}

/**
 * What Vatio's feedback moment looks like in this app. The SDK only says
 * when to ask (`chat.feedback`) and takes the answer (`rate`,
 * `dismissFeedback`); the card is yours to design.
 */
@Composable
fun FeedbackCard(chat: VatioChat, feedback: VatioFeedback) {
    val scope = rememberCoroutineScope()
    var picked by remember { mutableStateOf<VatioFeedback.Rating?>(null) }
    var comment by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun answer(call: suspend () -> Unit) {
        sending = true
        error = null
        scope.launch {
            try {
                call()
            } catch (e: VatioException) {
                error = e.message
            }
            sending = false
        }
    }

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (feedback.rating != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.CheckCircle, null, tint = Color(0xFF4CAF50))
                Text("¡Gracias por tu opinión!", modifier = Modifier.padding(start = 8.dp))
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("¿Cómo lo hizo ${feedback.agentName}?", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                TextButton(onClick = { answer { chat.dismissFeedback() } }, enabled = !sending) { Text("Omitir") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                VatioFeedback.Rating.entries.forEach { rating ->
                    val label = when (rating) {
                        VatioFeedback.Rating.GOOD -> "Bien"
                        VatioFeedback.Rating.NEUTRAL -> "Más o menos"
                        VatioFeedback.Rating.BAD -> "Mal"
                    }
                    // "Bien" needs no explanation, so it goes straight out.
                    val choose = {
                        picked = rating
                        if (rating == VatioFeedback.Rating.GOOD) answer { chat.rate(VatioFeedback.Rating.GOOD) }
                    }
                    if (picked == rating) {
                        FilledTonalButton(onClick = choose, enabled = !sending, modifier = Modifier.weight(1f)) { Text(label, maxLines = 1) }
                    } else {
                        OutlinedButton(onClick = choose, enabled = !sending, modifier = Modifier.weight(1f)) { Text(label, maxLines = 1) }
                    }
                }
            }
            picked?.takeIf { it != VatioFeedback.Rating.GOOD }?.let { rating ->
                OutlinedTextField(
                    comment, { comment = it }, placeholder = { Text("¿Qué pudo salir mejor? (opcional)") },
                    maxLines = 4, modifier = Modifier.fillMaxWidth(),
                )
                Button(onClick = { answer { chat.rate(rating, comment.ifEmpty { null }) } }, enabled = !sending) { Text("Enviar") }
            }
        }
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }
}

/** The conversation's input: text, a photo from the library, or a voice note. */
@Composable
fun Composer(onSend: (String, List<VatioUpload>) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    val recorder = remember { VoiceRecorder(context) }
    DisposableEffect(Unit) { onDispose { recorder.stop() } }

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val photo = withContext(Dispatchers.IO) { VatioUpload.fromUri(context, uri) }
            onSend("", listOf(photo))
        }
    }
    val microphone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) recorder.start()
    }

    val sendText = {
        val message = text.trim()
        if (message.isNotEmpty()) {
            text = ""
            onSend(message, emptyList())
        }
    }

    Surface(tonalElevation = 3.dp, modifier = Modifier.imePadding()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = {
                photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }) { Icon(Icons.Filled.AttachFile, "Adjuntar foto") }

            if (recorder.isRecording) {
                Text("Grabando… toca para enviar", color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
            } else {
                OutlinedTextField(
                    value = text, onValueChange = { text = it }, placeholder = { Text("Escribe un mensaje") },
                    singleLine = true, modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { sendText() }),
                )
            }

            if (text.isBlank()) {
                IconButton(onClick = {
                    if (recorder.isRecording) {
                        recorder.stop()?.let { onSend("", listOf(it)) }
                    } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        recorder.start()
                    } else {
                        microphone.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }) {
                    Icon(
                        if (recorder.isRecording) Icons.Filled.Stop else Icons.Filled.Mic, "Nota de voz",
                        tint = if (recorder.isRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    )
                }
            } else {
                IconButton(onClick = sendText) { Icon(Icons.AutoMirrored.Filled.Send, "Enviar") }
            }
        }
    }
}

/** Records an AAC .m4a, one of the formats Vatio transcribes. */
class VoiceRecorder(private val context: Context) {
    var isRecording by mutableStateOf(false)
        private set
    private var recorder: MediaRecorder? = null
    private val file = File(context.cacheDir, "nota.m4a")

    fun start() {
        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        try {
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioSamplingRate(44_100)
            recorder.setAudioChannels(1)
            recorder.setOutputFile(file.absolutePath)
            recorder.prepare()
            recorder.start()
        } catch (e: Exception) {
            recorder.release()
            return
        }
        this.recorder = recorder
        isRecording = true
    }

    fun stop(): VatioUpload? {
        val recorder = recorder ?: return null
        this.recorder = null
        isRecording = false
        val stopped = try {
            recorder.stop()
            true
        } catch (_: RuntimeException) {
            // Stopped before any audio was captured.
            false
        } finally {
            recorder.release()
        }
        return if (stopped) VatioUpload(file.readBytes(), file.name, "audio/mp4") else null
    }
}

/**
 * How this app draws a file on a message: the image, a player for a voice
 * note, a link for anything else. While pending there is no URL yet.
 */
@Composable
fun AttachmentView(attachment: VatioAttachment, color: Color) {
    val uriHandler = LocalUriHandler.current
    when (attachment.kind) {
        VatioAttachment.Kind.IMAGE -> {
            if (attachment.url != null) {
                AsyncImage(
                    model = attachment.url, contentDescription = attachment.filename,
                    modifier = Modifier.widthIn(max = 220.dp).heightIn(max = 220.dp).clip(RoundedCornerShape(10.dp)),
                )
            } else {
                Box(Modifier.size(160.dp, 120.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
        }
        VatioAttachment.Kind.AUDIO -> {
            var player by remember { mutableStateOf<MediaPlayer?>(null) }
            DisposableEffect(Unit) { onDispose { player?.release() } }
            Row(
                Modifier.clickable(enabled = attachment.url != null) {
                    player?.release()
                    player = MediaPlayer().apply {
                        setDataSource(attachment.url)
                        setOnPreparedListener { it.start() }
                        prepareAsync()
                    }
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.PlayCircle, null, tint = color)
                Text("Nota de voz", color = color, modifier = Modifier.padding(start = 6.dp))
            }
        }
        VatioAttachment.Kind.VIDEO, VatioAttachment.Kind.FILE -> {
            Row(
                Modifier.clickable(enabled = attachment.url != null) { attachment.url?.let(uriHandler::openUri) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Description, null, tint = color)
                Text(attachment.filename, color = color, modifier = Modifier.padding(start = 6.dp))
            }
        }
    }
}
