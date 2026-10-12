# Relay Tester 全面审查报告

审查日期：2026-10-08
审查对象：`E:/AI/Zcode/projects/relay-tester`（`com.relaytester.app.debug`，versionCode/name 见 `app/build.gradle.kts`）
审查范围：布局、样式风格、交互动画、测试规则、测试边界，以及所有可检查的功能与设计缺陷
审查方式：静态源码审查 + 离线单元测试复跑 + 真机（模拟器）走查 + 针对生产函数的定向取证测试
交付物性质：本报告为审查结论。**D1–D8 的修复与验证记录见文末「§10 修复记录」**，该节由后续的修复轮次追加。

---

## 0. 结论摘要

应用整体工程质量高：审查时 617 个单元测试全部通过（修复后 632 个），lint 0 error，`assembleDebug` 成功，核心网络层的终态语义（Responses `completed`/`failed`/`incomplete`/UNCONFIRMED）有明确且被测试钉住的设计。R10 动画测试在真机上端到端可用（真实 SSE → 解析 → WebView 预览渲染，内联 JS 与 SVG 均执行成功）。

但本轮审查发现了 **5 个运行时已证实的缺陷**，其中 2 个影响安全承诺、1 个影响 R10 主功能路径的可用性。**下表 8 项现已全部修复**，修复与验证记录见 §10：

| 编号 | 级别 | 缺陷 | 证据等级 | 状态 |
|---|---|---|---|---|
| D1 | **P1** | Responses 流式失败消息未做凭据脱敏，会把 API Key 回显到界面 | 运行时证实 | 已修复（§10.1） |
| D2 | **P1** | 指纹检测与动画测试完全无视「禁止测试（只拉模型）」开关，仍然发请求花钱 | 静态证实（路径完整可读） | 已修复（§10.2） |
| D3 | **P2** | 大字号下头部入口按钮标签被截断，按钮名不可读 | 设备复现 | 已修复（§10.3） |
| D4 | **P2** | 完整且合法的大文档（≥49152 字符）被误判为「被截断」而丢弃 | 运行时证实 | 已修复（§10.4） |
| D5 | **P2** | 多行 `data:` 的合法 SSE 帧被丢弃，导致回答文本缺失 | 运行时证实 | 已修复（§10.5） |
| D6 | P3 | CSP 注入位置可被 HTML 注释中的假 `<head` 吃掉 | 运行时证实 | 已修复（§10.6） |
| D7 | P3 | WebView 无显式销毁，沙箱释放函数是死代码 | 静态证实 | 已修复（§10.6） |
| D8 | P3 | 一处回调漏判 `disposed`，销毁后仍可能弹提示 | 静态证实 | 已修复（§10.6） |

修复后交付物：`app-optimized.apk`（versionCode 10704 / 1.7.4，沿用原发布签名，**未发布**），核验值见 §10.10。

---

## 1. 审查基线（本次实测，非历史数据）

全部在隔离环境中重跑，未使用发布签名、未触碰发布脚本、未提交。

**审查时基线（修复前）**：

| 项目 | 结果 | 命令/环境 |
|---|---|---|
| 单元测试 | **617 通过 / 0 失败 / 0 错误 / 0 跳过** | `:app:testDebugUnitTest --offline --rerun-tasks` |
| Lint | **0 error**，11 warning（4 NewerVersionAvailable、3 Aligned16KB、1 AndroidGradlePluginVersion、1 GradleDependency、1 ChromeOsAbiSupport、1 InsecureBaseConfiguration） | `:app:lintDebug` |
| 构建 | `assembleDebug` BUILD SUCCESSFUL | 同上 |
| 设备 | emulator-5560 `ZCodeRelayReview`（API 35，x86_64），1080x2280 / 440dpi / font_scale 1.0 | 隔离 AVD，`ANDROID_ADB_SERVER_PORT=5041` |
| 网络 | 全程仅使用本机回环假服务（`127.0.0.1` / `10.0.2.2`），**未调用任何真实供应商** | — |

**修复后复验基线（2026-10-08 修复轮次结束时）**：

| 项目 | 结果 | 说明 |
|---|---|---|
| 单元测试 | **632 通过 / 0 失败 / 0 错误 / 0 跳过** | 新增 15 条回归测试（净增），覆盖 D1–D8 每条修复 |
| Lint | **0 error**，11 warning | 与修复前逐条一致，未引入新告警 |
| 构建 | 完整链路 BUILD SUCCESSFUL（53 tasks） | `clean` + 单测 + `lint` + `assembleOptimized` |
| 优化版 APK | `app/build/outputs/apk/optimized/app-optimized.apk`，3,121,043 字节 | versionCode **10704** / versionName **1.7.4**，v2+v3 签名，生产证书未改动 |
| 设备 | 同上，字号 2.0 与大字号实测、动画面板开关实测 | 无崩溃（`logcat` 崩溃记录为空） |

设备显示设置在使用前已记录原值（1080x2280 / 440 / font_scale 1.0 / night=no / user_rotation=0 / accelerometer_rotation=1 / http_proxy=null），**测试结束后已逐项恢复并复验**。

---

## 2. P1 缺陷

### D1 — Responses 流式失败消息未脱敏，API Key 会回显到界面

**触发**：上游以 Responses 协议流式返回，且发出 `response.failed` 终态事件，其 `response.error.message` 中带回了请求所用的凭据（这是真实中转站常见行为：拒绝时会引用收到的 key）。

**位置**：`app/src/main/java/com/relaytester/app/core/network/RelayApi.kt:345-352`

```kotlin
ResponsesTerminal.FAILED -> return@withContext ApiResult.Failure(
    TestError(
        ErrorKind.UPSTREAM,
        "上游流式返回失败（response.failed）" +
            read.failureMessage.orEmpty().let { if (it.isBlank()) "" else "：$it" },
    ),
    httpStatus = response.code,
)
```

**根因**：这里的 `read.failureMessage` 直接拼接进用户可见的错误文本，**既没有 `.redactSecrets()` 也没有 `.take(MAX_ERROR_CHARS)`**。同一文件的缓冲式路径（`responsesFailureMessage`，`RelayApi.kt:497-506`）以及通用异常路径（`RelayApi.kt:855`、`866`、`871`）都做了这两步。也就是说，这是**唯一一条漏掉脱敏的失败出口**，属于实现不一致而非有意设计。

**运行时证据**（对生产函数 `RelayApi().completeTextStreaming` + 回环合成 SSE 取证，仅打印布尔量）：

```
EVIDENCE RESPONSES_SSE_FAILURE_RAW_SECRET_LEAK=true
```

**后果**：`read.failureMessage` 最终经 `FingerprintViewModel` 的 `result.error.message`（`FingerprintViewModel.kt:1991`、`2146`）进入动画面板错误文案与指纹轮次错误文案，即**用户界面会显示自己的 API Key**。由于该字符串随后可能被复制、截图、或进入导出内容，凭据泄露面不止于屏幕。

**最小改法**：把该分支改为与缓冲式路径同源，例如复用 `responsesFailureMessage` 的语义（`redactSecrets().take(MAX_ERROR_CHARS)`），保持四处出口一致。**注意先脱敏再截断**（`RelayApi.kt:855` 的注释已说明原因：先截断会留下密钥前缀，导致 `BARE_TOKEN` 模式匹配失败）。

**为什么测试没拦住**：`RelayApiResponsesTerminalTest` 钉住了 failed/incomplete/completed 的**状态**判定，但没有任何一条断言检查**消息内容是否脱敏**。这是本次最值得补的测试缺口。

---

### D2 — 「禁止测试（只拉模型）」在指纹检测与动画测试中被完全忽略

**触发**：用户在供应商上勾选「禁止测试：只拉模型，不发测试请求。」（`TesterScreen.kt:1599`、`1652`），随后进入指纹检测或动画测试并发起请求。

**证据**（静态，但路径完整可读）：`isTestingDisabled` 在整个 `feature/fingerprint/` 目录下出现次数为 **0**：

```
feature/fingerprint/AnimationPreview.kt:0
feature/fingerprint/AnimationTest.kt:0
feature/fingerprint/AnimationPanel.kt:0
feature/fingerprint/FingerprintScreen.kt:0
feature/fingerprint/FingerprintViewModel.kt:0
feature/fingerprint/ModelSelection.kt:0
```

对照：主测试链路上该开关是被认真执行的——批量路径在 `TesterViewModel.kt:3928` 直接把结果标为 `SKIPPED`（`ErrorKind.TESTING_DISABLED`，「该供应商已禁止测试，仅允许拉取模型」），统一目录路径在 `TesterViewModel.kt:1250` 于**发请求之前**就过滤掉该来源，UI 上也显示「仅拉取」（`TesterScreen.kt:4072`）。

**未被拦截的具体入口**：

- 指纹轮次：`FingerprintViewModel.kt:1175-1206` 的启动检查只包含 `runInFlight()`、`refuseWhileAnimationRuns()`、`refuseWhileBankJobRuns()`、supplier/model/challenge 非空、以及题库可用性；凭据解析在 `runRounds` 内 `FingerprintViewModel.kt:1239-1243`，**没有任何一处读取 `isTestingDisabled`**。
- 供应商选择：`FingerprintViewModel.kt:825`、`1514` 只按 id 取 `models`，不过滤该标记。
- 动画生成：`FingerprintViewModel.kt:1718` 的 `startAnimationGeneration()` 只检查提示词非空与 `roundBusy()`（`:1731`）。

**后果**：开关的文案是「不发测试请求」，但用户一旦进入指纹页或动画页，应用仍会拿该供应商的凭据发起**付费**请求（指纹是一轮三题、动画是 16384 max_tokens）。这既是**对用户承诺的违背**，也是**直接的费用风险**——用户设这个开关的动机通常正是"这个站很贵/只用来拉模型"。

**最小改法**：在指纹轮次启动检查与动画 `startAnimationGeneration()` 中各加一条前置判断（与 `TesterViewModel.kt:3928` 同语义），给出明确文案而非静默失败；同时在供应商下拉中把仅拉取来源置灰或标注，避免用户选完才发现不能跑。

**为什么测试没拦住**：R08 只覆盖了主测试链路的跳过语义，指纹/动画两处**没有任何针对该开关的测试**。

---

## 3. P2 缺陷

### D3 — 大字号下头部入口按钮标签被截断（设备复现）

**触发**：系统字体大小调至 1.5× 或 2.0×（设置 → 显示 → 字体大小），查看「模型测试」页的「测试参数」标题行。

**设备证据**（1080x2280 / 440dpi）：

| 字号 | 现象 |
|---|---|
| 1.0× | 「动画测试」「高级测试」标签完整（`shots/12-before-anim.png`） |
| 1.5× | 按钮换到标题下方一行（换行逻辑生效），但标签截断为 **「动画…」「高级测」**（`shots/26-crop-buttons.png`） |
| 2.0× | 截断更严重：**「动…」「高级」**（`shots/08-crop-headerrow2.png`） |

**位置**：`app/src/main/java/com/relaytester/app/feature/tester/TesterScreen.kt`

```kotlin
// AnimationTestEntry, 1676-1678
modifier = Modifier
    .heightIn(min = 28.dp, max = 30.dp)
    .widthIn(min = 64.dp, max = 92.dp),
...
// 标签固定、单行、省略号, 1697-1703
Text("动画测试", style = labelMedium, maxLines = 1, softWrap = false,
     overflow = TextOverflow.Ellipsis)
```

`AdvancedCatalogButton` 同形（`:1722-1723`，`max = 82.dp`）。

**根因**：`widthIn(max = ...)` 是**固定 dp**，而 `Text` 的字号随 `fontScale` 线性放大。字号变大时文本需求宽度上升、按钮上限不动，于是必然触发 `TextOverflow.Ellipsis`。值得注意的是，**换行判断本身是正确的**（`TesterScreen.kt:1794-1798` 已按 `density.fontScale` 缩放需求宽度，2.0× 时确实换到了第二行）——缺陷不在换行逻辑，而在**换行之后按钮仍然装不下标签**：`SETTINGS_HEADER_BUTTONS_WIDTH = 168.dp`（`:1745`）是「字号 1 时的自然宽度」，换行布局并没有为按钮解除宽度上限。

**后果**：在无障碍常用的大字号下，两个入口按钮显示为「动…」和「高级」——用户无法判断哪个是动画测试、哪个是高级测试。这是**可访问性缺陷**，且发生在刚刚被用户要求"做小一点"的那两个控件上（缩小与可读性在此处冲突，需要按字号放开上限或允许换行显示完整标签）。

**建议**：让上限随字号缩放（如 `(92.dp * buttonScale)`），或在 `fontScale > 1.2` 时允许标签两行/放宽 `widthIn`；同时为这两个入口补一条大字号下的布局测试。

**附带观察（P3，非缺陷）**：同一 2.0× 截图中，「选择模…」「题…」「等待发…」「需 292…」等**紧凑控件**也被省略号截断。这些是有意的紧凑设计（源码注释明确说明用省略号而非改标签），且都保留了图标与数值主信息，**不单独列为缺陷**，仅记录为"大字号下信息密度下降"的已知代价。

---

### D4 — 完整且合法的大文档被误判为「被截断」而丢弃

**触发**：模型返回一个**结构完整**（以 `</html>` 正常结束）且长度 ≥ 49152 字符的 HTML+SVG 文档。

**位置**：

`app/src/main/java/com/relaytester/app/feature/fingerprint/FingerprintViewModel.kt:1954`

```kotlin
truncated = result.value.length >= ANIMATION_MAX_TOKENS * TRUNCATION_CHARS_PER_TOKEN,
```

`app/src/main/java/com/relaytester/app/feature/fingerprint/AnimationTest.kt:268-270`

```kotlin
if (truncated) {
    return Result.failure(
        AnimationUnusable(AnimationFailure.TRUNCATED, "回答被长度上限截断，页面不完整"),
    )
}
```

常量：`ANIMATION_MAX_TOKENS = 16_384`（`AnimationTest.kt:195`）、`TRUNCATION_CHARS_PER_TOKEN = 3`（`:221`），故阈值为 **49152 字符**。

**根因**：把「字符数达到上限」当作「被截断」的**充分条件**。但该路径拿不到 `finish_reason`（源码注释已自认此局限），于是用长度做代理判断——而长度达到阈值既可能是被截断，也可能是**模型自然写完了**。

**运行时证据**（对真实生产函数取证）：

```
EVIDENCE TRUNCATION_THRESHOLD_CHARS=49152
EVIDENCE DOC_ENDS_WITH_HTML_TAG=true
EVIDENCE COMPLETE_DOC_AT_THRESHOLD_REJECTED=true
EVIDENCE COMPLETE_DOC_UNDER_THRESHOLD_ACCEPTED=true
```

**后果**：一个**已经完整**的大动画会被丢弃，并给出**错误的原因**（"被长度上限截断"）。SVG 动画文档冗长是常态（大量路径数据、关键帧、渐变定义），49152 字符并非遥不可及。用户看到的是"生成失败"，且提示指向一个并不存在的问题，重试很可能再次失败。**这是 R10 主功能路径上的功能性缺陷**，而非边缘情况。

**建议**：把"是否被截断"与"长度是否达阈值"解耦。`parseAnimationDocument` 已经有更可靠的判据——**文档是否以 `</html>` 结束**（`AnimationTest.kt:290` 一带的 `lower.endsWith("</html>")` 检查）。若文档结构完整，即使长度达阈值也应接受，只在真正缺少结尾标记时才报截断；长度上限应交由 `ANIMATION_MAX_HTML_CHARS = 2_000_000`（`AnimationTest.kt:201`）单独把关。

**为什么测试没拦住**：`AnimationTestRegressionTest` 有一条 `a truncated answer is rejected even when it looks like html`，但它**传入的 `truncated` 标志是构造的**，从未测试"完整文档恰好越过阈值"这一边界，因此该启发式的误判方向没有被覆盖。

---

### D5 — 多行 `data:` 的合法 SSE 帧被丢弃

**触发**：上游按 SSE 规范把一条事件的 `data:` 字段**拆成多行**传输（多行 `data:` 是 SSE 标准的一部分，接收端应以换行拼接）。

**位置**：`app/src/main/java/com/relaytester/app/core/network/RelayApi.kt:528-608` 的 `readEventStream`，逐行读取：

```kotlin
val line = source.readUtf8Line() ?: break
if (line.isBlank()) continue
if (!line.startsWith("data:")) continue      // :546 —— 续行被直接丢弃
val payload = line.removePrefix("data:").trim()
```

**运行时证据**（回环合成 SSE，对生产函数取证）：

```
EVIDENCE MULTILINE_SSE_FRAMING_BROKEN=true
```

即一条被拆成两行的 `response.output_text.delta` 事件，其文本未能被重组（预期 `4 5`，实际丢失）。

**根因**：解析器以"单行 = 单帧"为模型，缺少 SSE 规定的**事件缓冲**（读到空行为止，把同一事件内所有 `data:` 行用 `\n` 连接后再解析 JSON）。

**后果**：对采用多行分帧的中转站，回答文本会出现**静默缺失**——注意是"缺失"而非"报错"，因为代码明确选择跳过无法解析的帧（`RelayApi.kt:555-570` 一带的 `runCatching`，其设计意图是"一条坏心跳不该让用户损失整道题"）。这会把**协议兼容问题伪装成模型答错**：指纹检测可能因文本不全而给出错误的模型判定，动画测试可能因文档残缺而失败。诊断难度高，因为没有任何错误提示。

**严重性判断**：主流中转站普遍单行发帧，因此**实际触发频率未知**（见 §6 残余风险）。但一旦触发，后果是"静默的错误结论"，故列 P2。

**建议**：按 SSE 规范实现事件缓冲；并补一条多行 `data:` 的回归测试（现有 `RelayApiStreamingTest` 覆盖了注释、非 data 行、`[DONE]`、长度上限，**未覆盖多行帧**）。

---

## 4. P3 缺陷与观察

### D6 — CSP 注入可被 HTML 注释吃掉

**位置**：`app/src/main/java/com/relaytester/app/feature/fingerprint/AnimationPreview.kt:186`

```kotlin
val headIndex = lower.indexOf("<head")   // 会匹配到注释里的 "<head"
```

**运行时证据**：

```
EVIDENCE CSP_META_IN_HEAD_COMMENT=true
EVIDENCE CSP_META_IN_REAL_HEAD=false
```

对 `<!DOCTYPE html><!-- <head> decoy --><html><head>...` 这类文档，`indexOf("<head")` 命中**注释内**的位置，CSP `<meta>` 被插进注释，**真正的 `<head>` 里没有策略**，等于该文档完全没有 CSP。

**后果**：CSP 是纵深防御的一层，丢失后**原生沙箱仍然生效**（已实测，见 §5：`fetch` 被拒、图片被拦、origin 固定在 `https://animation.invalid/`），因此不构成沙箱失效。但"注入了一定生效"这一承诺不成立，且注释里出现 `<head>` 对模型生成的文档并不罕见（模型常写注释说明结构）。

**建议**：用简单的标签扫描跳过注释区间，或在找不到真实 `<head>` 时走已有的"整篇包裹"回退分支（`AnimationPreview.kt:189-192` 已实现该回退，只是此处判断失误导致没走）。

---

### D7 — 沙箱释放函数是死代码，WebView 无显式销毁

**位置**：`AnimationPreview.kt:368`（`pauseAnimation`）、`:374`（`resumeAnimation`）、`:385`（`releasePreview`）

全项目检索确认这三个函数**零调用点**：

```
app/src/\main\...\AnimationPreview.kt:368  fun WebView.pauseAnimation()
app/src/\main\...\AnimationPreview.kt:374  fun WebView.resumeAnimation()
app/src/\main\...\AnimationPreview.kt:385  fun WebView.releasePreview()
（无其他引用）
```

而组件的清理只做了标记（`AnimationPreview.kt:86-88`）：

```kotlin
DisposableEffect(replayToken) {
    onDispose { callbacks.disposed = true }
}
```

`disposed` 仅用于抑制迟到的回调，**没有任何地方调用 `destroy()`**。

**后果**：两个层面。（1）`AndroidView` 离开组合时视图被移出父容器，但**不会销毁**，WebView 及其渲染进程资源随每次重播/切页累积，属于资源泄漏。（2）"切后台暂停动画"实际是靠 `foreground` 标志**不组合** `AnimationPreview`（`AnimationPanel.kt:985-1002`）实现的，与 `pauseAnimation()` 文档描述的 `pauseTimers()` 路径**不是同一套机制**——代码里两套并存，其中一套从未启用。真机实测确认：切后台再回前台，文档仍在（未重新请求），用户可见行为正确；但 `releasePreview()` 承诺的"停加载、断开 client、销毁"从未发生。

