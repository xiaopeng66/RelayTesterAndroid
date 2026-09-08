# UI Optimization V7 Development Specification

Version: 1.2.0

## Goals

This iteration improves the model tester, advanced test dialog, balance query screen, and configuration backup workflow without changing existing domain behavior.

## Design Rules

- Use Material 3 spacing and container roles consistently.
- Keep all primary touch targets at least 44–48 dp high.
- Preserve the current navigation and data models.
- Keep provider cards aligned in two columns on normal-width screens.
- Prefer the semantic color scheme for success, failure, loading, and selected states.
- Keep destructive actions confirmed and visually distinct.

## Feature Design

### Model Tester

- Keep the provider title row vertically centered with add/delete actions.
- Keep provider cards in a two-column `FlowRow`.
- Group testing settings into a collapsible card:
  - Always visible: timeout and concurrency.
  - Collapsed under "更多测试参数": prompt, keyword, maximum output tokens, retry, request pacing, and batch settings.
- Keep already-configured values untouched when expanding or collapsing.

### Advanced Test Dialog

- Divide content into three clear areas:
  1. Model name and source management.
  2. Cross-supplier search.
  3. Manual provider/model selection and current sources.
- Keep the cross-supplier result list bounded and scrollable.
- Include a source label and bold provider name for each result.
- Add selected results without changing the active provider or regular model test selection.

### Balance Query

- Render provider cards with an adaptive grid using a 240 dp minimum column width.
- Remove the total balance metric from provider cards.
- Keep only available balance, status/update text, usage ring, and credential edit action.
- Batch queries run providers concurrently while maintaining per-provider loading state and aggregate progress.

### Configuration Backup

- Add an "加密备份" checkbox on the backup home dialog.
- Encrypted export keeps the existing password flow and format version 1.
- Unencrypted export writes a version 2 JSON envelope:
  - `format`: `relay-tester-backup`
  - `version`: `2`
  - `payload`: the existing validated configuration object.
- Import detects encrypted and unencrypted backups automatically.
- Unencrypted backups skip password steps and display a prominent warning because they can contain API keys and PATs.

## Compatibility

- Existing encrypted backups remain importable.
- No existing persisted settings, credentials, or model catalog fields are changed.
- Existing unencrypted test result export behavior is separate and unchanged.

## Verification Matrix

1. `:app:compileDebugKotlin`
2. `:app:testDebugUnitTest`
3. `assembleDebug`
4. Manual layout checks:
   - provider header alignment
   - provider card two-column layout
   - balance provider two-column layout
   - encrypted and unencrypted backup export/import dialogs
   - cross-supplier model search interactions

## Deliverable

- Updated Kotlin sources.
- This specification.
- A debug APK at `app/build/outputs/apk/debug/app-debug.apk`.
