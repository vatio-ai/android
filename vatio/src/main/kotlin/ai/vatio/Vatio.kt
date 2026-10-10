package ai.vatio

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * A connection to one Vatio workspace, from an Android app.
 *
 *     val vatio = Vatio(context, workspace = "acme", token = "vatpub_...")
 *     val chat = VatioChat(vatio)
 *     chat.send("hola")
 *
 * [token] is a publishable token (`vatpub_…`), meant to ship inside your app.
 * It only works once the workspace allowlists the app: add
 * `android-app://YOUR.PACKAGE.NAME` to `allowed_origins` in `vatio.yml`.
 * A `vat_` token is a developer secret and must never ship in an app.
 */
public class Vatio @JvmOverloads constructor(
    context: Context,
    public val workspace: String,
    public val token: String,
    public val baseUrl: String = "https://vatio.ai",
    /**
     * The package name sent as `Origin: android-app://<appId>`. Defaults to
     * the application's package name.
     */
    appId: String? = null,
    /** The `SharedPreferences` file the conversation and visitor id are kept in. */
    public val storageName: String = "vatio",
    client: OkHttpClient? = null,
) {
    public companion object {
        public const val VERSION: String = "0.2.1"

        private val sharedClient by lazy { OkHttpClient() }
    }

    private val context: Context = context.applicationContext
    public val appId: String = appId ?: context.packageName

    internal val client: OkHttpClient = client ?: sharedClient
    // The server pings every few seconds; a socket that hears nothing for a
    // while is dead even if the network never said so.
    internal val socketClient: OkHttpClient by lazy {
        this.client.newBuilder().pingInterval(15, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS).build()
    }

    internal val origin: String get() = "android-app://${appId.lowercase()}"

    /**
     * The agent's name, avatar, accent color and widget copy, without
     * starting a conversation. Throws `no_agent_deployed` when nothing is
     * published to the token's environment.
     */
    public suspend fun config(): VatioConfig {
        validateToken()
        val body = request("GET", "config", bearer = token)
        return decode(body, ::parseConfig)
    }

    /**
     * Every conversation this visitor has had on this workspace from this
     * device, newest first. Empty for a visitor who never talked here. Pass
     * the same [visitorToken] you give [VatioChat].
     */
    @JvmOverloads
    public suspend fun conversations(visitorToken: String? = null): List<VatioConversation> {
        validateToken()
        val visitorRef = storage(visitorToken).visitorRef ?: return emptyList()
        // A conversation this person had signed in is listed only alongside
        // their token.
        val builder = requestBuilder("chats?visitor_ref=${queryEscaped(visitorRef)}", token).get()
        if (!visitorToken.isNullOrEmpty()) builder.header("Vatio-Visitor-Token", visitorToken)
        val body = send(builder.build())
        return decode(body) { json -> json.list("data").map(::parseConversation) }
    }

    // Internals

    internal fun storage(visitorToken: String?): VisitorStorage = VisitorStorage(
        context.getSharedPreferences(storageName, Context.MODE_PRIVATE),
        "vatio:$baseUrl:$workspace",
        visitorToken,
    )

    internal fun validateToken() {
        if (!token.startsWith("vatpub_")) {
            throw VatioException(
                "wrong_token_kind",
                "token must be a publishable token (vatpub_...). A vat_ token is a developer secret and must never ship in an app.",
            )
        }
    }

    internal val cableUrl: HttpUrl get() = parsedBaseUrl().newBuilder().addPathSegment("cable").build()

    internal suspend fun request(method: String, path: String, bearer: String, body: JSONObject? = null): String {
        val requestBody = body?.toString()?.toRequestBody("application/json".toMediaType())
        return send(requestBuilder(path, bearer).method(method, requestBody).build())
    }

    /** multipart/form-data: `content` and one `files[]` part per upload. */
    internal suspend fun upload(path: String, bearer: String, content: String, files: List<VatioUpload>): String {
        val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
        if (content.isNotEmpty()) multipart.addFormDataPart("content", content)
        for (file in files) {
            multipart.addFormDataPart(
                "files[]",
                file.filename.replace("\"", ""),
                file.bytes.toRequestBody(file.contentType.toMediaType()),
            )
        }
        return send(requestBuilder(path, bearer).post(multipart.build()).build())
    }

    internal fun <T> decode(body: String, read: (JSONObject) -> T): T = try {
        read(JSONObject(body))
    } catch (e: JSONException) {
        throw VatioException("invalid_response", "unexpected response from Vatio: ${e.message}")
    }

    private fun parsedBaseUrl(): HttpUrl = try {
        baseUrl.toHttpUrl()
    } catch (e: IllegalArgumentException) {
        throw VatioException("sdk_error", "invalid baseUrl $baseUrl")
    }

    private fun requestBuilder(path: String, bearer: String): Request.Builder {
        val workspacePath = HttpUrl.Builder().scheme("https").host("x").addPathSegment(workspace).build().encodedPath
        val url = parsedBaseUrl().resolve("api/visitor/v1$workspacePath/$path")
            ?: throw VatioException("sdk_error", "invalid URL for $path")
        return Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $bearer")
            .header("Origin", origin)
            .header("Accept", "application/json")
    }

    private suspend fun send(request: Request): String = withContext(Dispatchers.IO) {
        val response = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            throw VatioException("network_error", e.message ?: "the request failed")
        }
        response.use {
            val body = try {
                it.body?.string() ?: ""
            } catch (e: IOException) {
                throw VatioException("network_error", e.message ?: "the response was cut off")
            }
            if (!it.isSuccessful) throw errorFrom(body, it.code)
            body
        }
    }

    private fun errorFrom(body: String, status: Int): VatioException {
        val json = try { JSONObject(body) } catch (_: JSONException) { null }
        val code = json?.string("error")
        val message = json?.string("error_description") ?: code ?: "request failed with $status"
        return VatioException(code ?: "request_failed", message, status)
    }
}