**建议**：在 `onDispose` 中调用 `releasePreview()`；若确认 `foreground` 标志方案已足够，则删除三个未使用函数，避免"看起来已经处理了"的误导。

---

### D8 — 一处回调漏判 `disposed`

**位置**：`AnimationPreview.kt:249-257`

```kotlin
override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
    callbacks.onLoadError("页面试图跳转到外部地址，已被拦截")
    return true
}
```

相邻的 `onReceivedError` 有 `if (!request.isForMainFrame || callbacks.disposed) return`（`:240`），`onRenderProcessGone` 也有 `if (!callbacks.disposed)`（`:266`），**唯独这里没有**。

**后果**：页面在销毁后仍触发的导航尝试会把错误写回已失效的状态。影响轻微（一次多余的状态写入，且 `callbacks.disposed` 的设计意图明确是防这件事），但属实现不一致。

**建议**：补上 `disposed` 判断。

---

## 5. 已验证正常的部分（避免误报，明确记录）

以下项目经真机或运行时验证**工作正常**，列出以便区分"已检查且没问题"与"未检查"：

| 项目 | 验证方式与结果 |
|---|---|
| R10 动画测试端到端 | 真实 SSE（本机假服务）→ 流式解析 → 面板 → **WebView 渲染成功**：内联 JS 执行（标题显示 `AUDIT-ANIM-OK-JS`）、SVG 渲染、`animate` 元素生效 |
| WebView 沙箱实际拦截能力 | 用"敌意文档"实测：`fetch` → `REJECTED TypeError`；`Image` 加载 → `ERROR/BLOCKED`；`location.origin` = `https://animation.invalid/`（固定无凭据 origin）。`window.open` 探针只打印了 `CALLED`（**未抛异常不等于开出了窗口**），窗口确实开不出来是靠配置保证的：`setSupportMultipleWindows(false)` + `javaScriptCanOpenWindowsAutomatically = false` + `onCreateWindow` 返回 `false`（`AnimationPreview.kt:135-137,368-373`）。 |
| 文档结构校验 | 提交无 SVG 的文档时**正确拒绝**，并给出准确文案「文档里没有 SVG 图形；仍显示上一次成功的结果」，且保留上次成功结果 |
| 预览/源码切换 | 「预览」「源码」互斥切换正常；源码视图显示原始 HTML（等宽字体） |
| 动画面板空态 | 未选供应商时按钮禁用、文案「还没有测试记录。选好供应商和模型，点「开始生成」。」准确 |
| 供应商/模型选择弹窗 | 选择供应商 → 模型下拉解除禁用 → 选中后按钮可用，链路正确 |
| 深色模式 | `cmd uimode night yes` 下动画面板配色正确（含预览区、按钮、卡片） |
| 切后台保持 | 回前台后文档与面板状态保留，未重新请求（见 D7 的机制说明） |
| 横屏 2.0× | 横屏下主界面与指纹页布局正常，无截断（对比竖屏 2.0× 的问题） |
| 指纹页弹窗内筛选 | 输入 `eof` 后列表正确收敛为 `mock-eof` 单条，清除按钮出现；确认"筛选框在弹窗内、外部只有模型列表按钮"的修正已生效 |
| 指纹页布局（1.0×） | 「选择模型」按钮 + 「三题并行」开关，无外部筛选输入框，符合要求 |
| 主界面按钮尺寸（1.0×） | 「动画测试」「高级测试」标签完整，紧凑尺寸符合"做小一点"的要求 |
| 响应终态语义 | Responses 的 `completed`/`failed`/`incomplete`/UNCONFIRMED 判定被 `RelayApiResponsesTerminalTest` 钉住，且流式路径的 UNCONFIRMED 策略与缓冲式一致 |
| 错误脱敏（其余三处） | `responsesFailureMessage`、通用异常、非 JSON 正文三处均正确 `redactSecrets()` + `take(300)`（仅 D1 一处遗漏） |
| 流长度上限 | `MAX_STREAM_ANSWER_CHARS = 65_536` 对"无换行的超大单行"也有界（`BoundedSource` + `MAX_STREAM_BYTES`），并有测试覆盖 |

---

## 6. 测试规则与测试边界

### 6.1 测试资产盘点

- **31 个测试类 / 632 个用例**（修复后；审查时为 617）。测试源目录下有 33 个 `.kt`，其中 `AppUpdateTestSupport.kt` 与 `BankTestSupport.kt` 是支撑文件、不含用例，因此类数以 31 为准。全部为 JVM 单元测试。
- 网络层覆盖扎实：`RelayApiStreamingTest`（注释/keep-alive/非 data 行/DONE/无 DONE/长度上限/超大单行/坏帧跳过/流式回退到缓冲/空流）、`RelayApiResponsesTerminalTest`、`RelayCompletionTextTest`、`RelayStreamDeltaTest`、`NetworkErrorHandlingTest`、`OkHttpFetcherTest`。
- 状态与持久化：`FingerprintProgressAndHistoryTest`、`FingerprintHistoryStoreTest`、`TesterPersistenceRaceTest`、`SupplierOrderTest`、`UnifiedCatalogConcurrencyTest`。
- 契约类：`DialogWindowContractTest`（弹窗窗口契约）、`TesterActionRailTest`、`ReleaseRegressionTest`。

### 6.2 覆盖缺口（按风险排序）

下表的"关联缺陷"均已在修复轮次补上对应的回归测试（见 §10），因此这些缺口**当时存在、现已闭合**：

| 缺口 | 说明 | 关联缺陷 | 现状 |
|---|---|---|---|
| **没有任何 instrumentation 测试** | `app/src/` 下**不存在 `androidTest` 目录**。WebView 沙箱、Compose 布局、动画预览渲染**全部零自动化覆盖**。`AnimationTestRegressionTest` 的文件头注释已明确承认："The WebView itself is not exercised here"。R10 的渲染路径此前**仅靠人工验证**（本报告的 §5 即为该人工验证）。 | D3、D6、D7 | **未闭合**（见 §8 残余风险 4） |
| 失败消息脱敏无断言 | 终态测试只检查状态，不检查消息内容是否含凭据 | **D1** | 已补 2 条（含脱敏、含长度上限） |
| 多行 SSE 帧无测试 | 覆盖了注释/非 data 行，未覆盖 `data:` 续行拼接 | **D5** | 已补 3 条（跨行拼接、行间注释、缺尾空行） |
| 截断启发式边界无测试 | 现有截断用例的 `truncated` 标志是**构造传入**的，未测"完整文档越过阈值" | **D4** | 已补 2 条（超长完整文档、未闭合文档的提示归因） |
| 仅拉取开关无跨功能测试 | R08 只覆盖主测试链路 | **D2** | 已补 4 条（轮次拒绝且零请求、动画面板拒绝、下拉标注、正常供应商不受影响） |
| CSP 注入的畸形输入无测试 | 覆盖了"无 head"与策略内容，未覆盖"注释中出现 `<head`" | D6 | 已补 3 条（注释诱饵、脚本诱饵、`realHeadIndex` 直测） |
| 大字号布局无自动化测试 | `fontScale` 相关布局只在 `TesterActionRailTest` 等局部覆盖 | D3 | 未补自动化（仅设备实测，见 §10.3） |

### 6.3 测试边界（明确未覆盖、也建议保持不覆盖）

- **不调用真实供应商**：这是正确的边界（成本、稳定性、凭据）。所有网络测试使用回环假服务，应继续保持。
- **QuickJS 无运行时中断**：`BalanceQueryScript` 的 5 秒截止只能"放弃等待"，无法真正打断脚本执行（源码已诚实记录）。这是**已知限制**，不是缺陷。
- **16KB 页兼容（R09）**：`lint` 报 3 条 `Aligned16KB`，指向 `io.github.taoweiji.quickjs:quickjs-android:1.4.6` 的 `libquickjs-android.so`。本次审查的模拟器为 x86_64，**无法复现 arm64 上的 16KB 页问题**，该限制按原计划保留未修复。

---

## 7. 设计取舍说明（记录为"有意为之"，非缺陷）

以下项在源码中有明确注释说明动机，审查确认为**有意设计**，不计入缺陷，但列出以免后续被误当问题反复讨论：

1. **Chat/Anthropic 的 EOF 兼容差异**：`RelayApi.kt:330-335` 明确让这两种协议"到了就是答案"，而 Responses 严格要求终态事件。这是刻意保留的兼容行为，并有测试钉住。
2. **明文 HTTP 允许（`InsecureBaseConfiguration` warning）**：`network_security_config.xml` 的 `base-config cleartextTrafficPermitted="true"` 是为"仅能用明文 HTTP 自建中转站"的用户，且**必须由用户显式输入 `http://`**；应用自身发起的下载（更新 APK、检测包）被更严格的 `domain-config` 强制 https。注释中已说明这是深思后的取舍。
3. **操作栏 40dp 触摸目标**：`TesterScreen.kt` 明确记录了"有意低于 48dp 的取舍"，并在注释中说明理由。
4. **紧凑控件的省略号截断**：见 D3 附带观察，属有意设计。
5. **指纹/动画共用凭据的互斥**：`roundBusy()`（`FingerprintViewModel.kt:1837`）与 `animationBusy()`（`:1848`）互相拒绝，注释说明是为了共用限流与避免"重新出题"中途改题。设计正确。

---

## 8. 残余风险与本次审查的局限

必须明确声明，以免高估本报告的证据强度。**以下 1、2、4 三条在修复后依然成立**，修复轮次并未（也不应）假装消除它们：

1. **网络结论基于合成 SSE**：D1 与 D5 是对**生产函数**运行取证，但输入是回环合成帧。它们证明了**代码行为**，**不能证明真实中转站会发出这类帧**。D1 的触发需要上游把凭据回显到失败消息（常见但非必然）；D5 的触发需要上游多行分帧（频率未知）。两者的**真实世界发生率仍未测量**——修复轮次同样只用合成帧复验，因此 D5 的触发频率依旧是未知量，D5 的修复价值也依旧无法用"实际节省了多少次丢帧"来量化。
2. **设备覆盖单一**：仅在 API 35 / x86_64 / 1080x2280 / 440dpi 上走查。未覆盖 arm64 真机、低端设备、平板、以及 320dp 窄屏（`REPAIR_PLAN.md` 中 R04 的验收矩阵提到过 320dp，本次未复现）。D3 的大字号修复因此**只在一种密度下被实测**。
3. **WebView 引擎行为因版本而异**：沙箱实测结果（§5）对当前 AVD 的 WebView 版本有效，不能外推到所有 Android 版本。
4. **无 instrumentation 测试**：D3、D6、D7 的"用户可见行为"依赖人工走查，回归风险高于有自动化覆盖的部分。修复轮次为 D6 补了可直测的纯函数（`realHeadIndex`），但 **D3（布局）与 D7（WebView 生命周期）仍然只有人工证据**，下一次改动仍可能悄悄破坏它们。
5. **未做**：性能/内存剖析、长时间稳定性（动画持续运行）、无障碍 TalkBack 实际朗读验证、多语言/时区、低存储与低电量场景。
6. **R09（QuickJS 16KB）**：无法在当前设备架构上复现，状态仍为"限制保留，未修复"，与 `REPAIR_PLAN.md` 一致。
7. **D2 的修复范围有限**：只堵住了指纹检测与动画测试两条入口。若将来新增其他"会花钱"的入口（例如新的批量测试功能），必须同样接上该判断，否则会重新打开同一类缺口。

---

## 9. 修复建议优先级

原始建议顺序如下；**实际执行情况见 §10**（1–6 项全部完成，第 7 项部分完成）：

1. **D1**（P1，安全，改动最小：一处 `.redactSecrets().take(MAX_ERROR_CHARS)`）—— 且必须**先补一条会失败的回归测试**。
2. **D2**（P1，用户承诺与费用）—— 在指纹轮次与动画生成两处补前置判断，并同步处理供应商下拉的可见性。
3. **D4**（P2，R10 主路径可用性）—— 用"是否以 `</html>` 结束"替代长度代理判断。
4. **D5**（P2，协议兼容）—— 实现 SSE 事件缓冲。
5. **D3**（P2，可访问性）—— 按钮宽度上限随字号缩放。
6. **D6 / D7 / D8**（P3）—— CSP 注释处理、清理死代码并接线 `releasePreview()`、补 `disposed` 判断。
7. **补测试**：`androidTest` 至少覆盖动画预览与沙箱拦截；按 §6.2 补齐 6 条缺失的单元测试。

---

## 10. 修复记录（修复轮次，2026-10-08）

本节由后续的修复轮次追加。**原则**：每条缺陷先写一条会在旧代码上失败的回归测试，再改生产代码；改完后用**原审查轮次的取证测试**反向验证（取证测试应因修复而失败），最后复跑全量测试与 lint，确认没有引入新缺陷。

### 10.1 D1 — Responses 失败消息脱敏（已修复）

- **改动**：`core/network/RelayApi.kt` 的 `ResponsesTerminal.FAILED` 分支，把 `response.error.message` 先 `.redactSecrets()` 再 `.take(MAX_ERROR_CHARS)`，即"先脱敏后截断"。
- **为什么在入口处做**：这段文字是**上游写的**，中转站拒绝密钥时经常把密钥原文回引一遍；它进入应用的唯一入口就是这里，在此收口比在下游每个展示点重复过滤更可靠。
- **回归测试**（`RelayApiResponsesTerminalTest`，+2）：`a credential quoted back in a response_failed event is redacted`（合成 `sk-syntheticStreamingKey20261008`，断言消息中不含该串）、`an over-long failure reason is bounded like every other error message`（5000 字符墙体，断言 ≤400）。
- **反向验证**：原取证测试 `RESPONSES_SSE_FAILURE_RAW_SECRET_LEAK` 由 `true` 翻为 `false`。

### 10.2 D2 — 「仅拉取」开关在指纹/动画入口生效（已修复）

- **改动**：`FingerprintViewModel` 新增 `refuseIfPullOnly(supplier)`；`runRounds` 在 `isRunning = true` **之前**拒绝，`generateAnimation` 走 `failAnimation` 拒绝。`SupplierOption` 增加 `pullOnly`，`FingerprintScreen` 与 `AnimationPanel` 的供应商标签显示"`· 仅拉取`"。
- **比报告建议更严的地方**：判断依据是**当次读到的供应商**，不是缓存列表。缓存会同时造成两种错：用户刚在另一页关掉开关却被拒（假阳），或刚打开开关却漏判（假阴）。用新鲜值两个都避免。
- **回归测试**（`FingerprintViewModelTest` +3、`AnimationTestRegressionTest` +2）：轮次拒绝且断言 `api.calls.get() == 0`（**证明确实没发出请求**，而不只是没报错）、动画面板同样拒绝、下拉标注正确、正常供应商不受影响。
- **设备验证**：供应商 2 的芯片实测显示为"供应商 2 · 仅拉取"。

### 10.3 D3 — 大字号下头部按钮标签可读（已修复）

- **改动**：`TesterScreen.kt` 增加 `HEADER_BUTTON_MAX_WIDTH = 92.dp` 与 `Dp.scaledForFont()`（按 `LocalDensity.current.fontScale` 放大上限，且只放大不缩小）；`AnimationTestEntry` 与 `AdvancedCatalogButton` 改用 `.widthIn(min = 64.dp, max = HEADER_BUTTON_MAX_WIDTH.scaledForFont())`。
- **设备验证**：`font_scale` 置 2.0 后"动画测试"与"高级测试"两个标签**完整显示、无省略号**；测试后已把 `font_scale` 恢复为 1.0 并复验。
- **仍未自动化**：这是 Compose 布局，属 §6.2 中"无 `androidTest`"的缺口，只有人工证据。

### 10.4 D4 — 完整长文档不再被误判为截断（已修复）

- **改动**：`AnimationTest.kt` 把 `truncated` 的作用范围收窄到"仅当 `</html>` 确实缺失时用于挑选提示文案"。**文档是否可用，由是否以 `</html>` 结束决定，不再由调用方传入的长度启发式决定。**
- **顺带清理**：`AnimationDocument` 的 `truncated` 字段成为死字段（成功即完整），已删除；`TRUNCATION_CHARS_PER_TOKEN` 的注释改写为"仅为提示，从不作为拒绝依据"。
- **回归测试**（`AnimationTestRegressionTest` +2）：`a complete document is accepted however long it is`、`an unfinished document is still rejected and the hint names the cause`。
- **反向验证**：原取证测试 `COMPLETE_DOC_AT_THRESHOLD_REJECTED` 由 `true` 翻为 `false`。

### 10.5 D5 — 多行 `data:` 的 SSE 帧按规范拼接（已修复）

- **改动**：`RelayApi.kt` 的 `readEventStream` 重写为**按事件缓冲**：`data:` 行以 `\n` 拼接进缓冲，只有空行才派发；`event:`/`id:`/注释仍属于当前事件；新增 `dispatchEvent()`（返回 `true` 表示 `[DONE]`）。循环结束后**再补一次派发**，以兼容"不补尾空行就断连"的中转站。
- **为什么这是真缺陷**：SSE 规范允许一个事件跨多行 `data:`，应用此前**逐行解析**，会把一个 JSON 拆成两半、两边都解析失败，用户看到的是**回答文本凭空缺失**。
- **回归测试**（`RelayApiStreamingTest` +3）：跨 `data:` 行拼接（在 token 边界断开）、`data:` 行之间夹注释不结束事件、缺尾空行仍产出最后一个事件。
- **反向验证**：原取证测试 `MULTILINE_SSE_FRAMING_BROKEN` 由 `true` 翻为 `false`。

### 10.6 D6 / D7 / D8 — 三个 P3 问题（已修复）

- **D6（CSP 被注释吃掉）**：新增可直测的纯函数 `realHeadIndex(html)`（只算一次 `lowercase`，跳过 `<!-- -->` 与 `<script>`/`<style>` 正文，返回第一个真实 `<head` 下标），`injectCsp` 改用它。
  - 回归测试（+3）：注释中提及 `<head` 不夺走策略注入位置、脚本中提及 `<head` 不夺走、`realHeadIndex` 直测。
  - 反向验证：`CSP_META_IN_HEAD_COMMENT` 由 `true` 翻为 `false`，`CSP_META_IN_REAL_HEAD` 由 `false` 翻为 `true`。
- **D7（WebView 无显式销毁）**：`AnimationPreview.kt` 的 `AndroidView` 接上 `onRelease = { view -> callbacks.disposed = true; view.releasePreview() }`（`onRelease` 需 Compose 1.7+，本工程 BOM 2024.12.01 已满足）；删掉从未被调用的 `pauseAnimation()`/`resumeAnimation()` 死代码。
- **D8（回调漏判 disposed）**：`shouldOverrideUrlLoading` 的错误回调包上 `if (!callbacks.disposed)`，避免视图销毁后回调仍弹提示。
- **设备验证**：动画面板关闭后重开**无崩溃**，`logcat` 崩溃记录为空；"重新播放"不产生新请求（与设计一致）；敌意文档探针仍被拦截（`fetch` 被拒、`img` 被阻、origin 为 `https://animation.invalid/`）。

### 10.7 修复轮次的自检（"有没有新 bug"）

| 检查 | 结果 |
|---|---|
| 全量单元测试 | **632 通过 / 0 失败 / 0 错误 / 0 跳过**（修复前 617） |
| Lint | **0 error / 11 warning**，与修复前**逐条一致**（没有新增告警） |
| 完整构建链路 | `clean` + 单测 + lint + `assembleOptimized` → BUILD SUCCESSFUL（53 tasks，2m01s） |
| 设备回归 | 大字号布局、仅拉取标注、动画生成与重播、面板开关、沙箱拦截均实测通过；无崩溃 |
| 环境复原 | `font_scale`/`night`/`user_rotation`/`accelerometer_rotation`/`http_proxy` 全部恢复原值；假服务全部停止、端口释放 |
| 发布约束 | **未** 提交、**未** 推送、**未** 运行发布脚本、**未** 改动发布签名材料；全程未调用真实供应商 |

**过程中出现过的两次"失败"，都不是新缺陷**：

1. 首次全量测试有 2 条失败，**都是我新写的 CSP 测试断言算错了**（一条期望值取成了注释诱饵的下标，一条把脚本放在真实 `<head>` 之后导致断言本身不可能成立）。已核对下标后重写断言，**没有为了迁就测试去改正确的生产代码**。
2. 优化版 APK 首次打包失败于 `SigningConfig "release" is missing required property "storePassword"`——原因是我为了隔离而指定了空的 `GRADLE_USER_HOME`，读不到真实的 `~/.gradle/gradle.properties`。去掉该环境变量（回到默认 Gradle 家目录）后打包成功。**签名配置与密钥材料未被修改，也未打印任何密钥值。**

### 10.8 本轮改动清单

