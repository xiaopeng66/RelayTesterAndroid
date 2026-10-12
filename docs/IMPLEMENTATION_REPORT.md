# Relay Tester R01–R10 实施报告

报告日期：2026-10-07（第 1–5 节）；**2026-10-08 追加第 4A 节（第五轮：布局微调、两层弹窗、测试边界、reasoning 可视、预览放行）**
实施对象：Relay Tester 最新本地源码（`E:/AI/Zcode/projects/relay-tester`）
方案依据：[REPAIR_PLAN.md](E:/AI/Zcode/projects/relay-tester/docs/REPAIR_PLAN.md)（第 17 节为本报告的事实来源；第 17.2.5–17.2.9 为第五轮）

结论口径：**已实施**＝代码已改；**已验证**＝有确定性测试或设备观察证据；**未验证**＝无验收证据，如实保留，不宣称完成。

> **阅读顺序提示**：第 1–5 节是 2026-10-07 首轮交付的原文，其中的测试计数（605）与 debug APK 摘要描述的是**当时**的产物，保留作历史记录；**当前有效产物是第 4A.9 节的 optimized 正式签名 APK（617 测试）**。

---

## 1. 交付物

| 交付物 | 路径 | 状态 |
|---|---|---|
| Debug 安装包 | `E:/AI/Zcode/projects/relay-tester/app/build/outputs/apk/debug/app-debug.apk` | 已构建（首轮交付物） |
| **Optimized 正式签名安装包** | `E:/AI/Zcode/projects/relay-tester/app/build/outputs/apk/optimized/app-optimized.apk` | **当前交付物**，见 4A.9；**未发布** |
| APK 摘要 | 见下方 SHA-256 | — |
| 单元测试报告 | `app/build/reports/tests/testDebugUnitTest/index.html` | 605/605 通过（首轮）；**当前 617/617，见 4A.8** |
| Lint 报告 | `app/build/reports/lint-results-debug.txt` | 0 error / 11 warning |
| 本实施报告 | `docs/IMPLEMENTATION_REPORT.md` | 本文件 |
| 方案文档（已更新实施状态） | `docs/REPAIR_PLAN.md` 第 17 节 | 逐项状态 + 验证边界 + 证据索引 |
| 门禁原始日志 | `E:/AI/Zcode/tmp/relay-tester-review/final-gates.log`（门禁阶段输出）、`apk.log`（`assembleDebug`） | 本机临时证据，非永久归档 |

APK 的 SHA-256：

```text
9308b82a1d0af57c6e817e7509c2e7f7a1902e367bd12fd0ec49e738102f1101
```

该摘要对应 2026-10-08 第五轮修订后的构建（最新一次 `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug` 全部通过）（`testDebugUnitTest` + `lintDebug` + `assembleDebug` 全部通过），并已覆盖安装到 `emulator-5560`。此前四版分别为 `4c1d73ca35e8ebf89358db61ec92843ba7cb71bd17a46079e7fe1346aa26f26f`（取消后无法重新开始检测的修复）、 `33cd7c71e9fcb0c924d73338bd9ecbedc66dc9fed2159e85c3e259f5dc6579f1`（入口对齐 + 提示词默认收起 + 生成过程可视化）、`5c2c3bf3094fe39767310e0e7d45522a2111e32f41dd0b2f3b2d8d4f02640a8b`（面板外观修订）与 `b1e49e85aab5ce5f1628d2a59ed4aa3d70bd66a18d0b836ce32269c968411d03`（首轮），均已被取代。**注**：debug 变体的摘要每次构建都会变（APK 内嵌构建时间戳），不可复现；本报告中的 debug 摘要只在当次构建有效，**optimized 变体的摘要则是可复现的**（两次独立构建得到同一值，见 4A.9）。APK 为 debug 变体，`applicationId = com.relaytester.app.debug`，与 release 签名配置无关；本轮未修改任何签名配置，未运行发布脚本。

---

## 2. 逐项完成状态

| 编号 | 任务 | 状态 | 关键改动位置 | 验证结果 |
|---|---|---|---|---|
| R01 | Responses 状态与流终态判定 | 已实施 / 已验证 | `core/network/RelayApi.kt`（`ResponsesTerminal` 判定与 `finish_reason`/终态标记解析） | `RelayApiResponsesTerminalTest`、`RelayApiStreamingTest` 全通过；COMPLETED / FAILED / INCOMPLETE / UNCONFIRMED 四态可区分 |
| R02 | 指纹重试跨轮隔离 | 已实施 / 已验证 | `feature/fingerprint/FingerprintViewModel.kt`（`roundEpoch`、`invalidateRound()`、`retrySlot`、`retryJobs[key] === job` 身份校验、`serialGate`） | `FingerprintViewModelTest` 108/108 通过，其中包含跨轮污染、同键重试去重、取消清理、取消后重开、跑完可重开五条针对性用例 |
| R03 | 保存成功确认与草稿保留 | 已实施 / 已验证 | `feature/tester/`（`CatalogSaveOutcome`、纯函数 `catalogSaveResetsDraft(...)`） | `TesterPanelRegressionTest` 21/21 通过，其中直接调用 `catalogSaveResetsDraft` 覆盖保存/改名/重命名冲突等分支 |
| R04 | 窄屏大字号布局 | 已实施 / 已验证 | `feature/tester/`（堆叠阈值 `342.dp` 与 `fontScale 1.3`，判定写法 `maxWidth / fontScale < threshold`） | 设备观察：320dp / fontScale 1.6 下 `maxWidth=320.0.dp stack=true`，余额卡单列、头部堆叠、名称整行可读 |
| R05 | 余额凭据入口 | 已实施 / 已验证 | 供应商卡片铅笔入口分支 | 设备观察：当前供应商直接打开"余额查询凭据"，未触发切站锁；非当前供应商仍走站点切换流程 |
| R06 | 历史操作排序 | 已实施 / 已验证 | `feature/tester/TesterViewModel.kt`（`enqueueHistoryOperation(block)` 串行链、`awaitIdle()`） | 指纹套件覆盖该串行链，`awaitIdle` 可汇合链尾；无交错写入 |
| R07 | 模型弹窗搜索 | 已实施 / 已验证 | 模型选择弹窗（弹窗内搜索 + `LazyColumn(items(...distinct(), key = { it }))`） | `ModelSelectionTest` 通过；重复模型项按键去重，不再发生 key 冲突 |
| R08 | 仅拉取来源语义 | 已实施 / 已验证 | `feature/tester/`（`TestStatus.SKIPPED`、`TestRunSummary.skipped`） | 设备观察：仅拉取来源显示为"已跳过"，不再计为失败；跳过数独立于失败数 |
| R09 | QuickJS 16KB 对齐 | 文档已实施 / **限制保留** | 无代码改动可用；依赖 `io.github.taoweiji.quickjs:quickjs-android:1.4.6` | Lint `Aligned16KB` 仍告警：`libquickjs-android.so` 非 16KB 对齐。**未修复，不得宣称支持 16KB 页设备** |
| R10 | 动画测试（新增功能） | 已实施 / **已验证**（详见第 4A 节） | `feature/fingerprint/AnimationTest.kt`、`AnimationPreview.kt`、`AnimationPanel.kt`、`FingerprintViewModel.kt`、`feature/tester/TesterScreen.kt` | 单元回归 56 条全通过；沙箱限制逐条静态核对 + 放行规则 10 例安全测试；**端到端设备走查已完成**——两层弹窗、默认提示词、生成/取消/重新播放、源码切换，且**预览实际渲染红圆并执行内联脚本**（第 4A.6 节）；如实保留的未验证项见第 4A.11 与第 5 节 |

