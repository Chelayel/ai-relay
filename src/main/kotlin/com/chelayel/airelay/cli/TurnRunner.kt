package com.chelayel.airelay.cli

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Runs a turn where Ctrl-C can reach it.
 *
 * `Agent.cancel()` asks a turn to stop; it cannot make it. The turn is blocked
 * somewhere — waiting on a model that has not sent its first byte, inside a
 * TLS close, in a one-second poll of a browser page — and notices the flag only
 * when that returns. With the turn on the prompt's own thread, the prompt came
 * back when the *agent* was done stopping, and Ctrl-C felt ignored for however
 * long that was.
 *
 * So the turn gets a thread of its own and the caller waits on a latch instead.
 * Ctrl-C says "stopped" at once, mutes the sink, releases the latch, and the
 * prompt is back; the agent finishes letting go in the background. It also
 * interrupts the turn's thread, which is what actually wakes a sleep, a future
 * or an `HttpClient.send` — the flag alone wakes nothing.
 *
 * The one thing that must not happen is two turns in one agent at once: they
 * share a conversation. [run] therefore waits out a turn that is still
 * unwinding before starting the next, and says that it is doing so.
 */
class TurnRunner(private val agent: Agent, private val sink: InterruptibleSink) {

    private class Turn(val thread: Thread, val released: CountDownLatch) { @Volatile var canceller: Thread? = null }

    @Volatile
    private var current: Turn? = null

    /** When a turn last started or ended; what the idle reaper measures from. */
    @Volatile var lastActivity: Long = System.currentTimeMillis()
        private set

    val isRunning: Boolean get() = current?.let { it.thread.isAlive && it.released.count > 0 } == true

    /** Run one turn; returns when it ends or is interrupted, whichever is first. */
    /** Held while a turn starts and while the idle reaper releases resources, so neither straddles the other. */
    val idleLock = Any()

    /** Runs [release] unless a turn is running or started less than [quietMs] ago. */
    fun ifIdle(quietMs: Long, release: () -> Unit) = synchronized(idleLock) {
        if (isRunning || System.currentTimeMillis() - lastActivity < quietMs) return@synchronized
        release()
    }

    fun run(prompt: String, attachments: List<Attachment> = emptyList()) {
        awaitPrevious()
        synchronized(idleLock) { lastActivity = System.currentTimeMillis() }
        val released = CountDownLatch(1)
        sink.userPrompt(prompt)
        sink.beginTurn()
        val thread = Thread({
            try {
                agent.send(prompt, sink, attachments)
            } catch (e: Throwable) {
                sink.error(e.message ?: e.toString())
            } finally {
                // An agent that threw never said so; without this the status
                // row would keep spinning over the next prompt. And whatever the
                // sink does, the latch must open or the next turn waits forever.
                try { sink.turnComplete() } catch (_: Throwable) {} finally {
                    lastActivity = System.currentTimeMillis()
                    released.countDown()
                }
            }
        }, "airelay-turn")
        thread.isDaemon = true
        synchronized(idleLock) { current = Turn(thread, released) }
        thread.start()
        val editor = Stdin.editor
        if (editor != null) editor.holdingTypeAhead { released.await() } else released.await()
    }

    /**
     * Ctrl-C. False when there is no turn left to stop, which the caller reads
     * as "exit" — that includes a second Ctrl-C while [run] is waiting out a
     * turn that will not let go, so there is always a way out.
     */
    fun interrupt(): Boolean {
        val turn = current ?: return false
        if (!turn.thread.isAlive) return false
        if (turn.released.count == 0L) return false
        sink.stop("Interrupted")
        turn.released.countDown()
        // Off the signal thread: cancel() closes sockets and kills process
        // trees, and neither is guaranteed to be quick.
        turn.canceller = Thread({
            runCatching { agent.cancel() }
            turn.thread.interrupt()
        }, "airelay-cancel").apply { isDaemon = true; start() }
        return true
    }

    private fun awaitPrevious() {
        val turn = current ?: return
        // The cancel runs on its own thread; it must have landed on the old turn before a new
        // one starts, or it lands on the new one instead.
        turn.canceller?.let { runCatching { it.join(3_000) } }
        if (!turn.thread.isAlive) return
        turn.thread.join(QUIET_GRACE_MILLIS)
        if (!turn.thread.isAlive) return
        println(Ansi.dim("Waiting for the interrupted turn to let go…"))
        while (turn.thread.isAlive) {
            turn.thread.join(1_000)
            // A thread can swallow one interrupt and block again.
            if (turn.thread.isAlive) turn.thread.interrupt()
        }
    }

    fun close() {
        current?.let { turn ->
            if (turn.thread.isAlive) {
                runCatching { agent.cancel() }
                turn.thread.interrupt()
                turn.thread.join(TimeUnit.SECONDS.toMillis(2))
            }
        }
    }

    private companion object {
        const val QUIET_GRACE_MILLIS = 300L
    }
}
