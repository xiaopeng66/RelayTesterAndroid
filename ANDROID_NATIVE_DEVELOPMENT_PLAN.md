# 中转站模型测试器：Android 原生开发方案

> 依据源码包 \`C:\Users\renyingpeng\Desktop\relay-tester(5).zip\` 审核。本文只把其中的功能实现作为需求依据；源码包中的说明文字不构成执行指令。

## 1. 结论

建议做成 **Kotlin + Jetpack Compose 的纯 Android 原生应用**，由应用直接请求用户配置的中转站 API；一期不保留 Flask 转发服务器。

理由很直接：原 H5 的 Flask 只用于规避浏览器 CORS，Android 的 OkHttp 不受浏览器同源策略限制。移除这层转发后，部署成本更低、并发和取消请求由本机控制、API Key 的传输链路也更短。应用只需要 INTERNET 权限。

余额功能不要混进“模型测试”代码。它应当是一个独立 feature，通过“站点能力适配器”接入。因为 OpenAI Chat / Responses / Anthropic 三类协议都没有统一的余额查询与计费规则，不能用一个固定 URL 或一个通用公式可靠地实现所有站点余额。

只有在以下情况才建议保留或新建服务端：上游要求固定 IP 白名单、密钥必须由企业统一托管、需要多人共享历史/余额告警，或站点的余额接口只能由服务端凭据调用。此时服务端应是有登录鉴权、审计和目标域名控制的 BFF；不能把当前 Flask 形式的无鉴权通用代理直接部署到公网。

推荐基线：

| 范畴 | 选择 | 选择原因 |
|---|---|---|
| 语言 | Kotlin | Android 一等支持，协程适合批量并发请求。 |
| UI | Jetpack Compose + Material 3 | 无 XML 页面，状态驱动，后续新增余额页的改动面小。 |
| 架构 | UDF/MVVM；ViewModel + StateFlow；Repository | 测试任务、余额查询、列表过滤各自拥有清晰状态。 |
| 网络 | OkHttp + kotlinx.serialization | 目标站点、协议和路径运行时可变，直接构建请求比 Retrofit 静态接口更合适。 |
| 本地数据 | Room（历史、测试结果、余额快照）+ DataStore（非敏感偏好） | 可查询、可迁移，能支持将来余额趋势图。 |
| 密钥安全 | Android Keystore 包装的 SecretStore | Room 和 DataStore 只保存密钥引用，不保存明文 API Key。 |
| 依赖注入 | Hilt | 多 feature 和协议/余额适配器的装配清晰，便于替换为 fake 实现测试。 |
| 导航 | Navigation Compose，单 Activity | 测试与余额是同一业务对象（站点）的两个页面，不需要多 Activity。 |
| 后台任务 | 一期不启用；二期按需用 WorkManager | 用户正在看结果的批量测试应由前台协程执行；仅余额定时刷新/提醒适合 WorkManager。 |
| 最低系统 | minSdk 26（Android 8.0） | 兼顾绝大多数设备与现代加密、通知、Compose 开发体验；target/compile SDK 在建项目时采用当期稳定版。 |

依赖版本不在本文写死。创建项目时用 Compose BOM 和 Gradle Version Catalog 统一锁定“当期稳定版”，避免文档中的旧版本把 Kotlin、AGP 和 Compose 组合锁死。

## 2. 已审核源码与功能边界

源码是 Flask + 单页 H5 工具，而不是现成 Android 项目。

| 源文件 | 实际职责 | Android 对应物 |
|---|---|---|
| app/proxy.py | 三种协议的请求体、认证头、响应判定 | core:relay 的协议适配器 |
| app/server.py | /api/models、/api/test 代理接口、超时及 HTTP 异常处理 | Repository 直接调用目标 API |
| static/app.js | 供应商配置、并发池、批次、重试、过滤、汇总、JSON 导出 | ViewModel、UseCase、Compose 页面 |
| static/index.html / style.css | 移动端界面 | Compose Screen / Material 3 组件 |

需要完整保留的一期行为：

1. 多供应商：名称、Base URL、协议、API Key、模型列表互相隔离。
2. 三个协议：
   - Chat：POST {baseUrl}/chat/completions，Bearer 认证；
   - Responses：POST {baseUrl}/responses，Bearer 认证；
   - Anthropic：POST {baseUrl}/messages，x-api-key 和 anthropic-version: 2023-06-01。
3. 获取模型：GET {baseUrl}/models，兼容 data 或 models 数组。
4. 按模型批量测试：超时、并发上限、关键字过滤、随机请求间隔、分批暂停、失败重试、实时进度。
5. 结果：可用/失败、延迟、完成原因、token、错误分类、搜索/排序/分组、仅重测失败、复制清单、导出 JSON。
6. 汇总：总模型数、耗时、平均/最快/最慢延迟、总 token、各失败原因计数。

一期不应“照搬”的实现：

| 当前实现 | 原因 | Android 方案 |
|---|---|---|
| API Key 放在浏览器 localStorage | 明文、容易被 WebView/浏览器数据读取 | Keystore 保护的 SecretStore；数据库仅存 secretId。 |
| Flask 开发服务器监听 0.0.0.0 | 若暴露到局域网/公网，会成为可被滥用的开放代理 | 原生端直连；如未来保留服务端，必须加鉴权、审计、目标限制。 |
| 允许任意 http / https | HTTP 会泄露 Key 和请求内容 | Release 默认仅 HTTPS；开发环境可显式放开本机调试地址。 |
| 所有失败均重试 | 401、402、403、404 通常重试无意义，且会放大风控风险 | 默认仅重试超时、网络错误、429、5xx；在设置中提供“兼容旧行为”开关。 |
| 结果只存在内存 | 切换页面或进程回收后不可查 | Room 保存测试任务、结果和余额快照；API Key 永不随结果导出。 |

## 3. 目标工程结构

建议初始即按 feature 物理拆分，但避免在一期建立过多无业务的模块。

    RelayTesterAndroid/
    ├── app/                    # Application、主导航、Hilt 装配
    ├── core/
    │   ├── model/              # 稳定领域模型与错误模型
    │   ├── network/            # OkHttp、JSON、URL/响应大小限制、日志脱敏
    │   ├── security/           # SecretStore、Android Keystore 实现
    │   ├── database/           # Room 实体、DAO、迁移
    │   └── ui/                 # 主题、通用 M3 组件
    ├── feature/
    │   ├── suppliers/          # 站点配置与密钥录入
    │   ├── tester/             # 模型列表、批量任务、结果与导出
    │   └── balance/            # 预留：余额页、查询/计算适配器
    └── build-logic/            # 可选：统一 Gradle 约定插件

模块依赖只能由 feature 指向 core；tester 与 balance 不能互相依赖。两者通过 core:model 中的 SupplierProfile、UsageRecord、BalanceSnapshot 和稳定接口协作。这一点是将来接入余额页面时不返工的关键。

## 4. 领域模型与一期协议接口

### 4.1 供应商与密钥

    data class SupplierProfile(
        val id: String,
        val displayName: String,
        val baseUrl: String,
        val protocol: RelayProtocol,
        val apiKeySecretId: String?,
        val balanceProviderId: String? = null,
        val createdAt: Instant,
        val updatedAt: Instant
    )

    enum class RelayProtocol { CHAT_COMPLETIONS, RESPONSES, ANTHROPIC }

    interface SecretStore {
        suspend fun put(secret: String): String       // 返回 secretId
        suspend fun get(secretId: String): String?
        suspend fun delete(secretId: String)
    }

必须保证 SupplierProfile 的 JSON 导出、日志、崩溃报告、Room 数据库中都没有 apiKey 明文。编辑密钥时显示“已保存”而不是回显全部密钥；只允许用户主动替换或删除。

### 4.2 三协议适配器

    interface RelayProtocolAdapter {
        val protocol: RelayProtocol

        suspend fun fetchModels(profile: SupplierProfile): List<String>

        suspend fun test(
            profile: SupplierProfile,
            request: ModelTestRequest
        ): ModelTestResult
    }

    data class ModelTestRequest(
        val model: String,
        val prompt: String,
        val maxTokens: Int,
        val timeout: Duration
    )

    data class ModelTestResult(
        val model: String,
        val status: TestStatus,
        val latencyMs: Long?,
        val httpStatus: Int?,
        val finishReason: String?,
        val usage: TokenUsage?,
        val error: RelayError?
    )

    enum class TestStatus { PENDING, SUCCESS, FAILED }

    data class TokenUsage(
        val inputTokens: Long?,
        val outputTokens: Long?,
        val totalTokens: Long?
    )

    sealed interface RelayError {
        data object Authentication : RelayError
        data object InsufficientQuota : RelayError
        data object Forbidden : RelayError
        data object ModelNotFound : RelayError
        data object RateLimited : RelayError
        data object Timeout : RelayError
        data object Network : RelayError
        data class Upstream(val code: Int?, val safeMessage: String) : RelayError
        data class InvalidResponse(val safeMessage: String) : RelayError
    }

每个协议适配器只负责“请求构建 + 响应结构解析”。HTTP、超时、响应最大长度、错误消息脱敏由统一的 RelayHttpClient 负责，不能散落在三个适配器中。

### 4.3 批量测试用例

    interface RunModelTestsUseCase {
        fun execute(config: BatchTestConfig): Flow<BatchTestEvent>
    }

    data class BatchTestConfig(
        val supplierId: String,
        val models: List<String>,
        val prompt: String,
        val maxTokens: Int,
        val timeout: Duration,
        val concurrency: Int,
        val retryPolicy: RetryPolicy,
        val rateLimit: RateLimitPolicy,
        val batchSize: Int,
        val pauseBetweenBatches: Duration
    )

    sealed interface BatchTestEvent {
        data class Started(val total: Int) : BatchTestEvent
        data class ModelFinished(val result: ModelTestResult) : BatchTestEvent
        data class Progress(val completed: Int, val total: Int) : BatchTestEvent
        data class Finished(val summary: TestRunSummary) : BatchTestEvent
    }

实现方式是 viewModelScope 中收集 Flow，在 IO 线程通过 Semaphore 控制真实并发；每个请求前随机 delay，批次之间暂停。离开页面或用户取消时取消父协程，OkHttp Call 必须同步取消。不要用 WorkManager 跑用户正在等待的前台批量测试。

## 5. 余额模块：现在预留、以后接入

### 5.1 为什么必须独立适配

模型调用协议标准化程度较高，余额却不是：

- 部分站点只有网页后台，无公开余额 API；
- 有的返回金额，有的返回额度或 token，有的同时有订阅余额、赠金和已用金额；
- 货币、精度、时区、充值规则和模型价格都可能不同；
- “当前余额”与“按 token 估算可用余额”是两个不同概念。

所以余额模块要分为“读取”与“计算”两层，任何一层都不能假设某个站点的 URL 或 JSON 字段。

### 5.2 稳定扩展点

    interface BalanceProvider {
        val id: String
        val displayName: String

        fun supports(profile: SupplierProfile): Boolean

        suspend fun fetchBalance(profile: SupplierProfile): BalanceFetchResult
    }

    data class BalanceSnapshot(
        val supplierId: String,
        val available: BigDecimal?,
        val currency: String?,
        val totalCredit: BigDecimal?,
        val usedCredit: BigDecimal?,
        val quota: BigDecimal?,
        val quotaUnit: String?,
        val capturedAt: Instant,
        val source: BalanceSource,
        val rawSchemaVersion: Int
    )

    sealed interface BalanceFetchResult {
        data class Success(val snapshot: BalanceSnapshot) : BalanceFetchResult
        data class Unsupported(val reason: String) : BalanceFetchResult
        data class Failure(val error: BalanceError) : BalanceFetchResult
    }

    interface BalanceCalculator {
        val id: String
        fun calculate(input: BalanceCalculationInput): BalanceCalculation
    }

    data class BalanceCalculationInput(
        val snapshot: BalanceSnapshot?,
        val usage: List<UsageRecord>,
        val pricingRule: PricingRule?,
        val at: Instant
    )

一个 BalanceProvider 对应“怎样安全地向某类站点取原始数据”；一个 BalanceCalculator 对应“怎样把余额、用量和价格规则算成页面展示指标”。例如可先有 ManualBalanceProvider（用户手工录入）和 DefaultBalanceCalculator，再按确定的站点 API 新增 XxxBalanceProvider。测试模块完全不需要修改。

BalanceProvider 注册表由 Hilt 注入 Set<BalanceProvider>；SupplierProfile.balanceProviderId 决定选用哪个实现。新增站点时只新增一个适配器、对应的契约测试和可能的展示文案，不修改 tester。

### 5.3 余额页面的集成方式

应用始终保持一个根导航图：

    供应商选择/编辑
             │
             ├── 模型测试（一期上线）
             │      └── 测试历史/结果详情
             │
             └── 余额（预留 destination，二期显示入口）
                    ├── 当前余额卡片
                    ├── 余额构成/额度说明
                    ├── 用量与本地估算
                    └── 刷新历史/趋势

一期在供应商详情页只显示“余额能力：未配置 / 手工录入 / 已接入”，不展示空白底部导航项。接入至少一种可靠的余额 Provider 后，再打开余额页面入口。这样保留导航和模块接口，但不会给用户一个不能工作的页面。

余额页面的数字一律标注来源和更新时间，例如“API 查询，2026-08-29 19:10”或“基于本地 token 与价格表的估算”。API 查询值与估算值不能混为“真实余额”。

## 6. Compose 页面与状态

一期采用单 Activity、纵向滚动的 Material 3 工具型界面，重点是高密度可读性，不需要把 H5 的视觉样式逐像素搬运。

| 页面 | 关键组件 | 状态来源 |
|---|---|---|
| 站点列表 | 卡片、添加/删除、当前站点标记 | SupplierListViewModel |
| 站点编辑 | 文本框、协议分段按钮、密钥隐藏/替换 | SupplierEditorViewModel |
| 测试配置 | 超时、并发、Prompt、模型过滤、高级参数 | TesterViewModel |
| 模型列表 | LazyColumn、数量、关键词匹配提示 | TesterUiState |
| 运行结果 | LinearProgressIndicator、统计卡、筛选 Chip、分组列表 | TesterUiState |
| 结果详情 | HTTP 状态、脱敏错误、延迟、token、重测操作 | TestHistoryViewModel |
| 余额（后续） | Snapshot 卡、构成列表、估算表、刷新记录 | BalanceViewModel |

所有输入控件不少于 48dp 触控尺寸；正文对比度至少 4.5:1；支持浅色/深色主题；密钥和余额的可访问性标签不包含明文。结果列表使用 LazyColumn，不能为几百个模型一次性创建完整 View。

## 7. 网络、安全与可靠性要求

1. Release 使用 network security config 禁止明文流量。若确有局域网调试需要，只在 debug product flavor 显式放行。
2. 使用用户输入的 Base URL 前做规范化与校验：补 https、移除结尾斜杠、拒绝空 host；URL 只接受 http/https。Release 默认拒绝 http。
3. OkHttp 设置连接/读取/写入/调用总超时，并限制成功或错误响应体大小；错误文本截断、脱敏后再显示。
4. 所有日志都经过 RedactingLogger：Authorization、x-api-key、疑似 sk- 前缀、JSON 中的 key/api_key 字段必须替换为 ***。
5. 导出 JSON 仅包含站点显示名、Base URL、协议、时间、统计和测试结果，绝不包含 secretId、API Key、原始 Authorization 头、完整上游响应。
6. 不做固定证书锁定。站点由用户自定义，固定 pin 会在站点证书轮换时大面积失效；使用系统 TLS 校验即可。
7. 对 401 / 402 / 403 / 404 立即失败；对网络错误、超时、429、5xx 用带上限的指数退避并遵从 Retry-After（若有）。并发上限建议 UI 设为 1–20。
8. 未得到用户动作时不要后台上传 Key、统计、测试结果或余额数据。若未来加入崩溃上报，必须先审查脱敏规则。

## 8. 数据表与迁移边界

| 表/存储 | 主要字段 | 保留策略 |
|---|---|---|
| suppliers | id、名称、URL、协议、apiKeySecretId、balanceProviderId | 长期保留，可导入/导出但不导出密钥。 |
| test_runs | id、supplierId、开始/结束时间、配置快照、聚合结果 | 建议保留最近 90 天或由用户清理。 |
| test_results | runId、模型、状态、延迟、token、错误类别 | 与 test_runs 级联删除。 |
| balance_snapshots | supplierId、金额/额度、来源、抓取时间、schema 版本 | 为余额趋势和估算准备。 |
| DataStore | UI 偏好、排序、非敏感默认测试参数 | 不存密钥、Cookie 或完整响应。 |
| SecretStore | API Key 密文 | 仅由 Keystore 解封；删除站点时删除。 |

供应商、运行历史和余额快照须以 Room migration 维护，不允许“升级时清库”作为默认方案。余额 Provider 的 JSON 解析原文不落库；如确有调试需求，必须用户主动开启、二次确认并可一键清除。

## 9. 交付顺序与验收标准

### 阶段 A：原生模型测试 MVP

1. 建立项目、版本目录、debug/release flavor、主题和根导航。
2. 完成 SupplierProfile、Keystore SecretStore、站点 CRUD。
3. 实现三个 RelayProtocolAdapter 和模型获取。
4. 实现 BatchTest UseCase、取消、并发、限速、结果分类。
5. 完成 Compose 测试页面、结果筛选、复制、无密钥 JSON 导出。

验收：对同一测试站点，Android 的模型数量、协议请求形状、成功/失败判定、token 统计与源 H5 一致；取消任务后不再发起新请求；重启应用仍能看到非敏感站点和历史。

### 阶段 B：可靠性与可维护性

1. Room 持久化任务历史；失败重试分级；请求/错误脱敏。
2. 为每个协议适配器提供 MockWebServer 契约测试，覆盖正常、401、402、429、非 JSON、超时和协议错配。
3. 为并发池、统计、错误分类、导出编写 JVM 单元测试；为“运行—取消—重测失败”编写 Compose UI 测试。

验收：密钥不出现在数据库、日志、导出文件和测试快照；20 路设置下同时在途请求不超过 20；所有取消、异常均恢复可操作界面。

### 阶段 C：余额能力

1. 获得每一种目标站点的官方余额 API 文档或不含密钥的请求/响应样本。
2. 为具体站点实现 BalanceProvider、JSON 契约测试和错误映射。
3. 先上线“当前余额 + 来源 + 更新时间 + 手动刷新”；确认价格规则后再上线本地估算、趋势与提醒。

验收：余额页面不向不支持的站点伪造数值；返回金额与站点后台一致；计算结果能显示采用的价格规则、币种、时间和数据来源。

## 10. 推荐官方资料

- Android app architecture: https://developer.android.com/topic/architecture
- Jetpack Compose: https://developer.android.com/develop/ui/compose
- Material 3 for Compose: https://developer.android.com/develop/ui/compose/designsystems/material3
- Navigation Compose: https://developer.android.com/develop/ui/compose/navigation
- ViewModel and StateFlow: https://developer.android.com/topic/libraries/architecture/viewmodel
- Kotlin coroutines: https://kotlinlang.org/docs/coroutines-overview.html
- Room: https://developer.android.com/training/data-storage/room
- DataStore: https://developer.android.com/topic/libraries/architecture/datastore
- Android Keystore: https://developer.android.com/privacy-and-security/keystore
- Network security configuration: https://developer.android.com/privacy-and-security/security-config
- Hilt dependency injection: https://developer.android.com/training/dependency-injection/hilt-android
- WorkManager（余额定时任务阶段再使用）: https://developer.android.com/develop/background-work/background-tasks/persistent

## 11. 开工前唯一需要补齐的业务输入

模型测试可立即按照本方案开发。余额模块在没有站点专有规则前只能保留接口，不能可靠地猜测。开始阶段 C 前，请提供每个要支持站点的以下任一材料：

1. 官方余额 API 文档；或
2. 从该站点后台抓取的、已删除 API Key / Cookie / 用户信息的请求和响应样本；并说明页面中的“余额”是金额、赠金、额度还是 token。

这样可以为每种站点建立显式的 BalanceProvider，而不会以网页抓取或猜测字段的方式损害安全性和正确性。