R09 的说明必须保持原样对外传达：当前 APK 在 16KB 页大小设备上的 QuickJS 行为**未经验证**，也没有可用的替代依赖，属于已知限制而非已修复项。

---

## 3. 关键改动位置与实现要点

### R01–R03（核心优先级）

- **R01** 引入 `ResponsesTerminal` 枚举，把"流结束"与"协议成功完成"拆开：只有拿到明确终态才算 COMPLETED，EOF 截断为 INCOMPLETE，协议错误为 FAILED，无法判定为 UNCONFIRMED，上层据此决定是否重试与是否计为成功。
- **R02** 用 `roundEpoch` 给每一轮打纪元；`invalidateRound()` 使旧纪元结果在写回时被丢弃，重试槽位 `retrySlot` 捕获发起时的纪元，`retryJobs[key] === job` 做身份校验避免误删新任务；`runInFlight()` 刻意保留被取消的 `runJob` 句柄，配合 `serialGate` 互斥保证跨轮不交错。
- **R03** 用 `CatalogSaveOutcome` 显式表达保存结果，纯函数 `catalogSaveResetsDraft(...)` 决定何时清空草稿：只有确认写入成功才清空，失败保留草稿供用户重试。

### R04–R08（体验与正确性）

- **R04** 采用 `maxWidth / fontScale < 阈值` 的判定写法，而非直接比较 dp，使大字号下的可用宽度被正确折算，窄屏堆叠在 320dp/fontScale 1.6 下实际生效。
- **R05** 区分"当前供应商"与"其他供应商"两条凭据入口路径，避免查余额时被强制走站点切换。
- **R06** 历史记录的 append/clear 统一进入 `enqueueHistoryOperation` 串行链，消除并发写入导致的操作顺序错乱。
- **R07** 搜索框移到弹窗内部，并对过滤结果做 `distinct()` 后以模型名作 key，消除重复项导致的列表异常。
- **R08** 新增 `TestStatus.SKIPPED` 与 `TestRunSummary.skipped`，"仅拉取来源"这类不该跑检测的场景标记为跳过，不再计入失败。

### R10（动画测试，独立新功能）

- **入口**：指纹三题模型卡上，"动画测试"按钮位于**模型名之后、状态标签之前**（`feature/fingerprint/FingerprintScreen.kt`，与 `:1719` 注释一致）。
- **提示词**：`AnimationTest.kt` 中 `DEFAULT_ANIMATION_PROMPT` 与方案文档第 13 节逐字一致（已用字节比对确认）。面板内可编辑、可滚动、可恢复默认。
- **会话与草稿**：草稿在指纹页面会话内保留（关闭重开、旋转不丢），不跨应用重启持久化，不写入历史；提交时捕获提示词快照。
- **请求绑定**：请求绑定卡片原供应商与原模型，不会静默改用到当前选中的其他供应商；供应商已不存在时给出错误提示并拒绝发起。
- **凭据**：只存在于网络层，不进入 Compose 状态、不写入 HTML/WebView、不进剪贴板、不落日志。
- **单会话约束**：首版只允许一个动画会话；三题主轮或单题重试进行中禁用新动画请求，动画生成期间阻止启动三题请求（与三题共用同一把请求锁，但状态完全分离——不影响三题答案、有效计数、候选排序、置信度、历史与普通测试结果）。
- **只有"开始生成/重新生成"会发请求**，无重复提交、无静默重发；已有有效预览不会因重新生成失败而丢失；重新播放复用已获 HTML，不再调用模型。
- **预览沙箱**（`AnimationPreview.kt`，全部在应用侧原生实现，不依赖提示词或 HTML 格式检查）：
  - 无 `addJavascriptInterface`，页面无任何原生桥；
  - `allowFileAccess` / `allowContentAccess` / `allowFileAccessFromFileURLs` / `allowUniversalAccessFromFileURLs` 全部为 false；
  - `blockNetworkLoads`、`blockNetworkImage` 为 true，`cacheMode = LOAD_NO_CACHE`；
  - 通过 `loadDataWithBaseURL` 以固定的无凭据基址 `https://animation.invalid/` 加载，相对 URL 无可解析目标；
  - 应用侧注入 CSP：`default-src 'none'`、仅允许内联 script/style、`img-src data:`、`connect-src 'none'`、`frame-src`/`object-src`/`form-action`/`base-uri` 全部封禁；
  - `shouldInterceptRequest` 只放行文档自身，其余一律返回 403 空响应；
  - `shouldOverrideUrlLoading` 一律拒绝并提示"页面试图跳转到外部地址，已被拦截"；
  - 无新窗口、无下载、无文件选择器、无地理位置、无 JS 对话框（全部在 `WebChromeClient` 侧拒绝）；
  - 原生关闭/错误控件位于 WebView 之外，页面无法伪造；
  - `onRenderProcessGone` 返回 true 并由面板提供原生恢复；离屏时 `onPause`/`pauseTimers`，销毁时 `releasePreview()` 停止加载并解绑客户端。

  该沙箱是**受限视图，不是安全边界**：页面仍是进程内运行的不可信代码，不能宣称完全安全沙箱，也不能防御 WebView 自身漏洞。

---

## 4. 实际验证结果

### 4.1 门禁（最终干净源码上执行）

| 项目 | 结果 |
|---|---|
| `testDebugUnitTest` | 31 个测试套件、**605 测试、0 失败、0 错误、0 跳过**（累计新增：面板外观 3 条、生成过程可视化 5 条、提示词默认态 1 条、取消后重开 1 条、跑完可重开 1 条、第四轮入口搬家与测试列表 13 条） |
| `lintDebug` | **0 errors、11 warnings**：R09 的 `Aligned16KB` ×3；版本提示 `NewerVersionAvailable` ×4、`GradleDependency` ×1、`AndroidGradlePluginVersion` ×1；以及既有配置类告警 `ChromeOsAbiSupport` ×1（`abiFilters` 仅 arm64-v8a）、`InsecureBaseConfiguration` ×1（`network_security_config.xml` 允许明文，局域网中转基址需要）。**四轮修订均未新增任何 error 或告警类别**；后两类为既有配置选择，本轮未改动 |
| `assembleDebug` | BUILD SUCCESSFUL，退出码 0 |

### 4.1.1 面板外观修订（2026-10-08）

按使用反馈改了模型卡入口与动画面板的外观。三项改动都在 UI 层，没有触碰请求路径、状态机与 WebView 沙箱：

