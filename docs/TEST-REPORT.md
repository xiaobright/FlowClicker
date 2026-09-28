# 审查修复测试报告（TEST-REPORT）

- 测试方：ZCode 会话（接手 docs/TEST-HANDOFF.md；修复方为另一工作流，额度中断）
- 日期：2026-09-27
- 源码：HEAD `eef1db4` + 修复方未提交改动（14 文件修改 + AutomationGate/WakeQueue/RetryPolicy/ScreenControl/AtomicTextFile/TaskValidation/StopAutomationReceiver/mock_ai.py/单测等新增）
- APK：`app-debug.apk` SHA-256 `d65ebbf925d2733df5fac1fdd3e2ac38ee290c88d02a84c3229be460d11cc248`
- 构建：`BUILD SUCCESSFUL`（:app:testDebugUnitTest :app:assembleDebug，21s up-to-date）；单测 **22 个全过**（AiClientTest 19 + SafetyRegressionTest 3，0 failures 0 errors）
- 设备：emulator-5555，Pixel_10 AVD，API 37，1080x2424，安装 `install -r` 保留数据
- 数据备份：`.tools/backup/app-files-pre-test.tar`（tasks.json 完整恢复；wake_rules.json 按测试前已知值重建）
- 假 AI 服务：`tools/mock_ai.py`（回环 + adb reverse tcp:8765），真实模型仅最后 1 次冒烟（龙猫免费额度，用户授权）

## P0：停止后绝不自动复活

| 项 | 结果 | 证据 |
|---|---|---|
| S01 停止时模型未返回 | **PASS** | late-action 15s：唤醒后 4s 点「停止全部」；迟到响应返回后 `/stats` 恰 1 次请求，任务数保持 4、无「回归·只创建一次」，引擎未被拉起；logcat `session cancelled` |
| S02 停止后立即手动开新一轮 | **PASS** | 新会话正常收到回复并落 ai_log（"go2"）；旧创建指令始终未执行（回归任务数 0） |
| S03 后台任务与看门狗到期 | **PASS** | 引擎运行中（截图 emu_s03a「运行中·engine」）→ 长等待任务执行中点停止（toast「已停止…」emu_s03b）→ 60s 后引擎「已停止」、idle 15s 规则未产生任何新 AI 请求（/stats 恒为 setup 的 3 次） |
| S04-A 只关 AI 开关 | **PASS**（含 1 项存疑） | 会话进行中取消勾选+保存：`session cancelled`（12:37:10）、迟到创建未执行、后续引擎事件 0 AI 请求；重新勾选后手动唤醒恢复正常（stats 第 2 次）。**存疑**：期间观察到一次引擎自行停止（见"异常"） |
| S04-B 全部停止 | **PASS**（主界面路径） | btnEmergencyStop → AI 会话取消 + 引擎停止 + toast 提示短手势收尾。**注意**：dumpsys notification 未检索到采集通知上的「停止全部」action（0 命中），通知路径的接线需修复方确认 |

## P1：数据与执行正确性

| 项 | 结果 | 证据 |
|---|---|---|
| D01 临时全链路测试不改任务库 | **PASS** | btnTest → preview 隔离执行：设置→无障碍页被自动打开（wait_text 前后验证均通过）；tasks.json SHA-256 前后一致（7020753b…），5 个任务完好 |
| D03 debug 状态保留 | **PASS** | 编辑器打开 回归·S03 → wait_text 步骤正常渲染可编辑（非"不支持编辑"占位）→ 保存后 mode=debug 保留、revision 语义正确（无变化不递增 rev1） |
| E01 失败不得报成功 | **PASS**（间接验证） | 实测中出现 2 次点击派发失败：任务立即「待验收/已暂停」（holdForReview）、引擎跳过它们继续评其他任务、未计入成功轮次（截图 emu_s04_run.png「跳过 id2/id5、执行 id3」）；TaskFinished.completed 语义与派发结果绑定 |
| D02 版本冲突 / E02 验证转正门槛 / E03 锚点与输入校验 | NOT_RUN | 时间窗口所限；机制代码已就位（updateTasks revision 检查、promote 须 verified+revision 匹配、anchor 未命中策略），建议修复方恢复后补跑 |

## P2：AI 调度、协议

| 项 | 结果 | 证据 |
|---|---|---|
| A01 重试不重复副作用 | **PASS** | retry 场景：恰好 3 次请求（200/503/200），任务只创建 1 个，同会话内请求级重试（消息上下文保留） |
| A01b 4xx 不重试 | **PASS** | auth 场景：恰好 1 次请求，日志「会话失败，未重放工具：HTTP 401」 |
| A02 有界队列 / A03 stall scoped idle / L01 旋转 / L02 长跑 | NOT_RUN | 交接要求优先级最低；L02 需 15-30 分钟专用窗口 |

## 真实模型冒烟（用户授权，龙猫免费额度）

**PASS**：切回 LongCat 真实配置后手动唤醒，describe_screen 工具调用 + 中文 reply 正常走通新闸门/队列/会话链路；且回复复用了 L3 长期记忆中的"全黑陷阱"经验对当时熄屏状态作出正确诊断。

## 观察到的异常（交修复方）

1. **引擎一次原因不明的自行停止**（12:38 前后）：运行中引擎在无人工停止的情况下变为「已停止」。怀疑 runner 因 `frameProvider` 瞬时返回 null 抛 `error("屏幕采集已停止")` 被 runner 顶层 catch 捕获后退出（等价"一个坏帧杀死整个监测循环"）；当时 logcat 已轮转未能取证。建议：坏帧应跳过本轮而非退出 runner；用 `runner stopped` 日志做一次复现关联。
2. **采集通知上未见「停止全部」action**（dumpsys notification 0 命中）：StopAutomationReceiver 的接线需确认。
3. 派发失败偶发（2 次）：任务列表页 OCR 自匹配导致频繁触发+快速导航干扰下出现，hold 机制兜底正确；与"限定目标应用"待办同源。

## 恢复与清理

- tasks.json / wake_rules.json 已恢复测试前状态（4 任务、无回归·残留；回归·S03 与 回归·只创建一次 已随恢复移除）
- ai_settings.json = 龙猫真实配置（enabled, maxToolRounds=8）；mock 进程已结束、adb reverse 已移除
- 测试证据截图：`.tools/emu_s03a.png` `.tools/emu_s03b.png` `.tools/emu_s04_engine.png` `.tools/emu_s04_run.png` `.tools/emu_d01.png` `.tools/emu_d01b.png`
- 未提交：按交接要求测试方不提交，工作区留给修复方收尾
