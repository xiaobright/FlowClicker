# ADB 调试命令（仅 debug APK）

目的：通过 ADB 复用 App 的任务库、引擎、AI 调度、OCR 和采集控制，不再靠像素点击配置页面。不绕过生产校验、会话取消或投影授权。

## 快速开始

```powershell
# 明确指定 adb devices 中的 serial
uv run --no-project E:\Desktop\mytests\android-test\tools\adb_debug.py --serial emulator-5554 status
uv run --no-project E:\Desktop\mytests\android-test\tools\adb_debug.py --serial emulator-5554 automation.stop
uv run --no-project E:\Desktop\mytests\android-test\tools\adb_debug.py --serial emulator-5554 tasks.list
uv run --no-project E:\Desktop\mytests\android-test\tools\adb_debug.py --serial emulator-5554 frame.dump --output E:\Desktop\mytests\android-test\.tools\frame.png
```

带参数时推荐 UTF-8 JSON 文件，避免 PowerShell → adb → Android shell 多层引号。文件只包含 args 对象：

```json
{"text":"检查当前屏幕，不修改任务"}
```

```powershell
uv run --no-project E:\Desktop\mytests\android-test\tools\adb_debug.py --serial emulator-5554 ai.wake --args-file E:\Desktop\mytests\android-test\.tools\wake.json
```

`ai.wake` 会使用当前保存的端点，可能发出真实模型请求，不是离线命令。调试工具不提供凭据查询或任意配置文件读写。

## 命令

| 命令 | args | 语义 |
|---|---|---|
| `status` | 无 | 引擎、AI、采集/帧/通知状态；不返回密钥或端点 |
| `automation.stop` | 无 | AI、引擎、录制全停；保留采集，等同通知停止按钮 |
| `engine.start` | 无 | 如 UI 一样检查任务/无障碍/采集，再启用引擎及正常唤醒 |
| `engine.stop` | 无 | **仅停引擎**，不等同全部停止 |
| `capture.stop` | 无 | 全停自动操作并请求停止采集服务 |
| `ai.enabled` | `{"enabled":false}` | 保存开关并调用生产设置变更处理；关闭时引擎可继续 |
| `ai.cancel` | 无 | 取消本轮与旧队列，不修改 enabled |
| `ai.wake` | `{"text":"..."}` | 入队；`accepted` 不等于模型已完成 |
| `tasks.list` / `tasks.get` | 无 / `{"id":4}` | 完整定义 / 单任务与笔记 |
| `tasks.upsert` | `{"task":{...}}` | 调用生产 upsert：新任务默认 debug，更新必须携带当前 revision |
| `tasks.enabled` / `tasks.delete` | `{"id":5,"enabled":false}` / `{"id":5}` | 单任务变更，不替换整库 |
| `tasks.run` / `tasks.result` | `{"taskId":5}` | 试跑入队 / 读取结构化结果；试跑需引擎运行 |
| `rules.get` / `rules.set` | 无 / `{"rules":[...]}` | 生产规则校验与保存，set 是整份规则替换 |
| `screen.describe` | 无 | 生产 OCR；需可用采集帧和屏幕控制权 |
| `screen.locate` | `{"text":"安全点击"}`，可选 `region` | 生产 OCR 定位，返回物理像素框与中心 |
| `frame.dump` | 无 | App 实际采集缓存的 PNG，不是 ADB screencap；可能含敏感画面 |
| `result` | 使用 `--request-id` | 只读已有结果，**不会重新发命令** |

采集开启仍从首页发起并确认系统授权；无障碍仍需系统授权。没有 `eval`、任意工具转发、任意文件路径或绕过屏幕控制锁的点击入口。

## 结果和中断恢复

- stdout 为 JSON：`{"id":"...","ok":true,"result":...}` 或 `{"id":"...","ok":false,"error":"..."}`。
- 退出码：0 命令成功/已受理；1 App 命令错误；2 传输/输入/权限错误。**ADB 返回 0 不代表命令执行成功。**
- stderr 先打印 request_id。每个请求最多 64 KiB，在广播中仅传 base64；结果写 App 私有 `cache/adb-debug/<id>.json`，不把敏感内容塞进 logcat。
- 同一 ID 在设备缓存存在时永不再次执行；不要重发，用以下命令读取。`IN_PROGRESS_OR_INTERRUPTED` 表示可能在执行或进程中断，先查状态，不自动重试有副作用命令。

```powershell
uv run --no-project E:\Desktop\mytests\android-test\tools\adb_debug.py --serial emulator-5554 result --request-id 0123456789abcdef0123456789abcdef
```

- 本入口是有界短命令（广播内约 8 秒）；不会在后台无界等待模型、任务或 OCR。超时可能已有部分效果，需查状态；缓存被系统清除后不提供跨缓存的 exactly-once 保证。
- JSON/PNG 留在 App 的专用 cache 目录以便故障接续；不要长期积存敏感截图。测试结束只清本轮已记录 ID 的文件，不清用户数据。
- `frame.dump --output` 以二进制获取 PNG，拒绝覆盖已有文件。

## 安全与发布

- Receiver 仅在 `src/debug` 注册并实现，要求 `android.permission.DUMP`。ADB shell/系统可调用，普通第三方 App 不可调用；这不是“所有安装 debug 包的应用都能发命令”的裸 exported Receiver。
- 仍需信任已授权 ADB 主机及持有系统权限的软件；debug 包本身通过 run-as 可读私有数据，勿作为发行包分发。
- release 构建检查必须同时核对合并 manifest 与 DEX 不含 `DebugCommandReceiver`，不能只看源码目录。
- instrumentation 保留给真实 ImageReader 竞态、故障注入与受控模型验收；CLI 不取代这些验证。

## 回归测试

- 主机单测：`uv run --no-project python -m unittest discover -s tools -p test_adb_debug.py -v`。
- `tools/test_adb_debug_device.py`：先备份、停止采集再执行；会临时修改 AI/规则并创建一个 disabled 测试任务，finally 还原定义。字节级恢复仍需 `tools/device_snapshot.py`。
- `tools/test_adb_debug_capture.py`：先通过系统授权启动采集并前台显示 androidTest 的 `RepairTestActivity`；只读验证 OCR、定位、PNG 与请求 ID，不启动引擎或模型。
- 实测结果与未覆盖项见 `ADB-DEBUG-ACCEPTANCE.md`，断点清单见 `ADB-DEBUG-CHECKLIST.md`。真实验收必须先通过相同主 APK 的 mock 套件；不要同时运行多个 instrumentation / uiautomator。
