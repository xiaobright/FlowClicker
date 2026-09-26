package com.flowclicker.app

import android.app.Application
import com.flowclicker.app.engine.MonitoringEngine
import com.flowclicker.app.engine.OcrRecognizer
import com.flowclicker.app.engine.TaskStore
import com.flowclicker.app.service.ScreenCaptureService

/** 启动时初始化持久化，并把识别器、共享帧来源、变更回调接入监测引擎 */
class FlowClickerApp : Application() {

    override fun onCreate() {
        super.onCreate()
        TaskStore.init(this)
        MonitoringEngine.frameProvider = {
            ScreenCaptureService.instance?.currentFrame()
        }
        MonitoringEngine.textRecognizer = { frame, region ->
            OcrRecognizer.recognize(frame, region)
        }
        MonitoringEngine.onChanged = { tasks ->
            TaskStore.saveAll(tasks)
        }
    }
}
