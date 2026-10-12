# R09 — QuickJS 16KB 页兼容性专项核查

日期：2026-10-07
结论：**未验证，保留限制声明。不得宣称支持 16KB 页设备。**

## 一、告警对象

Lint `Aligned16KB`（3 条实例，指向同一依赖）：

- 依赖声明：`app/build.gradle.kts:188`
  `implementation("io.github.taoweiji.quickjs:quickjs-android:1.4.6")`
- 唯一使用点：`app/src/main/java/com/relaytester/app/core/network/BalanceQueryScript.kt`
  （`QuickJS.createRuntime()` / `JSContext`，用于余额查询脚本模板）

## 二、实测证据（debug APK 内解出的原生库）

对 `app/build/outputs/apk/debug/app-debug.apk` 中两个 `arm64-v8a` 库做 ELF PT_LOAD 段对齐分析：

| 库 | 段 | offset | vaddr | p_align | 16KB 判定 |
|---|---|---|---|---|---|
| libquickjs-android.so | LOAD R-X | 0x0 | 0x0 | 0x1000 | 不符合 |
| libquickjs-android.so | LOAD RW- | 0x36b90 | 0x37b90 | 0x1000 | 不符合 |
| libquickjs.so | LOAD R-X | 0x0 | 0x0 | 0x1000 | 不符合 |
| libquickjs.so | LOAD RW- | 0xecf20 | 0xedf20 | 0x1000 | 不符合 |

- 两个库的 `p_align` 均为 **0x1000（4KB）**，16KB 页要求 `p_align >= 0x4000` 且
  offset/vaddr 按 16KB 对齐。
- 第二段的 `offset=0x36b90` / `0xecf20` 连 4KB 边界都未对齐（与 `p_align` 自身不一致），
  在 16KB 页内核下加载会失败。
- 因此 Aligned16KB 的 3 条告警指向同一根因：**依赖自带预编译库，不是本项目的代码问题**。

## 三、目标设备可用性

| 设备 | ABI | PAGE_SIZE | 可验证 16KB |
|---|---|---|---|
| emulator-5556 | x86_64, arm64-v8a | 4096 | 否 |
| emulator-5558 | x86_64, arm64-v8a | 4096 | 否 |

现有模拟器页面大小为 4KB，**没有 16KB 页目标设备**。按修复文档要求，
未在 16KB 页设备上检查库加载与脚本执行前，不宣称支持。

## 四、未实施的改动及原因

| 候选动作 | 未实施原因 |
|---|---|
| 升级 quickjs-android 到更高版本 | 未确认存在提供 16KB 对齐的版本；升级需验证脚本能力、异常处理与原有设备兼容性，属独立变更 |
| 重新构建原生库 | 需要 NDK 工具链与该库的构建配置，超出本轮修复范围，且未获授权 |
| 移除依赖 / 改用其他脚本引擎 | 会破坏余额脚本模板功能，属产品能力变更 |
| 通过 lint 基线屏蔽告警 | 会掩盖真实限制，与"不宣称支持"的要求相反；保留告警作为可见风险 |

## 五、直接影响面

- 仅影响**余额查询脚本模板**（`BalanceQueryScript.kt`）在 16KB 页设备上的可用性。
- 指纹检测、模型测试、R10 动画预览均**不依赖 QuickJS**：预览走 Android WebView，
  与原生脚本引擎无关（修复文档第 15 节亦明确"WebView 不依赖 QuickJS"）。
- 其余 Lint 告警（AndroidGradlePluginVersion、GradleDependency、NewerVersionAvailable、
  ChromeOsAbiSupport、InsecureBaseConfiguration）为版本提示或产品策略项，
  按文档要求不批量升级依赖、不为模拟器改正式 ABI、不改动明文 HTTP 策略。
