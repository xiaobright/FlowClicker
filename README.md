# FlowClicker

一个 Android 自动点击器（挂机工具），核心设计是 **「本地引擎干 99% 的活，LLM 只做调度员」**：

- 平时由**本地 OCR 关键词触发引擎**驱动任务 —— 免费、毫秒级、零流量；
- AI（任意 OpenAI 兼容 API，多模态可选）平时**零消耗**，只在唤醒条件命中时被叫醒，通过一组内部工具观察状态、看图诊断、修改编排；
- AI 新建/修改的任务默认进入 **debug 模式**（每次点击前留存截图），跑满 N 轮后自动唤醒 AI 复盘，确认无误才转正。

> ## ⚠️ 项目状态：已测试，未实际使用
>
> 本项目已在模拟器和真机（EMUI）上完成功能测试（见 `docs/` 下的验收与测试报告），
> 但**作者本人并未长期实际使用过它**。它是一个完整的原型/实验项目，不是经过日常验证的成熟工具。
> 使用中遇到问题请自行排查或提 issue，也欢迎 fork 改进。

## 功能一览

- **任务编排**：触发条件（定时 / OCR 文字出现）+ 有序步骤（点击、滑动、等待文字等），支持点位/区域拾取、录制生成任务。
- **常驻监控引擎**：前台服务 + 无障碍服务执行手势，MediaProjection 截屏做本地 OCR 匹配。
- **AI 调度员**：
  - 事件驱动唤醒（`idle` / `stall` / `debug_review` / 录制完成 / 试运行结束 / 手动提问）；
  - 纯 JSON 文本工具调用协议，不依赖各家端点的 function-calling 兼容性；
  - 16+ 个内部工具：读引擎状态、OCR 定位、看截图、增删改任务、调整唤醒规则、试运行、直接 tap/swipe 处理弹窗等；
  - 两步定位校准（裁剪放大确认坐标）、跨任务长期记忆、任务笔记交接；
  - 单飞会话 + 队列 + 退避重试，防止事件风暴刷爆 API。
- **Debug 模式**：AI 改动先小流量验证，截图复盘通过后才正式运行，平时不花一分钱 token。
- 原生 Android Views + Material 组件 UI，含浅色/深色主题；无 WebView、无统计 SDK、无内嵌网络资源。

## 架构

详见 [docs/AI-ARCHITECTURE.md](docs/AI-ARCHITECTURE.md)。

```
MonitoringEngine ──事件──┐
RecordingService ──事件──┤
DebugStore ──────────────┤
手动唤醒（UI）────────────┤
                         ▼
                  WakeDispatcher（规则匹配/单飞/冷却）
                         ▼
                  AiSession（JSON 工具调用循环）
                    │              │
             AiClient(OpenAI格式)  AiTools(内部工具)
```

## 构建

要求：Android Studio（AGP 8.13 / Kotlin 2.1）或命令行 Gradle，JDK 17。

```bash
./gradlew assembleRelease
```

产物在 `app/build/outputs/apk/release/`。仓库不带签名配置，release 默认未签名；如需自行安装，可用 debug 签名直接 `./gradlew installDebug`，或配置自己的 keystore。

## 运行要求

- Android 10（API 29）及以上；
- 需手动授予：无障碍服务（执行手势）、「显示在其他应用上层」（悬浮录制面板/选点器）、屏幕录制（MediaProjection）、通知权限（引擎常驻通知）；
- AI 功能需在 app 内自行配置 OpenAI 兼容端点与 API Key（仅保存在本地，密码框保护）。

## 免责声明

自动化操作可能违反目标应用的服务条款，由此产生的一切后果由使用者自行承担。本项目仅供学习交流。

## 类似项目

- [Nain57/Smart-AutoClicker](https://github.com/Nain57/Smart-AutoClicker)：Android 上基于图像识别的事件式自动点击器（无 AI 调度）。
- [autox-community/AutoX](https://github.com/autox-community/AutoX)：设备端 JavaScript 脚本自动化（可配 OCR 插件，无 AI 调度）。
- [droidrun/mobilerun](https://github.com/droidrun/mobilerun)、[X-PLUG/MobileAgent](https://github.com/X-PLUG/MobileAgent)：LLM 驱动每一步操作的手机 GUI Agent（研究向，token 成本高，非常驻挂机）。

FlowClicker 与上述项目的差异点：本地引擎常驻承担全部高频操作，LLM 只在事件唤醒时以「维护者」身份介入——观察、诊断、修改编排、验证，然后退回零消耗状态。
