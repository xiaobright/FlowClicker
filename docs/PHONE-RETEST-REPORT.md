# EMUI 实机 CLI 复验（2026-09-28）

接续 `PHONE-SMOKE-REPORT.md`。本报告纠正该报告对 P1 根因的推断；历史模拟器结果不计作本轮实测。

## 基线与边界

- HEAD：`d06cf72`。接续时已有未跟踪 `.claude/` 与 `docs/PHONE-SMOKE-REPORT.md`，保持原状。
- 手机：HLK-AL00，Android 10 / API 29；本轮不修改无障碍设置，不调用真实模型，不启用或修改用户任务/配置。
- 从手机读取安装包，SHA-256 确为 `9AEE711BB1CFC92D8BBAF250881ACD32CDA6960FC78F6342F7720013569FE32A`。**主 APK 未重建、未重装，Receiver 未修改。**
- 开始时主进程不在、包状态 `stopped=true`；本轮通过普通启动打开主页，没有执行 force-stop、清数据或卸载主应用。
- 本轮单独备份 3 个持久文件（任务 ID 1–3）和已安装 APK，位于忽略目录 `.tools/phone-retest-20260928/`。未使用默认会 force-stop 的 `device_snapshot.py backup/restore` 路径。
- 临时安装既有 androidTest APK 作为无网络验收页面和普通 UID 权限探针，SHA-256 `EF1626FC15D84697E554172FA21FAB9212DFE2E39E1D294A6E42CFD9D67A4878`；没有运行 instrumentation 套件。

## P1：已定位并修正 CLI

在**同一 APK、同一进程、同一广播参数**上做 action 有/无对照：

| 输入 | 实测结果 |
|---|---|
| 原 CLI：只有显式组件、没有 action | `result=0`，无 `FLOWCLICKER`，无结果文件；系统记录 `ordered=true`、接收器 `Skipped` |
| 仅补 `-a com.flowclicker.app.DEBUG_COMMAND` | `result=0, data="FLOWCLICKER:<id>"`，关联 JSON `ok=true`；系统记录 `ordered=true`、接收器 `Deliver` |
| 有 action、坏 payload | `result=2, data="FLOWCLICKER:INVALID_REQUEST"` |
| 无 action，另加 `--user 0` 或 include-stopped | 仍不送达，不能修复问题 |

因此，上一份报告“EMUI 丢失 ordered 语义、Receiver 门卫拦截”的推断被本轮对照推翻。组件解析/历史记录不等于 `onReceive` 已调用。已确认的触发条件是**本机跳过 action 为空的显式广播**；没有进一步声称已定位厂商框架的内部实现。

### 后台补测发现的第二个分支

只补 action 后，前台 CLI 和刚切换的采集页面全部通过，但持续在后台时 EMUI 代理先让 shell 返回空回执，随后又实际投递了同一广播：

- 原 `automation.stop` 请求 `8a62c67916ce4f39b5074d82a407cef7` 被 CLI 报为未受理；读取**原 ID** 得到 `ok=true, accepted=true`，没有重发。
- 系统记录同一 ID 的 `Pending/resultData=null` 和随后 `Deliver/resultData=FLOWCLICKER:<id>`；两者依旧 `ordered=true`。这不是 Receiver 丢弃非有序广播。
- 另一个只读 status 对照也得到“shell 无标记、关联 JSON 成功”。证据：`broadcasts-background-failure.txt`、`background-status-probe.json`、`recovered-stop-result.json`。

### 最终修复

- `tools/adb_debug.py` 增加固定 action；继续使用显式组件、关联结果和不自动重发语义。
- 缺回执时，仅对 CLI 新生成的 UUID 有界读取同一结果文件，最多轮询 10 秒（单次 ADB 另有 25 秒传输超时）；不重新广播。显式 ID 或明确拒绝不回退，不吞 ID/格式异常，不把 admission 占位误判为完成。
- 不移除 `isOrderedBroadcast`、debuggable 或 DUMP 门卫，不增加非有序接收或新的 App 入口。
- 普通 UID 的权限负测也补相同 action，避免把“空 action 被跳过”误判为“DUMP 拒绝成功”。
- 完整设备测试的新建请求改用 CLI 生成 ID，再取响应 ID 做重复请求测试，避免首次显式 ID 在后台无法安全回退。本轮未在日常手机执行其持久修改部分，仅做语法检查。
- 新增 action/显式组件、重复 ID、缺回执完成/等待/超时、明确拒绝不回退及错误响应校验，共 7 项主机回归。

证据：`action-ab.json`、`broadcast-probes.json`、`broadcasts-after-action.txt`（均在本轮忽略目录）。

