package ai.vatio

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.OffsetDateTime

// Wire shapes. Every reader tolerates a missing or null field the way the
// iOS SDK's Decodable optionals do; a missing required one throws, which
// Vatio.decode turns into invalid_response.

internal fun JSONObject.string(name: String): String? = if (isNull(name)) null else optString(name)

internal fun JSONObject.long(name: String): Long? = if (isNull(name)) null else optLong(name)

internal fun JSONObject.bool(name: String): Boolean? = if (isNull(name)) null else optBoolean(name)

internal fun JSONObject.list(name: String): List<JSONObject> {
    val array: JSONArray = optJSONArray(name) ?: return emptyList()
    return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
}

internal fun parseMessage(json: JSONObject): VatioMessage = VatioMessage(
    id = json.getLong("id"),
    role = VatioMessage.Role.of(json.getString("role")),
    content = json.string("content") ?: "",
    createdAt = parseDate(json.string("occurred_at") ?: json.string("created_at")),
    attachments = json.list("attachments").map(::parseAttachment),
    isMediaLabel = json.bool("content_is_media_label") ?: false,
)

internal fun parseAttachment(json: JSONObject): VatioAttachment = VatioAttachment(
    id = json.getLong("id"),
    filename = json.getString("filename"),
    contentType = json.string("content_type") ?: "application/octet-stream",
    byteSize = json.long("byte_size") ?: 0,
    kind = VatioAttachment.Kind.of(json.string("kind")),
    url = json.string("url"),
)

internal fun parseFeedback(json: JSONObject): VatioFeedback = VatioFeedback(
    agentName = json.getString("agent_name"),
    messageId = json.getLong("message_id"),
    rating = VatioFeedback.Rating.of(json.string("rating")),
    submittedAt = parseDate(json.string("submitted_at")),
    dismissed = json.bool("dismissed") ?: false,
)

internal fun parseConversation(json: JSONObject): VatioConversation = VatioConversation(
    id = json.getLong("chat_id"),
    title = json.string("title") ?: "",
    preview = json.string("preview") ?: "",
    startedAt = parseDate(json.string("started_at")),
    updatedAt = parseDate(json.string("updated_at")),
    chatToken = json.getString("chat_token"),
    expiresAt = parseDate(json.string("chat_token_expires_at")),
)

internal fun parseConfig(json: JSONObject): VatioConfig {
    val workspace = json.getString("workspace")
    val agent = json.getJSONObject("agent")
    val ui = json.optJSONObject("ui")
    val suggestions = ui?.optJSONArray("suggestions")
    return VatioConfig(
        workspace = workspace,
        environment = json.getString("environment"),
        agentName = agent.string("name") ?: workspace,
        avatarUrl = agent.string("avatar_url"),
        about = agent.string("about"),
        accentColor = json.optJSONObject("theme")?.string("accent"),
        title = ui?.string("title"),
        greeting = ui?.string("greeting"),
        suggestions = suggestions?.let { array -> (0 until array.length()).map { array.getString(it) } } ?: emptyList(),
        locale = json.string("locale"),
    )
}

internal fun parseDate(string: String?): Instant? {
    if (string == null) return null
    return try {
        OffsetDateTime.parse(string).toInstant()
    } catch (_: Exception) {
        null
    }
}
