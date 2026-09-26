package com.flowclicker.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/**
 * 拖拽框选视图：选中区域外加深色遮罩，框线高亮并显示像素尺寸。
 * 上报坐标 = 视图内坐标 + 视图在屏幕上的偏移 = 绝对屏幕像素。
 */
class RegionPickerView(context: Context) : View(context) {

    var onRectSelected: ((RectF) -> Unit)? = null

    private var start: Pair<Float, Float>? = null
    private var cur: Pair<Float, Float>? = null
    private val screenOffset = IntArray(2)

    private val dimPaint = Paint().apply { color = 0x55000000 }
    private val boxPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        color = 0xFF38BDF8.toInt()
    }
    private val textPaint = Paint().apply {
        color = 0xFFFFFFFF.toInt()
        textSize = 38f
        isAntiAlias = true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        getLocationOnScreen(screenOffset)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                start = event.x to event.y
                cur = start
            }

            MotionEvent.ACTION_MOVE -> cur = event.x to event.y

            MotionEvent.ACTION_UP -> {
                cur = event.x to event.y
                val s = start
                val c = cur
                if (s != null && c != null) {
                    val l = minOf(s.first, c.first)
                    val t = minOf(s.second, c.second)
                    val r = maxOf(s.first, c.first)
                    val b = maxOf(s.second, c.second)
                    if (r - l >= 20 && b - t >= 20) {
                        onRectSelected?.invoke(
                            RectF(
                                l + screenOffset[0], t + screenOffset[1],
                                r + screenOffset[0], b + screenOffset[1]
                            )
                        )
                    }
                }
                start = null
                cur = null
            }
        }
        invalidate()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val s = start
        val c = cur ?: return
        if (s == null) return
        val l = minOf(s.first, c.first)
        val t = minOf(s.second, c.second)
        val r = maxOf(s.first, c.first)
        val b = maxOf(s.second, c.second)
        // 四周遮罩
        canvas.drawRect(0f, 0f, width.toFloat(), t, dimPaint)
        canvas.drawRect(0f, b, width.toFloat(), height.toFloat(), dimPaint)
        canvas.drawRect(0f, t, l, b, dimPaint)
        canvas.drawRect(r, t, width.toFloat(), b, dimPaint)
        canvas.drawRect(l, t, r, b, boxPaint)
        canvas.drawText(
            "${(r - l).toInt()} × ${(b - t).toInt()}",
            l, (t - 16f).coerceAtLeast(60f), textPaint
        )
    }
}