## 本轮验证

- **PASS：最终 Python 15 项测试**，包括新增 7 项；日志 `python-tests-final.log`。早期 action-only 的 10 项结果另存，不冒充最终验证。
- **PASS：实机 CLI 只读/安全错误路径**：status、tasks.list/get、rules.get、未知命令/参数、无采集的 OCR/定位/PNG 失败、无启用任务的 engine.start 拒绝、中文/引号传输、重复 ID 拒绝及原结果保持、坏 payload、automation.stop。
- **PASS：DUMP 权限真实拒绝**：普通 androidTest UID 广播未生成结果；系统明确记录 `Permission Denial ... requires android.permission.DUMP`，不是仅以缺结果判断。证据 `protocol-results.json`、`protocol-tests.log`、`permission-logcat.txt`。
- **PASS：实机采集/OCR/中文定位/PNG**：从系统两键授权弹窗启动采集，前台只展示无网络测试页面。`screen.describe` 识别到“安全点击”，`screen.locate` 唯一命中且中心在屏幕内，PNG CRC/解压/尺寸/请求关联全部通过；人工确认画面为验收页，1080×2340。最终 CLI 在持续后台状态下再次通过同一测试；证据 `capture-background-results.json` / `capture-background-tests.log` / `capture-background-frame.png`。
- **PASS：停止语义**：原 `automation.stop` 已执行且采集/有效帧保留；最终 CLI 在后台执行 `capture.stop` 后采集、有效帧、引擎均为 false，AI 已停止。
- **PASS：停止后 60.297 秒未复活**，采集/帧/引擎仍关闭、AI 已停止；启动后的主 PID 始终为 6746，未观察到崩溃或进程重启。证据 `stop-tests-final.log` / `stop-observation.json` / `logcat-final.txt`。
- **PASS：最终空回执回退有实机证据**，后台 status 的 shell 输出无 `FLOWCLICKER`，最终 CLI 仍取得同 ID 的成功 JSON；停止 60 秒后的 status 也如此。不是只在主机 mock 中通过。证据 `background-cli-broadcasts.json` / `stop-results-final.json`。
- **PASS：通知与投影清理**，采集时存在 capture 渠道的 id=1001 前台通知和停止 action；停止后无该应用活动通知，`media_projection=null`。本轮未再次点击通知 action，不混用历史点击结果。
- **PASS：静态检查**，3 个修改的 Python 文件语法有效，`git diff --check` 通过，文本 UTF-8 可读；Receiver 和 debug manifest 与 HEAD 一致，主 APK 哈希未变。

## 最终数据与现场

- `files/tasks.json`、`shared_prefs/com.google.mlkit.internal.xml` SHA-256 与本轮开始备份一致，持久文件集合仍为 3 个，任务 ID 1–3 未变化。
- 唯一不同是 AndroidX profile 内部状态：`files/profileInstalled` 前后均 24 字节，只有偏移 22、23 共 2 个字节改变。未回写内部状态，也不宣称“所有文件逐字节一致”；详细哈希见 `persistent-file-verification.json`。
- 本轮 34 个已记录请求 JSON/PNG 先复制到本地证据目录，再逐文件删除；手机 debug cache 已空。未删除未识别文件、历史备份或用户数据。
- 临时 androidTest 包与 `/sdcard/flowclicker-retest-ui.xml` 已移除，主应用回到主页；没有卸载/重装主应用、force-stop 主应用或改无障碍设置。
- 收尾脚本曾把卸载后 `pm path` 的预期退出 1 当成传输错误；随后只读复核确认包确实不存在，没有重复执行清理。
- 最终再次确认：原 APK 哈希、用户文件哈希、无障碍原值、PID 6746、投影 null、无活动通知。证据 `cleanup-and-final-state.json`。没有遗留测试进程或需要继续等待的会话，手机可以拔下。
- 本轮仅修改 CLI、主机/设备测试脚本与文档；**没有提交或推送**。`.tools/` 全部保持忽略，其中备份、日志、截图可能含私人数据，不应发布。

## 未覆盖

- 不重跑历史 49 项 JVM、S01–S04、真实模型点击/WaitText、ImageReader 竞态或 release 隔离；本轮仅改 Python CLI/测试及文档，无 Android 源码或产物变更。
- 不在日常手机上运行会修改任务、规则、AI 设置的完整 `test_adb_debug_device.py`；本轮单独覆盖非持久修改路径。
- T03 持续缺帧端到端仍 BLOCKED；无障碍绑定、息屏/Doze、长跑不计作通过。
- 主页状态行问题与本次 CLI 传输修复分开，不顺带修改 UI。
