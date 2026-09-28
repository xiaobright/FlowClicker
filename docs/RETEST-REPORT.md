# 新 APK 补测报告（2026-09-27 晚间）

- 被测包：`app/build/outputs/apk/debug/app-debug.apk`，SHA-256 `4e6f3510451cb999183fc0aba6f434201cbf5ec3735db96617e630ba981f0d29`（与 RETEST-HANDOFF 一致）
- 设备：Pixel_10 模拟器（emulator-5554，Android 16k 系统映像），`adb install -r` 保留数据安装
- 装包前备份：`.tools/backup/retest-20260927/`（7 个私有文件）
- 模型：全程本机假 AI（tools/mock_ai.py，adb reverse tcp:8765），未调用真实模型
- 约束遵守：未改代码、未提交、未卸载、未清数据；结束后已恢复现场（见 §4）

## 0. 数据事故（重要，需修复方知晓）

装包后引擎无法启动，逐步排查发现：

- 设备 `files/tasks.json` **被截断**（2516 字节，结尾停在任务 4 步骤中间）。app 因 `loadError` 拒绝所有启动/更新/写入（P1-3 保护逻辑实际生效，未覆盖丢失数据）。
- 现存全部副本同样截断：本轮预备份 + 上轮 `app-files-pre-test.tar`（tar 头部也损坏，仅 python `tarfile` 能读出同一份截断内容）。截断早于本轮发生，上轮「恢复成功」的校验不充分。
- 恢复：任务 1-3 从完好前缀逐字节还原；任务 4「点击开始战斗」按已知值重建（click 538,1601 anchor=开始战斗 keywords=[开始战斗] debug；`enabled=true` 与尾字段为推定，其余用模型默认值）。写回后 app 解析正常、4 任务显示正常。
- 测试期间假 AI 陆续创建了 4 个「回归·只创建一次」任务（mock 默认 payload 被执行 4 次，其中 1 次来自 stall 看门狗触发会话），结束清理时全部移除。

## 1. 优先复核

### N01 通知停止动作 —— **PASS（附 1 个 P1 bug）**

| 子项 | 结果 | 证据 |
|---|---|---|
| 通知存在 | 需先授予 POST_NOTIFICATIONS 才出现 | 采集启动后 dumpsys 无任何 `NotificationRecord`；`pm grant` 后 id=1001、channel=capture 出现 |
| action 展示 | ✓ | 通知栏可见「停止全部操作」（SystemUI action0 按钮，bounds=[200,875][484,1001]）；dumpsys `actions=1` → PendingIntent broadcast → StopAutomationReceiver（manifest exported=false+显式 Intent，合法） |
| 点击停引擎 | ✓ | 点击 action 后任务列表显示「引擎：已停止」 |
| 进行中 AI 会话取消 | ✓ | mock 拖住响应 25s，点击后迟到响应释放（status=200）但 app 未发第 2 轮请求；对照自然流程 +0.07s 即发 round 2 |
| 采集服务是否继续 | 按设计保留 | 点击后 ScreenCaptureService 仍存活、录屏芯片继续；stopAll() 不含停采集 |
| 60 秒不唤醒 | ✓ | 观察期内 mock 无新请求，引擎停后看门狗自然失效 |

**BUG（P1，交修复方）：`POST_NOTIFICATIONS` 从未在运行时请求。** manifest 有声明，但 MainActivity/ScreenCaptureService 无 `requestPermissions`。Android 13+ 新装必然静默无通知，「停止全部操作」对用户不可见（本轮经 `pm grant` 才能测 action）。另：被取消的会话在 AI 日志里不留记录（CancellationException 被 WakeDispatcher 直接 rethrow），可观测性小瑕疵。

### F01 短暂缺帧（<5s）引擎存活 —— **BLOCKED**

端到端无法制造 null 帧：采集活着时 `currentFrame()` 返回缓存帧（latestFrame 永不为 null）；采集销毁即经 `onDestroy → stopAll()` 直接全停，引擎不会带 null 帧空转。FrameGapWatch 逻辑由单测覆盖：`temporaryMissingFrameDoesNotStopMonitoring`；本机 24 个单测全部通过（0 失败）。

### F02 持续缺帧/采集撤销

#### F02-a 系统撤销采集（录屏芯片 → Stop sharing）—— **FAIL（原生崩溃，P1）**