| 项 | 原状 | 现状 |
|---|---|---|
| 模型卡入口 | 描边按钮：有边框，点击时留下涟漪虚影 | 纯文字，无边框；抑制 `indication`（涟漪）但保留点击、无障碍节点与 40dp 触摸高度 |
| 面板容器 | `Surface(fillMaxSize)` 铺满窗口，无圆角 | 屏宽 94% 的圆角卡片（`shapes.extraLarge`，`tonalElevation 6dp` / `shadowElevation 10dp`），与「关于与更新」同形状 |
| 面板高度 | 固定占满窗口 | 随内容自适应，`heightIn(min = 0.45 屏高, max = 0.9 屏高)`；上限与其它弹窗同一条，超出交给内容滚动 |
| 提示词输入框 | 固定 `heightIn(160..320dp)` | 可收起/展开：展开 `120..280dp` 多行，收起 56dp 单行；状态 saveable，旋转不丢 |

改动位置：`FingerprintScreen.kt`（`AnimationTestButton`、`plainClickable`）、`AnimationPanel.kt`（卡片容器、`AnimationPromptSection`）。

新增 3 条源码契约测试（`DialogWindowContractTest`）：入口是纯文字且不带 indication、面板是 94% 圆角卡片且有高度上下限、提示词框可收起且收起态为单行。这些钉的是源码事实——用了哪个容器、限高挂在谁身上、收起态是不是真的单行——**不能替代人眼确认外观**。

### 4.1.2 入口对齐、提示词默认收起、生成过程可视化（2026-10-08 第二轮）

| 项 | 原状 | 现状 |
|---|---|---|
| 入口与「正在检测」的高度 | 入口用 `labelMedium`（12sp）且强制 40dp 最小高度，行盒高于状态胶囊（`labelSmall`，11sp），文字明显高于「正在检测」 | 入口改用与状态胶囊同一个 `labelSmall`，并在 40dp 触摸高度内垂直居中；两者落在同一 baseline |
| 提示词默认态 | 默认展开 | 默认收起；展开成为主动动作 |
| 生成过程 | 一条不确定进度条 + 「正在生成动画…」 | 显示正在写入的文档尾部（等宽字体、自动跟随末尾、可回看）与已收到字符数；完成后由动画整段替换 |

这一轮有一处不只是外观：**生成过程可视化需要真实接入流式回调**。`RelayApi.completeTextStreaming` 一直带 `onProgress`，R10 此前传的是空 lambda；现在接进 ViewModel，并把累积文本的尾部写入新增的 `AnimationSession.streamingText`。

几个刻意的取舍：

- `streamingText` 与 `html` 分开。`html` 只由解析通过的完整文档写入，所以半成品永远进不了预览；失败或取消时 `streamingText` 一并丢弃，因为半张页面留在屏幕上会看起来像「只是停住了」的结果。
- UI 状态只留尾部 `ANIMATION_STREAM_TAIL_CHARS = 4000` 字符。完整回答上限 200 万字符，每个 delta 都整份复制进状态的开销远大于用户真正要看的几行。
- 中转站一个 delta 都不发（或只发 `[DONE]`）不是失败：`streamingText` 保持 null、界面显示「等待响应…」，结果照常落地。

改动位置：`FingerprintScreen.kt`（`AnimationTestButton`）、`AnimationPanel.kt`（`AnimationStreamingSection`、默认收起）、`FingerprintViewModel.kt`（`onProgress` 接线、三处终态清空 `streamingText`）、`AnimationTest.kt`（新增字段与常量）。

新增 6 条测试：5 条行为回归（生成中可见正在写入的文档、完成后被替换、失败不留半成品、取消丢弃半成品但保留旧预览、无进度也能出结果）+ 1 条契约（生成中显示文档且与结果区互斥）。

### 4.1.3 取消后无法重新开始检测（2026-10-08 第三轮，用户报告缺陷）

**现象**：检测进行中点「取消」后，再点「开始」毫无反应——一个请求都不发，界面也不报错。

**根因**：`runJob` 被当作面板的锁使用，却从来没有人解锁。

起始守卫原本是 `_uiState.value.isRunning || runJob?.isCompleted == false`。一轮正常结束后，`runJob` 仍指向那个**已经完成**的 job：`isCompleted` 为 `true`，`== false` 恒为假，于是「看起来没在跑」；但句柄本身从不清空，任何按句柄判断的地方拿到的都是一个长期非 null 的值。取消把这个矛盾放大成死锁——被取消的 job 在 `catch (CancellationException)` 之后 `throw`，任何写在 `launch {}` 之后的清理代码都到不了。

**修复**（`FingerprintViewModel.kt`）：

| 处 | 改动 |
|---|---|
| `runInFlight()` | `_uiState.value.isRunning || runJob != null`——只问句柄还在不在 |
| 轮次启动 | `val job = viewModelScope.launch { … }` 后再 `runJob = job`，并 `job.invokeOnCompletion { if (runJob === job) runJob = null }` |
| `retryChallenge` 守卫 | 保持原设计（一轮在跑时不让重试插队）：`if (!state.isRunning && runJob != null) return` |

两个反直觉的地方，都写进了源码注释：

- 清理**不能**放进 `launch {}` 体内的 `finally`。`runJob = launch { … }` 是先启动协程体、再把句柄赋给字段；体内读 `runJob` 可能读到旧值或尚未赋值的 null，于是「该清的时候没清」，面板永久锁死——正是这个缺陷本身。`invokeOnCompletion` 注册在赋值之后，才是可靠的。
- 重试守卫**不能**写成 `if (runInFlight()) return`。那会把「一轮正在跑」也算作阻塞，而单题重试的模型早已落定，本来就该与整轮并行发出。改成那样会挂掉 3 条既有重试测试（并行开关下重试与整轮同时上线、串行时重试排在当前题之后、坏模型的单题重试排在健康模型在途题之后）。句柄的身份校验 `if (runJob === job)` 则保证被新轮次取代的旧轮次不会误清新轮次的锁。

**验证**：新增两条。

- `a cancelled round can be started again`：按用户动作走完整流程——起一轮 → 等第一题确实在线上 → 取消 → 等清理跑完 → 再点开始——然后断言这一轮自己该发的题数全部发出，且结果行不停在 `RUNNING`。
- `a finished round can be started again`：正常跑完后再点开始，第二轮三题必须重新发出。钉的是新 `runInFlight()` 的回归面。

**变异验证（诚实记录哪条才是缺陷的守卫）**：把源码改回旧写法并去掉句柄清理后重跑，`a cancelled round can be started again` 在 10 秒超时内失败，确实钉住本缺陷；`a finished round can be started again` **仍然通过**——已完成的 job 让旧守卫求值为「不忙」，所以它证明不了这个缺陷。前者是根因证据，后者只是防止新实现回退的护栏。

定位过程本身也修正了一个测试骨架缺陷：`awaitBatchSettled` 原先只轮询「没有 PENDING/RUNNING 行」，但轮次是在协程里才发布这些行的，启动窗口内列表为空，轮询会立刻满足、**在整轮开始前就返回**。现在先等面板真的 busy（或行已出现）再等排空。

### 4.1.4 动画测试入口搬家与测试列表（2026-10-08 第四轮，用户需求）

