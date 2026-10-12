# Relay Tester 修复方案

文档日期：2026-10-07（第 17.2.5–17.2.9 于 2026-10-08 追加）  
状态：**第 1–13 节为方案原文，保留不改；实际实施状态一律以第 17 节为准**。第 17 节逐项区分「已实施 / 已验证（有确定性测试或设备证据）/ 仍未验证」，未验证项不得当作已完成。  
审查基线：`7ae3fbda57c08b2540966360fb9903bcaa3bcd8c`

## 1. 目标与边界

本方案针对本地最新源码及该提交原样构建的 debug APK，不针对已发布的旧 APK。源码版本字段仍为 `1.7.3`，但受测产物是当前提交重新构建的 debug 包。

实施目标按以下顺序推进：

1. 修复 Responses 成功状态误判，保证普通模型测试与指纹检测遵循一致的协议语义。
2. 隔离指纹单题重试与新轮次，避免旧请求污染新结果。
3. 保存失败时保留高级目录草稿。
4. 修复窄屏大字号布局与余额凭据入口。
5. 补齐历史操作排序、模型选择弹窗和兼容性验证。
6. 独立新增“鹈鹕动画测试”：调用题卡对应模型生成 HTML，并在受限预览面板显示 SVG 动画，不改变指纹规则。

本轮审查没有修改业务代码、测试源码、ABI 或签名配置，没有提交或发布。所有网络功能验证使用本地假服务及假密钥，没有调用真实供应商。后续实施仍应使用隔离设备和假服务完成回归。

R10 是用户提出的新增能力，不属于审查发现的故障。本次增补仅更新文档和默认提示词，不实施功能、不构建、不运行模型请求或动画测试。提示词中的“无需测试”约束生成模型只交付 HTML，不删除 R01–R09 既有回归要求。

## 2. 基线与验证结果

| 项目 | 结果 |
|---|---|
| Git HEAD | `7ae3fbda57c08b2540966360fb9903bcaa3bcd8c` |
| 审查收尾工作树 | 无未提交改动；main 领先 origin/main 5 个提交 |
| Debug 构建 | 成功，收尾重跑退出码 0 |
| JVM 测试 | 29 个套件、513 个测试、0 失败、0 错误、0 跳过 |
| Debug Lint | 0 errors、11 warnings，以最后一次重跑结果为准 |
| 模拟器 | `ZCodeRelayReview:5560`，API 35，ADB 服务端口 5041 |
| 设备 ABI | x86_64，支持 arm64-v8a 翻译；原样 APK 可安装运行 |
| 构建差异 | 无源码、ABI、签名配置差异 |

APK：`app/build/outputs/apk/debug/app-debug.apk`

```text
SHA-256
c43ce9cb3064f2862894b296f58fb3b633487991c6e1b9c254757ce26f1e4c26
```

上述测试通过只证明已有测试通过，不代表本文列出的异常路径已覆盖。旧 optimized 测试及 Lint 产物早于本轮基线，不得混入当前 debug 验证结果。编写及增补本文只改动文档，未再次构建。

### 证据等级

- **模拟器复现**：观察到实际界面结果，必要时结合假服务日志确认请求路径。
- **源码可确认**：控制流或状态契约存在明确缺口，但不代表最终用户故障已在设备复现。
- **待验证风险**：需要确定性测试或专门设备测量后确认影响与严重性。

P1：优先处理的正确性或数据保留问题。P2：后续处理的交互问题或待验证风险。P3：语义一致性及体验改进。

## 3. 修复与新增功能总览

| 编号 | 优先级 | 任务 | 证据状态 |
|---|---|---|---|
| R01 | P1 | Responses 状态及流终态判定 | 已实施 / 已验证（见 17.1） |
| R02 | P1 | 指纹重试跨轮隔离 | 已实施 / 已验证（见 17.1） |
| R03 | P1 | 保存失败保留目录草稿 | 已实施 / 已验证（见 17.1） |
| R04 | P2 | 窄屏大字号操作区布局 | 已实施 / 已验证（320dp + 1.6 设备观察） |
| R05 | P2 | 当前供应商余额凭据入口 | 已实施 / 已验证（设备观察） |
| R06 | P2 | 历史追加与清空顺序 | 已实施 / 已验证（见 17.1） |
| R07 | P2/P3 | 模型弹窗搜索与长列表 | 已实施 / 已验证 |
| R08 | P3 | 仅拉取来源状态与进度语义 | 已实施 / 已验证 |
| R09 | 支持相应设备前必验 | QuickJS 16KB 页兼容性 | 已实施（文档）/ **限制保留，未修复** |
| R10 | 独立新增功能 | HTML / SVG 动画测试与受限预览 | 已实施 / **已验证**（首轮仅到「单元与源码验证」；端到端设备走查已完成，见 17.2.1–17.2.5） |

> 最新实施与验证结论以第 17 节为准；第 3 节仅保留原始优先级与任务划分。

## 4. R01：修复 Responses 成功误判

### 触发与现象

1. 普通测试收到 HTTP 200，响应包含 `status: failed`、`error` 和非空 `output`，界面仍计为可用。
2. 指纹 Responses 流只返回 `response.output_text.delta` 后直接 EOF，没有完成事件，仍进入有效回答统计与评分。
3. stream 400 后 buffered 回退路径已验证发生；回退得到文本可以评分，但回退后的失败状态判定还需专门回归。

模拟器中四个假模型普通测试结果为“可用 4、不可用 0”，其中 `mock-failed` 明确返回失败状态。`mock-eof` 指纹三题得到“有效 3/3”和候选结果。`incomplete`、`response.failed` 等其他终态尚未逐项设备复现。

### 根因与位置

以下路径相对项目根目录，行号对应审查基线：

- `app/src/main/java/com/relaytester/app/core/network/RelayApi.kt:513`：非空 output 可以满足普通测试成功条件，没有先处理失败状态。
- 同文件 `:175`：非流式 completion 提取非空文本后返回成功。
- 同文件 `:239`：流式与回退入口需要共享状态规则。
- 同文件 `:376`：SSE 读取只累计文本，EOF 和 `[DONE]` 都结束读取，没有保留 Responses 终态。

### 最小改法

1. 在网络层建立 Responses 专用的终态判定，区分 `completed`、`failed`、`incomplete`、未确认完成。不要只依赖文本是否存在。
2. 明确失败或未完成时保留诊断文本，但不将其作为完整成功回答送入正式指纹评分。
3. 普通测试、流式 completion、非流式 completion 和回退共用该状态规则，避免只修一个调用入口。
4. 流式处理分别记录文本增量和协议状态；错误事件不能被“已有文本”掩盖。明确处理终态、传输中断及格式错误。
5. 对缺失状态、仅有 `[DONE]` 的 Responses 兼容响应采用明确策略：默认不标记为协议已完成；若后续确需兼容，必须单独表达状态，不得静默与正式完成混用。
6. 保留 Chat 协议现有 EOF 兼容性，不要把 Responses 规则强加给所有协议。

现有 `RelayApiStreamingTest.kt:177` 明确测试 Chat 在无 `[DONE]` EOF 时返回已接收文本。这项行为是兼容性约束，不应因本修复被一刀切删除。

### 先补的回归用例

| 输入 | 预期 |
|---|---|
| Responses JSON：failed + 非空 output | 失败，不计可用 |
| Responses JSON：incomplete + 非空 output | 明确未完成，不作完整指纹回答 |
| Responses JSON：completed + 合法文本 | completion 成功 |
| Responses SSE：delta 后 EOF | 不确认完成，不出完整成功结论 |
| Responses SSE：delta 后 response.failed | 失败，已有文本不覆盖错误 |
| Responses SSE：delta 后 response.incomplete | 明确未完成 |
| Responses SSE：正常 response.completed | 正常完成 |
| stream 400 后 buffered failed + output | 回退后仍失败 |
| stream 400 后 buffered completed + 文本 | 正常回退成功 |
| Chat delta 后 EOF | 保持原有兼容行为 |

普通模型测试和指纹 completion 的成功条件不必完全相同：前者是连接/协议测试，后者必须得到可评分文本；共享的是终态解释，不能借此意外改变各自的文本要求。

### 验收标准

- [ ] HTTP 200 不能掩盖 Responses 失败状态。
- [ ] 传输 EOF 不会被自动当作 Responses 正常完成。
- [ ] 三个网络入口和 buffered 回退均有回归覆盖。
- [ ] 鉴权、额度、模型不存在等现有非回退行为保持稳定。
- [ ] 普通测试汇总和指纹最终状态与协议结果一致。

## 5. R02：隔离指纹单题重试与轮次

### 触发与风险

主轮结束后启动单题 retry，在请求返回前重新生成题目、切换供应商、切换模式、改变模型选择或开始新主轮。旧 retry 仍可能按原索引更新当前状态、写入缓存、分析和历史。

当前证据为源码生命周期缺口；尚未通过可控调度复现最终结果污染。

### 根因与位置

`app/src/main/java/com/relaytester/app/feature/fingerprint/FingerprintViewModel.kt`：

- `:555`：`runInFlight()` 不包含 retryJobs。
- `:768`、`:777`、`:868`、`:881`：上下文切换只检查主轮运行标志。
- `:919`、`:944`：重新出题/丢弃上下文不取消旧 retry。
- `:972`、`:1019`：retry 使用捕获的旧 credentials/template。
- `:1326`：partial 和最终结果按索引写回，缺少轮次身份检查。
- `:1505`：`cancelRun()` 只取消主轮 Job，不覆盖独立 retryJobs。

`FingerprintScreen.kt:1508` 在主轮不运行时允许重新生成题目，因此该 UI 路径并未被 retry 状态禁用。

### 最小改法

1. 增加递增轮次标识，捕获供应商、模型与题目身份，不能只用列表索引认领结果。
2. 上下文失效时先使旧标识失效，再取消关联 retry 并清理该轮状态。
3. 所有 partial、最终答案、缓存、分析和历史写入前检查归属。
4. Job 完成回调只清理自己登记的任务；相同 model/index 的新任务不应被旧完成回调移除。
5. 明确取消主轮与取消本轮所有请求的行为，UI 与实现保持一致。
6. 审查 `refreshCatalogue` 和检测入口等其他会替换上下文的路径，一并接入同一失效规则。

单纯取消 Job 不足以替代写回检查；单纯把所有控件永久锁到 retry 完成也会改变交互，应优先保证结果归属正确。

### 先补的回归用例

使用可手动放行的假 API，而不是依赖 sleep：

