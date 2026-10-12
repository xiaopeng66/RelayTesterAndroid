plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

/**
 * A signing secret, looked up outside the source tree: the Gradle property (a
 * `gradle.properties` in the project or in ~/.gradle, neither of which is committed), the
 * JVM system property (`-Drelaytester.storePassword=…`), or the environment
 * (`RELAYTESTER_STOREPASSWORD`).
 *
 * The environment name is the property name uppercased with every `.` replaced by `_`, so
 * `relaytester.storePassword` becomes `RELAYTESTER_STOREPASSWORD` — there is no underscore
 * between STORE and PASSWORD. Set the variable under exactly that name: a near-miss such as
 * `RELAYTESTER_STORE_PASSWORD` is simply absent, and the signing config then gets a null
 * password.
 *
 * Null when this machine has no signing secrets, so a debug build still works; building the
 * published variant then fails on the missing property name instead of on a keystore error.
 */
fun Project.secret(name: String): String? =
    providers.gradleProperty(name).orNull
        ?: System.getProperty(name)
        ?: providers.environmentVariable(name.uppercase().replace('.', '_')).orNull

android {
    namespace = "com.relaytester.app"
    compileSdk = 36

    signingConfigs {
        create("release") {
            // The published key. The two properties are read from a `gradle.properties`
            // outside this repository (normally ~/.gradle/gradle.properties) or from the
            // command line as -Drelaytester.storePassword=…, so no secret is ever written
            // into the tree. Losing either the file or the password means the published app
            // can never be updated in place again — there is no recovery, so keep a copy of
            // both off this machine.
            storeFile = rootProject.file("keystore-relay-tester-release.p12")
            storeType = "PKCS12"
            keyAlias = "relaytester"
            // v2 is what installs; v3 is the one that carries a key-rotation lineage. Signing
            // with v3 now means that if this key is ever lost, a new key can be introduced as
            // an update (with a proof-of-rotation lineage) instead of making every user
            // uninstall — Android 9+ honours it. Costs nothing today.
            enableV3Signing = true
            storePassword = project.secret("relaytester.storePassword")
            // PKCS12 uses one password for the store and the key; the separate property stays
            // supported so a future JKS key needs no code change.
            keyPassword = project.secret("relaytester.keyPassword")
                ?: project.secret("relaytester.storePassword")
        }
    }

    defaultConfig {
        applicationId = "com.relaytester.app"
        minSdk = 26
        targetSdk = 36
        // Android versionCode must remain monotonic for an in-place upgrade.
        // Encode the public line as major * 10_000 + minor * 100 + patch.
        // 1.4.0 was rebuilt with the model picker on board, so it carries the newest code
        // at a code above the retired 1.4.1/1.4.2 builds (10303 / 10304): installing it
        // over either of those is an upgrade, and the release line stays a single 1.4.0.
        // 1.5.0 keeps one public release; every rebuild code must still upgrade 10500.
        // 10502 adds the detection history, the round progress display, the copy button
        // and the parallel-switch fixes on top of 10501.
        // 10503 adds the swapped result-row actions, the unified expand panel (evaluation
        // card then candidate list), the usable-answer counter beside the model name and
        // the per-model single-question retry.
        // 10504 turns the question area into one three-column block per model and queues a
        // single-question retry behind the question already on the wire when the panel is
        // set to 逐题发送.
        // 10505 swaps the result row's two action cells (the copy takes the cell that was
        // the fingerprint's, aligned to the 可用 pill's text centre), centres the challenge
        // cell's retry, gives the 有效 n/3 counter a fixed slot, and reads both the current
        // detection-package format and the previous one.
        // 10600 adds the update surface: a header entry with a dot when something is
        // published, a page showing both feeds (the app itself and the detection package),
        // byte progress for both downloads, a timestamp for the last check of each, and one
        // switch per feed. The detection package's card finally says what the silent entry
        // check is doing, and an unreadable package format is reported as an app update with
        // the version code to reach instead of a format number.
        // 10700 is the review round: the update chain pins every download and every redirect
        // hop to the release hosts over HTTPS, verifies size and SHA-256 on the part file
        // before the rename and checks the APK against this app's signing certificate;
        // stored credentials report when they can no longer be decrypted, corrupted storage
        // is surfaced instead of silently dropped, and the streaming and balance paths get
        // their missing ceilings and deadlines.
        // 10701 prints the release notes inside the update dialog and sizes both raw dialogs
        // to the real window on both axes (landscape was clipping the card edge). The launch
        // check now runs on every launch while the switch is on — the six-hour window used to
        // swallow it, so nothing was ever checked and the「上次检查」row kept the previous
        // session's time — and a silent check that finds an update opens the page by itself.
        // 10702 lets the suppliers be reordered from a drag panel in the test tab: the order is
        // the profiles list's own, so the test cards, the balance grid and the fingerprint
        // picker all follow one drag. The template editor becomes a dialog over the balance
        // page instead of replacing it, so closing it lands back on the same scroll position
        // instead of the top. A reasoning model's stream no longer fails the detection: the
        // wire ceiling stops judging the answer (reasoning frames used to spend it), and the
        // answer text gets its own bound instead.
        // 10703 makes the reorder panel actually draggable: the gesture hung on the 44dp grip
        // icon, so the first wobble slid off it and the drag died. The whole row is the handle
        // now, the arrows are gone, the rows animate when they swap, and the panel's card no
        // longer carries the 44dp top padding that pushed its footer off the screen (the
        // template editor dialog had the same padding and the same clipped bottom). The
        // balance editor's two selectors fill with the primary colour when chosen, its script
        // field starts empty with a button that fills the example on demand, and a failed
        // configuration write is retried once so the next save cannot store the order its
        // caller just rolled back. Both supplier editors (config, balance credentials) close
        // themselves once a save succeeds — the user used to have to tap 关闭 afterwards — and
        // the save confirmation is a shared StatusToast drawn on the page, not a snackbar
        // trapped under the still-open dialog's scrim.
        // 10704 is the audit-fix round. A Responses stream that ends in `response.failed` now
        // redacts the reason like every other error path — a relay that quotes the rejected
        // key back used to print it on screen. 禁止测试：只拉模型 is honoured by the fingerprint
        // round and the animation request, not only by the 模型测试 page, so a supplier the
        // user marked as pull-only no longer has a paid request spent on its key. The
        // animation document gate accepts a complete page however long it is (the closing tag
        // decides, not the token-ceiling estimate, which used to throw away finished SVG
        // pages), and SSE payloads split over several `data:` lines are reassembled instead of
        // silently losing the answer text. The header's 动画测试/高级测试 labels stay readable
        // at large font scales, the CSP is injected into the real head rather than into a
        // comment that names one, and the preview's WebView is destroyed when it leaves the
        // screen instead of leaking a renderer per replay.
        // 10705 draws the pull-only state instead of spelling it. The 指纹检测 supplier chips
        // and both 动画测试 supplier surfaces used to append "· 仅拉取" to the name; they now
        // carry the same red bar the 模型测试 supplier cards put at their top-left, before the
        // name, so one setting looks the same everywhere. The wording also ate the width of a
        // chip row that already scrolls sideways and ellipsizes long names. The marker is one
        // shared composable (ui/components/PullOnlyMarker.kt) rather than four copies, and it
        // carries its own "仅拉取模型" description because on the pickers it is now the only
        // indication a screen reader gets the meaning from.
        //
        // 1.7.6 — the animation test's live display, in three parts, all of them measured
        // against a reasoning model rather than reasoned about:
        //  * the reasoning block only ever showed its last fragment. `readEventStream` reports
        //    the answer accumulated but the reasoning as fragments, and the view model treated
        //    both the same, so each new fragment replaced the last. The two callbacks now
        //    document their different granularities, the view model accumulates reasoning and
        //    counts it, and a regression test pins the contract.
        //  * the panel was rebuilt on the conventions Codex and ZCode use: a labelled,
        //    collapsible 思考 block that folds itself away once the answer starts, a monospace
        //    输出 block that follows the tail, a status line that says which phase it is in
        //    with a running character count and elapsed time, and a result header that reports
        //    the finished generation's duration.
        //  * the timeout is 300 s, not 180 s (305 s in the client). A thinking model on this
        //    supplier spent 38001 reasoning tokens and produced no content at all in 300 s
        //    when thinking was left on, so the request now asks for it off — with the three
        //    spellings this supplier accepts and none of the two it rejects with HTTP 400 —
        //    and the ceiling was raised so a slow model is not killed mid-thought.
        //
        // 1.7.7 — reverses the thinking-off decision above, on the user's instruction that the
        // animation test must not restrict the model ("不要关闭思考，你不要做任何限制"):
        //  * the three thinking-off fields are gone, along with the parameter that carried them.
        //    Nothing in a request now touches the model's reasoning.
        //  * the bounds are raised to match: max_tokens 16 384 → 65 536, timeout 300 → 600 s,
        //    stream ceiling 8 MB → 32 MB, buffered ceiling 1 MB → 4 MB, answer ceiling
        //    64 K → 256 K characters. Each is sized from a measurement, recorded at the
        //    constant. Measured with thinking free: deepseek-v4.1-flash spent 33 079
        //    characters of reasoning in 300 s with zero answer characters, MiniMax-M2.7
        //    44 145, reasoning framing ~5.3 KB/s of wire, document ~16 wire bytes per
        //    character — so 600 s of thinking plus a full document is ~7.4 MB, which is why
        //    8 MB was too tight and 32 MB is not.
        //  * raising the answer ceiling to 256 K would have made the per-delta progress
        //    callback quadratic in real terms (~570 M characters copied for one document),
        //    so the cadence is now size-proportional (streamProgressDue): every delta below
        //    16 K, then a report per 1/64 of the length, which is ~16.8 M characters total
        //    and a count never more than 1.6% behind.
        //  * the preview pane is 16:9 and the WebView lays the page out at a desktop width
        //    and zooms it to fit (useWideViewPort + loadWithOverviewMode), so a document
        //    composed for a desktop canvas is shown whole instead of cropped.
        //
        // 1.7.8 — the live display, from watching a real generation:
        //  * the reasoning box stayed on the *old* lines and its bottom line was drawn
        //    half-visible. Its scroll state was created inside the block, so every delta
        //    re-mounted it at offset 0; the fix holds the state across recompositions and
        //    follows the end. Following is no longer unconditional: the box yields to a manual
        //    scroll-up and resumes when scrolled back to the end (followEndObservation carries
        //    the rule, keyed on maxValue so text growing is never read as a gesture). The
        //    follow scroll waits one frame, because `maxValue` before layout still describes
        //    the previous text — scrolling to it leaves the newest line one update below the
        //    edge, which is the half-glyph that was reported.
        //  * a connection that dropped mid-thought ended the generation with no retry. There
        //    are now up to 2 automatic retries with a linear 2 s/4 s wait, announced on screen
        //    ("…正在重试（第 2 次，共 3 次）") so the counters dropping to zero read as a
        //    restart rather than as the counting breaking, and the status line names the
        //    attempt.
        //  * the retry trigger is deliberately narrow: transient *and* nothing usable arrived.
        //    A failure after the document started is not re-requested (it was already paid for
        //    and discarding it would re-bill), and neither is an auth/quota/model failure
        //    (repeating it only gets refused again). "Transient" is the tester's own policy,
        //    lifted to ErrorKind.isTransient() so the two screens cannot drift apart.
        //  * 真实供应商实测（freeapi.site / deepseek-v4.1-flash）发现两处只有真流量才暴露的
        //    缺陷，都在 1.7.9 修掉：
        //      - 整段调用超时：上游在响应头之前会静默 30–58 秒，健康的一整篇要约 850 秒。
        //        原本把 callTimeout 也设成 605 秒，等于给"思考时间"设了上限，模型想到一半
        //        就被掐断，界面上显示成"请求超时"。现在流式与缓冲两条路径都不限制整段调用，
        //        连接/读/写仍各自有界——读窗口才是真正的看门狗（只要还有字节到达就不会触发）。
        //      - 答案解析：真实回答是 preamble + ```html 围栏 + 完整文档 + 尾部说明，
        //        stripOuterFence 只在围栏覆盖整段答案时才剥，于是什么都没剥掉，文档被
        //        "不是完整的 HTML 文档"拒收。现在会从散文里挑出**自身完整**（同时有开头
        //        标记与 </html> 结尾）的文档；只有半截的引用依然拒绝，以免把片段当整页展示。
        //  * 预览窗在手机宽度下只有 231 dp（16:9），而沙箱按桌面宽度排版再整体缩放，画面小到
        //    看不清。旧代码的 220 dp 地板链在 aspectRatio 之后，会被比例自身的高度夹掉，
        //    等于没有；1.7.10 把地板折进比例本身（animationPreviewAspectRatio），窄屏按地板
        //    反推比例，宽屏仍原样用 16:9。
        //  * 1.7.11 修用户实测报的五处动画测试问题：
        //      - 上滑后视图被拉回底部：pin 决定的读取端与写入端跑在不同协程，读取端捕获了
        //        启动时的旧值，于是每来一段新内容就重新贴底。改为滚动时现读当前决定。
        //      - 白屏/只显示一点点：文档自己的盒子没跟视口对上（body 无高度，或写死了像素
        //        尺寸再被整体缩放）。载入时注入一份 fitting 样式，给根元素一个视口高度的
        //        盒子、并把 body 内的 svg 撑满。
        //      - 思考框与输出框末行只显示半行：固定 dp 上限不随字号缩放，切在字的中间。
        //        改为按「行高 × 整行数」计算上限，并把 clipToBounds 放在滚动容器外层。
        //      - 思考过程默认收起（几万字的链子自动展开会把正文挤出屏幕）。
        //      - 新增「浏览器打开」：把文档写进 cache、经独立的 FileProvider 以 content URI
        //        交给系统浏览器；清单里同时声明了 <queries>，否则 Android 11+ 上
        //        resolveActivity 看不到任何浏览器。
        //  * 1.7.12 加「分享动图」：把预览里正在播的动画录成 GIF 交给系统分享表。WebView 没有
        //    取帧接口，只能对上了屏的那个 view 逐帧 draw，所以录制必须发生在预览还活着的时候
        //    （preview 交出 view、销毁前收回），编码放后台线程。GIF 编码器是自己写的（项目没有
        //    任何图像依赖），交付前用一份独立写的解码器往返验证 —— 就是这一步抓出三个真错误：
        //      - 帧数据后多写了一个子块结束符，帧与帧之间留了一个空块；
        //      - 量化把通道右移后 OR 回去（0xC0 变 0xF0），每个中间调都亮一截，纯色图分享出去
        //        会变成另一个颜色。改为把保留的高位复制进空出的低位，误差回落到 3/255。
        //  * 1.7.13 修白屏的真正根因（这一版把 1.7.12 的「修 2」推翻重做）。1.7.12 注入的
        //    fit 样式用 height:100%，在浏览器里对、在这个 WebView 里错：`data:` 文档 +
        //    loadDataWithBaseURL + 桌面布局视口，引擎里没有任何确定高度的包含块，实测
        //    html/body/svg 的 height 全算成 0px（视口本身是好的 299×220），页面布局正确却
        //    画进一个零高盒子 —— 那就是用户看到的白屏，100vh 也一样塌。改用
        //    `position: fixed; inset: 0` 直接对视口定尺寸后，svg 高度从 0 恢复到 220，
        //    整幅鹈鹕动画当场显示出来。回归测试钉住「必须锚在视口」这个选择。
        //  * 1.7.14 修「上滑停留」只做了一半的问题（设备实测抓到的）。滚动位置确实钉住了，
        //    但显示的思考文本是 takeLast(4000) 的**滑动**窗口：读者上滑回到中间后，新分片
        //    仍会把前面的字挤掉——框停在同一个像素位置，底下的文字却整体前移。实测两次
        //    截图相隔 5 秒，框的行位置完全相同（表头都在 y=886），内容却从「第 168 步」
        //    变成「第 215 步」。修法：读者离开末尾时停止修剪（session.reasoningHeld），
        //    追加只发生在末尾，正在读的那一行停在原处；回到末尾立即恢复截断，避免长链无界。
        //  * 1.7.15 默认提示词换成用户原话的一句话版本。原来七条要求（不要围栏、不要解释、
        //    从 DOCTYPE 到 /html）每一条都是在替解析器挡输入；selectAnimationDocument 已能
        //    从任意包裹形式（裸文档 / 围栏 / 开场白+文档+解说）里取出最外层完整文档并有
        //    真实响应回归钉住，提示词只说要什么，回什么形状交给模型。
        //  * 1.7.16 五件事：① 四个动作按钮等宽一行（大字号时藏图标保一行）；② 解析不再要求
        //    SVG——任何「生成 HTML」的提示词，答案含一份完整文档即可预览，预览样式按文档
        //    是否含 SVG 二选一（普通页面保留自身排版与滚动）；③ 上游断连韧性——流被掐断时
        //    已收到的字节不再丢（按流结束交还），「没成篇」的解析失败进重试预算（重试
        //    2→3 次），失败文案带上已收字数；④ 思考通道兼容——显式 null 的
        //    reasoning_content 不再被 optString 变成字面 "null" 刷屏，<think>…</think> 包在
        //    content 里的模型（无思考字段的部署）按围栏路由进思考框；⑤ 源码/输出/思考三处
        //    按代码高亮（标签、属性、字符串、注释、关键字各有其色，零依赖）。
        //  * 1.7.17 四件事：① 主生成按钮与四个次要按钮统一高度（min 44dp，原 48dp），动作
        //    行两组之间隔 12dp；② 源码窗格不再用 aspectRatio 匹配预览高度（比例值切在行
        //    中间→末行半行），改整行数上限 + 内边距移出滚动视口，上下页边距相等；③ 重试
        //    提示在重试等待结束、新尝试开始时清掉（不再钉在屏幕上），次数在生成彻底结束时
        //    归位；④ 模型多选（同供应商勾选，换供应商即清）+ 批量测试（按目录顺序开齐
        //    所有勾中的模型，聚焦第一条，其余在列表里跑），会话作业按会话各管各的——跨
        //    供应商并行测试互不影响，不再「新测试取代旧测试」。
        //  * 1.7.18 五件事：① 动画面板底部四个次要动作只留图标（文案进无障碍名），去掉外边框、
        //    统一成 44dp 方按钮、间隔 8dp 并封顶一排 200dp——不再按宽度藏图标/缩字号；同时
        //    主题层关掉涟漪与状态层（LocalRippleConfiguration = null、LocalIndication =
        //    NoIndication），全应用按钮按下都不再留下那层「选中虚影」；② 供应商排序拖动时列表
        //    跟着手指沿上下边缘自动滚动（边缘 56dp 感应区，速度逐帧爬升到 12px/帧，到顶/到底
        //    自然停），手指越出可见窗口不再中断拖动——被拖的行因此始终整行可见；③ 自动滚过的
        //    位移计入换位判定，边缘换位与中间一样是平移动画，不再闪切；④ 排序行高 56→48dp、
        //    行距 6→2dp，并删掉标题下那行说明文字；⑤ 指纹检测「最接近的候选」卡片的置信度
        //    从标题旁的小字挪成右侧圆环（64dp、6dp 描边，12 点起顺时针，轨道 outlineVariant、
        //    进度主色，环心三位数百分数，无障碍名「置信 n%」）。
        //  * 1.7.19 一件事：指纹检测「上游返回 HTTP 200 但没有可用的文本内容」不再是一道死题。
        //    实测抓到根因（scdn / deepseek-v4-flash-0731）：同一道题四次里有一次 200 的正常流
        //    里正文 0 字符——8192 的预算全部花在 reasoning 通道（reasoning_tokens = 8192、
        //    finish_reason: length），模型把任务做进了思考；其余三次同一请求都拿到正文，说明
        //    空白是上游自己的硬币，不是这次请求的属性。所以：① 空白识别成一处
        //    （NO_ANSWER_TEXT_MESSAGE + isMissingAnswerText，两条完成路径共用同一句话）；
        //    ② 指纹检测对空白自动重发一次（CHALLENGE_MAX_ATTEMPTS = 2，按实测约 1/4 → 1/16），
        //    结题时仍空白才报失败；③ 报错分形状：思考通道有内容时说「模型只输出了思考内容、
        //    没有给出正文」，两条通道都空才保留上游原话，并且都写明自动重试过的次数；④ 卡片
        //    状态词在重试期间自称「重试中…」/「正在重试…」（不再和「接收中」同形）；⑤ 不发思考
        //    开关、不加预算——关推理是产品明确否掉的（有一条测试钉着请求体不得出现那几个字段），
        //    加预算只是把墙往后挪，重发才是对「硬币」有效的动作。
        // 1.7.20：本轮四项优化 + 上游空正文定位。
        //  ① 动画操作条：四个次要动作改成固定 44dp 等边方按钮（此前 weight 分配宽度，窄屏两行
        //     布局里会被拉成 70dp 大方格，与 36dp 主按钮一比就是大小不一）；② 主操作（开始生成/
        //     重新生成/取消生成）统一压到 36dp 可见高度；取消保持描边；③ 模型选择弹窗只留一个
        //     「开始生成」，一次启动全部勾选模型（N=1 亦然），并新增本地关键词搜索（纯过滤，
    //     不产生请求、不丢勾选）；④ 指纹挑战预算 8192 → 32768；动画空正文纳入既有三次重试
        //     预算，并按实测把「思考已到、正文 0」报成上游思考阶段中断（2026-10-10 实测某中转
        //     网关约 121 秒硬截断：8K 预算下短提示词成功、长思考任务被切断且无 [DONE]/finish_reason）。
        //  ⑤ 跨功能并行：移除指纹↔动画互斥；余额改为按供应商占位/释放，批量与单卡刷新可同时在途
        //     （实测三个站点同时在网）；⑥ 高级测试的供应商下拉、默认选择与全量模型检索都排除
        //     禁止测试的供应商。持久化仍走原有凭据/配置写锁。
        // 1.8.0：在 1.7.20 之上完成的一轮系统性审查与优化（对比 GitHub 上一个发行版 v1.7.3，
        // 本版本一次性覆盖 v1.7.3 → 1.8.0 的全部变化，明细见 release-notes/RELEASE_NOTES_1.8.0.md）。
        // 本次审查共修 15 处：提示归宿错误 3（拉取/检索的拒绝被弹窗盖住、检测包忙碌原因不分类型）、
        // UI 与逻辑判定不一致 4（目录卡片误用过滤后的供应商列表、禁止测试的供应商仍可加为来源、
        // 高级↔普通测试双向静默阻断、余额批量按钮仍按页面级忙碌禁用而锁死并行）、使用边界无上限 3
        // （动画提示词、测试 Prompt 的写入端与读取端）、备份自断兼容 1（导入侧 4096 < 保存侧 16384，
        // 自己导出的备份导不回来）、资源无界 1（动画记录从不修剪，每条最多 2MB HTML + 截图）、
        // 导出未自述范围 1、死代码 1。另新增动画成功截图缩略图（替代「已完成」）。
        versionCode = 10_800
        versionName = "1.8.0"
        ndk {
            // arm64 only: the published update ships one APK, and every device the app is
            // meant for is arm64. The 32-bit and x86 ABIs would each add a copy of the two
            // QuickJS libraries for devices this build never targets.
            abiFilters += "arm64-v8a"
        }
    }

    buildFeatures {
        compose = true
        // No source references BuildConfig. Avoid generating an otherwise
        // unused Java class and its startup/build-time worker overhead.
        buildConfig = false
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            // Release and the local optimized build use the same production
            // bytecode/resource pipeline. This changes no Compose output; it
            // reduces install-time verification and cold-start class loading.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("release")
        }
        create("optimized") {
            initWith(getByName("release"))
            // Same signing as `release`: this variant is what gets published, and an app can
            // only be updated in place by a build with the same package name and the same
            // signing certificate. `debug` keeps its own `.debug` package and debug signing
            // so a development build can sit next to the published one.
            signingConfig = signingConfigs.getByName("release")
            // No versionNameSuffix, unlike `debug`: the update page prints the installed
            // version name next to the feed's, and a "-optimized" marker there reads as a
            // different version.
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")

    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    // 供应商排序面板的换位动画来自 foundation 的 animateItem（BOM 2024.12.01 起叫这个名字）。
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.github.taoweiji.quickjs:quickjs-android:1.4.6")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("org.json:json:20240303")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
