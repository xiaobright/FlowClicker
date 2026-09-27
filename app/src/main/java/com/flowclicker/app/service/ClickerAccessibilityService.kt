package com.flowclicker.app.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import com.flowclicker.app.core.GestureDispatcher

/** 无障碍服务：手势派发的系统入口，全局单实例。 */
class ClickerAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        GestureDispatcher.attach(this)
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        com.flowclicker.app.ai.WakeDispatcher.stopAll()
        GestureDispatcher.detach(this)
        instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile
        var instance: ClickerAccessibilityService? = null
            private set
    }
}
