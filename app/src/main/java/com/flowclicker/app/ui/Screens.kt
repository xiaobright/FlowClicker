package com.flowclicker.app.ui

import android.app.Activity
import android.text.InputType
import android.view.View
import com.flowclicker.app.R
import com.flowclicker.app.ui.Ui.add

/** Screen composition only. Activities bind existing production actions to stable view IDs. */
object Screens {
    fun home(a: Activity) = with(Ui) {
        val body = screen(a, "自动化，井然有序。", "把重复留给流程，把时间留给自己。", R.id.navHome)
        card(body, 22, R.color.fc_hero, 24) {
            add(text(a, "CONTROL CENTER  /  工作台", 11, R.color.fc_hero_muted, true))
            add(text(a, "准备开始", 27, R.color.fc_hero_accent, true, R.id.tvHomeTitle), 18)
            add(text(a, "", 14, R.color.fc_hero_muted, id = R.id.tvStatus), 8)
            add(text(a, "识别屏幕    →    执行动作    →    验证结果", 12, R.color.fc_hero_muted), 24)
            add(text(a, "", 13, R.color.fc_hero_accent, id = R.id.tvTaskSummary), 16)
        }
        section(body, "权限与连接", "仅在你主动开启后，才会采集屏幕。")
        card(body, 12) {
            add(text(a, "01  无障碍服务", 16, bold = true))
            add(text(a, "用于派发点击和滑动手势", 13, R.color.fc_muted, id = R.id.tvAccessStatus), 6)
            add(button(a, "开启无障碍服务", R.id.btnAccessibility, "soft"), 10)
            add(text(a, "02  悬浮控制", 16, bold = true), 20)
            add(text(a, "录制时显示控制面板", 13, R.color.fc_muted, id = R.id.tvOverlayStatus), 6)
            add(button(a, "授予悬浮窗权限", R.id.btnOverlay, "soft"), 10)
        }
        card(body) {
            add(text(a, "03  屏幕采集", 16, bold = true))
            add(text(a, "", 13, R.color.fc_muted, id = R.id.tvCaptureStatus), 6)
            add(row(a, button(a, "开启采集", R.id.btnCaptureStart),
                button(a, "停止采集", R.id.btnCaptureStop, "quiet")), 12)
        }
        section(body, "下一步，交给流程")
        card(body, 12) {
            add(button(a, "管理我的任务  →", R.id.btnTasks, "soft"))
            add(button(a, "打开 AI 助手  →", R.id.btnAi, "quiet"), 8)
            add(text(a, "先创建任务，再在任务页启动监测。AI 为可选功能，需要单独配置。", 13, R.color.fc_muted), 12)
        }
        card(body) {
            add(text(a, "始终由你掌控", 16, bold = true))
            add(text(a, "停止 AI、任务引擎和录制；保留屏幕采集。已派发的短手势可能仍在收尾。", 13, R.color.fc_muted), 6)
            add(button(a, "停止全部自动操作", R.id.btnEmergencyStop, "danger"), 12)
            add(button(a, "运行隔离测试", R.id.btnTest, "quiet"), 8)
            add(text(a, "测试会点击本页的无障碍入口，不会改写任务库。", 12, R.color.fc_muted), 6)
        }
    }

    fun tasks(a: Activity) = with(Ui) {
        val body = screen(a, "我的任务", "把每一次操作，整理成可复用的流程。", R.id.navTasks)
        card(body, 22, R.color.fc_hero) {
            add(text(a, "AUTOMATION  /  运行控制", 11, R.color.fc_hero_muted, true))
            add(text(a, "已停止", 23, R.color.fc_hero_accent, true, R.id.tvEngineStatus), 12)
            add(text(a, "", 13, R.color.fc_hero_muted, id = R.id.tvTaskCount), 8)
            add(row(a, button(a, "启动监测", R.id.btnStart, "soft"),
                button(a, "停止全部", R.id.btnStop, "danger")), 16)
        }
        section(body, "流程库", "启用开关不等于立即运行；启动监测后才会执行。")
        body.add(button(a, "＋  新建任务", R.id.btnNew), 12)
        body.add(column(a).apply { id = R.id.llTasks }, 4)
        body.add(text(a, "模块衔接：通过标签把任务串联起来，在步骤中启用或停用相应标签组。", 12, R.color.fc_muted), 20)
    }

