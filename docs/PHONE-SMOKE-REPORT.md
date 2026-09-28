# 实机冒烟验收（荣耀 V30 / EMUI，Android 10）

日期：2026-09-28 晚。测试方（第二方）在真机上的补充验收；接续 `ADB-DEBUG-ACCEPTANCE.md`（修复方当轮全部在 API 37 模拟器完成）。

## 环境与产物

- 设备：HLK-AL00（荣耀 V30），EMUI，Android 10 / API 29，serial `C7Y6R19814007892`，物理真机（用户日常使用机）。
- 安装：`adb install -r` 保留数据升级，主 APK SHA-256 `9AEE711BB1CFC92D8BBAF250881ACD32CDA6960FC78F6342F7720013569FE32A`（提交 `d06cf72` 的构建产物，与 BUILD-VERIFICATION.md 一致）。
- 升级前旧包：`ccc289a32e2bbc8453c0d7b380d65dda708af3e806c60e12aa4e01b047fd0d3a`（2026-09-26 23:52 构建，即未含采集竞态修复的版本），已留档 `.tools/backup/phone-old-build.apk`。
- 装包前备份：`.tools/backup/phone-20260928/`（device.tar + manifest.json，3 个文件）。手机任务库为 ID 1–3 且全部 disabled；此前模拟器侧"任务 4"数据事故从未波及手机。
- 测试前状态：app 未运行、无障碍服务关闭（`enabled_accessibility_services` 为空）、无活动自动化。

## 结果

| 检查 | 结论与证据（`.tools/` 下） |
|---|---|
| 升级安装与数据保护 | PASS：install -r 成功；`files/tasks.json`、`shared_prefs/com.google.mlkit.internal.xml` 逐字节一致（sha256 复核）；`files/profileInstalled` 有 4 字节时间戳变化，对应 logcat 22:17:17 `ProfileInstaller: Installing profile`，属新包首启时 AndroidX 写内部状态，非用户数据 |
| 启动与 UI | PASS：MainActivity 正常聚焦渲染，状态行"无障碍✗ 悬浮窗✓ 采集✗"与实际权限一致（`phone-launch.png`） |
| EMUI 采集授权 | PASS：系统弹窗为两键式（禁止/允许）单步确认，不同于模拟器的"分享整个屏幕→分享屏幕"两步；点允许后采集启动（`phone-consent.png` / `phone-capture-on.png`） |
| 通知兼容分支（Android ≤12） | PASS：API 29 无 POST_NOTIFICATIONS 运行时申请路径，前台通知直接发布成功——id=1001、channel=capture、资源图标 `ic_stat_notify`、actions=1 含"停止全部操作"（dumpsys notification 记录） |
| 通知动作 | PASS：点"停止全部操作"无异常，进程稳定，采集按设计保留（与模拟器 T06 行为一致）（`phone-after-action.png`） |
| 停止采集 | PASS：UI 停止后通知记录清空、`dumpsys media_projection` 为 null（`phone-capture-stopped.png`） |
| 进程稳定性 | PASS：全程 PID 31200 不变（启动→采集→通知动作→停止），真机上无崩溃、无 SIGSEGV——旧包带有的采集销毁崩溃在本轮新包上未复现 |
| 数据保护 | PASS：任务 1–3 全程 disabled，未启动引擎/AI/自动化，未触碰真实任务库 |

## 发现（交接修复方）

- **P1：DebugCommandReceiver 在 EMUI 上不可用（CLI 全部命令失败）。**
  - 现象：包装器报 `broadcast not accepted`（exit 2）；原始 `am broadcast` 始终 `result=0`，无 `FLOWCLICKER:` 标记；logcat 无崩溃、无接收器痕迹；`cache/adb-debug/` 目录从未被创建。
  - 排除项：广播已到达（`dumpsys activity broadcasts` 有 4 条显式组件记录且解析到接收器）；包确为 DEBUGGABLE（pkgFlags）；坏 payload 探测（应回 `FLOWCLICKER:INVALID_REQUEST`，result=2）同样返回 result=0。
  - 推断：`DebugCommandReceiver.kt:27` 的 `!isOrderedBroadcast` 门卫在本机恒为真——EMUI 广播管线（华为并行/代理分发优化）未保留 `am broadcast` 的有序语义，接收器被静默短路；而所有通过门卫的路径都必然落盘或 setResult，与观察矛盾的唯一自洽解释即此。
  - 修复方向（由修复方定夺）：容忍非有序分发（ admission 落盘逻辑本已存在，可依赖既有 `result --request-id` 轮询流），或改用不依赖 ordered 的通道；DUMP 权限与 debuggable 门卫保留。模拟器（API 37）不受影响。
- 次要观察：MainActivity 状态行不随采集启停实时刷新（停止采集后仍显示"采集✓"，重进页面才更新）；仅状态文本问题，服务/投影/通知的实际状态均正确。

## 覆盖边界

- CLI 的 OCR/定位/frame.dump 未在真机验证（被上述 P1 阻断）；`engine.start` / `tasks.run` / `ai.wake` 有意不跑（真实任务库）。
- 无障碍服务保持关闭（原状即关闭），未验证其绑定行为；未测息屏/Doze/长跑；历史 T03 持续缺帧端到端仍 BLOCKED。
- 本轮未修改任何代码；`REPAIR-ACCEPTANCE.md` 等历史结论不改写。

## 现场恢复

- 采集/录制/通知已清，media_projection=null；任务数据逐字节核对（profileInstalled 变化见上表说明）。
- 手机上临时文件 `/sdcard/phone-ui.xml` 已删除；app 停在主页，进程留在系统缓存（按 EMUI 无障碍保护约定不 force-stop）。
- 证据：`.tools/phone-*.png`、`.tools/phone-ui-dump.txt`、`.tools/phone-status-stderr.txt`、`.tools/backup/phone-20260928/`、`.tools/backup/phone-old-build.apk`。
