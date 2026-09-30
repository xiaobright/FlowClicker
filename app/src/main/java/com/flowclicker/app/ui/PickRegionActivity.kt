package com.flowclicker.app.ui

import android.app.Activity
import android.content.Intent
import android.graphics.RectF
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import com.flowclicker.app.R
import com.flowclicker.app.ui.Ui.add
import com.flowclicker.app.ui.Ui.dp

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

        val hint = Ui.column(this, 16).apply {
            background = Ui.background(this@PickRegionActivity, R.color.fc_surface, 20, R.color.fc_outline)
            add(Ui.text(this@PickRegionActivity, "圈定检测区域", 18, bold = true))
            add(Ui.text(this@PickRegionActivity, "在面板外拖出矩形，只识别你关心的区域。", 13, R.color.fc_muted), 4)
            add(Ui.button(this@PickRegionActivity, "取消框选", tone = "quiet") { finish() }, 10)
            isClickable = true
        }
        frame.addView(
            hint,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            ).apply { setMargins(dp(16), dp(24), dp(16), 0) }
        )
        Ui.pickerContent(this, frame, hint)
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
