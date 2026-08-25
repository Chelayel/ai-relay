package com.chelayel.airelay.agent

import com.google.gson.JsonObject

/**
 * A tool the model may call, described in a backend-neutral way.
 *
 * The two transports advertise tools very differently — Gemini sends
 * `functionDeclarations` on the wire, while the Copilot web endpoint has no
 * function-calling at all and must be taught a JSON protocol in the system
 * prompt — so [Tools] describes them once, here, and each backend renders the
 * spec into whatever its connection understands.
 *
 * [parameters] is an OpenAPI-subset JSON schema object (`type: object` with
 * `properties`/`required`), which is what Gemini wants verbatim and what reads
 * clearly when inlined into a prompt.
 */
class ToolSpec(
    val name: String,
    val description: String,
    val parameters: JsonObject,
)

/** The user's answer to a permission prompt. */
enum class PermissionDecision { ALLOW_ONCE, ALLOW_ALWAYS, DENY }
