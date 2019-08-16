@file:Suppress("unused", "MemberVisibilityCanBePrivate")

import kotlinx.coroutines.*
import org.telegram.telegrambots.ApiContextInitializer
import org.telegram.telegrambots.bots.TelegramLongPollingBot
import org.telegram.telegrambots.meta.TelegramBotsApi
import org.telegram.telegrambots.meta.api.methods.GetMe
import org.telegram.telegrambots.meta.api.methods.send.SendDocument
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageReplyMarkup
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText
import org.telegram.telegrambots.meta.api.objects.*
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardRemove
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardButton
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow
import java.io.File
import java.lang.RuntimeException
import java.util.Queue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

typealias InlineKeyboard = InlineKeyboardMarkup
typealias InlineRow = ArrayList<InlineKeyboardButton>
typealias InlineButton = InlineKeyboardButton
typealias ReplyKeyboard = ReplyKeyboardMarkup
typealias ReplyButton = KeyboardButton
typealias ReplyRow = KeyboardRow
typealias Send = SendMessage

class Pooling(val bot: Bot): TelegramLongPollingBot() {
    override fun getBotToken(): String = bot.token
    override fun getBotUsername(): String = bot.username
    override fun onUpdateReceived(update: Update?) = bot.onUpdateReceived(update)
}

interface BotEngine {
    fun download(id: String): File

    fun execute(exec: GetMe): User
    fun execute(exec: Send): Message
    fun execute(exec: SendPhoto): Message
    fun execute(exec: SendDocument): Message
    fun execute(exec: DeleteMessage): Boolean

    fun execute(exec: EditMessageText)
    fun execute(exec: EditMessageReplyMarkup)
}

class TestEngine(val conf: Bot): BotEngine {
    companion object {
        @JvmStatic var updateId = 0
        @JvmStatic var callbackId = 0
    }

    private inline fun <reified T> T.fields(crossinline init: MutableMap<String, Any?>.(T) -> Unit): T {
        val map = HashMap<String, Any?>().apply { init(this@fields) }
        for ((field, value) in map)
            T::class.java.getDeclaredField(field).apply { isAccessible = true }.set(this, value)
        return this
    }
    private fun MutableMap<String, Any?>.field(name: String, value: Any?) {
        this[name] = value
    }

    private val bot = User().fields {
        field("id", 0)
        field("firstName", "Test-Bot#first-name")

        field("lastName", "Test-Bot#last-name")
        field("userName", "Test-Bot#username")
        field("languageCode", "Test-Bot#lang")
        field("isBot", true)
    }

    private val chats = ArrayList<EmulatedChat>()
    inner class EmulatedChat {
        val id = chats.size
        val messages = ArrayList<Message>()

        val chat = Chat().fields {
            field("id", id.toLong())
            field("type", "private")
            field("description", "Emulated-Chat[$id]")
        }

        operator fun get(index: Int) =
            if (index >= 0) messages[index]
            else messages[messages.size + index]

        inline fun EmulatedChat.runandwait(
            count: Int = 1, timeout: Float = .5f, crossinline run: EmulatedChat.() -> Unit
        ) {
            val time = System.nanoTime()
            val i = messages.size

            this.run()

            while (messages.size < i + count)
                if ((System.nanoTime() - time) / 1e9 >= timeout)
                    throw RuntimeException("Timeout of waiting for message has been exceeded")
        }

        private fun from() = User().fields {
            field("id", chat.id.toInt())
            field("firstName", "Test-User#first-name")

            field("lastName", "Test-User#last-name")
            field("userName", "Test-User#username")
            field("languageCode", "Test-User#lang")
            field("isBot", false)
        }

        fun send(text: String) {
            conf.onUpdateReceived(Update().fields {
                field("updateId", updateId ++)

                field("message", Message().fields {
                    field("messageId", messages.size)

                    field("date", (System.nanoTime() / 1000000000).toInt())
                    field("from", from())
                    field("chat", chat)

                    field("text", text)
                })
            })
        }

        fun Message.click(text: String) {
            conf.onUpdateReceived(Update().fields {
                field("updateId", updateId ++)

                field("callbackQuery", CallbackQuery().fields {
                    field("id", (callbackId ++).toString())

                    field("message", this@click)
                    field("from", from())

                    field("data", replyMarkup!!.keyboard.flatten().find { it.text == text }!!.callbackData)
                })
            })
        }
    }
    fun emulated(chatId: String) = chats.find { it.id == chatId.toInt() } ?: error("No such emulated chat")