**需求**：入口从指纹测试页移到模型测试页「高级测试」旁；进入后先选供应商和模型；开始后弹窗里要有测试列表，可点进单条看进度；测试进行中关掉弹窗时按钮上要有提示，点该按钮直接回到测试列表。要求不影响其他功能、不引入新缺陷。

**关键约束**：动画测试的全部状态都在 `FingerprintViewModel`（与指纹三题检测共用供应商限速和凭据），而新入口必须长在 `TesterScreen` 上。采用**一个 ViewModel、状态借给另一个页面**——沿用既有的 `onFingerprintModel` 跨页先例，未新建状态机、未新增页面切换。

**改动**：

| 文件 | 改动 |
|---|---|
| `AnimationTest.kt` | 单会话 `session` → `sessions` 列表（新在前）+ `selectedSessionId` + `panelOpen` + 卡片自己的 `supplierId`/`model`/`models`；`AnimationSession` 加 `createdAt` |
| `FingerprintViewModel.kt` | 新增 `openAnimationPanel` / `closeAnimationPanel`（只收起，不取消）/ `selectAnimationSession` / `startAnimationTest` / `removeAnimationSession` / `selectAnimationSupplier` / `selectAnimationModel`；25 处写状态全部改为按 `sessionId` 定位 |
| `AnimationPanel.kt` | 单会话面板 → 列表视图 + 详情视图；列表 = 供应商/模型选择区 + 测试记录列表；选择器各自放在对话框里（卡片可滚动，内嵌下拉会被裁） |
| `FingerprintScreen.kt` | 删除 `AnimationTestButton`、`plainClickable`、`CHALLENGE_ACTION_MIN_HEIGHT` 及入口相关参数 |
| `TesterScreen.kt` | 新增 `AnimationTestEntry`，与「高级测试」并排；标题行按宽度（≥340dp）与字号（<1.3）决定并排还是换行 |
| `MainActivity.kt` | 桥接 `fingerprintState.animation` 与全部回调 |

**进行中提示**：入口读 `animationState.generating != null`，为真时播放图标右上角画 8dp 圆点角标，文案改为「动画生成中…」。关卡片不取消（`closeAnimationPanel()` 只置 `panelOpen = false`），取消与删除是两个独立动作。

**两个只有改成列表后才暴露的真实缺陷，本轮一并修掉**：

1. **被取代的生成永远停在 GENERATING**。`startAnimationGeneration()` 会取消上一条请求，而 `generateAnimation` 捕获 `CancellationException` 后直接 `throw`（旧语义下被取消的会话整个丢弃，不写终态是对的）。记录现在会留在列表里，那一行就会永远转圈。新增 `settleReplacedAnimation(keepSessionId)`，在取消前把其它仍在 GENERATING 的记录落定为 READY（有旧结果）或 FAILED，并清掉半截流式文本。
2. **同供应商 + 同模型重复建记录**：再点一次「开始生成」是重跑那条测试，`startAnimationTest` 复用已有记录而不是新增一行。

**验证**：

- 契约测试语义改写（非放宽）4 条：`AnimationPanel.kt` 收起状态键改为 `selected?.sessionId`；「入口是纯文字无涟漪」语义反转，改为断言「入口在 `TesterScreen`、是与高级测试同规格的描边按钮、带进行中角标、与后者同处渲染」。
- 旧关闭语义测试改写 3 条：关卡片保留进行中的测试；删除才取消；晚到响应落到它自己那条记录上。
- 新增 12 条：关卡片不取消、删除取消、晚到响应不复活记录、同供应商同模型复用记录、换模型是新记录、列表顺序、选中/返回列表、选不存在的记录被忽略、未知供应商与空模型不留记录、换供应商清空模型、动画选供应商不扰动检测页、开新测试取消旧测试但保留其记录。
- **一处测试自身的判断失误，如实记录**：最初两条新测试假设「两条测试可以同时在生成」，断言被取代的记录仍是 GENERATING。这假设是错的——共用限速下同时只允许一条生成。是测试错了，不是实现错了，断言已改为实际契约。

**真机走查**（emulator-5560，debug 包组件 `com.relaytester.app.debug/com.relaytester.app.MainActivity`）：

| 检查 | 结果 |
|---|---|
| 入口位置 | 「动画测试」与「高级测试」并排于「测试参数」标题行，同规格描边按钮（截图 `entry.png`） |
| 列表视图 | 供应商、模型选择器；未选供应商时模型禁用并提示「该供应商没有模型」；无记录时提示「还没有测试记录」（`card2.png`） |
| 选供应商/模型 | 选「供应商 3」后模型选择器变可用「请选择模型」，选项为其 2 个模型；选好后「开始生成」由禁用变可点 |
| 生成中详情 | 点击后进入详情，显示「正在生成 / 等待响应…」与「取消生成」（`generating.png`） |
| 关卡片不丢记录 | 生成中关卡片再打开，卡片回到该记录的详情并显示真实终态（`上游返回 HTTP 502`），记录未被丢弃 |
| **进行中角标** | 生成中关卡片，入口文案变为「**动画生成中…**」，播放图标右上角出现蓝色圆点（`BADGE2.png`） |
| 点角标回到测试 | 点带角标的入口，卡片直接打开在**仍在进行**的那条测试（「正在生成 / 等待响应…」） |

走查用本地假服务（`E:/AI/Zcode/tmp/relay-tester-review/fake-anim/server.py`，绑定 `0.0.0.0:8099`，只回固定 HTML 样例），**未调用任何真实供应商**。为制造足够长的进行中窗口，临时把「供应商 3」Base URL 指向 RFC 5737 的 `192.0.2.1` 使连接挂起；走查结束后**已恢复原值** `http://10.0.2.2:8099/v1` 并复核保存成功，假服务已按 PID 停止、8099 端口已释放。

### 4.2 R10 针对性与静态验证

- `AnimationTestRegressionTest` **38/38 通过**：默认提示词逐字比对、空白不可提交、提交时快照绑定、三题状态隔离、单会话约束、CSP 注入、重新播放不重发请求，以及本轮新增的 5 条生成过程用例。
- 沙箱控制在源码中逐条落地并可静态核对（见第 3 节 R10 列表）。
- 面板外观：3 条源码契约测试通过（见 4.1.1）。
- 设备侧可达性：debug 包在 `emulator-5560` 正常启动；本地假服务（`127.0.0.1:8099`，经 `10.0.2.2` 访问）`GET /v1/models` 返回 2 个模型、`POST /v1/messages` 返回固定 HTML+SVG 文档；批量拉取与三题检测轮均实际走通（可用 2 / 不可用 0）。
- 走查过程中定位并修复了一处**测试骨架缺陷**：`parkedRetryState` 误用 `skipRestore = true` 构造路径，导致重试落入不发请求的分支、`await()` 永久等待，套件挂起超过 15 分钟。改用 `readyApiViewModelFor(SingleSupplierStore(testSupplier()), api)`，并给所有重试等待加 `withTimeout`，套件从挂起变为 3 秒内通过。

### 4.3 环境状态

