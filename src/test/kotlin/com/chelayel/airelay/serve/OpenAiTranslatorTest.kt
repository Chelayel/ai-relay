package com.chelayel.airelay.serve

import com.chelayel.airelay.gemini.api.ModelTurn
import com.chelayel.airelay.gemini.api.Part
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenAiTranslatorTest {

    private fun json(s: String): JsonObject = JsonParser.parseString(s).asJsonObject

    @Test
    fun `messages become contents with system apart and tool results answered by name`() {
        val r = OpenAiTranslator.toGemini(json("""
            {"model":"gemini-3.8-flash","messages":[
              {"role":"system","content":"Be brief."},
              {"role":"user","content":[{"type":"text","text":"Read it"},{"type":"image_url","image_url":{"url":"data:image/png;base64,AAAA"}}]},
              {"role":"assistant","content":null,"tool_calls":[{"id":"call_1","type":"function","function":{"name":"read_file","arguments":"{\"path\":\"a.kt\"}"}}]},
              {"role":"tool","tool_call_id":"call_1","content":"fun a() {}"}
            ]}
        """), SignatureCache())
        assertEquals("Be brief.", r.system)
        assertEquals(listOf("user", "model", "user"), r.contents.map { it.role })
        assertEquals(listOf(Part.Text("Read it"), Part.InlineData("image/png", "AAAA")), r.contents[0].parts)
        val call = r.contents[1].parts.single() as Part.FunctionCall
        assertEquals("read_file", call.name)
        assertEquals("a.kt", call.args.get("path").asString)
        val result = r.contents[2].parts.single() as Part.FunctionResponse
        assertEquals("read_file", result.name)
        assertEquals("fun a() {}", result.response.get("content").asString)
    }

    @Test
    fun `a kept signature goes back on its call, and the placeholder only when none was kept`() {
        val cache = SignatureCache()
        val args = json("""{"path":"a.kt"}""")
        cache.put("call_1", "read_file", args, "sig-1")
        fun assistant(id: String) = json("""
            {"model":"gemini-3.8-flash","messages":[{"role":"user","content":"x"},
             {"role":"assistant","tool_calls":[{"id":"$id","type":"function","function":{"name":"read_file","arguments":"{\"path\":\"a.kt\"}"}},
                                               {"id":"other","type":"function","function":{"name":"list","arguments":"{}"}}]}]}
        """)
        val kept = OpenAiTranslator.toGemini(assistant("call_1"), cache).contents[1].parts.map { (it as Part.FunctionCall).thoughtSignature }
        assertEquals(listOf("sig-1", null), kept)
        // Unknown id, same name and arguments: found by the call itself (Ollama sends no ids).
        val byCall = OpenAiTranslator.toGemini(assistant("unknown"), cache).contents[1].parts.map { (it as Part.FunctionCall).thoughtSignature }
        assertEquals(listOf("sig-1", null), byCall)
        val none = OpenAiTranslator.toGemini(assistant("x"), SignatureCache()).contents[1].parts.map { (it as Part.FunctionCall).thoughtSignature }
        assertEquals(listOf(SignatureCache.PLACEHOLDER, null), none)
    }

    @Test
    fun `gemini 2_5 gets no placeholder`() {
        val r = OpenAiTranslator.toGemini(json("""
            {"model":"gemini-2.5-pro","messages":[{"role":"user","content":"x"},
             {"role":"assistant","tool_calls":[{"id":"c","type":"function","function":{"name":"f","arguments":"{}"}}]}]}
        """), SignatureCache())
        assertNull((r.contents[1].parts.single() as Part.FunctionCall).thoughtSignature)
    }

    @Test
    fun `issued calls keep their signature for the way back`() {
        val cache = SignatureCache()
        val args = json("""{"q":"x"}""")
        val calls = OpenAiTranslator.issueCalls(ModelTurn(listOf(Part.FunctionCall("search", args, "sig-9")), "STOP", null), cache)
        assertTrue(calls.single().id.startsWith("call_"))
        assertEquals("sig-9", cache.get(calls.single().id, "x", JsonObject()))
        // Arguments re-serialised in another order still match.
        assertEquals("sig-9", cache.get(null, "search", json("""{"q":"x"}""")))
    }

    @Test
    fun `sampling settings and tool choice are passed through`() {
        val r = OpenAiTranslator.toGemini(json("""
            {"model":"m","messages":[{"role":"user","content":"x"}],"temperature":0.2,"max_tokens":100,"stop":"END",
             "tools":[{"type":"function","function":{"name":"f","parameters":{"type":"object","properties":{"a":{"type":"string"}}}}}],
             "tool_choice":{"type":"function","function":{"name":"f"}},"stream":true,"stream_options":{"include_usage":true}}
        """), SignatureCache())
        val gen = r.extras!!.getAsJsonObject("generationConfig")
        assertEquals(0.2, gen.get("temperature").asDouble)
        assertEquals(100, gen.get("maxOutputTokens").asInt)
        assertEquals("END", gen.getAsJsonArray("stopSequences")[0].asString)
        val fc = r.extras!!.getAsJsonObject("toolConfig").getAsJsonObject("functionCallingConfig")
        assertEquals("ANY", fc.get("mode").asString)
        assertEquals("f", fc.getAsJsonArray("allowedFunctionNames")[0].asString)
        assertTrue(r.stream); assertTrue(r.includeUsage)
    }

    @Test
    fun `a request without a model or messages is refused`() {
        assertFailsWith<BadRequest> { OpenAiTranslator.toGemini(json("""{"messages":[]}"""), SignatureCache()) }
        assertFailsWith<BadRequest> { OpenAiTranslator.toGemini(json("""{"model":"m"}"""), SignatureCache()) }
    }

    @Test
    fun `schemas are cut down to what Gemini accepts`() {
        val cleaned = OpenAiTranslator.cleanSchema(json("""
            {"${'$'}schema":"http://json-schema.org/draft-07/schema#","type":"object","additionalProperties":false,
             "properties":{
               "path":{"type":["string","null"],"format":"uri","description":"where"},
               "mode":{"const":"fast"},
               "level":{"enum":[1,2,3],"type":"integer"},
               "opts":{"${'$'}ref":"#/${'$'}defs/Opts"},
               "either":{"oneOf":[{"type":"string"},{"type":"null"}]},
               "tags":{"type":"array"}
             },
             "required":["path","ghost"],
             "${'$'}defs":{"Opts":{"type":"object","properties":{"deep":{"type":"boolean"}}}}}
        """))!!
        assertFalse(cleaned.has("\$schema")); assertFalse(cleaned.has("additionalProperties")); assertFalse(cleaned.has("\$defs"))
        val p = cleaned.getAsJsonObject("properties")
        assertEquals("string", p.getAsJsonObject("path").get("type").asString)
        assertTrue(p.getAsJsonObject("path").get("nullable").asBoolean)
        assertFalse(p.getAsJsonObject("path").has("format"))
        assertEquals("fast", p.getAsJsonObject("mode").getAsJsonArray("enum")[0].asString)
        assertFalse(p.getAsJsonObject("level").has("enum"))
        assertEquals("boolean", p.getAsJsonObject("opts").getAsJsonObject("properties").getAsJsonObject("deep").get("type").asString)
        assertEquals("string", p.getAsJsonObject("either").get("type").asString)
        assertTrue(p.getAsJsonObject("either").get("nullable").asBoolean)
        assertEquals("string", p.getAsJsonObject("tags").getAsJsonObject("items").get("type").asString)
        assertEquals(listOf("path"), cleaned.getAsJsonArray("required").map { it.asString })
    }

    @Test
    fun `a tool without arguments has no schema`() {
        assertNull(OpenAiTranslator.cleanSchema(json("""{"type":"object","properties":{}}""")))
        assertNull(OpenAiTranslator.cleanSchema(null))
    }

    @Test
    fun `finish reasons map to OpenAI's`() {
        fun turn(reason: String?) = ModelTurn(listOf(Part.Text("x")), reason, null)
        assertEquals("stop", OpenAiTranslator.finishReason(turn("STOP"), false))
        assertEquals("tool_calls", OpenAiTranslator.finishReason(turn("STOP"), true))
        assertEquals("length", OpenAiTranslator.finishReason(turn("MAX_TOKENS"), false))
        assertEquals("content_filter", OpenAiTranslator.finishReason(turn("SAFETY"), false))
    }

    @Test
    fun `ollama chat is rewritten into the OpenAI shape with made-up ids`() {
        val o = OllamaApi.toOpenAi(json("""
            {"model":"gemini-3.8-flash","messages":[
              {"role":"user","content":"look","images":["iVBORw0K"]},
              {"role":"assistant","content":"","tool_calls":[{"function":{"name":"read","arguments":{"p":"a"}}}]},
              {"role":"tool","tool_name":"read","content":"text"}
            ],"options":{"num_predict":50}}
        """))
        val msgs = o.getAsJsonArray("messages")
        assertEquals("data:image/png;base64,iVBORw0K", msgs[0].asJsonObject.getAsJsonArray("content")[1].asJsonObject.getAsJsonObject("image_url").get("url").asString)
        val id = msgs[1].asJsonObject.getAsJsonArray("tool_calls")[0].asJsonObject.get("id").asString
        assertEquals(id, msgs[2].asJsonObject.get("tool_call_id").asString)
        assertEquals(50, o.get("max_tokens").asInt)
        assertTrue(o.get("stream").asBoolean)
    }

    @Test
    fun `display names read like the model`() {
        assertEquals("Gemini 3.8 Flash", ModelInfo.displayName("gemini-3.8-flash"))
        assertEquals("Gemini 3.1 Pro (preview)", ModelInfo.displayName("gemini-3.1-pro-preview"))
        assertEquals("Gemini 3.5 Flash-Lite", ModelInfo.displayName("gemini-3.5-flash-lite"))
        assertEquals("corp-model", ModelInfo.displayName("corp-model"))
    }
}
