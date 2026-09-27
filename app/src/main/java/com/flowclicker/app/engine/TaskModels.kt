package com.flowclicker.app.engine

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 屏幕检测区域（物理像素），null 表示全屏 */
@Serializable
data class Region(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

@Serializable
data class Trigger(
    val region: Region? = null,
    val keywords: List<String> = emptyList(),
    val ignoreCase: Boolean = true,
)

/**
 * 动作步骤。坐标为屏幕物理像素。
 * 随机化：*Jitter* 字段为抖动幅度，执行时在 ±jitter 内取随机值叠加。
 * 控制步骤（Enable/DisableTagged）用于模块衔接：常驻模块（如重新登录）
 * 在序列末尾启用某个标签组，之后打上该标签的任务进入监测，实现"登录后自动接挂机"。
 */
@Serializable
sealed interface Step {
    @Serializable
    @SerialName("click")
    data class Click(
        val x: Float,
        val y: Float,
        /** OCR 锚点文字：非空时执行前实时识别屏幕，点击包含该文字的文本框中心；未命中回退 x,y */
        val anchor: String? = null,
        val anchorFallback: Boolean = false,
        val maxOffsetPx: Float = 0f,
        val pressMs: Long = 60,
        val pressJitterMs: Long = 0,
        val delayAfterMs: Long = 500,
        val delayJitterMs: Long = 0,
    ) : Step

    @Serializable
    @SerialName("swipe")
    data class Swipe(
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float,
        val durationMs: Long = 300,
        val durationJitterMs: Long = 0,
        val delayAfterMs: Long = 500,
        val delayJitterMs: Long = 0,
    ) : Step

    @Serializable
    @SerialName("wait")
    data class Wait(val ms: Long) : Step

    @Serializable
    @SerialName("wait_text")
    data class WaitText(
        val text: String,
        val present: Boolean = true,
        val timeoutMs: Long = 10000,
        val region: Region? = null,
    ) : Step

    @Serializable
    @SerialName("enable_tagged")
    data class EnableTagged(val tag: String) : Step

    @Serializable
    @SerialName("disable_tagged")
    data class DisableTagged(val tag: String) : Step
}

/**
 * 一个自动化模块。
 * - enabled：用户开关（常驻模块=长期启用，按需模块=用的时候打开）
 * - tag：分组标签；被其他任务的 EnableTagged/DisableTagged 步骤批量控制
 * - loop：true=触发后重新武装可再次执行（挂机轮询型）；false=执行一次后自动停用
 * - priority：小者先评估，同帧多个任务匹配时高优先级（数值小）先执行
 * - mode：normal=正常；debug=每次点击/滑动前留存截图供 AI 复盘，跑满轮数后唤醒调度员
 */
@Serializable
data class Task(
    val id: Long,
    val name: String,
    val priority: Int = 0,
    val tag: String? = null,
    val trigger: Trigger = Trigger(),
    val steps: List<Step> = emptyList(),
    val enabled: Boolean = true,
    val loop: Boolean = true,
    val mode: String = MODE_NORMAL,
    /** Definition version; toggling enabled/mode does not invalidate execution evidence. */
    val revision: Long = 0,
) {
    companion object {
        const val MODE_NORMAL = "normal"
        const val MODE_DEBUG = "debug"
    }
}
