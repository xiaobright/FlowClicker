package com.flowclicker.app.engine

import android.graphics.Bitmap
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ML Kit 中文 OCR 封装（bundled 模型，离线运行）。
 * 输入为共享屏幕帧与可选检测区域，区域内裁剪识别，返回区域内的全部文本。
 */
object OcrRecognizer {

    private const val TAG = "OcrRecognizer"

    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    suspend fun recognize(frame: Bitmap?, region: Region?): String? {
        if (frame == null || frame.isRecycled) return null
        return withContext(Dispatchers.Default) {
            val start = System.currentTimeMillis()
            try {
                val input = InputImage.fromBitmap(crop(frame, region), 0)
                val text = Tasks.await(recognizer.process(input)).text
                Log.d(
                    TAG,
                    "recognize ${System.currentTimeMillis() - start}ms region=${region ?: "full"} chars=${text.length}"
                )
                text
            } catch (e: Exception) {
                Log.w(TAG, "recognize failed", e)
                null
            }
        }
    }

    private fun crop(frame: Bitmap, region: Region?): Bitmap {
        if (region == null) return frame
        val left = region.left.coerceIn(0, frame.width - 1)
        val top = region.top.coerceIn(0, frame.height - 1)
        val width = region.width.coerceAtMost(frame.width - left).coerceAtLeast(1)
        val height = region.height.coerceAtMost(frame.height - top).coerceAtLeast(1)
        return Bitmap.createBitmap(frame, left, top, width, height)
    }
}
