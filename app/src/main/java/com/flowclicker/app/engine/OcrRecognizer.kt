package com.flowclicker.app.engine

import android.graphics.Bitmap
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 一条 OCR 识别结果及其屏幕坐标框（物理像素，region 偏移已换算回原图坐标） */
data class OcrBox(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
}

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
                val text = process(frame, region).text
                Log.d(
                    TAG,
                    "recognize ${System.currentTimeMillis() - start}ms region=${region ?: "full"} chars=${text.length}"
                )
                text
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "recognize failed", e)
                null
            }
        }
    }

    /** 识别并返回行级文字框（屏幕坐标）。供文字锚点定位与 AI locate_text 使用 */
    suspend fun recognizeBoxes(frame: Bitmap?, region: Region?): List<OcrBox> {
        if (frame == null || frame.isRecycled) return emptyList()
        return withContext(Dispatchers.Default) {
            val start = System.currentTimeMillis()
            try {
                val (originX, originY) = cropOrigin(frame, region)
                val result = process(frame, region)
                result.textBlocks.flatMap { block ->
                    block.lines.mapNotNull { line ->
                        val r = line.boundingBox ?: return@mapNotNull null
                        OcrBox(
                            text = line.text,
                            left = r.left + originX,
                            top = r.top + originY,
                            right = r.right + originX,
                            bottom = r.bottom + originY,
                        )
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "recognizeBoxes failed", e)
                emptyList()
            }.also { boxes ->
                if (boxes.isNotEmpty()) {
                    Log.d(TAG, "recognizeBoxes ${System.currentTimeMillis() - start}ms region=${region ?: "full"} lines=${boxes.size}")
                }
            }
        }
    }

    private fun cropOrigin(frame: Bitmap, region: Region?): Pair<Int, Int> {
        if (region == null) return 0 to 0
        val left = region.left.coerceIn(0, frame.width - 1)
        val top = region.top.coerceIn(0, frame.height - 1)
        return left to top
    }

    private suspend fun process(frame: Bitmap, region: Region?): com.google.mlkit.vision.text.Text =
        suspendCancellableCoroutine { continuation ->
            // ML Kit may finish after cancellation; give it an owned bitmap released by its callback.
            val cropped = crop(frame, region)
            val owned = if (cropped === frame) frame.copy(Bitmap.Config.ARGB_8888, false) else cropped
            try {
                recognizer.process(InputImage.fromBitmap(owned, 0))
                    .addOnCompleteListener { task ->
                        owned.recycle()
                        if (continuation.isActive) {
                            if (task.isSuccessful) continuation.resume(task.result)
                            else continuation.resumeWithException(task.exception ?: IllegalStateException("OCR 失败"))
                        }
                    }
            } catch (e: Exception) {
                owned.recycle()
                if (continuation.isActive) continuation.resumeWithException(e)
            }
        }

    private fun crop(frame: Bitmap, region: Region?): Bitmap {
        if (region == null) return frame
        TaskValidation.region(region)
        require(region.right <= frame.width && region.bottom <= frame.height) { "检测区域超出画面" }
        val left = region.left.coerceIn(0, frame.width - 1)
        val top = region.top.coerceIn(0, frame.height - 1)
        val width = region.width.coerceAtMost(frame.width - left).coerceAtLeast(1)
        val height = region.height.coerceAtMost(frame.height - top).coerceAtLeast(1)
        return Bitmap.createBitmap(frame, left, top, width, height)
    }
}
