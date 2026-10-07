# AI 调度员架构（v1）

## 定位与核心原则

AI（OpenAI 兼容 API，多模态可选）在 app 内扮演**编排调度员**，不是高频操作者：

1. **常驻主力仍是本地引擎**：OCR 关键词触发免费、毫秒级、零流量，负责 99% 的日常挂机。
2. **AI 平时零消耗**：只在"唤醒条件"命中时被叫醒，通过一组**内部工具**观察状态、修改编排、试运行、看图诊断。
3. **唤醒条件本身是 AI 可配置的资产**：多久没有下一轮、某任务执行后迟迟没有下一步——这些兜底规则由 AI 通过工具增删改。
4. **Debug 模式控制成本**：AI 新建/修改的编排默认进 debug 模式（每次点击前留存截图），跑满 N 轮后自动唤醒 AI 复盘，确认无误才切回 normal 模式。

## 组件与数据流

可选扩展：`WakeDispatcher` 在 `idle` / `stall` 时先经过 `JudgeRouter`（默认关闭），从已验证的恢复任务中选择；观察模式、判断失败或无法处理时继续进入原 `AiSession`。判断服务兼容 Jev/System One，支持自定义端点与模型。详细边界、内部工具和验证方式见 [STRUCTURED-JUDGE.md](STRUCTURED-JUDGE.md)。

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

## 内部工具

观察：`list_tasks` `get_task`(含AI交接笔记) `get_engine_status` `describe_screen`(OCR全文) `locate_text`(OCR文字精确坐标框) `get_screenshot`(下一轮附图, 需VLM) `get_debug_captures`(附带最近截图) `get_wake_rules` `get_last_recording`
行动：`upsert_task`(全量提交单个任务) `delete_task` `set_task_enabled` `set_task_mode`(normal/debug) `set_wake_rules` `run_task_now`(试运行,需引擎运行中) `start_engine` `stop_engine` `log_note`(给用户留言)
定位校准：`confirm_target`(两步定位: 预估坐标裁剪放大→图内局部坐标回传→端上换算) `confirm_region`(框选区域外扩15%自查)
直接操作：`tap_screen` `swipe_screen`(处理弹窗/推进画面/验证坐标，正式循环仍靠任务步骤)
记忆：`save_memory`(跨任务长期记忆) `save_task_note`(任务绑定笔记)

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

- 每会话最多 `maxToolRounds`（默认 8）轮工具调用；解析失败会把错误喂回去重试。
- 单飞（同一时刻仅一个会话）+ 待处理队列，防止事件风暴刷爆 API；单会话网络失败按 5s/15s 退避重试（协议/参数错误不重试）。
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

## v2：定位校准、分层记忆与上下文管理

### 定位校准（实验驱动）

在模拟屏（1080×2340，GT：开始战斗=(540,1560)、重新登录=(540,1955)、关闭X=(990,490)、面板=(80,450,1000,1400)）上对 LongCat-2.5-Preview 实测：

| 方案 | 误差 |
|---|---|
| 裸 VLM 直读坐标 | 51~220px，**Y 轴系统性压缩 ~11%**；区域 IoU≈0.61 |
| 让模型直接输出"修正后原图坐标" | 不可靠（出现 X=1175 出屏） |
| **放大裁剪 + 模型输出图内局部坐标 + 端上换算**（`原图 = 裁剪原点 + 局部/zoom`） | **170px → 12px** |

由此实现三件套：`locate_text`（文字目标直接 OCR 拿精确框，首选、零视觉调用）；`confirm_target`（视觉目标两步校准）；`confirm_region`（框选自查）。另加 `Click.anchor` 字段：执行时实时 OCR 定位文字中心（抗布局漂移），未命中回退 x,y。

### 分层记忆（L0-L5）

| 层 | 内容 | 生命周期 |
|---|---|---|
| L0 | 稳定系统提示词（角色+协议+工具手册+守则） | 永不变更 → 前缀缓存友好 |
| L1 | 全局快照（任务摘要+引擎状态+长期记忆+相关任务笔记） | 每次唤醒现取 |
| L2 | 事件上下文（唤醒事件详情） | 单次 |
| L3 | `ai_memory.json` 跨任务长期记忆（≤20条） | 持久，AI 用 save_memory 维护 |
| L4 | `ai_task_notes.json` 任务绑定笔记 | 持久；转正时被交接笔记覆盖；任务删除即清理 |
| L5 | 会话转录 | **永不持久化原文**（图片部件直接丢弃）；debug 任务留文本档 12h 供续接 |

### 会话续接与转正压缩

- debug 任务的事件（debug_review/test_run_finished）带 taskId → 会话结束把转录（文本化）存 `ai_sessions/task_<id>.json`；下次相关唤醒**续接**同一对话，复盘/修编排有连续性；超过 12h 视为过期。
- `set_task_mode(normal)` 转正时自动触发**交接笔记**：单独一次调用把整段会话压缩成 ≤500 字的"给下次自己的笔记"（任务目标/关键坐标/注意事项/下次先做什么），存入 L4 并删除会话留档 —— 上下文从"无限累积"变成"笔记化沉淀"。
- normal 模式会话不留档：每次唤醒都是新鲜的 L0+L1+L2。

### 前缀缓存实测（LongCat-2.5-Preview，60KB 固定 system 前缀）

API 在 `usage.prompt_tokens_details.cached_tokens` 直接报告命中：16207 prompt tokens 中命中 16128（600s 间隔那次，延迟也最低 3.4s）。但 30s/120s/300s/1200s/1800s 均未命中 —— **缓存是尽力而为，非确定性 TTL**。结论：稳定前缀（L0 不变）依然是正确策略，命中时省钱省延迟，未命中无额外损失；保守估计 ≥10 分钟内有机会命中。

## v3 展望

截图模板匹配、模型操作录制回放、流式输出、token 用量统计与成本面板（cached_tokens 已可从 usage 读取）、多方案 A/B 试跑、设备本地小模型兜底。

## 成本与安全约束

- AI 总开关（默认关）+ baseUrl/key/model 可配置（兼容国内各 OpenAI 格式端点）。
- 截图出网仅在 debug_review / 显式 get_screenshot 时发生；引擎主循环永不联网。
- 每会话轮数与队列上限硬编码封顶，异常时记录日志不重试风暴。
