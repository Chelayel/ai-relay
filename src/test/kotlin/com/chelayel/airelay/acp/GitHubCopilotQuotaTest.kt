package com.chelayel.airelay.acp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GitHubCopilotQuotaTest {

    @Test
    fun `premium and chat allowances become windows, a plan without premium requests shows only chat`() {
        val q = AcpAgent.parseQuota("""{"copilot_plan":"individual","quota_reset_date_utc":"2026-11-01T00:00:00.000Z",
            "quota_snapshots":{"chat":{"entitlement":200,"remaining":150,"percent_remaining":75.0,"unlimited":false},
            "premium_interactions":{"entitlement":0,"remaining":0,"percent_remaining":0.0,"unlimited":false}}}""")!!
        assertEquals(listOf("chat"), q.windows.map { it.label })
        val chat = q.windows.single()
        assertEquals(50.0, chat.used); assertEquals(200.0, chat.limit); assertEquals(25.0, chat.usedPercent)
        assertEquals(java.time.Instant.parse("2026-11-01T00:00:00Z").toEpochMilli(), chat.resetsAt)
        assertEquals("plan: individual", q.note)
    }

    @Test
    fun `an unlimited allowance is said so, and anything unexpected is no quota rather than an error`() {
        val q = AcpAgent.parseQuota("""{"quota_snapshots":{"premium_interactions":{"unlimited":true}}}""")!!
        assertTrue(q.windows.single().unlimited)
        assertEquals("premium unlimited", q.summary())
        assertNull(AcpAgent.parseQuota("""<html>sign in</html>"""))
        assertNull(AcpAgent.parseQuota("""{"message":"Not Found"}"""))
    }
}