1. 完成主轮并制造一题失败。
2. 启动该题 retry，挂起响应。
3. 分别执行重新出题、切供应商、切模式、改变模型、开始新主轮。
4. 放行旧 partial 和旧最终响应。
5. 断言新 progress、modelSlots、候选、历史、queuedRetries 均未被旧请求改变。
6. 验证同一轮合法 retry 仍能成功补题，重复点击不会产生多余请求。

### 验收标准

- [ ] 旧请求不会写入新轮次任一状态容器。
- [ ] 新轮任务不会被旧 Job 的清理回调删除。
- [ ] 取消、切换、刷新后无残留“排队中/请求中”。
- [ ] 同轮重试、取消后重新检测保持可用。

## 6. R03：保存成功后再清空目录草稿

### 触发与风险

高级目录保存遇到异步写盘失败、目录容量限制、目标失效等情况时，编辑草稿已经被 UI 清空，用户无法直接修正或重试。部分输入错误已被 UI 按钮禁用挡住，不应宣称所有 ViewModel 拒绝条件都能从界面触发。

### 根因与位置

- `app/src/main/java/com/relaytester/app/feature/tester/TesterScreen.kt:3464`：调用保存后立即执行 `resetDraft()`。
- `app/src/main/java/com/relaytester/app/feature/tester/TesterViewModel.kt:883`：校验可能直接拒绝，持久化异步执行且可能失败；失败只回滚目录状态。
- 同文件 `:926`：`next == previous` 同时覆盖“未改动”和“未找到目标”，错误信息不准确。

### 最小改法

1. 建立显式保存结果，包含请求标识或草稿版本及成功/失败状态。
2. 仅在持久化成功且当前草稿仍属于该请求时重置草稿。
3. 保存期间防重复提交；失败保留编辑 ID、名称和全部来源，并显示错误。
4. 将未改动保存作为成功无变化或明确无变化反馈，与目标已不存在分开。
5. 确认结果与编辑会话绑定，避免关闭后重开或切换另一条草稿时被晚到的成功事件清空。

### 先补的回归与验收

- [ ] 注入保存异常：目录状态回滚，草稿原样保留，可再次保存。
- [ ] 注入可到达的同步拒绝：草稿保留、错误明确。
- [ ] 编辑项已被删除：不给出假成功，不清空用户输入。
- [ ] 保存期间换草稿或重开弹窗：旧结果不清理新草稿。
- [ ] 未改动保存不显示“未找到要更新的模型来源”。
- [ ] 成功保存只提交一次并只清理对应草稿。

需要同时覆盖 ViewModel 存储行为和 Compose 草稿行为，仅测试存储回滚无法证明 UI 保留输入。

## 7. R04：窄屏大字号布局

### 已复现现象

模拟器参数为 880×1800 像素、density 440、font_scale 1.6，即有效宽度 320dp。高级目录底部“保存模型来源”被压成三行，操作栏变高并挤压正文。

余额页同条件下两张卡片名称均截成“供应…”，右侧总数也截断。余额页尚未完成窄屏图标点击验收，不能据此判定页面完全无法操作。

证据截图：

[高级目录 320dp / 字号 1.6](E:/AI/Zcode/tmp/relay-tester-review/evidence/catalog-320dp-font160.png)

### 定位与最小改法

- `app/src/main/java/com/relaytester/app/feature/tester/TesterScreen.kt:3440`：底部关闭、添加来源、保存固定在一行。
- 窄宽度或大字号时，将主保存动作独占一行，次要操作另排一行；正文保持独立滚动。
- 余额卡根据有效宽度和字体空间降为单列或提供完整名称入口，避免不同供应商无法区分。
- 不通过缩小字号、压缩系统字体缩放来掩盖布局问题。
- 高级目录既有 40dp 紧凑动作控件是已有设计取舍，未获进一步设计确认前，不把所有紧凑控件统一改成另一套尺寸。

### 验收矩阵

| 维度 | 场景 |
|---|---|
| 有效宽度 | 320dp、360dp、常规手机宽度 |
| 字号 | 1.0、1.3、1.6 |
| 方向 | 竖屏、横屏 |
| 内容 | 长名称、多来源、错误信息、模型名截断 |
| 输入状态 | 键盘关闭、键盘打开、焦点在最后一个字段 |

- [ ] 主保存动作文字完整可辨认，操作区不覆盖正文。
- [ ] 所有字段、列表项和底部操作均可滚动到达。
- [ ] 系统导航区和键盘不遮挡关键按钮。
- [ ] 两个供应商可区分，完整名称有可用入口。
- [ ] 指纹三题卡在成功、失败、重试排队状态下可辨认且能操作。

本轮没有完成横屏三题卡全状态走查；不得把横屏页面能够打开等同于该矩阵全部通过。

## 8. R05：余额凭据入口与切站锁分离

### 已复现现象

本地假模型 `mock-completed` 非流式请求延迟 16 秒。普通模型测试运行期间，点击当前活动供应商余额凭据图标，弹窗不打开，并显示“模型测试正在运行，暂时不能切换供应商”。空闲时同入口可打开。

### 根因与位置

`app/src/main/java/com/relaytester/app/feature/balance/BalanceScreen.kt`：

- `:147`：运行和初始化状态合并为 siteSwitchLocked。
- `:171`：统一阻止供应商切换。
- `:307`：即使编辑当前供应商，也先调用 onSelectSupplier。

### 最小改法

1. 当前供应商打开凭据时不再走切站回调。
2. 其他供应商继续遵守切换锁，只有激活成功才打开对应凭据。
3. 查看、编辑、保存分别表达限制；如运行期间不允许保存，应禁用保存并给准确原因，而不是以切站失败阻断查看。
4. 保持进行中请求使用其已捕获的凭据快照；不要因打开弹窗改变当前请求配置。

### 回归与验收

- [ ] 普通测试和高级测试运行中，当前供应商入口可按既定查看策略打开。
- [ ] 其他供应商切换仍被正确阻止，没有延迟弹出错误对象的弹窗。
- [ ] 打开、取消凭据弹窗不改变活动供应商或进行中请求。
- [ ] 若保存受限，按钮及提示准确表达保存限制。
- [ ] 空闲状态编辑保存、切换失败、初始化状态均有覆盖。

## 9. R06：历史追加与清空按发起顺序执行

### 风险与定位

`app/src/main/java/com/relaytester/app/feature/fingerprint/FingerprintViewModel.kt:1259` 分别启动 append 和 clear 的异步任务，并覆盖 historyJob。`FingerprintHistoryStore` 的方法级 `@Synchronized` 只保证单次操作互斥，不保证按用户发起顺序获得执行机会。

已有 `FingerprintProgressAndHistoryTest.kt:341` 覆盖静态清空，但不覆盖待执行追加与清空交错。本项尚未确定性复现，先补测试再决定最终改动范围。

### 建议语义与最小改法

- 清空之前已排队的写入不应在清空之后让旧记录重新出现。
- 清空之后新完成的检测可以作为新记录写入。
- ViewModel 使用同一串行命令队列处理历史操作，入队发生在操作发起时，避免在 IO 内才竞争顺序。
- 写盘结果和 UI 更新遵守同一顺序；awaitIdle 应能等待所有已提交历史操作，而不是仅最后一个 Job。
- 若采用代次屏障替代队列，必须明确保护磁盘与 UI 两端；仅忽略 UI 回调不能阻止旧数据写回磁盘。

### 先补的确定性回归

1. 挂起先发起的 append，让随后 clear 具备先进入 IO 的机会。
2. 放行全部任务，检查磁盘与 UI 都无清空前旧记录。
3. 清空后执行新 append，确认新记录保留。
4. 覆盖连续 clear、append/clear/append 以及写盘失败反馈。

验收以确定性调度测试为准，不依赖手动快速点击碰概率。

## 10. R07：模型弹窗内搜索与长列表

### 证据与定位

`app/src/main/java/com/relaytester/app/feature/fingerprint/FingerprintScreen.kt:1006` 的筛选字段位于页面上，`ModelListDialog` 是模态弹窗。`:1101` 注释称弹窗打开后外部字段仍可输入，与实际交互结构不一致。`:1128` 使用 `Column.verticalScroll` 和 `matches.forEach` 全量组合。

小规模四模型列表可用；1,000 项列表的启动耗时和滚动性能未测量，不能直接写成已复现卡顿。

### 最小改法与验收

- 将筛选输入放到弹窗内，或明确要求关闭后筛选；推荐前者以减少往返操作。
- 使用带稳定 key 的 LazyColumn，保留已有勾选顺序、清空、未匹配手填模型入口。
- 修正注释，避免注释承诺不存在的交互。
- [ ] 1,000 个假模型能在弹窗内筛选、选择、清空。
- [ ] 搜索不意外删除已选模型，关闭重开后选择顺序稳定。
- [ ] 旋转、字号增大后输入框与完成按钮可达。
- [ ] 记录改动前后打开耗时与滚动观察，不能只凭使用 LazyColumn 宣称性能改善。

## 11. R08：仅拉取来源采用跳过语义

### 已纠正结论

混合来源测试中，界面正确显示“仅拉取”，对应按钮禁用，而且该来源没有网络请求。没有证据证明它在界面显示失败、污染普通测试失败计数或导出。

内部 `TesterViewModel.kt:1173` 返回 `FAILED/TESTING_DISABLED`，`TesterScreen.kt:3388` 的进度总量使用所有来源。普通结果导出消费的是另一组 state.results，不能把两条数据路径混为一谈。

### 最小改法与验收

本项为 P3，放在核心正确性修复之后：

- 给高级目录来源使用明确的跳过/不可测试状态，避免内部将策略禁止混为网络失败。
- 区分实际测试数与跳过数，进度分母的文案和含义一致。
- [ ] 可测 + 仅拉取混合条目只发送可测请求。
- [ ] 全部仅拉取时整体测试入口禁用。
- [ ] 单来源测试时不会把其他未测试来源误称为已测试成功。
- [ ] 不改变普通测试导出范围，不引入没有消费方的多余统计字段。

## 12. R09：原生库及其他 Lint 告警处理

最新 debug Lint：0 errors、11 warnings。

| 类型 | 实例数 | 处理建议 |
|---|---:|---|
| AndroidGradlePluginVersion | 1 | 版本提示，单独评估升级 |
| GradleDependency | 1 | 版本提示，单独评估升级 |
| NewerVersionAvailable | 4 | 不因提示批量升级依赖 |
| Aligned16KB | 3 | QuickJS 16KB 页兼容性需专项验证 |
| ChromeOsAbiSupport | 1 | 结合实际支持平台决定，不为模拟器随意改正式 ABI |
| InsecureBaseConfiguration | 1 | 自定义 HTTP 供应商能力与安全默认值需单独设计 |