    private val files = HashMap<String, File>()

    override fun download(id: String): File = files[id]!!

    override fun execute(exec: GetMe) = bot
    override fun execute(exec: Send): Message = Message().fields {
        val emulated = emulated(exec.chatId)
        field("text", exec.text)

        field("from", bot)
        field("chat", emulated.chat)
        field("replyMarkup", exec.replyMarkup)
        field("messageId", emulated.messages.size)
//        field("replyToMessage", exec.replyToMessageId)
        field("date", (System.nanoTime() / 1000000000).toInt())
        emulated.messages += it
    }
    override fun execute(exec: SendPhoto): Message = Message().fields {
        val emulated = emulated(exec.chatId)
        field("photo", ArrayList<PhotoSize>().apply {
            this += PhotoSize().fields {
                val id = files.size.toString()
                files[id] = exec.photo.newMediaFile
                field("fileId", id)
            }
        })

        field("form", bot)
        field("chat", emulated)
        field("replyMarkup", exec.replyMarkup)
        field("messageId", emulated.messages.size)
        field("replyToMessageId", exec.replyToMessageId)
        field("date", (System.nanoTime() / 1000000000).toInt())
    }
    override fun execute(exec: SendDocument): Message = Message().fields {
        val emulated = emulated(exec.chatId)
        field("document", Document().fields {
            val id = files.size.toString()
            files[id] = exec.document.newMediaFile
            field("fileId", id)
        })

        field("form", bot)
        field("chat", emulated)
        field("replyMarkup", exec.replyMarkup)
        field("messageId", emulated.messages.size)
        field("replyToMessageId", exec.replyToMessageId)
        field("date", (System.nanoTime() / 1000000000).toInt())
    }
    override fun execute(exec: DeleteMessage): Boolean {
        emulated(exec.chatId).messages.removeIf { it.messageId == exec.messageId }
        return true
    }
    override fun execute(exec: EditMessageText) {
        emulated(exec.chatId).messages.find { it.messageId == exec.messageId }?.fields {
            field("text", exec.text)
            field("replyMarkup", exec.replyMarkup)
        }
    }
    override fun execute(exec: EditMessageReplyMarkup) {
        emulated(exec.chatId).messages.find { it.messageId == exec.messageId }?.fields {
            field("replyMarkup", exec.replyMarkup)
        }
    }

    fun newChat() = EmulatedChat().apply { chats += this }
}

class PoolingBasedEngine(val pooling: Pooling): BotEngine {
    override fun download(id: String): File = pooling.downloadFile(id)

    override fun execute(exec: GetMe): User = pooling.execute(exec)
    override fun execute(exec: Send): Message = pooling.execute(exec)
    override fun execute(exec: SendPhoto): Message = pooling.execute(exec)
    override fun execute(exec: SendDocument): Message = pooling.execute(exec)
    override fun execute(exec: DeleteMessage): Boolean = pooling.execute(exec)

    override fun execute(exec: EditMessageText) { pooling.execute(exec) }
    override fun execute(exec: EditMessageReplyMarkup) { pooling.execute(exec) }
}

class Bot(val username: String, val token: String, threads: Int) { init {
    for (num in 0 until threads)
        workingThread().name = "working-thread[$num]"
}
    private  val lock = Object()
    lateinit var engine: BotEngine

    fun getBotToken() = token
    fun getBotUsername() = username

    private val actions = ConcurrentHashMap<Long, AtomicLong>()
    private val updates = ConcurrentHashMap<Long, ConcurrentLinkedQueue<Update>>()

