# Relay Tester Android：零 UI 变更启动性能优化规范

**目标版本：** `0.7.6`  
**状态：** 本轮实施依据  
**范围：** 启动性能、构建产物、凭据恢复调度与验证；不重绘、不重排、不改文案。

---

## 1. 本轮不变量

本轮以当前 `0.7.5` 版本为视觉与交互基线。以下内容均不得改变：

- `MainActivity` 的系统 Splash 到主界面的单一启动交接；
- 所有标题、文案、图标、Logo、颜色、字体、圆角、间距、卡片高度与阴影；
- 模型测试页、余额查询页、供应商双栏、余额双栏和配置导入导出对话框的布局与控件顺序；
- 供应商、模型、测试、余额、模板、凭据、导入导出的功能和数据格式；
- Android Keystore 的 AES-GCM 密文格式、存储位置和既有密钥标识；
- 已安装 `com.relaytester.app.debug` 的应用数据与可覆盖安装路径。

本轮不会编辑以下界面文件：

```text
app/src/main/java/com/relaytester/app/MainActivity.kt
app/src/main/java/com/relaytester/app/feature/tester/TesterScreen.kt
app/src/main/java/com/relaytester/app/feature/balance/BalanceScreen.kt
app/src/main/java/com/relaytester/app/feature/backup/ConfigurationBackupDialog.kt
app/src/main/java/com/relaytester/app/ui/**
app/src/main/res/**
```

页面出现得更快或控件更早可用是性能结果，不构成视觉结构变更；不得新增骨架屏、加载页、弹窗或提示文字。

---

## 2. 已确认问题

当前安装的是 `0.7.5-debug`。该包的 Dex 状态为 `run-from-apk`，未带可用的应用 Baseline Profile，首开会承受 Compose、Material 3 和应用代码的类加载/JIT 成本。

启动恢复已正确将 DataStore/JSON 放到 IO 线程，但仍存在两项可见前的尾部成本：

1. 当前活动站点的模型 API Key 与余额访问令牌在一个 IO 协程中串行读取；Android Keystore 首次访问可能等待系统/硬件服务。
2. 凭据读取的交互保护窗口为 1,200ms。超时后虽然 UI 能继续工作，但若用户恰好先点击模型测试，空白草稿可能先触发校验而不是使用已保存密钥。

本轮不改变界面解决方案，而是缩短后台等待，并在实际请求前按需解析已保存密钥，消除这个竞态。

---

## 3. 实施设计

### 3.1 可覆盖安装的优化构建

保留 `debug` 构建给日常开发；新增 `optimized` 构建，仅用于真实启动/滚动性能验证：

```text
debug      -> com.relaytester.app.debug, 未压缩，便于开发
optimized  -> com.relaytester.app.debug, R8 + 资源收缩，可覆盖现有 debug 数据
release    -> com.relaytester.app, R8 + 资源收缩，正式发布基础
```

`optimized` 使用 debug 签名且沿用 `.debug` 包名，因此可以通过 `adb install -r` 覆盖当前测试包，不会删除或迁移用户数据。它仅改变字节码/资源打包方式，不参与 UI 代码路径。

### 3.2 启动凭据恢复

恢复步骤保持现有 UI 状态和安全边界，但改为：

1. DataStore/JSON 继续在 `Dispatchers.IO` 读取；
2. 首个结构状态仍一次性发布；
3. 模型 API Key 和余额访问令牌并行读取，复用已有 Keystore 密钥句柄缓存；
4. 将初始交互保护等待控制在 250ms；
5. 若凭据在保护窗口后仍未回填，后台任务继续运行；
6. 用户发起模型请求时，若该输入框尚未收到后台回填且密钥未被用户编辑，则从该站点的 Keystore 记录按需读取；
7. 用户输入的新 API Key 始终优先于后台旧值，且不会被晚到的恢复结果覆盖。

这保持已保存密钥的测试语义：用户不会因性能优化而看到错误的“请填写 API Key”校验，也不会改变密钥持久化协议。

### 3.3 本轮明确不做的改动

- 不替换 `FlowRow`、`LazyColumn`、`LazyVerticalGrid` 或任何 Compose 视觉容器；
- 不删除/缩短 `TesterScreen` 的 `350ms` 屏幕外内容策略，以避免可见界面出现时序或布局差异；
- 不提前迁移到 Room；当前已测模拟器配置只有约 1.3KB，尚无数据证明数据库能改善首开；
- 不关闭 EmojiCompat、ProfileInstaller 或其他 AndroidX Startup 初始化器，须在 Perfetto 证据确认后才可裁剪；
- 不手写或伪造 Baseline Profile。后续需在独立 benchmark 模块采集真实启动路径后再接入。

---

## 4. 验证与回滚

### 4.1 构建验证

- `:app:lintDebug`：0 errors；
- `:app:assembleDebug`：确保日常开发包不回归；
- `:app:assembleOptimized`：生成可安装的 R8 优化包；
- `:app:assembleRelease`：验证正式发布链路可压缩并通过资源收缩。

### 4.2 设备验证

- 优先使用 API 35 模拟器对 `optimized` 包执行覆盖安装；若其系统镜像不可用，则使用隔离的相邻 API 级别临时模拟器，不卸载、不清除数据；
- 冷启动至少采样 5 次 `force-stop -> am start -W`；
- 检查 `MainActivity` 在前台、`AndroidRuntime` 为 0、没有 ANR；
- 对比模型测试和余额查询页的截图，确认 UI 结构与 v0.7.5 基线一致；
- 不点击获取模型、开始测试、余额查询、导入或导出，不调用真实站点 API。

