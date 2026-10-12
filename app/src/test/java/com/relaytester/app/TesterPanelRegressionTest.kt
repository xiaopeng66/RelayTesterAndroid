package com.relaytester.app

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import com.relaytester.app.core.model.ApiResult
import com.relaytester.app.core.model.BalanceHttpMethod
import com.relaytester.app.core.model.BalanceQueryResult
import com.relaytester.app.core.model.BalanceQueryTemplate
import com.relaytester.app.core.model.BalanceSnapshot
import com.relaytester.app.core.model.ModelCatalogEntry
import com.relaytester.app.core.model.ModelSource
import com.relaytester.app.core.model.ModelTestResult
import com.relaytester.app.core.model.SupplierProfile
import com.relaytester.app.core.model.TestSettings
import com.relaytester.app.core.model.TestStatus
import com.relaytester.app.core.network.BalanceApi
import com.relaytester.app.core.network.RelayApi
import com.relaytester.app.core.security.SecretStore
import com.relaytester.app.core.storage.SupplierStore
import com.relaytester.app.core.storage.SupplierStoreState
import com.relaytester.app.feature.tester.CatalogSaveOutcome
import com.relaytester.app.feature.tester.TesterUiState
import com.relaytester.app.feature.tester.TesterViewModel
import com.relaytester.app.feature.tester.catalogSaveResetsDraft
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Covers the tester panel behaviour the concurrency test does not: what a stopped run
 * leaves behind, and how a supplier's live model directory is judged.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TesterPanelRegressionTest {
    private val mainDispatcher = UnconfinedTestDispatcher()
    private val lifecycle = ViewModelStore()
    private val subjects = mutableListOf<TesterViewModel>()
    private val releaseFixtures = mutableListOf<() -> Unit>()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        val roots = subjects.map { it.viewModelScope.coroutineContext[Job]!! }
        try {
            lifecycle.clear()
            releaseFixtures.forEach { it() }
            mainDispatcher.scheduler.runCurrent()
            awaitReal { roots.forEach { it.join() } }
        } finally {
            Dispatchers.resetMain()
        }
    }

    // ---- Starting a run is atomic ----------------------------------------

    @Test
    fun `a double tap on start does not launch two rounds`() {
        // The first draft write stays parked until both taps have reached the start guard.
        val api = RecordingTestApi(listOf("model-a"))
        val store = GatedStore()
        val subject = viewModel(relayApi = api, supplierStore = store)
        seedRunnableState(subject)

        subject.startTest()
        runBlocking { withTimeout(SETTLE_TIMEOUT_MS) { store.entered.await() } }
        subject.startTest()

        store.release()
        settle(subject)

        assertEquals("双击只允许一轮", 1, api.calls.get())
    }
    // ---- Cancelling a run settles its rows -------------------------------

    @Test
    fun `cancelling a run leaves no row still claiming to be running`() {
        val subject = viewModel(ModelsApi(listOf("model-a")))
        setResultsForTest(
            subject,
            listOf(
                ModelTestResult("model-a", TestStatus.PENDING),
                ModelTestResult("model-b", TestStatus.SUCCESS, latencyMs = 5),
            ),
        )

        subject.cancelRun()

        val results = subject.uiState.value.results
        assertTrue(
            "取消后不得留下仍显示“进行中”的行",
            results.none { it.status == TestStatus.PENDING },
        )
        val cancelled = results.first { it.model == "model-a" }
        assertEquals(TestStatus.FAILED, cancelled.status)
        assertEquals("已取消", cancelled.error?.message)
        // A row that already had a verdict keeps it: cancelling is not a re-test.
        assertEquals(TestStatus.SUCCESS, results.first { it.model == "model-b" }.status)
    }

    // ---- Judging a supplier's model directory ----------------------------

    @Test
    fun `an empty directory is not evidence that models were delisted`() {
        // An endpoint that answers 200 with `[]` looks the same as one whose list
        // failed to populate. Judging it would mark every model of that site as gone.
        val api = RecordingModelsApi(emptyList())
        val subject = viewModel(api)
        seedEntry(subject, ModelSource("supplier-1", "model-a"))

        subject.refreshModelCatalogEntry(ENTRY_ID)
        settle(subject)

        assertEquals(listOf("supplier-1"), api.queriedSupplierIds.toList())
        val state = subject.uiState.value
        assertTrue("目录检查必须完成", state.catalogRefreshingEntryIds.isEmpty())
        assertTrue("空目录不得判为已下架", state.catalogMissingSources.isEmpty())
        assertFalse(
            "不得声称模型已不存在",
            state.message?.contains("已不存在") == true,
        )
    }

    @Test
    fun `a model absent from a non-empty directory is still reported as delisted`() {
        // The empty-directory rule must not disable the check altogether.
        val subject = viewModel(ModelsApi(listOf("model-b")))
        seedEntry(subject, ModelSource("supplier-1", "model-a"))

        subject.refreshModelCatalogEntry(ENTRY_ID)
        settle(subject)

        val state = subject.uiState.value
        assertTrue(
            "非空目录里找不到的模型仍须报为已下架",
            state.catalogMissingSources.isNotEmpty(),
        )
        assertTrue(state.message?.contains("已不存在") == true)
    }

    // ---- The balance fan-out is bounded ----------------------------------

    @Test
    fun `querying every balance runs at most eight sites at once`() {
        val api = GatedBalanceApi()
        val subject = viewModel(balanceApi = api)
        seedBalanceState(subject, suppliers = 12)

        subject.queryAllBalances()
        awaitReal { api.started(8).await() }
        assertEquals("第一批八条请求必须保持在途", 8, api.inFlight.get())
        val firstWave = api.queriedSupplierIds.toSet()
        firstWave.forEach(api::release)
        awaitReal { api.started(12).await() }
        api.releaseAll()
        settle(subject)

        assertEquals("每个站点恰好查询一次", 12, api.calls.get())
        assertEquals((1..12).map { "supplier-$it" }.toSet(), api.queriedSupplierIds.toSet())
        assertEquals("批量余额查询最多八条请求同时在途", 8, api.maxInFlight)
        assertFalse(subject.balanceUiState.value.isQuerying)
    }

    @Test
    fun `a double tap on query balance does not launch two queries`() {
        // The second tap lands before persistence can publish the first query's busy state.
        val api = GatedBalanceApi()
        val store = GatedStore()
        val subject = viewModel(balanceApi = api, supplierStore = store)
        seedBalanceState(subject, suppliers = 1)

        subject.queryBalance()
        awaitReal { store.entered.await() }
        assertFalse(subject.balanceUiState.value.isQuerying)
        subject.queryBalance()
        store.release()
        awaitReal { api.started(1).await() }
        api.releaseAll()
        settle(subject)

        assertEquals("双击只允许一次查询", 1, api.calls.get())
        assertFalse(subject.balanceUiState.value.isQuerying)
    }

    @Test
    fun `a single-card refresh runs beside a batch query without clearing its state`() {
        assertBalanceQueriesKeepOtherOwners(cardFinishesFirst = true)
    }

    @Test
    fun `a finished batch keeps a single-card refresh marked as querying`() {
        assertBalanceQueriesKeepOtherOwners(cardFinishesFirst = false)
    }

    private fun assertBalanceQueriesKeepOtherOwners(cardFinishesFirst: Boolean) {
        val api = GatedBalanceApi()
        val subject = viewModel(balanceApi = api)
        seedBalanceState(subject, suppliers = 3)

        subject.refreshSupplierBalance("supplier-1")
        awaitReal { api.started(1).await() }
        val cardJobs = subject.viewModelScope.coroutineContext[Job]!!.children.toList()
        subject.queryAllBalances()
        awaitReal { api.started(3).await() }
        val batchJobs = subject.viewModelScope.coroutineContext[Job]!!.children.toList() - cardJobs.toSet()
        assertEquals("三个站点的请求必须同时在途", 3, api.inFlight.get())
        assertEquals(
            setOf("supplier-1", "supplier-2", "supplier-3"),
            subject.balanceUiState.value.queryingSupplierIds,
        )

        val completed = if (cardFinishesFirst) setOf("supplier-1") else setOf("supplier-2", "supplier-3")
        completed.forEach(api::release)
        awaitReal { (if (cardFinishesFirst) cardJobs else batchJobs).forEach { it.join() } }
        val remaining = setOf("supplier-1", "supplier-2", "supplier-3") - completed
        assertEquals("先完成的操作只清除自己的占位", remaining, subject.balanceUiState.value.queryingSupplierIds)
        assertTrue("另一个操作仍在查询", subject.balanceUiState.value.isQuerying)
        assertEquals(remaining.size, api.inFlight.get())

        api.releaseAll()
        settle(subject)
        val settled = subject.balanceUiState.value
        assertFalse("全部结束后不得残留查询中状态", settled.isQuerying)
        assertEquals("每个站点恰好查询一次", 3, api.calls.get())
        assertEquals("三个站点都应有结果", 3, settled.balanceSnapshots.size)
    }

    @Test
    fun `the advanced model search never contacts a testing-disabled supplier`() {
        // 新要求：禁止测试的供应商不得出现在高级测试的候选里。执行层本来就会跳过它们，
        // 但检索仍会为它们发一次模型目录请求，而且结果里会出现永远不能选的来源。
        val api = RecordingModelsApi(listOf("model-a"))
        val subject = viewModel(relayApi = api)
        seedRunnableState(subject, secondSupplier = true)
        setSupplierTestingDisabledForTest(subject, "supplier-2", disabled = true)

        subject.searchAllCatalogModels("model")

        settle(subject)

        assertFalse(
            "禁止测试的供应商不得被检索（实际请求过：${api.queriedSupplierIds}）",
            api.queriedSupplierIds.contains("supplier-2"),
        )
        assertTrue(
            "没被禁止的供应商仍应被检索",
            api.queriedSupplierIds.contains("supplier-1"),
        )
        assertTrue(
            "检索结果里不得出现禁止测试的来源：${subject.uiState.value.catalogSearchResults}",
            subject.uiState.value.catalogSearchResults.none { it.supplierId == "supplier-2" },
        )
    }

    @Test
    fun `a normal test still starts while an advanced run is in flight`() {
        // 要求是所有测试可并行：高级测试在它自己的弹窗里跑时，主界面的「开始测试」必须真的
        // 发出请求，而不是可点却静默无效。两者各有各的作业与结果键。
        val api = RecordingTestApi(listOf("model-a"))
        val subject = viewModel(relayApi = api)
        seedRunnableState(subject)
        setUnifiedTestingForTest(subject)

        subject.startTest()
        settle(subject)

        assertEquals("高级测试在跑时普通测试也必须发出请求", 1, api.calls.get())
        assertTrue(subject.uiState.value.results.isNotEmpty())
    }

    @Test
    fun `editing configuration is still locked while an advanced run is in flight`() {
        // 反向不能过度放开：高级测试运行期间改供应商配置仍须被拒，否则它读到的是一份
        // 半改的快照。这里只放开「启动测试」，配置编辑继续锁。
        val subject = viewModel()
        seedRunnableState(subject, secondSupplier = true)
        setUnifiedTestingForTest(subject)

        var reported: Boolean? = null
        subject.selectSupplier("supplier-2") { reported = it }

        assertEquals("高级测试在跑时切换供应商仍须被拒", false, reported)
    }

    @Test
    fun `a batch with nothing left to query answers instead of doing nothing`() {
        // 唯一供应商正在单卡刷新时，批量按钮仍是可点的（balanceQueryRefused 不含 isQuerying），
        // 而批量只会认领空闲站点 → 认领集合为空 → 内部直接 return。结果是一次点击既不发请求
        // 也没有任何提示，读起来就是按钮坏了。要么说清「都在查了／没有可查的」，要么别让点。
        val api = GatedBalanceApi()
        val subject = viewModel(balanceApi = api)
        seedBalanceState(subject, suppliers = 1)

        subject.refreshSupplierBalance("supplier-1")
        runBlocking { withTimeout(5_000) { api.started(1).await() } }

        subject.clearBalanceMessage()
        subject.queryAllBalances()
        awaitReal { subject.balanceUiState.first { it.message != null } }

        assertEquals("全部站点都已被占用时不得再发请求", 1, api.calls.get())
        assertTrue(
            "空批量必须给出说明，而不是静默无响应：${subject.balanceUiState.value.message}",
            subject.balanceUiState.value.message != null,
        )

        api.releaseAll()
        settle(subject)
    }

    @Test
    fun `a refresh in flight for one supplier does not refuse another supplier's refresh`() {
        // 并行要求的第一半在 ViewModel 里（按供应商占位），这一条钉住 UI 侧的同一个判据：
        // 界面若按**页面级** isQuerying 禁用，A 在查时 B 就点不动，ViewModel 的并行能力白开。
        val api = GatedBalanceApi()
        val subject = viewModel(balanceApi = api)
        seedBalanceState(subject, suppliers = 2)

        subject.refreshSupplierBalance("supplier-1")
        runBlocking { withTimeout(5_000) { api.started(1).await() } }

        // 第二个供应商必须能发出自己的查询，而不是被第一个占着页面挡回去。
        subject.refreshSupplierBalance("supplier-2")
        runBlocking { withTimeout(5_000) { api.started(2).await() } }

        assertEquals("两个供应商的刷新必须同时在途", 2, api.inFlight.get())
        assertEquals(
            "两条占位必须同时存在",
            setOf("supplier-1", "supplier-2"),
            subject.balanceUiState.value.queryingSupplierIds,
        )

        api.releaseAll()
        settle(subject)
        assertFalse("全部结束后不得残留查询中状态", subject.balanceUiState.value.isQuerying)
    }

    @Test
    fun `a model search with nothing selectable answers inside the dialog`() {
        // 全部供应商都被设为「禁止测试」时，检索没有对象。拒绝必须落在**弹窗渲染的那条状态**
        // （catalogSearchMessage）上，而不是页面级 message——页面 snackbar 住在活动窗口里、
        // 被弹窗盖住，用户点「检索」看到的是「什么也没发生」。同一条规则此前已用在拉取路径上。
        val api = RecordingModelsApi(listOf("model-a"))
        val subject = viewModel(relayApi = api)
        seedRunnableState(subject, secondSupplier = true)
        setSupplierTestingDisabledForTest(subject, "supplier-1", disabled = true)
        setSupplierTestingDisabledForTest(subject, "supplier-2", disabled = true)

        subject.searchAllCatalogModels("model")
        settle(subject)
        awaitReal { subject.uiState.first { it.catalogSearchMessage != null } }

        val state = subject.uiState.value
        assertTrue(
            "全禁止时的拒绝必须在弹窗状态里可见：${state.catalogSearchMessage}",
            state.catalogSearchMessage?.contains("供应商") == true,
        )
        assertTrue("必须标红", state.isCatalogSearchError)
        assertFalse("搜索不应真的发起", state.isCatalogSearching)
        assertEquals("不得向被禁止的供应商发请求", 0, api.queriedSupplierIds.size)
    }

    @Test
    fun `an empty keyword is also answered inside the dialog`() {
        // 同一类：空关键词的拒绝也写在弹窗状态里，否则同样是「点了没反应」。
        val subject = viewModel(relayApi = RecordingModelsApi(listOf("model-a")))
        seedRunnableState(subject, secondSupplier = false)

        subject.searchAllCatalogModels("   ")
        awaitReal { subject.uiState.first { it.catalogSearchMessage != null } }

        val state = subject.uiState.value
        assertTrue(
            "空关键词的拒绝必须在弹窗状态里可见：${state.catalogSearchMessage}",
            state.catalogSearchMessage?.contains("关键词") == true,
        )
        assertTrue(state.isCatalogSearchError)
    }

    @Test
    fun `the exported payload states how many rows it holds and whether a filter was on`() {
        // 导出写的是**全部**结果，不是屏幕筛选后可见的那几行——这本身是对的，但文件里必须能
        // 看出这件事：用户筛选后导出会拿到比屏幕多得多的行，没有记录就会被当成"导错了"。
        val subject = viewModel()
        seedRunnableState(subject)
        setResultsForTest(
            subject,
            listOf(
                ModelTestResult("model-a", TestStatus.SUCCESS, latencyMs = 5),
                ModelTestResult("model-b", TestStatus.FAILED, latencyMs = 9),
            ),
        )
        subject.updateResultQuery("model-a")

        val payload = subject.buildExportPayload()
        assertNotNull("有结果时必须能导出", payload)
        val root = org.json.JSONObject(payload!!.json)
        assertEquals("导出的行数是全部结果，不是筛选后的可见行", 2, root.getJSONArray("results").length())
        assertEquals(
            "文件要记录导出时的筛选条件，否则「为什么比屏幕多」无从解释",
            "model-a",
            root.optString("export_filter"),
        )
    }

    @Test
    fun `an unfiltered export records an empty filter`() {
        val subject = viewModel()
        seedRunnableState(subject)
        setResultsForTest(subject, listOf(ModelTestResult("model-a", TestStatus.SUCCESS, latencyMs = 5)))

        val payload = subject.buildExportPayload()
        val root = org.json.JSONObject(payload!!.json)
        assertEquals("没有筛选时该字段为空", "", root.optString("export_filter"))
        assertEquals(1, root.getJSONArray("results").length())
    }

    // ---- Re-running part of a list ---------------------------------------

    @Test
    fun `retesting the ticked rows keeps the rows left unticked`() {
        // 重测已选 re-runs the ticked rows; it must not rebuild the list from them alone.
        // It used to, which dropped every unticked verdict from the panel and from the
        // exported JSON — the button says "retest the selected", not "discard the rest".
        val api = RecordingTestApi(listOf("model-a", "model-b"))
        val subject = viewModel(relayApi = api)
        seedRunnableState(subject)
        setResultsForTest(
            subject,
            listOf(
                ModelTestResult("model-a", TestStatus.SUCCESS, latencyMs = 5),
                ModelTestResult("model-b", TestStatus.SUCCESS, latencyMs = 7),
            ),
        )

        subject.retestAll()
        settle(subject)

        val results = subject.uiState.value.results
        assertTrue(
            "未勾选的行必须留在结果里",
            results.any { it.model == "model-b" },
        )
        // ...and it is neither re-run nor counted: only the ticked model is requested.
        assertEquals("只重测勾选的行", 1, api.calls.get())
    }

    // ---- Judging a batch fetch -------------------------------------------

    @Test
    fun `a site answering the batch with an empty directory is reported`() {
        // The single-supplier path already treats `200 []` as an error message. The batch
        // counted it as a clean success and then overwrote the per-site reason with
        // "已拉取 N 个供应商的模型", so the failure card stayed empty and the summary
        // claimed a batch that returned nothing had gone fine.
        val subject = viewModel(ModelsApi(emptyList()))
        seedRunnableState(subject)

        subject.fetchAllSupplierModels()
        settle(subject)

        val state = subject.uiState.value
        assertEquals("空目录必须出现在失败清单里", 1, state.modelFetchFailures.size)
        assertEquals("站点未返回模型列表", state.modelFetchFailures.first().reason)
        assertTrue(
            "摘要必须说明原因",
            state.message?.contains("站点未返回模型列表") == true,
        )
        assertTrue("摘要必须标为错误", state.isMessageError)
    }

    // ---- Switching suppliers reports whether it landed --------------------

    @Test
    fun `an idle switch reports success to the caller that armed an editor`() {
        // 两个「编辑」入口都靠这个回报决定要不要记下弹窗 id。以前 selectSupplier 没有
        // 回报，它们只能先记后切——切换被拒或写盘失败时 id 留在原地，之后这个站点因别
        // 的原因变成活动站点时，编辑弹窗会自己蹦出来。
        val subject = viewModel()
        seedRunnableState(subject, secondSupplier = true)

        var reported: Boolean? = null
        subject.selectSupplier("supplier-2") { reported = it }
        settle(subject)

        assertEquals("成功的切换必须回报 true", true, reported)
        assertEquals("supplier-2", subject.uiState.value.activeSupplierId)
    }

    @Test
    fun `re-selecting the active supplier reports success without a write`() {
        // 编辑入口对「当前站点」点开也必须能开：这条路径不碰磁盘，所以运行中也成立。
        val subject = viewModel()
        seedRunnableState(subject)

        var reported: Boolean? = null
        subject.selectSupplier("supplier-1") { reported = it }

        assertEquals("已是活动站点＝成功", true, reported)
    }

    @Test
    fun `a switch refused while a run is in flight reports failure`() {
        val subject = viewModel()
        seedRunnableState(subject, secondSupplier = true)
        setRunningForTest(subject)

        var reported: Boolean? = null
        subject.selectSupplier("supplier-2") { reported = it }

        assertEquals("被拒绝的切换必须回报 false", false, reported)
        assertEquals("supplier-1", subject.uiState.value.activeSupplierId)
    }

    // ---- The picker's fetch result is visible inside the dialog -----------

    @Test
    fun `a failed catalog fetch leaves its reason where the dialog renders it`() {
        // 拉取失败以前只写页面级 message，而那个 snackbar 住在活动窗口里、被弹窗自己的
        // 窗口盖住：用户看到的是「转完圈后什么也没发生」。
        val subject = viewModel(FailingModelsApi("连接供应商失败"))
        seedEntry(subject, ModelSource("supplier-1", "model-a"))

        subject.fetchCatalogModels("supplier-1")
        settle(subject)

        val state = subject.uiState.value
        assertEquals("连接供应商失败", state.catalogPickerMessage)
        assertTrue("失败必须标红", state.isCatalogPickerMessageError)
        assertTrue("拉取已结束", !state.isCatalogFetching)
    }

    @Test
    fun `a successful catalog fetch reports the count on the same line`() {
        val subject = viewModel(ModelsApi(listOf("model-a", "model-b")))
        seedEntry(subject, ModelSource("supplier-1", "model-a"))

        subject.fetchCatalogModels("supplier-1")
        settle(subject)

        val state = subject.uiState.value
        assertEquals("已拉取 2 个模型", state.catalogPickerMessage)
        assertTrue("成功不得标红", !state.isCatalogPickerMessageError)
    }

    @Test
    fun `an empty catalog answers on the same line and reads as a problem`() {
        val subject = viewModel(ModelsApi(emptyList()))
        seedEntry(subject, ModelSource("supplier-1", "model-a"))

        subject.fetchCatalogModels("supplier-1")
        settle(subject)

        val state = subject.uiState.value
        assertEquals("该供应商未返回模型", state.catalogPickerMessage)
        assertTrue(state.isCatalogPickerMessageError)
    }

    @Test
    fun `a fetch refused for a missing key answers where the dialog renders it`() {
        // 这条拒绝也发生在弹窗开着时：只写页面级 message 的话，用户点「拉取」看到的是
        // 什么都没发生。理由必须落进弹窗渲染的那条状态。
        val subject = viewModel()
        setProfileForTest(subject, apiKeySecretId = null)
        setPickerStateForTest(subject)

        subject.fetchCatalogModels("supplier-1")
        settle(subject)

        val state = subject.uiState.value
        assertTrue(
            "未保存 API Key 的拒绝没有落在弹窗状态上：${state.catalogPickerMessage}",
            state.catalogPickerMessage?.contains("API Key") == true,
        )
        assertTrue(state.isCatalogPickerMessageError)
    }

    // ---- Saving a model-catalog entry reports what happened ---------------

    @Test
    fun `an unchanged save reports success without touching the store`() {
        // Two no-ops used to share one misleading line: an unchanged save is a success
        // (the dialog may close), a vanished target is a failure (it may not).
        val store = RecordingStore()
        val subject = viewModel(supplierStore = store)
        seedEntry(subject, ModelSource("supplier-1", "model-a"))

        subject.updateModelCatalogEntry(ENTRY_ID, "entry", listOf(ModelSource("supplier-1", "model-a")))
        settle(subject)

        val outcome = subject.uiState.value.catalogSaveOutcome
        assertNotNull("未改动保存也要有一次可确认的回执", outcome)
        assertTrue("内容未变＝成功", outcome!!.success)
        assertTrue("未变化必须单独说明", outcome.unchanged)
        assertEquals("配置未变化", outcome.message)
        assertEquals("未变化不得写盘", 0, store.saveCalls.get())
    }

    @Test
    fun `a save whose target has been deleted reports failure`() {
        // The entry was deleted from another window between opening the editor and
        // saving. The save must not dress itself up as a success and close the dialog.
        val subject = viewModel()
        seedEntry(subject, ModelSource("supplier-1", "model-a"))

        subject.updateModelCatalogEntry("entry-gone", "entry", listOf(ModelSource("supplier-1", "model-a")))
        settle(subject)

        val outcome = subject.uiState.value.catalogSaveOutcome
        assertNotNull(outcome)
        assertFalse("目标不存在必须报失败", outcome!!.success)
        assertFalse(outcome.unchanged)
        assertEquals("未找到要更新的模型来源", outcome.message)
    }

    @Test
    fun `a failed configuration write rolls the entry back and reports failure`() {
        val store = RecordingStore(fail = true)
        val subject = viewModel(supplierStore = store)
        seedEntry(subject, ModelSource("supplier-1", "model-a"))

        subject.updateModelCatalogEntry(ENTRY_ID, "renamed", listOf(ModelSource("supplier-1", "model-a")))
        settle(subject)

        val state = subject.uiState.value
        val outcome = state.catalogSaveOutcome
        assertNotNull("写盘失败也要有回执", outcome)
        assertFalse("写盘失败必须报失败", outcome!!.success)
        assertTrue(outcome.message.contains("未能写入"))
        assertEquals("失败后目录必须回到保存前", 1, state.modelCatalog.size)
        assertEquals("entry", state.modelCatalog.first().name)
        assertFalse("保存结束后不得残留保存中", state.isSavingCatalogEntry)
    }

    @Test
    fun `a second save click while the first is writing is refused`() {
        // The write parks inside the store, so the second click lands while
        // isSavingCatalogEntry is up: without the guard both writes race the same draft.
        val store = RecordingStore(gate = CompletableDeferred())
        val subject = viewModel(supplierStore = store)
        seedEntry(subject, ModelSource("supplier-1", "model-a"))

        subject.updateModelCatalogEntry(ENTRY_ID, "renamed", listOf(ModelSource("supplier-1", "model-a")))
        awaitReal { store.entered.await() }
        assertEquals("第一次保存已进入写盘", 1, store.saveCalls.get())
        assertTrue(subject.uiState.value.isSavingCatalogEntry)

        // Different content bypasses the unchanged-draft shortcut; only the in-flight guard may refuse it.
        subject.updateModelCatalogEntry(ENTRY_ID, "renamed-again", listOf(ModelSource("supplier-1", "model-a")))

        store.gate!!.complete(Unit)
        settle(subject)
        assertEquals("保存中的第二次点击不得再写盘", 1, store.saveCalls.get())

        val outcome = subject.uiState.value.catalogSaveOutcome
        assertTrue("放行后这一次保存必须成功落地", outcome!!.success)
        assertFalse(subject.uiState.value.isSavingCatalogEntry)
        assertEquals("落盘的是第一次保存的内容", "renamed", subject.uiState.value.modelCatalog.first().name)
        assertEquals("回执属于第一次保存", "renamed", outcome.savedName)
        assertFalse(subject.uiState.value.isSavingCatalogEntry)
        assertEquals("renamed", subject.uiState.value.modelCatalog.first().name)
    }

    @Test
    fun `the dialog resets its draft only for a success that matches the draft`() {
        // The confirmation carries the draft it saved. A success that answers a session
        // the user has already moved past (renamed, edited sources, switched rows) must
        // not wipe the newer draft; a failure never resets anything.
        val saved = CatalogSaveOutcome(
            id = 1,
            entryId = ENTRY_ID,
            success = true,
            unchanged = false,
            savedName = "entry",
            savedSources = listOf(ModelSource("supplier-1", "model-a")),
            message = "已更新模型来源配置",
        )
        assertTrue(
            "与保存时一致的会话才允许重置",
            catalogSaveResetsDraft(saved, ENTRY_ID, "entry", listOf(ModelSource("supplier-1", "model-a"))),
        )
        assertFalse(
            "保存期间又改了名字：晚到的成功不得清掉新草稿",
            catalogSaveResetsDraft(saved, ENTRY_ID, "renamed", listOf(ModelSource("supplier-1", "model-a"))),
        )
        assertFalse(
            "保存期间又加了来源：晚到的成功不得清掉新草稿",
            catalogSaveResetsDraft(
                saved,
                ENTRY_ID,
                "entry",
                listOf(ModelSource("supplier-1", "model-a"), ModelSource("supplier-1", "model-b")),
            ),
        )
        assertFalse(
            "保存期间切到了另一行：回执不属于这个会话",
            catalogSaveResetsDraft(saved, "entry-2", "entry", listOf(ModelSource("supplier-1", "model-a"))),
        )
        assertFalse(
            "失败的结果永不重置草稿",
            catalogSaveResetsDraft(
                saved.copy(success = false),
                ENTRY_ID,
                "entry",
                listOf(ModelSource("supplier-1", "model-a")),
            ),
        )
    }

    @Test
    fun `a new entry's confirmation resets the same way`() {
        // 新增会话的 entryId 是 null，回执的 entryId 也是 null：两边对得上才算同一次保存。
        val saved = CatalogSaveOutcome(
            id = 2,
            entryId = null,
            success = true,
            unchanged = false,
            savedName = "new entry",
            savedSources = listOf(ModelSource("supplier-1", "model-a")),
            message = "已添加模型来源配置",
        )
        assertTrue(
            catalogSaveResetsDraft(saved, null, "new entry", listOf(ModelSource("supplier-1", "model-a"))),
        )
        assertFalse(
            "编辑已有条目的会话不得被新增回执重置",
            catalogSaveResetsDraft(saved, ENTRY_ID, "new entry", listOf(ModelSource("supplier-1", "model-a"))),
        )
    }

    // ---- Waiting ---------------------------------------------------------

    /** Includes sibling jobs launched while a preparation job is finishing. */
    private fun settle(subject: TesterViewModel) {
        awaitReal {
            while (true) {
                val pending = subject.viewModelScope.coroutineContext[Job]!!.children.toList()
                if (pending.isEmpty()) break
                pending.forEach { it.join() }
            }
        }
    }

    /** IO work and deadlines use real dispatchers, not the Main test scheduler. */
    private fun <T> awaitReal(block: suspend () -> T): T = try {
        runBlocking {
            withContext(Dispatchers.Default) { withTimeout(SETTLE_TIMEOUT_MS) { block() } }
        }
    } catch (timeout: TimeoutCancellationException) {
        throw AssertionError("等待测试操作超时（${SETTLE_TIMEOUT_MS}ms）", timeout)
    }

    // ---- Harness ---------------------------------------------------------

    private fun viewModel(
        relayApi: RelayApi = ModelsApi(emptyList()),
        balanceApi: BalanceApi = BalanceApi(),
        supplierStore: SupplierStore = SilentStore(),
    ): TesterViewModel = TesterViewModel(
        supplierStore = supplierStore,
        secretStore = object : SecretStore {
            override fun put(value: String): String = "secret-1"
            override fun get(secretId: String): String = "api-key"
            override fun delete(secretId: String) = Unit
        },
        relayApiFactory = { relayApi },
        balanceApiFactory = { balanceApi },
        skipRestore = true,
    ).also { subject ->
        lifecycle.put("panel-${subjects.size}", subject)
        subjects += subject
        if (supplierStore is GatedStore) releaseFixtures += { supplierStore.release(); Unit }
        if (supplierStore is RecordingStore) releaseFixtures += { supplierStore.gate?.complete(Unit); Unit }
        if (balanceApi is GatedBalanceApi) releaseFixtures += balanceApi::releaseAll
    }

    private fun seedEntry(subject: TesterViewModel, source: ModelSource) {
        setProfileForTest(subject)
        val catalogField = TesterViewModel::class.java.getDeclaredField("modelCatalog")
        catalogField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val catalog = catalogField.get(subject) as MutableList<ModelCatalogEntry>
        catalog += ModelCatalogEntry(id = ENTRY_ID, name = "entry", sources = listOf(source))
        val stateField = TesterViewModel::class.java.getDeclaredField("_uiState")
        stateField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val state = stateField.get(subject) as MutableStateFlow<TesterUiState>
        state.value = state.value.copy(
            isInitializing = false,
            modelCatalog = catalog.toList(),
        )
    }

    private fun setProfileForTest(subject: TesterViewModel, apiKeySecretId: String? = "secret-1") {
        val field = TesterViewModel::class.java.getDeclaredField("profiles")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val profiles = field.get(subject) as MutableList<SupplierProfile>
        profiles += SupplierProfile(
            id = "supplier-1",
            name = "supplier-1",
            baseUrl = "https://relay.test/v1",
            protocol = com.relaytester.app.core.model.RelayProtocol.CHAT_COMPLETIONS,
            apiKeySecretId = apiKeySecretId,
            models = listOf("model-a"),
            testSettings = TestSettings(),
        )
    }

    /**
     * Clears the boot-time guard so a fetch can actually run. Deliberately leaves the draft
     * empty: fetchCatalogModels then falls back to the stored secret, which is what the
     * missing-key path has to answer for — a draft with a typed key would never reach it.
     */
    private fun setPickerStateForTest(subject: TesterViewModel) {
        val field = TesterViewModel::class.java.getDeclaredField("_uiState")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val state = field.get(subject) as MutableStateFlow<TesterUiState>
        state.value = state.value.copy(isInitializing = false)
    }

    private fun setResultsForTest(subject: TesterViewModel, results: List<ModelTestResult>) {
        val field = TesterViewModel::class.java.getDeclaredField("_uiState")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val state = field.get(subject) as MutableStateFlow<TesterUiState>
        state.value = state.value.copy(isInitializing = false, results = results)
    }

    /** Flips the pull-only switch on a stored profile, the way the editor would persist it. */
    private fun setSupplierTestingDisabledForTest(
        subject: TesterViewModel,
        supplierId: String,
        disabled: Boolean,
    ) {
        val profilesField = TesterViewModel::class.java.getDeclaredField("profiles")
        profilesField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val profiles = profilesField.get(subject) as MutableList<SupplierProfile>
        val index = profiles.indexOfFirst { it.id == supplierId }
        if (index >= 0) {
            profiles[index] = profiles[index].copy(isTestingDisabled = disabled)
        }
        val stateField = TesterViewModel::class.java.getDeclaredField("_uiState")
        stateField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val state = stateField.get(subject) as MutableStateFlow<TesterUiState>
        state.value = state.value.copy(suppliers = profiles.toList())
    }

    /** Puts the panel in the state a run leaves it in, which is what the guards read. */
    private fun setRunningForTest(subject: TesterViewModel) {
        val stateField = TesterViewModel::class.java.getDeclaredField("_uiState")
        stateField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val state = stateField.get(subject) as MutableStateFlow<TesterUiState>
        state.value = state.value.copy(isRunning = true)
    }

    /** Puts the panel in the state an advanced source test leaves it in. */
    private fun setUnifiedTestingForTest(subject: TesterViewModel) {
        val stateField = TesterViewModel::class.java.getDeclaredField("_uiState")
        stateField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val state = stateField.get(subject) as MutableStateFlow<TesterUiState>
        state.value = state.value.copy(
            isUnifiedTesting = true,
            unifiedTestingKeys = setOf("entry\u0000supplier-1\u0000model-x"),
        )
    }

    /**
     * A panel with [suppliers] configured sites (all pointing at a template that
     * references no credentials), the first one active with an editable draft — the
     * dirty API key makes persistDraft() really suspend, which is the window the
     * balance start guard has to close.
     */
    private fun seedBalanceState(subject: TesterViewModel, suppliers: Int) {
        val template = BalanceQueryTemplate(
            id = "tpl-1",
            name = "tpl",
            description = "",
            method = BalanceHttpMethod.GET,
            endpointTemplate = "{{baseUrl}}/balance",
            headers = emptyList(),
            availablePath = "data.available",
        )
        val profilesField = TesterViewModel::class.java.getDeclaredField("profiles")
        profilesField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val profiles = profilesField.get(subject) as MutableList<SupplierProfile>
        (1..suppliers).mapTo(profiles) { index ->
            SupplierProfile(
                id = "supplier-$index",
                name = "supplier-$index",
                baseUrl = "https://relay.test/v1",
                protocol = com.relaytester.app.core.model.RelayProtocol.CHAT_COMPLETIONS,
                apiKeySecretId = null,
                models = listOf("model-a"),
                testSettings = TestSettings(),
            )
        }
        val templatesField = TesterViewModel::class.java.getDeclaredField("balanceTemplates")
        templatesField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        (templatesField.get(subject) as MutableList<BalanceQueryTemplate>) += template

        val stateField = TesterViewModel::class.java.getDeclaredField("_uiState")
        stateField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val state = stateField.get(subject) as MutableStateFlow<TesterUiState>
        state.value = state.value.copy(
            isInitializing = false,
            draft = com.relaytester.app.feature.tester.SupplierDraft
                .from(profiles.first(), "api-key")
                .copy(apiKeyDirty = true),
            activeSupplierId = profiles.first().id,
            suppliers = profiles.toList(),
        )
        // BalanceUiState boots as "initializing" until a real restore clears it;
        // skipRestore never does, and balanceOperationBlocked() would refuse to run.
        val balanceStateField = TesterViewModel::class.java.getDeclaredField("_balanceUiState")
        balanceStateField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val balanceState = balanceStateField.get(subject) as MutableStateFlow<com.relaytester.app.feature.tester.BalanceUiState>
        balanceState.value = balanceState.value.copy(isInitializing = false)
        // persistBalanceCredentials reads the ViewModel's own field, not the UI state.
        val activeIdField = TesterViewModel::class.java.getDeclaredField("activeSupplierId")
        activeIdField.isAccessible = true
        activeIdField.set(subject, profiles.first().id)
    }

    /** A panel with one real supplier, one ticked model, and an editable draft. */
    private fun seedRunnableState(subject: TesterViewModel, secondSupplier: Boolean = false) {
        val profile = SupplierProfile(
            id = "supplier-1",
            name = "supplier-1",
            baseUrl = "https://relay.test/v1",
            protocol = com.relaytester.app.core.model.RelayProtocol.CHAT_COMPLETIONS,
            apiKeySecretId = null,
            models = listOf("model-a"),
            // No inter-request delay: the runner's defaults (500–2000 ms) are scheduled
            // on the test dispatcher and would park the round before its first request.
            testSettings = TestSettings(delayMinMs = 0, delayMaxMs = 0, batchPauseMs = 0),
        )
        val field = TesterViewModel::class.java.getDeclaredField("profiles")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val profiles = field.get(subject) as MutableList<SupplierProfile>
        profiles += profile
        if (secondSupplier) {
            profiles += profile.copy(id = "supplier-2", name = "supplier-2", models = listOf("model-b"))
        }
        val stateField = TesterViewModel::class.java.getDeclaredField("_uiState")
        stateField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val state = stateField.get(subject) as MutableStateFlow<TesterUiState>
        state.value = state.value.copy(
            isInitializing = false,
            // Dirty, so persisting the draft really suspends on the Keystore write:
            // that suspension is the window the start guard has to close.
            draft = com.relaytester.app.feature.tester.SupplierDraft
                .from(profile, "api-key")
                .copy(apiKeyDirty = true),
            activeSupplierId = profile.id,
            selectedModels = setOf("model-a"),
            suppliers = profiles.toList(),
        )
        // 切换供应商的路径读的是 ViewModel 自己的 activeSupplierId 字段（不是 UI 状态），
        // 与 seedBalanceState 一样要把它也种上，否则 persistDraft/persistBalanceCredentials
        // 找不到活动站点、切换被当作失败回滚。
        val activeIdField = TesterViewModel::class.java.getDeclaredField("activeSupplierId")
        activeIdField.isAccessible = true
        activeIdField.set(subject, profile.id)
        // balanceOperationBlocked() 的门槛：BalanceUiState 默认从 initializing 起步，
        // skipRestore 永远不会清它。
        val balanceStateField = TesterViewModel::class.java.getDeclaredField("_balanceUiState")
        balanceStateField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val balanceState = balanceStateField.get(subject) as MutableStateFlow<com.relaytester.app.feature.tester.BalanceUiState>
        balanceState.value = balanceState.value.copy(isInitializing = false)
    }

    private class SilentStore : SupplierStore(null) {
        override suspend fun read() = SupplierStoreState(emptyList(), null)
        override suspend fun save(state: SupplierStoreState) = Unit
    }

    /**
     * Counts configuration writes, optionally failing them the way a full disk would,
     * optionally parking inside the write so a test can observe the in-flight window.
     */
    private class RecordingStore(
        private val fail: Boolean = false,
        /** Parks a write inside the store until the test releases it. */
        val gate: CompletableDeferred<Unit>? = null,
    ) : SupplierStore(null) {
        val saveCalls = java.util.concurrent.atomic.AtomicInteger(0)
        val entered = CompletableDeferred<Unit>()

        override suspend fun read() = SupplierStoreState(emptyList(), null)

        override suspend fun save(state: SupplierStoreState) {
            saveCalls.incrementAndGet()
            entered.complete(Unit)
            gate?.await()
            if (fail) error("磁盘写入失败")
        }
    }

    /** A relay whose model directory is exactly [models]. */
    private class ModelsApi(private val models: List<String>) : RelayApi() {
        override suspend fun fetchModels(
            profile: SupplierProfile,
            apiKey: String,
            timeoutSeconds: Int,
        ): ApiResult<List<String>> = ApiResult.Success(models)
    }

    /** A directory relay that records which suppliers it was asked about. */
    private class RecordingModelsApi(private val models: List<String>) : RelayApi() {
        val queriedSupplierIds = java.util.Collections.synchronizedList(mutableListOf<String>())

        override suspend fun fetchModels(
            profile: SupplierProfile,
            apiKey: String,
            timeoutSeconds: Int,
        ): ApiResult<List<String>> {
            queriedSupplierIds += profile.id
            return ApiResult.Success(models)
        }
    }

    /** A relay that refuses every directory fetch with [reason]. */
    private class FailingModelsApi(private val reason: String) : RelayApi() {
        override suspend fun fetchModels(
            profile: SupplierProfile,
            apiKey: String,
            timeoutSeconds: Int,
        ): ApiResult<List<String>> = ApiResult.Failure(
            com.relaytester.app.core.model.TestError(
                com.relaytester.app.core.model.ErrorKind.NETWORK,
                reason,
            ),
            null,
        )
    }

    /** A relay that answers every model test instantly and counts the requests. */
    private class RecordingTestApi(private val models: List<String>) : RelayApi() {
        val calls = java.util.concurrent.atomic.AtomicInteger(0)

        override suspend fun fetchModels(
            profile: SupplierProfile,
            apiKey: String,
            timeoutSeconds: Int,
        ): ApiResult<List<String>> = ApiResult.Success(models)

        override suspend fun test(
            profile: SupplierProfile,
            apiKey: String,
            model: String,
            prompt: String,
            maxTokens: Int,
            timeoutSeconds: Int,
        ): ModelTestResult {
            calls.incrementAndGet()
            return ModelTestResult(model = model, status = TestStatus.SUCCESS)
        }
    }

    /** Holds persistence open until the test has issued both taps. */
    private class GatedStore : SupplierStore(null) {
        val entered = CompletableDeferred<Unit>()
        private val gate = CompletableDeferred<Unit>()

        override suspend fun read() = SupplierStoreState(emptyList(), null)

        override suspend fun save(state: SupplierStoreState) {
            entered.complete(Unit)
            gate.await()
        }

        fun release() = gate.complete(Unit)
    }

    /** Each entered request stays open until its supplier or the whole fixture is released. */
    private class GatedBalanceApi : BalanceApi() {
        val calls = java.util.concurrent.atomic.AtomicInteger(0)
        val inFlight = java.util.concurrent.atomic.AtomicInteger(0)
        private val peak = java.util.concurrent.atomic.AtomicInteger(0)
        private val gates = mutableListOf<Pair<String, CompletableDeferred<Unit>>>()
        private val pendingSignals = mutableListOf<Pair<Int, CompletableDeferred<Unit>>>()
        private var released = false

        val maxInFlight: Int get() = peak.get()
        val queriedSupplierIds: List<String> get() = synchronized(gates) { gates.map { it.first } }

        /** Resolves only after the arriving requests have installed their release gates. */
        fun started(count: Int): CompletableDeferred<Unit> = synchronized(gates) {
            CompletableDeferred<Unit>().also { signal ->
                if (calls.get() >= count) {
                    signal.complete(Unit)
                } else {
                    pendingSignals += count to signal
                }
            }
        }

        fun release(supplierId: String) {
            synchronized(gates) { gates.filter { it.first == supplierId }.map { it.second } }
                .forEach { it.complete(Unit) }
        }

        fun releaseAll() {
            val pending = synchronized(gates) {
                released = true
                gates.map { it.second }
            }
            pending.forEach { it.complete(Unit) }
        }

        override suspend fun query(
            profile: SupplierProfile,
            apiKey: String,
            accessToken: String,
            userId: String,
            template: BalanceQueryTemplate,
        ): BalanceQueryResult {
            val gate = CompletableDeferred<Unit>()
            val signals = synchronized(gates) {
                gates += profile.id to gate
                if (released) gate.complete(Unit)
                val active = inFlight.incrementAndGet()
                peak.updateAndGet { previous -> maxOf(previous, active) }
                val arrived = calls.incrementAndGet()
                val ready = pendingSignals.filter { arrived >= it.first }
                pendingSignals.removeAll(ready.toSet())
                ready.map { it.second }
            }
            signals.forEach { it.complete(Unit) }
            try {
                gate.await()
            } finally {
                inFlight.decrementAndGet()
            }
            return BalanceQueryResult.Success(
                BalanceSnapshot(
                    supplierId = profile.id,
                    templateId = template.id,
                    templateName = template.name,
                    availableRaw = 1.0,
                    unitLabel = "USD",
                    scaleDivisor = 1.0,
                    checkedAt = 0L,
                    latencyMs = 1,
                ),
            )
        }
    }

    private companion object {
        const val ENTRY_ID = "entry-1"
        const val SETTLE_TIMEOUT_MS = 5_000L
    }
}
