# Native UI refresh

## Direction

FlowClicker uses a calm green palette, restrained cards, and clear separation between configuration and execution. The interface stays native Android Views + Material Components; no WebView, new UI framework, analytics, or network assets.

- **Workbench:** live execution summary, permission state, explicit capture controls, safe-stop explanation.
- **Tasks:** stable cards (not rebuilt every second), explicit edit/delete actions, enable state separate from engine state.
- **Editor:** basic information → trigger → ordered steps, reordering, deletion confirmation, scrollable dialogs, unsaved-draft warning and rotation restoration.
- **AI:** collapsible configuration, password-protected API key, manual wake and activity history. Merely opening the screen does not call a model.
- **Overlays:** compact recording panel and coordinated point/region picker surfaces; preserve physical-pixel coordinates, gesture forwarding and cancellation.

## Source map

- `ui/Ui.kt`: shared native components, window/IME insets, content width and navigation.
- `ui/Screens.kt`: four screen compositions, with stable resource IDs for view restoration and device acceptance.
- `res/values/colors.xml`, `res/values-night/colors.xml`: light/dark palette.
- Existing activities continue to bind the production actions; the engine, stores, AI tools and permission gates remain authoritative.
- Recording overlay uses a Material-themed context; its touch region measures the actual on-screen origin after layout.

Keep UI strings Chinese for now. Do not describe screenshot OCR or optional remote-model use as entirely offline: OCR is local, while AI uses the configured provider. An open-source release still needs a separate privacy/history/license review; a visual refresh is not release clearance.

## Acceptance

The previous EMUI fix is committed as `633f068`; this UI refresh is a separate change set. Acceptance uses only the disposable emulator, not the unplugged phone.

- Emulator: existing `Pixel_10` started as `emulator-5556` with `-read-only -no-snapshot -no-window`; its original data is not written back.
- Before UI tests, the disposable clone was backed up, model credentials removed and tasks emptied only within that clone.
- Intermediate builds exposed a decor-before-content error and an incorrect `TextInputLayout` child layout parameter; both were corrected and the affected screens re-tested. The first UI test also checked task deletion before AlertController's posted listener ran; the test now waits for UI idle/completion.
- The recording screenshot initially preceded surface presentation; visual verification now waits for that surface, in addition to testing actual forwarding.
- Picker panels are inset around status bars/cutouts without changing the full-screen coordinate origin.

### Verified scenarios

Evidence stays under ignored `.tools/ui-redesign/`. It includes backups and must not be published wholesale.

| Area | Evidence / result |
|---|---|
| Home, task list, editor, AI settings | Real emulator screenshots; coherent native light/dark palette |
| Task CRUD | `suite ui`: create/save/toggle/delete; normal production store and validation |
| Draft lifecycle | Activity recreation retains unsaved name, trigger, steps; back warns before discarding |
| List refresh | Unchanged task card retains view identity across periodic refresh |
| Step dialogs | Long click configuration is scrollable; existing anchor safety controls retained |
| AI guards | Hidden API key, collapsible settings; empty wake does not call a model |
| Recording | `suite ui-overlay`: inject a real touch into the overlay, forward via accessibility, observe test-button success; exactly one recorded click with physical coordinates within 2 px |
| Recording stop | Finish returns steps; cancel returns no result; both remove overlay/release ownership, task store unchanged |
| Point picker | Tap (500,1200), dialog receives exactly (500,1200) |
| Region picker | Drag (200,800) to (800,1300), editor receives all four exact values |
| Capture | System projection consent; live on/off status without reopening home; stop removes projection/frame |
| OCR regression | CLI capture test: Chinese OCR/locate, PNG CRC/decompression/dimensions/request correlation |
| Responsive layout | Dark editor and task list; landscape home at 1.3× font scale with scrollable content |

### Reproduction

Use a disposable, sanitized emulator; the UI suite refuses a configured AI endpoint or nonempty task store.