QuickJS 定位：`app/build.gradle.kts:188`，依赖 `io.github.taoweiji.quickjs:quickjs-android:1.4.6`。告警指向 `arm64-v8a/libquickjs-android.so` 的 16KB 对齐问题，3 条实例不代表 3 个独立故障。

实施步骤：先在 16KB 页目标设备检查库加载和实际脚本执行，再评估有相应支持的依赖版本或重新构建原生库。升级必须验证脚本能力、异常处理与原有设备兼容性。未完成该验证前，不宣称支持 16KB 页设备。

明文网络告警不应简单通过全局禁止 HTTP 来消除，否则会破坏用户配置本地或兼容中转服务的能力。若调整，需明确 HTTPS 默认、HTTP 显式提示及迁移行为。本轮未实施这一产品策略变更。

## 13. R10：鹈鹕 HTML / SVG 动画测试（新增功能）

### 目标与独立性

在指纹检测“三道题目”的每个模型卡标题行增加“动画测试”按钮。点击后立即打开带提示词编辑区的面板；用户确认或修改提示词并点击“开始生成”，以该卡片对应的供应商和模型生成 HTML / SVG 2D 动画，完成后直接显示动画。默认题目为“鹈鹕骑自行车”，用户可以编辑为其他 SVG 动画题目。

本功能用于观察模型的代码生成及动画表现，不是模型身份认证，也不是指纹第四题。不得修改三题答案、有效回答计数、候选排序、置信度、指纹历史或普通模型测试结果。动画不好看、页面无法运行与供应商协议失败应分别表达，不自动折算为指纹分数。

### 入口与面板交互

- 入口位置：`app/src/main/java/com/relaytester/app/feature/fingerprint/FingerprintScreen.kt:1598` 的 `ChallengeBlock`，按钮放在模型名之后、`ModelStatusChip` 之前。现有标题 Row 约在 `:1614`，多模型块调用约在 `:1550`；行号对应审查基线。
- 模型名仍保留省略能力；按钮与状态不能重叠。在 320dp、大字号或长模型名场景下，允许状态移到下一行，按钮保持模型名后的阅读顺序和足够触控区域。不通过缩小系统字号解决空间不足。
- `model == null` 的占位三题块不显示可调用按钮。无法解析卡片原供应商、模型或有效凭据时，不发送请求并给出明确原因；不能静默改用当前选中的其他供应商。
- 点击后先打开接近全屏的原生面板，标题显示“动画测试”、供应商与完整模型名；正文先显示可编辑提示词及“开始生成”，不因打开面板自动请求。提交后立即显示“生成中”和取消入口，完成后自动切到预览。
- 面板提供“提示词”编辑区，默认填充下方优化提示词。用户可以修改本次请求内容，编辑区应支持滚动、长文本和恢复默认；不要把提示词做成只能复制的静态说明文字。
- 提示词草稿与动画会话绑定。编辑内容后可主动“重新生成”，重新生成使用提交时的当前草稿；生成中的草稿变化不应偷偷改变已经发出的请求。当前指纹页面会话内保留最后一次草稿，关闭重开或旋转不丢失；首版不做跨应用重启持久化，也不存入指纹历史。提供“恢复默认”，只重置草稿，不清空已有预览、不自动发送请求。旧会话的晚到结果不得覆盖新会话。空白提示词禁用生成并给出提示。
- 重新播放只重载已有预览 HTML，不调用模型。修改提示词不会自动调用模型；重新生成才发起新请求，动作语义及可能产生的 API 费用需要明确，不能由重组、旋转或切换视图自动触发。
- 成功后默认进入“预览”，另有“源码”视图。提供关闭、重新播放、重新生成、复制源码；源码作为纯文本展示，不通过 WebView 执行。
- 生成中禁止重复提交；取消后面板显示已取消并允许用户主动重试。关闭面板、系统返回或离开指纹页面时取消关联生成并释放预览资源。
- 首版只允许一个动画生成会话。三题主轮或单题重试进行中时禁用新动画请求；动画生成期间阻止启动三题请求或改变该请求上下文。已生成页面的播放不占用模型请求锁。

### 默认提示词（可编辑，直接发送给模型）

以下文本整体作为默认用户提示词发送，不追加数字题格式要求或指纹回答约束。用户可以在面板中编辑后再发送；以下是默认值，不是不可修改的系统指令。保留主体题目，不限定颜色、构图或具体实现细节，以便观察模型自身的生成表现。

```text
创建一个完整、可直接在浏览器中打开的 HTML 页面，内容是使用内联 SVG 绘制“鹈鹕骑自行车”的 2D 动画。

要求：
1. 鹈鹕和自行车清晰可辨。鹈鹕具有明显的长喙、喉囊和翅膀，并自然地坐在自行车上骑行。
2. 动画持续、平滑循环；车轮旋转、踏板转动与腿部踩踏协调，鹈鹕可以有自然的身体或翅膀动作。画面不能只是静态图片。
3. 核心画面使用 SVG 绘制，可使用内联 CSS 和必要的 JavaScript 实现动画。所有内容放在同一个 HTML 文件中，包含 <!DOCTYPE html>、html、head、body、UTF-8 编码声明和 viewport 设置。
4. 不依赖外部资源：不加载第三方库、字体、图片、音视频或其他文件，不发起网络请求，不使用 iframe、弹窗、页面跳转、下载、浏览器存储或设备权限。
5. 使用合适的 SVG viewBox 和响应式布局，适配手机竖屏、横屏及桌面预览区域，主体完整可见，不出现横向溢出。
6. 只返回完整 HTML 源码，从 <!DOCTYPE html> 开始到 </html> 结束。不要使用 Markdown 代码围栏，不要附加解释、步骤或其他文本。
7. 无需编写或执行任何测试，不返回测试代码。
```

### 模型调用、状态与结果归属

1. 在 `FingerprintViewModel.kt` 或其同功能组件中建立独立动画状态与 Job，不复用三题的 progress、modelSlots、retryJobs、roundAnswers、analysis 或 historyJob。状态至少区分待编辑、生成中、可预览、失败、已取消，并分别保存可编辑草稿、本次提交的提示词快照、原始源码及必要错误信息。已有预览绑定生成它的提示词快照，后续草稿修改不改变该归属。
2. 卡片只传递可识别的供应商/模型上下文；ViewModel 在发起时解析原供应商并捕获端点、协议、模型及凭据快照。密钥只用于应用网络层，不能放进 Compose 预览状态、HTML、WebView、剪贴板或日志。
3. 建立递增请求标识和面板会话标识。partial、最终响应及完成回调都校验所属会话；关闭、取消、替换请求先使旧标识失效，再取消任务，防止晚到响应覆盖新面板。只取消 Job 不足以替代归属检查。
4. 复用适合文本生成的网络能力和 R01 修正后的协议终态规则，但使用独立的 HTML 输出长度与超时限制，不复用数字题的 token 配额、解析或有效数字门槛。超过限制、协议失败或响应截断时明确失败，不自动拼接或静默重发计费请求。
5. 生成中可显示接收状态或纯文本进度，不把尚未完成的 HTML 逐段送入 WebView。仅在请求成功且完整文档结构初检通过后加载一次预览。
6. 仅去除回答最外层、覆盖整个文档的 Markdown 围栏和外围空白；保留原始返回文本供查看。拒绝空文本、明显非 HTML、缺失 SVG 或已知截断，不在解释中随意截取片段并当作完整页面。结构初检只能用于友好报错，不能作为安全保证或动画质量判定。
7. 失败原因在面板内显示，例如鉴权失败、超时、协议未完成、HTML 不完整、页面渲染失败。已有有效预览在重新生成失败后仍可恢复查看；任何失败都不能改变三题检测结果。

### 受限 WebView 预览

使用 Compose `AndroidView` 承载 Android WebView。生成内容一律视为不可信：提示词要求禁止联网只是输出约束，实际限制必须由应用实施；WebView 不应被描述为能够安全执行任意代码的完全沙箱。

- 允许支持动画所需的 JavaScript，但不注册 `addJavascriptInterface` 或其他原生消息桥，不注入密钥、应用对象或供应商 URL。源码视图使用原生纯文本组件。
- 禁止文件与 content 访问、file URL 跨域访问及通用访问；不使用本地文件 URL 或应用资产作为页面基址。HTML 通过内存加载，采用固定、不含凭据且不属于供应商的隔离基址。
- 禁止外部网络加载，并在请求拦截层拒绝非允许资源。预览层注入由应用控制的 CSP，至少禁止 connect、外部脚本/样式/字体、frame、object、form、worker、外部媒体及基址改写，只允许动画需要的内联脚本和样式。策略不由模型提供，也不依赖正则清理脚本来保证安全。
- 顶层和子框架导航均按允许列表拦截；拒绝外部 URL、intent、自定义 scheme、新窗口和下载，不转交系统浏览器或其他应用。拒绝权限、文件选择、地理位置和 JavaScript 弹窗请求。
- 关闭 DOM 存储，不提供登录、Cookie 或共享应用会话，不复用其他业务的登录 WebView；不要把这些设置描述为隔离了进程内所有缓存或全局状态。
- 原生关闭、返回和错误区域放在 WebView 外，避免生成页面伪造应用控件。切后台或离开预览时暂停动画；销毁时停止加载、清理回调并释放 WebView。渲染进程终止时显示原生错误并允许重新播放。
- HTML 长度、生成超时和预览生命周期需要明确上限。脚本可能消耗大量 CPU/内存，网络与权限限制不能消除这一风险；不得以“已加载”代替实际可见、可关闭的验收。

### 实施验收标准（尚未执行）

本次只加入方案，不运行以下验收，也不调用真实模型。后续实现时使用本地假服务与固定 HTML 样例检查应用流程；真实模型生成质量的观察需另获供应商调用授权。提示词中的“无需测试”不要求生成模型编写测试，也不替代应用安全和生命周期验收。

