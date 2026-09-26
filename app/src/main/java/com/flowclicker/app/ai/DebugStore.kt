package com.flowclicker.app.ai

import android.graphics.Bitmap
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * debug 模式截图留存：任务每次执行点击/滑动前抓一帧存为 JPEG，
 * 每任务目录只保留最近 [MAX_FILES_PER_TASK] 张，供 AI 复盘读取。
 */
object DebugStore {

    private const val TAG = "DebugStore"
    private const val MAX_FILES_PER_TASK = 12
    private lateinit var root: File

    fun init(context: android.content.Context) {
        root = File(context.filesDir, "debug")
        if (!root.exists()) root.mkdirs()
    }

    fun capture(taskId: Long, stepIndex: Int, frame: Bitmap) {
        val dir = File(root, taskId.toString())
        if (!dir.exists()) dir.mkdirs()
        val ts = SimpleDateFormat("MMdd-HHmmss-SSS", Locale.US).format(Date())
        val file = File(dir, "step$stepIndex-$ts.jpg")
        runCatching {
            file.outputStream().use { frame.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        }.onFailure { Log.w(TAG, "capture save failed", it) }
        prune(dir)
    }

    private fun prune(dir: File) {
        val files = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
        files.drop(MAX_FILES_PER_TASK).forEach { runCatching { it.delete() } }
    }

    /** 最新的 [count] 张截图（旧→新），供 AI 会话附图 */
    fun latestCaptures(taskId: Long, count: Int): List<File> {
        val dir = File(root, taskId.toString())
        return dir.listFiles()
            ?.sortedByDescending { it.lastModified() }
            ?.take(count.coerceIn(1, MAX_FILES_PER_TASK))
            ?.reversed()
            ?: emptyList()
    }
}
