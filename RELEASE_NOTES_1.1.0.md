# Relay Tester Android 1.1.0

发布日期：2026-08-31

## 模型筛选

- 模型名过滤与测试结果搜索支持 `|` OR 分隔符，例如 `gpt|claude`；全角 `｜` 也可用。
- 新增每个供应商独立保存的快捷筛选词，可添加、删除和多选；快捷词与文本筛选统一按 OR 匹配。
- 开始测试会准确使用当前筛选范围，避免不需要的模型请求。
- 快捷筛选词随加密配置备份迁移；当前选中状态保持为页面会话状态，不会意外带入其他供应商。

## 余额查询

- 成功的余额查询结果默认保留在本机，退出并重新打开应用后可恢复最近一次结果。
- 快照仅包含展示所需的余额、模板、套餐、时间和延迟信息，不含 API Key、PAT、用户 ID、URL、请求头或响应原文。
- 切换、更新或删除余额模板，以及删除供应商时，会同步清理不再有效的本地快照。
- 双栏余额卡重排为“圆环右上、已选右下、金额主列全宽”，长金额不再被圆环挤压。

## 版本

- versionName：`1.1.0`
- versionCode：`10100`
- 优化体验包：`app-optimized.apk`（debug 签名，包名 `com.relaytester.app.debug`）

## 验证

- `compileDebugKotlin`、`lintDebug`、`testDebugUnitTest`、`assembleDebug`、`assembleOptimized` 均通过；项目当前没有测试源码，因此单元测试任务为 `NO-SOURCE`。
- `app-optimized.apk` 已在隔离的 `RelayTesterApi35` API 35 模拟器上通过 `adb install -r` 覆盖安装，主界面和余额查询页均正常渲染。
- 连续 5 次冷启动耗时为 359–470 ms，崩溃缓冲为空，未发现 ANR；验证未调用真实模型或余额 API，也未导入、导出或清除数据。
- 优化 APK 大小为 1,602,377 bytes，SHA-256：`80B2A7188822EC9F1878FDB9A17A98F9484874B4427C8ADBEE79847D133D1DA0`。
- 正式 release APK 仍未使用发布签名密钥，因此不会作为 Release 附件上传。
