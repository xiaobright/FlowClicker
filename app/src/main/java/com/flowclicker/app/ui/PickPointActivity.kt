package com.flowclicker.app.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.TextView

/** 全屏半透明坐标拾取页：点按任意位置，返回该点屏幕坐标 */
class PickPointActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tv = TextView(this).apply {
            text = "点击屏幕上要自动点击的位置\n（返回键取消）"
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(0, 96, 0, 96)
            setBackgroundColor(0x66000000)
        }
        setContentView(
            tv,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            )
        )
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