- [ ] 每个有明确上下文的模型卡有独立入口，点击即显示正确供应商、模型及可编辑提示词；开始生成后立即显示进度，成功后自动预览。
- [ ] 多模型、长名称、320dp/大字号和横竖屏下，模型名、按钮、状态与面板控制均可辨认、可点击。
- [ ] 默认提示词可编辑、可滚动、可恢复默认；仅点击“开始生成/重新生成”发送当前草稿，打开面板、编辑、恢复默认或重新播放不发送请求；空白草稿不可提交，旋转及当前页面内关闭重开不丢草稿。
- [ ] 完整 HTML / SVG 样例能显示动画；预览/源码切换、复制及重新播放均不增加模型请求；重新生成只产生一次新请求。
- [ ] 取消、关闭、离页、重新生成、上下文失效及旋转不会因晚到响应污染新面板，也不会自动重复调用模型。
- [ ] 超时、协议失败、非 HTML、缺失 SVG、截断与渲染错误均有可恢复反馈，不计入指纹结果。
- [ ] 固定恶意样例不能访问网络、本地文件、content URI、应用凭据或原生桥，不能打开外部应用、新窗口、下载或获得权限。
- [ ] WebView 暂停与销毁正确；三题答案、有效计数、候选、置信度和指纹历史在动画会话前后保持不变。

## 14. 指纹规则保持项

以下内容不应作为缺陷误修：

1. 最低有效数字数由 `minimumNumbersFor` 决定，包含检测包最低值与约 55% 的请求长度门槛。本轮 183 个数字超过三题最低值，因此“183 个一定不足”的判断不成立。
2. “有效 3/3”表示三条回答达到规则门槛，不表示收到每题全部请求数字，也不表示上游正常结束。R01 要补的是协议完成状态，不能用随意抬高数字门槛替代。
3. `SharedScoring.kt:113` 使用温度参数校准闭集候选权重。闭集百分比不能解释成开放世界中的模型身份认证概率。
4. `FingerprintScreen.kt:2060` 已有“仅为库内最接近候选，非身份证明；相近版本需谨慎”的提示。应保留该提示，不能宣称界面完全没有说明。
5. 假回答得到 91% 只证明程序产出了闭集排序，不证明模型身份或真实检测准确率，也不能单凭这次假服务结果修改评分权重。

## 15. 实施顺序与交付拆分

建议每个阶段独立提交，便于回归和回退；本文不执行提交。

| 阶段 | 范围 | 必须提供的交付 |
|---|---|---|
| A | R01 协议判定 | 网络层失败回归、三个入口及回退覆盖、假服务复测 |
| B | R02 重试轮次 | 可控调度回归、状态归属约束、取消/切换复测 |
| C | R03 保存确认 | 存储失败与 UI 草稿回归、成功/失败反馈 |
| D | R04、R05 交互 | 布局矩阵截图、入口运行锁回归 |
| E | R06、R07、R08 | 历史排序回归、弹窗交互/性能记录、跳过语义 |
| F | R09 兼容性 | 16KB 页专项报告；依赖升级如需实施则独立提交 |
| G | R10 独立新增功能 | 默认提示词、可编辑草稿、卡片绑定与面板流程、受限 WebView、请求归属及本地样例验收记录 |

修复阶段 A–F 遵循：先编写能暴露问题的测试并确认失败，再修改实现，运行针对性回归，最后执行必要的公共门禁。不要把新增失败测试未通过解释为修复完成，也不要把为新语义调整的测试直接算作证明原问题存在。

R10 单独交付，不与 R01–R09 的故障修复混成一个变更。优先完成 A–D，并复用 R01 协议判定、R02 请求归属和 R04 自适应布局约束；E/F 不需要为动画功能合并升级。WebView 不依赖 QuickJS，不把 R09 原生库升级作为动画实现前置。本次文档更新不执行任何阶段或验收。

## 16. 公共验收与执行命令

在项目根目录执行，沿用本机已有 JDK/SDK 和隔离 Gradle 缓存。以下 PowerShell 示例显式将临时文件限制在工作区：

```powershell
$env:JAVA_HOME = 'C:/Program Files/Java/jdk-17'
$env:ANDROID_HOME = 'E:/AI/Codex/android-sdk'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:GRADLE_USER_HOME = 'E:/AI/Zcode/tmp/relay-tester-review/gradle-home'
$env:TEMP = 'E:/AI/Zcode/tmp/relay-tester-review'
$env:TMP = $env:TEMP
Set-Location 'E:/AI/Zcode/projects/relay-tester'

./tools/build-android.ps1 :app:assembleDebug :app:testDebugUnitTest --rerun :app:lintDebug --console=plain
```

本机本轮使用仓库已有的 Gradle 回环兼容脚本；不要为此改业务代码或发布脚本。新增测试后测试总数应相应增加，验收重点是零失败及必要路径覆盖，而不是固定维持 513。

- [ ] 记录实施后的 HEAD、工作树状态、APK 哈希及构建差异。
- [ ] 每个修复具备原行为失败、修复后通过的针对性回归证据。
- [ ] Debug 构建、单元测试与 Lint 完成，新增告警有解释。
- [ ] 在隔离设备复测普通模型测试、指纹、目录保存与余额入口。
- [ ] 若交付 R10，单独记录默认提示词、可编辑草稿、动画面板流程、本地安全样例与取消/晚到响应验收；不把动画表现计入指纹评分或既有审查通过结果。
- [ ] 不发送真实密钥，不调用真实供应商，不记录 Authorization 原文。
- [ ] 不借用或修改其他项目模拟器；显式使用 ADB 5041 / emulator-5560。
- [ ] 显示设置变更前记录原值，测试后恢复。
- [ ] 停止本轮假服务前核对命令行和端口归属，不根据过期 PID 杀进程。
- [ ] 若需要 optimized/release 验收，重新执行对应门禁，不能复用旧报告。
- [ ] 修复提交与发布分开处理；本文不构成发布或签名配置修改授权。

## 17. 本轮实施结果（2026-10-07）

本节记录 R01–R10 的实际实施与验证状态，覆盖第 2 节的审查期基线。术语：**已实施**＝代码已改；**已验证**＝有确定性测试或设备观察证据；**未验证**＝无验收证据，且如实保留。

### 17.1 逐项状态（2026-10-07 首轮快照）

> **本表是首轮（10-07）的状态快照，其中的套件计数已过期，勿当现状引用。** 现状见 17.2.5–17.2.10：R10 的**端到端设备走查已完成**（17.2.1–17.2.5 多轮实机走查，含预览渲染与脚本执行证据），动画回归套件已由 33 条增至 56 条，指纹套件 106 → 108 条，全库 **617 条**。R09 的「限制保留」结论未变。


| 编号 | 状态 | 验证证据 |
|---|---|---|
| R01 | 已实施 / 已验证 | `RelayApiResponsesTerminalTest`、`RelayApiStreamingTest` 全通过；`ResponsesTerminal` 区分 COMPLETED/FAILED/INCOMPLETE/UNCONFIRMED |
| R02 | 已实施 / 已验证 | `FingerprintViewModelTest` 106 通过（含跨轮污染、同键重试去重、取消清理三条） |
| R03 | 已实施 / 已验证 | `TesterPanelRegressionTest` 21 通过；`catalogSaveResetsDraft` 纯函数覆盖成败两路 |
| R04 | 已实施 / 已验证 | 设备观察：320dp/fontScale 1.6 下 `maxWidth=320.0.dp stack=true`，余额卡单列、头部堆叠、名称整行可读 |
| R05 | 已实施 / 已验证 | 设备观察：当前供应商铅笔直接打开"余额查询凭据"，未触发切站锁 |
| R06 | 已实施 / 已验证 | `enqueueHistoryOperation` 串行链由指纹套件覆盖，`awaitIdle` 汇合尾部 |
| R07 | 已实施 / 已验证 | 弹窗内搜索 + `LazyColumn(key = { it })`；`ModelSelectionTest` 通过 |
| R08 | 已实施 / 已验证 | `TestStatus.SKIPPED` + `TestRunSummary.skipped`；设备观察仅拉取来源显示为已跳过而非失败 |
| R09 | 已实施（文档）/ **限制保留** | Lint `Aligned16KB` 仍告警：`libquickjs-android.so`（quickjs-android 1.4.6）非 16KB 对齐。**未做修复，不得宣称支持** |
| R10 | 已实施 / **已验证**（首轮写此表时仅到「部分验证」；端到端走查已于 17.2.1–17.2.5 完成） | 单元回归 56 条（首轮 33）；受限 WebView 逐项配置见源码；面板外观另有源码契约测试；**端到端设备走查已完成**——两层弹窗、默认提示词、生成/取消/重新播放、**预览实际渲染红圆并执行内联脚本**（17.2.5） |

### 17.2 R10 验证的边界（如实记录）

已验证：
- `AnimationTestRegressionTest` 33/33 通过（提示词逐字、空白拒绝、快照绑定、会话隔离、单会话约束、CSP 注入、重新播放不重发）。
- 沙箱限制在源码中逐条落地并可静态核对：无 `addJavascriptInterface`、禁止 file/content 访问、`blockNetworkLoads`、固定 `https://animation.invalid/` 基址、`shouldInterceptRequest` 拒绝一切子资源、`shouldOverrideUrlLoading` 拒绝一切跳转、无新窗口/下载/权限/JS 对话框、`onRenderProcessGone` 原生恢复。
- 设备侧：debug 包在 `emulator-5560` 正常启动；本地假服务（`127.0.0.1:8099`）经 `10.0.2.2` 可达，`GET /v1/models` 返回 2 个模型、`POST /v1/messages` 返回固定 HTML+SVG 文档；批量拉取与三题检测轮均实际走通（可用 2 / 不可用 0）。

未验证：
- **面板本身的端到端走查未完成**：点击"动画测试"、面板外观、提示词编辑/滚动/恢复默认/收起展开、预览与源码切换、复制源码、重新播放、生成中关闭确认、渲染进程异常恢复，均未在设备上实际观察。
- 原因：模拟器的合成触摸在多轮操作后不再被应用消费，且宿主前台窗口被其他应用反复抢占，注入输入无法稳定送达；继续强行驱动会干扰用户桌面，故停止。
- 因此 R10 的**界面与 WebView 运行时行为**只有单元级与源码级证据，**没有端到端设备证据**；面板的视觉与交互是否真的符合下面的验收点，需要一次人工走查确认。

### 17.2.1 面板外观修订（2026-10-08）

按使用反馈改了面板与入口的外观，均属 UI 层改动，不触碰请求、状态机与沙箱：

| 项 | 原状 | 现状 | 位置 |
|---|---|---|---|
| 模型卡入口 | `OutlinedButton`，有描边、有点击涟漪 | 纯文字，无边框；抑制 indication（涟漪）但保留点击、无障碍节点与 40dp 触摸高度 | `FingerprintScreen.kt` `AnimationTestButton` / `plainClickable` |
| 面板容器 | `Surface(fillMaxSize)`，铺满窗口、无圆角 | 屏宽 94% 的圆角卡片（`shapes.extraLarge`），`tonalElevation 6dp` / `shadowElevation 10dp`，与「关于与更新」同形状 | `AnimationPanel.kt` |
| 面板高度 | 固定占满窗口 | 随内容自适应，`heightIn(min = 0.45 屏高, max = 0.9 屏高)`；上限与其它弹窗同一条 | `AnimationPanel.kt` |
| 提示词输入框 | 固定 `heightIn(160..320dp)` | 可收起/展开；展开 `120..280dp` 多行，收起 56dp 单行；状态 `rememberSaveable(session.sessionId)`，旋转不丢 | `AnimationPanel.kt` `AnimationPromptSection` |