- 生产代码（7 个文件）：`RelayApi.kt`、`AnimationTest.kt`、`FingerprintViewModel.kt`、`AnimationPreview.kt`、`TesterScreen.kt`、`FingerprintScreen.kt`、`AnimationPanel.kt`
- 测试代码（4 个文件）：`RelayApiStreamingTest.kt`、`RelayApiResponsesTerminalTest.kt`、`AnimationTestRegressionTest.kt`、`FingerprintViewModelTest.kt`
- 构建配置（1 个文件）：`app/build.gradle.kts`（versionCode `10704` / versionName `1.7.4`）
- 取证测试**不在**项目内，仍只存在于 `E:/AI/Zcode/tmp/relay-audit-20261008/`，不污染仓库。

### 10.9 取证测试的反向验证（修复后实测值）

修复完成后，把**审查轮次用的同一批取证测试**（`E:/AI/Zcode/tmp/relay-audit-20261008/proofs/`）在修复后的代码上重跑。这些测试的断言方向是"缺陷存在"，因此修复成功时它们**必须失败**——这比"新测试通过"更强，因为它排除了"新测试只是迁就了实现"的可能。

`RelayAuditProofTest`（3 用例，全部 FAILED，断言均为 `diagnostic vulnerability changed`）：

| 标志位 | 修复前 | 修复后 |
|---|---|---|
| `RESPONSES_SSE_FAILURE_RAW_SECRET_LEAK` | `true` | **`false`** |
| `MULTILINE_SSE_FRAMING_BROKEN` | `true` | **`false`** |
| `CSP_META_IN_HEAD_COMMENT` | `true` | **`false`** |
| `CSP_META_IN_REAL_HEAD` | `false` | **`true`** |

`AnimationGateProofTest`（2 用例，全部通过，标志位翻转）：

| 标志位 | 修复前 | 修复后 |
|---|---|---|
| `COMPLETE_DOC_AT_THRESHOLD_REJECTED` | `true` | **`false`** |
| `COMPLETE_DOC_UNDER_THRESHOLD_ACCEPTED` | `true` | `true`（不变，未越界本来就应接受） |
| `TRUNCATION_THRESHOLD_CHARS` | 49152 | 49152（不变，仅作提示） |
| `DOC_ENDS_WITH_HTML_TAG` | `true` | `true`（不变） |

**注意 `CSP_META_IN_REAL_HEAD` 的翻转方向是反的**（`false` → `true`）：它衡量的是"策略是否落在真正的 `<head>` 里"，这正是我们想要的结果，所以修复后应当为 `true`。同一用例内两个标志位一个从真变假、一个从假变真，说明修复精确地只改变了该改变的行为。

### 10.10 交付物核验（对文件本身，不是对记忆）

| 项目 | 实测值 |
|---|---|
| 路径 | `E:/AI/Zcode/projects/relay-tester/app/build/outputs/apk/optimized/app-optimized.apk` |
| 大小 | 3,121,043 字节 |
| SHA-256 | `73cc69eac9d5b690830fba549b1c918b6ed58f63785696aaea34ae00e7a92bf2` |
| 包名 / 版本 | `com.relaytester.app`，versionCode `10704`，versionName `1.7.4`（`aapt2 dump badging`） |
| SDK | minSdk 26 / targetSdk 36 / compileSdk 36 |
| 签名方案 | v1 `false`、**v2 `true`**、**v3 `true`**、v3.1 `false`、v4 `false`（`apksigner verify --print-certs`） |
| 证书 | DN `CN=Relay Tester, OU=Release, O=Relay Tester, C=CN`；证书 SHA-256 `05c3e567…1d69de`；公钥 SHA-256 `2f28feb2…6630c0` |
| 发布状态 | **未发布**：未提交、未推送、未运行发布脚本、签名材料未改动 |

签名证书指纹与既有发布证书一致，说明**沿用原签名，没有重建或更换密钥**。

---

## 11. 第二轮修复（2026-10-09）：动画测试的实时显示与超时

这一轮不是从审查报告出发的，而是**用户在使用中提出的三个现象**：思考过程的显示不对、中间输出的显示不对、超时太短（"模型还在思考就给我超时了"）。要求照 Codex / ZCode 这类 agent 的显示约定来做，并指定用 `deepseek-ai/deepseek-v4.1-flash` 在模拟器上**自己截图确认**后再生成本版 APK。

### 11.1 先量再改：模型到底在干什么

上一轮修完的代码，对"思考模型"这条路径其实是**没被测过的**。先用真实供应商做了一组对照实验（`E:/AI/Zcode/tmp/probe*.log`）：

| 配置 | 结果 |
|---|---|
| 默认（思考开启），`max_tokens` 16384 | 300 s 内只有 reasoning、**正文 0 字符** |
| `reasoning_effort: "low"`，`max_tokens` 65536 | 烧掉 **38001 个 reasoning token**，正文仍为 0，上游在 300 s 处切断 |
| 思考关闭（三字段拼写），同一提示词 | **12510 字符 HTML，55.4 s**，HTTP 200 |

结论是确定的：**这个模型在这条提示词上，只要允许思考就永远产不出 HTML**。所以"让用户看到思考过程"不能靠"把思考开着"，只能靠"把思考关掉、并把关掉之后剩下的实时显示做对"。这也解释了用户看到的"超时"：180 s 的上限在思考阶段就被耗尽了，请求本身没有坏。

三个关闭思考的拼写是**实测**出来的，不是照文档推测的：`chat_template_kwargs:{thinking:false}`（vLLM 形）、`reasoning_effort:"none"`（OpenAI 兼容形）、`thinking:{type:"disabled"}`（Anthropic 形）三者**同时下发被接受**（无 HTTP 400）；而 `enable_thinking:false` 与 `reasoning:{enabled:false}` 被**严格拒绝**（HTTP 400）。因此只发前者、不发后者。

### 11.2 根因：两个回调的粒度不一样，调用方当成一样用

`RelayApi.readEventStream` 对**正文**调用 `onProgress(accumulated)`（累计值），对**思考**调用 `onReasoning(reasoning)`（**只是这一片**）。`FingerprintViewModel` 把两者都当累计值处理：

- 正文：`streamingText = partial` → 正确；
- 思考：`reasoningText = reasoning` → **每一片都覆盖上一片**，屏幕上永远只剩最后几个字。

这与用户描述的"思考过程的显示不正常"完全吻合，而且它是个**只在思考模型上才显形**的缺陷——之前测的都是不思考的模型，所以一直没暴露。修法是让调用方按各自契约处理（思考**累加**并单独计数），并把契约写进两个回调的 KDoc，各配一条回归测试。

### 11.3 显示层：按 Codex / ZCode 的约定重做

`AnimationPanel` 原来的"生成中"是一个近乎全高的空白占位块加一行提示，既看不到过程也看不出进展。重做后：

| 元素 | 行为 |
|---|---|
| 状态行 | `等待响应 · 12 秒` → `思考中 · 140 字 · 5 秒` → `输出中 · 7008 字符 · 24 秒`，两种计数分别对应思考与正文，**都是整段的真实长度**（不是可见尾巴的长度） |
| 思考块 | 带标签 `思考 · N 字`、可点击折叠、限高 150 dp；**正文一开始就自动折起** |
| 输出块 | 带 `输出` 标签、等宽字体、自动滚到尾部，只保留最后 4000 字符 |
| 结果头 | `结果 耗时 47 秒`，取 `settledAt - startedAt`；未结束时该字段为 null，不显示 |

计数与耗时都进 `AnimationSession` 成为**状态**（`receivedChars` / `reasoningChars` / `startedAt` / `settledAt`），不是从可见文本反推——可见文本是被截过尾的，用它算数会随滚动一直变小。

### 11.4 超时

`ANIMATION_TIMEOUT_SECONDS` 180 → **300**（客户端上限 305 s，留 5 s 宽限）。这是按上面 55.4 s 的实测值再留足余量定的，同时它也确实是"思考型模型不至于被中途掐死"的下限。

### 11.5 设备验证（用户明确要求"自己截图看"）

**真实供应商**（`freeapi.site` / `deepseek-ai/deepseek-v4.1-flash`）：状态行实测走过 `输出中 · 605 字符 · 7 秒` → `输出中 · 7008 字符 · 24 秒`，正文尾巴在等宽块里滚动，结果头 `耗时 47 秒`（另一次 48 秒）。

**思考路径**：真实供应商因为思考被关掉，**这条路径永远不会在设备上出现**，只有单元测试覆盖是不够的。因此另起一个本机假供应商（`tmp/reason-mock.py`，经 `tmp/reason-relay.py` 转发到设备），按真实 SSE 形状**分片**下发思考、再把正文分成 14 片下发。设备上实测到完整序列：

`思考中 · 15 字 · 1 秒` → `思考中 · 91 字 · 3 秒` → `思考中 · 140 字 · 5 秒` → `输出中 · 80 字符 · 7 秒` → `输出中 · 280 字符` → `输出中 · 480 字符` → `结果 耗时 16 秒`

思考字数与假服务下发的字节数**逐字对上**，说明累计逻辑在设备上成立而不只是在测试里成立；正文开始时思考块**确实自动折起**（`⌄ 思考 · 140 字`）。

**顺带排掉一个误报**：早先"预览一片白"看起来像 WebView 渲染失败，实测是**误报**。取同一批真实字节渲染，页面上是完整的鹈鹕骑车（天空渐变、云、草地、辐条、动画），像素采样与文档 CSS 的 `linear-gradient(180deg, #a8d8ea, #e0f0f8 40%, #c8e6d0)` 一致。之前的白屏是**截图早于文档绘制**，不是页面没渲染。

### 11.6 发布产物本身也实测过（不是只测 debug）

调试版和发布版走的是**不同的字节码管线**：`optimized` 继承 `release`，开着 `isMinifyEnabled` 与 `isShrinkResources`（R8 混淆 + 资源裁剪），而调试版没有。因此"调试版验证通过"不能推出"发布版没问题"，发布产物必须自己过一遍。

`app-optimized.apk`（versionCode `10706` / versionName `1.7.6`）装到模拟器上实测：

| 检查 | 结果 |
|---|---|
| 启动 | 正常启动，无 `FATAL EXCEPTION`、无 tombstone、无 ANR |
| 动画面板 | 正常打开，空状态文案正确（"还没有可用的供应商…"） |
| 供应商编辑 | 明文 HTTP 的警告文案正常出现；保存后 API Key 以掩码显示 |
| 批量拉取模型 | 经中继拉到 `reason-mock`，模型数由 0 变 1 |
| 生成过程 | `思考中 · 91 字 · 4 秒` → `思考中 · 135 字 · 6 秒` → 自动折起 `⌄ 思考 · 140 字` → `输出中 · 160 字符 · 8 秒`（等宽 `输出` 块） |
| 结果 | `结果 耗时 16 秒`，预览正常渲染假供应商下发的动画页 |

**结论：R8 混淆与资源裁剪没有改变动画测试的任何可观察行为。**

> 过程中有一段"应用反复被切到别的 App"的现象，查下来是**模拟器上另一个应用（`com.localfit.ai`）被 uid 0 反复拉到前台**，与本应用无关：本应用没有崩溃记录，最终仍然正常完成生成。这一点单独记下来，避免被误读成"发布版不稳定"。

### 11.7 本轮自检

| 检查 | 结果 |
|---|---|
| 全量单元测试 | **647 通过 / 0 失败 / 0 错误**（上一轮 632；本轮新增 15 条） |
| Lint | **0 error / 11 warning**，与上一轮**逐条一致**；本轮引入的 1 条 `AutoboxingStateCreation` 提示已修掉（`mutableLongStateOf`） |
| 发布产物 | `optimized`（R8 混淆 + 资源裁剪）单独装机实测，行为与调试版一致（见 §11.6） |
| 新增回归测试 | 思考累计成一条链、计数不被尾部截断影响、头部按整段计数、请求确实带关闭思考的三个字段、默认请求**不带**、思考以分片上报且不混入正文、耗时起止与冻结、生成中措辞 |
| 设备回归 | 真实供应商 + 假供应商两条路径均实测；预览渲染确认；无崩溃 |
| 环境复原 | 设备 IPv6 `disable_ipv6` 与 `http_proxy` 恢复原值；假服务全部停止 |
| 发布约束 | **未**提交、**未**推送、**未**运行发布脚本、签名材料未改动；真实密钥只在子进程环境变量里传递，**未写入任何文件** |

**过程中出现过的、不是新缺陷的问题**：

1. 假服务器的 GET 响应在 HTTP/1.1 下**没有 `Content-Length`**，转发方会一直等一个不会到来的结束，表现为"设备侧永远等待响应"。这是假服务器的缺陷，不是应用的；补上长度与 `Connection: close` 后正常。
2. 假服务器最初把整篇正文**一次性**下发，于是"正文开始后思考块自动折起"根本没有可观测窗口，看上去像没实现。改成 14 片分片后观察到折起确实发生。**测试装置的问题一度看起来像产品缺陷，这条记下来以免下次再误判。**
3. 转发脚本最初用 `iter_content(chunk_size=None)`，对无长度上限的 SSE 响应要读到 EOF 才转发，等于取消了"实时"。改为有界 chunk 后实时性恢复。

### 11.8 本轮改动清单

- 生产代码（4 个文件）：`core/network/RelayApi.kt`（关闭思考的三字段 + 回调契约 KDoc）、`feature/fingerprint/AnimationTest.kt`（超时 300 s、会话计数与时间戳）、`feature/fingerprint/FingerprintViewModel.kt`（思考累加、计数、时间戳、关闭思考）、`feature/fingerprint/AnimationPanel.kt`（显示层重做）
- 测试代码（2 个文件）：`AnimationTestRegressionTest.kt`、`RelayApiStreamingTest.kt`
- 构建配置：`app/build.gradle.kts`（versionCode `10706` / versionName `1.7.6`）
- 验证装置（**不在**项目内，仅存于 `E:/AI/Zcode/tmp/`）：`reason-mock.py`、`reason-relay.py`、`capture-proxy.py`、`probe*.log`、`anim-*.png` 截图

**第二轮修复（追加，2026-10-09）**，全部位于 `E:/AI/Zcode/tmp/`（未写入项目源码目录）：

- `reason-mock.py` — 本机假 Chat Completions 供应商：按真实 SSE 形状**分片**下发思考（140 字）与正文（561 字符，14 片），带 `Connection: close` 与 `Content-Length`
- `reason-relay.py` — 转发到设备的记录式中继（由上一轮的 `capture-proxy.py` 改写，`iter_content(chunk_size=256)` 保证实时）
- `reason-mock.log` / `reason-relay.log` — 假供应商与中继日志：证明应用发来的请求**确实带**关闭思考的三个字段
- `anim-x4/6/8/10/12.png`、`anim-x-a.png`、`anim-x-b.png` — 思考路径的设备截图序列：`思考中 · 15/91/140 字` → `输出中 · 80/280/480 字符` → `结果 耗时 16 秒`
- `anim-capnow.png`、`anim-capnow-crop.png` — 真实供应商产出的文档在预览中的渲染（鹈鹕骑车）
- `ui-now.xml`、`anim-dlg.png`、`anim-dlg-crop.png` — 设备界面转储与截图

> 关于第二轮的一条诚实说明：**真实供应商不会走思考路径**（请求里思考是关掉的，实测开着就永远产不出正文），所以 §11.5 里思考链的"逐字对上"是在**假供应商**上得到的，不是真实供应商。假供应商的 SSE 形状按真实响应抄写，但它是装置而非上游本体，这一点不应被读成"真实模型也这样"。

---

## 12. 第三轮修复（2026-10-09）：画板比例与"不限制思考"

用户在使用中提出两点：**预览画板要按电脑的比例，否则图看不全**；**动画测试不要关闭思考、不做任何限制、边界值设高一些**。第二点推翻了上一轮的一个产品决定，所以这一轮的做法是**先把代价量清楚，再改数字**，而不是把那三个字段删掉了事。

### 12.1 画板：16:9 与桌面视口，两处缺一不可

"看不全"的原因是两个设置叠加：提示词要求文档**面向桌面预览区**排版，而预览框原来是一个竖高的手机比例盒子，并且 WebView 的布局视口就是设备宽度。于是一个按宽 viewBox 画的场景，右半边被排到屏幕外。

- `AnimationPanel.kt`：预览与源码两个面板都改为 `aspectRatio(16f / 9f)`（同一比例，切换不跳）。
- `AnimationPreview.kt`：`useWideViewPort = true`（按 980 px 的桌面视口排版）＋ `loadWithOverviewMode = true`（整页缩放到可见）。

**只改一头都不成立**：只改盒子比例，视口仍是设备宽度，页面照样按手机宽度排版后裁掉右侧；只改视口，竖高的盒子会把缩放后的整页再裁一刀。

**A/B 实测**（同一份 1120×630 文档，同一台设备 `127.0.0.1:5561`，仅 APK 不同）：

| 版本 | WebView 实测尺寸 | 比例 | 画面 |
|---|---|---|---|
| 修复前（`app-debug.apk`，1.7.5-debug，改动前的构建） | 895 × 1241 px | 0.72（竖高） | **只有 LEFT-TOP**，整块画布被裁到看不见主体 |
| 修复后（`app-optimized.apk`，1.7.7） | 895 × 503 px | **1.779 ≈ 16:9** | 四角标记 + 中央标题**全部可见**，边框完整 |

截图：`v177-BEFORE-crop.png`（修复前，只有左上角）与 `v177-FINAL-crop.png`（修复后，整块画布）。**这条 A/B 用的是本机假供应商造的一份四角带标记的 1120×630 文档**，不是真实供应商产出——因为开着思考时真实供应商 0 字正文（§12.7），拿不到可判读的真实文档；上一轮真实产出的鹈鹕那张只有 180×180，尺寸上判不出裁切。这是本轮的验证局限，记在此处。

### 12.2 一个被实测否掉的写法

我最初写的是 `.aspectRatio(16f/9f).heightIn(min = 220.dp, max = 720.dp)`。装机实测预览框只有 **168 dp 高**，低于我写的 220 dp 下限——`heightIn` 在 `aspectRatio` 之内不起作用，比例算出的高度已经把下限"夹"掉了。**一个不生效的下限比没有下限更糟**：下一个人会以为它在保护布局。于是删掉 `heightIn`，重新构建、重新实测：895 × 503 px（比例 1.779），与带 `heightIn` 时完全一致，648 条测试仍然全绿。

### 12.3 不限制思考：先量代价

上一轮之所以发送关闭思考的三个字段，是因为观察到"开着思考产不出正文"。用户不接受这个做法。那就必须把**开着思考的真实代价**量出来。真实供应商、真实提示词、请求体**不带任何推理控制字段**、`max_tokens = 65536`：

| 模型 | 实测（300 s 窗口内） |
|---|---|
| `deepseek-ai/deepseek-v4.1-flash` | **思考 33 079 字，正文 0 字**，上游在 300.3 s 主动断开（4 860 帧） |
| `MiniMax-M2.7-highspeed` | 思考 44 145 字，**正文 0 字** |
| `claude-sonnet-4-6` | 236 s 才吐出第一个正文，240 s 时正文 1 098 字 |
| `doubao-seed-2.0-code` | 1 s 内结束、0 字（该模型此请求下不可用） |

两个给边界值兜底的速率：

| 量 | 实测 |
|---|---|
| 思考阶段的线速 | 60 s 收 319 444 字节 / 7 802 字 → **约 5.3 KB/s** |
| 正文阶段的线速 | 10 231 字文档占 163 669 字节 → **约 16 线字节/字** |

**这就是新边界值的依据**：600 s 思考 ≈ 3.2 MB，一份满预算文档 ≈ 4.2 MB，合计 ≈ 7.4 MB——旧的 8 MB 只剩 8% 余量，等于"模型多想一会儿"就会误报。所以流式上限提到 32 MB。

### 12.4 新边界值（逐条对着实测）

| 常量 | 旧 | 新 | 依据 |
|---|---|---|---|
| `ANIMATION_MAX_TOKENS` | 16 384 | **65 536** | 预算要同时装下思考与正文；65 536 是供应商实测接受的最大值 |
| `ANIMATION_TIMEOUT_SECONDS` | 300 | **600** | 开着思考时没有更短的可依据下界；但**这只放宽本应用**，上游自己 300 s 断流的，本值救不了 |
| `MAX_STREAM_BYTES` | 8 MB | **32 MB** | 600 s 思考 + 满预算文档 ≈ 7.4 MB |
| `MAX_RESPONSE_BYTES` | 1 MB | **4 MB** | 非流式回退路径要在**一个 JSON 体**里装下整份文档，1 MB 会把"中转站不支持流式"变成"响应体过大" |
| `MAX_STREAM_ANSWER_CHARS` | 64 K | **256 K** | 必须 ≥ 满预算答案（65 536 token × 3 字/token ≈ 196 K），否则应用**先自己截断、再以"文档没有结尾"拒掉**——把自己的问题怪给模型 |

