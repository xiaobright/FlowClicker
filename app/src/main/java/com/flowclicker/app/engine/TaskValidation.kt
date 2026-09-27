package com.flowclicker.app.engine

object TaskValidation {
    fun region(r: Region?) {
        require(r == null || (r.left >= 0 && r.top >= 0 && r.right > r.left && r.bottom > r.top)) {
            "检测区域无效"
        }
    }

    fun validate(task: Task) {
        require(task.name.isNotBlank()) { "任务名不能为空" }
        require(task.mode in setOf(Task.MODE_NORMAL, Task.MODE_DEBUG)) { "mode 无效" }
        region(task.trigger.region)
        require(task.trigger.keywords.all { it.isNotBlank() }) { "关键词不能包含空字符串" }
        require(task.steps.size in 1..100) { "步骤数量必须为 1..100" }
        var duration = 0L
        fun point(x: Float, y: Float) {
            require(x.isFinite() && y.isFinite() && x >= 0 && y >= 0) { "坐标必须是非负有限数值" }
        }
        fun timing(base: Long, jitter: Long, max: Long) {
            require(base in 0..max && jitter in 0..max && base <= max - jitter) { "时长或抖动超限" }
        }
        task.steps.forEach {
            when (it) {
                is Step.Click -> {
                    point(it.x, it.y)
                    require(it.maxOffsetPx.isFinite() && it.maxOffsetPx in 0f..100f) { "随机偏移超限" }
                    timing(it.pressMs, it.pressJitterMs, 5000)
                    timing(it.delayAfterMs, it.delayJitterMs, 120000)
                    duration += it.pressMs + it.pressJitterMs + it.delayAfterMs + it.delayJitterMs
                }
                is Step.Swipe -> {
                    point(it.x1, it.y1); point(it.x2, it.y2)
                    require(it.durationMs > 0)
                    timing(it.durationMs, it.durationJitterMs, 5000)
                    timing(it.delayAfterMs, it.delayJitterMs, 120000)
                    duration += it.durationMs + it.durationJitterMs + it.delayAfterMs + it.delayJitterMs
                }
                is Step.Wait -> {
                    require(it.ms in 0..120000) { "等待时长超限" }
                    duration += it.ms
                }
                is Step.WaitText -> {
                    require(it.text.isNotBlank() && it.timeoutMs in 500..120000) { "文字等待参数无效" }
                    region(it.region)
                    duration += it.timeoutMs
                }
                is Step.EnableTagged -> require(it.tag.isNotBlank())
                is Step.DisableTagged -> require(it.tag.isNotBlank())
            }
        }
        require(duration <= 300000) { "单任务最长执行时间不能超过 5 分钟" }
    }
}