对应源码契约测试 3 条（`DialogWindowContractTest`）：`the animation entry is plain text with no ripple`、`the animation panel is a card, not a full-bleed sheet`、`the animation panel's prompt box collapses on request`。这些钉的是源码事实（用了哪个容器、限高挂在谁身上、收起态是不是单行），**不能替代人眼确认外观**。

### 17.2.2 对齐、默认态与生成过程可视化（2026-10-08 第二轮）

按第二轮反馈又改了三处：

| 项 | 原状 | 现状 | 位置 |
|---|---|---|---|
| 入口与状态标签的高度 | 入口用 `labelMedium`（12sp）且强制 40dp 最小高度，比状态胶囊（`labelSmall`，11sp）的行盒更高，文字明显高于「正在检测」 | 入口改用与状态胶囊同一个 `labelSmall`，并以 `wrapContentHeight(CenterVertically)` 在 40dp 触摸高度内垂直居中；两者在同一 baseline 上 | `FingerprintScreen.kt` `AnimationTestButton` |
| 提示词默认态 | 默认展开 | 默认收起（`mutableStateOf(false)`）；展开变成主动动作 | `AnimationPanel.kt` |
| 生成过程 | 只显示一条不确定进度条 + 「正在生成动画…」 | 显示正在写入的文档尾部（等宽字体、自动跟随末尾、可回看），并显示已收到字符数；**生成完成或失败后这一段整段让位**给动画/错误 | `AnimationPanel.kt` `AnimationStreamingSection`、`FingerprintViewModel.kt` `onProgress` |

生成过程可视化的实现要点：

- `RelayApi.completeTextStreaming` 本来就带 `onProgress` 回调（累积文本），此前 R10 传的是空 lambda；现在接进 ViewModel，只在 `sessionId`/`requestId` 都匹配时写入。
- 新增 `AnimationSession.streamingText`，**与 `html` 分开**：`html` 只由解析通过的完整文档写入，半成品永远进不了预览。
- UI 状态里只保留尾部 `ANIMATION_STREAM_TAIL_CHARS = 4000` 字符：完整回答上限是 200 万字符，每个 delta 都整份复制进状态的开销远大于用户真正要看的那几行。
- 成功 → `streamingText` 清空、`html` 就位、`phase = READY`，动画替换掉生成过程；失败/取消 → `streamingText` 一并丢弃（半成品留在屏幕上会看起来像「只是停住了」的结果），已有成功预览不受影响。
- 中转站一个 delta 都不发（或只发 `[DONE]`）时 `streamingText` 保持为 null，界面显示「等待响应…」，结果照常落地、不算失败。

### 17.2.3 取消后无法重新开始检测（2026-10-08 第三轮，用户报告缺陷）

**现象**：检测进行中点「取消」，之后再点「开始」毫无反应——不发任何请求，面板也没有报错。

**根因**：`runJob` 是面板的锁，但从来没有人解锁。

- 起始守卫用 `runInFlight()` 判断，原实现是 `_uiState.value.isRunning || runJob?.isCompleted == false`。
- 一轮结束后 `runJob` 一直指向那个**已经完成**的 job：`isCompleted` 变成 `true`，`== false` 永远是假，看起来「没在跑」；可句柄本身从不清空，于是 `retryChallenge` 里的同类判断（`runJob?.isCompleted == false`）以及任何读句柄的地方都拿到一个**长期非 null** 的值。
- 取消把这个矛盾放大成死锁：被取消的 job 走 `catch (CancellationException)` 后 `throw`，任何写在 `launch {}` 之后、try 之外的清理代码都到不了，句柄永久留在原处。

**修复**（`FingerprintViewModel.kt`）：

| 处 | 改动 |
|---|---|
| `runInFlight()` | 改为 `_uiState.value.isRunning || runJob != null`——只问「句柄还在不在」 |
| 轮次启动 | 捕获 `val job = viewModelScope.launch { … }`，再 `runJob = job`，并 `job.invokeOnCompletion { if (runJob === job) runJob = null }` |
| `retryChallenge` 守卫 | 保持「一轮在跑时不让重试插队」的原设计：`if (!state.isRunning && runJob != null) return` |

两个容易写错的地方，都在源码注释里写明了：

- **不能把清理放进 `launch {}` 体内的 `finally`**。`runJob = launch { … }` 是先启动协程体、再把句柄赋给字段；体内读 `runJob` 可能读到旧值或还没赋值的 null，于是「该清的时候没清」，面板永久锁死——正是这个缺陷本身。注册 `invokeOnCompletion` 则发生在赋值之后。
- **不能把重试守卫写成 `if (runInFlight()) return`**。那会把「一轮正在跑」也算作阻塞，而单题重试的模型早已落定，本来就该和整轮并行发出。改成那样会挂掉 3 条既有重试测试（并行开关下重试与整轮同时上线、串行时重试排在当前题之后、坏模型的单题重试排在健康模型在途题之后）。身份校验 `if (runJob === job)` 则保证被新轮次取代的旧轮次不会误清新轮次的锁。

**测试**：新增两条（592 条中的新增 2 条）。

- `a cancelled round can be started again`：按用户动作走——起一轮 → 等第一题真在线上 → 取消 → 等清理跑完 → 再点开始，然后断言这一轮自己该发的题数全部发出（`1 + progress.size`），且结果行不停在 `RUNNING`。
- `a finished round can be started again`：正常跑完后再点一次开始，第二轮的三题必须重新发出。这条钉的是新 `runInFlight()` 的回归面——它现在只要句柄存在就报「忙」，跑完的句柄在被清掉之前不能让面板卡住。

**这两条是否真的守得住，做了变异验证**：把源码改回旧写法（`runJob?.isCompleted == false` 且去掉 `invokeOnCompletion` 清理），`a cancelled round can be started again` 在 10 秒超时内失败——确实钉住了本缺陷；而 `a finished round can be started again` 仍然通过，因为已完成的 job 让旧守卫求值为「不忙」。也就是说**只有取消那条是缺陷的守卫**，跑完那条是防止新实现回退的护栏，不是本缺陷的证据。这一点如实记录，避免把护栏当成根因证明。

**顺带修掉一个测试骨架缺陷**：`awaitBatchSettled` 原先只轮询「没有 PENDING/RUNNING 行」。轮次是在协程里才发布行的，启动窗口内列表为空，轮询会立刻满足并**在整轮开始前就返回**。现在先等面板真的 busy（或行已出现），再等排空。

### 17.2.4 动画测试入口搬家与测试列表（2026-10-08 第四轮，用户需求）

**需求**：动画测试的入口从指纹测试页移到模型测试页的「高级测试」旁边；进入后先选供应商和模型再开始；开始后弹窗里要有测试列表，可以点进单条看进度；测试进行中关掉弹窗，按钮上要有提示，点该按钮直接回到测试列表。要求不影响其他功能、不引入新缺陷。

**关键约束（决定了整个改法）**：动画测试的全部状态（会话、请求、`animationJob`）都在 `FingerprintViewModel` 里，因为它和指纹三题检测共用供应商限速与凭据；而新入口必须长在 `TesterScreen` 上。因此采用**一个 ViewModel、状态借给另一个页面**的做法——与既有的 `onFingerprintModel` 跨页跳转同一先例，没有新建状态机，也没有新增页面切换。

**状态机改动**（`AnimationTest.kt`）：

| 原 | 新 |
|---|---|
| `session: AnimationSession?`（单会话） | `sessions: List<AnimationSession>`（新在前）+ `selectedSessionId: Long?` |
| — | `panelOpen: Boolean`（关卡片不再等于丢会话） |
| — | `supplierId` / `model` / `models`（卡片自己的选择，见下） |

保留 `session` 只读访问器和 `isOpen`，仍在 38 条既有状态机测试里表达「卡片正在显示哪一条」；新代码一律用 `selected`。`AnimationSession` 增加 `createdAt`（排序可被测试钉住）。

**卡片 UI 改动**（`AnimationPanel.kt`）：单会话面板改为**列表视图 + 详情视图**两态。列表视图 = 供应商/模型选择区 + `测试记录` 列表；点某条进详情，详情顶部有返回箭头回到列表。原来的三个 section（提示词 / 生成过程 / 结果）原样复用，未改动其内部逻辑。选择器放在**各自的对话框**里而不是下拉菜单——卡片本身可滚动，内嵌下拉会被裁掉。

**入口搬家**：删掉 `FingerprintScreen.kt` 里的 `AnimationTestButton`、`plainClickable`、`CHALLENGE_ACTION_MIN_HEIGHT` 及相关参数；`TesterScreen.kt` 新增 `AnimationTestEntry`，与「高级测试」并排放在「测试参数」标题行。入口的标题行用 `BoxWithConstraints` 判断：宽 ≥340dp 且字号 <1.3 才并排，否则标题独占一行、两个按钮另起一行靠右——与操作栏、挑战头部同一条「放不下就换行」的规则。

**进行中提示**：入口按钮读 `animationState.generating != null`，为真时在播放图标右上角画一个 8dp 圆点角标，文案由「动画测试」改为「动画生成中…」。**关卡片不取消**：`closeAnimationPanel()` 只置 `panelOpen = false`；取消是 `cancelAnimationGeneration()`，删除记录是 `removeAnimationSession()`。

**修掉两个只有改成列表之后才暴露的真实缺陷**：

1. **被取代的生成永远停在 GENERATING**。`startAnimationGeneration()` 会 `invalidateAnimationRequest()` 取消上一条请求，而 `generateAnimation` 捕获取消后直接 `throw`（旧语义下被取消的会话本来就要整个丢掉，不写终态是对的）。现在记录会留在列表里，于是那一行会永远转圈。新增 `settleReplacedAnimation(keepSessionId)`，在取消前把其它仍在 GENERATING 的记录落定为 READY（有旧结果）或 FAILED，并清掉半截流式文本。
2. **`startAnimationTest` 复用同供应商+同模型的记录**：再点一次「开始生成」是重跑那条测试，不是列表里多一行重复记录。

