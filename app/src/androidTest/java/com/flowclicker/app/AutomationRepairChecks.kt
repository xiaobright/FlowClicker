package com.flowclicker.app

import android.app.Instrumentation
import android.app.UiAutomation
import android.graphics.Bitmap
import android.os.Bundle
import android.provider.Settings
import com.flowclicker.app.ai.*
import com.flowclicker.app.core.GestureDispatcher
import com.flowclicker.app.core.ScreenControl
import com.flowclicker.app.engine.MonitoringEngine
import kotlinx.coroutines.*
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.*

/** Actual dispatcher + loopback HTTP; synthetic OCR avoids triggering any user task. */
internal class AutomationRepairChecks(private val instrumentation: Instrumentation) {
    private fun report(message: String) = instrumentation.sendStatus(0,
        Bundle().apply { putString("stream", "$message\n") })

    fun run() = runBlocking {
        val settings = AiStores.loadSettings()
        val rules = AiStores.loadRules()
        val frame = MonitoringEngine.frameProvider
        val recognize = MonitoringEngine.textRecognizer
        val before = MonitoringEngine.tasksSnapshot()
        try {
            WakeDispatcher.stopAll()
            MonitoringEngine.frameProvider = { Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888) }
            MonitoringEngine.textRecognizer = { _, _ -> "" }
            AiStores.saveRules(listOf(WakeRule("idle", timeoutMs = 20000)))
            // am instrument force-stops the target, which also unbinds its accessibility service.
            // Re-enable the already-authorized component, preserving every other enabled service.
            reconnectAccessibility(instrumentation)
            suspend fun prepare(server: DelayedFakeAi) {
                WakeDispatcher.stopAll()
                withTimeout(10000) { while (ScreenControl.owner != null) delay(50) }
                AiStores.saveSettings(settings.copy(enabled = true, vlm = false,
                    baseUrl = "http://127.0.0.1:${server.port}/v1", model = "fake", apiKey = ""))
                WakeDispatcher.onSettingsChanged()
            }
            suspend fun received(server: DelayedFakeAi, count: Int = 1) =
                withTimeout(30000) { while (server.requests.get() < count) delay(50) }
            fun unchanged() {
                check(before == MonitoringEngine.tasksSnapshot()) {
                    "task snapshot differs: before=${before.map { it.id }}, after=${MonitoringEngine.tasksSnapshot().map { it.id }}"
                }
            }

            DelayedFakeAi().use { server ->
                prepare(server)
                check(WakeDispatcher.manual("回归 S01"))
                received(server)
                WakeDispatcher.stopAll()
                server.release.countDown()
                delay(60000)
                check(server.requests.get() == 1 && !MonitoringEngine.isMonitoring)
                unchanged()
                report("S01 PASS: late tool ignored; 60s no revival; requests=1")
            }
            DelayedFakeAi().use { server ->
                prepare(server)
                check(WakeDispatcher.manual("回归 S02 old"))
                received(server)
                WakeDispatcher.stopAll()
                val lastLog = AiStores.loadLog().lastOrNull()
                check(WakeDispatcher.manual("回归 S02 new"))
                received(server, 2)
                server.release.countDown()
                // The production log is capped at 30 entries; its size need not grow.
                withTimeout(10000) { while (AiStores.loadLog().lastOrNull() == lastLog) delay(100) }
                delay(1000)
                check(server.requests.get() == 2)
                unchanged()
                report("S02 PASS: new session completed; old creation ignored; requests=2")
            }
            DelayedFakeAi().use { server ->
                prepare(server)
                MonitoringEngine.start()
                received(server) // Real 20s idle watcher, not a manually fabricated event.
                WakeDispatcher.stopAll()
                server.release.countDown()
                delay(60000)
                check(server.requests.get() == 1 && !MonitoringEngine.isMonitoring)
                WakeDispatcher.resumeAutomation()
                MonitoringEngine.start()
                delay(15000)
                check(server.requests.get() == 1) { "old idle event replayed on restart" }
                WakeDispatcher.stopAll()
                unchanged()
                report("S03 PASS: idle cancellation + 60s stopped + 15s restart with no replay")
            }
            DelayedFakeAi().use { server ->
                prepare(server)
                MonitoringEngine.start()
                check(WakeDispatcher.manual("回归 S04"))
                received(server)
                AiStores.saveSettings(AiStores.loadSettings().copy(enabled = false))
                WakeDispatcher.onSettingsChanged()
                server.release.countDown()
                delay(60000)
                check(MonitoringEngine.isMonitoring && server.requests.get() == 1)
                WakeDispatcher.stopAll()
                delay(60000)
                check(!MonitoringEngine.isMonitoring && server.requests.get() == 1)
                unchanged()
                report("S04 PASS: AI-off preserves engine; all-stop keeps it off; 60s each")
            }
            java.io.File(instrumentation.targetContext.cacheDir, "repair-mock-passed")
                .writeText(repairApkHash(instrumentation))
        } finally {
            WakeDispatcher.stopAll()
            MonitoringEngine.frameProvider = frame
            MonitoringEngine.textRecognizer = recognize
            AiStores.saveSettings(settings)
            AiStores.saveRules(rules)
            // Do not resume: restoring settings must never restart automation.
        }
    }
}

