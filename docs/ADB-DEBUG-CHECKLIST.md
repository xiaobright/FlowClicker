# ADB 调试入口与真实点击补验收

2026-09-28。本轮接续入口；每完成一项更新，不把 BLOCKED/PARTIAL 勾成全通过。

**最终状态：C00–C07 全部完成。没有待续测试进程；模拟器已关闭，现场已恢复。** 结果与覆盖边界见 `ADB-DEBUG-ACCEPTANCE.md`，命令用法见 `ADB-DEBUG.md`。主 APK 仍为 `9AEE711B…`，没有提交/推送。

## 范围

- 基线 HEAD `eef1db4` + 现有未提交修复；已核对磁盘 APK SHA-256 `E97D957969B7A8E308B2BD481CCB4E140EF6A5E839B494901B126EAC0E2F7587` 与第二方报告相符。
- 保留所有既有修改，不提交/推送，不回灌任务 4，不卸载/清应用数据。
- debug 专用 ADB 命令入口，复用生产控制/校验；不开放任意 Kotlin/脚本/文件路径执行，不绕过投影系统授权。
- 仅允许 shell/系统拥有的 DUMP 权限或应用自身调用。release 合并 manifest、DEX 都必须无此入口。
- 返回关联请求 ID 的 JSON，错误非零退出；wake/run 的 accepted 不代表完成。不用 logcat 传递配置、密钥、截图或全文参数。
- 修正真实测试夹具并完成一次点击→WaitText。先重跑新包假模型，再调已有真实端点；工具白名单和点击边界继续有效，不为通过测试放宽成任意滑动。

## 清单

- [x] C00：核对接续资料、APK 哈希、现存改动与进程（无 emulator/qemu/java；ADB daemon 存活）。
- [x] C01：debug receiver、DUMP 权限保护、20 个命令、带 ID 的私有 JSON 结果；同 ID 预留后不重放。设备验证见 C04。
- [x] C02：Python/uv 包装器与 ADB-DEBUG.md 已写；base64 笔误和 exec-out 缺结果误判已修复，8 项 Python 测试全过。
- [x] C03：49 项 JVM 全过（原42+DebugProtocol7），Python 8 项全过；debug/release/androidTest 构建成功。release 合并 manifest 与全部 DEX 均无调试入口/协议字符串；无 debug 网络配置。
- [x] C04：装包前二进制备份、设备 CLI 成功/错误/权限拒绝、修改后恢复、frame_dump/OCR。
- [x] C05：核查真实测试页面是否真正可见并有投影新帧；确定性点击+WaitText 通过（`.tools/adb-debug-workflow-2.log`）。
- [x] C06：最终包假模型 S01–S04；通过后真实模型定位→点击一次→WaitText→停止 60s。
- [x] C07：恢复本轮数据/权限/无障碍/旋转与测试资源，核对哈希，保存最终产物和未覆盖项。

## 已知报告边界

- REPAIR-ACCEPTANCE 的真实点击未完成；白名单拒绝仅说明安全限制生效，尚不足以证明失败原因只是模型。
- T03 端到端 BLOCKED 不等价于 PASS；当前实现可在错误/手势后主动清缓存，报告中“缓存永不 null”的理由已不完全适用。
- `ConsumerBase` 是平台日志，不为降低噪音改写框架或吞异常。
- “dumpsys 自定义输出必须系统签名”不作为设计依据；本轮选择 receiver 是为控制与机器可读结果，不是因应用无法实现 Service.dump。

## 执行记录（历史断点，不是当前待办）

