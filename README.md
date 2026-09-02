# Relay Tester Android

Android 原生版中转站模型批量测试器。它由应用直接向用户配置的 API 中转站发起请求，不依赖原 H5 项目中的 Flask 跨域代理。

## 已实现

- 多供应商配置：供应商名称、Base URL、协议、模型列表和测试参数分别保存；
- API Key 使用 Android Keystore 加密，配置数据库只保存密钥引用；
- 三种协议：OpenAI Chat Completions（/chat/completions）、OpenAI Responses（/responses）、Anthropic Messages（/messages）；
- 请求模型列表：GET /models，兼容 data / models 数组；
- 批量测试：并发（1–20）、超时、随机请求间隔、分批、批间暂停、失败重试和取消；
- 错误分类：认证、余额不足、无权限、模型不存在、限流、超时、网络、响应格式和上游错误；
- 实时显示每个模型的状态、延迟、完成原因与 token；
- 结果筛选、支持 `|` 的 OR 搜索、每个供应商可保存的多选快捷模型词、按名称/延迟排序、复制可用或失败模型、导出测试结果 JSON（不含 API Key、PAT 或用户 ID）；
- 深色/浅色 Material 3 主题，页面对窄屏保持 48dp 以上交互目标。
- 品牌图标已重绘为“中继节点 + 验证勾”的原生矢量标识；Adaptive Icon、Android 12+ 系统启动面和顶部品牌位共用同一套图形语言。系统 Splash 是唯一启动面，不再插入应用内“正在准备”加载页，因此不会出现两张连续启动页面。
- 供应商选择卡由 52dp 最小高度的居中容器承载两行内容，名称与“协议 / 模型数”的文本组按卡片可用高度严格居中；卡片右上角使用 48dp 点击区承载紧凑的站点配置图标，标题栏的添加和删除图标保持不变。协议与 Prompt 选择器为等宽、8dp 等距的紧凑胶囊，点击不会出现超出控件边界的浅色覆盖层。
- 余额页导航统一命名为“余额查询”；模板安全边界采用更清晰的 8dp 圆形强调标记。系统 Splash 保留一次性品牌验证环动效，并在 Compose 首帧可用时直接交接至主界面。
- 测试 Prompt 保留初始源码的快捷预设：`ping`、`Hi`、`Say OK`、`1+1=?`、`回复ok`；仍可自定义输入。
- 点击“获取模型”后，模型直接进入与测试结果共用的纵向列表：测试前只显示“已获取，尚未开始测试”，开始后在原卡片位置显示状态、延迟、Token 和错误详情；测试前不会显示汇总、筛选、复制或导出操作。
- 供应商选择改为双栏卡片，不需要横向滑动；模型批量回调最多每 120ms 向界面发布一次快照，降低大量模型完成时的列表重组压力。
- 启动恢复分两阶段在后台完成：先读取 DataStore/JSON 并显示供应商和基础配置，再读取当前站点的 Keystore 凭据；凭据补齐期间保存、切换、请求与配置迁移会被安全禁用，避免空值覆盖 API Key/PAT。网络客户端、批量测试器和屏幕外长表单均按需延后创建/组合，降低刚打开应用时的集中加载卡顿。
- 供应商卡片按两行内容收紧为 52dp 自适应最小高度，名称使用半粗体、模型数使用强调色；卡片右上角提供图标化站点配置入口。余额卡使用 148dp 高度容纳分行显示的可用/总额，选中状态以蓝色边框与容器色表达，标题行右侧提供图标化查询凭据入口，已用圆环缩至 36dp，避免挤压金额。
- 余额模板选择器提供受限高度的可滚动菜单；模板名与“内置 / 自定义”标签同行，模板安全边界以三条简洁规则展示。
- 完整配置备份与迁移：可从模型测试或余额查询右上角的“导入或导出配置”入口迁移全部供应商、模型、测试参数、余额模板、API Key、PAT、用户 ID 与当前站点。备份文件为用户密码加密的 `.rtbackup`，导入会先预览摘要、再二次确认覆盖；首页将“导出 / 导入 / 关闭”放在同一行。
- 模型列表可按单项勾选，提供全选与取消全选；测试、重测与失败重试只会请求已勾选的模型。测试参数右侧的“高级测试”弹窗可将多个供应商的模型集中为条目，并支持编辑、删除和单条来源测试。
- 站点配置由供应商卡片右上角图标以弹窗打开；余额 PAT 与用户 ID 由余额卡供应商名称后的图标以弹窗打开。卡片单击切换站点，双击分别拉取模型或查询余额。