### 4.3 回滚

已创建以下独立源码快照：

```text
backups/RelayTesterAndroid-v0.7.5-before-startup-optimization-20260830-132155.zip
SHA-256: 684DAB56F21014406D5635CE7D8479C4F6DF93FC8F2CE89883B7F74C606DF79E
```

若任何界面或功能回归，直接以该快照还原源码；现有应用配置不在快照和还原范围内。

---

## 5. 0.7.6 实施记录与验证结果

**实施日期：** 2026-08-30  
**验证原则：** 全程未调用获取模型、开始测试、查询余额、批量查询、导入或导出；未访问真实站点，也没有清除任何应用配置或 Keystore 记录。

### 5.1 已实施的非视觉优化

1. 应用版本更新为 `versionCode 16`、`versionName 0.7.6`。
2. 新增同包名的 `optimized` 构建：使用 R8、资源收缩和 debug 签名，包名保持为 `com.relaytester.app.debug`，可通过 `adb install -r` 覆盖调试安装而不删除数据。
3. 正式 `release` 也启用 R8 与资源收缩；无业务代码使用 `BuildConfig`，因此关闭无引用的 `BuildConfig` 生成。
4. 启动时模型 API Key 和余额访问令牌由串行读取改为并行读取，磁盘与 Android Keystore 工作仍在 `Dispatchers.IO`。
5. 初始凭据交互保护窗由 1,200 ms 缩短至 250 ms。若后台凭据尚未回填，用户真实发起模型请求时才按需读取保存的 API Key；新输入值始终优先，不会被晚到的旧密钥覆盖。

本次源码变动仅限于：

```text
app/build.gradle.kts
app/src/main/java/com/relaytester/app/feature/tester/TesterViewModel.kt
STARTUP_ZERO_UI_OPTIMIZATION_SPEC.md
```

### 5.2 构建结果

在当前 Windows/JDK 环境中，普通 Gradle Worker 的本机回环连接存在系统性异常，因此以禁用 daemon/instrumentation agent 的 Wrapper 启动方式完成离线构建验证；这不是应用运行时问题。

| 验证项 | 结果 |
| --- | --- |
| `:app:assembleOptimized` | 成功；已执行 R8、资源收缩和资源优化 |
| `:app:lintDebug` | 成功，0 errors |
| `:app:assembleDebug` | 成功 |
| `:app:assembleRelease` | 成功；已执行 R8、资源收缩和资源优化 |
| `:app:testDebugUnitTest` | 成功；当前工程没有已提交的本地单元测试源码（任务显示 `NO-SOURCE`） |

优化测试包：

```text
路径：app/build/outputs/apk/optimized/app-optimized.apk
包名：com.relaytester.app.debug
版本：0.7.6-optimized（versionCode 16）
大小：1,585,993 bytes（1.51 MiB）
SHA-256：8196EF76CD8FA46F723E2515583D38BA5CFED04F4FACF59F99A498A941CA8DF4
```

作为打包体积参考，未压缩的 `debug` 包为 16.96 MiB；`optimized` 包为 1.51 MiB。该差异来自 R8 与资源收缩，不涉及界面资源或布局改写。

### 5.3 模拟器验证

原 `RelayTesterApi35` AVD 因本机缺失其 API 35 系统镜像而无法启动，错误为系统镜像路径损坏；未改动该 AVD。为不阻塞验证，创建了隔离的临时 `RelayTesterPerfApi34`（Android 14 / API 34）模拟器，并在其中安装优化包。

安装方式为覆盖安装；临时 AVD 中没有真实站点凭据。五次 `force-stop -> am start -W` 的冷进程启动结果如下：

| 次数 | LaunchState | TotalTime |
| --- | --- | --- |
| 1 | COLD | 698 ms |
| 2 | COLD | 505 ms |
| 3 | COLD | 502 ms |
| 4 | COLD | 483 ms |
| 5 | COLD | 498 ms |

后四次平均为 **497 ms**；五次均由 `MainActivity` 成功进入前台。`AndroidRuntime` 错误缓冲为空，崩溃缓冲为空，`/data/anr` 中没有 ANR 文件。

为了分开观察初次 Compose 建树和稳定交互，页面稳定后执行了不触发网络的安全上下滚动：模型测试页共 251 帧、1.99% 慢帧，50/90/95/99 分位为 13/19/19/27 ms；余额查询页共 248 帧、3.23% 慢帧，50/90/95/99 分位为 19/31/34/38 ms。该临时 AVD 使用无窗口软件渲染，余额页的图形绘制数据应视作保守模拟器指标，而非硬件 GPU 真机帧率。

### 5.4 UI 零变更证据

从 0.7.5 备份 ZIP 中直接对比了以下 21 个可见页面、主题和资源文件的 SHA-256：

```text
MainActivity.kt
TesterScreen.kt
BalanceScreen.kt
ConfigurationBackupDialog.kt
app/src/main/java/com/relaytester/app/ui/**
app/src/main/res/**
```

结果为 `UI_SOURCE_HASH_COMPARISON=PASS`，所有文件字节完全一致。优化包已在模拟器展示并截图模型测试、余额查询两个主页面：

```text
artifacts/startup-ui-regression-v0.7.6/model-tester.png
artifacts/startup-ui-regression-v0.7.6/balance-query.png
```

因此，本版没有添加第二个应用内启动页、骨架屏、弹窗或任何可见 UI 改动；现有单一系统 Splash 交接逻辑也没有修改。
