package com.chelayel.airelay.serve

/**
 * What a client is told about a model it cannot ask Gemini about: context size,
 * output cap, whether it takes images, and a readable name. Copilot sizes the
 * history it sends from the context figure and offers agent mode only on a
 * model that calls tools, so a gateway id it knows nothing about still needs
 * an answer — a conservative one.
 */
object ModelInfo {
    data class Limits(val inputTokens: Int, val outputTokens: Int, val images: Boolean, val tools: Boolean = true)

    private val GEMINI = Limits(1_048_576, 65_536, images = true)
    private val UNKNOWN = Limits(128_000, 8_192, images = false)

    fun limits(id: String): Limits = if ("gemini" in id.lowercase()) GEMINI else UNKNOWN

    /** `gemini-3.1-pro-preview` → "Gemini 3.1 Pro (preview)"; an id that is not Gemini's stays as it is. */
    fun displayName(id: String): String {
        if (!id.lowercase().startsWith("gemini-")) return id
        val words = id.split('-')
        val preview = words.last().equals("preview", ignoreCase = true)
        val name = (if (preview) words.dropLast(1) else words).joinToString(" ") { w ->
            when {
                w.equals("lite", true) -> "Lite"
                w.first().isDigit() -> w
                else -> w.replaceFirstChar { it.uppercase() }
            }
        }.replace("Flash Lite", "Flash-Lite")
        return if (preview) "$name (preview)" else name
    }
}
