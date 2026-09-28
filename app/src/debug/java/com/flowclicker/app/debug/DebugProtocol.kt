package com.flowclicker.app.debug

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.util.Base64

@Serializable
internal data class DebugRequest(val id: String, val command: String, val args: JsonObject = JsonObject(emptyMap()))

/** Shell-safe transport, bounded payloads and allowlisted commands, with no arbitrary paths/code. */
internal object DebugProtocol {
    val json = Json { encodeDefaults = true }
    val commands = mapOf(
        "status" to emptySet(),
        "automation.stop" to emptySet(),
        "engine.start" to emptySet(),
        "engine.stop" to emptySet(),
        "capture.stop" to emptySet(),
        "ai.enabled" to setOf("enabled"),
        "ai.cancel" to emptySet(),
        "ai.wake" to setOf("text"),
        "tasks.list" to emptySet(),
        "tasks.get" to setOf("id"),
        "tasks.upsert" to setOf("task"),
        "tasks.delete" to setOf("id"),
        "tasks.enabled" to setOf("id", "enabled"),
        "tasks.run" to setOf("taskId"),
        "tasks.result" to setOf("taskId"),
        "rules.get" to emptySet(),
        "rules.set" to setOf("rules"),
        "screen.describe" to emptySet(),
        "screen.locate" to setOf("text", "region"),
        "frame.dump" to emptySet(),
    )
    fun validId(id: String) = id.matches(Regex("[a-f0-9]{32}"))
    fun decode(encoded: String): DebugRequest {
        require(encoded.length <= 90000) { "payload too large" }
        val bytes = Base64.getDecoder().decode(encoded)
        require(bytes.size <= 65536) { "payload too large" }
        val request = json.decodeFromString<DebugRequest>(bytes.toString(Charsets.UTF_8))
        require(validId(request.id)) { "invalid request id" }
        return request
    }
    fun validate(request: DebugRequest) {
        val keys = commands[request.command] ?: error("unknown command")
        require(request.args.keys.all { it in keys }) { "unknown argument" }
        if (request.command == "ai.wake") {
            val text = request.args["text"]?.jsonPrimitive?.content.orEmpty()
            require(text.isNotBlank() && text.length <= 4000) { "text must contain 1..4000 characters" }
        }
    }
    fun response(id: String, result: JsonElement? = null, error: String? = null) = buildJsonObject {
        put("id", id)
        put("ok", error == null)
        if (error != null) put("error", error) else put("result", result ?: JsonNull)
    }
}