internal suspend fun reconnectAccessibility(instrumentation: Instrumentation) {
    val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    val resolver = instrumentation.targetContext.contentResolver
    val services = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
    check(!services.isNullOrBlank() && services.contains("com.flowclicker.app")) {
        "enable the app accessibility service before running this suite"
    }
    automation.adoptShellPermissionIdentity("android.permission.WRITE_SECURE_SETTINGS")
    try {
        Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            services.split(':').filterNot { it.startsWith("com.flowclicker.app/") }.joinToString(":"))
        delay(300)
        Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, services)
    } finally { automation.dropShellPermissionIdentity() }
    withTimeout(15000) { while (!GestureDispatcher.isReady) delay(100) }
}

internal fun repairApkHash(instrumentation: Instrumentation): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    java.io.File(instrumentation.targetContext.applicationInfo.sourceDir).inputStream().use { input ->
        val buffer = ByteArray(65536)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private class DelayedFakeAi : AutoCloseable {
    private val server = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
    val port = server.localPort
    val requests = AtomicInteger()
    val release = CountDownLatch(1)
    private val clients = java.util.concurrent.ConcurrentHashMap.newKeySet<Socket>()
    init {
        Thread({
            while (!server.isClosed) {
                val client = try { server.accept() } catch (_: Exception) { break }
                clients.add(client)
                Thread({
                    try {
                        client.use {
                            val input = client.getInputStream()
                            val header = java.io.ByteArrayOutputStream()
                            var tail = 0
                            while (tail != 0x0d0a0d0a) {
                                val b = input.read()
                                check(b >= 0)
                                header.write(b)
                                tail = (tail shl 8) or b
                            }
                            val length = header.toString("UTF-8").lineSequence()
                                .firstOrNull { it.startsWith("Content-Length:", true) }
                                ?.substringAfter(':')?.trim()?.toInt() ?: 0
                            var remaining = length
                            val buffer = ByteArray(8192)
                            while (remaining > 0) {
                                val n = input.read(buffer, 0, minOf(buffer.size, remaining))
                                check(n > 0)
                                remaining -= n
                            }
                            val ordinal = requests.incrementAndGet()
                            if (ordinal == 1) check(release.await(90, TimeUnit.SECONDS))
                            val content = if (ordinal == 1)
                                """{"tool":"upsert_task","args":{"task":{"id":0,"name":"回归·不应创建","enabled":false,"steps":[{"type":"wait","ms":1}]}}}"""
                            else """{"reply":"新会话正常完成"}"""
                            val response = buildJsonObject {
                                putJsonArray("choices") {
                                    add(buildJsonObject { putJsonObject("message") { put("content", content) } })
                                }
                            }.toString().toByteArray()
                            val output = client.getOutputStream()
                            output.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${response.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            output.write(response)
                            output.flush()
                        }
                    } catch (_: Exception) { /* A cancelled client is expected to close its socket. */ }
                    finally { clients.remove(client) }
                }, "repair-fake-client").apply { isDaemon = true; start() }
            }
        }, "repair-fake-ai").apply { isDaemon = true; start() }
    }
    override fun close() {
        release.countDown()
        server.close()
        clients.forEach { runCatching { it.close() } }
    }
}