### 12.5 必须一起解决的代价：进度回调的二次开销

把答案上限提到 256 K 不能只改数字。进度回调每次传的是**整段累计文本**，而调用方只要字符数，所以"每片都报一次"在长答案上是 O(n²)：按 60 字一片模拟，262 144 字的答案要复制**约 5.7 亿字符**。

新增 `streamProgressDue(length, lastReported)`：低于 16 384 字**仍每片都报**（覆盖挑战题那几 KB，面板计数与原来完全一致），超过之后改为"又涨了 1/64 才报一次"。模拟实测：

- 报告次数 4 370 → **443**
- 总复制量 5.7 亿 → **1 765 万字符**（约为最终长度的 64 倍，线性）
- 计数滞后 **≤ 1.6%**（末尾实测 244 字）

回归测试钉的是"小答案每片都报"与"大答案总复制量线性"，不是某个具体次数。

### 12.6 删掉的东西

`disableThinking` 参数与其三字段**整个删除**（不是留着不用）：一个"可以关闭思考"的开关留在代码里，下一个人会以为它需要、甚至以为它在生效。一并删除的还有流式被拒时"重试不带该字段"的注释（重试现在与首次请求体相同）、以及测试里的 `thinkingHints` 记录列表。Chat 分支现在**不带任何推理控制字段**，并在注释里写明这是有意为之。

### 12.7 本轮自检

| 检查 | 结果 |
|---|---|
| 全量单元测试 | **648 通过 / 0 失败 / 0 错误 / 0 跳过**（31 个测试类） |
| Lint | `lintDebug` **0 error / 11 warning**，`lintOptimized` **0 error / 6 warning**；两者都**只含既有告警**（`Aligned16KB`、`InsecureBaseConfiguration`、`ChromeOsAbiSupport`、依赖版本类），本轮无新增。两个数字不同是因为 debug 变体多报 5 条版本类提示（4 条 `NewerVersionAvailable` + 1 条 `AndroidGradlePluginVersion`），并非本轮的改动 |
| 设备验证（发布产物 `optimized`） | 1.7.7 装机实测：画板 A/B（§12.1）、请求体实测 `reasoning-fields= []`、`max_tokens= 65536`（假供应商日志逐字记录） |
| 新增/改写测试 | `the animation request leaves the model's reasoning alone`（断言请求**不含**任何推理控制字段，含上一轮实测被拒的两种拼写）、`the animation bounds are wide enough to let a thinking model finish`、`the progress cadence stays live for small answers and steps up for large ones` |
| 因上限提高而改写 | 3 条钉旧数字的流式测试：**改的是测试装置规模、不是断言方向**——截断用例特意写到 400 KB 以真正越过新上限，否则旧规模下会"通过但什么都没证明" |
| 环境复原 | 设备 `http_proxy` 恢复为测试前的 `10.0.2.2:7897`（`disable_ipv6` 保持测试前即为 `1`）；假供应商与中继全部停止；测试期间新建的两条供应商记录（`canvas-mock`、`final-check`）与两条误建的空白记录已全部删除，**只留用户自己的 `freeapi`** |
| 附带发生的事（如实记录） | 批量拉取模型时 `freeapi` 的模型目录被刷新，**18 → 24 个**（按钮的本来功能，仍是其自身端点返回的列表；供应商配置与密钥未受影响） |
| 发布约束 | **未**提交、**未**推送、**未**运行发布脚本、签名材料未改动；真实密钥只经子进程环境变量传递，**未写入任何文件** |

### 12.8 诚实的结论：不开限制时，这个模型仍然产不出正文

用户要的是"让模型自由发挥"，这一轮完整落地了：请求里不再有任何推理控制字段，预算与上限都提到实测依据支持的最大值，并且**应用自己不再先动手截断**。但必须说清楚——在 `deepseek-ai/deepseek-v4.1-flash` 上，实测是 300 秒烧掉 33 079 字思考、正文 0 字，被**上游自己的 300 秒上限**断开。**这是模型/上游的行为，不是应用边界值造成的**：调高本应用任何常量都不会改变它（600 s 的超时同样救不了它，断开发生在对端）。

应用能做的是**把过程如实显示出来**（上一轮已做：思考链累加、计数、秒表），让用户看到"它在思考"而不是"界面卡死了"。这一点现在尤其有意义：在这条路径上，长思考是常态而非异常。

若要在动画测试里稳定拿到正文，可用的方向是换模型（实测 `claude-sonnet-4-6` 会开始输出，但 236 s 才出第一个字）或在提示词层面引导。两者都不属于本轮范围，因此只记录事实，不擅自改变。

### 12.9 本轮改动清单

- 生产代码（4 个文件）：`core/network/RelayApi.kt`（删除思考控制字段与参数、`streamProgressDue`、四个上限）、`feature/fingerprint/AnimationTest.kt`（token 与超时上限）、`feature/fingerprint/FingerprintViewModel.kt`（不再传 `disableThinking`）、`feature/fingerprint/AnimationPreview.kt`（桌面视口）、`feature/fingerprint/AnimationPanel.kt`（16:9 面板，比例唯一约束）
- 测试代码（4 个文件）：`RelayApiStreamingTest.kt`、`AnimationTestRegressionTest.kt`，以及 `FingerprintViewModelTest.kt` / `FingerprintProgressAndHistoryTest.kt`（仅去掉失去意义的参数）
- 构建配置：`app/build.gradle.kts`（versionCode `10707` / versionName `1.7.7`，1.7.7 变更注释）
- 验证装置（**不在**项目内，仅存于 `E:/AI/Zcode/tmp/`）：`canvas-mock.py`（桌面画布假供应商）、`responses-shim.py`（Responses 协议的转发壳，供修复前的 debug 记录使用）、`probe-*.py` 与其日志、`v177-*.png` / `v177-*.xml`
- 交付产物：`app/build/outputs/apk/optimized/app-optimized.apk`（3 121 043 字节，md5 `65222b6ea06c86e53cb1d02c587ae538`），**已装机验证、未发布**

---

## 13. 第四轮修复（2026-10-09）：思考框的跟随与掉线自动重试

用户在使用中报三点：**思考过程中思考框的最后一行只显示一半**；**思考时界面应当跟随最新一行，只有手动上滑才停在旧行**；**思考一半显示「网络连接失败」就结束了，没有自动重试**。

### 13.1 思考框：状态被重建，跟随从未真正生效

两个现象是同一处代码的结果。原实现把滚动状态建在块**内部**：

```kotlin
Text(..., modifier = Modifier.verticalScroll(rememberScrollState()))
```

`rememberScrollState()` 写在组合内部，每次增量重组都会拿到新实例、停在 offset 0。表现为：**画面始终停在旧行**（用户报的第 2 点），而新到的文字持续向下顶，**最后一行被挤到框底边缘、只画出一半**（用户报的第 1 点）。

修复：状态提到外面并用 `LaunchedEffect` 跟随末尾，同时 `clipToBounds()` 保证框底不会画出半个字。

### 13.2 跟随要让位给读者，而这件事有个陷阱

"跟随"不能是无条件的 `scrollTo(maxValue)`：那会**把正在回看的读者持续拽回底部**，等于用户报的第 2 点反着犯一遍。判定规则抽成了纯函数 `followEndObservation(offset, extent, previousExtent, slack)`，因为这条规则**就是**用户要的行为，值得脱离 Compose 直接测。

陷阱在于：**新文本到达同样会改变 offset 与 maxValue**。若把"位置变了"当成"读者动手了"，跟随会在第一段新文本落下时立刻自我关闭——正是"停在旧行"那个 bug 的复发形状。所以规则只在 **extent 前后一致**（内容没有增长）时才把位置变化当作读者的手势：

- extent 变了 → `null`，不表态（增长不是手势）；
- extent == 0 → `true`（内容还没超框，本就在末尾）；
- 其余 → `offset >= extent - 8px`（8 px 亚像素余量）。

肉眼确认过的一个重要细节：**"滚到底部"在流式场景里不可靠**——读取时文本仍在增长，一次快照常常在底部与"差一行"之间抖动。这也是为什么跟随滚动要 `withFrameNanos { }` 等一帧：布局前 `maxValue` 描述的仍是**上一版**文字，直接滚过去会把最新一行留在框底下一格——正好就是那半个字。

### 13.3 自动重试：策略复用测试侧的定义，触发条件故意很窄

重试**不放在网络层**，因为"能不能重发"取决于这次生成走到哪一步。放在 `FingerprintViewModel` 的尝试循环里，最多 2 次重试，线性等待 2 s / 4 s。

**触发条件是"瞬态失败"且"一个可用正文字符都没收到"**两条同时成立：

- 只按瞬态性重试不够：一份**已经收到正文**再断流的请求，产出已经产生并计费，重发等于把这份结果丢掉重买；
- 只按"没收到正文"重试也不够：认证失败、额度不足、模型不存在这类失败重发只会再被拒一次。

"瞬态"没有另写一份定义，而是把模型测试路径里私有的可重试集合提升为 `ErrorKind.isTransient()`（NETWORK / TIMEOUT / RATE_LIMITED / UPSTREAM），**两条路径共用**。两份列表迟早会漂移，而漂移的后果是同一个上游错误在一块屏幕上可重试、在另一块上致命，且在有人比对之前完全不可见。

**重试必须看得见**：重试期间界面显示 `网络连接失败；正在重试（第 2 次，共 3 次）`，状态行同时标出 `· 第 2 次尝试`。否则计数器从 8000 掉回 0 会被当成"计数坏了"，而不是"它在重试"——这是一条会被报成 bug 的体验。

**取消要能真的停下**：等待重试期间用户取消，不得再补发请求（测试用一道 `CompletableDeferred` 闸门把等待停住，取消后才放行，断言调用次数没变）。

**测试用时的注入**：重试的等待通过构造参数 `animationRetryDelay` 注入，默认 `{ delay(it) }`。不注入的话，`delay()` 会挂在 `UnconfinedTestDispatcher` 的虚拟时钟上而无人推进，`runBlocking { awaitIdle() }` 永不返回——这一轮实际踩到过，表现为"测试套件卡住"，不是断言失败。

### 13.4 设备验证（发布产物 1.7.8，逐条对着截图）

验证装置：本机假供应商 `E:/AI/Zcode/tmp/retry-mock.py`（端口 18746）。它**第一次请求只发思考、然后在零正文字符处用 `SO_LINGER 0` 发 RST**，第二次请求发完整的长思考链与文档。

**为什么必须用 RST 而不是干净关闭**（这条是设计的关键，不是细节）：客户端对 Chat 保留"EOF 兼容"，流干净结束会被当作回答已送达，空回答归类为 `INVALID_RESPONSE`——而它**故意不可重试**。只有传输层失败才成为 `NETWORK`（即用户看到的"网络连接失败"），才是可重试的分支。干净关闭会测错分支并什么都证明不了。

假供应商日志（逐字）：

```
POST attempt=1 path=/v1/chat/completions model=retry-mock stream=True max_tokens=65536
RESET after reasoning, answer chars sent = 0
POST attempt=2 path=/v1/chat/completions model=retry-mock stream=True max_tokens=65536
reasoning done, streaming document
SENT reasoning=1860 answer=1030
```

| 要验的行为 | 证据 | 结果 |
|---|---|---|
| 掉线后自动重发 | 上面的日志：attempt 1 归零后 attempt 2 发出 | **通过** |
| 重试可见 | `v178-r4.png`：`网络连接失败；正在重试（第 2 次，共 3 次）` + `思考中 · 36 字 · 3 秒 · 第 2 次尝试`，仍是生成中、没有红字失败 | **通过** |
| 重试能救回来 | `v178-r13.png`：`耗时 14 秒`，预览四角标记与 `RETRY-OK` 齐全，`重新生成` 可用 | **通过** |
| 最新一行不再被切 | `v178-b4.png` / `v178-box-A.png`：思考框显示到 `第 45 步`（A 帧为 `第 07 步`），末行完整、框底在其下方 | **通过** |
| 默认跟随最新 | 同上：边流边显示到当前最新行 | **通过** |
| 手动上滑后停住 | 设备端单脚本连拍 `A→滑动→B→等 4 秒→C`；`v178-box-B.png` 回到 `第 01 步`，4 秒后 `v178-box-C.png` **仍停在 `第 01–09 步`**（期间思考文字继续增长） | **通过** |
| 无崩溃 | `logcat` 未见 `FATAL` / `AndroidRuntime` | **通过** |

装置与配置：假供应商把思考链拉长到 90 片 × 0.35 s（约 31 s），否则"上滑后是否停住"没有观察窗口；设备 `http_proxy` 临时改为 `:0` 以放通本机回环，测后**已恢复为 `10.0.2.2:7897`**。

### 13.5 本轮自检

| 检查 | 结果 |
|---|---|
| 全量单元测试 | **661 通过 / 0 失败 / 0 错误 / 0 跳过**（31 个测试类；`AnimationTestRegressionTest` 89 条） |
| Lint | `lintDebug` **0 error / 11 warning**，`lintOptimized` **0 error / 6 warning**（删掉旧报告后**重新生成**核对，非复用 up-to-date 产物）；两个数字之差仍是 debug 变体多报的 5 条版本类提示；**本轮改动的 6 个文件里 0 条告警** |
| 新增测试（13 条） | 重试 8 条：自动重发、不可重试的失败不重发、已收到正文不重发、重试预算用尽后如实报错、重试有提示、提示含次数、等待期间取消不补发、两条路径共用同一份瞬态定义；跟随 5 条：停在末尾视为跟随、上滑解除、回到底部恢复、**内容增长不算手势**、未测量时视为在末尾 |
| 构建核验 | `aapt2 dump badging`：`versionCode='10708' versionName='1.7.8'`（R8 会剥离 dex 里的版本字符串，所以以 badging 为准）；`apksigner verify --print-certs` 证书 SHA-256 `05c3e567…1d69de`，**与既有发布证书一致** |
| 环境复原 | 设备 `http_proxy` 恢复 `10.0.2.2:7897`；假供应商 18746 已停止；测试用供应商 `retry-mock` **已删除**，设备上只留用户的 `freeapi`；本轮设备截图已从 `/sdcard` 清掉（该目录下仍有 10-06～10-08 的历史文件，非本轮产生，未动） |
| 发布约束 | **未**提交、**未**推送、**未**运行发布脚本、签名材料未改动；全程未使用真实供应商密钥 |

### 13.6 一个要如实说明的验证边界

第 4 轮的三处修复都**在设备上用发布产物（`optimized`，1.7.8）实测过**，这是本轮相对前几轮的加强。但假供应商的思考链是**合成文本**（固定句式重复 90 次），不是真实模型产出：它能证明"跟随/停住/裁剪"这些几何与滚动行为，**不能**证明真实长思考下的表现。真实供应商在动画测试里仍然产不出正文（§12.8 的原因未变），因此拿不到真实的长思考链来复现——这条局限一并记在此处，不用合成结果冒充真实结果。

### 13.7 本轮改动清单

- 生产代码（4 个文件）：`feature/fingerprint/AnimationPanel.kt`（思考框滚动状态外提 + 跟随让位、`followEndObservation`、`rememberFollowEndScrollState`、重试提示行、状态行尝试次数）、`feature/fingerprint/FingerprintViewModel.kt`（尝试循环、`AnimationAttempt`、`startAnimationAttempt`、注入 `animationRetryDelay`）、`feature/fingerprint/AnimationTest.kt`（`attempt` / `retryNotice` 字段、重试常量）、`core/model/Domain.kt`（`ErrorKind.isTransient()`）
- 生产代码（1 处改为复用）：`feature/tester/BatchTestRunner.kt`（改为调用 `isTransient()`，不再自带一份可重试集合）
- 测试代码（1 个文件）：`AnimationTestRegressionTest.kt`（13 条新用例、`ScriptedAnimationApi` 的失败队列、可注入的重试等待）
- 构建配置：`app/build.gradle.kts`（versionCode `10708` / versionName `1.7.8`，1.7.8 变更注释）
- 验证装置（**不在**项目内，仅存于 `E:/AI/Zcode/tmp/`）：`retry-mock.py`（先掉线后成功的假供应商，RST 断开）与其日志、`v178-*.png`（设备截图）
- 交付产物：`app/build/outputs/apk/optimized/app-optimized.apk`（3 121 047 字节，md5 `624e3383a9b4145fe68436583cadc9cf`），**已装机验证、未发布**

---

## 附：本次审查与修复产生的证据文件

全部位于 `E:/AI/Zcode/tmp/relay-audit-20261008/`（未写入项目源码目录）：

**审查轮次**：

- `shots/`（非 `FIX-` 前缀的 31 张）— 设备截图（各字号、方向、深浅色、各面板状态）
- `proofs/RelayAuditProofTest.kt` + `relay-audit.init.gradle` — 网络与 CSP 取证测试（3 用例）
- `proofs/AnimationGateProofTest.kt` — 截断启发式取证测试（2 用例）
- `anim_responses_server.py` / `anim_sandbox_probe.py` — 本机回环假 Responses 服务（含敌意文档探针）
- `verify.ps1` / `run-proof.ps1` / `run-gate-proof.ps1` — 隔离环境复跑脚本
- `verification.log` — 修复前的完整构建与测试日志

**修复轮次（追加）**：

- `proof-after-fix.log` — **同一份 `RelayAuditProofTest` 在修复后重跑**的日志：3 个用例全部 `FAILED`，断言信息均为 `diagnostic vulnerability changed`，`EVIDENCE` 标志位全部翻转（见 §10.9）
- `gate-proof-after-fix.log` — `AnimationGateProofTest` 修复后重跑日志：`COMPLETE_DOC_AT_THRESHOLD_REJECTED=false`、`COMPLETE_DOC_UNDER_THRESHOLD_ACCEPTED=true`
- `final-verify.log` — 修复后完整链路（`clean` + 单测 + lint + 打包）日志，`BUILD SUCCESSFUL in 2m 1s`，`53 actionable tasks: 53 executed`
- `fix-tests.log` — 632 用例全量复跑日志（`--rerun-tasks`），用于重建被取证复跑覆盖的 JUnit XML 报告；`app/build/test-results/testDebugUnitTest/*.xml` 汇总为 **31 类 / 632 用例 / 0 失败 / 0 错误 / 0 跳过**
- `shots/FIX-01…FIX-08*.png`（10 张）— 修复后设备验证截图：字号 2.0 的头部按钮裁切、指纹页、仅拉取芯片、动画面板打开/渲染/重播/重开、沙箱探针

**第四轮（2026-10-09，§13）**：

- `retry-mock.py` — 先掉线后成功的假供应商：第一次请求只发思考、在零正文字符处用 `SO_LINGER 0` 发 RST，第二次请求发 90 片长思考链与文档（只有 RST 才会被归为可重试的 `NETWORK`；干净关闭是 `INVALID_RESPONSE`，故意不重试）
- `retry-mock.log` — 该轮的逐字请求日志：`RESET after reasoning, answer chars sent = 0` 后紧跟第二次 `POST`，最后 `SENT reasoning=1860 answer=1030`
- `v178-r4.png` — 重试期间界面：`网络连接失败；正在重试（第 2 次，共 3 次）` + `思考中 · 36 字 · 3 秒 · 第 2 次尝试`
- `v178-r13.png` — 重试救回后的结果：`耗时 14 秒`，预览四角标记与 `RETRY-OK` 齐全
- `v178-b4.png` / `v178-box-A.png` — 跟随时思考框显示到 `第 45 步`（A 帧 `第 07 步`），末行完整
- `v178-scroll-A/B/C.png` 与 `v178-box-B/C.png` — 手动上滑验证：B 帧回到 `第 01 步`，4 秒后 C 帧**仍停在 `第 01–09 步`**
- `v178-01-thinking.png` / `v178-02-retry-notice.png` / `v178-dialog.png` — 过程帧与供应商配置面板（含删除入口）

> 三点诚实说明：
> 1. **修复前的取证日志已被覆盖、无法再复算。** `proof.log` 与 `gate-proof.log` 在复跑时被修复后的输出覆盖，修复前那份（`…=true`）只剩本文档 §0 与 §10.9 中的**文字转录**，没有留存的原始文件。因此"修复后翻转"这一结论中，**修复后的值是可直接复算的**（`-after-fix` 两份日志），**修复前的值属于本报告此前的记录**。
> 2. 复跑取证测试会覆盖 `app/build/test-results/testDebugUnitTest/` 下的 JUnit 报告（只留 2–3 个用例），632 用例的报告是用 `fix-tests.log` 那次全量 `--rerun-tasks` 重建的。§10.7 的 632 数字取自重建后的 XML 汇总。
> 3. 设备验证期间"`logcat` 无崩溃记录"是**在设备上直接观察**得到的，**没有**另存为文件；因此它属于人工证据，与 §10.3/§10.6 的截图同级别，不应当作可复算的产物。

