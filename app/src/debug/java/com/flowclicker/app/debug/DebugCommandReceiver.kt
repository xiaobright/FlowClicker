package com.flowclicker.app.debug

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import androidx.core.app.NotificationManagerCompat
import com.flowclicker.app.ai.*
import com.flowclicker.app.core.AtomicTextFile
import com.flowclicker.app.core.ScreenControl
import com.flowclicker.app.engine.MonitoringEngine
import com.flowclicker.app.service.RecordingService
import com.flowclicker.app.service.ScreenCaptureService
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.File

/**
 * Explicit ordered broadcast from adb shell; manifest DUMP permission excludes ordinary apps.
 * Never pass keys, task content or images through logcat/broadcast results.
 */
class DebugCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0 || !isOrderedBroadcast) return
        val request = try { DebugProtocol.decode(intent.getStringExtra("payload") ?: error("missing payload")) }
        catch (_: Exception) {
            setResult(2, "FLOWCLICKER:INVALID_REQUEST", null)
            return
        }
        val dir = File(context.cacheDir, "adb-debug").apply { mkdirs() }
        val output = File(dir, "${request.id}.json")
        // Persist the admission BEFORE doing anything. Reusing an ID never repeats a side effect,
        // even after a killed process or disconnected adb. Read the original result instead.
        try {
            if (!output.createNewFile()) {
                setResult(2, "FLOWCLICKER:ID_USED:${request.id}", null)
                return
            }
            AtomicTextFile.write(output, DebugProtocol.response(request.id, error = "IN_PROGRESS_OR_INTERRUPTED").toString())
        } catch (_: Exception) {
            setResult(2, "FLOWCLICKER:OUTPUT_UNAVAILABLE:${request.id}", null)
            return
        }
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            try {
                val result = withTimeout(8000) {
                    DebugProtocol.validate(request)
                    execute(context, dir, request)
                }
                AtomicTextFile.write(output, DebugProtocol.response(request.id, result).toString())
                pending.setResult(0, "FLOWCLICKER:${request.id}", null)
            } catch (e: Exception) {
                val message = if (e is TimeoutCancellationException) "TIMEOUT: inspect status before retrying"
                    else (e.message ?: e.javaClass.simpleName).take(500)
                // Inputs intentionally exclude credentials. No stack trace or request body is logged.
                runCatching { AtomicTextFile.write(output, DebugProtocol.response(request.id, error = message).toString()) }
                pending.setResult(1, "FLOWCLICKER:${request.id}", null)
            } finally { pending.finish() }
        }
    }

    private suspend fun execute(context: Context, dir: File, r: DebugRequest): JsonElement {
        val engine = MonitoringEngine
        fun stopped() {
            WakeDispatcher.stopAll()
            context.stopService(Intent(context, RecordingService::class.java))
        }
        val tool = when (r.command) {
            "tasks.get" -> "get_task"
            "tasks.upsert" -> "upsert_task"
            "tasks.delete" -> "delete_task"
            "tasks.enabled" -> "set_task_enabled"
            "tasks.run" -> "run_task_now"
            "tasks.result" -> "get_run_result"
            "rules.get" -> "get_wake_rules"
            "rules.set" -> "set_wake_rules"
            "screen.describe" -> "describe_screen"
            "screen.locate" -> "locate_text"
            else -> null
        }
        if (tool != null) {
            suspend fun call(): JsonElement {
                val result = Json.parseToJsonElement(AiTools.execute(tool, r.args, AiStores.loadSettings()).text)
                if (result is JsonObject && result.containsKey("error")) error(result["error"]!!.jsonPrimitive.content)
                return result
            }
            return if (r.command.startsWith("screen.")) ScreenControl.withOwner("debug") { call() } else call()
        }
        return when (r.command) {
            "status" -> buildJsonObject {
                put("engine", Json.parseToJsonElement(AiTools.engineStatus()))
                put("aiEnabled", AiStores.loadSettings().enabled)
                put("aiStatus", WakeDispatcher.status)
                put("captureRunning", ScreenCaptureService.isRunning)
                val frame = ScreenCaptureService.instance?.currentFrame()
                try {
                    put("frameAvailable", frame != null)
                    frame?.let { put("width", it.width); put("height", it.height) }
                } finally { frame?.recycle() }
                put("notificationsEnabled", NotificationManagerCompat.from(context).areNotificationsEnabled())
                put("captureChannelEnabled", context.getSystemService(NotificationManager::class.java)
                    .getNotificationChannel(ScreenCaptureService.CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE)
            }
            "automation.stop" -> { stopped(); accepted() }
            "engine.start" -> {
                check(engine.tasksSnapshot().any { it.enabled }) { "没有启用的任务" }
                engine.start()
                WakeDispatcher.resumeAutomation()
                accepted()
            }
            "engine.stop" -> { engine.stop(); accepted() }
            "capture.stop" -> {
                stopped()
                ScreenCaptureService.stop(context)
                accepted()
            }
            "ai.cancel" -> { WakeDispatcher.cancelAi(); accepted() }
            "ai.enabled" -> {
                val enabled = r.args["enabled"]?.jsonPrimitive?.boolean ?: error("enabled must be boolean")
                AiStores.saveSettings(AiStores.loadSettings().copy(enabled = enabled))
                WakeDispatcher.onSettingsChanged()
                accepted()
            }
            "ai.wake" -> {
                check(WakeDispatcher.manual(r.args["text"]!!.jsonPrimitive.content)) { "AI disabled, unconfigured or queue full" }
                accepted()
            }
            "tasks.list" -> DebugProtocol.json.encodeToJsonElement(engine.tasksSnapshot())
            "frame.dump" -> withContext(Dispatchers.IO) {
                val frame = ScreenCaptureService.instance?.currentFrame() ?: error("no valid capture frame")
                val file = File(dir, "${r.id}.png")
                try {
                    file.outputStream().use { check(frame.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                    buildJsonObject {
                        put("path", "cache/adb-debug/${r.id}.png")
                        put("width", frame.width); put("height", frame.height)
                    }
                } finally { frame.recycle() }
            }
            else -> error("unknown command")
        }
    }
    private fun accepted() = buildJsonObject { put("accepted", true) }
}
