# Relay Tester Android

Relay Tester 是一款 Android 原生的多供应商模型测试与余额查询工具，面向经常使用中转站 / API 代理服务的用户。它不依赖外部服务器，直接从手机向用户配置的 API 站点发起请求，支持模型可用性验证、批量测试、余额查询与配置迁移。

- 当前版本：1.2.0
- 下载地址：[GitHub Release v1.2.0](https://github.com/xiaopeng66/RelayTesterAndroid/releases/tag/v1.2.0)
- APK 大小：约 2.69 MB
- 支持系统：Android 8.0 及以上

## 核心功能

### 1. 多供应商管理

- 支持添加多个供应商，独立维护站点名称、Base URL、协议、API Key、模型列表和测试参数。
- API Key 与余额查询令牌分别保存，均可加密存放在 Android Keystore。
- 支持同一站点切换不同模型配置，不必重复搭建。
- 站点配置可导入导出，便于备份和迁移。

### 2. 模型测试

- 支持 OpenAI Chat Completions、OpenAI Responses 与 Anthropic Messages 三种常用协议。
- 支持 `GET /models` 模型列表拉取，可自动识别站点的模型目录。
- 批量测试时可配置并发数、超时时间、请求间隔、分批数量、批间暂停、失败重试与取消。
- 实时显示每个模型的成功 / 失败状态、延迟、完成原因、Token 用量和错误类型。
- 支持按名称 / 状态 / 延迟 / 错误类型筛选，支持多关键词搜索、结果排序、复制与结果导出。
- 支持失败原因分类，如认证失败、余额不足、无权限、模型不存在、限流、超时、网络错误、响应格式异常和上游错误。

### 3. 高级测试

- 支持在一个测试任务中同时选择多个供应商的模型。
- 支持跨供应商关键词检索：输入模型名称关键词后，自动拉取当前所有供应商的模型目录，并列出匹配结果与来源。
- 支持从检索列表中手动勾选，一键添加到测试任务。
- 支持对同一模型配置多个来源，并单独测试某个来源的连通性与响应。
- 支持对已有来源进行编辑、删除、重命名和单条测试。

### 4. 余额查询

- 支持为不同供应商配置独立的余额查询模板。
- 内置 `new-api` 模板，可基于访问令牌查询可用余额、已用额度与分组信息。
- 支持自定义余额模板，可配置请求方法、请求头、请求体、JSON 路径与换算方式。
- 支持批量并行查询多个供应商的余额，结果自动汇总。
- 余额结果以卡片形式展示，包含供应商、可用余额、分组、刷新时间、原始额度与换算后数值。
- 支持双栏布局，减少超长站点列表的滚动成本。

### 5. 配置备份与迁移

- 支持将全部供应商、模型、测试参数、余额模板、API Key、PAT、用户 ID 与当前站点一次性导出。
- 支持加密备份与明文备份，可根据需要选择是否加密。
- 导入时可自动识别备份格式，先预览摘要，再确认覆盖，降低误操作风险。
- 导入完成不会自动请求任何站点，避免恢复配置时意外消耗请求额度或余额。

### 6. 用户体验

- 原生 Jetpack Compose 界面，支持浅色 / 深色 Material 3 主题。
- 供应商卡片、余额卡片和结果列表均针对手机竖屏优化，常用按钮保持足够大的触控区域。
- 支持双栏布局、抽屉、弹窗和快速筛选，方便小屏幕下高效操作。
- 支持快速双击卡片刷新，常用操作无需进入深层菜单。
- 启动页与品牌图标采用同一套视觉语言，减少应用内的多余过渡页。

## 安全与隐私

- 应用默认仅允许 HTTPS 请求，避免 API Key 与请求内容被明文传输。
- API Key、余额查询 PAT 等敏感信息不写入测试结果和历史记录。
- 配置备份可加密存储，采用 PBKDF2 + AES-256-GCM。
- 导出测试结果 JSON 时默认不含 API Key、PAT 或用户 ID。
- 余额查询脚本运行在受限环境中，无法访问 Android、Java 或网络底层 API。
- 站点配置默认只保存本地，不会上传到任何第三方服务器。

## 使用方式

1. 安装 APK 后打开应用，进入“模型测试”页面。
2. 点击“添加供应商”，填写站点名称、Base URL、协议与 API Key。
3. 点击“获取模型”拉取模型列表，或者在“高级测试”中手动选择模型。
4. 按需调整测试参数，例如超时、并发、Prompt、过滤词、重试和分批数量。
5. 开始测试后，可实时查看每个模型的响应结果和错误信息。
6. 切换到“余额查询”页面，配置模板与访问令牌，即可查询余额。
7. 通过右上角“导入或导出配置”功能，可迁移全部配置到其他设备。

## 构建开发

使用 Android Studio 打开本目录，选择 JDK 17，并执行：

    .\gradlew.bat assembleDebug

若 Windows 上的 Oracle JDK 17 在构建启动阶段报 `Unable to establish loopback connection`，可使用项目内的 TCP 回环兼容包装脚本：

    .\tools\build-android.ps1 assembleDebug

调试包位于 `app/build/outputs/apk/debug/app-debug.apk`，优化包位于 `app/build/outputs/apk/optimized/app-optimized.apk`。

有关完整实现细节，可查看以下文档：

- [开发实施规范](DEVELOPMENT_IMPLEMENTATION_SPEC.md)
- [余额模板指南](BALANCE_TEMPLATE_GUIDE.md)
- [配置备份与迁移](CONFIG_BACKUP_IMPORT_EXPORT_V5_SPEC.md)
- [UI 优化规范](UI_STARTUP_POLISH_V6_SPEC.md)

## 发行版本

- [v1.2.0 Release](https://github.com/xiaopeng66/RelayTesterAndroid/releases/tag/v1.2.0)
- APK：`RelayTester-v1.2.0-android.apk`
- SHA-256：`6ac061abd873867a14dd6c04a3055a91dbc2fbdefc7378a5021877a373e92fed`

若有新功能需求或问题反馈，请提交 Issue，或直接在本仓库中继续开发。