- 显示设置已恢复原值：1080×2400 / density 420 / font_scale 1.0。
- 模拟器 `http_proxy` 走查期间曾清空以绕开全局代理拦截，**收尾已恢复原值 `10.0.2.2:7897`** 并复核通过。
- 第三轮假服务（端口 8099）已停止，停止前核对了进程命令行与端口归属。
- 第四轮改动的设备侧唯一持久项是「供应商 3」的 Base URL：走查期间为制造挂起窗口临时指向 `192.0.2.1`，**收尾已恢复原值 `http://10.0.2.2:8099/v1`** 并重新打开对话框复核保存成功。假服务（`fake-anim/server.py`）已按 PID 停止，`netstat` 复核 8099 端口已释放。其他模拟器（5556 / 5558）未改动。

---

---

## 4A. 第五轮（本轮）：布局微调、两层弹窗、测试边界、reasoning 可视、预览放行

本轮四项需求与一项边界审计。以下按「需求 → 改动位置 → 证据」排列，**已实施 / 已在设备验证 / 仍未验证**分别标注。

### 4A.1 指纹页：筛选输入进模型列表弹窗，页面只留胶囊按钮，并行开关同行居中

**需求（用户原话）**：「指纹检测的输入框放在模型列表按钮点开的弹窗里，不是在外边，外边不需要输入筛选，只留一个模型列表按钮，右边并列三题测试的文字说明」。

> **本条在 2026-10-08 第三轮被用户更正。** 第二轮的实现是把筛选框留在页面上、只是套成胶囊外形——位置错了，输入框本该在弹窗里。

**改动**：FingerprintScreen.kt 的 `ModelPicker` 缩成一个 `Row`：左侧是 `OutlinedButton(shape = RoundedCornerShape(percent = 50))` 胶囊（列表图标 + 「选择模型」／「已选 N 个模型」），点它打开列表弹窗；胶囊右边是**可见文字「三题并行」**，再右边是开关，`verticalAlignment = Alignment.CenterVertically` 居中。筛选输入框搬进 `ModelListDialog`。

**这一轮同时修掉两个真实缺陷（都是读代码时发现的）**：

1. **两个筛选词互相盖住**。页面上有一个筛选框（写 `state.modelFilter`），弹窗里又有一个自带的 `rememberSaveable` 的 `query`；弹窗收到的是**已经过滤过**的 `matches`，于是列表是两份关键词的交集，而页面上那个关键词在模态弹窗背后根本改不到——列表被一个看不见也清不掉的词筛窄了，兜底行还会请用户去测一个他已经看不见的名字。现在全局只有一个筛选词（`state.modelFilter`），弹窗的输入框直接读写它；弹窗里的 `unmatched` 也改为自己算一次，不再由外面传进来（同一件事两个来源正是这个缺陷的成因）。
2. **空目录时只给提示、不给输入框**。原文案是「暂无模型；可在『模型测试』拉取，或直接输入模型名」，但输入框被挡在 `else` 分支里——**让人直接输模型名，却没有地方输**。对目录为空的供应商，这是唯一一条能配上模型的路，等于被自己堵死。现在输入框无条件渲染，空目录时紧跟在提示下方。设备实测：供应商 2（0 个模型）打开弹窗后，提示下方就是「筛选模型」输入框，输入 `my-custom-model` 立即出现「不在列表，直接检测」的兜底行。

**为什么开关旁边要有文字**：只有一个孤零零的开关时，「并行」这个词只存在于无障碍描述里（`contentDescription = "并行发送三题"`），**看得见的用户看不到它开的是什么**。现在有一行可见的「三题并行」，并且给它挂了 `clearAndSetSemantics {}`——不这样读屏会把同一句话念两遍（标题念一次、开关念一次）。

**验收**：`the model list is filtered by one field, and it exists even with no catalogue`（源码契约，断言页面这一层不再有 `OutlinedTextField`、弹窗里必须有且只有一个 `filter` 参数驱动的输入框、不再有 `var query by rememberSaveable`、输入框不在空目录分支之内）与 `the parallel switch says out loud what it switches`（断言「三题并行」存在且配了 `clearAndSetSemantics`）。**变异验证**：把源码改回旧形态（弹窗自带 `query` + 输入框塞回空目录 `else` 分支）→ 第一条**失败**；改回后通过。

设备实测（debug 与 optimized 各一次）：胶囊按钮独占左侧，点开弹窗顶部就是「筛选模型」，输入 `eof` 把 4 个模型筛成 `mock-eof` 一个，「清空筛选」随之出现。

### 4A.2 动画测试：两层弹窗（列表 → 详情）

**需求**：点「动画测试」先看到「模型选择按钮 + 测试列表」；点列表里某一条才进入现在的测试界面；两层都是弹窗。

**改动**：AnimationPanel.kt 由一个「按选中态切换内容」的卡片改为**两个各管一件事的 `Dialog`**：

- `AnimationTestListDialog`：`AnimationSetupSection`（供应商 + 模型 + 开始生成）与 `AnimationSessionList`（测试记录）。
- `AnimationTestDetailDialog`：`AnimationPromptSection`、生成中/结果区、`AnimationActionBar`。
- 公共外观抽成 `AnimationCard`（屏宽 94%、圆角、高度上下限）。

**入口永远落在列表**：`openAnimationPanel()` 打开时一并清掉 `selectedSessionId`。这一点必须做在 ViewModel 而不是只在弹窗里判断，因为入口由 TesterScreen 的本地 `showAnimationSheet` 控制，选中的记录会跨「关闭—重开」存活，否则点入口会直接弹进上次看了一半的那条记录。`TesterScreen` 新增 `onOpenAnimation` 回调接到 `openAnimationPanel`。

**返回语义**：详情层的返回箭头、系统返回、点卡片外都退回列表层；关闭按钮关掉整层。

**契约测试**：DialogWindowContractTest 新增 `the animation test opens on its list, and a record is what opens the detail`——断言文件里恰好 2 个原生 `Dialog`、列表层含 `AnimationSetupSection`+`AnimationSessionList` 且**不含**操作栏/提示词区、详情层含提示词区+操作栏且**不含**选模型/记录列表、两层互斥（`if (selected == null)` / `else`）、`openAnimationPanel` 里清 `selectedSessionId`。既有的 `the animation panel's prompt box collapses on request` 的键断言改为「键里必须含 `sessionId`」这一**形状**断言（键的写法已从 `selected?.sessionId` 变成详情层参数 `session.sessionId`，语义未放宽）。

**证据**：`608 tests completed, 1 failed` → 修正键断言后全绿；设备走查见 4A.5。

**状态**：已实施 + **已在设备验证**（见 4A.5 的「两层弹窗」两行）。

### 4A.3 动画测试按钮：进行中不改文案、只加角标；两个按钮改小与标题对齐

**需求**：动画测试进行中按钮文字不变、只加角标；动画测试与高级测试按钮改小，避免和「测试参数」标题错位。

**改动**：

