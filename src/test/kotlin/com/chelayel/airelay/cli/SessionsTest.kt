package com.chelayel.airelay.cli

import com.google.gson.JsonObject
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SessionsTest {

    private val home: File = Files.createTempDirectory("airelay-home").toFile()
    private val repo: File = Files.createTempDirectory("airelay-repo").toFile()

    @AfterTest fun cleanUp() { home.deleteRecursively(); repo.deleteRecursively() }

    private class FakeAgent(var id: String?) : Agent {
        override fun send(prompt: String, sink: Sink, attachments: List<Attachment>) {}
        override fun describe() = "fake"
        override fun sessionId() = id
        override fun currentModel() = "m1"
    }

    @Test fun `a turn is recorded, indexed under the workspace, and listed newest first`() {
        val sessions = Sessions(repo, home)
        val agent = FakeAgent("s1")
        val rec = RecordingSink(object : InterruptibleSink { override fun beginTurn() {}; override fun stop(message: String) {}; override fun assistantText(text: String) {} }, sessions, agent, "gemini")
        rec.userPrompt("Fix the build\nplease"); rec.beginTurn(); rec.assistantText("done"); rec.turnComplete()
        val list = sessions.list()
        assertEquals(1, list.size)
        assertEquals("Fix the build", list[0].title)
        assertEquals("gemini", list[0].backend)
        assertEquals("m1", list[0].model)
        val events = sessions.transcript("s1")
        assertEquals(listOf("user", "text"), events.map { it.get("type").asString })
        assertTrue(sessions.dir.path.startsWith(File(home, ".airelay/sessions").path))
    }

    @Test fun `events before the id is known are kept and written once it is`() {
        val sessions = Sessions(repo, home)
        val agent = FakeAgent(null)
        val rec = RecordingSink(object : InterruptibleSink { override fun beginTurn() {}; override fun stop(message: String) {}; override fun assistantText(text: String) {} }, sessions, agent, "claude")
        rec.userPrompt("hello"); rec.beginTurn(); rec.assistantText("hi")
        assertTrue(sessions.list().isEmpty())
        agent.id = "late"
        rec.turnComplete()
        assertEquals(listOf("user", "text"), sessions.transcript("late").map { it.get("type").asString })
    }

    @Test fun `find accepts an id prefix when it is unambiguous`() {
        val sessions = Sessions(repo, home)
        sessions.upsert(Sessions.Entry("abcdef", "claude", "one", null, 2))
        sessions.upsert(Sessions.Entry("abzzzz", "claude", "two", null, 1))
        assertEquals("one", sessions.find("abcd")?.title)
        assertEquals(null, sessions.find("ab"))
        sessions.append("x", JsonObject().apply { addProperty("type", "user") })
        assertEquals(1, sessions.transcript("x").size)
    }
}
