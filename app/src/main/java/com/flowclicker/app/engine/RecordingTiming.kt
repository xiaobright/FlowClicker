package com.flowclicker.app.engine

object RecordingTiming {
    fun delayAfter(times: List<Pair<Long, Long>>, index: Int): Long =
        if (index == times.lastIndex) 500L else (times[index + 1].first - times[index].second).coerceIn(0, 120000)
}
