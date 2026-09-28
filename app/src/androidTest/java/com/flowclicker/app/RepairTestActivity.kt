package com.flowclicker.app

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Disposable screen installed only in the test APK. No app data or network access. */
class RepairTestActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(50, 80, 50, 80)
            setBackgroundColor(android.graphics.Color.WHITE)
        }
        val title = TextView(this).apply {
            text = "点击器安全验收\n等待操作"
            textSize = 28f
            gravity = Gravity.CENTER
            setTextColor(android.graphics.Color.BLACK)
        }
        layout.addView(title)
        layout.addView(Button(this).apply {
            text = "安全点击"
            textSize = 24f
            setOnClickListener { title.text = "点击器安全验收\n验证成功" }
        }, LinearLayout.LayoutParams(-1, 180))
        val clock = TextView(this).apply { textSize = 16f; gravity = Gravity.CENTER }
        layout.addView(clock)
        setContentView(layout)
        handler.post(object : Runnable {
            private var tick = 0
            override fun run() {
                clock.text = "采集测试帧 ${tick++}"
                handler.postDelayed(this, 250)
            }
        })
    }
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