**测试**：本轮把契约测试里 4 条动画相关的断言的**语义**改到新结构（不是放宽）——`AnimationPanel.kt` 的收起状态键由 `session.sessionId` 改为 `selected?.sessionId`（卡片现在会在多条记录间切换）；「入口是纯文字、无涟漪」这条**语义反转**，改写成「入口在 TesterScreen、是与高级测试同规格的描边按钮、带进行中角标，且与后者同处渲染」。三条旧的关闭语义测试改到新契约：关卡片保留进行中的测试；删除才取消；晚到响应落到它自己那条记录上而不是污染当前那条。新增 12 条，覆盖关卡片不取消、删除取消、晚到响应不复活记录、同供应商同模型复用记录、换模型是新记录、列表顺序、选中/返回列表、选不存在的记录被忽略、未知供应商/空模型不留记录、换供应商清空模型、动画选供应商不扰动检测页状态、开新测试取消旧测试但保留其记录。

**如实记录一处测试自身的判断失误**：最初两条新测试假设「两条测试可以同时在生成」，因此断言被取代的记录仍是 GENERATING。这是错的——共用限速下同时只允许一条生成，`startAnimationGeneration` 会取消上一条。是测试假设错了，不是实现错了；两条断言已改成实际契约（被取代的记录落定但留在列表里）。

**真机走查**（emulator-5560，安装 debug 包；`applicationIdSuffix = ".debug"`，组件为 `com.relaytester.app.debug/com.relaytester.app.MainActivity`）：

| 检查 | 结果 |
|---|---|
| 入口位置 | 「动画测试」与「高级测试」并排出现在「测试参数」标题行，同一规格描边按钮 |
| 卡片形态 | 圆角卡片 + 遮罩，标题「动画测试」，右上角关闭 |
| 列表视图 | 供应商、模型选择器；未选供应商时模型禁用并提示「该供应商没有模型」；无记录时提示「还没有测试记录」 |
| 选供应商 | 选「供应商 3」后模型选择器变为可用的「请选择模型」，选项为该供应商的 2 个模型 |
| 开始生成 | 选好模型后「开始生成」由禁用变可点；点击后进入详情视图，显示「正在生成 / 等待响应…」与「取消生成」 |
| 关卡片不丢记录 | 生成中关卡片，再打开，卡片直接回到该条记录的详情并显示其真实终态（`上游返回 HTTP 502`），记录未被丢弃 |
| 进行中角标 | 生成中关卡片，入口文案变为「**动画生成中…**」且播放图标右上角出现蓝色圆点 |
| 点角标回到测试 | 点带角标的入口，卡片直接打开在**仍在进行**的那条测试（「正在生成 / 等待响应…」） |

走查用本地假服务（`E:/AI/Zcode/tmp/relay-tester-review/fake-anim/server.py`，绑定 `0.0.0.0:8099`，只回固定 HTML 样例）。**未调用任何真实供应商**；为制造足够长的进行中窗口，临时把「供应商 3」的 Base URL 指向 RFC 5737 的 `192.0.2.1`（保证连接挂起），走查结束后**已恢复原值** `http://10.0.2.2:8099/v1` 并复核保存成功；假服务已按 PID 停止，8099 端口已释放。

### 17.3 本轮门禁结果

| 项目 | 结果 |
|---|---|
| `testDebugUnitTest` | 31 套件、**605 测试、0 失败、0 错误、0 跳过**（在 592 条基础上：第四轮新增 12 条 + 改写/新增契约测试 1 条） |
| `lintDebug` | **0 errors、11 warnings**：R09 `Aligned16KB` ×3、版本提示 ×6、既有配置告警 ×2（`ChromeOsAbiSupport`、`InsecureBaseConfiguration`）。**三轮修订都未新增任何 error 或告警** |
| `assembleDebug` | BUILD SUCCESSFUL，退出码 0 |
| 安装 | `emulator-5560` 覆盖安装成功，`lastUpdateTime` 与本轮构建一致 |
| 显示设置 | 已恢复原值：1080×2400 / density 420 / font_scale 1.0 |
| 模拟器代理 | 走查期间清空 `http_proxy`，收尾已恢复原值 `10.0.2.2:7897`（`settings get global http_proxy` 复核通过） |

APK：`app/build/outputs/apk/debug/app-debug.apk`

```text
SHA-256（2026-10-08 第四轮：入口搬家 + 测试列表；debug 摘要随构建时间戳变化，此值仅对该次构建有效）
6c533ff869e8cd18a840cc50e3356208a6c09d056ec2bdda16bb331bbbe7ccf5
```

历史构建：取消后重开修复版 `4c1d73ca35e8ebf89358db61ec92843ba7cb71bd17a46079e7fe1346aa26f26f`；对齐 + 默认收起 + 生成过程可视化版 `33cd7c71e9fcb0c924d73338bd9ecbedc66dc9fed2159e85c3e259f5dc6579f1`；面板外观修订版 `5c2c3bf3094fe39767310e0e7d45522a2111e32f41dd0b2f3b2d8d4f02640a8b`；外观修订前 `b1e49e85aab5ce5f1628d2a59ed4aa3d70bd66a18d0b836ce32269c968411d03`。均已被取代。

### 17.4 本轮附带修复：指纹测试套件挂起

`FingerprintViewModelTest` 长期超过 15 分钟不返回，已定位并修复：
- 根因是测试骨架 `parkedRetryState` 误用 `skipRestore = true` 的构造路径，导致无供应商/密钥、`runApiDetection` 直接返回、`batchResults` 为空，`retryChallenge` 落入 `replaceChallenge` 分支而不发请求，`api.retryParked.await()` 永久等待。
- 改用 `readyApiViewModelFor(SingleSupplierStore(testSupplier()), api)`。
- 同时给所有重试等待加 `withTimeout(BATCH_SETTLE_TIMEOUT_MS)`，使同类回归在 10 秒内失败而不是挂死。
- 结果：套件从挂起变为 **3 秒内全部通过**。

### 17.5 本轮证据索引

- 最终门禁日志：`E:/AI/Zcode/tmp/relay-tester-review/gates-r10-v2.log`（第二轮修订后）
- JVM 测试报告：`E:/AI/Zcode/projects/relay-tester/app/build/reports/tests/testDebugUnitTest/index.html`
- Debug Lint 文本：`E:/AI/Zcode/projects/relay-tester/app/build/reports/lint-results-debug.txt`
- 受测 APK：`E:/AI/Zcode/projects/relay-tester/app/build/outputs/apk/debug/app-debug.apk`
- 本轮假服务：`E:/AI/Zcode/tmp/relay-tester-review/stub_server.py`（端口 8099，日志 `stub.log`）
- 第四轮假服务：`E:/AI/Zcode/tmp/relay-tester-review/fake-anim/server.py`（绑定 `0.0.0.0:8099`，日志 `server.log`）
- 走查截图：`E:/AI/Zcode/tmp/relay-tester-review/shots/`（第四轮：`entry.png` 入口并排、`card2.png` 列表视图、`generating.png` 生成中详情、`BADGE2.png` 进行中角标）

假服务只返回固定 HTML 样例，不接入任何真实供应商，日志不含密钥或 Authorization 原文。

### 17.2.5 布局微调、两层弹窗、测试边界与 reasoning 可视（2026-10-08 第五轮，用户需求）

**本轮四项需求**：① 指纹测试的筛选输入放进胶囊按钮、与并行开关同行居中；② 动画测试改为两层弹窗（先列表、点记录进详情）；③ 审计四页测试边界，进行中互不干扰、取消后不残留；④ 输出 optimized 正式签名 APK（不发布）。另有两条用户报告的现象：⑤ 动画测试「一直等待响应、没有思考过程也没有输出」；⑥ 动画测试与高级测试按钮与「测试参数」标题错位。

#### 逐项状态（已实施 / 已验证 / 仍未验证）

| # | 需求 | 已实施 | 已验证 | 仍未验证 |
|---|---|---|---|---|
| ① | 指纹页胶囊选择器 + 并行开关同行 | `FingerprintScreen.kt` `ModelPicker` 改为 `Row`：胶囊 `OutlinedTextField` + 角标列表按钮 + 并行开关，`Alignment.CenterVertically` | 编译 + 单元套件全绿 | **实机观感未逐像素验收**（本轮走查未截指纹页） |
| ② | 动画测试两层弹窗 | `AnimationPanel.kt` 拆成 `AnimationTestListDialog` / `AnimationTestDetailDialog`，共享 `AnimationCard`；`openAnimationPanel()` 清 `selectedSessionId` 保证入口永远落在列表 | 契约测试 1 条 + **实机走查**：弹窗一 = 模型选择 + 测试记录；点记录 → 弹窗二 = 当前测试界面 | — |
| ③ | 四条测试链路的边界 | 新增 `animationBusy()` + `refuseWhileAnimationRuns()` 接进 `runApiDetection` 与 `retryChallenge`（修复下文的不对称缺陷） | 4 条行为测试 + **双向变异验证**；其余三页为读代码审计结论 | **模型测试页与余额页未做设备级并发走查**；动画卡片角标未在 release 包截到 |
| ④ | optimized 正式签名 APK | `assembleOptimized` BUILD SUCCESSFUL | SHA-256 与签名证书已核（见 17.3） | 未发布（按要求） |
| ⑤ | 「一直等待响应」无思考无输出 | 新增 reasoning 通道（`onReasoning` + `extractStreamReasoning`），面板三态显示 | 3 条单元测试 + **变异验证** | **真机「先出思考」观感未验证**（假服务不含 reasoning 帧） |
| ⑥ | 入口按钮与标题错位 | 两个入口 `contentPadding`/`heightIn`/图标/字号收小，换行阈值随字号缩放 | **实机量测**：标题中心 1097.5px vs 两按钮 1098px（同线，偏差 0.5px） | — |

#### ① 指纹页：筛选输入进胶囊按钮

`ModelPicker` 由「只读摘要 + 独立筛选行 + 独立并行开关行」改为一个 `Row`（左胶囊选择器、右并行开关，`Alignment.CenterVertically`）。

两处判断记录在案：

- **用 `placeholder` 而非 `label`**：`OutlinedTextField` 的 `label` 在未聚焦时会浮到边框缺口上方并抬高布局盒，此时「可见边框的中心」与开关中心并不在同一线，`CenterVertically` 就成了假的居中；`placeholder` 不抬高盒高，对齐才是诚实的。
- **一次自我纠正**：第一版把输入框改成 `OutlinedButton` + 只读摘要，那会删掉「输入目录里没有的模型名」这条能力，属功能倒退；已改回可编辑的 `OutlinedTextField`。

#### ② 动画测试：两层弹窗