    fun ai(a: Activity) = with(Ui) {
        val body = screen(a, "AI 助手", "按需唤醒，协助编排与复盘。", R.id.navAi)
        card(body, 22, R.color.fc_hero) {
            add(text(a, "ON DEMAND  /  AI 调度员", 11, R.color.fc_hero_muted, true))
            add(text(a, "", 23, R.color.fc_hero_accent, true, R.id.tvAiStatus), 12)
            add(text(a, "配置不会自动发送请求。唤醒后，会使用已保存的模型端点。", 13, R.color.fc_hero_muted), 8)
        }
        card(body) {
            add(button(a, "模型与连接设置", R.id.btnAiSettings, "soft"))
            add(column(a).apply {
                id = R.id.aiSettingsPanel
                add(check(a, "启用 AI 调度员", R.id.cbEnabled), 8)
                field(this, "API 地址（OpenAI 兼容）", R.id.etBaseUrl, type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
                field(this, "API Key · 仅保存在本机", R.id.etApiKey, type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, password = true)
                field(this, "模型名称", R.id.etModel)
                add(check(a, "模型支持图像输入", R.id.cbVlm), 8)
                field(this, "单次工具轮数上限（1–12）", R.id.etMaxRounds, type = InputType.TYPE_CLASS_NUMBER)
                field(this, "复盘轮数（1–10）", R.id.etDebugRounds, type = InputType.TYPE_CLASS_NUMBER)
                add(text(a, "结构化判断服务（可选，兼容 Jev）", 16, bold = true), 24)
                add(check(a, "启用判断服务：空闲或衔接超时时判断", R.id.cbJudgeEnabled), 8)
                add(check(a, "仅记录建议，仍由 AI 处理", R.id.cbJudgeObserve, true), 4)
                add(text(a, "可连接兼容服务或自建模型。会发送当前屏幕文字、恢复场景与执行结果。关闭“仅记录”后，只运行已验证的恢复任务；不确定时交给 AI。", 12, R.color.fc_muted), 8)
                field(this, "判断 API 前缀或完整 /systemone 地址", R.id.etJudgeBaseUrl, type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
                field(this, "判断服务 API Key · 无鉴权可留空", R.id.etJudgeApiKey, type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, password = true)
                field(this, "判断模型名称", R.id.etJudgeModel)
                add(text(a, "支持 HTTP/HTTPS；HTTP 会明文传输，请仅用于可信网络。", 12, R.color.fc_muted), 6)
                field(this, "最低置信门槛（0.5–1，需按实际场景验证）", R.id.etJudgeConfidence, type = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
                add(button(a, "保存配置", R.id.btnSave), 16)
                add(text(a, "配置仅在保存后生效。关闭 AI 不会自动停止任务引擎。", 12, R.color.fc_muted), 8)
            })
        }
        section(body, "给助手一个目标")
        card(body, 12) {
            field(this, "例如：检查当前任务编排", R.id.etManual,
                type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE).apply { minLines = 2; maxLines = 5 }
            add(button(a, "发送指令并唤醒", R.id.btnWake), 12)
            add(button(a, "停止全部自动操作", R.id.btnStopAi, "danger"), 8)
        }
        section(body, "活动记录", "事件、工具调用与回复，按时间倒序排列。")
        card(body, 12) {
            add(text(a, "暂无调度记录", 14, R.color.fc_muted, id = R.id.tvLog).apply { setTextIsSelectable(true) })
        }
    }

    fun editor(a: Activity, editing: Boolean) = with(Ui) {
        val body = screen(a, if (editing) "编辑任务" else "创建任务", "定义触发条件，再安排每一个动作。")
        card(body, 22) {
            add(text(a, "01  基本信息", 18, bold = true))
            field(this, "任务名称", R.id.etName)
            field(this, "分组标签（可选）", R.id.etTag)
            field(this, "优先级 · 数字越小越先执行", R.id.etPriority, "0", InputType.TYPE_CLASS_NUMBER)
            add(check(a, "重复触发（关闭后只执行一次）", R.id.cbLoop, true), 8)
            field(this, "异常恢复适用场景（留空不参与分流）", R.id.etRecoveryHint,
                type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE).apply { maxLines = 4 }
            add(text(a, "例如：连接中断且出现重试按钮。AI 也可编排此字段。仅支持无回退的文字锚点点击、等待与文字验证；须先成功试跑、处于正常模式，重启或修改后需再验证。", 12, R.color.fc_muted), 6)
        }
        card(body) {
            add(text(a, "02  触发条件", 18, bold = true))
            add(text(a, "当屏幕出现关键词时，执行下面的动作。", 13, R.color.fc_muted), 6)
            field(this, "关键词 · 用逗号分隔", R.id.etKeywords)
            add(text(a, "检测区域 · 物理像素，全部留空为全屏", 12, R.color.fc_muted), 16)
            val first = column(a); val second = column(a)
            field(first, "左", R.id.etRegionL, type = InputType.TYPE_CLASS_NUMBER)
            field(second, "上", R.id.etRegionT, type = InputType.TYPE_CLASS_NUMBER)
            add(row(a, first, second))
            val third = column(a); val fourth = column(a)
            field(third, "右", R.id.etRegionR, type = InputType.TYPE_CLASS_NUMBER)
            field(fourth, "下", R.id.etRegionB, type = InputType.TYPE_CLASS_NUMBER)
            add(row(a, third, fourth))
            add(row(a, button(a, "框选区域", R.id.btnPickRegion, "soft"),
                button(a, "恢复全屏", R.id.btnClearRegion, "quiet")), 12)
        }
        section(body, "03  动作序列", "按顺序执行。建议最后添加文字验证，确认操作结果。")
        body.add(text(a, "", 13, R.color.fc_primary, true, R.id.tvStepCount), 10)
        body.add(column(a).apply { id = R.id.llSteps })
        card(body, 12) {
            add(row(a, button(a, "＋ 点击", R.id.btnAddClick, "soft"), button(a, "＋ 滑动", R.id.btnAddSwipe, "soft")))
            add(row(a, button(a, "＋ 等待", R.id.btnAddWait, "quiet"), button(a, "＋ 文字验证", R.id.btnAddWaitText, "soft")), 6)
            add(row(a, button(a, "启用标签组", R.id.btnAddEnable, "quiet"), button(a, "停用标签组", R.id.btnAddDisable, "quiet")), 6)
            add(button(a, "录制操作序列", R.id.btnRecord, "soft"), 14)
            add(text(a, "录制面板会悬浮在其他应用上方。完成后返回此页，步骤会追加到当前草稿。", 12, R.color.fc_muted), 8)
        }
        body.add(button(a, "保存任务", R.id.btnSave), 24)
    }
}
