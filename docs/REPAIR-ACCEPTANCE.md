# 采集/通知修复 · 设备验收报告（第三轮，2026-09-28 下午）

- 被测包：工作区最终源码构建，**SHA-256 `e97d957969b7a8e308b2bd481ccb4e140ef6a5e839b494901b126eac0e2f7587`**（`app/build/outputs/apk/debug/app-debug.apk`）。
- 该包包含修复方最后一批源码改动（`CaptureWorker` 手势屏障/串行化、permission_ui 套件入口）；清单中记录的 `B8A38C3A…` 因晚于其构建的源码改动而作废，未作为被测对象。
- 设备：emulator-5554（API 37、1080×2424）；`install -r` 保留数据；测前台账见 `.tools/backup/repair-20260928/manifest.json`（tasks.json 基线 `ca5a630e…`，与设备实测一致）。
- 单测：`testDebugUnitTest` 42 项全过（AiClientTest 3 / CaptureRegressionTest 18 / SafetyRegressionTest 21），0 failure/error。

## T01 系统撤销投影 —— **PASS**

最终包上执行 2 次（修复方在 B8A38C3A 上已完成 4 次）：点录屏芯片 → "Stop sharing"：
- `VirtualDisplayAdapter: Virtual display device released because media projection stopped`（系统侧）+ `ScreenCapture: media projection stopped by system`（回调）均出现。
- **PID 前后不变（7694），无 SIGSEGV**（日志仅剩预期的 `ConsumerBase abandonLocked` 告警）；服务与通知均清理；首轮修复前的 `memmove_avx2` 原生崩溃未复现。
- 瑕疵（不影响结论）：撤销时出现 `ConsumerBase abandonLocked` 错误日志——串行化后它已无害，但建议修复方后续静默或降级为 debug 日志。

## T02 手工停止/旋转/快速重启/缺 token —— **PASS**

- 手工「停止屏幕采集」→ 服务干净退出。
- 横屏旋转（锁定 user_rotation=1）→ `ScreenCapture: capture resources released`，服务退出，状态行显示「采集✗」，无泄漏迹象，PID 不变；转回竖屏正常。
- 快停快起（停止后立即重新授权）→ 新的 `capture started`（不同 VirtualDisplay），无 ANR、无死锁。
- 缺投影 token：进程被杀后系统**不再**拉起 ScreenCaptureService（START_NOT_STICKY 生效），状态行不复位为「采集✓」——第一轮报告的僵尸服务现象已修复。
- 注意：本系统映像在应用进程被 kill 时会清空 `enabled_accessibility_services`，需重写设置并做一次移除/恢复切换才重新绑定；测试脚本已按此处理。

## T03 确定性故障注入 —— **BLOCKED（端到端）**

设备侧无可控"持续 null 帧/持续无有效帧"注入入口：帧缓存使 `currentFrame()` 在采集存活期间永不为 null；采集销毁即经 `onDestroy → stopAll`。等价覆盖：instrumentation `conversionFailureRecovers`（真实 ImageReader 转换故障后恢复）PASS；JVM `captureErrorDiscardsCacheAndNextSuccessfulFrameRecovers`、`invalidatedCacheActuallyFeedsMissingFrameWatch` PASS。健康静止页面不误停由 `unchangedPixelsDoNotExpireReadOnlyMonitoring` 单测覆盖。

## T04 操作后验证 —— **负路径单测覆盖；设备完整工作流因模型不合规未完成**

单测覆盖（均 PASS）：`waitTextDoesNotAcceptInvalidatedPreActionFrame`（操作前缓存不得满足 wait_text）、`missingFrameDoesNotProveTextDisappeared`+`validEmptyOcrCanProveTextDisappeared`（必须等到新帧）、`gestureInvalidationRejectsConversionStartedBeforeGestureEnded`+`slowConversionDoesNotFreshenAnOldAcquisition`（手势屏障拒绝滞后/排队旧 Image）、`cancellingWaitTextPropagatesCancellation`（超时/取消传播）。设备侧 happy path 由 T07-real 承担，但该套件未跑到验证段（见下）。

## T05 通知运行时权限 —— **PASS（设备）**

denied → 点击「开启屏幕采集」→ 系统弹窗出现（图标/文案正确）→ 点 Allow → 继续录屏授权 → 采集启动回调到达 → 套件判 PASS。修复方已验证：拒绝后显示风险说明、不循环弹窗、「去设置」跳转正确；JVM 另覆盖 first-install/denied-warn/granted/disabled-channel/Android12 分支。历史结论不变：权限被拒绝时通知与「停止全部操作」不可见（P1，已由 R03 修复）。

## T06 通知「停止全部操作」迟到请求 —— **PASS**

（权限已授予，通知可见）真实会话进行中（host 假 AI 60s 拖住 2 个请求）→ 点通知 action → 迟到响应全部释放（status 200）但 app **未发任何后续请求**，70 秒观察无复活；采集服务按设计保留。与 T07 套件内 S04 的"全部停止 60s 零复活"互相印证。

## T07 假模型套件 S01–S04 —— **PASS（最终包全量）**

`am instrument -w -e suite mock` 全量重跑：
- S01 PASS：迟到工具未执行；60s 无复活；requests=1。
- S02 PASS：新会话正常完成；旧创建被忽略；requests=2。
- S03 PASS：idle 事件进行中取消 + 停止 60s + 重启 15s 不重放。
- S04 PASS：仅关 AI 引擎继续；全部停止保持停止，各 60s。
任务字节全程未变（与测前一致）。

## T07-real 真实模型验收 —— **部分 PASS**

- 真实提供方链路验证：`REAL_TOOL_OK: describe_screen`、`get_screenshot`、`locate_text` 均成功（真实模型 + 生产工具 + 真实投影/OCR）。补充冒烟：生产会话用真实端点回复了准确的上下文分析（正确指出当前无障碍已关）。
- **一次点击 + WaitText 验证 + 停止 60s 段未完成**：两次独立运行中模型均自行选择了提示词白名单之外的 `swipe_screen`（套件的 `test policy blocked tool: swipe_screen` 主动拦截），非生产缺陷；属模型对工具限制的遵循问题。建议修复方：在提示词中把"只准使用"改为更强的约束（显式列出禁止项+原因），或验收提示改为独立 round 重试。

## R06 差异核对 —— **完成**

- 改动范围：17 个文件修改、8 处新增（CaptureFrameStore/CaptureWorker/NotificationAccess/AutomationGate/WaitForText、androidTest/debug/test 源集、docs）；新增文件均为 ASCII/UTF-8，无编码异常；未发现构建产物或密钥进入 diff。
- 未提交任何代码（按约束）。

## 线程收尾（T08）与遗留

- 恢复项在移交时逐项核对：文件（tasks/rules/settings/log）按备份还原并读回校验、`repair-mock-passed` 缓存删除、POST_NOTIFICATIONS revoke、无障碍恢复为启用（工作原状）、adb reverse 移除、mock 服务停止、模拟器关闭。
- 遗留给修复方（不影响本轮结论）：① 撤销投影时 `abandonLocked` 日志噪音；② 系统 kill 应用后 `enabled_accessibility_services` 被清空是该模拟器系统行为，app 可在设置页引导用户重新开启；③ 真实模型对工具白名单的遵循需提示词加固。
