package ai.vatio

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.File
import java.time.Instant

/** One message in a conversation, as the visitor sees it. */
public data class VatioMessage(
    /** Server id. Negative while [isPending], because the server has not assigned one yet. */
    val id: Long,
    val role: Role,
    /** Markdown, as the agent wrote it. Render it as untrusted text. */
    val content: String,
    val createdAt: Instant? = null,
    /**
     * True for a message the visitor just sent that the server has not
     * acknowledged yet. It is shown straight away so the question is on
     * screen before the network answers.
     */
    val isPending: Boolean = false,
    /** The visitor's own files. While [isPending] their `url` is null. */
    val attachments: List<VatioAttachment> = emptyList(),
    /**
     * [content] is only a stand-in ("Image", "Audio") for a message that was
     * just a file: draw the attachment, not the word.
     */
    val isMediaLabel: Boolean = false,
) {
    val isFromVisitor: Boolean get() = role == Role.User

    public sealed interface Role {
        public data object User : Role
        public data object Assistant : Role
        /** A role this SDK version does not know yet, passed through as sent. */
        public data class Other(val raw: String) : Role

        public companion object {
            internal fun of(raw: String): Role = when (raw) {
                "user" -> User
                "assistant" -> Assistant
                else -> Other(raw)
            }
        }
    }
}

/** A file on a message. */
public data class VatioAttachment(
    val id: Long,
    val filename: String,
    val contentType: String,
    val byteSize: Long,
    val kind: Kind,
    /** Absolute and signed; loads without the chat credential. Null while the message is pending. */
    val url: String?,
) {
    public enum class Kind(internal val wire: String) {
        IMAGE("image"), AUDIO("audio"), VIDEO("video"), FILE("file");

        internal companion object {
            fun of(wire: String?): Kind = entries.firstOrNull { it.wire == wire } ?: FILE
        }
    }
}

/**
 * A file to attach to `send`: an image (JPEG, PNG, WebP, GIF, HEIC), a PDF,
 * a text file, or audio (M4A, MP3, OGG, WAV, AAC, FLAC), up to 8 MB. A voice
 * note is transcribed, and the transcript becomes the message's content.
 */
public class VatioUpload(
    public val bytes: ByteArray,
    public val filename: String,
    public val contentType: String,
) {
    internal val kind: VatioAttachment.Kind
        get() = when {
            contentType.startsWith("image/") -> VatioAttachment.Kind.IMAGE
            contentType.startsWith("audio/") -> VatioAttachment.Kind.AUDIO
            contentType.startsWith("video/") -> VatioAttachment.Kind.VIDEO
            else -> VatioAttachment.Kind.FILE
        }

    public companion object {
        public const val MAX_BYTES: Int = 8 * 1024 * 1024

        /**
         * Reads a local file, taking its type from the extension -- a
         * `MediaRecorder` .m4a, a photo saved to the cache directory.
         */
        @JvmStatic
        public fun fromFile(file: File): VatioUpload =
            VatioUpload(file.readBytes(), file.name, mimeType(file.extension))

        /**
         * Reads a `content://` URI from a picker (`PickVisualMedia`,
         * `OpenDocument`), with the name and type its provider reports.
         * Call it off the main thread: it reads the whole file.
         */
        @JvmStatic
        public fun fromUri(context: Context, uri: Uri): VatioUpload {
            val resolver = context.contentResolver
            val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            } ?: uri.lastPathSegment ?: "file"
            val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw VatioException("file_unreadable", "could not read $uri")
            return VatioUpload(bytes, name, resolver.getType(uri) ?: mimeType(name.substringAfterLast('.', "")))
        }

        private fun mimeType(extension: String): String =
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.lowercase()) ?: "application/octet-stream"
    }
}

/** How a reply is delivered. Fixed when a conversation starts. */
public enum class ReplyStyle(internal val wire: String) {
    /** One complete reply, sent the moment it is ready. The default. */
    STREAM("stream"),
    /** Two or three short messages, each preceded by a typing indicator. */
    PACED("paced"),
    /** One complete reply and no typing events. */
    INSTANT("instant"),
}

/** The agent's public branding and the widget copy configured in `vatio.yml`. */
public data class VatioConfig(
    val workspace: String,
    val environment: String,
    val agentName: String,
    val avatarUrl: String?,
    val about: String?,
    /** `#RRGGBB`. */
    val accentColor: String?,
    val title: String?,
    val greeting: String?,
    val suggestions: List<String>,
    /**
     * `en`, `es` or `pt`: the language for your UI's own labels. The agent
     * answers in whatever language the visitor writes.
     */
    val locale: String?,
)

/** A past conversation of this visitor, as returned by [Vatio.conversations]. */
@ConsistentCopyVisibility
public data class VatioConversation internal constructor(
    val id: Long,
    /** What the visitor opened with. */
    val title: String,
    /** The last thing said. */
    val preview: String,
    val startedAt: Instant?,
    val updatedAt: Instant?,
    internal val chatToken: String,
    internal val expiresAt: Instant?,
)

/**
 * A moment to ask the visitor how it went, offered by Vatio once per
 * conversation when a request looks resolved.
 */
public data class VatioFeedback(
    /** The agent that answered, for "How did <name> do?". */
    val agentName: String,
    /** The reply the moment is attached to. */
    val messageId: Long,
    /** Set once the visitor answered. */
    val rating: Rating?,
    val submittedAt: Instant?,
    val dismissed: Boolean,
    /**
     * The agent's own words to ask with, written for this conversation; null
     * when there are none, and you ask in your own words.
     */
    val question: String? = null,
) {
    /** Still waiting for the visitor: neither rated nor dismissed. */
    val isOpen: Boolean get() = rating == null && !dismissed

    public enum class Rating(internal val wire: String) {
        GOOD("good"), NEUTRAL("neutral"), BAD("bad");

        internal companion object {
            fun of(wire: String?): Rating? = entries.firstOrNull { it.wire == wire }
        }
    }
}

public class VatioException(
    /**
     * Machine-readable: `origin_not_allowed`, `rate_limited`,
     * `no_agent_deployed`, `blank_content`, `content_too_long`, … or
     * `network_error` when the request never got an answer.
     */
    public val code: String,
    override val message: String,
    /** HTTP status, when there was a response. */
    public val status: Int? = null,
) : Exception(message) {
    override fun toString(): String = "VatioException($code: $message)"
}