- `AnimationTestEntry` 的文案固定为「动画测试」，进行中**只**在图标右上角画一个 7dp 圆点角标（`CircleShape`，`primary` 色）。理由写在 KDoc 里：按钮的职责是「好找」，在用户眼皮底下重写自己的标签会让他的瞄准目标移动；角标挂在图标上，因此标签被截断也不影响它。
- 两个入口都缩小：`contentPadding = PaddingValues(horizontal = 8.dp)`、`heightIn(min = 32.dp, max = 36.dp)`、`widthIn` 上限收窄、图标 16dp、`labelLarge`。
- 换行判定跟着字号走：`SETTINGS_HEADER_BUTTONS_WIDTH = 200.dp`（两个四字按钮 + 图标在 fontScale 1 下的自然宽度）与 `SETTINGS_HEADER_TITLE_MIN_WIDTH = 68.dp`，按 `density.fontScale` 缩放后决定并排还是换行。
- **2026-10-08 第三轮再缩一轮**（用户要求「再小一点」）：`contentPadding` 8→6dp、`heightIn` 32/36→28/30dp、图标 16→14dp、字号 `labelLarge`→`labelMedium`、`widthIn` 上限同步收窄（动画 112→92dp、高级 100→82dp）、角标 7→6dp。`SETTINGS_HEADER_BUTTONS_WIDTH` 必须一起从 200dp 改到 168dp——**这个常量写大了，在本来放得下的屏幕上会让标题行误判放不下而换行**，正是用户要求避免的那种错位。

**证据（设备，release 签名包，emulator-5560）**：`uiautomator dump` 量到「测试参数」标题纵向中心 1097.5px、「动画测试」1098px、「高级测试」1098px——**同一行、中心对齐，偏差 0.5px，无换行**（截图 `release-main.png`）。第三轮再缩后复测：标题中心 1075px、两个按钮 1076px，**仍同行、偏差 1px、无换行**。

**状态**：已实施 + **已在设备验证**。

### 4A.4 「一直等待响应、没有思考过程也没有输出」：根因与修复

**需求**：动画测试配置是不是有问题？为什么一直停在等待响应、既没有思考过程也没有输出结果？

**根因（读代码 + 复现，不是推断）**：`RelayApi.readEventStream` 只在**累积的答案正文**增长时回调 `onProgress`，而 `extractStreamDelta` 对 `reasoning_content` / `thinking_delta` / `response.reasoning_summary_text.delta` **故意返回空串**（对「答案」而言这是对的：思考不是答案）。于是推理模型在写出第一个答案字符之前，界面**没有任何信号**，面板就一直是「等待响应…」——与请求卡死完全无法区分。

**修复**：给思考单开一条通道，绝不混进答案：

- `RelayApi.completeTextStreaming` / `readEventStream` 新增 `onReasoning`；当一帧没有答案文本时，用新增的 `RelayCompletionText.extractStreamReasoning(payload, protocol)` 取思考（按协议：Chat 的 `reasoning_content`→`reasoning`、Anthropic 的 `thinking_delta`、Responses 的 `response.reasoning_summary_text.delta`），非空才上报。
- `AnimationSession` 新增 `reasoningText`（与 `streamingText` 一样尾部截断）。它**绝不进 `html`**：思考不是答案，且「只有思考没有答案」的流仍由既有的空答案检查判为失败。
- 面板显示改为三态：有正文 → 「已收到 N 字符」；只有思考 → 「模型正在思考…」并单独显示思考内容；都没有 → 「等待响应…」。
- 三题检测不订阅 `onReasoning`（思考不参与判分、也没有显示位置），并在调用处写明。

**测试**：RelayApiStreamingTest 新增 3 条（纯 reasoning 流要报告活动、Anthropic thinking 流同样、只有思考没有答案仍算空）。**变异验证**：把 `extractStreamReasoning` 改成直接返回空串，两条新测试**失败**；改回后通过——证明这两条确实在守这个修复，而不是恰好通过。

**一次自我纠正（如实记录）**：第一版 reasoning 测试「通过」了，但它断言的是 `onProgress`，而流里随后有答案帧也会触发 `onProgress`，所以它**什么都没证明**。改为断言专门注入的 `onReasoning` 回调后才成为有效测试。

**状态**：已实施 + 变异验证；**「真机上推理模型先出思考」这一观感未验证**——设备走查用的假服务返回的是普通流，不含 reasoning 帧。

### 4A.5 测试边界审计：进行中的互不干扰、取消后不残留

**需求**：检查所有测试边界——动画测试、指纹测试、余额测试、模型测试进行中点击别的按钮不影响该进程；取消后不再占据后台测试进程、影响下一次测试。

**审计发现的一处真实不对称缺陷（本轮修复）**：动画生成会拒绝在三题检测进行时启动（`roundBusy()`），但**反过来没有**——三题检测（`runApiDetection`）与单题重试（`retryChallenge`）都不检查动画是否在跑。两者共用同一供应商的凭据与限速，于是「会不会有两个请求同时挂在一条 key 上」取决于用户先按了哪个键。

**修复**：新增 `animationBusy()`（读 `animationJob?.isCompleted == false`——取消时 `invalidateAnimationRequest` 会把句柄清成 null，所以取消后读到的 false 是「这里没有东西了」而不是「刚好跑完」）与 `refuseWhileAnimationRuns()`（说明原因、**拒绝而不是取消**动画：动画是用户主动发起的，屏幕另一处的按钮不该把它拿走），在 `runApiDetection` 与 `retryChallenge` 两处接入。

**新增 4 条行为测试**：

1. 动画生成中按检测 → 不发起请求、`isRunning` 保持 false、**必须给出原因**（含「动画」）。
2. **取消动画后检测能起来**（必须把检测停在闸门上才作数：否则它会同步跑完，`isRunning` 读到的 false 是「已经结束」而不是「没被拦住」，测试就什么都没证明）。
3. **动画正常跑完后检测能起来**（同上，闸门 + 超时）。
4. **取消之后能再发一次动画请求**（`api.calls` 从 1 到 2、新的一次真的处于 GENERATING）——正对用户原话「取消后不再占据后台测试进程，影响下一次测试」。

**超时保护**：等待带上界 `ROUND_START_TIMEOUT_MILLIS`。第一次用无上界等待做变异验证时套件**挂住**了——那正是「过度拦截」这种回归的失败形态，读起来像环境问题；加界后过度拦截 5 秒内失败并报 `TimeoutCancellationException`。

**变异验证（双向）**：

- `animationBusy()` 恒为 `false`（等价于修复前的行为）→ 「动画生成中按检测」**失败**。
- `animationBusy()` 恒为 `true`（过度拦截）→ 「取消后」与「跑完后」两条**失败**（超时）。

两个方向都能被抓住，说明这三条不是白写的。

**其余三页的审计结论（已读代码，未新增改动）**：

