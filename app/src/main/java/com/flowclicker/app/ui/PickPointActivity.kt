package com.flowclicker.app.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.TextView
import com.flowclicker.app.R
import com.flowclicker.app.ui.Ui.add
import com.flowclicker.app.ui.Ui.dp

/** 全屏半透明坐标拾取页：点按任意位置，返回该点屏幕坐标 */
class PickPointActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val frame = FrameLayout(this)
        val hint = Ui.column(this, 16).apply {
            background = Ui.background(this@PickPointActivity, R.color.fc_surface, 20, R.color.fc_outline)
            add(Ui.text(this@PickPointActivity, "拾取一个位置", 18, bold = true))
            add(Ui.text(this@PickPointActivity, "点击面板以外的目标位置，自动返回坐标。", 13, R.color.fc_muted), 4)
            add(Ui.button(this@PickPointActivity, "取消拾取", tone = "quiet") { finish() }, 10)
            isClickable = true
        }
        frame.addView(hint, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { setMargins(dp(16), dp(24), dp(16), 0) })
        Ui.pickerContent(this, frame, hint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            setResult(
                RESULT_OK,
                Intent().putExtra("x", event.rawX).putExtra("y", event.rawY)
            )
            finish()
            return true
        }
        return true
    }
}