- 本轮启动 Pixel_10 headless PID=25568（`.tools/adb-debug-emulator.pid`）；尚未改设备数据或调用模型。
- 首轮 Gradle 构建 session 14429（debug/release/androidTest），日志 `.tools/adb-debug-build.log`，须先续接状态。
- session 14429 已结束，BUILD SUCCESSFUL 4m46s。主 APK SHA-256 `9AEE711BB1CFC92D8BBAF250881ACD32CDA6960FC78F6342F7720013569FE32A`，已保留数据安装主 APK/androidTest。CLI status 已成功读取设备状态，未调用模型。
- 新备份 `.tools/backup/adb-debug-20260928/`（22 文件，task IDs 1–4）与装包前无障碍/旋转/权限状态；结束只恢复这个基线，不使用其他轮次快照。尚无 mock/adb reverse。
- 测试夹具已加 shell 前台启动+窗口/OCR 双重就绪检查，新增 `-e suite workflow` 确定性设备点击；真实模型改用仅列出验收允许工具的独立提示词，白名单与按钮边界保留。androidTest 需重建。
- 旧报告只保留为历史证据，后续新结果写入本文件/新文档。
- 中断后核对：模拟器 25568/qemu 46456 仍在线，主 APK 9AEE711B 未变，无 Gradle/mock 在跑；未重建、未覆盖备份。
- CLI 设备首轮：状态/停止/AI开关、错误返回、UTF-8、单任务增改删、revision、重复 ID、规则校验均通过并恢复原定义；权限负测读取不存在的结果时，暴露 adb exec-out 可能退出 0 的传输边界，包装器已补“缺失/不完整结果”错误，需重跑 Python 和设备测试。首轮证据 `.tools/adb-debug-device.log` / `adb-debug-device-results.json`。
- CLI 第二轮 PASS：`.tools/adb-debug-device-2.log` / `adb-debug-device-results-2.json`，包括普通测试 App UID 的拒绝与无结果文件。任务增改删仅涉及新建 disabled 测试任务，finally 已移除并核对原任务定义；规则/AI开关恢复原值，自动操作停止。原始 JSON 字节最终仍按快照恢复。
- androidTest 定向构建确认全部 UP-TO-DATE（25s），新夹具已在产物中；主 APK 未变。当前长任务 session **38372**：正在跑 `suite mock`，日志 `.tools/adb-debug-mock.log`。开始前将无障碍恢复为本轮基线启用，并临时 pm grant 通知（结束须 revoke）。不得同时启动其他套件/模型。
- session **38372 已完成**：S01–S04 MOCK AUTOMATION PASS，包含实际 60s 观察窗；设备 mock 标记与当前主 APK `9AEE711B…` 匹配。接着运行 workflow，确认夹具/采集后再运行 real；C06 尚未整体完成。
- workflow 首次被模拟器启动遗留的 System UI ANR 弹窗遮挡（events 时间 11:08，非本 App 崩溃）；前台检查拒绝并干净停止采集。外部 uiautomator dump 与 instrumentation 的 UiAutomation 冲突，不再并用。
- 点击系统弹窗 Wait 后 workflow 第二次 PASS：真实采集/OCR→定位→手势→新帧 WaitText，原任务不变。另补测试夹具每次清新 task 并检查“等待操作”、未出现“验证成功”，避免重复运行借用旧成功画面；统一真实提示词白名单，修改仅在 androidTest，需定向重建后运行 real。
- androidTest 重建/安装完成（47s，主 APK 哈希未变）。real session **14887 已完成 PASS**：新夹具前台+OCR 就绪；5 次真实请求，describe_screen→locate_text→tap_screen→describe_screen→reply；仅点击一次；生产 WaitText 验证成功，停止 60s，原任务不变。证据 `.tools/adb-debug-real.log`，没有在日志输出凭据或真实响应正文。
- C04 仅余实际采集下 CLI OCR/PNG；新增只读 `tools/test_adb_debug_capture.py`，准备通过系统授权后运行。结束仍需恢复本轮基线、撤销通知权限、清本轮测试产物并关闭模拟器。
- CLI 实际采集 PASS：status / screen.describe / 中文 screen.locate / frame.dump；PNG 1080×2424，chunk CRC、解压、尺寸与 ID 关联全部验证，人工查看确为新建验收页面；证据 `.tools/adb-debug-capture-results.json` / `adb-debug-capture.log` / `adb-debug-frame.png`。
- CLI capture.stop 后 status 确认采集/帧/引擎关闭、AI 已停止；随后当前包 3 项真实 ImageReader 插桩测试全部通过（closeDuringCopy、conversionFailureRecovers、queuedPreGestureFrameIsDrained），证据 `.tools/adb-debug-capture-races.log`。开始最终恢复；当前已无测试套件运行。
- 最终恢复完成：本轮快照 22 个文件逐一 SHA-256 回读匹配，文件集合也与基线相同；通知 permission/flags、无障碍 enabled/bound、旋转设置均核对。63 个本轮请求 JSON/PNG、mock 标记、临时窗口 dump 逐文件删除，清单在 `.tools/adb-debug-device-cleanup.json`；没有删除历史任务/截图/备份。
- 采集/录制/引擎已停、adb reverse 为空；`adb emu kill` 完成后 `adb devices -l` 为空，原 emulator/qemu PID 均不存在。恢复证据 `.tools/adb-debug-restore.log` / `adb-debug-restored-state.json`。
- release 隔离复核通过；按本项目 DebugCommandReceiver/网络配置/DEX 检查，不把 AndroidX ProfileInstallReceiver 自带的 DUMP 权限误报为泄漏。最终主 APK 哈希仍为 `9AEE711B…`。
- 已保存 `ADB-DEBUG-ACCEPTANCE.md` / `BUILD-VERIFICATION.md`；保留所有未提交修复，不提交/推送。主机 `.tools/` 证据保留且不应发布（备份含凭据）；Python 运行缓存已加入精确忽略规则。
