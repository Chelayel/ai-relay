package com.chelayel.airelay.copilot.api

/**
 * How a Copilot turn actually reaches Copilot.
 *
 * There are two, because the site can work either way. Replaying a captured
 * HTTP request is the cheaper one and works where chat is plain HTTP. M365
 * Copilot streams chat over a WebSocket instead, where no request can be
 * replayed at all, so there the page itself is driven.
 *
 * The agentic loop above this — tools, permissions, history — is identical for
 * both, so it is written once against this interface.
 */
interface CopilotTransport : AutoCloseable {

    /** One line for the banner. */
    fun describe(model: String): String

    /** Prepare the transport. Called once, before the first turn. */
    fun start(status: (String) -> Unit) {}

    /**
     * Send one message and return the whole reply, streaming it to [onText].
     * [rawSample] on the result carries a diagnostic when nothing was readable.
     */
    fun send(message: String, model: String?, onText: (String) -> Unit): CopilotTurn = send(message, model, emptyList(), onText)

    /**
     * With images. The default cannot attach them and says so on [CopilotTurn.note];
     * browser mode drops them into the composer's file input.
     */
    fun send(message: String, model: String?, images: List<com.chelayel.airelay.cli.Attachment>, onText: (String) -> Unit): CopilotTurn

    /** Interrupt the in-flight turn. */
    fun cancel() {}

    /** Idle for a while: release what costs (browser mode closes its browser when nobody else uses it). */
    fun idle() {}

    /** True when a model can be chosen from the CLI rather than in the browser. */
    val canChooseModel: Boolean get() = false
}

/** Replays the captured HTTP request — the original backend. */
class ReplayTransport(private val config: CopilotConfig) : CopilotTransport {

    @Volatile private var client: CopilotClient? = null
    @Volatile private var conversationId: String? = config.conversationId

    override fun describe(model: String): String {
        val chosen = model.takeIf { it.isNotBlank() } ?: "site default model"
        return "Copilot · ${config.hostLabel()} · $chosen"
    }

    override fun send(message: String, model: String?, images: List<com.chelayel.airelay.cli.Attachment>, onText: (String) -> Unit): CopilotTurn {
        if (images.isNotEmpty()) return send(message, model, onText).let { it.copy(note = (it.note?.let { n -> "$n " } ?: "") + "Images cannot be attached over a replayed request; the text went alone.") }
        val c = CopilotClient(config)
        client = c
        val turn = c.send(message, model, conversationId, onText)
        conversationId = turn.conversationId
        return turn
    }

    override fun cancel() {
        client?.cancel()
    }

    override fun close() {
        cancel()
    }

    override val canChooseModel: Boolean get() = config.canChooseModel
}

/** Drives the Copilot page in a real browser — for chat that runs over a socket. */
class BrowserTransport(private val config: CopilotConfig) : CopilotTransport {

    private val browser = CopilotBrowser(
        url = config.url,
        attachPort = config.attachPort,
        inputSelector = config.inputSelector,
        quietMillis = config.quietMillis,
        turnTimeoutSeconds = config.turnTimeoutSeconds,
        headless = config.headless,
    )

    override fun describe(model: String): String =
        "Copilot · ${browser.host} · browser · model chosen in the window"

    override fun start(status: (String) -> Unit) = browser.start(status)

    override fun send(message: String, model: String?, images: List<com.chelayel.airelay.cli.Attachment>, onText: (String) -> Unit): CopilotTurn {
        val note = if (images.isEmpty()) null else browser.attachImages(images)
        val text = browser.ask(message, onText)
        return CopilotTurn(text, null, browser.diagnostics(), note)
    }

    override fun cancel() = browser.cancel()

    override fun idle() = browser.idleClose()

    override fun close() = browser.close()
}
