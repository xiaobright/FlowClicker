package com.flowclicker.app.ui

import android.app.Activity
import android.content.Intent
import android.graphics.RectF
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView

/** 全屏半透明区域框选页：拖拽画出矩形，确认后返回绝对屏幕坐标区域 */
class PickRegionActivity : Activity() {

    private var picker: RegionPickerView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val frame = FrameLayout(this)

        val view = RegionPickerView(this).apply {
            onRectSelected = { rect -> confirm(rect) }
        }
        picker = view
        frame.addView(
            view,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        val hint = TextView(this).apply {
            text = "在屏幕上拖拽框出检测区域\n返回键取消"
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, 96, 0, 96)
            setBackgroundColor(0x66000000)
        }
        frame.addView(
            hint,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            )
        )
        setContentView(frame)
    }

    private fun confirm(rect: RectF) {
        val l = rect.left.toInt()
        val t = rect.top.toInt()
        val r = rect.right.toInt()
        val b = rect.bottom.toInt()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("确认检测区域")
            .setMessage("区域：($l, $t) → ($r, $b)\n大小：${r - l} × ${b - t}")
            .setPositiveButton("使用此区域") { _, _ ->
                setResult(
                    RESULT_OK,
                    Intent()
                        .putExtra("l", l).putExtra("t", t)
                        .putExtra("r", r).putExtra("b", b)
                )
                finish()
            }
            .setNegativeButton("重新框选", null)
            .setNeutralButton("取消") { _, _ -> finish() }
            .show()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        setResult(RESULT_CANCELED)
        super.onBackPressed()
    }
}