    fun onUpdateReceived(update: Update?) {
        val chat = (update?.message?.chat ?: update?.callbackQuery?.message?.chat)?.id ?: return
        synchronized(lock) {
            updates.computeIfAbsent(chat) { ConcurrentLinkedQueue() }.add(update)
            actions.computeIfAbsent(chat) { AtomicLong(System.nanoTime()) }
        }
    }

    private val filters = ConcurrentHashMap<(Update) -> Boolean, suspend HandlerContext.(Update) -> Unit>()
    fun updatefiltered(filter: (Update) -> Boolean, run: suspend HandlerContext.(Update) -> Unit) {
        filters[filter] = run
    }
    fun messagefiltered(filter: (Message) -> Boolean, run: suspend HandlerContext.(Message) -> Unit) {
        updatefiltered({ it.message != null && filter(it.message) }) { run(it.message) }
    }
    fun textfiltered(filter: (String) -> Boolean, run: suspend HandlerContext.(String) -> Unit) {
        updatefiltered({ it.message?.text != null && filter(it.message.text) }) { run(it.message.text) }
    }
    fun regexps(vararg regexps: String, run: suspend HandlerContext.(String) -> Unit) {
        val filters = regexps
            .map { it.toRegex() }
            .map { regex -> { text: String -> text matches regex } }
        updatefiltered({ it.message?.text != null && filters.any { filter -> filter(it.message.text) } }) {
            run(it.message.text)
        }
    }
    fun handler(vararg keys: String, run: suspend HandlerContext.(String) -> Unit) {
        updatefiltered({ it.message?.text != null && it.message.text in keys }) { run(it.message.text) }
    }
    fun always(run: suspend HandlerContext.(Update) -> Unit) {
        updatefiltered({ true }, run)
    }

    inline fun Long.send(text: String, crossinline init: Send.() -> Unit = {}) = engine.execute(Send().apply {
            this.text = text
            this.chatId = this@send.toString()
            this.init()
    })
    inline fun Send.inlinekeyboard(crossinline init: InlineKeyboard.() -> Unit) = InlineKeyboard().apply {
        this.init()
        this@inlinekeyboard.replyMarkup = this
    }
    inline fun Send.replykeyboard(crossinline init: ReplyKeyboard.() -> Unit) = ReplyKeyboard().apply {
        this.init()
        this@replykeyboard.replyMarkup = this
    }
    fun Send.removereplykeyboard() {
        replyMarkup = ReplyKeyboardRemove()
    }
    fun Send.replyto(message: Message) {
        this.replyToMessageId = message.messageId
    }

    inline fun Long.senddocument(crossinline init: SendDocument.() -> Unit) = engine.execute(SendDocument().apply {
            this.chatId = this@senddocument.toString()
            this.init()
    })
    fun SendDocument.file(document: File, name: String? = null, thumbnail: File? = null) {
        if (name != null)
            this.document = InputFile().setMedia(document, name)
        else
            this.document = InputFile().setMedia(document, document.name)
        if (thumbnail != null)
            thumb = InputFile().setMedia(thumbnail, thumbnail.name)
    }
    inline fun SendDocument.inlinekeyboard(crossinline init: InlineKeyboard.() -> Unit) = InlineKeyboard().apply {
        this.init()
        this@inlinekeyboard.replyMarkup = this
    }
    inline fun SendDocument.replykeyboard(crossinline init: ReplyKeyboard.() -> Unit) = ReplyKeyboard().apply {
        this.init()
        this@replykeyboard.replyMarkup = this
    }
    fun SendDocument.removereplykeyboard() {
        replyMarkup = ReplyKeyboardRemove()
    }
    fun SendDocument.replyto(message: Message) {
        this.replyToMessageId = message.messageId
    }

