package com.flowclicker.app

import com.flowclicker.app.debug.DebugProtocol
import com.flowclicker.app.debug.DebugRequest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class DebugProtocolTest {
    private val id = "0123456789abcdef0123456789abcdef"
    private fun decode(raw: String) = DebugProtocol.decode(Base64.getEncoder().encodeToString(raw.toByteArray()))
    @Test fun chineseAndShellMetacharactersRoundTrip() {
        val text = "中文 ' \" ; $(whoami)\n第二行"
        val raw = buildJsonObject {
            put("id", id); put("command", "ai.wake")
            putJsonObject("args") { put("text", text) }
        }
        val r = decode(raw.toString())
        DebugProtocol.validate(r)
        assertEquals(text, r.args["text"]!!.jsonPrimitive.content)
    }
    @Test fun pathTraversalAndInvalidIdsRejected() {
        for (bad in listOf("../tasks", "/files/tasks.json", "", "A".repeat(32))) {
            assertFalse(DebugProtocol.validId(bad))
        }
        assertTrue(DebugProtocol.validId(id))
    }
    @Test fun oversizedPayloadRejected() {
        assertThrows(IllegalArgumentException::class.java) { DebugProtocol.decode("a".repeat(90001)) }
    }
    @Test fun unknownCommandsAndArgumentsRejected() {
        assertThrows(IllegalStateException::class.java) { DebugProtocol.validate(DebugRequest(id, "eval")) }
        assertThrows(IllegalArgumentException::class.java) {
            DebugProtocol.validate(DebugRequest(id, "status", buildJsonObject { put("apiKey", "not accepted") }))
        }
    }
    @Test fun wakeRequiresNonEmptyBoundedText() {
        for (text in listOf("", " ", "a".repeat(4001))) {
            assertThrows(IllegalArgumentException::class.java) {
                DebugProtocol.validate(DebugRequest(id, "ai.wake", buildJsonObject { put("text", text) }))
            }
        }
    }
    @Test fun successAndErrorEnvelopesAreUnambiguous() {
        assertTrue(DebugProtocol.response(id, JsonNull)["ok"]!!.jsonPrimitive.boolean)
        val failed = DebugProtocol.response(id, error = "no frame")
        assertFalse(failed["ok"]!!.jsonPrimitive.boolean)
        assertEquals(id, failed["id"]!!.jsonPrimitive.content)
        assertFalse(failed.containsKey("result"))
    }
    @Test fun invalidBase64AndExtraEnvelopeFieldsRejected() {
        assertThrows(IllegalArgumentException::class.java) { DebugProtocol.decode("not+base64!") }
        assertThrows(Exception::class.java) { decode("""{"id":"$id","command":"status","extra":1}""") }
    }
}
