# ADB 调试接口与真实点击验收

日期：2026-09-28。HEAD `eef1db4` + 既有未提交修复 + 本轮 debug 接口/测试；未提交、未推送。

## 被测产物

- 主 debug APK SHA-256：`9AEE711BB1CFC92D8BBAF250881ACD32CDA6960FC78F6342F7720013569FE32A`。
- 最终 androidTest APK SHA-256：`EF1626FC15D84697E554172FA21FAB9212DFE2E39E1D294A6E42CFD9D67A4878`。
- 设备：Pixel_10 / Android API 37 模拟器，`emulator-5554`，1080×2424；不是物理真机。
- 本轮主要新增代码只在 debug / androidTest / 主机工具。既有采集修复保留，未借机重构。

## 结果

| 检查 | 结论与证据（工作区内） |
|---|---|
| 构建 / JVM | PASS：debug、release、androidTest 构建成功；49 项 JVM，0 failure/error/skipped；`.tools/adb-debug-build.log` 与 XML 报告。本次接续复核原构建结果，未机械重跑主包 |
| Python CLI | PASS：8 项单测；覆盖 UTF-8/引号、ID/体积校验、权限/缺结果不能算成功、不重试、PNG 固定路径与拒绝覆盖 |
| CLI 状态与变更 | PASS：状态、全部停止、AI 开关、任务增改删/启停、revision 冲突、规则校验、重复 ID 防重放、结果重读；仅新增一个 disabled 测试任务，finally 删除并核对原定义；`.tools/adb-debug-device-2.log` |
| CLI 负路径 / 权限 | PASS：缺采集、引擎未启动时试跑、AI disabled 时唤醒、未知命令均拒绝；普通测试 App UID 不能调用 Receiver，未生成结果文件 |
| CLI 真实采集 | PASS：screen.describe / 中文 screen.locate / frame.dump；1080×2424 PNG 的 CRC、解压、尺寸和请求 ID 均核对，人工查看是隔离测试页面；`.tools/adb-debug-capture-results.json` |
| 确定性点击 | PASS：前台测试窗口 + 生产 OCR 就绪 → locate_text → tap_screen → 新帧 WaitText；原任务不变；`.tools/adb-debug-workflow-2.log` |
| 假模型 | PASS：当前主 APK S01–S04 全通过，包含实际 60s 等待窗口；`.tools/adb-debug-mock.log` |
| 真实模型 | PASS：假模型标记与主 APK 哈希一致后调用既有真实端点；5 次请求，describe_screen → locate_text → tap_screen → describe_screen → reply；仅点击一次，生产 WaitText 验证“验证成功”，停止 60s 无复活、原任务未变；`.tools/adb-debug-real.log` |
| ImageReader 插桩 | PASS：closeDuringCopy（内部 10 次）、conversionFailureRecovers、queuedPreGestureFrameIsDrained；`.tools/adb-debug-capture-races.log` |
| release 隔离 | PASS：合并 manifest 无 Receiver/debug 网络配置；2 个 DEX 均无 debug 入口/协议标记；没有 debug_network_security 资源 |

## 测试环境问题与修正

- 首次 workflow 被模拟器启动时遗留的 System UI ANR 弹窗遮挡，前台检查超时并停止采集，没有调用模型。events 中 ANR 属于 System UI（11:08），不是本 App 的采集原生崩溃。
- 点击系统弹窗 Wait 后重跑成功；失败日志/截图仍保留，没有把第一次结果改成 PASS。
- instrumentation 已占用 UiAutomation 时，外部 `uiautomator dump` 会发生服务注册冲突；此时用 ADB 截图检查系统授权，不再并用。
- 测试夹具现在每轮重建 Activity task，检查“等待操作”且不存在“验证成功”，再进行 OCR 预检；真实测试提示词与硬白名单保持一致。旧通知栏截图不再被误当成正常测试页。

## 覆盖边界

- 真实验收是**受限工具链**，使用生产 AiClient / AiTools / OCR / 手势 / WaitText，但不是开放权限的完整自主 AiSession，也不代表所有真实提示词都可靠。没有修改工具白名单来迁就失败。
- 本次真实模型选择了 OCR 路径，未调用 get_screenshot；CLI PNG 另行验证。更早报告的真实图像链路结果仍属于其原 APK。
- CLI 的 engine.start / tasks.run / ai.wake 在设备上验证了拒绝路径；其成功调度逻辑由生产假模型/工作流覆盖，未声称每个命令的所有成功组合都完成 CLI 端到端验证。
- 历史 T03 持续缺帧故障注入端到端仍 BLOCKED；JVM/插桩测试不冒充这项 PASS。
- 历史任务 4 重建值的来源不确定性仍存在，本轮未猜测或重建任何原任务。
- 采集开始仍需系统投影授权；调试 Receiver 不绕过系统权限。接口只供可信 ADB 主机使用，release 不包含。

## 恢复与接续

- 本轮唯一恢复基线：`.tools/backup/adb-debug-20260928/`，22 个文件、任务 ID 1–4；恢复脚本解析 JSON 并逐文件 SHA-256 回读验证。
- 恢复已完成（C07）：22 个文件 SHA-256 及文件集合匹配；通知拒绝状态/flags、无障碍启用并绑定、旋转设置均恢复。63 个本轮请求 JSON/PNG、mock 标记及临时窗口 dump 已逐文件删除，保留历史文件。
- 采集/录制/引擎已停止，无 adb reverse；模拟器已关闭，ADB 无在线设备，原 emulator/qemu PID 已退出。证据在 `.tools/adb-debug-restore.log` / `adb-debug-device-cleanup.json` / `adb-debug-restored-state.json`。
- 主机日志、截图和备份保留在被 Git 忽略的 `.tools/`，其中备份含凭据，不应发布或提交。
- 使用方法见 `ADB-DEBUG.md`；断点接续入口为 `ADB-DEBUG-CHECKLIST.md`。
