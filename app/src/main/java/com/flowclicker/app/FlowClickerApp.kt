package com.flowclicker.app

import android.app.Application
import com.flowclicker.app.ai.AiStores
import com.flowclicker.app.ai.DebugStore
import com.flowclicker.app.ai.WakeDispatcher
import com.flowclicker.app.engine.MonitoringEngine
import com.flowclicker.app.engine.OcrRecognizer
import com.flowclicker.app.engine.TaskStore
import com.flowclicker.app.service.ScreenCaptureService

/** 启动时初始化各存储，并把识别器、共享帧、debug 截图、事件监听接入引擎 */
class FlowClickerApp : Application() {

    override fun onCreate() {
        super.onCreate()
        TaskStore.init(this)
        AiStores.init(this)
        DebugStore.init(this)

        MonitoringEngine.setTasks(TaskStore.loadAll())
        MonitoringEngine.frameProvider = {
            ScreenCaptureService.instance?.currentFrame()
        }
        MonitoringEngine.textRecognizer = { frame, region ->
            OcrRecognizer.recognize(frame, region)
        }
        MonitoringEngine.onChanged = { tasks ->
            TaskStore.saveAll(tasks)
        }
        MonitoringEngine.stepCapture = { taskId, stepIndex ->
            MonitoringEngine.frameProvider?.invoke()?.let { frame ->
                try {
                    DebugStore.capture(taskId, stepIndex, frame)
                } finally {
                    frame.recycle()
                }
            }
        }
        WakeDispatcher.init()
    }
}
