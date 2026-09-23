package com.chelayel.airelay.cli

import com.chelayel.airelay.agent.PermissionDecision

/**
 * `airelay demo` — a backend with no model behind it.
 *
 * It exists for the same reason `airelay web` and `airelay mcp` do: to prove
 * plumbing without spending a turn. Here the plumbing is the terminal itself —
 * the editor's keys, the renderer, a permission question asked mid-turn, and a
 * Ctrl-C landing on a turn that is slow to let go — none of which can be
 * exercised against a real backend without credentials and a bill.
 */
class DemoAgent(
    private val confirm: (name: String, summary: String, detail: String) -> PermissionDecision,
) : Agent {

    @Volatile private var cancelled = false

    override fun describe() = "Demo · no model, canned replies"
    private val id = java.util.UUID.randomUUID().toString()
    override fun sessionId(): String = id
    override fun models(): List<String> = listOf("demo-1", "demo-2")
    override fun currentModel(): String = "demo-1"

    override fun cancel() { cancelled = true }

    override fun send(prompt: String, sink: Sink, attachments: List<Attachment>) {
        cancelled = false
        if (attachments.isNotEmpty()) sink.info("Received ${attachments.size} attachment(s): " + attachments.joinToString { "${it.name} (${it.mimeType}, ${it.dataBase64.length * 3 / 4} bytes)" })
        sink.thinking("The user said ${prompt.lines().size} line(s); replying from a script.")
        stream(sink, REPLY.replace("{prompt}", prompt.lines().joinToString(" ⏎ ")))
        if (!cancelled) {
            sink.toolUse("runCommand", "sleep 5 && echo done")
            when (confirm("runCommand", "sleep 5 && echo done", "sleep 5 && echo done")) {
                PermissionDecision.DENY -> sink.toolResult("Denied by user.", isError = true)
                else -> {
                    // Deliberately deaf to the cancel flag, like a socket read
                    // that has not returned yet: only an interrupt wakes it.
                    runCatching { Thread.sleep(5_000) }
                        .onSuccess { sink.toolResult("exit 0\ndone", isError = false) }
                }
            }
        }
        if (!cancelled) stream(sink, "That is everything. Try **Ctrl-C** during the next turn.\n")
        sink.turnComplete()
    }

    /** A few characters at a time, the way a model's tokens arrive. */
    private fun stream(sink: Sink, text: String) {
        for (chunk in text.chunked(6)) {
            if (cancelled) return
            sink.assistantText(chunk)
            if (runCatching { Thread.sleep(12) }.isFailure) return
        }
    }

    private companion object {
        val REPLY = """
            ## What you sent
            > {prompt}

            A reply with **bold**, *italic* and `inline code`, a [link](https://example.com/docs),
            and a list:
            - first item
            - second item, with `code`
              - nested

            ```kotlin
            fun main() {
                println("**not bold** in here")
            }
            ```

            Next comes a tool call that asks for permission.

        """.trimIndent()
    }
}
