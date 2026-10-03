package ai.vatio.example

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ai.vatio.Vatio
import ai.vatio.VatioChat
import ai.vatio.VatioException
import ai.vatio.VatioMessage
import ai.vatio.VatioUpload
import kotlinx.coroutines.launch
import java.io.File

// A dummy app for trying the SDK: a home screen with an ask bar at the
// bottom, a conversation tab where the agent answers, and a settings tab for
// the workspace and token. Allow `android-app://ai.vatio.example` in the
// workspace's `allowed_origins` first.
//
// Settings can also come from launch extras, and `ask` there, or opening
// `vatioexample://ask?q=hola`, asks a question the way the bar does.
// `askFile` sends a file from the app's storage:
//   adb shell am start -n ai.vatio.example/.MainActivity \
//     --es workspace acme --es token vatpub_... --es baseUrl http://10.0.2.2:3000 --es ask hola
class MainActivity : ComponentActivity() {
    private val store: ChatStore by viewModels()
    private var tab by mutableStateOf(Tab.HOME)

    enum class Tab { HOME, CONVERSATION, SETTINGS }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handle(intent)

        setContent {
            MaterialTheme {
                LaunchedEffect(store.chat) {
                    store.chat.resume()
                    store.settle(store.chat.messages.value)
                }
                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            NavigationBarItem(
                                selected = tab == Tab.HOME, onClick = { tab = Tab.HOME },
                                icon = { Icon(Icons.Filled.Home, null) }, label = { Text("Inicio") },
                            )
                            NavigationBarItem(
                                selected = tab == Tab.CONVERSATION, onClick = { tab = Tab.CONVERSATION },
                                icon = { Icon(Icons.AutoMirrored.Filled.Chat, null) }, label = { Text("Asistente") },
                            )
                            NavigationBarItem(
                                selected = tab == Tab.SETTINGS, onClick = { tab = Tab.SETTINGS },
                                icon = { Icon(Icons.Filled.Settings, null) }, label = { Text("Ajustes") },
                            )
                        }
                    },
                ) { padding ->
                    val modifier = Modifier.padding(padding)
                    when (tab) {
                        Tab.HOME -> HomeScreen(modifier) { ask(it) }
                        Tab.CONVERSATION -> ConversationScreen(store, store.chat, modifier)
                        Tab.SETTINGS -> SettingsScreen(store, modifier)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent) {
        val extras = intent.extras
        if (extras != null && (extras.containsKey("workspace") || extras.containsKey("token"))) {
            store.configure(
                workspace = extras.getString("workspace") ?: store.workspace,
                token = extras.getString("token") ?: store.token,
                baseUrl = extras.getString("baseUrl") ?: store.baseUrl,
            )
        }
        intent.data?.takeIf { it.scheme == "vatioexample" && it.host == "ask" }?.getQueryParameter("q")?.let(::ask)
        extras?.getString("ask")?.let(::ask)
        extras?.getString("askFile")?.let { path ->
            val file = File(path).takeIf { it.isAbsolute } ?: File(filesDir, path)
            if (file.exists()) {
                tab = Tab.CONVERSATION
                store.send("", listOf(VatioUpload.fromFile(file)))
            }
        }
    }

    private fun ask(question: String) {
        tab = Tab.CONVERSATION
        store.send(question)
    }
}

/** Holds the current VatioChat and rebuilds it when the settings change. */
class ChatStore(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("example", Context.MODE_PRIVATE)

    var workspace: String = prefs.getString("workspace", "") ?: ""
        private set
    var token: String = prefs.getString("token", "") ?: ""
        private set
    var baseUrl: String = prefs.getString("baseUrl", "https://vatio.ai") ?: "https://vatio.ai"
        private set

    var chat by mutableStateOf(makeChat())
        private set
    var sendError by mutableStateOf<String?>(null)
        private set

    /**
     * Replies already shown in full: history from `resume()`, and every
     * reply once its typewriter finishes. Only the rest are painted.
     */
    var settled by mutableStateOf(emptySet<Long>())
        private set

    val isConfigured: Boolean get() = workspace.isNotEmpty() && token.startsWith("vatpub_")

    fun settle(messages: List<VatioMessage>) {
        settled = settled + messages.map { it.id }
    }

    fun configure(workspace: String, token: String, baseUrl: String) {
        this.workspace = workspace.trim()
        this.token = token.trim()
        this.baseUrl = baseUrl.trim().ifEmpty { "https://vatio.ai" }
        prefs.edit()
            .putString("workspace", this.workspace)
            .putString("token", this.token)
            .putString("baseUrl", this.baseUrl)
            .apply()
        chat.close()
        chat = makeChat()
    }

    fun send(question: String, attachments: List<VatioUpload> = emptyList()) {
        sendError = null
        viewModelScope.launch {
            try {
                chat.send(question, attachments)
            } catch (e: VatioException) {
                sendError = "${e.code}: ${e.message}"
            }
        }
    }

    private fun makeChat() = VatioChat(Vatio(getApplication(), workspace = workspace, token = token, baseUrl = baseUrl))

    override fun onCleared() {
        chat.close()
    }
}