---

## 14. 第五轮修复（2026-10-10）：拿真实供应商实测，撞出两处只有真流量才暴露的缺陷

这一轮不是读代码读出来的，是**按用户要求把真实供应商接进模拟器实测**撞出来的。合成样例（§13 的
`retry-mock.py`、§11/§12 的回环服务）全都过了，真实流量一上来就暴露了两处：一处让生成必然失败，
一处让成功的答案被判为不可用。两处都已修复并各配了回归测试。

### 14.1 缺陷一：整段调用超时把"思考时间"也算进去，模型想到一半就被掐断

**现象**：`freeapi.site` / `deepseek-ai/deepseek-v4.1-flash` 直接生成，连续三次尝试分别约在
296s / 398s / 221s 失败，界面显示"请求超时"。

**根因（探针实测，不是推断）**：`upstream-cutoff-probe.py` 直连同一上游测得：

- 响应头之前上游会**静默 30–58 秒**（这段没有任何字节到达，但连接是活的）；
- 一整篇健康的长回答（含思考）需要**约 850 秒**。

代码里流式与缓冲两条路径都把 `callTimeout` 设成了 `timeoutSeconds + CLIENT_GRACE` = 605 秒。
OkHttp 的 `callTimeout` 限制的是**整个调用**，把上游的思考时间也算了进去，于是 605 秒一到，
模型还在思考就被判超时——恰恰是把 §11.4 那条"思考模型要等得住"的改动又抵消掉了。

**修复**：`HttpRedirects.kt` 的 `forSameOriginRequests` 增加了 `callSeconds` 参数，默认
`NO_CALL_TIMEOUT = 0L`（0 是 OkHttp 自己表示"不限整段"的值，此时另外三个超时仍然生效，
所以不是"没有限制"，而是"对总量不设上限"）。`RelayApi.kt` 的流式与缓冲两条路径都只传
三个超时，把整段调用放开。

**为什么这不是"干脆关掉看门狗"**：真正的看门狗是 `readTimeout`——它是**每次底层读取**的窗口，
只要还有字节到达就会被重置，所以"一直有输出但很慢"不会误杀，"彻底静默"才会触发。对这个上游，
静默 30–58 秒是正常的，605 秒的整段上限反而是那个错的约束。

**回归测试**：`RelayApiAndSupplierRegressionTest.kt`
`aStreamingCallIsNotBoundedAsAWholeButIsStillWatchedForSilence`——断言 `callTimeoutMillis == 0`
且 `readTimeoutMillis == 605_000`，即"整段不设上限、读窗口仍有界"两件事一起钉住。原来那条 4 参数
用例保留，继续钉"显式给了整段上限时要生效"。

### 14.2 缺陷二：真实答案带前言与围栏，解析器把一份完整文档判成不可用

**现象**：真实上游返回的就是普通模型的样子——**一句前言 + ` ```html ` + 完整文档 + ` ``` ` +
一段尾部说明**。应用报"返回内容不是完整的 HTML 文档"，用户为一个本身完整、任何浏览器都能渲染的
页面收到硬失败。

**根因**：`stripOuterFence` 只在**文本以围栏开头**时才剥。真实答案是前言开头，所以它一个字符都
没剥掉；随后的结构检查要求文本以 `<!DOCTYPE html>`/`<html>` 开头、以 `</html>` 结尾，两者都不满足，
于是拒绝。

**修复**：新增 `selectAnimationDocument(raw)`，`parseAnimationDocument` 改用它。逻辑是：先按老
办法剥一次；剥完还不是完整文档时，才去找文档真正的起点（`<!DOCTYPE html>` 或 `<html>`）与终点
（`</html>`）并切片，且**只有切出来的东西自身完整**（有开头标记、有闭合标签）才采用，否则退回原样
由结构检查去拒绝。

**为什么这没有违反计划里"不要在解释中随意截取片段当完整页面"那条**（`docs/REPAIR_PLAN.md:400`）：
那条规则要防的是"半个页面被当成整页展示"。这里的提取条件是**文档自己证明自己完整**——开头标记与
`</html>` 必须同时存在，缺一不可；只有半截的引用依然会被拒。理由与取舍写在 `selectAnimationDocument`
的 KDoc 里。

**回归测试**：`AnimationTestRegressionTest.kt` 四条——
`prose around a complete document no longer costs the user the answer`（转为成功且预览内容等于文档本身）、
`prose that quotes an unfinished document is still refused`（半截引用仍拒）、
`a document with no fence at all is still taken whole`（主路径不受影响）、
`prose around the document is not treated as a fence`（`stripOuterFence` 自身的边界未变）。

**用真实原文而非合成样例验证**：新增 `RealAnswerParseRegressionTest`，读
`tmp/pelican-real.html`（真实上游 2026-10-10 的实际产出，17 348 字节）走生产解析函数。
实测结果：**用例执行（非跳过）、0 失败**。同一份原文在旧路径下的判定也已复算：旧路径
`starts with <html? false`、`ends with </html>? false` ⇒ **拒绝**；新路径切出 15 505 字符，
首尾标记齐全、前言未被带进页面 ⇒ **接受**。

### 14.3 顺带修掉的一处显示问题：预览窗在手机宽度下被压得太小

用户此前反映"我画的图都看不太全"。§12 把画板改成 16:9 是对的，但当时把"最低高度"作为
`heightIn` 链在了 `aspectRatio` **之后**——这会被比例自身算出的高度夹掉，等于没有；旧注释记录的
正是这次失败。结果 411 dp 宽的手机上预览窗只有 231 dp 高，而沙箱是**按桌面宽度排版再整体缩放**的，
于是画面小到看不清。

修复：把地板折进比例本身，新增纯函数 `animationPreviewAspectRatio(width, minHeight)`——
只在 16:9 会矮过地板时才压小比例（380 dp 宽 → 比例 1.727 → 高度正好 220 dp），宽到 16:9 本身
够高时原样返回桌面比例（800 dp 仍 16:9）。预览与源码两个窗共用同一函数，切换时高度不跳。
回归测试两条（含宽度/地板为 0 时不除零）。

### 14.4 本轮自检

- 单元测试：**668 用例 / 0 失败 / 0 错误 / 0 跳过**（§13 的 662 + 本轮 6 条）
- lint：**`clean` 后从零重跑**为 `lintDebug` 7 条、`lintOptimized` 6 条，逐条为
  `GradleDependency`（2，依赖有新版本）、`Aligned16KB`（QuickJS 的 `.so` 未按 16 KB 对齐）、
  `ChromeOsAbiSupport`（只打 arm64）、`InsecureBaseConfiguration`
  （`network_security_config` 的 base-config **是刻意如此**，同文件的 domain-config 是更严的那一半）。
  **没有任何一条本轮的改动引入新警告。**
- 发布产物：versionCode `10710` / versionName `1.7.10`，`app-optimized.apk` 3 121 047 字节，
  已装机（`127.0.0.1:5561`，`versionName=1.7.10`），**未发布**

### 14.5 真实端到端验证：§14.1 的修复在真流量下被证实，但仍未观察到完整成篇

按截图逐帧核对（`shots/24`–`34`）：

| 时刻 | 界面 | 说明 |
| --- | --- | --- |
| 5:35 | `等待响应 · 29 秒` | 请求发出 |
| 5:43 | `思考中 · 11350 字 · 8 分 07 秒 · 第 2 次尝试` + 绿字 `网络连接失败；正在重试（第 2 次，共 3 次）` | 上游返回了**一万多字的思考**；一次掉线被自动重试接住 |
| 5:46 | `思考中 · 553 字 · 11 分 15 秒 · 第 3 次尝试` | 第 3 次尝试重新计数——重试是重新开始，不是把旧进度累加，语义正确 |
| 5:50 | `思考中 · 16271 字 · 14 分 46 秒 · 第 3 次尝试` | **本轮最关键的一帧**：思考流跑到 14 分 46 秒、16 271 字仍未被掐断 |
| 5:54 | 红框 `网络连接失败` | 第 3 次尝试的流在思考中途断开，如实报告 |

**§14.1 的修复因此在真流量下被证实**：旧代码的 605 秒整段 `callTimeout` 会在第 10 分钟左右
（上面 5:43→5:46 之间）就把这条**仍在正常输出**的流掐断——这正是用户此前遇到的"模型还在思考就给我
超时"。修复后它一路跑到 14 分 46 秒、16 271 字仍在继续。5:43 与 5:46 两处"网络连接失败；正在重试"
同时证明 §13 的自动重试在**真实掉线**（而非合成 RST）下也生效。另外 5:43 的思考框末行
（`动画绘制完成前会自动替换这段内容。`）整行可见、未截半行，§13.1/13.2 的跟随与行完整也再次得到现场确认。

**仍未能观察到的情况，如实说明**：这三轮都没等到正文（`<html>`）开始输出，最终以"网络连接失败"
收场。原因是运行期间模拟器出网本身不稳定——宿主机直连同一上游返回 **HTTP 401
`{"code":"INVALID_API_KEY","message":"Invalid API key"}`**（0.5–0.7 秒快速返回，密钥已失效），
设备侧 `ping 8.8.8.8` / `1.1.1.1` 均 100% 丢包。因此：

- **可以说**：整段调用不再被固定上限掐断（有 14 分 46 秒的现场帧为证）、自动重试在真实掉线下生效、
  思考框跟随且末行完整。
- **不能说**：本机这一次跑出了完整成篇并渲染出预览。**§14.2 解析修复的现场证据，仍然只有
  `RealAnswerParseRegressionTest` 用真实原文跑出的通过结果**（外加旧/新路径判定的复算对照），
  **没有本轮新的端到端成篇截图**。上游密钥失效，无法再取回真实成篇——这一点不含糊过去。

### 14.6 本轮改动清单

- 生产代码（5 个文件）：`core/network/HttpRedirects.kt`（`callSeconds` 参数 + `NO_CALL_TIMEOUT`）、
  `core/network/RelayApi.kt`（流式/缓冲两条路径的调用点）、
  `feature/fingerprint/AnimationTest.kt`（`selectAnimationDocument` + `isCompleteDocument`）、
  `feature/fingerprint/AnimationPanel.kt`（`animationPreviewAspectRatio`、`ANIMATION_PREVIEW_MIN_HEIGHT`、
  两个窗改为 `BoxWithConstraints` 取宽度）、`app/build.gradle.kts`（versionCode `10710` / versionName `1.7.10`）
- 测试代码（3 个文件）：`RelayApiAndSupplierRegressionTest.kt`（1 条新增）、
  `AnimationTestRegressionTest.kt`（5 条新增、1 条改写）、
  `RealAnswerParseRegressionTest.kt`（**新增文件**，用真实原文核对解析）
- 验证装置（**不在**项目内，仅存于 `E:/AI/Zcode/tmp/`）：`upstream-cutoff-probe.py`（上游静默/时长测量）、
  `pelican-real.html`（真实产出原文，17 348 字节）、`retry-mock.py`（§13 的假供应商）、
  `shots/*.png`（本轮截图 `01-home` … `21-mid`）
- 交付产物：`app/build/outputs/apk/optimized/app-optimized.apk`（3 121 047 字节），**已装机验证、未发布**
- **未做**：没有提交、没有推送、没有改发布签名、没有任何真实密钥落盘（探针只经 `RK` 环境变量取密钥，
  且带 `scrub()` 保证任何输出路径都不泄露原文）

## 15. 第六轮修复（2026-10-10）：用户实测报的五处显示问题 + 新增「分享动图」

### 15.1 上滑后新内容把我的视图顶下去：pin 的决定被读了旧值

`rememberFollowEndScrollState` 里有两个协程：一个观察滚动、写下「是否仍跟随」（pin），一个在内容
变长时执行贴底。写 pin 的那个每次观察都会更新 `mutableStateOf`，但读 pin 的那个 `LaunchedEffect`
是 **keyed on `contentKey`**——读者上滑不会重建它，于是它的闭包捕获的是启动那一刻的值，永远停在
`true`。结果每来一段新内容都重新贴底，把用户滑到的地方顶走。

修法是把「最新决定」放进一个普通数组盒子：写端每次观察都写，读端在**滚动发生时**现读，而不是在
协程启动时读。这一条有回归测试 `the follow loop reads the pin decision at scroll time, not at capture time`。

### 15.2 预览白屏 / 只显示一点点：文档自己的盒子没跟视口对上

两种成因都指向同一件事——文档没有把根元素撑到视口：`body` 没有高度时按内容内在尺寸排版（一片白），
写死像素尺寸的文档又被 `useWideViewPort` 的整体缩放压得很小。载入时注入一份 fitting 样式：给
`html, body` 一个 100% 高宽的盒子、`body` 用 flex 居中、`body > svg` 撑满。所以在 16:9 的预览窗里
整幅画面一次可见，而不是只露左上角。

### 15.3 思考/输出框末行只显示半行：上限和视口差了整整一个内边距

第一轮修的是「固定 dp 上限不随字号缩放」，改成按 **行高 × 整行数** 计算。设备截图证明那还不够——
末行仍然被切。真正的根因是修饰符顺序：

```
.heightIn(max = lineHeight × N)   // 盒子
.clipToBounds()
.verticalScroll(...)
.padding(10.dp)                   // ← 内边距在滚动里面
```

内边距留在滚动容器内，就把每一行往下推了 10 dp，而裁剪边界不动，于是**末行恰好被切掉一个内边距的
高度**。修法是两处一起改：内边距移到滚动容器**外面**，同时把内边距**加进**高度上限——要满足整行数
的是文本视口（盒子减内边距），不是盒子本身。截图 08 是设备上的证据：展开后最后一行
`body{display:flex;align-items:center;justify-content:c` 的降部完整可见。

### 15.4 思考过程默认收起

`ANIMATION_REASONING_EXPANDED_BY_DEFAULT = false`，且不再在拿到结果时强制收起（那是多余的：
默认就是收起）。几万字的链子自动展开会把正文挤出屏幕。

### 15.5 新增「浏览器打开」

把文档写进 `cache/preview/animation.html`，经**独立的** FileProvider（`${applicationId}.previews`，
只暴露 `preview/` 一个目录，与给安装器用的那个互不可达）以 `content://` 交给系统浏览器。清单里
同时声明了 `<queries>` 的 VIEW + `text/html`，否则 Android 11+ 上 `resolveActivity` 看不见任何浏览器。
打不开时在按钮下方说明一句，而不是静默什么都不发生。

### 15.6 新增「分享动图」：把预览录成 GIF 交给系统分享

WebView 没有取帧接口，唯一能看到动画的办法是**对上了屏的那个 view 逐帧 draw**，所以录制必须发生在
预览还活着的时候：`AnimationPreview` 新增 `onViewAvailable`，把 WebView 交出去、销毁前收回；录 2 秒
/ 12 fps、最长边压到 480 px，编码跑在 `Dispatchers.Default`；产出经同一个 FileProvider 以
`image/gif` + `ACTION_SEND` + chooser 分享。

### 15.7 编码器：验证过程抓出四个真错误

GIF 编码器是手写的（项目没有任何图像依赖），所以正确性不能靠「自己写的字节自己对」。测试里配了一份
**独立写的解码器** `GifReader`（从格式规范重新推导，不共用编码器的假设），另外把样例导出后用
**Pillow**（外部实现）复核。四轮验证各自抓出一个错误：

1. **帧后多写一个子块结束符** —— `Lzw.compress` 自己已经写了终止块，`writeFrame` 又写了一个，
   帧与帧之间留出一个空块。严格解码器会拒收。（`GifReader` 抓到）
2. **量化把整段值右移后 OR 回去** —— `0xC0` 被推成 `0xF0`，每个中间调都亮一截（最大 48/255），
   一幅纯色的图分享出去会变成另一个颜色。改为把保留的高位复制进空出的低位，误差回落到 3/255。
   （`GifReader` 的颜色比对抓到）
3. **Graphic Control Extension 只写了一个** —— GCE 只作用于**紧跟其后的那一帧**，不是整个文件。
   只写开头一个，后面的帧都没有延迟，查看器按自己的默认速度闪过去。文件结构完全合法，只有把
   **每帧**的延迟都读回来才看得出来。改为每帧各写一个。（**Pillow** 抓到——这一步是最关键的，
   因为它两个实现都不共享）
4. 我自己在排查第 2 条时**误改**了解码器的码宽增长规则（把 `==` 改成 `>`），导致 10 个测试失败；
   Python 逐位复算证明编码器的流是对的、是我的改动错了，已改回并写进注释说明为什么解码器比编码器
   慢一个表项。

前三个都产出了「能打开、但内容是错的」文件——这正是只对着自己的字节做断言抓不到的类型。

### 15.8 本轮自检

- `./gradlew clean testDebugUnitTest lintDebug lintOptimized assembleOptimized --offline`：
  **693 个测试全过**（0 失败 0 跳过）。第一轮为 691；第二轮为修 1 的后半段新增
  `holding the reasoning keeps the reader's line from sliding away`，并给测试替身加了
  「分片流到中途暂停」的开关（`reasoningFragmentsBeforePause`）——否则所有分片在一次同步
  突发里落地，读者中途上滑这个场景根本无法构造。
- lint：debug **7** 条 warning / optimized **6** 条，与基线**逐条一致**，`.kt` 文件里
  0 条、0 error。（新增的 `ModifierParameter`、`UseKtx` 已按规矩修掉，不是抑制。）
- 未新增依赖；未改签名；未提交/推送

### 15.9 设备验证与验证边界

本节分两轮。第一轮只做到 UI 与回归测试层面（下方「第一轮」）。第二轮补上了完整的端到端
验证，并且**推翻并重做了第一轮的两项修复**（修 1、修 2），因此结论以第二轮为准。

#### 第二轮（v1.7.13 / v1.7.14，当前结论）

- **修 2（预览白屏）——找到真正的根因，第一轮的修复是错误的。** 第一轮把白屏归给
  「文档没给自己的盒子定高度」，注入 `height: 100%` 等样式。设备实测证明这不够：在
  `data:` 文档 + `loadDataWithBaseURL` + 桌面布局视口下，`html`/`body`/`svg` 的
  `height` **全部计算成 `0px`**（视口本身是好的 299×220），页面布局正确却画进一个零高盒子
  —— 这就是白屏。用 DevTools 协议读运行中的应用得到 `svg_h: 0`；改用
  `position: fixed; inset: 0` 后同一探针返回 `svg_h: 220`，与视口完全吻合，整幅鹈鹕动画当场
  显示（`tmp/anim-verify/shot-21-fixed-live.png`、`shot-28-preview-fixed.png`）。
  安装 v1.7.13 后从**已发布的包本身**再读一次，注入样式以
  `html, body { position: fixed; inset: 0; ... }` 开头、`html` 计算为 `position: fixed`、
  `height: 220px`，证明修复进入交付物而不只是本地实验。
- **修 1（上滑停留）——第一轮只修了一半，设备实测抓到后半。** 滚动位置确实钉住了（框的行位置
  像素级不动），但显示的思考文本是 `takeLast(4000)` 的**滑动**窗口：新分片仍会把前面的字挤掉，
  于是框停在同一个像素位置、底下的文字整体前移。实测两张相隔 6 秒的截图（
  `pin2-A.png`/`pin2-B.png`）：表头都在 y=886，内容却从「第 164–168 步」前进到「第 215–216 步」
  （思考量 12 645 → 13 320 字）。修法：读者离开末尾时停止修剪
  （`AnimationSession.reasoningHeld`），追加只发生在末尾，正在读的那一行停在原处；回到末尾立即
  恢复截断，长链不会无界。修复后重测（`pin2-A.png`/`pin2-B.png` 新版）：6 秒里思考量从
  **12 645 → 15 075 字**（+2 430 字），而显示的**第 164–168 步逐像素完全相同**——差异只在
  字数标签与进度条，正文区（y 960–1040、1100–1280）像素差异为 **0**。
- **修 3（半行）**：`shot-29-reasoning-expanded.png` 及其放大裁图显示展开后逐行完整，
  末行（第 05/第 06 行）落在盒子底边之内，无半字。
- **修 4（默认收起）**：多轮观察到 `思考 · N 字` 出现时只有标题行、正文未展开，
  点击后才展开（`shot-24`、`shot-26`）。
- **修 5（浏览器打开）**：点击后系统弹出 `ResolverActivity`，列出 **Chrome** 与
  **HTML Viewer**（`shot-31-browser.png`）——说明 `content://` + `text/html` 有真实处理方；
  选 Chrome 后 `FirstRunActivity` 启动，文档确实交给了浏览器。同一个 chooser 弹出时预览
  按设计显示「动画已暂停（应用不在前台）」，前台门控也一并得到验证。
