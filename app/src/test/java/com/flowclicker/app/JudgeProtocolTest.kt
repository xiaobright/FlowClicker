package com.flowclicker.app

import com.flowclicker.app.ai.*
import com.flowclicker.app.engine.Task
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class JudgeProtocolTest {
    private val candidates = listOf(JudgeCandidate(7, 2, "重连", "连接中断且有重试按钮"))
    private val settings = JudgeSettings()
    private fun response(action: String = "task_7", confidence: Double = 0.97, sufficient: Double = 0.98,
                         scene: String = "connection_problem"): String = buildJsonObject {
        put("model", "self-hosted-selector-v1")
        putJsonObject("usage") { put("input_tokens", 123) }
        putJsonObject("answers") {
            putJsonObject("action") {
                put("type", "choice"); put("choice", action); put("confidence", confidence)
                putJsonObject("probabilities") {
                    put("task_7", if (action == "task_7") 0.99 else 0.01)
                    put("escalate", if (action == "escalate") 0.99 else 0.01)
                }
            }
            putJsonObject("scene") {
                put("type", "choice"); put("choice", scene); put("confidence", 0.99)
                putJsonObject("probabilities") { JudgeProtocol.scenes.keys.forEach { put(it, if (it == scene) 1.0 else 0.0) } }
            }
            putJsonObject("sufficient") { put("type", "noul"); put("noul", sufficient) }
        }
    }.toString()

    @Test fun oldSettingsAndTasksKeepFeatureOff() {
        val s = Json.decodeFromString<AiSettings>("""{"enabled":true,"model":"old"}""")
        assertFalse(s.judge.enabled); assertTrue(s.judge.observeOnly)
        assertEquals("", Json.decodeFromString<Task>("""{"id":1,"name":"old"}""").recoveryHint)
    }
    @Test fun arbitraryCompatibleBaseAndFullEndpointAreSupported() {
        val s = settings.copy(enabled = true, baseUrl = "http://192.168.1.8:8080/custom/v1/", model = "open-model", apiKey = "")
        s.validate()
        assertEquals("http://192.168.1.8:8080/custom/v1/systemone", s.endpoint())
        assertEquals("https://host.example/api/systemone", s.copy(baseUrl = "https://host.example/api/systemone/").endpoint())
    }
    @Test fun invalidAddressAndThresholdAreRejected() {
        listOf("file:///secret", "https://key@host/v1", "https://host/v1?key=secret", "https://host/#x").forEach { url ->
            assertThrows(IllegalArgumentException::class.java) { settings.copy(enabled = true, baseUrl = url).validate() }
        }
        assertThrows(IllegalArgumentException::class.java) { settings.copy(minConfidence = Double.NaN).validate() }
    }
    @Test fun requestUsesTypedProtocolAndDoesNotIncludeSecrets() {
        val req = JudgeProtocol.request(settings.copy(apiKey = "secret"), "idle", "timeout", "连接失败，请重试", candidates, JsonNull)
        assertEquals(setOf("state", "model", "questions"), req.keys)
        val choices = req.getValue("questions").jsonObject.getValue("action").jsonObject.getValue("criteria").jsonObject
        assertEquals(setOf("escalate", "task_7"), choices.keys)
        assertFalse(req.toString().contains("secret"))
    }
    @Test fun explicitSelectionCanPassWhileLowScoresCannot() {
        val d = JudgeProtocol.parse(response(), candidates)
        assertEquals(candidates.single(), JudgeProtocol.selected(d, settings, candidates))
        assertNull(JudgeProtocol.selected(d.copy(confidence = 0.84), settings, candidates))
        assertNull(JudgeProtocol.selected(d.copy(sufficient = 0.84), settings, candidates))
        assertNull(JudgeProtocol.selected(d.copy(scene = "loading"), settings, candidates))
        assertNull(JudgeProtocol.selected(d.copy(scene = "other"), settings, candidates))
        assertNull(JudgeProtocol.selected(d.copy(action = "escalate"), settings, candidates))
    }
    @Test fun fabricatedActionOrBrokenProbabilityDistributionIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { JudgeProtocol.parse(response(action = "task_999"), candidates) }
        assertThrows(IllegalArgumentException::class.java) { JudgeProtocol.parse(response(confidence = 1.1), candidates) }
        assertThrows(IllegalArgumentException::class.java) { JudgeProtocol.parse(response(sufficient = -0.1), candidates) }
        assertThrows(IllegalArgumentException::class.java) { JudgeProtocol.parse(response().replace("0.99", "0.1"), candidates) }
        assertThrows(IllegalArgumentException::class.java) { JudgeProtocol.parse(response().replace("\"task_7\":0.99", "\"task_7\":0.01"), candidates) }
    }
    @Test fun missingTypedFieldsDoNotBecomeDefaultSuccess() {
        assertThrows(Exception::class.java) { JudgeProtocol.parse("""{"answers":{}}""", candidates) }
        assertThrows(Exception::class.java) { JudgeProtocol.parse(response().replace("\"noul\":0.98", "\"value\":0.98"), candidates) }
    }
    @Test fun oversizedInputsAreNotSilentlyTruncatedIntoDecisions() {
        assertThrows(IllegalArgumentException::class.java) { JudgeProtocol.request(settings, "idle", "", "x".repeat(6001), candidates, JsonNull) }
        assertThrows(IllegalArgumentException::class.java) { JudgeProtocol.request(settings, "idle", "", "text", emptyList(), JsonNull) }
    }
}
