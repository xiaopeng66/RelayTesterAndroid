# Relay Tester Android 1.0.0

发布日期：2026-08-30

## 首个公开版本

Relay Tester Android 是一个原生 Android 工具，用于管理多个中转站、拉取模型列表、批量验证模型可用性，并以可配置的模板查询站点余额。

## 核心功能

- 多供应商管理：每个供应商独立保存 Base URL、接口协议、模型列表、测试参数和余额模板；
- 支持 Chat Completions、Responses 与 Anthropic Messages 三种模型测试协议；
- 模型列表拉取、并发批量测试、限速、重试、筛选、排序、复制和安全结果导出；
- `new-api` 内置余额模板，以及可新建、复制、编辑、删除的受限模板；
- 单站点与全部站点余额查询，余额访问令牌与模型 API Key 可独立保存，用户 ID 为可选项；
- 完整配置导入导出：供应商、模型、测试参数、模板和凭据均封装在用户密码加密的 `.rtbackup` 文件中；
- API Key 和 PAT 使用 Android Keystore 的 AES-GCM 保护，本地常规配置只保存密钥引用。

## 1.0.0 质量与性能修复

- 保持既有视觉界面不变，优化启动期间的后台数据与 Keystore 调度；
- 模型 API Key 与余额访问令牌并行恢复，并提供请求前按需读取兜底；
- R8 与资源收缩用于优化体验包和正式 release 构建；
- 修复极端本地存储失败时旧密钥可能过早删除的问题：现在新配置成功落盘后才会清理旧密钥；
- 修复保存失败后仍可能继续切换/新增供应商或显示成功提示的问题；
- 对 JSON、HTML 和普通文本上游错误统一脱敏，避免回显的认证信息进入界面或测试结果导出。

## 构建产物

| 产物 | 包名 | 说明 |
| --- | --- | --- |
| `app-optimized.apk` | `com.relaytester.app.debug` | R8 优化、debug 签名，用于体验与覆盖同签名调试包。 |
| `app-release-unsigned.apk` | `com.relaytester.app` | 正式包名、R8 优化，但尚未使用发布密钥签名。 |

正式对外分发前，必须用发布者持有的签名密钥对 `app-release-unsigned.apk` 进行签名；不要将签名密钥、`local.properties`、`.rtbackup`、API Key 或 PAT 提交到仓库。

## 验证摘要

- `lintDebug`：0 errors；
- Debug、optimized、release 三种构建均成功；
- 5 次冷进程启动均进入 `MainActivity`，后 4 次平均为 497 ms；
- API 34 隔离模拟器中无 `AndroidRuntime` 崩溃、无 ANR；
- 模型测试和余额查询主页面已截图回归；验证期间未发起真实模型/余额请求，也没有导入、导出或清除用户数据。