- **余额页**：查询走独立状态流 `_balanceUiState`，不写模型测试的 `_uiState`；三个入口（`queryBalance` / `refreshSupplierBalance` / `queryAllBalances`）共用 `balanceQueryStartMutex` + `releaseWhenSettled`（`invokeOnCompletion` 释放，异常/提前 return 也会释放）。页面还把模型测试正在跑这件事收进 `siteSwitchLocked`，被拒时说明原因而不是静默吞掉点击。
- **模型测试页**：`startTest` 用 `testRunStartMutex.tryLock` + `runInFlight()`（`testJob?.isCompleted == false`）双重把关，并在获取 runner 之后、真正启动之前**再查一次 `isRunning`**——那一段是可取消窗口，关闭它才不会出现「点了取消却照样发请求」。取消只 `cancel` 句柄而不清空，正是为了让「已取消但还没清理完」的尾巴继续挡住下一次启动（既有测试 `a cancelled request must finish cleanup before another run can start` 守着这条）。
- **指纹页**：进行中把选型、筛选、模式、题目、开始等控件置灰（`enabled = !state.isRunning`）。

**状态**：动画↔检测的对称守卫已实施 + 双向变异验证；其余三页结论为**读代码所得，未做设备级并发走查**。

### 4A.6 预览放行规则：一处只有真机能发现的缺陷

**需求（附带）**：设备走查应确认预览真的能显示动画。

**缺陷（真机发现，单元测试不可能发现）**：预览区显示的是引擎的失败页——`Webpage not available / net::ERR_HTTP_RESPONSE_CODE_FAILURE`，而不是动画。

**根因**：`shouldInterceptRequest` 只放行 `ANIMATION_PREVIEW_BASE_URL`，但 `loadDataWithBaseURL` 交给渲染进程的**主框架请求带的是 `data:` 地址**，于是沙箱把**自己的文档**当外部资源拦掉了（返回 403 空响应），页面自然渲染不出来。

**修复**：抽出 `previewRequestAllowed(url)`：放行 `data:`（文档自身与 CSP 已允许的 `img-src data:` 内联图——`data:` 到不了网络，导航另有拦截，因此不构成逃逸通道）与固定的 `ANIMATION_PREVIEW_BASE_URL`；其余（`http(s)`、`file:`、`content:`、`javascript:`、`about:`）一律拒绝。同时补 `onReceivedError`：**只在主框架**上报载入失败，避免某个被拒的子资源把可用页面误报为失败。

**测试**：新增 `the preview admits its own document and nothing that could reach out`——正向 3 条（base、`data:text/html`、`data:image/svg+xml`）+ 反向 7 条（http、https、供应商地址、file、content、javascript、about）。

**变异验证（双向）**：把规则改回旧的「只认 base」→ 测试**失败**（抓住原缺陷）；把规则改成全放行 → 测试**失败**（抓住安全边界被拆）。两个方向都守住。

**设备证据（release 签名包，emulator-5560，本地假服务返回带内联 script 的红圆文档）**：预览区显示出**红色圆形**，且脚本执行成功（标题从 `FAKE-ANIMATION-OK` 变成 `FAKE-ANIMATION-OK-JS`）——既证明文档被放行、也证明 `script-src 'unsafe-inline'` 生效而沙箱其余限制仍在。切到「源码」视图显示 HTML 源码（截图 `preview-ok.png` / `source-view.png`）。

**状态**：已实施 + **已在设备端到端验证** + 双向变异验证。

### 4A.7 另一处真机发现的缺陷：卡片打开时目录是空的

**缺陷（设备走查发现）**：从模型测试页打开动画卡片，供应商选择器显示「请选择供应商」，下拉里**什么都没有**——而供应商列表就在它背后那一页上。

**根因**：动画卡片读的是指纹 ViewModel 的 `knownSuppliers`，而这份目录只在**指纹页被展示时**刷新（`FingerprintScreen` 的 `refreshCatalogue()`）。入口本轮才搬到模型测试页，于是「先拉模型、直接点动画测试、从没进过指纹页」这条最顺的路径上，目录还是空的。

**修复**：`openAnimationPanel()` 里先 `refreshCatalogue()` 再打开。理由是那句最糟的形态——弹窗里说「还没有可用的供应商」，而供应商就在它背后。

**测试**：新增 `opening the card reads the catalogue it needs to offer a supplier`。**这条测试的第一版是自证的**（fixture 的 helper 已经预读过目录），改成「先清空 `knownSuppliers` 与 `uiState.suppliers`，再断言打开后非空」才有效。**变异验证**：去掉 `refreshCatalogue()` → 测试**失败**。

**状态**：已实施 + 已在设备验证（下拉里正确列出「供应商 1 / 供应商 2」）+ 变异验证。

### 4A.8 本轮门禁

```
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug     ->  BUILD SUCCESSFUL
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleOptimized ->  BUILD SUCCESSFUL
```

- 单元测试：31 个套件、**617 测试、0 失败、0 错误、0 跳过**（在第四轮 605 条的基础上净增 12 条）：
  - `RelayApiStreamingTest` 新增 3 条 reasoning 通道测试（纯思考流要报告活动、Anthropic `thinking_delta` 同样、只有思考没有答案仍判空）；
  - `AnimationTestRegressionTest` 新增 4 条边界测试（动画中拒检测、取消后不拦、跑完后不拦、取消后可再发一次）＋ 1 条预览放行 10 例安全测试 ＋ 1 条「打开卡片要现读目录」；
  - `DialogWindowContractTest` 新增 1 条两层弹窗契约，并改写既有键断言为形状断言（语义未放宽）；
  - 第三轮再新增 2 条源码契约（单一筛选词 + 空目录下输入框必须存在；并行开关必须有可见文字说明），并删掉因改动而失效的 `Badge`/`BadgedBox` 两个未用导入。
- 本轮所有新增/修改的行为测试与安全测试都做了**双向变异验证**（见 4A.4–4A.7），即「把修复改回旧行为会失败、把规则放宽也会失败」。

### 4A.9 本轮交付物：optimized（正式签名）APK

用户授权「构建优化版正式签名的 apk，但是不要发布」。

| 项 | 值 |
|---|---|
| 变体 | `optimized`（`initWith(getByName("release"))`，与发布同一套签名；minify + shrinkResources 开启） |
| 路径 | `E:/AI/Zcode/projects/relay-tester/app/build/outputs/apk/optimized/app-optimized.apk` |
| 大小 | 3,121,047 字节（第三轮重建后相同大小） |
| **SHA-256** | `dfd82ed9b3db975ff62d7b93c582a5abb6bb5c5e9169311ed244eb07ebccfe7d`（第三轮后的重建；第二轮为 `b6002b3089e7580ea2a4dfccd40f596c3716992289c15200ffb20dd0fd9fb6a9`，已被取代） |
| 包名 / versionCode / versionName | `com.relaytester.app` / 10703 / 1.7.3 |
| 签名 | `apksigner verify --print-certs`：Signer #1 `CN=Relay Tester, OU=Release, O=Relay Tester, C=CN`，证书 SHA-256 `05c3e56705aa8c420523f999d950ce74482c42f4376ac88918db10e9451d69de` |
| 新鲜度 | `find app/src/main -name "*.kt" -newer <apk>` 为空（APK 比所有源码新） |
| 发布 | **未发布**：未运行任何发布脚本、未上传任何渠道 |

