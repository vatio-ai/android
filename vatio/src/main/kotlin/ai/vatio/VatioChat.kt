package ai.vatio

import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.time.Instant

/**
 * One visitor's conversation with the agent, as observable state. It draws
 * nothing: collect [messages], [isTyping] and [status] in your own UI and
 * call [send].
 *
 *     val chat = VatioChat(vatio)
 *
 *     val messages by chat.messages.collectAsState()
 *     scope.launch { chat.send(text) }
 *
 * Creating one costs nothing and opens no connection. The conversation starts
 * on the first [send], so a visitor who never writes never becomes a contact.
 * Call [resume] when the screen appears to pick up where they left off, and
 * [close] when it is gone for good -- from a ViewModel's `onCleared`, say.
 *
 * Its state belongs to the main thread; every suspending call switches to it.
 */
public class VatioChat @JvmOverloads constructor(
    public val vatio: Vatio,
    /**
     * Says who the visitor is when your app already knows: a token your
     * backend signed for the signed-in user (see
     * docs.vatio.ai/authentication/sessions). A different token subject is a
     * different person, with a conversation of their own.
     */
    public val visitorToken: String? = null,
    /** Fixed when the conversation starts. */
    public val replyStyle: ReplyStyle = ReplyStyle.STREAM,
) {
    public enum class Status {
        /** No conversation yet, or it was closed with [startOver]. */
        IDLE,
        CONNECTING,
        /** Live: replies arrive the moment they are sent. */
        CONNECTED,
        /** The socket dropped. Replies are fetched by polling until it is back. */
        RECONNECTING,
        CLOSED,
    }

    private val _messages = MutableStateFlow<List<VatioMessage>>(emptyList())
    private val _isTyping = MutableStateFlow(false)
    private val _status = MutableStateFlow(Status.IDLE)
    private val _lastError = MutableStateFlow<VatioException?>(null)
    private val _feedback = MutableStateFlow<VatioFeedback?>(null)

    /** The conversation so far, oldest first, deduplicated by id. */
    public val messages: StateFlow<List<VatioMessage>> = _messages.asStateFlow()

    /** The agent is writing. */
    public val isTyping: StateFlow<Boolean> = _isTyping.asStateFlow()
    public val status: StateFlow<Status> = _status.asStateFlow()

    /**
     * The last failure that happened outside a [send] call — a reply that
     * could not be fetched, a credential that expired. [send] throws instead.
     */
    public val lastError: StateFlow<VatioException?> = _lastError.asStateFlow()

    /**
     * A moment to ask the visitor how it went, when Vatio offers one -- at
     * most once per conversation, after a request looks resolved. Show it
     * while `isOpen`, and answer with [rate] or [dismissFeedback]. It goes
     * back to null when the visitor writes again without answering.
     */
    public val feedback: StateFlow<VatioFeedback?> = _feedback.asStateFlow()

    /** The conversation id, once there is one. */
    public val conversationId: Long? get() = current?.chatId

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var current: VisitorStorage.StoredChat? = null
    private var starting: Deferred<VisitorStorage.StoredChat>? = null
    private var cable: Cable? = null
    private var subscribed = false
    private var closed = false
    private var attempt = 0
    private var lastMessageId = 0L
    private var nextPendingId = -1L
    private val readyWaiters = mutableListOf<CompletableDeferred<Unit>>()
    private var pollJob: Job? = null
    private var pollUntil = 0L

    private companion object {
        val RECONNECT_DELAYS = longArrayOf(500, 1_000, 2_000, 5_000, 10_000)
        const val POLL_INTERVAL = 1_500L
        const val POLL_WINDOW = 45_000L
        const val SUBSCRIBE_TIMEOUT = 10_000L
    }

    // Public surface

    /** Whether there is a stored, unexpired conversation [resume] would open. */
    public val canResume: Boolean
        get() {
            val storage = vatio.storage(visitorToken)
            return storage.chat != null || (storage.isIdentified && storage.anonymousChat != null)
        }

    /**
     * Reopens the visitor's stored conversation and loads its history. Does
     * nothing when there is none — it never starts a new one.
     */
    public suspend fun resume(): Unit = withContext(Dispatchers.Main.immediate) {
        if (current != null || starting != null || !canResume) return@withContext
        try {
            ensureStarted(createIfMissing = false)
        } catch (e: VatioException) {
            _lastError.value = e
        }
    }

    /**
     * Sends the visitor's message. It appears in [messages] at once, marked
     * `isPending`, and the reply follows through [messages] and [isTyping].
     *
     * [attachments] adds up to four files (see [VatioUpload]); with them,
     * [text] may be empty. Once the server answers, the pending message is
     * replaced by the stored one, file URLs included. A voice note is
     * transcribed in the background: the message shows "Audio"
     * (`isMediaLabel`) at first, and its `content` becomes the transcript a
     * few seconds later, in place.
     *
     * The first call starts the conversation. Throws a [VatioException] --
     * `blank_content`, `content_too_long` (4,000 characters),
     * `too_many_files`, `file_too_large`, `unsupported_file_type`,
     * `rate_limited`, `origin_not_allowed`, …; on failure the pending
     * message is removed, so put the text and files back.
     */
    @JvmOverloads
    public suspend fun send(text: String = "", attachments: List<VatioUpload> = emptyList()): Unit =
        withContext(Dispatchers.Main.immediate) {
            val content = text.trim()
            if (content.isEmpty() && attachments.isEmpty()) {
                throw VatioException("blank_content", "content or attachments are required")
            }
            if (attachments.size > 4) throw VatioException("too_many_files", "at most 4 files per message")
            attachments.firstOrNull { it.bytes.size > VatioUpload.MAX_BYTES }?.let {
                throw VatioException("file_too_large", "${it.filename} is over 8 MB")
            }

            val pendingFiles = attachments.mapIndexed { index, file ->
                VatioAttachment(
                    id = -(index + 1L), filename = file.filename, contentType = file.contentType,
                    byteSize = file.bytes.size.toLong(), kind = file.kind, url = null,
                )
            }
            val pending = VatioMessage(
                id = nextPendingId, role = VatioMessage.Role.User, content = content, createdAt = Instant.now(),
                isPending = true, attachments = pendingFiles, isMediaLabel = content.isEmpty(),
            )
            nextPendingId -= 1
            _messages.value = _messages.value + pending
            _lastError.value = null

            try {
                val chat = ensureStarted(createIfMissing = true)
                ready()

                val path = "chats/${chat.chatId}/messages"
                val body = if (attachments.isEmpty()) {
                    vatio.request("POST", path, chat.chatToken, JSONObject().put("content", content))
                } else {
                    vatio.upload(path, chat.chatToken, content, attachments)
                }
                val result = try { JSONObject(body) } catch (_: Exception) { null }
                val stored = result?.optJSONObject("message")?.let { try { parseMessage(it) } catch (_: Exception) { null } }

                confirm(pending, stored, result?.long("user_message_id"))
                // Writing again moves the conversation on, and the moment with it.
                if (_feedback.value?.rating == null) _feedback.value = null
                if (!subscribed) pollFor(POLL_WINDOW)
            } catch (e: Throwable) {
                _messages.value = _messages.value.filterNot { it.id == pending.id }
                throw e
            }
        }

    /**
     * Answers the feedback moment. [comment] (up to 2,000 characters) is
     * meant for `NEUTRAL` and `BAD`. Throws `feedback_unavailable` once the
     * moment has passed.
     */
    @JvmOverloads
    public suspend fun rate(rating: VatioFeedback.Rating, comment: String? = null): Unit =
        answerFeedback(rating = rating, comment = comment)

    /** Declines the feedback moment; it is not offered again. */
    public suspend fun dismissFeedback(): Unit = answerFeedback(dismiss = true)

    /**
     * Switches to one of the visitor's past conversations, from
     * [Vatio.conversations]. It becomes the stored one, so [resume] returns
     * to it from then on.
     */
    public suspend fun open(conversation: VatioConversation): Unit = withContext(Dispatchers.Main.immediate) {
        disconnect()
        _messages.value = emptyList()
        val chat = VisitorStorage.StoredChat(conversation.id, conversation.chatToken, conversation.expiresAt)
        vatio.storage(visitorToken).chat = chat
        connect(chat, loadHistory = true)
    }

    /**
     * Forgets the stored conversation and clears [messages]. The next [send]
     * starts a new one. Server history is kept.
     */
    public fun startOver() {
        disconnect()
        vatio.storage(visitorToken).chat = null
        _messages.value = emptyList()
        _lastError.value = null
        _status.value = Status.IDLE
    }

    /**
     * Disconnects. Call it when the conversation leaves the screen for good;
     * [resume] or [send] reconnects.
     */
    public fun close() {
        disconnect()
        _status.value = Status.CLOSED
    }

    // Starting

    private suspend fun ensureStarted(createIfMissing: Boolean): VisitorStorage.StoredChat {
        current?.let { return it }
        starting?.let { return it.await() }

        val task = scope.async(start = CoroutineStart.LAZY) { start(createIfMissing) }
        starting = task
        try {
            return task.await()
        } finally {
            if (starting === task) starting = null
        }
    }

    private suspend fun start(createIfMissing: Boolean): VisitorStorage.StoredChat {
        vatio.validateToken()
        _status.value = Status.CONNECTING
        val storage = vatio.storage(visitorToken)

        val stored = storage.chat ?: (if (storage.isIdentified) storage.anonymousChat else null)
        if (stored != null) {
            if (visitorToken != null && !identify(stored, visitorToken)) {
                // The stored conversation belongs to someone else.
                storage.anonymousChat = null
            } else {
                // Signing in keeps the conversation the visitor was having.
                if (storage.chat == null) {
                    storage.chat = stored
                    storage.anonymousChat = null
                }
                connect(stored, loadHistory = true)
                return stored
            }
        }

        if (!createIfMissing) {
            _status.value = Status.IDLE
            throw VatioException("no_conversation", "there is no conversation to resume")
        }

        val body = JSONObject().put("reply_style", replyStyle.wire)
        storage.visitorRef?.let { body.put("visitor_ref", it) }
        visitorToken?.let { body.put("visitor_token", it) }

        val response = vatio.request("POST", "chats", vatio.token, body)
        val (chat, visitorRef) = vatio.decode(response) { json ->
            VisitorStorage.StoredChat(
                chatId = json.getLong("chat_id"),
                chatToken = json.getString("chat_token"),
                expiresAt = parseDate(json.string("chat_token_expires_at")),
            ) to json.string("visitor_ref")
        }
        visitorRef?.let { storage.visitorRef = it }
        storage.chat = chat

        connect(chat, loadHistory = false)
        return chat
    }

    /**
     * False only when the server says this conversation belongs to a
     * different person. A network failure carries on with it.
     */
    private suspend fun identify(chat: VisitorStorage.StoredChat, visitorToken: String): Boolean = try {
        val body = vatio.request(
            "POST", "chats/${chat.chatId}/identify", chat.chatToken, JSONObject().put("visitor_token", visitorToken),
        )
        val reason = try { JSONObject(body).string("reason") } catch (_: Exception) { null }
        reason != "identity_changed"
    } catch (e: VatioException) {
        e.status != 401
    }

    private suspend fun connect(chat: VisitorStorage.StoredChat, loadHistory: Boolean) {
        closed = false
        _status.value = Status.CONNECTING
        if (loadHistory) {
            val body = vatio.request("GET", "chats/${chat.chatId}/messages", chat.chatToken)
            merge(vatio.decode(body) { json -> json.list("data").map(::parseMessage) })
        }
        current = chat
        openSocket()
        ready()
    }

    // Transport

    private fun openSocket() {
        val chat = current ?: return
        if (closed) return

        // Weak, so a chat dropped without close() is not kept alive by its
        // socket: Cable notices and hangs up.
        val self = WeakReference(this)
        val cable = Cable(vatio.socketClient, vatio.cableUrl, vatio.origin, chat.chatToken, this) { event ->
            self.get()?.handle(event)
        }
        this.cable = cable
        cable.connect()

        scope.launch {
            delay(SUBSCRIBE_TIMEOUT)
            if (this@VatioChat.cable !== cable || subscribed) return@launch
            // No socket in time (a network that eats upgrades): poll instead.
            pollFor(POLL_WINDOW)
            releaseWaiters()
        }
    }

    private fun handle(event: Cable.Event) {
        when (event) {
            Cable.Event.Subscribed -> {
                subscribed = true
                attempt = 0
                _status.value = Status.CONNECTED
                stopPolling()
                releaseWaiters()
                // Whatever was said while there was no subscription: the gap
                // after loading history, or a reconnect.
                if (lastMessageId > 0) scope.launch { resync() }
                // A moment offered while nobody was listening arrives this way.
                scope.launch { refreshFeedback() }
            }

            Cable.Event.Rejected -> {
                // The credential expired or was revoked. The next send starts
                // a new conversation.
                cable = null
                subscribed = false
                current = null
                vatio.storage(visitorToken).chat = null
                _status.value = Status.IDLE
                _lastError.value = VatioException("subscription_rejected", "the chat credential is no longer valid")
                releaseWaiters()
            }

            is Cable.Event.Typing -> _isTyping.value = event.typing

            is Cable.Event.Message -> {
                if (event.message.role != VatioMessage.Role.User) _isTyping.value = false
                merge(listOf(event.message))
            }

            is Cable.Event.Feedback -> _feedback.value = event.feedback

            is Cable.Event.Updated -> {
                // A voice note's transcript, in where the "Audio" label stood.
                _messages.value = _messages.value.map {
                    if (it.id == event.id) it.copy(content = event.content, isMediaLabel = event.isMediaLabel) else it
                }
            }

            Cable.Event.Closed -> {
                cable = null
                subscribed = false
                if (closed) return
                _status.value = Status.RECONNECTING
                releaseWaiters()
                pollFor(POLL_WINDOW)

                val wait = RECONNECT_DELAYS[minOf(attempt, RECONNECT_DELAYS.size - 1)]
                attempt += 1
                scope.launch {
                    delay(wait)
                    if (closed || cable != null) return@launch
                    openSocket()
                }
            }
        }
    }

    /**
     * Returns once the socket is subscribed, or once it is clear it will not
     * be soon, so a send never races the reply it is about to cause.
     */
    private suspend fun ready() {
        if (cable == null || subscribed || pollJob != null) return
        val waiter = CompletableDeferred<Unit>()
        readyWaiters += waiter
        waiter.await()
    }

    private fun releaseWaiters() {
        val waiters = readyWaiters.toList()
        readyWaiters.clear()
        waiters.forEach { it.complete(Unit) }
    }

    private fun disconnect() {
        closed = true
        cable?.close()
        cable = null
        subscribed = false
        current = null
        _isTyping.value = false
        _feedback.value = null
        stopPolling()
        releaseWaiters()
    }

    // Feedback

    private suspend fun answerFeedback(
        rating: VatioFeedback.Rating? = null, comment: String? = null, dismiss: Boolean = false,
    ): Unit = withContext(Dispatchers.Main.immediate) {
        val chat = current ?: throw VatioException("feedback_unavailable", "there is no conversation")
        val body = JSONObject()
        rating?.let { body.put("rating", it.wire) }
        comment?.let { body.put("comment", it) }
        if (dismiss) body.put("dismiss", true)
        val response = vatio.request("POST", "chats/${chat.chatId}/feedback", chat.chatToken, body)
        _feedback.value = vatio.decode(response) { json -> json.optJSONObject("feedback")?.let(::parseFeedback) }
    }

    private suspend fun refreshFeedback() {
        val chat = current ?: return
        val offered = try {
            val body = vatio.request("GET", "chats/${chat.chatId}/feedback", chat.chatToken)
            vatio.decode(body) { json -> json.optJSONObject("feedback")?.let(::parseFeedback) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return
        }
        if (current?.chatId != chat.chatId) return
        if (offered != _feedback.value) _feedback.value = offered
    }

    // Fallbacks

    private suspend fun resync() {
        val chat = current ?: return
        try {
            val body = vatio.request("GET", "chats/${chat.chatId}/messages?after=$lastMessageId", chat.chatToken)
            val fetched = vatio.decode(body) { json -> json.list("data").map(::parseMessage) }
            if (fetched.any { it.role != VatioMessage.Role.User }) _isTyping.value = false
            merge(fetched)
        } catch (e: VatioException) {
            if (e.code != "network_error") _lastError.value = e
        }
    }

    private fun pollFor(duration: Long) {
        pollUntil = maxOf(pollUntil, SystemClock.elapsedRealtime() + duration)
        if (pollJob != null || closed) return

        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                while (true) {
                    delay(POLL_INTERVAL)
                    if (closed || subscribed || SystemClock.elapsedRealtime() > pollUntil) break
                    resync()
                }
            } finally {
                if (pollJob === coroutineContext[Job]) pollJob = null
            }
        }
        pollJob = job
        job.start()
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    // State

    // The stored message when the server returned it (its transcript, its
    // file URLs); otherwise the pending one under the server's id.
    private fun confirm(pending: VatioMessage, stored: VatioMessage?, id: Long?) {
        val list = _messages.value.toMutableList()
        val index = list.indexOfFirst { it.id == pending.id }
        if (index < 0) return
        val confirmedId = stored?.id ?: id
        if (confirmedId == null || list.any { it.id == confirmedId }) {
            list.removeAt(index)
            _messages.value = list
            return
        }
        list[index] = stored ?: pending.copy(id = confirmedId, isPending = false)
        lastMessageId = maxOf(lastMessageId, confirmedId)
        _messages.value = sorted(list)
    }

    private fun merge(incoming: List<VatioMessage>) {
        if (incoming.isEmpty()) return
        val list = _messages.value.toMutableList()
        val known = list.mapTo(HashSet()) { it.id }
        for (message in incoming) {
            if (message.id in known) continue
            // A resync can return the visitor's message before `send` hears
            // back: it replaces the pending copy instead of doubling it.
            if (message.role == VatioMessage.Role.User) {
                val index = list.indexOfFirst { it.isPending && it.content == message.content }
                if (index >= 0) list.removeAt(index)
            }
            list += message
            known += message.id
            lastMessageId = maxOf(lastMessageId, message.id)
        }
        _messages.value = sorted(list)
    }

    // Pending messages last, in the order they were sent (their ids count
    // down from -1).
    private fun sorted(list: List<VatioMessage>): List<VatioMessage> =
        list.sortedWith(compareBy<VatioMessage> { it.isPending }.thenBy { if (it.isPending) -it.id else it.id })
}