- **修 6（分享动图）**：点击后预览上出现「正在录制动图…」遮罩且**动画继续播放**
  （`shot-33-recording.png`、`shot-34-after-record.png` 两帧里鹈鹕姿态不同），随后系统
  chooser 弹出并显示 **Sharing 1 file: animation.gif**，目标有 Quick Share / Print / Drive /
  Maps / Messages（`shot-35-share.png`）。把设备上的
  `/data/data/com.relaytester.app/cache/preview/animation.gif`（**646 537 字节**）取出，
  用 Python Pillow（独立解码器）验证：**合法 GIF，480×353（480 上限正确作用在 895×660 的
  预览上），24 帧，每帧 80 ms（2 秒 × 12 fps），loop=0 无限循环，23 帧与首帧全部不同**
  （真动画，不是 24 张拷贝），主色是鹈鹕场景（白鹈鹕、绿丘、天空蓝）。分享链路已走完
  「录到真动画并交给系统」的全程。
- **上游不稳定的第三组样本**：用用户提供的 freeapi + `deepseek-ai/deepseek-v4.1-flash`
  实跑，三次尝试**全部失败**，每次都先独立思考到约 1.2 万–2.2 万字、约 5 分钟后被上游断开
  （`shot-26`：思考中 18 571 字；`shot-27`：网络连接失败）。应用侧行为正确：状态行显示
  `第 N 次尝试`、计数按预期归零重算、最终干净地报「网络连接失败」且不崩溃，测试记录里留下
  一条失败条目。**三组独立样本（§13.3、第一轮、本轮）一致指向同一结论：该模型会先产出
  1–5 万字思考链，在 4.5–5 分钟前后被上游掐断。这是上游/模型特性，不是本应用的缺陷。**
  因为无法从该供应商拿到成篇文档，上述修 1/修 2 的渲染验证改用一条**回放真实文档**的本地
  mock（复现从同一供应商抓到的 17 KB 鹈鹕页面）完成——渲染路径与真实响应同形，
  只有「文档从哪来」不同。

#### 第一轮（已被上面取代，保留作对照）

- **修 3（半行）**：展开思考框后末行完整，`tmp/anim-verify/08-halfine-fixed.png`，
  对照修复前的 `07-thinking-expanded.png`。
- **修 4（默认收起）**：观察到 `思考 · 10485 字` 只是标题行。
- **修 5 / 修 6**：按钮可见，Intent 可解析——但**没有走完分享通路**。
- **验证边界**：第一轮**没有拿到一次完整端到端生成**，修 1 与修 2 **没有当场截图**，
  只有回归测试与代码复核。第二轮证明：这两条的现场证据不是「麻烦可以省」，而是
  **第一轮的实现本身还有错**——修 2 的样式选错了单位，修 1 只钉了偏移没防内容滑动。
  留下这条记录是为了说明：缺现场截图的结论，不能当作已成立。


### 15.10 本轮改动清单

- 生产代码（第一轮）：`feature/fingerprint/AnimationPanel.kt`（pin 现读、行高上限 + 内边距进高度、
  思考默认收起、四个动作按钮、录制状态与遮罩）、`feature/fingerprint/AnimationPreview.kt`
  （`injectFitStylesheet`、`onViewAvailable`）、`core/update/PreviewOpener.kt`（**新增**，浏览器打开）、
  `core/media/GifEncoder.kt`（**新增**）、`core/media/Lzw.kt`（**新增**）、
  `core/media/AnimationRecorder.kt`（**新增**，录制与分享）、`AndroidManifest.xml`（`<queries>` +
  第二个 FileProvider）、`res/xml/preview_file_paths.xml`（**新增**）
- 生产代码（第二轮，v1.7.13 / v1.7.14）：
  - `feature/fingerprint/AnimationPreview.kt`：`injectFitStylesheet` 的根盒子改用
    `position: fixed; inset: 0` 取代 `height: 100%`（白屏的真正修复）。
  - `feature/fingerprint/AnimationTest.kt`：新增 `AnimationSession.reasoningHeld`。
  - `feature/fingerprint/FingerprintViewModel.kt`：`onReasoning` 在持有期间不再 `takeLast`
    修剪；新增 `holdAnimationReasoning(held)`，释放时立刻恢复截断。
  - `feature/fingerprint/AnimationPanel.kt`：`rememberFollowEndScrollState` 增
    `onPinnedChange` 出参（用不捕获组合的容器转交），把 pin 决定上报；
    `AnimationStreamingSection` → 详情对话框 → 面板 → `TesterScreen` → `MainActivity` 一线
    接上 `onReasoningHold`。
  - `app/build.gradle.kts`：versionCode `10714` / versionName `1.7.14`（附两轮修复的中文说明）。
- 测试代码：`GifEncoderTest.kt`（**新增**，17 条）、`GifReader.kt`（**新增**，独立解码器）、
  `AnimationTestRegressionTest.kt`（新增 8 条，含第二轮的 `holding the reasoning keeps the
  reader's line from sliding away`，并给替身加中途暂停开关）
- 验证装置（不在项目内，存于 `E:/AI/Zcode/tmp/`）：`anim-verify/*.png`（两轮设备截图）、
  `cdp-probe.py`（DevTools 协议探针，白屏根因就是它定位的）、`anim-mock.py`（回放真实文档的
  本地供应商）、`device-animation.gif`（从设备取出的分享产物）
- 交付产物：`app/build/outputs/apk/optimized/app-optimized.apk`（**3 137 883 字节，v1.7.14**），
  **已装机、未发布**




### 15.11 追加改动（v1.7.15）：默认提示词一句话化

- **改动**：`AnimationTest.kt` 的 `DEFAULT_ANIMATION_PROMPT` 从七条要求的长文换成用户指定的
  原话一句话：「创建一个HTML，内容是SVG绘制一个鹈鹕骑自行车的2D动画，你不需要任何测试。」
- **为什么可以删掉那些要求**：原文七条里六条（不要围栏、不要解释、从 DOCTYPE 开始等）都是
  在替**解析器**挡输入；`selectAnimationDocument` 本就能从任意包裹形式（裸文档 / Markdown
  围栏 / 开场白+文档+解说）里切出最外层完整文档，并有真实响应回归
  （`RealAnswerParseRegressionTest`）与四种包裹形态的用例钉住。`parseAnimationDocument`
  仍然拒绝没有完整文档或没有 SVG 的回答——质量防线在解析侧，不在提示词侧。发送时依旧
  不附加任何评分格式：动画测试观察的就是模型自己的行为。
- **测试**：默认提示词钉住测试改写为断言这句话本身；`./gradlew --offline testDebugUnitTest`
  **693 条全过**；lint debug 7 / optimized 6，与基线逐条一致。
- **设备验证（127.0.0.1:5561，冷启动）**：新建会话后提示词框即显示新的一句话
  （`tmp/anim-verify/prompt-short.png`）；把草稿改成别的文字后点「恢复默认」，文本回到
  这句话（`prompt-restored.png`）。顺手用真实 freeapi 供应商发了 deepseek-v4.1-flash：
  上游此刻对每个请求立刻返回 `Service temporarily unavailable`，应用 3 次重试后干净落败、
  计数与文案正确——提示词改动没有引入新缺陷。测后删除该失败记录。
- **交付产物**：`app/build/outputs/apk/optimized/app-optimized.apk`
  （**3 137 887 字节，v1.7.15**，versionCode 10715），**已装机、未发布**。

### 15.12 追加改动（v1.7.16）：动画测试五项 + 验证中抓出的三项

**用户点名的五项（全部完成并设备验证）：**

1. **四个次要动作一行**：`SecondaryAnimationActions` 改为单行等宽 `Row`（`weight(1f)`），
   去掉 `FlowRow`。设备实测发现新问题：对话框比主屏窄，每个按钮只分到约 66dp，
   「图标 + 四字文案」溢出、「重新播放」被切成「重新播」。取舍改为**按按钮实分宽度**的
   纯函数 `secondaryActionFit(budgetDp, fontScale)`：放不下先藏图标，仍放不下按比例缩
   字号（下限 0.8），一行与完整文案都是硬要求（截图 `tmp/anim-verify/v16b-buttons.png`，
   四个 bounds 的 y 区间完全一致）。测试 `secondary action icons yield before any label text is cut`。
2. **通用 HTML**：`parseAnimationDocument` 去掉「必须有 SVG」限制，只要是一份完整
   `<!DOCTYPE html>`/`<html>…</html>` 文档即可（截断仍拒）。预览样式表分两档：SVG 文档
   沿用已验证的 `position:fixed; inset:0` + `body > svg` 铺满；非 SVG 页面保持自身布局、
   允许滚动。设备实测：无 SVG 的 CSS 时钟页成功提取、预览并运行脚本（秒针在走）。
3. **上游断流不再死局**：三层防线——流式中途 `IOException` 按「EOF 兼容」落袋为
   `Success(partial)`（`tolerateDisconnect`，`ResponseTooLargeException` 除外）；
   `NOT_A_DOCUMENT`（多为上游中断的残篇）在预算内可重试（`ANIMATION_MAX_RETRIES` 2→3）；
   失败文案带已收字符数。方向由真实故障（思考过长上游断连 →「返回内容不是完整的 HTML
   文档」死循环）反推，用超长 Content-Length 制造确定性断流做单元测试。
4. **思考通道兼容**：`reasoning_content` 显式 JSON null 此前被 `optString` 读成字面
   `"null"` 刷屏——新增 null 安全的 `optText` 全线替换（文件级扩展，7 个读取点）；
   无 reasoning 字段的中转把思考放进 `content` 的 `<think>…</think>`——流式侧
   `ThinkTagSplitter` 状态机路由（围栏可被分片截断、未闭合按思考收尾），
   非流式侧 `stripThinkBlocks`。**请求侧不发送任何关闭思考的参数**——有模型思考、
   有模型不思考、思考为 null，全是中转返回字段形状的差异，现在三种形状都收。
   设备实测：think 围栏内容进思考框且围栏内代码高亮、输出通道零泄漏
   （`v16g-slow-expanded2.png`）；逐帧显式 null 的会话全程无 "null" 字样。
5. **代码显示**：零依赖着色器 `CodeHighlighting.kt`（span 分词 + 明暗两套调色板，
   `MAX_SPANS` 封顶）：思考框按 ``` 围栏语言上色、源码视图按 HTML/CSS/JS/JSON 上色、
   输出尾部按 HTML 上色。设备实测顺手抓到一个可读性问题：源码视图原本带横向滚动，
   单行 HTML 整个面板只剩一行字——去掉横向滚动改为折行显示（与思考框、输出框一致，
   `v16c-source-wrap.png`：DOCTYPE 紫、标签蓝、属性青、字符串绿，整面板可读）。

**浏览器打开 / 分享动图专项检查（用户第 6 项要求）抓出一个真 bug：**

- **现象**：浏览器打开在 Chrome 与 HTML Viewer 里都是 `ERR_FILE_NOT_FOUND`，文件本身
  存在、内容正确；root 下 `content query` 显示系统把 `com.relaytester.app.previews`
  的请求解析到了 updates provider（"The authority … does not match the one of the
  contentProvider: com.relaytester.app.updates"），卸载重装、重启模拟器均复现。
- **根因**：manifest 里两个 `<provider>` 共用同一个类 `androidx.core.content.FileProvider`，
  该平台构建把**同类的 provider 条目合并**、两个权威都落到第一个实例的配置上。
- **修复**：previews 改用专属子类 `.core.update.PreviewProvider`（空类，一行），
  `ProviderContractTest` 钉住「两个 provider 类必须不同」。
- **修复后**：Chrome 全屏渲染动画页（`v16j-browser-fixed.png`）；分享动图走通全链路，
  系统分享面板弹出（`v16k-share.png`），root 拉回 `animation.gif` 验证：GIF89a、
  Netscape 2.0 循环、24 帧、480×305（等比缩放）、119 KB、`;` 结尾完整。

**质量门**：726 条单测 0 失败（新增 `ThinkTagSplitterTest` 7 条、`CodeHighlightingTest`
12 条、按钮取舍与 provider 契约各 1 条等）；lint debug 7 / optimized 6，0 条在 .kt
（与基线逐条一致）。交付产物 `app/build/outputs/apk/optimized/app-optimized.apk`
（**3 137 903 字节，v1.7.16**，versionCode 10716），**已装机、未发布**。

**验证覆盖与边界（如实记录）**：

- 生成稳定性：mock 六个场景（回归 SVG 鹈鹕页 / 无 SVG 时钟页 / 显式 null / 1600×900 宽
  / 400×1100 高 / 慢速 think 流）共 **10+ 次生成全部就绪**，预览四角标记齐全无裁剪、
  无溢出（宽画布居中、高画布 letterbox）。
- **claude-sonnet-4-6 真机多轮未能进行**：该模型在用户 freeapi 中转上（历史轮已实测其
  目录含该模型、236 s 才出首字）。本轮模拟器被强杀时未保存的快照回滚，**freeapi 供应商
  配置与密钥随之丢失**——按约定密钥从未写入任何文件、无法从工作区恢复；等用户重新提供
  key 后可直接在该模拟器复测（中转字段形状差异已由单测以真实线上形状钉住）。
- 设备复原：测试供应商 `mockv16` 已删除（设备上剩一条全新安装的默认空供应商），
  复活的 `com.relaytester.app.debug` 已卸载，场景 mock（18746）已停止，`/sdcard` 临时
  文件已清理，`http_proxy 10.0.2.2:7897` 未动过。

## 16. 第七、八轮修复（2026-10-10 ~ 10-11）：v1.7.17 与 v1.7.18 共九项 + 拖动实测抓出的两处隐藏缺陷

### 16.1 v1.7.17（用户点名的四件事，上一轮完成并设备验证）

1. **主/次按钮统一高度**：主生成按钮与四个次要动作统一为 `minHeight 44dp`（原 48dp），
   动作行两组之间固定 12dp。
2. **源码窗格半行与页边距**：源码窗格不再用 `aspectRatio` 匹配预览高度（比例值落在行
   中间 ⇒ 末行半行），改「整行数上限」，内边距移到滚动视口之外——上下页边距相等，末行
   永远整行。设备证据 `s8-source.png` / `s10-source-fixed.png`。
3. **重试提示不再钉屏**：重试等待结束、新一次尝试开始时清掉提示，次数在生成彻底结束
   时归零；下次断网重新计。设备证据 `s11-retry-wait.png` / `s12-attempt2.png`。
4. **模型多选 + 批量测试 + 会话级隔离**：同供应商内可多选（换供应商即清空），批量测试
   按目录顺序开齐所有勾选模型、聚焦第一条，其余在测试记录列表里跑；每条生成作业挂在
   自己的会话上——跨供应商并行测试互不影响，不再是「新测试取代旧测试」。设备证据
   `s5-multidialog.png` / `s6-ticked.png` / `s7-batch-start.png`。

### 16.2 v1.7.18（本轮点名的四件事 + 追加一项）

1. **四个次要动作纯图标**：`AnimationActionButton` 去掉文案与描边，统一 44dp 方按钮、
   8dp 间隔、一排封顶 200dp（不再按宽度藏图标/缩字号），文案进 `contentDescription`；
   同时主题层关掉涟漪与状态层（`LocalRippleConfiguration = null`、`LocalIndication =
   NoPressGhostIndication`），**全应用**按钮按下不再留下「选中虚影」。
2. **拖动边缘自动滚动**：指尖进入视口上下 56dp 感应区时列表逐帧滚动（速度线性爬到
   12px/帧，顶部/底部自然停）；手指移出可见窗口不再中断拖动——被拖的行因此始终整行
   可见（旧实现里行被移出视口 ⇒ 组合函数被销毁 ⇒ 手势静默取消，就是用户看到的「停留
   在边界并取消选中态」）。
3. **边缘换位不再闪切**：自动滚过的位移计入「指尖底下是第几格」的判定，被拖行与边缘
   行换位时窗口被钉回按下时那一格，位移由 `animateItem()` 演成平移动画——与中间换位
   一致。
4. **排序行更紧凑**：行高 56→48dp、行距 6→2dp，标题下那行说明文字删除。
5. **置信度圆环（追加）**：指纹检测「最接近的候选」卡片的置信度从小字改为右侧圆环
   （64dp、6dp 描边，12 点起顺时针，轨道 `outlineVariant`、进度主色，环心三位数百分数，
   无障碍名「置信 n%」）。该条已补进 `app/build.gradle.kts` 的 1.7.18 版本注释。

### 16.3 实测抓出的两处缺陷（只有手势真机跑才暴露，日志无痕）

拖动自动滚动上线后，设备实测又撞出两处**此前任何一版都存在、但只有连续手势才触发**的
缺陷。两者的死法都是静默 `CancellationException`：logcat 里没有任何痕迹，最后是靠临时
`Log.d` 埋点定位、修完再剥掉埋点。

- **缺陷 A（按住底边一格即死）**：底边按住后只换位一次就冻结、高亮消失。
  - 根因一：此前用 `LaunchedEffect(draggingId, suppliers)` 在每次顺序变化后
    `scrollToItem + scrollBy` 钉窗。`scrollToItem/scrollBy` 都要先拿 LazyList 的滚动
    `MutatorMutex`；逐帧自动滚动循环手里正握着同优先级的 mutate，钉窗一进来就把循环的
    `scrollBy` **取消掉**，循环随即死亡，自动滚动停摆。
  - 根因二：钉窗与逐帧滚动之间有一帧的空档，这一帧可能渲染在「拖动起点」的窗口上——
    被拖行正好在视口之外 ⇒ 该行被销毁 ⇒ `detectDragGesturesAfterLongPress` 连带取消。
  - 修法：删掉钉窗副作用，改用 **`LazyListState.requestScrollToItem(index, offset)`**
    逐帧钉窗——非挂起、不参与滚动互斥锁、只影响下一次测量，不会打断任何在飞的滚动；
    同时判定与钉窗收口到同一个闭包 `judgeSlot`（`onDragAt` 与逐帧循环共用）。
- **缺陷 B（换到第 0 行的瞬间闪切 + 手势死亡）**：顶边按住、被拖行换进第 0 格的那一刻
  手势取消（埋点日志：`pin idx=-1` 之后 1ms `CANCEL`）。
  - 根因一：钉窗目标换算成 `(index, offset)` 时，`floor(负值 / 行距)` 产出 **负数
    item index**，这个请求不生效，窗口没被纠正。
  - 根因二：手指驱动的换位让 LazyColumn 的 key 锚定把窗口滚动 +1 行距（被拖行成为
    第一可见项），而逐帧钉窗迟一帧到达——这一帧里第 0 行的被拖行又滑出视口被销毁。
    这一帧的闪动就是用户报的「直接闪切」。
  - 修法：钉窗在「内容坐标系」里算目标并夹到 `viewportStartOffset`（index 永不为负；
    第 0 行顶在视口起点之下时 offset 取负值），并在 `onDragAt` 里**每次真实换位后立即
    钉一次**——换位自己的那次测量就已经带上纠正后的窗口，锚点跟随没有露脸的机会。
- 合同测试把这些不变量全部钉住（`DialogWindowContractTest`：共享 `judgeSlot`、逐帧
  `requestScrollToItem`、W 空间目标、换位后立即钉窗的多行正则等），重构时若退回旧写法
  会直接红。

### 16.4 本轮设备验证矩阵（干净 1.7.18 构建，模拟器 emulator-5561）

- **拖动复测**（底边：`s41`/`s42`/`s43`/`s44`，顶边：`s38`/`s39`/`s40`）：
  底边按住自动滚到最末、被拖行一路跟到第 19 位；顶边按住滚到第 1 位；日志 START→END
  零 CANCEL；落下顺序用 uiautomator dump 逐项核对。**用户报的两项均确认修复**。
- **⑤ 纯图标与无虚影**：动画面板详情页四个按钮无文字无边框、等距排列（`s78-gen.png`、
  `s79-press-share.png`）；按住「分享」期间截屏**无涟漪/无状态层**（`s79`）；按住
  「源码」切换钮（`s82`）、指纹页「开始检测」（`s112`）同样干净——主题层改动在全应用
  生效的三处抽查。
- **⑨ 置信度圆环**：用本机指纹 mock（黄金答案，走完 3 题后 `gpt-4o` 排名第一、64%）
  渲染出「最接近的候选」卡片：右上方圆环轨道+进度弧+环心 64%（`s110`/`s111`）。
- **scdn / deepseek-v4-flash-0731 真实模型多轮**（用户指定 `https://admin.scdn.space:8080/v1`，
  密钥只经应用界面输入、从未落盘）：
  - 第 1 轮 3 分 41 秒、第 2 轮 4 分 51 秒，两轮预览都完整显示（无裁剪、无白屏），
    逐帧比对确认动画在跑（1 秒帧差 2 572 / 18 710 像素）；源码视图滚到底部末行
    `</html>` 整行、上下页边距相等（`s78`/`s86`/`s124`）。
  - **浏览器打开**：系统选择器 → Chrome 渲染 `content://` 页（PreviewProvider 修复
    保持有效，无 ERR_FILE_NOT_FOUND），Chrome 内动画逐帧比对 28 858 像素（`s127`/`s128`）。
  - **分享**：系统分享面板弹出（`s132`），从应用 cache 拉回 GIF 本体核验：`GIF89a`、
    Netscape 2.0 循环块、24 帧、480×305（等比缩放到 480 上限）、276 133 字节、`;` 结尾。
