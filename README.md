# Relay Tester Android

Relay Tester 是一款 Android 原生的多供应商模型测试与余额查询工具，面向经常使用中转站 / API 代理服务的用户。它不依赖外部服务器，直接从手机向用户配置的 API 站点发起请求。

- 当前版本：1.7.3
- 下载地址：[GitHub Release v1.7.3](https://github.com/xiaopeng66/RelayTesterAndroid/releases/tag/v1.7.3)
- 安装包：包名 `com.relaytester.app`，约 2.85 MB；模型指纹检测包按需下载，不占安装包体积
- 支持系统：Android 8.0 及以上

## 核心功能

1. **多供应商管理**：可添加多个供应商，各自维护地址、协议、API Key、模型列表与测试参数，配置可整体导入导出；供应商顺序可在模型测试页拖动调整，模型测试、余额查询与指纹检测三处按同一顺序排列。
2. **模型测试**：支持 Chat Completions / Responses / Anthropic Messages 三种协议，可批量并行刷新模型目录、按并发与重试参数批量测试，并实时显示延迟、Token 用量与失败原因。
3. **高级测试**：可在一个任务里混选多个供应商的模型，支持跨供应商关键词检索、为同一模型配置多个来源并单独重测，站点已下架的模型会被标注「已不存在」。
4. **余额查询**：可为每个供应商配置独立的查询模板（内置 `new-api`），支持批量并行查询并汇总展示可用余额与套餐。
5. **模型指纹检测**：让目标模型写出约 300 个 1–355 的整数，与参考库比对来判断实际响应的是哪个模型，用来核对中转站是否暗中替换了模型；参考库（58 个模型 / 13 个家族）以**检测包**形式提供，打开应用时自动查一次更新，下载后按大小与 SHA-256 校验安装，**评分完全离线、不上传回答**。
6. **配置备份与迁移**：一次性导出全部供应商、模型、参数、余额模板与凭据，支持加密（PBKDF2 + AES-256-GCM）与明文两种格式，导入前先预览摘要。
7. **应用内更新**：顶栏「关于与更新」显示当前版本与线上版本、下载字节进度，校验体积与 SHA-256 后交给系统安装；打开应用时自动检查，查到新版本会自行打开这一页并把「下载并安装」摆在面前，顶栏图标同时亮小红点。
8. **界面与交互**：原生 Jetpack Compose，浅色 / 深色 Material 3 主题，供应商与余额卡片针对手机竖屏优化。

## 安全与隐私

- 应用默认使用 HTTPS 请求；仅当中转站地址显式填写 `http://` 时按明文请求，并给出风险提示。
- API Key、余额查询 PAT 等敏感信息不写入测试结果与历史记录。
- 配置备份可加密存储，采用 PBKDF2 + AES-256-GCM；导出测试结果 JSON 时默认不含 API Key、PAT 或用户 ID。
- **重定向只在同源内跟随**：仅当目标与供应商地址的协议、主机、端口完全一致时才继续，且从不接受 HTTPS 降级为 HTTP；跨域或降级重定向会被直接拒绝并提示。因此 API Key / PAT 不会被 3xx 带到第三方主机。
- **导入余额模板只做静态语法校验，绝不执行脚本**：校验阶段不创建 JS 运行时，只扫描源码，拦截 `eval`、`Function` 构造器、`globalThis`、`WebAssembly`、`importScripts`、`debugger` 等逃逸入口，同时保留 `extractor: function (json) {}` 的正常写法。
- 余额查询脚本在受限 JS 环境（QuickJS）中运行，无法访问 Android、Java 或网络底层 API。诚实说明其边界：QuickJS 的 Android 绑定**没有提供执行中断接口**，所以脚本仅在**用户主动点击查询**时才运行，且始终不在主线程——能限制的是「什么时候跑」和「跑在哪」，不是「跑多久」。请只使用自己信任的模板。
- 供应商配置默认只保存本地，不会上传到任何第三方服务器。

### 已知限制（如实登记）

- **屏幕内容未加 `FLAG_SECURE`**：多任务切换器的预览截图与录屏会包含供应商名称、密钥输入框等界面内容。设置它会让用户自己也无法截图分享界面，权衡后不加。
- **模板可以把密钥写进 URL**：若把 `{{apiKey}}` / `{{accessToken}}` 放进 URL 查询串（部分站点确实这样设计），密钥就会出现在请求 URL 里，可能被上游日志记录。应用不阻止这种写法，请尽量把凭据放在请求头。
- **配置导出不是原子写**：导出走系统文件选择器（SAF），不支持「写临时文件再换名」，写入中途失败会在目标位置留下半个文件；应用会报错，但不会替你清理残件。
- **失败重试会重放非幂等的 POST**：模型测试失败自动重试时重发的是同一个对话请求，若上一次其实已被上游处理、只是响应丢失，重试会再计一次费用。
- **错误脱敏依赖可识别形状**：上游错误中的凭据字段、Bearer、`API key provided` 等话术以及常见 `sk-` / GitHub token 会替换为 `***`；它不会按本次提交的密钥逐值比对，任意无前缀裸值仍可能漏网。不要把报错原文贴给不可信的第三方。

## 构建开发

使用 Android Studio 打开本目录，选择 JDK 17，并执行：

    .\gradlew.bat assembleDebug

若 Windows 上的 Oracle JDK 17 在构建启动阶段报 `Unable to establish loopback connection`，可使用项目内的 TCP 回环兼容包装脚本：

    .\tools\build-android.ps1 assembleDebug

调试包位于 `app/build/outputs/apk/debug/app-debug.apk`，发布用的优化包位于 `app/build/outputs/apk/optimized/app-optimized.apk`（包名 `com.relaytester.app`）。发布包的签名口令只放在仓库外（`~/.gradle/gradle.properties` 或构建时传入），不进版本库。

## 发行版本

- 最新：[v1.7.3 Release](https://github.com/xiaopeng66/RelayTesterAndroid/releases/tag/v1.7.3) · [发行说明](release-notes/RELEASE_NOTES_1.7.3.md)
  - `RelayTester-v1.7.3-android.apk` · `2,991,079` 字节 · SHA-256 `e8b496c4477156a64615c17cdae3c9e932eb58a02f2d47e7d603365731edea6c`

历史版本（每个版本的说明页写明了该版当前安装包的字节数与 SHA-256）：

- [v1.7.2](https://github.com/xiaopeng66/RelayTesterAndroid/releases/tag/v1.7.2) · [发行说明](release-notes/RELEASE_NOTES_1.7.2.md)

- [v1.7.1](https://github.com/xiaopeng66/RelayTesterAndroid/releases/tag/v1.7.1) · [发行说明](release-notes/RELEASE_NOTES_1.7.1.md)

- [v1.7.0](https://github.com/xiaopeng66/RelayTesterAndroid/releases/tag/v1.7.0) · [发行说明](release-notes/RELEASE_NOTES_1.7.0.md)
- [v1.6.0](https://github.com/xiaopeng66/RelayTesterAndroid/releases/tag/v1.6.0) · [发行说明](release-notes/RELEASE_NOTES_1.6.0.md)
- [v1.5.0](https://github.com/xiaopeng66/RelayTesterAndroid/releases/tag/v1.5.0) · [发行说明](release-notes/RELEASE_NOTES_1.5.0.md)
- [v1.4.0](https://github.com/xiaopeng66/RelayTesterAndroid/releases/tag/v1.4.0) · [发行说明](release-notes/RELEASE_NOTES_1.4.0.md)
- [v1.3.0](https://github.com/xiaopeng66/RelayTesterAndroid/releases/tag/v1.3.0) · [发行说明](release-notes/RELEASE_NOTES_1.3.0.md)
- [v1.2.0](https://github.com/xiaopeng66/RelayTesterAndroid/releases/tag/v1.2.0) · [发行说明](release-notes/RELEASE_NOTES_1.2.0.md)
- [v1.1.0](https://github.com/xiaopeng66/RelayTesterAndroid/releases/tag/v1.1.0) · [发行说明](release-notes/RELEASE_NOTES_1.1.0.md)
- [v1.0.0](https://github.com/xiaopeng66/RelayTesterAndroid/releases/tag/v1.0.0) · [发行说明](release-notes/RELEASE_NOTES_1.0.0.md)

## 更多文档

- [各版本发行说明](release-notes/)
- [第三方许可声明](THIRD_PARTY_NOTICES.md)
