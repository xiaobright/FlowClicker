# 本地构建验证

- 日期：2026-09-27；HEAD：`eef1db4` + 未提交的审查修复和测试方文件。
- SDK：`D:\androidsdk`；JDK 17；Gradle 8.13。
- 命令：`:app:testDebugUnitTest :app:assembleDebug`，`BUILD SUCCESSFUL`。
- 单测：24 个（AiClientTest 3、SafetyRegressionTest 21），0 failures、0 errors、0 skipped。
- APK：`app/build/outputs/apk/debug/app-debug.apk`，SHA-256：`4E6F3510451CB999183FC0ABA6F434201CBF5EC3735DB96617E630BA981F0D29`。
- `git diff --check`：通过。未安装 APK；`adb devices -l` 无设备，因此新包的设备测试尚未执行。

旧 `TEST-REPORT.md` 的场景结果属于旧 APK（SHA-256 `d65ebbf925d2733df5fac1fdd3e2ac38ee290c88d02a84c3229be460d11cc248`）。当前包增加短暂缺帧容忍、连续缺帧退出日志、采集线程关闭竞态防护和通知 action 图标；这些设备行为不能由编译或单测代替验证。不要将旧报告的 PASS 直接转记到当前包。

---

# 2026-09-28 下午 最终构建（修复方第二轮修复后）

- 工作区：HEAD `eef1db4` + 修复方未提交改动 + 测试方证据文件（仍未提交）。
- 最终源码包含：CaptureWorker 串行化与手势屏障、CaptureFrameStore 帧准入、通知运行时权限（NotificationAccess）、WaitForText、AutomationGate 及全部测试。
- 命令：`.tools/gradle-8.13/bin/gradle.bat --no-daemon :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest`，BUILD SUCCESSFUL 57s。
- 单测 42：AiClientTest 3 + CaptureRegressionTest 18 + SafetyRegressionTest 21，0 failures/errors/skipped。
- APK：`app/build/outputs/apk/debug/app-debug.apk`，SHA-256 `E97D957969B7A8E308B2BD481CCB4E140EF6A5E839B494901B126EAC0E2F7587`。
- 旧中间产物 `B8A38C3A…`（本轮首个构建）不含后续源码改动，作废；`4E6F3510…` 为上一轮被试包，历史报告已绑定。
- 设备验收结论见 REPAIR-ACCEPTANCE.md（T01/T02/T05/T06/T07 PASS、T03 BLOCKED、T04/T07-real 部分完成）。

---

# 2026-09-28 ADB debug 接口构建与验收

- 主 debug APK：`app/build/outputs/apk/debug/app-debug.apk`，SHA-256 `9AEE711BB1CFC92D8BBAF250881ACD32CDA6960FC78F6342F7720013569FE32A`。
- release（未签名）：`app/build/outputs/apk/release/app-release-unsigned.apk`，SHA-256 `BD19A04C25E960B549C45C2EE20DC2DFE5F72DC45EF4C00464CFBEF47EBAC163`。
- 最终 androidTest：`app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`，SHA-256 `EF1626FC15D84697E554172FA21FAB9212DFE2E39E1D294A6E42CFD9D67A4878`。
- 主包构建日志 `.tools/adb-debug-build.log`：BUILD SUCCESSFUL 4m46s；49 项 JVM（3 + 18 + 21 + DebugProtocol 7），全部通过。
- 测试夹具后续定向构建 `.tools/adb-debug-test-rebuild.log`：BUILD SUCCESSFUL 47s；只变更 androidTest，主 APK 哈希不变。
- Python CLI 8 项通过；release 合并 manifest 与全部 2 个 DEX 无 debug Receiver/协议/网络配置。
- 设备 CLI、真实采集/OCR、确定性点击、假模型 S01–S04、真实模型单次点击 + WaitText + 停止 60s、3 项 ImageReader 插桩通过。详细覆盖边界及恢复状态见 `ADB-DEBUG-ACCEPTANCE.md` / `ADB-DEBUG-CHECKLIST.md`。
- 旧 APK 的历史结果继续保留，不把历史 T03 BLOCKED 改记为通过。
