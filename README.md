# Vatio for Android

Talk to a [Vatio](https://vatio.ai) agent from your own Android app. A Kotlin
library with no UI: it gives you the conversation as `StateFlow`s, and you
draw it however your app looks, in Compose or Views.

- Android 8.0+ (API 26)
- Gradle, through JitPack
- OkHttp and kotlinx.coroutines only

## Setup

**1. Allow your app.** A publishable token only works from origins the
workspace allowlists. Your app's origin is `android-app://` plus its package
name (its `applicationId`):

```yaml
# vatio.yml
allowed_origins:
  - https://acme.com
  - android-app://com.acme.app
```

```bash
vatio push
vatio tokens create --env live --label android
```

**2. Add the library.** It is built by [JitPack](https://jitpack.io) from the
tags of this repository:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

// app/build.gradle.kts
dependencies {
    implementation("com.github.vatio-ai:android:0.2.1")
}
```

**3. Create a client** with the `vatpub_` token. It is meant to ship inside
the app. A `vat_` token is a developer secret: never put one in an app.

```kotlin
import ai.vatio.Vatio

val vatio = Vatio(context, workspace = "acme", token = "vatpub_...")
```

## The whole API

```kotlin
val chat = VatioChat(vatio)          // no network yet

chat.messages                        // StateFlow<List<VatioMessage>>, oldest first
chat.isTyping                        // StateFlow<Boolean>: the agent is writing
chat.status                          // IDLE CONNECTING CONNECTED RECONNECTING CLOSED
chat.lastError                       // background failures; send() throws instead
chat.feedback                        // a feedback moment on offer, or null

chat.send("hola")                    // suspends; starts the conversation on first call
chat.send(attachments = listOf(upload))   // images, PDFs, text, audio (8 MB)
chat.resume()                        // reopen the stored conversation, if any
chat.startOver()                     // forget it; next send starts a new one
chat.close()                         // disconnect

chat.rate(VatioFeedback.Rating.GOOD) // answer it: GOOD NEUTRAL BAD (+ comment)
chat.dismissFeedback()               // or decline it

vatio.config()                       // agent name, avatar, accent, greeting, suggestions
vatio.conversations()                // this visitor's past conversations
chat.open(conversation)              // switch to one of them
```

A `VatioMessage` has `id`, `role` (`Role.User`, `Role.Assistant`), `content`
(markdown, untrusted), `createdAt` and `isPending`. The visitor's message
appears in `messages` the moment `send` is called, with `isPending == true`
until the server acknowledges it; the reply follows on its own.

The chat's state belongs to the main thread, and every suspending call
switches to it, so call them from any scope. Keep the chat in a `ViewModel`
and close it in `onCleared()`.

## Example: a text bar that opens the conversation

A bar at the bottom of any screen. When the user asks something, the app moves
to a Conversation tab where the agent is answering.

```kotlin
class AssistantViewModel(application: Application) : AndroidViewModel(application) {
    val chat = VatioChat(Vatio(application, workspace = "acme", token = "vatpub_..."))

    init { viewModelScope.launch { chat.resume() } }

    fun ask(question: String) = viewModelScope.launch {
        try { chat.send(question) } catch (e: VatioException) { /* put the text back */ }
    }

    override fun onCleared() = chat.close()
}

@Composable
fun AcmeApp(model: AssistantViewModel = viewModel()) {
    var tab by remember { mutableStateOf(0) }
    Scaffold(bottomBar = {
        NavigationBar {
            NavigationBarItem(tab == 0, { tab = 0 }, { Icon(Icons.Filled.Home, null) }, label = { Text("Home") })
            NavigationBarItem(tab == 1, { tab = 1 }, { Icon(Icons.AutoMirrored.Filled.Chat, null) }, label = { Text("Assistant") })
        }
    }) { padding ->
        Column(Modifier.padding(padding)) {
            if (tab == 0) {
                HomeScreen(Modifier.weight(1f))
            } else {
                ConversationList(model.chat, Modifier.weight(1f))
            }
            AskBar { question ->
                tab = 1
                model.ask(question)
            }
        }
    }
}

@Composable
fun AskBar(onSubmit: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Row(Modifier.fillMaxWidth().padding(8.dp)) {
        OutlinedTextField(text, { text = it }, Modifier.weight(1f), placeholder = { Text("Ask anything") })
        IconButton(onClick = { onSubmit(text); text = "" }, enabled = text.isNotBlank()) {
            Icon(Icons.AutoMirrored.Filled.Send, "Send")
        }
    }
}

@Composable
fun ConversationList(chat: VatioChat, modifier: Modifier = Modifier) {
    val messages by chat.messages.collectAsStateWithLifecycle()
    val typing by chat.isTyping.collectAsStateWithLifecycle()
    LazyColumn(modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp)) {
        items(messages, key = { it.id }) { message ->
            Text(
                message.content,
                Modifier
                    .fillMaxWidth()
                    .wrapContentWidth(if (message.isFromVisitor) Alignment.End else Alignment.Start)
                    .alpha(if (message.isPending) 0.6f else 1f)
                    .padding(vertical = 4.dp),
            )
        }
        if (typing) item { CircularProgressIndicator() }
    }
}
```

Handle the exception from `send` to put the text back in the field: on failure
the pending message is removed from `messages`.

### Try it: the example app

`example/` is a runnable version of this, with a settings tab for the
workspace, token and base URL. It also paints each new reply progressively, as
the web widget does (`TypewriterText` in `Views.kt`): the SDK delivers the
reply whole, and the effect is purely on screen, so copy it if you want it.
Allow `android-app://ai.vatio.example` in `vatio.yml`, then open this folder
in Android Studio and run `example`, or:

```bash
./gradlew :example:installDebug
```

Or launch it already configured, with a question (`10.0.2.2` is your machine
from the emulator):

```bash
adb shell am start -n ai.vatio.example/.MainActivity \
  --es workspace acme --es token vatpub_... --es baseUrl http://10.0.2.2:3000 --es ask hola
```

## Files and voice notes

`send(text, attachments)` takes up to four `VatioUpload`s — an image (JPEG,
PNG, WebP, GIF, HEIC), a PDF, a text file, or audio (M4A, MP3, OGG, WAV, AAC,
FLAC), up to 8 MB each. With files, the text may be empty:

```kotlin
// From a picker: PickVisualMedia, OpenDocument. It reads the file, so not on the main thread.
val photo = withContext(Dispatchers.IO) { VatioUpload.fromUri(context, uri) }
chat.send("Is this the right part?", listOf(photo))

// A MediaRecorder recording (AAC in .m4a).
chat.send(attachments = listOf(VatioUpload.fromFile(recording)))

// Or bytes you already have.
VatioUpload(jpegBytes, "photo.jpg", "image/jpeg")
```

The pending message shows the files at once, with `url == null`; when the
server answers it is replaced by the stored message, whose `VatioAttachment`s
each have a signed, absolute `url`. A voice note is transcribed in the
background: the message reads "Audio" at first and its `content` becomes the
transcript a few seconds later, in place. The agent waits for it. When
`isMediaLabel` is true, `content` is only a stand-in ("Image", "Audio") for a
file-only message: draw the attachment instead.

The server reads a file's type from its bytes and refuses one the agent cannot
read (`unsupported_file_type`). `example/` has a composer with a photo picker
and a voice recorder (`Composer`, `VoiceRecorder`, `AttachmentView` in
`Views.kt`); recording needs the `RECORD_AUDIO` permission.

## Visitor feedback

Vatio offers a feedback moment at most once per conversation, when a request
looks resolved and the visitor closed the topic. `chat.feedback` holds it —
pushed the moment it exists, and fetched on connect for a conversation that
was resumed. Show your own prompt while `feedback.isOpen` and answer with
`rate(rating, comment)` (a comment of up to 2,000 characters, meant for
`NEUTRAL` and `BAD`) or `dismissFeedback()`. It goes back to null when the
visitor writes again without answering. `example/` has a card for it
(`FeedbackCard`).

## Signed-in users

When your app knows who the user is, have your backend sign a visitor token
and pass it, so the agent can use protected tools and the conversation lands on
the right contact:

```kotlin
val chat = VatioChat(vatio, visitorToken = tokenFromYourBackend)
```

Pass the same token to `vatio.conversations(visitorToken)`. A different token
subject is a different person, with its own conversation and history, so
signing out of your app signs out of the chat too. An anonymous conversation is
kept when the user signs in. See
[Sessions and channels](https://vatio.ai/docs/authentication/sessions).

## Options

```kotlin
Vatio(
    context,
    workspace = "acme",
    token = "vatpub_...",
    baseUrl = "https://vatio.ai",   // default
    appId = null,                   // defaults to the app's package name
    storageName = "vatio",          // SharedPreferences file
    client = null,                  // an OkHttpClient of your own
)

VatioChat(vatio, visitorToken = null, replyStyle = ReplyStyle.STREAM)   // STREAM, PACED, INSTANT
```

`replyStyle` is fixed when a conversation starts. `STREAM` sends one complete
reply as soon as it is ready; `PACED` sends two or three short messages with a
typing indicator before each, like a person texting.

## Delivery and storage

Replies arrive over a WebSocket. When it drops (the app went to the
background, the network changed), the SDK reconnects with backoff, polls in the
meantime, and fetches whatever was said while it was away. Messages are
deduplicated by id.

The current conversation and the visitor id are stored in `SharedPreferences`,
per workspace and per signed-in user. Chat credentials last 12 hours; after
that, `resume()` finds nothing and the next `send` starts a new conversation.

Limits are the same as on the web: 4,000 characters per message, and per IP
and workspace, per minute, 10 conversations started, 30 messages and 60
configuration reads or conversation lists.

## Errors

Every failure is a `VatioException` with a `code`, a `message` and the HTTP
`status` when there was one:

| `code` | Meaning |
|---|---|
| `origin_not_allowed` | `android-app://<package name>` is not in `allowed_origins` |
| `wrong_token_kind` | The token is not a `vatpub_` publishable token |
| `no_agent_deployed` | Nothing is published to the token's environment |
| `blank_content`, `content_too_long` | The message is empty or over 4,000 characters |
| `too_many_files`, `file_too_large`, `unsupported_file_type` | More than four files, one over 8 MB, or a type the agent cannot read |
| `rate_limited` | Too many requests; back off |
| `subscription_rejected` | The chat credential expired; the next `send` starts a new conversation |
| `network_error` | The request never got an answer |

## Versions

Tags follow semver, and `Vatio.VERSION` matches the tag. A breaking change to
the public API is a new major version.

## Issues

Bugs and questions go to [issues](https://github.com/vatio-ai/android/issues).
This repository is a read-only mirror of the SDK as it ships inside Vatio, so a
pull request cannot be merged here: open an issue describing the change
instead.