证据链（logcat 时间戳 14:41:41）：`MediaProjectionManagerService: Stopping active projection` → `ScreenCapture: media projection stopped by system`（回调与 stopSelf 正确）→ `ConsumerBase abandonLocked` → **`libc SIGSEGV in tid frame-capture`** → tombstone：`memmove_avx2`（拷贝已释放的 Image plane 缓冲，`imageToBitmap` 与 `imageReader.close()` 的竞态；Kotlin try/catch 拦不住原生段错误）。后果：整个进程死亡，activity 强制关闭，采集通知移除，AI/引擎/采集确实都停了，但 a11y 服务被系统判定 crashed 后 1 秒自动拉起（设置级开关未丢）。修复方上轮的 ImageReader 异常处理未覆盖此路径。建议：销毁时先停帧回调并 join captureThread，再 close reader，或保证拷贝期间 reader 存活。

#### F02-b FrameGapWatch >5s 退出并记录 `runner stopped` —— **BLOCKED**

同 F01：真机不可达（缓存帧+服务销毁直连 stopAll 两条路径都不产生"活着但持续无帧"）。单测 `sustainedMissingFramesStopMonitoring` 覆盖。

### S01 停止时模型还没返回 —— **PASS**

迟响应 15s 窗口内点「停止全部自动操作」：迟到响应释放后无第 2 轮请求、无新任务、无引擎启动/点击，60 秒观察无复活。

### S02 停止后立即手动开新一轮 —— **PASS**

停止后立即再唤醒：新会话正常结束（mock count+1、AI 日志追加正常）；旧轮的迟到创建指令未执行（任务数、请求数不变）。

### S03 后台任务完成与看门狗到期不重放 —— **PASS**

短规则（idle 20s/stall 20s）+ mock 59s 迟响应：看门狗事件触发的会话进行中点「停止全部操作」→ 迟到响应 60s 后释放但无第 2 轮请求（23:54:57 最后一个调用，停止后计数冻结 95 秒）；重启引擎后 **19 秒内零请求**（旧队列事件不立即重放），新 idle 事件按正常 20s 节律（23:58:13/23:58:33）才到来。

### S04 停用 AI 与全部停止的区别

- **只关 AI（PASS）**：AI 页取消勾选并保存 → settings 落盘 enabled=false → 引擎继续运行；5 分钟无干扰浸泡测试内 mock 零请求（90s idle 边界被看门狗层直接拦截：`gate.permits(enabled=false)` 恒 false）。
- **全部停止（PASS）**：引擎运行中点停止 → 80 秒跨 stall 边界零复活；通知 action 路径见 N01。
- 手势收尾：S01/N01/S03 均证明停止后无后续派发。

## 2. 未跑项（本轮未覆盖）

按 RETEST-HANDOFF：D02（UI 旧快照/版本冲突）、E02（结果验证与转正门）、E03（锚点/输入边界）、E02/E03 之外的 A02（队列/轮数）、A03（scoped idle/stall 细节）、L01（旋转/撤权）、L02（15-30 分钟长跑）、E04/E05/A04 均记 **NOT_RUN**。上一轮旧 APK 的 D01/D03/E01/A01 结论仅作回归参照，不计入本包 PASS。

## 3. 异常记录（交修复方）

1. **F02-a 原生崩溃**（见上，P1）。
2. **POST_NOTIFICATIONS 运行时未请求**（P1）。
3. 一次引擎在密集 UI 交互期间自行停止（23:24，无 runner stopped 日志、无 stopAll 调用源可循；随后 5 分钟无干扰浸泡未复现，推测与自触任务点击落在变化的窗口上或 IME/导航时序有关，需关注任务自匹配场景）。
4. 取消的 AI 会话不写日志（可观测性小项）。
5. 采集服务实例在进程死亡后由系统拉起但没有投屏 token（`isRunning=true` 但 `currentFrame()=null`），表现为「采集✓ 但无法启动引擎」；属 force-stop 后的环境副作用，app 侧建议对无投影的僵尸服务自检或重启引导。
6. 模拟器输入偶发丢失（顶部 16px 可点缝、uiautomator dump 偶发陈旧窗口），测试实现层面的注意点。

## 4. 现场恢复确认

- `tasks.json`：4 个原始任务（2594B，与重建版一致），3 个 mock 创建任务已移除
- `wake_rules.json`：原始规则（idle 90s + stall task2 60s，96B）
- `ai_settings.json`：原始 LongCat 配置（212B，与备份逐字节一致）
- `ai_log.json`：还原为装包前版本（23023B）
- `ai_memory.json` / `ai_task_notes.json` / `ai_sessions/task_4.json`：测试期间未新增，未动
- `POST_NOTIFICATIONS`：revoke 回 denied（装包前状态）
- 采集服务已停、引擎/AI 未运行、adb reverse 已移除、`/data/local/tmp` 残留已清、mock 进程已停、显示密度已 reset
- 未提交任何代码；`.tools/` 下保留本轮证据（截图与日志快照）