**构建方式说明**：签名口令按设计放在仓库之外的 `~/.gradle/gradle.properties`，而本轮门禁为隔离使用了重定向的 `GRADLE_USER_HOME`，因此 `assembleOptimized` 报 `SigningConfig "release" is missing required property "storePassword"`。解决办法是把 `GRADLE_USER_HOME` 指回真实目录 `C:/Users/renyingpeng/.gradle` 完成该次构建——**没有**把口令写进仓库或命令行明文。构建过程中未打印任何口令。

### 4A.10 本轮设备走查的环境改动与恢复

| 项 | 原值 | 改动 | 恢复 |
|---|---|---|---|
| `http_proxy` | `10.0.2.2:7897` | 走查期间清空为 `:0`（否则本机假服务的流量被全局代理拦成 502） | **已恢复**为 `10.0.2.2:7897` 并复核 |
| `global_http_proxy_host` | `10.0.2.2` | 删除 | **已恢复**并复核 |
| `global_http_proxy_port` | `7897` | 删除 | **已恢复**并复核 |
| 假服务 | 8099 端口 | 启动（只回固定 HTML 样例，绑定 `0.0.0.0`） | **已按 PID 停止**，端口已释放 |
| 展示的 App | debug 包 | 安装 release 签名包用于走查 | release 与 debug 包共存（包名不同，互不影响） |

未改动发布签名文件，未按过期 PID 杀进程（停止前核对端口归属），未调用任何真实供应商。

### 4A.11 本轮仍未验证（如实声明）

1. **指纹页胶囊选择器与并行开关的实机观感未逐像素验收**（本轮设备走查未截图指纹页）。
2. **推理模型「先出思考」的真机观感未验证**——假服务返回的是普通流，不含 reasoning 帧；该修复目前只有单元级 + 变异验证证据。
3. **取消后不残留这条性质，只在动画↔检测两路做了确定性行为测试**；模型测试与余额页是读代码结论（既有测试 `a cancelled request must finish cleanup before another run can start` 覆盖了模型测试页的取消尾巴），未做设备级并发走查。
4. **动画卡片的「进行中角标」在 release 包上未截到**：走查用的假服务现在瞬时返回，生成窗口太短来不及截图；角标逻辑由单元测试与源码契约覆盖（debug 包在上一轮已实测到角标；本轮按要求改为文案不变、只留角标，属外观改动）。
5. 其余限制沿用第 5 节（R09 QuickJS 16KB 未修复、16KB 设备未验证等）。

### 4A.12 第三轮（2026-10-08 用户更正与缺陷自查）

**用户更正**：4A.1 的位置理解错了——筛选框该在弹窗里。已按更正重做（见 4A.1 的新正文），并顺带修掉读代码时发现的两个真实缺陷（两个筛选词互相盖住；空目录时提示「可直接输入模型名」却不给输入框）。同时把两个入口按钮再缩一轮。

**「检查最近的修改有没有带来 bug」的自查结果**（逐项读代码，不是跑一遍测试就算）：

| 检查项 | 结论 |
|---|---|
| 动画 ↔ 三题检测的互斥 | **对称**：`animationBusy()` 拒检测、`roundBusy()` 拒动画，两侧都有守卫；`runManualAnalysis`（手动粘贴评分）不发请求，因此不需要该守卫，但它读的是 `runJob?.isActive` 而非 `runInFlight()`——不构成缺陷，因为手动评分不碰凭据 |
| 迟到的响应写错记录 | **不会**：`sessionId` 由单调递增的 `animationSessionSequence` 分配、**从不复用**，且每次写入前都比对 `requestId`，两道检查叠加 |
| 取消后句柄残留 | **不会**：`invalidateAnimationRequest()` 同时 cancel 并把句柄置 null，`animationBusy()` 读 `isCompleted` 覆盖「已取消但还在收尾」的窗口 |
| 预览沙箱被 `data:` 放行削弱 | **否**：放行的只有文档自身的 `data:`（到不了网络，导航另有 `shouldOverrideUrlLoading` 全程拒绝）与固定 base；10 例测试含 7 例反向拒绝（http/https/供应商地址/file/content/javascript/about）；`onReceivedError` 只报主框架，避免被拒子资源把好页面判成失败 |
| 筛选状态搬迁是否漏改调用点 | **已改净**：`unmatched` 不再由外部传入（同源重复正是原缺陷成因），页面层不再有输入框，`Badge`/`BadgedBox` 成为未用导入已删除；`lintDebug` 无新增告警 |
| 按钮缩小是否连带破坏换行判定 | **已同步**：`SETTINGS_HEADER_BUTTONS_WIDTH` 200→168dp；实测量测仍同行（偏差 1px） |
| 是否误伤既有契约 | `every raw dialog sizes its box…` 与 `every raw dialog carries a dismiss scrim` 仍按「原生 `Dialog` 数 = 定尺盒数 = 遮罩数」通过；本次未新增弹窗 |

**仍未验证**：`三题并行` 那行文字的取舍是读代码 + 契约测试结论，**未做读屏实测**；第三轮的会话/边界改动未在真机上做并发走查（与 4A.11 同一限制）。

## 5. 剩余限制与未验证项

必须如实传达，不得当作已完成：

1. ~~R10 的界面与 WebView 运行时行为没有端到端设备证据。~~ **此条已于 2026-10-08 由多轮实机走查关闭**（入口与标题同行对齐、弹窗一/弹窗二、默认提示词、开始生成、取消生成、返回测试列表、重新播放、源码视图、预览渲染红圆并执行内联脚本），证据见第 4A.6 与 REPAIR_PLAN 第 17.2.1–17.2.5 节。**仍未验证的 R10 子项**：推理模型「先出思考」的真机观感（假服务不含 reasoning 帧）、release 包上「进行中角标」的截图、以及**真实供应商模型生成的 HTML/SVG 质量**——按安全约束全程未调用真实供应商。
2. **R09 未修复。** `libquickjs-android.so` 非 16KB 对齐，lint 持续告警；在 16KB 页设备上的实际运行未验证，不得宣称支持。
3. 跨轮 retry 结果污染、保存失败时 UI 草稿丢失、历史 append/clear 交错这三类故障注入的**确定性复现**未做（已有单元覆盖，但未做故障注入级复现）。
4. 长模型列表的性能未测量。
5. 横屏、窄屏指纹三题卡的**全部状态**未逐一验收（R04 只覆盖 320dp/fontScale 1.6 这一组观察点）。
6. 正式检测包的安装与下载链路未做端到端验证。
7. 当前提交的 optimized/release 门禁未执行；本报告仅覆盖 debug 变体。
8. 已有假服务尚未覆盖全部 Responses 终态用例，不能当作完整协议测试集使用。

---

## 6. 本轮未做（边界声明）

- 未修改发布签名配置，未运行任何发布脚本。
- 未自动提交或推送代码。
- 未调用任何真实供应商；R10 全部使用固定 HTML 样例与本地假服务。
- 未记录任何真实密钥或 Authorization 原文。
- 未混入无关重构或批量依赖升级。
- 未根据过期 PID 杀进程（停服务前均核对命令行与端口归属）。
- 所有新增文件均位于 `E:/AI/Zcode/` 内，临时文件位于 `E:/AI/Zcode/tmp/relay-tester-review/`。