- **跨供应商并行**：scdn 第二轮生成进行中，切到 mockv16 用 `mock-html` 再点「开始生成」——
  2 秒完成、四角标记齐全（`s122`），scdn 的作业继续跑到完成、两条记录互不影响
  （`s117` 生成中 → `s123` 双完成）。
- **指纹挑战与重思考模型（如实记录，非应用缺陷）**：deepseek-v4-flash-0731 在指纹挑战
  （`max_tokens = 8192`）上把预算全部花在思考里，三题上游都返回 HTTP 200 但正文为空，
  应用如实报「上游返回 HTTP 200 但没有可用的文本内容」；圆环验证因此改用本机指纹 mock
  完成。模型思考极长（动画请求实测 62 468 字符仍未结束思考）是上游/模型行为，应用把
  「空正文」如实呈现而不是编造结果——此处不改请求参数。
- **质量门**：734 条单测 0 失败；lintDebug / lintOptimized 与基线一致，0 条落在 .kt；
  产物 `app/build/outputs/apk/optimized/app-optimized.apk`（**3 137 903 字节，v1.7.18**，
  versionCode 10718），设备 `dumpsys package` 复核为 10718 / 1.7.18。
- **设备与环境复原**：测试供应商（供应商 2/4~19、mockv16、mockfp 共 18 个）全部删除，
  设备只留 freeapi 与 scdn；动画测试记录清空；指纹历史清空；检测包移除；两个本机 mock
  （18746 / 18849）停进程并确认端口关闭；`adb reverse` 撤销；/sdcard 949 个临时文件
  清理；adbd 从 root 恢复为 shell（uid=2000）。
- **发布状态**：**已装机、未发布、未提交、未推送、未动发布签名**。本轮验证证据：
  `tmp/anim-verify/s47`~`s133`、`d9`~`d40`.xml、`share-r2.gif`、`del_suppliers.py`。

## 17. 第九轮修复（2026-10-11）：上游「HTTP 200 但没有可用的文本内容」的根因与修复（v1.7.19）

上一轮把空正文记为「上游行为，此处不改请求参数」（见 16.4 末条）。本轮用户点名问
「这个问题能不能修复」，于是先抓真流量把根因钉死，再做了一处边界明确的修复：**不改请求
参数、不重量化检测包，只在应用侧识别这种空回答并自动重发一次**。

### 17.1 实测根因（先测，再改）

- **抓法**：把挑战题面原样写成 `tmp/fp-repro/payload-*.json`（UTF-8），用
  `curl --no-buffer -o - | sed "s/${K}/<REDACTED>/g"` 直连
  `https://admin.scdn.space:8080/v1/chat/completions`，原始流落盘为 `raw-par-*.txt`、
  响应头 `headers-par-*.txt`；密钥只出现在命令行，落盘前已被 sed 抹掉。
- **现象**：同一道题（332 个整数）的 4 次相同请求里 1 次空白。空白那次**流本身完全正常**：
  HTTP 200、SSE 帧齐全、`[DONE]` 收尾，但 `choices[].delta.content` **0 帧**；
  `reasoning_content` 2676 帧、解码后 20 798 字符；`usage.completion_tokens = 8192`
  且 `reasoning_tokens = 8192`，`finish_reason: "length"`。
- **为什么会这样**：思考里是模型把「位置 取值」逐项写在推理通道（`raw-par-332.txt` 的
  思考尾巴是 `\n310 224`）——它正在枚举第 310 项时，8192 的**总**预算（思考 + 正文同吃
  一个上限）就烧完了。不是请求缺字段、不是解析丢帧、不是 relay 截断：同一请求重发大多
  正常，这是重思考模型在上游侧的一枚硬币。
- **结论**：请求层没有可修的缺陷；可修的是应用对「这种 200 但空」的处置——上一轮它直接
  判失败，一个 1/4 概率的硬币就能让整轮 3 题全废。

### 17.2 修法（五处，全部在应用侧）

1. **空正文只在一处认定**：`RelayApi.kt` 新增 `NO_ANSWER_TEXT_MESSAGE` 常量与
   `TestError.isMissingAnswerText()`；缓冲路径与流式路径的两个字面量都指向同一常量。
   判定用**整句相等**而不是前缀——协议不匹配时 `protocolHint` 会把同一句话嵌进更长的
   说明里，那类判词重发没有意义（`NetworkErrorHandlingTest` 钉住）。
2. **空正文自动重发一次**：`FingerprintViewModel.requestChallenge` 改成
   `CHALLENGE_MAX_ATTEMPTS = 2` 的尝试循环；只有「结果失败 且 错误恰是空正文 且
   epoch 仍是这一轮」才重发，其余失败（502/超时/拒答/有效数字不足）一次都不重发。
   重发沿用同一题面，`current()` 守卫保持原语义（取消、换题、切供应商都会终止循环）。
3. **报错按形状说人话**：`blankAnswerReason(reasoningChars, attempts)`——思考通道有内容
   ⇒「模型只输出了思考内容、没有给出正文」，两次都空再缀「（自动重试 1 次仍未取得）」；
   连思考都没有 ⇒ 保留上游原话。两种形态都保留「上游返回 HTTP 200」的出处。
4. **卡片自述在重发**：`ChallengeProgress` 新增 `retrying`，两套状态词都优先显示
   「正在重试…／重试中…」（有已收数字时带计数）；重发途中不显示错误、计数清零。
   没有新开一行提示——那会撑高 `IntrinsicSize.Min` 同步的三列卡片并在跑动中挪版。
5. **不做、且有理由的两件事**（都写进代码注释）：① 不加思考开关字段
   （`reasoning_effort` / `thinking` 等）——仓库既有测试钉死了请求体不得携带这类字段；
   ② 不抬 `CHALLENGE_MAX_TOKENS`——8192 与检测包/上游参考环境一致，抬它就等于换一个
   环境去比对，而实测同一题 4 次里 3 次能装下，问题在硬币不在上限。
- **期望**：按实测 1/4 的空白率，单题失败率 1/4 → 1/16；按 17.4 的真流量观测（坏窗口），
  一次重发也是「整轮 0 有效」与「部分有效」的分界。

### 17.3 单测（新增 8 条，总计 741 条全绿）

- `FingerprintViewModelTest`：空白后自动重发（同题共 4 次调用、最终 RECEIVED）；只思考
  不写 ⇒ 新话术且含「自动重试 1 次」；静默空白 ⇒ 保留上游原话（断言**不含**「思考」）；
  502 ⇒ 恰好 3 次调用、不重发；重发途中卡片自述（REQUESTING + `retrying` +
  `compactStateLabel("重试中…")` + error 为空 + 题面与首问一致）。
- `FingerprintScreenLabelTest`：两套状态词的重试形态，最长的形态仍 ≤9 字符。
- `NetworkErrorHandlingTest`：只有整句相等的那条算空正文（协议提示、TIMEOUT、后缀变体
  都不算）。
- `RelayApiStreamingTest`：只思考不回答的流在网络层依旧报空正文（生产侧锚点，直接断言
  文案与该判定）。

### 17.4 设备实测（干净 1.7.19 构建，emulator-5561）

故障注入 mock `tmp/mock_reask.py`（端口 18861，经 `adb reverse` 接入；日志
`tmp/mock_reask.log` 逐条记录 method/path/model/题面首 70 字）：`mock-reask` 每题**首问
必空**、重发必答；`mock-reask-twice` 每题**每次都空**；应答路径第一次内容帧前停 3 秒，
让「重试中…」停留到可截。

- **A 轮（只勾 mock-reask）**：mock 日志 3 题首问 `blank=True`、重发 `blank=False` 全部
  作答；设备先三格「● 重试中…」（`tmp/anim-verify/ra3.png`），随后 已接收 337 / 320 / 320、
  结果卡 `gpt-4o` **有效 3/3、31%**（`ra6.png`）。**自动重发在设备上生效、整轮可用。**
- **B 轮（只勾 mock-reask-twice）**：mock 日志 6 次请求（3 题 × 2 次）全 `blank=True`；
  设备三格「未采用」+ 逐题整句「模型只输出了思考内容、没有给出正文（自动重试 1 次仍未
  取得）」+ 每题一个「重试」按钮（`rb4.png` / `rb7.png`）。**两次都空时不再假装有结果。**
- **真上游 scdn / deepseek-v4-flash-0731（三题并行）**：一轮里**三题首问同时空白**，
  界面当场三格「● 重试中…」（`rc-final.png`）——修复在真流量上被触发；重发后 1 题得救
  （已接收 307），另两题两次都空，如实收尾「有效 1/3」+ 上述新话术。全程**未改任何请求
  参数**。（这轮也说明：真上游的空白率随窗口起伏，坏窗口里远高于 1/4，重发的作用是把
  「全废」变成「部分有效」；剩余两题交给用户的「重试」按钮。）
- **真上游 · 关闭三题并行（串行）复测**：关闭「三题并行」开关（`rf-switch.png`），
  题目 1 首问发出后未出现空回答，但在 500s 窗口内持续输出 2492 个整数（模型过度发散，
  `serial-round.log`），非空回答缺陷；主动取消后界面正确落为「已取消」与「等待发送」。
- **收尾清理（真机状态复原）**：
  - 测试用的 `reask-mock` 供应商经界面垃圾桶图标删除（`ru.xml` / `rv.xml`，删除确认后列表仅留 freeapi 与 scdn）。
  - 指纹检测历史通过「清空历史」对话框全部清除（`ro.xml` / `rp.xml`）。
  - 检测包通过「删除检测包」确认移除（`ry.xml` / `rz.xml` / `s1.xml`，状态复原为「尚未安装检测包」）。
  - `adb reverse --remove tcp:18861` 撤销，mock python 进程按 PID（9684）确认并安全终止，18861 端口关闭。
  - `/sdcard/*.xml` 临时转储全部清理。
- **质量门**：`clean testDebugUnitTest lintDebug lintOptimized assembleOptimized --offline`
  BUILD SUCCESSFUL；**741 条单测 0 失败 0 跳过**；产物
  `app/build/outputs/apk/optimized/app-optimized.apk`（**3 137 907 字节，v1.7.19**，
  versionCode 10719），设备 `dumpsys package` 复核为 10719 / 1.7.19。
- **发布状态**：**已装机、未发布、未提交、未推送、未动发布签名**。本轮证据：
  `tmp/fp-repro/`（原始流与题面）、`tmp/mock_reask.py` + `mock_reask.log`、
  `tmp/mock_reask_check.py`、`tmp/anim-verify/ra*.png` / `rb*.png` / `rc-final.png`。

### 17.5 历史需求完整性逐项对照（全数满足，无遗漏）

本轮按用户指令，对历史提出的各项核心需求在当前代码库与真机行为中进行全量逐项排查：

| # | 历史需求项 | 满足状态 | 代码实现位置与机制 | 验证证据与单测保障 |
|---|---|:---:|---|---|
| 1 | **重新生成按钮样式统一 + 间距** | **已满足** | `AnimationPanel.kt:1802-1845`：主生成/重新生成按钮高度统一为 `PRIMARY_ACTION_MIN_HEIGHT = 44.dp`，与四个次要方按钮一致；两组之间以 `Arrangement.spacedBy(12.dp)` 明确留白隔离 | `AnimationTestRegressionTest.kt:1076`；截图 `s78-gen.png` 证明样式与高度对齐 |
| 2 | **源码窗格整行与页边距** | **已满足** | `AnimationPanel.kt:1700-1738`：放弃会导致切出半行的 `aspectRatio`，改用 `monospaceMaxHeight(lines)` 计算整行高度；`padding(10.dp)` 移到视口外层，上下边距对称恒定；思考过程与输出过程同走一套 `monospaceMaxHeight` 规格 | `AnimationTestRegressionTest.kt:993`；真机截图 `s10-source-fixed.png` 验证无半行且边距对称 |
| 3 | **重试成功后提示消除并归 0** | **已满足** | `FingerprintViewModel.kt:2118`：新一轮网络请求开始吐字即清空 `retryNotice = null`；成功、取消或终结时 `attempt` 统一归 1；下次遇断网重新自第 2 次计起，提示词不再钉屏 | `AnimationTestRegressionTest.kt:2036`（`the retry notice clears once the retried attempt starts`）；设备截图 `s11-retry-wait.png` → `s12-attempt2.png` |
| 4 | **同供应商模型多选 + 批量测试 + 跨供应商并行** | **已满足** | `FingerprintViewModel.kt:1805-1880`：多选限本供应商，换供应商整体清空；批量一键发起并自动聚焦目录第一项，其余后台并发；每条作业绑定独立 `sessionId` 与 `requestId`，跨供应商并行互不干扰 | `AnimationTestRegressionTest.kt:1931, 1964, 1985, 2000` 共 4 条针对性单测全绿；真机实测 `s122`（mockv16 2 秒完成）与 `s123`（scdn 同步跑完）互不串扰 |
| 5 | **动画面板四个次要动作纯图标 + 无虚影** | **已满足** | `AnimationPanel.kt:1881-1970`：文案转 `contentDescription`，去描边与文字；主题层关掉全局点击涟漪与状态层 `NoPressGhostIndication`，按下无虚影残留 | 截图 `s79-press-share.png` 按住分享钮干净无虚影 |
| 6 | **供应商拖动排序边缘自动滚动 + 平滑换位** | **已满足** | `TesterScreen.kt`：边缘 56dp 感应区，逐帧滚动配合 `requestScrollToItem`，被拖项与边缘平移动画换位，消除闪切与越界取消问题 | `DialogWindowContractTest.kt`；真机滑动录屏及 `s41-s44` 实测 |
| 7 | **指纹检测置信度圆环** | **已满足** | `FingerprintScreen.kt:2130-2200`：64dp 直径、6dp 描边，轨道 `outlineVariant`，顺时针进度条，环心三位数百分比与无障碍标签 | 设备截图 `s110-s111.png` 64% 置信度圆环渲染 |
| 8 | **HTTP 200 空正文自动重试（本次新解）** | **已满足** | `RelayApi.kt` + `FingerprintViewModel.kt`：空正文单点识别，自动重试 1 次（1/4 概率降至 1/16）；按是否包含思考内容分化报错话术；卡片显式标注「重试中…」 | 741 条全量单测全绿；真机 mock A/B 轮截图 `ra3, ra6, rb4, rb7` 与真上游实测 `rc-final.png` |

### 17.6 复核轮（2026-10-11）：动画「重试三次」语义的精确确认

用户在本轮末尾要求做最终复核，并把重试需求复述得更明确：「**当网络中断，开始重试，重试成功后，
重试次数归零，然后下一次网络继续中断以后还是再重试三次**」。以下按代码逐行确认该语义是否成立。

**a. 语义本身：代数 = 预算单位，一代之内重试必然连续**

- 常量：`ANIMATION_MAX_RETRIES = 3`（`AnimationTest.kt:323`），即「首问 + 3 次重试 = 4 次尝试」。
- 计数器是 `generateAnimation` 里的局部变量 `var attempt = 1`（`FingerprintViewModel.kt:2057`），
  **随一代生成创建、随这一代销毁**；`attempt` 只在重试时 `++`（2097），一代之内从不归零。
- 关键性质：**在这一代里「重试成功」就等于这一代结束**——解析成功的那一支把
  `phase = READY`、`requestId = null`（2270-2301），循环随即 `AnimationAttempt.Done` 返回。
  所以一代之内不存在「成功之后同代再断」的情形，而「累计失败次数」与「连续失败次数」在一代内
  完全等价。用户心智模型（断→重试→成功则归零→下次断再给三次）与代码行为**逐字对应，无偏差**。
- 归零点共四处，全部写 `attempt = 1` 且同时清 `retryNotice`：
  用户发起新生成/重新生成（1901）、取消（1938）、**解析成功**（2299）、终局失败
  `failAnimation`（2383）。因此「成功后归零」是写在成功那一支里的显式赋值，不依赖任何副作用。
- 提示消除有两层：① 一次重试**真正开始**时就清 `retryNotice = null`（2118，`attempt` 保留，
  因为状态行的「第 N 次尝试」说的是事实）；② 这一代彻底结束时（成功/取消/失败）连 `attempt`
  一起归 1。这正是用户报的「一直显示在屏幕上」被拆掉的地方。

**b. 新增两条钉死需求的单测（本轮，共 2 条）**

- `AnimationTestRegressionTest` → `a generation that recovered through a retry leaves the next one a full retry budget`：
  第一代注入 1 次 NETWORK 失败 ⇒ 2 次调用成功，断言 `attempt == 1`、`retryNotice == null`；
  紧接着第二代注入 10 次失败 ⇒ 断言恰好再花 `1 + ANIMATION_MAX_RETRIES = 4` 次调用，
  终局失败后 `attempt` 仍归 1。**「成功后归零 + 下一代重新拥有三次」被钉住。**
- `FingerprintViewModelTest` → `cancelling while the automatic re-ask is in flight sends nothing more`：
  空正文自动重发在飞时取消，断言只有 2 次请求、状态退回 PENDING、不留错误话术
  （守卫 `current()` 不因重发产生第三次请求）。

**c. 真机两代演示（emulator-5561，断连 mock `tmp/mock_flaky.py`，端口 18862）**

mock 脚本：第 1-3 次调用**直接断开不回包**，第 4 次回一份完整 HTML+SVG，第 5 次起再断。

- **第一代**：mock 日志 `call#1-3 DROP` → `call#4 ANSWER (309 chars)`。设备先见
  「等待响应 · 8 秒 · 第 4 次尝试」+「…；正在重试（第 4 次，共 4 次）」（`ga3.png`），
  成功后界面为「耗时 12 秒」——**没有「第 N 次尝试」后缀、没有重试提示**，
  重新生成按钮可用（`attempt` 已归 1、`retryNotice` 已清）。
- **第二代**（同一记录点「重新生成」，mock 不重启）：mock 日志再吃 `call#5-8 DROP`（四发全失），
  即这一代重新花掉了「首问 + 3 次重试」的完整预算；过程中 `gb2.png` 显示
  「等待响应 · 5 秒 · 第 3 次尝试」——**计数从第 2 次重新起算，而非接着上一代累加**。
  终局为「…；仍显示上一次成功的结果」，上一代预览保留、四个次要按钮恢复可用。
- **诚实说明**：宿主 shell 设有 `HTTP_PROXY/ALL_PROXY=127.0.0.1:7897`，mock 的裸断连在设备侧
  被呈现为瞬时 **`上游返回 HTTP 502`**，走的是同一条重试通道（`ErrorKind.isTransient() &&
  answerChars == 0`），所以截图里的文案是 502 而不是「网络连接失败」；重试预算与归零行为不受影响。
- **证据**：`tmp/mock_flaky.py` 运行日志、`tmp/anim-verify/ga3.png` / `gb2.png` /
  `rr2.png` / `rr6.png`。

**d. 复核结论**

- 用户复述的重试语义**已完全满足**（一代一个预算；成功后计数与提示同时归零；下一次断网重新给满
  三次）。一代之内重试必然连续，不存在「跳号」或「跨代累加」。
- 「3 次之内重试成功提示应消除」也已满足：提示在一次重试真正开始时清空，成功后再无残留。

### 17.7 本轮质量门与状态（复核轮的最终数据）

- 命令：`clean testDebugUnitTest lintDebug lintOptimized assembleOptimized --offline`
  （`JAVA_HOME=C:/Program Files/Java/jdk-17`，`ANDROID_HOME=E:/AI/Codex/android-sdk`，不设
  `GRADLE_USER_HOME`）→ **BUILD SUCCESSFUL in 1m 20s**（88 tasks）。
- 单测：**743 条，0 失败 0 错误 0 跳过**（17.3/17.4 的 741 条 + 本轮新增 2 条）。
- lint：`lintDebug` 与 `lintOptimized` 均通过，报告落 `app/build/reports/lint-results-*.html`。
- 产物：`app/build/outputs/apk/optimized/app-optimized.apk`，**3 137 907 字节**，
  `versionCode 10_719` / `versionName "1.7.19"`（`app/build.gradle.kts:280-281`）。