    inline fun Long.sendphoto(crossinline init: SendPhoto.() -> Unit) = engine.execute(SendPhoto().apply {
            this.chatId = this@sendphoto.toString()
            this.init()
    })
    fun SendPhoto.file(document: File, name: String? = null) {
        if (name != null)
            this.photo = InputFile().setMedia(document, name)
        else
            this.photo = InputFile().setMedia(document, document.name)
    }
    inline fun SendPhoto.inlinekeyboard(crossinline init: InlineKeyboard.() -> Unit) = InlineKeyboard().apply {
        this.init()
        this@inlinekeyboard.replyMarkup = this
    }
    inline fun SendPhoto.replykeyboard(crossinline init: ReplyKeyboard.() -> Unit) = ReplyKeyboard().apply {
        this.init()
        this@replykeyboard.replyMarkup = this
    }
    fun SendPhoto.removereplykeyboard() {
        replyMarkup = ReplyKeyboardRemove()
    }
    fun SendPhoto.replyto(message: Message) {
        this.replyToMessageId = message.messageId
    }

    fun getme() = engine.execute(GetMe())

    private val callbackNumber = AtomicInteger(0)
    inline fun InlineKeyboard.row(crossinline init: InlineRow.() -> Unit) = InlineRow().apply {
        this.init()
        if (this@row.keyboard == null)
            this@row.keyboard = ArrayList()
        this@row.keyboard!! += this
    }
    inline fun ReplyKeyboard.row(crossinline init: ReplyRow.() -> Unit) = ReplyRow().apply {
        this.init()
        if (this@row.keyboard == null)
            this@row.keyboard = ArrayList()
        this@row.keyboard!! += this
    }
    fun InlineRow.button(text: String, save: Boolean = false, run: (HandlerContext.(String) -> Unit)? = null) = InlineButton().apply {
        this.text = text
        this.callbackData = "'$text'#callback[${callbackNumber.getAndIncrement()}]"
        if (run != null) {
            val filter = { update: Update -> update.callbackQuery?.data == this.callbackData }
            if (save)
                updatefiltered(filter) {
                    run(text)
                }
            else updatefiltered(filter) {
                run(text)
                filters.remove(filter)
            }
        }
        this@button += this
    }
    fun InlineRow.urlbutton(text: String, url: String) = InlineButton().apply {
        this.text = text
        this.url = url
        this@urlbutton += this
    }
    fun InlineRow.inlinequerybutton(text: String, query: String = "", current: Boolean = false) = InlineButton().apply {
        this.text = text
        if (current)
            switchInlineQueryCurrentChat = query
        else
            switchInlineQuery = query
        this@inlinequerybutton += this
    }
    inline fun ReplyRow.button(text: String, crossinline init: ReplyButton.() -> Unit = {}) = ReplyButton().apply {
        this.text = text
        this.init()
        this@button += this
    }