```powershell
adb -s emulator-5556 shell am instrument -w -e suite ui com.flowclicker.app.test/com.flowclicker.app.CaptureRepairInstrumentation
adb -s emulator-5556 shell am instrument -w -e suite ui-overlay com.flowclicker.app.test/com.flowclicker.app.CaptureRepairInstrumentation
```

The overlay suite needs the app's accessibility service and overlay permission enabled. Its screenshot is saved to app-private `cache/ui-overlay.png`. Never run these tests on a daily-use phone; they intentionally create disposable task data.

`tools/ui_probe.py` is an emulator-only screenshot/hierarchy helper. It refuses to act on a missing, ambiguous or disabled target, masks password fields in printed output, and treats a failed hierarchy dump as failure rather than reading stale XML. When a cold launch has not settled, inspect the foreground/crash state before taking a fresh dump.

### Boundaries

- No real model requests; historical mock/model, Doze/soak, T03 and physical-phone findings are not relabeled as passed.
- No engine, scheduler, store format, debug admission gate, or production permission policy redesign.
- The panel is intentionally fixed near the top; this change does not promise draggable floating controls.
- The editor restores the main draft after recreation, not an unconfirmed step dialog or an in-flight picker callback across process death.
- UI text is Chinese. Localization, license choice and a full repository-history/secret audit remain separate open-source preparation work.

## Final verification — 2026-09-29

- Final build: `testDebugUnitTest`, `assembleDebug`, `assembleDebugAndroidTest`, `assembleRelease` succeeded (`build-final.log`). All **49 JVM tests** passed; all **15 Python CLI tests** passed.
- Both `suite ui` and `suite ui-overlay` passed against the final app (`ui-instrumentation-final.log`, `overlay-instrumentation-final-apk.log`). The installed debug APK was read back and its SHA-256 matched the final build.
- Final point/region screenshots were visually inspected after the system-bar inset correction. Exact coordinate results are in `point-result-final.json` and `region-result-final.json`.
- Capture on/off and OCR acceptance ran before the final picker-only inset correction; these scenarios were not rerun against the final APK. The final APK's stopped capture state was verified during cleanup.
- Final release merged manifest and all DEX files exclude the app's debug receiver/protocol/response marker; the debug network-security resource is absent. The debug APK provides a positive control. AndroidX's normal `ProfileInstallReceiver` still has its own `DUMP` permission gate; that is not the app's debug command interface.
- UTF-8, resource XML, Python syntax and `git diff --check` passed. No real model, phone, Doze/soak or T03 acceptance was added by this UI work.

### APK SHA-256

| Artifact | SHA-256 |
|---|---|
| `app/build/outputs/apk/debug/app-debug.apk` | `b5b703a449c388e3f67c88ff5844162315d0432ff40cbd13a8741bcde03720a5` |
| `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` | `c990cf6e69986c3ff16756c1fd8c60e267369007a14c669babe538ad49fb7740` |
| `app/build/outputs/apk/release/app-release-unsigned.apk` | `e97e953615e91e2b1ae5f7641ee88170d040a6ab4b43d1e569c7578357b12009` |

The release artifact is unsigned and is not a published release.

### Cleanup

- CLI stop completed: engine stopped, AI disabled, no screen owner, empty task store, no capture frame, no recording/capture service and no media projection (`cleanup-before-exit.json`, `cleanup-services.txt`, `cleanup-projection.txt`).
- Crash-buffer evidence was retained as `crash-buffer-final.log`; it contains the early iteration failures described above and was not cleared to manufacture a clean log.
- Only the verified read-only `Pixel_10` instance on port 5556 was closed with `adb emu kill`. The initial immediate exit check raced ADB removal; a subsequent check confirmed both owned emulator processes and `emulator-5556` gone. No second kill was sent.
- Original emulator data and host evidence/backups were retained. No physical phone was connected during cleanup.
- Previous fix remains committed as `633f068`. This UI change set is intentionally left uncommitted for review; nothing was pushed. Windows shutdown is scheduled only after the report and final workspace checks are finished.
