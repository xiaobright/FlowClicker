package com.flowclicker.app.engine

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** Missing/invalidated frames and OCR failures never prove text absence. */
internal suspend fun waitForText(step: Step.WaitText, readText: suspend () -> String?) {
    val matched = withTimeoutOrNull(step.timeoutMs) {
        var text = readText()
        while (text == null || text.contains(step.text, true) != step.present) {
            delay(300)
            text = readText()
        }
        true
    } ?: false
    check(matched) { "等待新画面中文字${if (step.present) "出现" else "消失"}超时：${step.text}" }
}