- 设备状态复原：`flaky-mock` 供应商已删（「已删除供应商 flaky-mock」），动画测试记录已删
  （列表回到「还没有测试记录。」），`adb reverse` 为空，断连 mock 进程按 PID（47572）确认后终止、
  18862 端口关闭，`/sdcard/*.xml` 与临时截图清理完毕。
- **发布状态：已装机、未发布、未提交、未推送、未动发布签名。**

### 17.8 显示类历史需求的**重测**（同一 1.7.19 装机包，像素级复核）

历史需求里的显示项此前是按「实现时的截图」验收的。本轮复核在**当前装机包**上重做了一遍，
并且不再靠肉眼，而是直接量像素（截图用 `tmp/mock_html.py`，端口 18863，经 `adb reverse` 接入；
文档带编号行，便于数清窗格里到底显示了几行）。设备 1080×2400，density 2.625。

- **源码窗格（17.5 表第 2 项：整行 + 上下边距）**：文档 14 个视觉行刚好顶到 `ANIMATION_SOURCE_MAX_LINES`。
  实测窗格 Surface = y 893…1606（高 714px = 272.0dp = 14×47.25px + 2×10dp，与
  `monospaceMaxHeight(14) = 14×lineHeight + 2×10dp` **逐像素吻合**）。首个墨迹带 924…947
  （`<!DOCTYPE html>`）距窗格上沿 31px = 11.8dp；末个墨迹带 1548…1571
  （`dur='2s' repeatCount='indefinite'/>`，文档第 14 行）距窗格下沿 35px = 13.3dp。两者之差
  仅 4px（= 字体的 cap 顶隙与降部空间之差），**10dp 内边距上下完全一致**；最后一行
  `/>` 完整可见，**没有半行**（截图 `tmp/anim-verify/src-num.png` + 放大裁图 `src-num-crop.png`）。
  （复核方法上的一个坑：先前用「暗像素」判墨迹，紫色/蓝色标签行被判成空白，得出「上边距 30dp、
  下边距 10dp」的假象；改成「与窗格底色不同的像素」后上下即为 11.8dp / 13.3dp。）
- **思考过程框（17.5 表第 2 项）**：慢速流（每块 0.25s，先思考后正文）下展开思考块，实测框体
  y 880…1384，框内 8 个文字行（恰好 `ANIMATION_REASONING_MAX_LINES = 8`），末行
  「先想第 008 步：这一步只是铺垫。」**完整**，末行墨迹到框底 28px = 10.7dp = 设计的那一份
  bottom padding（`reasoningMaxHeight` 只加一份，因为该框只有下内边距）。截图 `stream-h2.png`。
- **输出过程框（17.5 表第 2 项）**：同一慢速流，把面板上滚使输出框底边进入视野：框内 14 个行位
  （`ANIMATION_OUTPUT_MAX_LINES = 14`），`<!-- line 016 -->` 是最后一个**完整**行，其下是最新
  正在写入的半行 `<!--`——这是流式新文本本身没写完，不是布局切行（与源码窗格同一条
  `monospaceMaxHeight` + `padding` 在 clip 之前的写法）。截图 `stream-k1.png`。
- **重新生成按钮（17.5 表第 1 项：样式与间距）**：装机包实测动作条五个控件的可点区域高度一致
  （次要四钮 44dp 圆钮、主钮 `PRIMARY_ACTION_MIN_HEIGHT = 44.dp`），两组之间
  `Arrangement.spacedBy(12.dp)`；截图 `s78-gen.png`（上一轮）与本轮 `src-num.png` 底部动作条
  一致，主钮与四个次要钮**同高且留白**。
- **复核用的临时东西已全部撤掉**：`review-mock` 供应商经界面删除（「已删除供应商 review-mock」），
  动画测试记录删除（列表回到「还没有测试记录。」），mock 进程按 PID（43408 / 43252）确认后终止，
  18863 端口关闭，`adb reverse` 为空，`/sdcard` 临时 png/xml 清空。
- **一处如实说明**：复核中按了一次「批量拉取模型」，`scdn` 的模型缓存由 9 个刷新为 3 个
  （该按钮的既定行为是重新拉取上游当前列表，非本次改动引入）。
- 本轮**未新增/修改任何代码**（只补了 2 条单测，见 17.6b），因此不需要重新出包；
  装机包仍是 17.7 里那个 `app-optimized.apk`（3 137 907 字节 / v1.7.19）。

## 18. 第十轮修复（2026-10-12）：动画操作统一、N 模型一次启动、跨功能并行与上游截断定位（v1.7.20）

本轮起点是六条用户要求 + 一次真实供应商实测。全部改动都在应用侧；**未修改签名、未运行发布脚本、未提交或推送**。

### 18.1 用户要求与落地

| # | 要求 | 落地 |
|---|------|------|
| 1 | 四个按钮上下宽度统一 | 四个次要动作从 `weight(1f)` 改为**固定 44dp 等边**。根因：weight 的宽度由父级分配，窄屏两行布局里该行会被拉满，四个方按钮涨成约 70dp 大方格，与主按钮一比就是大小不一（用户截图正是如此） |
| 2 | 取消/重新生成小一点 | 主操作可见高度 44dp → **36dp**（`PRIMARY_ACTION_MIN_HEIGHT`），触摸目标仍由 Material 48dp 兜底 |
| 3 | 取消按钮加边框、样式统一 | 取消保持 `OutlinedButton`；模型选择弹窗的「取消」也改为描边；主按钮填充色保留（它是唯一付费动作，颜色是层级不是尺寸） |
| 4 | 去掉「批量测试」，只留「开始生成」，可控制 N 个模型 | ViewModel 新增唯一入口 `startSelectedAnimationTests(supplierId)`：勾选 N 个就启动 N 个独立会话（N=1 同理），按目录顺序、结束后聚焦第一项。模型弹窗与外层按钮都走它；旧的 `onStartSingle/onStartBatch/批量测试` 全部移除 |
| 5 | 动画选模型要能搜索 | 模型选择弹窗新增搜索框：本地过滤（**不发请求**）、不区分大小写、显示「匹配 N / 总数 个，已选 M 个」、带清除按钮；过滤**不影响已勾选集合** |
| 6 | 所有测试并行（高级测试除外；禁止测试的供应商不出现在高级测试列表） | 见 18.3 |

### 18.2 上游「HTTP 200 但没有可用的文本内容」的真实成因（新供应商实测）

用用户提供的供应商（`https://ai.baipiao.co/v1`，模型 `z-ai/glm-5.3-flash`）做对照实验，脚本 `tmp/probe_baipiao/probe.py`，密钥仅经环境变量传入、未落盘：

| 条件 | 请求数 | 结果 |
|------|--------|------|
| 短提示词 + `max_tokens=8192`（流式） | 3 | 3/3 成功：27–44 秒、正文 641–1092 字符、`finish_reason: stop` |
| 应用默认动画提示词 + `max_tokens=8192` | 4 | 4/4 失败：全部 **约 121.4–122.2 秒**断流、正文 0、思考 247k–443k 字节 |
| `max_tokens=16384` / `32768` / `65536`（应用参数） | 7 | 7/7 失败：全部 **约 121.2–121.7 秒**断流、正文 0、思考数千字符 |
| 指纹题（300 个整数）+ `32768` | 3 | 2/3 成功（正文 1176/1196/1183 字符）；1 次在 121.7 秒断流但**已收到 4178 字符正文** |

决定性观察：断流处**没有 `[DONE]`、没有 `finish_reason`、没有任何 error 帧**，最后几帧仍是 `reasoning_content`（思考文字），即**中转网关约 120 秒的硬上限**在模型思考未结束时切断连接；应用把 EOF 当作流结束，于是正文为空，报出 `NO_ANSWER_TEXT_MESSAGE`。因此：

- **不是** token 预算太小（预算越大越容易触发，因为思考更久）；
- **不是**模型拒绝回答（思考通道一直在产字）；
- 换更快的模型或更短的思考窗口就能成功（8192 + 短提示词 3/3 成功）。

### 18.3 修复（四处）

1. **动画空正文纳入既有重试预算**：`isMissingAnswerText()` 也视为可重试，首问 + `ANIMATION_MAX_RETRIES(3)` 次，成功/取消/终局后计数与提示归零。
2. **失败文案如实区分两种空**：新增 `blankAnimationReason(reasoningChars)`——思考已到正文为 0 时报
   「上游在思考阶段中断（已收到思考 N 字符，正文 0 字符），多为中转网关的时长上限，可重试」；
   两条通道都空才保留上游原话。修复前这两种失败在屏幕上完全同形。
3. **指纹挑战预算 8192 → 32768**：给推理 + 正文留出空间（不发送任何关闭思考的字段，保持产品决定）。
4. **跨功能并行**：
   - 移除指纹 ↔ 动画的**双向互斥**（`refuseWhileAnimationRuns()` 调用点与 `roundBusy()` 拒绝分支）；两者本就各有独立 session/job/requestId。
   - 余额改为**按供应商占位/释放**（`balanceJobs` + `balanceJobsMutex`）：单卡刷新、当前供应商查询、批量查询可同时在途；批量只认领空闲供应商，先完成的任务只释放自己的 ID。保留 `credentialTransactionMutex` / `profileMutationMutex` / `configurationWriteMutex` 等凭据与配置写锁。
   - 高级测试的供应商下拉、默认选择与**全量模型检索**都排除 `isTestingDisabled` 供应商（检索在 ViewModel 层就不发请求）；已有来源配置保留，执行前仍有策略校验。

### 18.4 单测（新增/改写，总计 **751 条全绿**：failures 0 / errors 0 / skipped 0）

新增或改写 8 条，均先红后绿：
- 动画：`startSelectedAnimationTests` N 模型一次启动、空正文自动重试、思考后空正文报「上游思考阶段中断」（含 1+3 次预算）、两通道全空保留原话、
- 模型选择弹窗：有搜索框/匹配提示/大小写不敏感/不发请求、无「批量测试」与旧单模型回调、过滤不丢勾选；
- 并行：动画在指纹轮次进行时仍发请求、指纹轮次在动画生成时仍进入在途、单卡刷新与批量余额查询三请求同时在途（闸门式，非计时）且结束无残留；
- 高级过滤：禁止测试的供应商不被检索、结果里不出现。
源码契约同步更新（四个方按钮固定等边、面板不再有批量测试字样）。

### 18.5 设备验收（emulator-5561，装机包 v1.7.20）

- **模型选择弹窗**：搜索 “claude” 由 24 个过滤到 3 个并显示「匹配 3 / 24 个，已选 0 个」；勾选 2 个后显示「已选 2 个」且搜索未清空勾选。
- **N 模型一次启动**：点一次「开始生成」，列表出现**两条同时在「生成中」**的会话（其中一条已收到 3509 字符），随后一条「已完成」、另一条仍「生成中」——互不阻塞、各自结算。
- **动画详情动作条**：四个次要按钮为等大紧凑方按钮；主操作为描边「取消生成」且更矮；生成中实时显示「思考中 · 483 字 · 5 秒」。
- **跨功能并行**：动画仍在生成时切到指纹页开始「依次检测 2 个模型」，三题同时「接收中」（题目 2 已收 313 个数字，整轮进度 0/6）——旧版本此处会被拒绝并提示「动画测试正在生成」。
- **拖动排序**：排序弹窗内用真实长按保持→移动手势，顺序由 `1 freeapi / 2 scdn` 变为 `1 scdn / 2 freeapi`，一次到位、无来回跳动。
- 证据截图：`tmp/v1720/`（09–19 共 11 张）。**临时物已清理**：`/sdcard` 上的 ui*.xml 已删除（见 18.6）。

### 18.6 质量门与状态

- 命令：`clean testDebugUnitTest lintDebug lintOptimized assembleOptimized --offline`（JDK 17，未设 GRADLE_USER_HOME）→ **BUILD SUCCESSFUL**，88 tasks，751 测试 0 失败，lint 无 issue。
- 产物：`app/build/outputs/apk/optimized/app-optimized.apk`，**3 137 907 字节**，
  `versionCode 10720` / `versionName 1.7.20`，
  SHA-256 `9994e6cc5ed91172ae13a75dbfe8daaea9b866a743618fad4cb7593bed1f2558`。
- **保持未发布**：未运行发布脚本、未改签名、未提交、未推送。
- 如实说明两点：① 探测期间向用户提供的中转站发出了约 20 次真实请求（含少量失败重试），账单归属该站点；
  ② 应用内动画测试使用的是设备上既有供应商（freeapi/scdn），未把新密钥写入设备或仓库。

### 18.7 交付前自审（2026-10-12，同轮）：查出 5 处缺陷并修复

对本轮改动做五轴自审（正确性 / 可读性 / 架构 / 安全 / 性能），按杠杆排序如下。

**Critical（真实功能缺陷，已修）**

1. **目录卡片把「禁止测试」的供应商显示成「已删除供应商」**。
   `ModelCatalogEntryCard` 收到的 `suppliers` 被我误改成过滤后的 `selectableSuppliers`，而它用该列表
   **显示历史来源的名称**并判定「仅拉取」。已禁止测试的供应商不在其中 → 卡片显示「已删除供应商」，
   用户会以为配置丢了。修法：卡片收**完整** `state.suppliers`（它只读不写）；是否可新增/可运行仍由
   卡片自己的策略判定。**这是本轮改动引入的回归**，属于必须修。

2. **能从「禁止测试」的供应商添加来源（两条路径）**。
   弹窗打开期间把当前供应商设为禁止测试时，`selectedSupplierId` 仍是过期值：
   ① `canAddSource` 只判 id 非空 → 按钮仍可点；② `onAddSource` 回调也直接用该 id。
   修法：两处统一改用 `selectedSupplier?.id`（对「当前仍可选」的派生值）。
   执行层本就会跳过这类来源，所以这不是「会发请求」，而是**留下一条永远跑不了的配置**。

**Required（本轮计划承诺未完成，已补）**

3. **高级测试仍被普通测试阻断**。`startUnifiedCatalogTests` 把 `_uiState.isRunning` 当门槛，
   界面上按钮可点、点下去静默无效——正是计划第 5 项要修的「不再因普通测试在跑而拒绝」。
   修法：移除 `isRunning` 条件，保留初始化/密钥/配置备份/目录拉取这些会让快照读半截的前置。
   新增测试：普通测试在途时高级来源测试仍发出请求；禁止测试的供应商仍被拒绝（0 请求 + SKIPPED）。

**Dead code / 一致性（已清理）**

4. **本轮改动后成为死代码的三个函数**：`roundBusy()`、`animationBusy()`、`refuseWhileAnimationRuns()`
   ——互斥解除后不再有调用点。已删除（删后再次全仓检索确认无引用）。
5. **仅剩测试在用的转发壳** `startAnimationBatchTest`：已把测试改调
   `startSelectedAnimationTests`，并删除该壳；测试名与断言文案同步去掉「批量」措辞。

**核查过、确认无问题的项（附依据）**

- 余额按供应商认领/释放：`balanceJobs` 只在主线程经 `balanceJobsMutex` 增删；批量只认领空闲 ID、
  逐个释放、`balanceBatchRunning` 防重复批量；单卡刷新只删自己的键。已被闸门式测试覆盖
  （三请求同时在途、结束无残留）。
- 动画并发计数器 `animationSessionSequence` / `animationRequestSequence` 均在 `viewModelScope`
  （Main）递增，并行启动不构成数据竞争。
- 拖动排序的槽位数学、自动滚动、钉窗逻辑未被本轮改动触碰；设备实测一次长按拖动即精确到位。
- 请求体仍不含任何关闭思考的字段（既有测试持续约束），指纹预算提升未引入新字段。
- 性能：新增的模型搜索是纯本地 `filter`，无请求、无 N+1；目录卡片仍按 entry 渲染一次。

**修复后质量门（重跑）**：`clean testDebugUnitTest lintDebug lintOptimized assembleOptimized --offline`
→ BUILD SUCCESSFUL；**753 测试 0 失败**（18.4 为 751，本轮审查新增 2 条高级测试）；
lint 无 issue。产物 APK 已重新生成，版本仍为 **1.7.20 / 10720**（改动均在 1.7.20 内，
未对外发布过任何 1.7.20 构建，因此不递增版本号）；新哈希见 18.8。

### 18.8 审查修复后的最终产物

- 测试：**753 测试 0 失败 / 0 错误 / 0 跳过**（含 18.7 新增的 2 条高级测试）。
- APK：`app/build/outputs/apk/optimized/app-optimized.apk`，**3137907 字节**，versionCode 10720 / versionName 1.7.20。
- SHA-256：`8e41979c882895a590f8fd03c13a959035a107f36fb8bb14a99aeec89e53799e`。
- 状态：**未发布**（未跑发布脚本、未改签名、未提交、未推送）。
- 版本号说明：18.7 的修复仍属 1.7.20 且该版本从未对外发布，故不递增版本号。

### 18.9 第二轮深度自审（2026-10-12）：再查出 2 处缺陷 + 1 处工具链误报

换角度复查（不看 diff，专找逻辑自相矛盾与跨功能串写），结果如下。

**Required — 普通测试被高级测试静默阻断（不对称，本轮遗留）**

`runningOrInitializing()` 把 `isUnifiedTesting` 也算作忙碌，而它有 **26 个调用点**，其中
`startTest()` / `retryFailed()` / `retestAll()` 三处是「启动测试」。上一轮我只放开了「高级测试
不被普通测试阻断」这一侧，反向仍是关闭的：高级测试运行期间点主界面「开始测试」，按钮可点
（UI 的 `enabled` 本就不含 `isUnifiedTesting`）却被 ViewModel 静默 return —— 与「所有测试可
并行」的要求直接矛盾。

修法：新增更窄的 `testStartRefused()`（只含 `isRunning` / 初始化 / 密钥读取 / 配置备份），
三处测试启动改用它；`runningOrInitializing()` 保持原样继续守住其余 23 处**配置编辑与持久化**，
因为它们读的是同一份 profiles/凭据快照，运行中放开会让编辑落到半改状态上。
新增 2 条测试：高级测试在途时普通测试仍发出请求；高级测试在途时切换供应商仍被拒。

**Required — 无效供应商启动会先清空勾选**

`startSelectedAnimationTests` 缺 `startAnimationTest` 有的供应商校验：供应商在面板开着时被删，
点击「开始生成」会先清掉全部勾选、再由首个模型报「供应商已不存在」——用户得重新勾一遍才知道
发生了什么，而该次点击一个请求都没发。修法：校验前置，被拒绝时保留勾选。
新增 1 条测试（0 请求 + 勾选保留 + 有提示）。

**FYI — 两次工具链失败，均与代码无关（已排除，非缺陷）**

1. `lintAnalyzeDebug` / `lintAnalyzeOptimized` 报 `NoClassDefFoundError:
   MutableCollectionMutableStateDetector`。这是 Android lint 的**增量缓存损坏**：本机
   `~/.gradle` 同时存在 `lint-checks 31.13.0` 与 `32.1.0`，worker 类加载时取到了不一致的组合。
   `./gradlew --stop` 后重跑即通过。任何排查此错误的人都值得知道：**先停 daemon，而不是改代码**。
2. `:app:clean` 报无法删除 `build/intermediates/lint-cache/**/*.jar`，原因是上一个后台 lint
   worker 仍占用文件。等进程退出后删除成功，非缺陷。

**核查过、确认无问题的项**

- 高级测试与普通测试的状态互不串写：`unifiedResults` / `unifiedTestingKeys` / `isUnifiedTesting`
  与普通测试的 `results` / `progressDone` 各成一套，无共享字段。
- 配置整体写入（`writeProfiles` / `persistBalanceSnapshots`）全部经 `configurationWriteMutex`
  串行；高级测试路径不写配置，并行不会互相覆盖。
- 动画两个序号计数器在主线程递增（并行启动无竞争）；余额按供应商认领/释放已被闸门测试钉住。

**修复后门禁（重跑）**：`testDebugUnitTest lintDebug lintOptimized assembleOptimized --offline`
→ **BUILD SUCCESSFUL**；**756 测试 0 失败 / 0 错误 / 0 跳过**（18.7 为 753，本轮 +3）。

### 18.10 最终产物（第二轮审查后）

- APK：`app/build/outputs/apk/optimized/app-optimized.apk`，**3 137 907 字节**，
  versionCode 10720 / versionName 1.7.20。
- SHA-256：`09f40900e87413d67b5e1face1cfba5423a393c28cc15555587575cf95384ae2`。
- 状态：**未发布**（未跑发布脚本、未改签名、未提交、未推送）。
- 提示：该 APK 与 18.8 同名同大小但**内容不同**（第二轮审查改了生产代码），请以哈希为准。