一个「按选中态切换内容」的卡片改为两个各管一件事的 `Dialog`：

- `AnimationTestListDialog` = `AnimationSetupSection`（供应商 + 模型 + 开始生成）+ `AnimationSessionList`（测试记录）；
- `AnimationTestDetailDialog` = 提示词区 + 生成中/结果区 + 操作栏；
- 公共外观抽成 `AnimationCard`（屏宽 94%、外层圆角、高度上下限）。**窗口盒与遮罩各自持有**，因为既有契约测试要求原生 `Dialog` 数与 `rememberDialogWindowBox()`/`DialogDismissScrim()` 数一一对应。

**入口永远落在列表**：清 `selectedSessionId` 必须做在 `openAnimationPanel()` 而不是弹窗内部——入口的开合由 `TesterScreen` 的本地 `showAnimationSheet` 控制，选中的记录会跨「关闭—重开」存活，否则点入口会直接弹进上次看了一半的那条记录。`TesterScreen` 新增 `onOpenAnimation` 回调接到 `openAnimationPanel`。

**返回语义**：详情层的返回箭头、系统返回、点卡片外都退回列表层；关闭按钮关掉整层。

**契约测试改写（语义未放宽）**：`the animation panel's prompt box collapses on request` 原先钉的是字面键 `selected?.sessionId`，拆分后详情层键的是参数 `session.sessionId`；改为断言「键里必须含 `sessionId`」这一**形状**断言——钉的是「收起状态按记录隔离」这个性质，不是某一版写法的字符串。新增 `the animation test opens on its list, and a record is what opens the detail`：断言文件里恰好 2 个原生 `Dialog`、列表层含选择区+记录列表且不含提示词区/操作栏、详情层反之、两层互斥、`openAnimationPanel` 里清 `selectedSessionId`。

#### ③ 测试边界审计：一处真实的不对称缺陷

**发现的缺陷**：动画生成会拒绝在三题检测进行时启动（`roundBusy()`），但**反过来没有**——`runApiDetection` 与 `retryChallenge` 都不检查动画是否在跑。两者共用同一供应商的凭据与限速，于是「会不会有两个请求同时挂在一条 key 上」取决于用户先按了哪个键。

**修复**：新增

```kotlin
private fun animationBusy(): Boolean = animationJob?.isCompleted == false
```

读 `isCompleted` 而不是 `isActive`：取消时 `invalidateAnimationRequest()` 会把句柄**清成 null**，所以取消后读到的 `false` 是「这里已经没有东西了」，而不是「刚好跑完」。`refuseWhileAnimationRuns()` 说明原因并**拒绝而不是取消**动画——动画是用户主动发起的，屏幕另一处的按钮不该把它拿走。两处接入点各在 `refuseWhileBankJobRuns()` 之前。

**新增 4 条行为测试**（均带等待上界 `ROUND_START_TIMEOUT_MILLIS = 5_000L`）：

1. 动画生成中按检测 → 不发起请求、`isRunning` 保持 false、**必须给出含「动画」的原因**；
2. 取消动画后检测能起来；
3. 动画正常跑完后检测能起来；
4. 取消之后能再发一次动画请求（`api.calls` 1→2 且新的那一次真的处于 GENERATING）——正对用户原话「取消后不再占据后台测试进程，影响下一次测试」。

第 2、3 条必须把检测**停在闸门上**才作数：否则检测会同步跑完，`isRunning` 读到的 false 是「已经结束」而不是「没被拦住」，测试将什么都没证明。

**变异验证（双向）**：`animationBusy()` 恒为 `false`（等价修复前）→ 第 1 条失败；恒为 `true`（过度拦截）→ 第 2、3 条失败。两个方向都被抓住。

**一处教训（如实记录）**：第一次做过度拦截变异时用了无上界等待，整个套件**挂住**了——而挂住正是「过度拦截」这种回归的失败形态，读起来却像环境问题。已加等待上界，现在过度拦截 5 秒内以 `TimeoutCancellationException` 失败。

**其余三页的审计结论（已读代码，本轮未新增改动）**：

- **余额页**：查询走独立状态流 `_balanceUiState`，不写模型测试的 `_uiState`；三个入口（`queryBalance` / `refreshSupplierBalance` / `queryAllBalances`）共用 `balanceQueryStartMutex` + `releaseWhenSettled`（`invokeOnCompletion` 释放，异常与提前 return 也会释放）。页面把「模型测试正在跑」收进 `siteSwitchLocked`，被拒时说明原因而非静默吞点击。
- **模型测试页**：`startTest` 用 `testRunStartMutex.tryLock` + `runInFlight()`（`testJob?.isCompleted == false`）双重把关，并在获取 runner 之后、真正启动之前**再查一次 `isRunning`**——那一段是取消窗口，关掉它才不会「点了取消却照样发请求」。取消只 `cancel` 句柄而不清空，正是为了让「已取消但尚未清理完」的尾巴继续挡住下一次启动（既有测试 `a cancelled request must finish cleanup before another run can start` 守着）。
- **指纹页**：进行中把选型、筛选、模式、题目、开始等控件置灰（`enabled = !state.isRunning`）。

#### ⑤ 「一直等待响应」的根因

**根因**：`RelayApi.readEventStream` 只在**累积的答案正文**增长时回调 `onProgress`，而 `extractStreamDelta` 对 `reasoning_content` / `thinking_delta` / `response.reasoning_summary_text.delta` **故意返回空串**（就「答案」而言这是对的：思考不是答案）。于是推理模型在写出第一个答案字符之前，界面**没有任何信号**，一直显示「等待响应…」——与请求卡死无法区分。

**修复**：思考单开一条通道，绝不混进答案。

- `completeTextStreaming` / `readEventStream` 新增 `onReasoning`；一帧没有答案文本时，用新增的 `RelayCompletionText.extractStreamReasoning(payload, protocol)` 取思考（Chat `reasoning_content`→`reasoning`、Anthropic `thinking_delta`、Responses `response.reasoning_summary_text.delta`），非空才上报。
- `AnimationSession` 新增 `reasoningText`（尾部截断，与 `streamingText` 同）。它**绝不进 `html`**：思考不是答案，且「只有思考没有答案」的流仍由既有空答案检查判为失败。
- 面板三态：有正文 → 「已收到 N 字符」；只有思考 → 「模型正在思考…」并单独显示思考；都没有 → 「等待响应…」。
- 三题检测不订阅 `onReasoning`（思考不参与判分，也没有显示位置），已在调用处注明。

**变异验证**：把 `extractStreamReasoning` 改为恒返回空串 → 两条新测试失败；改回后通过。

**一次自我纠正（如实记录）**：第一版 reasoning 测试「通过」了，但它断言的是 `onProgress`，而流里随后的答案帧同样会触发 `onProgress`，所以它**什么都没证明**。改为断言专门注入的 `onReasoning` 后才成为有效测试。

#### ⑥ 入口按钮与标题对齐

两个入口（动画测试 / 高级测试）收小：`contentPadding = PaddingValues(horizontal = 8.dp)`、`heightIn(min = 32.dp, max = 36.dp)`、`widthIn` 上限收窄、图标 16dp、`labelLarge`。换行判定跟着字号走：`SETTINGS_HEADER_BUTTONS_WIDTH = 200.dp`（两个四字按钮加图标在 fontScale 1 下的自然宽度）与 `SETTINGS_HEADER_TITLE_MIN_WIDTH = 68.dp`，按 `density.fontScale` 缩放后决定并排还是换行。

**实机量测（release 签名包，emulator-5560，`uiautomator dump`）**：「测试参数」标题纵向中心 1097.5px、「动画测试」1098px、「高级测试」1098px——同一行、中心对齐，偏差 0.5px，未换行。

#### 动画测试按钮：进行中只加角标

需求②（前序）曾要求进行中改文案；本轮用户改为**文案不变、只加角标**。`AnimationTestEntry` 的文案固定为「动画测试」，进行中只在图标右上角画 7dp 圆点（`CircleShape`，`primary` 色）。理由写在 KDoc 里：按钮的职责是「好找」，在用户眼皮底下重写自己的标签会让他的瞄准目标移动；角标挂在图标上，因此标签被截断也不影响它。

#### 真机走查发现的两处缺陷（单元测试结构上无法发现）

这两条是本轮走查的主要价值，也是「不得以测试通过/页面能打开替代验收证据」的实例。

**(a) 预览区显示引擎失败页**：现象为 `Webpage not available / net::ERR_HTTP_RESPONSE_CODE_FAILURE`。根因是 `shouldInterceptRequest` 只放行 `ANIMATION_PREVIEW_BASE_URL`，而 `loadDataWithBaseURL` 交给渲染进程的**主框架请求带的是 `data:` 地址**，于是沙箱把**自己的文档**当外部资源拦掉了。

修复：抽出 `previewRequestAllowed(url)`，放行 `data:`（文档自身与 CSP 已允许的 `img-src data:` 内联图；`data:` 到不了网络、导航另有拦截，不构成逃逸通道）与固定的 base，其余（`http(s)`、`file:`、`content:`、`javascript:`、`about:`）一律拒绝；并补 `onReceivedError`，**只在主框架**上报载入失败，避免某个被拒的子资源把可用页面误报为失败。

测试：`the preview admits its own document and nothing that could reach out`（正向 3 条 + 反向 7 条）。**双向变异**：改回「只认 base」→ 失败（抓住原缺陷）；改成全放行 → 失败（抓住安全边界被拆）。

设备证据：预览区显示出**红色圆形**，且内联脚本执行成功（标题由 `FAKE-ANIMATION-OK` 变为 `FAKE-ANIMATION-OK-JS`）——既证明文档被放行，也证明 `script-src 'unsafe-inline'` 生效而沙箱其余限制仍在。

**(b) 卡片打开时目录为空**：从模型测试页打开动画卡片，供应商下拉里什么都没有，而供应商列表就在它背后那一页上。根因是卡片读的是指纹 ViewModel 的 `knownSuppliers`，而这份目录只在**指纹页被展示时**刷新；入口是第四轮才搬到模型测试页的，于是「先拉模型 → 直接点动画测试 → 从没进过指纹页」这条最顺的路径上目录还是空的。

修复：`openAnimationPanel()` 里先 `refreshCatalogue()` 再打开。**测试**：新增 `opening the card reads the catalogue it needs to offer a supplier`；**变异验证**：去掉 `refreshCatalogue()` → 失败。**一次自我纠正**：该测试第一版是自证的（fixture 已预读过目录），改成「先清空 `knownSuppliers` 与 `uiState.suppliers`，再断言打开后非空」才有效。

