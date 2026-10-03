package ai.vatio

import android.os.Handler
import android.os.Looper
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONException
import org.json.JSONObject
import java.lang.ref.WeakReference

/**
 * The realtime half: one Action Cable subscription to the visitor's own
 * conversation. Not public -- the wire format is free to change, VatioChat is
 * the contract. Everything here runs on the main thread; OkHttp's callbacks
 * are posted to it.
 */
internal class Cable(
    private val client: OkHttpClient,
    private val url: HttpUrl,
    private val origin: String,
    chatToken: String,
    owner: Any,
    private val onEvent: (Event) -> Unit,
) {
    sealed interface Event {
        data object Subscribed : Event
        data object Rejected : Event
        data class Typing(val typing: Boolean) : Event
        data class Message(val message: VatioMessage) : Event
        data class Feedback(val feedback: VatioFeedback?) : Event
        data class Updated(val id: Long, val content: String, val isMediaLabel: Boolean) : Event
        data object Closed : Event
    }

    private val owner = WeakReference(owner)
    private val main = Handler(Looper.getMainLooper())
    private var socket: WebSocket? = null

    // Sorted keys, so the identifier the server echoes back is always the
    // same string this sent.
    private val identifier: String =
        JSONObject(sortedMapOf("channel" to "VisitorChatChannel", "chat_token" to chatToken)).toString()

    fun connect() {
        val request = Request.Builder()
            .url(url)
            .header("Origin", origin)
            .header("Sec-WebSocket-Protocol", "actioncable-v1-json, actioncable-unsupported")
            .build()
        socket = client.newWebSocket(request, Listener())
    }

    fun close() {
        val socket = socket ?: return
        this.socket = null
        send(JSONObject().put("command", "unsubscribe").put("identifier", identifier), socket)
        socket.close(1001, null)
    }

    private inner class Listener : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            main.post { receive(text, webSocket) }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            onMessage(webSocket, bytes.utf8())
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            main.post { ended(webSocket) }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            main.post { ended(webSocket) }
        }
    }

    // Closed by the network rather than by close(): report it once.
    private fun ended(webSocket: WebSocket) {
        if (socket !== webSocket) return
        socket = null
        onEvent(Event.Closed)
    }

    private fun receive(text: String, webSocket: WebSocket) {
        if (socket !== webSocket) return
        // The chat was released without close(). The server pings every few
        // seconds, so this is noticed soon after.
        if (owner.get() == null) {
            socket = null
            webSocket.cancel()
            return
        }
        val json = try { JSONObject(text) } catch (_: JSONException) { return }

        when (json.string("type")) {
            "welcome" -> send(JSONObject().put("command", "subscribe").put("identifier", identifier), webSocket)
            "confirm_subscription" -> onEvent(Event.Subscribed)
            "reject_subscription" -> {
                socket = null
                webSocket.close(1000, null)
                onEvent(Event.Rejected)
            }
            // A disconnect is followed by the socket closing, which is where
            // reconnecting happens.
            "ping", "disconnect" -> return
            else -> {
                val event = json.optJSONObject("message") ?: return
                handle(event)
            }
        }
    }

    private fun handle(event: JSONObject) {
        try {
            when (event.string("type")) {
                "typing_start" -> onEvent(Event.Typing(true))
                "typing_stop" -> onEvent(Event.Typing(false))
                "message" -> onEvent(Event.Message(parseMessage(event.getJSONObject("message"))))
                "message_updated" -> {
                    val update = event.getJSONObject("message")
                    onEvent(
                        Event.Updated(
                            id = update.getLong("id"),
                            content = update.string("content") ?: "",
                            isMediaLabel = update.bool("content_is_media_label") ?: false,
                        ),
                    )
                }
                "feedback" -> {
                    val feedback = event.optJSONObject("feedback")?.let {
                        try { parseFeedback(it) } catch (_: JSONException) { null }
                    }
                    onEvent(Event.Feedback(feedback))
                }
            }
        } catch (_: JSONException) {
            return
        }
    }

    private fun send(command: JSONObject, webSocket: WebSocket) {
        webSocket.send(command.toString())
    }
}
