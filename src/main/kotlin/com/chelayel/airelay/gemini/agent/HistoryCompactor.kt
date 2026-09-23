package com.chelayel.airelay.gemini.agent

import com.chelayel.airelay.gemini.api.Content
import com.chelayel.airelay.gemini.api.Part
import com.google.gson.JsonObject

/**
 * The Gemini API is stateless: every turn re-sends the whole history, and the
 * history keeps tool results verbatim — a file read can be 60 KB, and a long
 * migration has hundreds of them. Most of that is dead weight: once the model
 * has acted on a file's contents, later turns carry the effect and the text
 * itself is never looked at again. So all but the most recent tool results are
 * sent as a one-line stub. The stored history is untouched (a resumed session
 * still has everything); only what goes on the wire is compacted.
 */
object HistoryCompactor {

    /** Tool results at or under this many characters are cheap enough to keep as they are. */
    const val MIN_CHARS = 400

    /**
     * [history] with every `functionResponse` older than the last [keepVerbatim]
     * of them replaced by a stub, when it is larger than [minChars].
     */
    fun compact(history: List<Content>, keepVerbatim: Int, minChars: Int = MIN_CHARS): List<Content> {
        if (keepVerbatim < 0) return history
        // Count tool results from the end: the last `keepVerbatim` stay whole.
        var seen = 0
        val cutoff = IntArray(history.size) { -1 } // per content: index within its parts from which responses are old; -1 = none
        for (i in history.indices.reversed()) {
            val parts = history[i].parts
            var firstOld = -1
            for (j in parts.indices.reversed()) {
                if (parts[j] !is Part.FunctionResponse) continue
                seen++
                if (seen > keepVerbatim) firstOld = j
            }
            cutoff[i] = firstOld
        }
        if (cutoff.all { it < 0 }) return history
        return history.mapIndexed { i, content ->
            val firstOld = cutoff[i]
            if (firstOld < 0) content
            else Content(content.role, content.parts.mapIndexed { j, p ->
                if (j <= firstOld && p is Part.FunctionResponse && p.response.toString().length > minChars) stub(p) else p
            })
        }
    }

    private fun stub(p: Part.FunctionResponse): Part.FunctionResponse {
        val r = p.response
        val text = (r.get("error") ?: r.get("result"))?.takeIf { it.isJsonPrimitive }?.asString ?: r.toString()
        val head = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(120).orEmpty()
        val size = r.toString().length
        val summary = (if (r.has("error")) "error: " else "") + head + " … ($size chars, omitted from history — read again if needed)"
        return Part.FunctionResponse(p.name, JsonObject().apply { addProperty("summary", summary) })
    }
}
