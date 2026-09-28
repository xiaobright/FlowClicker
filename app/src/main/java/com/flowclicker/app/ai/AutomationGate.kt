package com.flowclicker.app.ai

/** Stopping invalidates old callbacks permanently; resume never revives their tokens. */
class AutomationGate {
    @Volatile var epoch: Long = 0
        private set
    @Volatile var stopped: Boolean = false
        private set
    @Synchronized fun stop() { stopped = true; epoch++ }
    @Synchronized fun resume() { stopped = false }
    fun permits(token: Long, enabled: Boolean): Boolean = enabled && !stopped && token == epoch
}