#### 17.2.6 第五轮门禁与交付物

```
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug     ->  BUILD SUCCESSFUL
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleOptimized ->  BUILD SUCCESSFUL
```

| 项目 | 结果 |
|---|---|
| `testDebugUnitTest` | 31 套件、**615 测试、0 失败、0 错误、0 跳过**（第四轮 605 → 615）；第三轮再增至 **617**，见 17.2.10 |
| `lintDebug` | **0 errors、11 warnings**（与第四轮同：R09 `Aligned16KB` ×3、版本提示 ×6、既有配置告警 ×2）。本轮未新增任何 error 或告警 |
| `assembleDebug` | BUILD SUCCESSFUL |
| `assembleOptimized` | BUILD SUCCESSFUL |

**optimized（正式签名）APK——用户授权构建，明确不发布**：

| 项 | 值 |
|---|---|
| 变体 | `optimized`（`initWith(getByName("release"))`，与发布同一签名；minify + shrinkResources 开启） |
| 路径 | `E:/AI/Zcode/projects/relay-tester/app/build/outputs/apk/optimized/app-optimized.apk` |
| 大小 | 3,121,047 字节 |
| **SHA-256** | `b6002b3089e7580ea2a4dfccd40f596c3716992289c15200ffb20dd0fd9fb6a9` |
| 包名 / versionCode / versionName | `com.relaytester.app` / 10703 / 1.7.3 |
| 签名 | Signer #1 `CN=Relay Tester, OU=Release, O=Relay Tester, C=CN`；证书 SHA-256 `05c3e56705aa8c420523f999d950ce74482c42f4376ac88918db10e9451d69de` |
| 新鲜度 | `find app/src/main -name "*.kt" -newer <apk>` 为空（APK 比全部源码新） |
| 发布 | **未发布**：未运行发布脚本、未上传任何渠道、未改发布签名 |

**构建方式说明**：签名口令按设计放在仓库之外的 `~/.gradle/gradle.properties`，而本轮门禁为隔离使用了重定向的 `GRADLE_USER_HOME`，因此 `assembleOptimized` 会报 `SigningConfig "release" is missing required property "storePassword"`。解法是把 `GRADLE_USER_HOME` 指回真实目录 `C:/Users/renyingpeng/.gradle` 完成该次构建——**没有**把口令写进仓库或命令行明文。

#### 17.2.7 第五轮环境改动与恢复

| 项 | 原值 | 改动 | 恢复 |
|---|---|---|---|
| `http_proxy` | `10.0.2.2:7897` | 走查期间清空为 `:0`（否则本机假服务流量被全局代理拦成 502） | **已恢复**并复核 |
| `global_http_proxy_host` | `10.0.2.2` | 删除 | **已恢复**并复核 |
| `global_http_proxy_port` | `7897` | 删除 | **已恢复**并复核 |
| 假服务 | — | 启动 `E:/AI/Zcode/tmp/relay-tester-review/fake-anim/server2.py`（瞬时返回带内联脚本的固定 HTML 样例，绑定 `0.0.0.0:8099`） | **已按 PID 停止**，端口已释放 |
| 展示的 App | debug 包 | 安装 release 签名包用于走查 | 两者共存（包名不同，互不影响） |

未改动发布签名文件；未按过期 PID 杀进程（停止前核对端口归属）；**未调用任何真实供应商**。

#### 17.2.8 第五轮仍未验证（如实声明）

1. **指纹页胶囊选择器与并行开关的实机观感未逐像素验收**——本轮设备走查集中在模型测试页与动画卡片，未截指纹页。
2. **推理模型「先出思考」的真机观感未验证**——假服务返回的是普通流，不含 reasoning 帧；该修复目前只有单元级 + 变异验证证据。
3. **「取消后不残留」只在动画↔检测两路做了确定性行为测试**；模型测试页与余额页是读代码结论（前者另有既有测试守着取消尾巴），未做设备级并发走查。
4. **动画卡片「进行中角标」未在 release 包截到**——走查用假服务现在瞬时返回，生成窗口太短；角标逻辑由单元测试与源码契约覆盖（debug 包在第四轮实测到角标；本轮改为文案不变、只留角标，属外观改动）。
5. R09 `Aligned16KB` 与 16KB 页设备上的 QuickJS 实际运行仍未修复/未验证（沿用第 5 节）。

#### 17.2.9 第五轮证据索引

- 走查截图：`E:/AI/Zcode/tmp/relay-tester-review/release-main.png`（入口与标题同行对齐）、`release-dialog1.png`（弹窗一：模型选择 + 测试记录）、`preview-ok.png`（预览渲染红圆 + 脚本执行）、`source-view.png`（源码视图）、`streaming.png`、`after-pull.png`
- 假服务：`E:/AI/Zcode/tmp/relay-tester-review/fake-anim/server2.py`（端口 8099，走查后已停）
- 受测 APK：`app/build/outputs/apk/optimized/app-optimized.apk`（SHA-256 见 17.2.6）
- 测试报告：`app/build/reports/tests/testDebugUnitTest/index.html`

#### 17.2.10 第三轮更正与缺陷自查（2026-10-08）

**用户更正 4A/17.2.5 的第一项**：筛选输入不该留在页面上套成胶囊，而应放进模型列表弹窗；页面只留一个胶囊按钮，右边是并行开关并补可见文字说明。

| # | 变更 | 已实施 | 已验证 | 仍未验证 |
|---|---|---|---|---|
| ① | 筛选框移入 `ModelListDialog`，页面只剩胶囊按钮（显示「选择模型」／「已选 N 个模型」）+ 可见文字「三题并行」+ 开关，同一行居中 | `FingerprintScreen.kt` | 设备实测（debug + optimized）：弹窗顶部即「筛选模型」，输入 `eof` 把 4 个模型筛成 `mock-eof`，出现「清空筛选」 | 读屏实测未做 |
| ② | 顺带修掉：两个筛选词互相盖住（页面级 + 弹窗级，列表成了两者交集且页面那个在模态背后改不到） | 合并为单一 `state.modelFilter`；`unmatched` 不再外部传入 | 源码契约测试 + **变异验证**（改回旧形态即失败） | — |
| ③ | 顺带修掉：空目录时提示「可直接输入模型名」却不渲染输入框，把唯一可用的一条路堵死 | 输入框移出 `else` 分支 | 设备实测：供应商 2（0 模型）→ 提示下方即输入框，输入 `my-custom-model` 出现「不在列表，直接检测」兜底行 | — |
| ④ | 两个入口按钮再缩小（8→6dp padding、36→30dp 高、16→14dp 图标、`labelLarge`→`labelMedium`、角标 7→6dp） | `TesterScreen.kt` | 设备实测：标题中心 1075px vs 按钮 1076px，同行、偏差 1px、无换行 | — |
| ⑤ | `SETTINGS_HEADER_BUTTONS_WIDTH` 200→168dp（写大会导致本可并排的屏幕误判换行） | 同步修改 | 同上实测 | — |

**「检查最近的修改有没有带来 bug」的自查**（逐项读代码）：动画↔三题互斥已对称（`runManualAnalysis` 不发请求故无需守卫）；`sessionId` 单调不复用 + 每次写入比对 `requestId`，迟到响应不会写错记录；`invalidateAnimationRequest` cancel 同时置 null，`animationBusy()` 读 `isCompleted` 覆盖收尾窗口；预览沙箱的 `data:` 放行只有文档自身（到不了网络，导航全程拒绝），10 例测试含 7 例反向；筛选搬迁的调用点已改净，删掉 `Badge`/`BadgedBox` 两个未用导入，lint 无新增告警。

**门禁**：617 测试 0 失败（第二轮 615 → 617）、lint 0 error / 11 warning（无新增）、`assembleDebug` 与 `assembleOptimized` 均 BUILD SUCCESSFUL。

**第三轮 optimized APK**：SHA-256 `dfd82ed9b3db975ff62d7b93c582a5abb6bb5c5e9169311ed244eb07ebccfe7d`，3,121,047 字节，签名与 17.2.6 同（证书 SHA-256 `05c3e56705aa8c420523f999d950ce74482c42f4376ac88918db10e9451d69de`），**未发布**。第二轮摘要 `b6002b3089e7580ea2a4dfccd40f596c3716992289c15200ffb20dd0fd9fb6a9` 已被取代。

## 18. 审查期证据索引与未完成验证（历史保留）

### 已有证据

- [最终 debug 门禁日志](E:/AI/Zcode/tmp/relay-tester-review/final-debug-gates.log)
- [JVM 测试报告](E:/AI/Zcode/projects/relay-tester/app/build/reports/tests/testDebugUnitTest/index.html)
- [Debug Lint 文本](E:/AI/Zcode/projects/relay-tester/app/build/reports/lint-results-debug.txt)
- [受测 APK](E:/AI/Zcode/projects/relay-tester/app/build/outputs/apk/debug/app-debug.apk)
- [本地假服务](E:/AI/Zcode/tmp/relay-tester-review/mock-server.js)
- [假服务请求记录](E:/AI/Zcode/tmp/relay-tester-review/mock-requests.jsonl)
- [320dp 大字号高级目录截图](E:/AI/Zcode/tmp/relay-tester-review/evidence/catalog-320dp-font160.png)

这些是本机路径。`app/build` 报告会被后续构建覆盖，`tmp` 证据也不是永久归档；实施前需要保留基线副本时，应写入工作区内的新目录，不覆盖原始证据。

假服务四个模型为 mock-failed、mock-completed、mock-eof、mock-fallback。日志只记录请求路径、模型、stream 标志和鉴权是否匹配的布尔值，不含真实密钥。已有服务尚未覆盖所有 Responses 终态用例，扩充测试时不要把当前 mock 当成完整协议测试集。

### 明确未完成的验证

- 跨轮 retry 结果污染的确定性复现。
- 保存失败时 UI 草稿丢失的故障注入复现。
- 历史 append/clear 的可控调度交错。
- 长模型列表的性能测量。
- 横屏、窄屏指纹三题卡的全部状态验收。
- 正式检测包的安装与下载链路端到端验证。
- 当前提交的 optimized/release 门禁。
- 16KB 页设备上的 QuickJS 实际运行。
- R10 功能实现、HTML / SVG 生成与 WebView 安全/生命周期验收；本次未调用模型、生成页面或运行动画测试。

审查收尾已将隔离设备设置为 1080×2280、density 440、字号 1.0、竖屏；本轮假服务监听端口 18743 已关闭。其他项目的 5558 模拟器未修改或停止。以上是审查结束时的记录，不代表未来执行时设备仍保持同一状态。