### 余额查询（二期）

- 顶栏提供“模型测试 / 余额查询”两个功能页；余额查询 PAT 独立于模型测试 API Key，二者均使用 Keystore 加密保存；
- 内置 `new-api` 查询模板：`GET /api/user/self`、`Authorization: Bearer {{accessToken}}`、`User-Agent: RelayTester/1.0`、`data.quota`、`data.used_quota` 与 `data.group`；
- 余额页同时展示全部供应商的双栏卡片。每张卡片显示可用余额、总额和已用比例圆环；圆环固定在右上角、已选状态固定在右下角，避免长金额挤压主信息列。点击卡片切换当前站点，支持顺序批量查询，单个站点失败不会中断其余站点。
- 每个站点可配置余额查询访问令牌（PAT）和**可选**用户 ID；仅填写用户 ID 时才发送 `New-Api-User`，结果自动展示套餐、可用、已用和总额度（`quota + used_quota`）；
- 最近一次成功余额查询会以仅展示的数据快照保存到本机；重新打开应用后会恢复，切换/更新余额模板、删除供应商或删除模板时会同步清理失效快照。快照不包含 API Key、PAT 或用户 ID，也不会写入配置备份。
- 每个供应商可以单独选择余额模板；新建、编辑、复制、删除自定义模板均在应用内完成；
- 模板可配置 GET / POST、站内 HTTPS 请求地址、请求头、Body、可用/已用/总额/币种 JSON 路径、单位、换算除数与成功标记；
- 模板可选择“参数配置”或“查询脚本”。脚本遵循 cc-switch 风格的 `request + extractor` 结构，只能描述同站请求和 JSON 映射；实际 HTTP 请求仍由应用校验并发起；
- 内置模板不可删除；点击“复制为自定义模板”后才创建可编辑副本，避免默认适配被覆盖；
- 查询结果显示“API 查询”来源、模板名、刷新时间、延迟、原始额度与换算值，不把本地值标成真实余额。

完整字段说明与 `new-api` 使用前提见 [BALANCE_TEMPLATE_GUIDE.md](BALANCE_TEMPLATE_GUIDE.md)。

## 安全边界

- Release 与 debug 均默认仅允许 HTTPS；HTTP 中转站会被拒绝，以避免 API Key 明文传输。
- API Key 不写入 DataStore、测试结果、导出 JSON 或日志。
- “导出测试结果 JSON”仅用于导出测试结果，不含 API Key、PAT 或用户 ID；“配置导入与导出”会包含全部配置和凭据，但仅以 PBKDF2 + AES-256-GCM 加密的 `.rtbackup` 文件写出。备份密码不保存，遗失后无法恢复文件内容。
- 网络错误文本会截断和脱敏；响应体限制为 512 KiB。
- 自动重试只针对网络错误、超时、429 与 5xx；401、402、403、404 不会重复消耗请求额度。
- 余额查询脚本没有 Android、Java 或网络 API 权限；应用会拒绝循环、动态执行、模块加载和原型访问等高风险语法，并仅接受受限的请求描述与 JSON 映射结果。
- 余额请求仅允许访问当前供应商相同主机的 HTTPS 地址；认证头必须使用 `{{apiKey}}` 或 `{{accessToken}}` 占位符，不能把实际密钥写入模板。

## 构建

使用 Android Studio 打开本目录，选择 JDK 17，并执行：

    .\gradlew.bat assembleDebug

