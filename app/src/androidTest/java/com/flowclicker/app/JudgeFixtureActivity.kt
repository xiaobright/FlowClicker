package com.flowclicker.app

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Disposable English screen; short labels keep real OCR stable across safety comparisons. */
class JudgeFixtureActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(60, 60, 60, 60); setBackgroundColor(android.graphics.Color.WHITE)
        }
        val title = TextView(this).apply {
            text = "CONNECTION LOST"
            textSize = 28f; gravity = Gravity.CENTER; setTextColor(android.graphics.Color.BLACK)
        }
        layout.addView(title)
        layout.addView(Button(this).apply {
            text = "RETRY CONNECTION"; textSize = 24f
            setOnClickListener { title.text = "RECOVERY OK"; isEnabled = false }
        }, LinearLayout.LayoutParams(-1, 180))
        // Produce fresh capture buffers without changing OCR text (gesture admission checks frame age).
        val pulse = View(this)
        layout.addView(pulse, LinearLayout.LayoutParams(8, 8))
        handler.post(object : Runnable {
            private var on = false
            override fun run() {
                on = !on
                pulse.setBackgroundColor(if (on) android.graphics.Color.GRAY else android.graphics.Color.LTGRAY)
                handler.postDelayed(this, 250)
            }
        })
        setContentView(layout)
    }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); super.onDestroy() }
}
