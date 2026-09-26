# AI 调度员架构（v1）

## 定位与核心原则

AI（OpenAI 兼容 API，多模态可选）在 app 内扮演**编排调度员**，不是高频操作者：

1. **常驻主力仍是本地引擎**：OCR 关键词触发免费、毫秒级、零流量，负责 99% 的日常挂机。
2. **AI 平时零消耗**：只在"唤醒条件"命中时被叫醒，通过一组**内部工具**观察状态、修改编排、试运行、看图诊断。
3. **唤醒条件本身是 AI 可配置的资产**：多久没有下一轮、某任务执行后迟迟没有下一步——这些兜底规则由 AI 通过工具增删改。
4. **Debug 模式控制成本**：AI 新建/修改的编排默认进 debug 模式（每次点击前留存截图），跑满 N 轮后自动唤醒 AI 复盘，确认无误才切回 normal 模式。

## 组件与数据流

```
MonitoringEngine ──事件(TaskFired/TaskFinished)──┐
RecordingService ──事件(录制完成)───────────────┤
DebugStore        ──事件(N轮跑满)───────────────┤
手动唤醒（UI）───────────────────────────────────┤
                                                 ▼
                                          WakeDispatcher
                                     （规则匹配 / 单飞会话 / 冷却）
                                                 ▼
                                            AiSession
                                  （JSON 工具调用循环，最多 N 轮）
                                     │                │
                              AiClient(OpenAI格式)   AiTools(内部工具)
                              可携带截图 base64      操作 TaskStore/Engine/OCR/DebugStore
```

## 事件与唤醒规则（WakeRule）

| 类型 | 参数 | 语义 |
|---|---|---|
| `idle` | timeoutMs，可选 taskId/tag | 引擎在跑但持续没有任何任务触发超过 T → 唤醒（"多久没有下一轮"） |
| `stall` | taskId, timeoutMs | 该任务执行完成后，T 内没有任何其他任务接续触发 → 唤醒（"点了之后没有下一步"） |
| `debug_review` | 全局 settings.debugRounds | debug 任务跑满 N 轮 → 唤醒并附带截图与步骤结果 |
| `recording_finished` | 系统事件 | 用户/模型录制完成 → 唤醒，AI 把录制结果整理成正式任务 |
| `test_run_finished` | 系统事件 | `run_task_now` 试运行结束 → 唤醒，AI 检查执行结果 |
| `manual` | 用户输入 | 用户在 AI 页主动提问/下达指令 |

规则持久化在 `wake_rules.json`；AI 用 `get_wake_rules` / `set_wake_rules` 全量读写。

## 内部工具（v1 共 16 个）

观察：`list_tasks` `get_task` `get_engine_status` `describe_screen`(OCR全文) `get_screenshot`(下一轮附图, 需VLM) `get_debug_captures`(附带最近截图) `get_wake_rules` `get_last_recording`
行动：`upsert_task`(全量提交单个任务) `delete_task` `set_task_enabled` `set_task_mode`(normal/debug) `set_wake_rules` `run_task_now`(试运行,需引擎运行中) `start_engine` `stop_engine` `log_note`(给用户留言)

所有工具返回 JSON 文本，进入会话上下文；`upsert_task` 与界面编辑器写同一条数据路径（引擎内存 → onChanged → tasks.json），无双写分歧。

## 会话协议

不依赖各家端点的 function-calling 兼容性，用**纯 JSON 文本协议**（任何"OpenAI 兼容"端点都能跑）：

```
system:  角色定义 + 工具手册（含 Task/Step JSON Schema 范例）+ 响应协议 + 工作守则
user:    唤醒事件上下文（事件详情 + 引擎快照 + 任务摘要）
assistant: {"thought":"...", "tool":"list_tasks", "args":{}}
user:    {"tool_result": ...}（若调用了 get_screenshot/get_debug_captures，本条消息附图）
assistant: ... 或 {"reply":"给用户的总结"}
```

- 每会话最多 `maxToolRounds`（默认 6）轮工具调用；解析失败会把错误喂回去重试。
- 单飞（同一时刻仅一个会话）+ 待处理队列上限 5，防止事件风暴刷爆 API。
- 会话落 `ai_log.json`（事件类型、工具调用数、最终 reply），界面可查。

## Debug 模式生命周期

```
AI upsert_task(mode=debug) ──► 每次执行：点击/滑动前截屏存 debug/<taskId>/
                               （每任务目录仅保留最近 12 张）
        │
        └─ 跑满 settings.debugRounds 轮 ──► debug_review 唤醒
                 AI: get_debug_captures 看图 → 修编排 或 确认
                 → set_task_mode(normal) → 恢复零 AI 消耗
```

用户手工录制的编排同样建议先 debug 跑两轮（AI 复盘截图后再转正）。

## 与既有模块的关系

- 文字识别（ML Kit）不变，仍是触发主力；`describe_screen` 让 AI 低成本"读屏"。
- 截图匹配（图像模板）为 v2；v1 的视觉能力全部走 API。
- 录制器不变；录制完成事件新增回调，AI 可在会话里把录制结果加工成任务。

## v2 展望

截图模板匹配、模型操作录制回放、流式输出、token 用量统计、多方案 A/B 试跑、设备本地小模型兜底。

## 成本与安全约束

- AI 总开关（默认关）+ baseUrl/key/model 可配置（兼容国内各 OpenAI 格式端点）。
- 截图出网仅在 debug_review / 显式 get_screenshot 时发生；引擎主循环永不联网。
- 每会话轮数与队列上限硬编码封顶，异常时记录日志不重试风暴。