若 Windows 上的 Oracle JDK 17 在构建启动阶段报 `Unable to establish loopback connection`，使用项目内的 TCP 回环兼容包装脚本：

    .\tools\build-android.ps1 assembleDebug

该脚本只在本次构建中注入 JVM 代理，构建结束后会恢复 `JAVA_TOOL_OPTIONS`；代理产物写入已忽略的 `build/` 目录。

生成的调试包位于 app/build/outputs/apk/debug/app-debug.apk。

当前源码版本为 `1.1.0`（versionCode `10100`）。日常开发构建为 `1.1.0-debug`；用于体验启动性能的优化构建为 `1.1.0-optimized`，二者均使用 `com.relaytester.app.debug` 包名，可覆盖同签名的旧调试包而无需清除应用数据。

`1.1.0` 延续并通过同一套离线构建门禁：`lintDebug`、`testDebugUnitTest`、`assembleDebug`、`assembleOptimized` 与 `assembleRelease`。优化体验包位于 `app/build/outputs/apk/optimized/app-optimized.apk`，约 2.7 MiB；正式 release 包位于 `app/build/outputs/apk/release/app-release-unsigned.apk`，已启用 R8 与资源收缩，但在使用前必须由发布者使用自己的正式签名密钥签名。GitHub Release 仅发布精简优化包 `RelayTester-1.1.0.apk`。上一公开版本 `1.0.0` 的验证资产 SHA-256 记录保留在 [RELEASE_NOTES_1.0.0.md](RELEASE_NOTES_1.0.0.md)。

优化体验包已在隔离的 API 34 模拟器以 `adb install -r` 覆盖安装，连续五次冷进程启动均成功进入 `MainActivity`，后四次平均启动时间为 497 ms；`AndroidRuntime` 与崩溃缓冲均未发现异常。为保护现有配置，验证未调用模型或余额 API，也未导入、导出或清除数据。

本机当前目录包含中文字符，因此工程设置了 android.overridePathCheck=true。这是 Android Gradle Plugin 对 Windows 非 ASCII 路径的兼容开关；若将项目迁移到纯英文路径，可以移除它。

## 使用

1. 添加供应商，填写地址（填至 /v1 层级）、协议和 API Key。
2. 点击“获取模型”；模型列表会按供应商保存，并立即以纵向待测卡片显示。
3. 按需设置超时、并发、Prompt 和模型名过滤。使用 `gpt,claude` 可按 OR 匹配（旧配置中的 `|` 仍兼容）；可在“快捷筛选”中添加常用词，多选也按 OR 匹配。高级设置中可调整限速、批次和重试。
4. 点击“开始测试”，运行期间同一批卡片原位更新；完成后可筛选、复制或导出结果。
5. 如需余额，在“余额查询”页选择要配置的站点，保存 PAT；仅站点要求时再填写用户 ID，然后可查询当前站点或批量查询全部站点。
6. 如需迁移全部配置，点击右上角“导入或导出配置”：导出时设置并妥善保存至少 12 个字符的独立密码；导入时先选择 `.rtbackup`、输入密码查看摘要，再次输入密码确认覆盖。导入不会自动请求任何站点。

## 后续扩展

余额模板和查询结果已与模型测试分层；后续可在不改变现有模板数据的情况下增加余额历史、阈值提醒、站点分组或本地汇总计算。完整的实施、验收和模拟器验证记录见 [DEVELOPMENT_IMPLEMENTATION_SPEC.md](DEVELOPMENT_IMPLEMENTATION_SPEC.md)，本次筛选与余额快照规范见 [MODEL_FILTER_BALANCE_PERSISTENCE_V1_1_SPEC.md](MODEL_FILTER_BALANCE_PERSISTENCE_V1_1_SPEC.md)，当前紧凑交互、统一启动与品牌标识规范见 [UI_STARTUP_POLISH_V6_SPEC.md](UI_STARTUP_POLISH_V6_SPEC.md)，完整配置迁移规范见 [CONFIG_BACKUP_IMPORT_EXPORT_V5_SPEC.md](CONFIG_BACKUP_IMPORT_EXPORT_V5_SPEC.md)。
