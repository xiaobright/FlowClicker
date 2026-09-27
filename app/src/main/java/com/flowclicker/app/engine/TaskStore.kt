package com.flowclicker.app.engine

import android.content.Context
import android.util.Log
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import com.flowclicker.app.core.AtomicTextFile

/**
 * 任务持久化：JSON 存于应用私有目录。
 * 首次启动时写入一组示例任务，展示"常驻触发 → 启用标签组 → 标签任务执行"的结构。
 */
object TaskStore {

    private const val TAG = "TaskStore"
    private const val FILE_NAME = "tasks.json"

    private lateinit var file: File
    private val json = Json {
        classDiscriminator = "type"
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    fun init(context: Context) {
        file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) {
            saveAll(seed())
        }
    }

    @Synchronized fun loadAll(): MutableList<Task> {
        if (!file.exists()) return mutableListOf()
        return json.decodeFromString<List<Task>>(AtomicTextFile.read(file)).toMutableList()
    }

    @Synchronized fun saveAll(tasks: List<Task>) {
        AtomicTextFile.write(file, json.encodeToString<List<Task>>(tasks))
    }

    fun nextId(tasks: List<Task>): Long = (tasks.maxOfOrNull { it.id } ?: 0L) + 1L

    /**
     * 示例结构（对应典型云游戏挂机场景，坐标为占位值，需按实际画面编辑）：
     * 1. 重新登录：常驻监测，识别到被踢下线的界面文字后执行登录流程，
     *    最后"启用[登录后]"标签组；单次执行后停用，等待下次掉线时手动/再触发。
     * 2. 继续战斗：常驻监测，识别到即点击，执行后重新武装。
     * 3. 挂机关卡：标签[登录后]，平时不监测；登录流程走完被启用后，
     *    识别到关卡入口文字就执行进关卡的点击序列。
     */
    private fun seed(): List<Task> = listOf(
        Task(
            id = 1,
            name = "示例·重新登录（常驻）",
            tag = null,
            trigger = Trigger(keywords = listOf("重新登录", "重新连接")),
            steps = listOf(
                Step.Click(x = 540f, y = 1200f, maxOffsetPx = 8f, delayAfterMs = 2000, delayJitterMs = 500),
                Step.Wait(ms = 5000),
                Step.Click(x = 540f, y = 1800f, maxOffsetPx = 8f, delayAfterMs = 1500, delayJitterMs = 500),
                Step.EnableTagged(tag = "登录后"),
            ),
            enabled = false,
            loop = true,
        ),
        Task(
            id = 2,
            name = "示例·继续战斗（常驻）",
            tag = null,
            trigger = Trigger(keywords = listOf("继续战斗")),
            steps = listOf(
                Step.Click(x = 540f, y = 2000f, maxOffsetPx = 10f, pressMs = 80, delayAfterMs = 1000, delayJitterMs = 400),
            ),
            enabled = false,
            loop = true,
        ),
        Task(
            id = 3,
            name = "示例·进入挂机关卡",
            tag = "登录后",
            trigger = Trigger(keywords = listOf("关卡")),
            steps = listOf(
                Step.Click(x = 540f, y = 1500f, maxOffsetPx = 8f, delayAfterMs = 2000, delayJitterMs = 600),
                Step.Swipe(x1 = 540f, y1 = 1600f, x2 = 540f, y2 = 1000f, durationMs = 400, delayAfterMs = 1000),
            ),
            enabled = false,
            loop = true,
        ),
    )
}