    inner class HandlerContext(val scope: CoroutineScope, val chat: Long, val queue: Queue<Update>) {
        inline fun send(text: String, crossinline init: Send.() -> Unit = {}) = chat.send(text, init)
        inline fun senddocument(crossinline init: SendDocument.() -> Unit) = chat.senddocument(init)
        inline fun sendphoto(crossinline init: SendPhoto.() -> Unit) = chat.sendphoto(init)

        fun Message.remove() = engine.execute(DeleteMessage().apply {
                chatId = chat.toString()
                messageId = this@remove.messageId
        })
        fun String.download(): File = engine.download(this)
        fun Message.voice() = if (voice == null) null else voice.fileId.download()
        fun Message.document() = if (document == null) null else document.fileId.download()
        fun Message.photo() = if (photo == null) null else photo.map { it.fileId.download() }
        fun Message.video() = if (video == null) null else video.fileId.download()
        inline fun Message.edittext(text: String, crossinline init: InlineKeyboard.() -> Unit) = engine.execute(EditMessageText().apply {
                this.text = text
                this.messageId = this@edittext.messageId
                this.chatId = chat.toString()
                this.replyMarkup = InlineKeyboard().apply(init)
        })
        fun Message.edittext(text: String)  = engine.execute(EditMessageText().apply {
            this.text = text
            this.messageId = this@edittext.messageId
            this.chatId = chat.toString()
        })
        inline fun Message.editkeyboard(crossinline init: InlineKeyboard.() -> Unit) = engine.execute(EditMessageReplyMarkup().apply {
                this.chatId = chat.toString()
                this.messageId = this@editkeyboard.messageId
                this.replyMarkup = InlineKeyboard().apply(init)
        })

        suspend fun Message.click(timeout: Float, handlers: Boolean = false): String? {
            val buttons = replyMarkup
                .keyboard
                .flatten()
                .filter { it.callbackData != null }
                .map { it.callbackData!! to it.text!! }
                .toMap()

            val start = System.nanoTime()
            while (!Thread.currentThread().isInterrupted) {
                val update = queue.poll()
                val key = update?.callbackQuery?.data
                if (key != null && key in buttons)
                    return buttons[key]
                if (handlers)
                    for ((filter, handler) in filters)
                        if (update != null && filter(update))
                            scope.launch {
                                handler(this@HandlerContext, update)
                                if (updates[this@HandlerContext.chat]!!.isNotEmpty())
                                    actions[this@HandlerContext.chat]!!.set(System.nanoTime())
                                else {
                                    actions.remove(this@HandlerContext.chat)
                                    updates.remove(this@HandlerContext.chat)
                                }
                            }
                yield()
                if ((System.nanoTime() - start) / 1e9 >= timeout)
                    return null
            }
            throw InterruptedException()
        }
        suspend inline fun askmessage(text: String? = null, timeout: Float): Message? {
            if (text != null)
                chat.send(text)
            val start = System.nanoTime()
            while (!Thread.currentThread().isInterrupted) {
                val message = queue.poll()?.message
                if (message != null)
                    return message
                else yield()
                if ((System.nanoTime() - start) / 1e9 >= timeout)
                    return null
            }
            throw InterruptedException()
        }
        suspend inline fun ask(message: String? = null, timeout: Float): String? {
            if (message != null)
                chat.send(message)
            val start = System.nanoTime()
            while (!Thread.currentThread().isInterrupted) {
                val text = queue.poll()?.message?.text
                if (text != null)
                    return text
                else yield()
                if ((System.nanoTime() - start) / 1e9 >= timeout)
                    return null
            }
            throw InterruptedException()
        }
        suspend inline fun ask(message: String? = null): String = this.ask(message, Float.POSITIVE_INFINITY)!!
        suspend inline fun Message.click(handlers: Boolean = false) = click(Float.POSITIVE_INFINITY, handlers)!!
    }

    fun Any?.ignore() = null

    fun killsession(): Nothing = throw CancellationException()

    private fun workingThread() = thread(isDaemon = true) {
        runBlocking {
            while (!Thread.currentThread().isInterrupted) {
                yield()
                val chat = synchronized(lock) {
                    val user = actions
                        .filter { it.value.get() != 0L }
                        .minBy { it.value.get() }
                        ?.key ?: return@synchronized null
                    user.apply {
                        actions[this]!!.set(0)
                    }
                }
                if (chat == null) {
                    delay(100)
                    continue
                }
                val queue = updates[chat] ?: continue
                val update = queue.poll() ?: continue
                for ((filter, handler) in filters)
                    if (filter(update))
                        launch {
                            handler(HandlerContext(this, chat, queue), update)
                            if (updates[chat]!!.isNotEmpty())
                                actions[chat]!!.set(System.nanoTime())
                            else {
                                actions.remove(chat)
                                updates.remove(chat)
                            }
                         }
            }
        }
    }
}

inline fun botconfig(username: String, token: String, threads: Int = 3, crossinline init: Bot.() -> Unit) = Bot(username, token, threads).apply(init)

inline fun testconfig(threads: Int = 2, crossinline init: Bot.() -> Unit) = botconfig("", "", threads, init)

fun Bot.pooling() {
    ApiContextInitializer.init()
    val pooling = Pooling(this)
    engine = PoolingBasedEngine(pooling)
    TelegramBotsApi().registerBot(pooling)
}

fun Bot.mock() = TestEngine(this).apply { this@mock.engine = this }

fun TestEngine.test(run: TestEngine.EmulatedChat.() -> Unit) {
    thread {
        newChat().run(run)
    }
}

fun Bot.test(run: TestEngine.EmulatedChat.() -> Unit) = mock().test(run)