internal fun queryEscaped(value: String): String =
    HttpUrl.Builder().scheme("https").host("x").addQueryParameter("v", value).build().encodedQuery!!.removePrefix("v=")

/**
 * The stored conversation and visitor id, kept per person: a different
 * `visitorToken` subject is a different visitor, with its own conversation
 * and its own id, so signing out of the app really does sign out.
 */
internal class VisitorStorage(
    private val prefs: SharedPreferences,
    private val prefix: String,
    private val visitorToken: String?,
) {
    data class StoredChat(val chatId: Long, val chatToken: String, val expiresAt: Instant?) {
        val isExpired: Boolean get() = (expiresAt ?: Instant.MIN) <= Instant.now()
    }

    val isIdentified: Boolean get() = subject != null

    var visitorRef: String?
        get() = prefs.getString(key("visitor", isIdentified), null)
        set(value) = prefs.edit().putString(key("visitor", isIdentified), value).apply()

    var chat: StoredChat?
        get() = read(key("chat", isIdentified))
        set(value) = write(value, key("chat", isIdentified))

    /**
     * The conversation filed before the visitor signed in, which an
     * identified visitor adopts instead of losing.
     */
    var anonymousChat: StoredChat?
        get() = read(key("chat", false))
        set(value) = write(value, key("chat", false))

    private fun key(name: String, identified: Boolean): String {
        val scope = if (identified) "default#$subject" else "default"
        return "$prefix:$scope:$name"
    }

    private fun read(key: String): StoredChat? {
        val json = prefs.getString(key, null)?.let { try { JSONObject(it) } catch (_: JSONException) { null } } ?: return null
        val chat = StoredChat(
            chatId = json.long("chatID") ?: return null,
            chatToken = json.string("chatToken") ?: return null,
            expiresAt = json.long("expiresAt")?.let(Instant::ofEpochMilli),
        )
        return chat.takeUnless { it.isExpired }
    }

    private fun write(chat: StoredChat?, key: String) {
        if (chat == null) {
            prefs.edit().remove(key).apply()
            return
        }
        val json = JSONObject()
            .put("chatID", chat.chatId)
            .put("chatToken", chat.chatToken)
            .put("expiresAt", chat.expiresAt?.toEpochMilli())
        prefs.edit().putString(key, json.toString()).apply()
    }

    // Read without verifying, which is safe because it is only a cache key:
    // the server checks the signature before trusting anything in it.
    private val subject: String?
        get() {
            val parts = visitorToken?.split(".") ?: return null
            if (parts.size < 2) return null
            return try {
                val payload = JSONObject(String(Base64.getUrlDecoder().decode(parts[1].trimEnd('='))))
                if (payload.isNull("sub")) null else payload.get("sub").toString()
            } catch (_: Exception) {
                null
            }
        }
}
