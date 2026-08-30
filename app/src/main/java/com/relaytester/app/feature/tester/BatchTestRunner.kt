package com.relaytester.app.feature.tester

import com.relaytester.app.core.model.BatchTestConfig
import com.relaytester.app.core.model.ErrorKind
import com.relaytester.app.core.model.ModelTestResult
import com.relaytester.app.core.model.TestRunSummary
import com.relaytester.app.core.model.TestStatus
import com.relaytester.app.core.network.RelayApi
import kotlin.math.max
import kotlin.random.Random
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

class BatchTestRunner(private val relayApi: RelayApi) {
    suspend fun run(
        config: BatchTestConfig,
        onResult: suspend (ModelTestResult, Int, Int) -> Unit,
    ): TestRunSummary {
        val startedAt = System.nanoTime()
        val allResults = mutableListOf<ModelTestResult>()
        var completed = 0
        val models = config.models.distinct()
        val semaphore = Semaphore(config.settings.concurrency.coerceIn(1, 20))
        val progressMutex = Mutex()
        val minDelay = config.settings.delayMinMs.coerceAtLeast(0)
        val maxDelay = max(minDelay, config.settings.delayMaxMs)
        val batches = models.chunked(config.settings.batchSize.coerceIn(1, 200))

        batches.forEachIndexed { batchIndex, batch ->
            coroutineScope {
                batch.map { model ->
                    async {
                        val result = semaphore.withPermit {
                            randomDelay(minDelay, maxDelay)
                            testWithRetry(config, model, minDelay, maxDelay)
                        }
                        val progress = progressMutex.withLock {
                            allResults += result
                            completed += 1
                            completed to models.size
                        }
                        onResult(result, progress.first, progress.second)
                    }
                }.awaitAll()
            }
            if (batchIndex < batches.lastIndex) {
                delay(config.settings.batchPauseMs.coerceIn(0, 60_000))
            }
        }

        return allResults.toSummary((System.nanoTime() - startedAt) / NANOS_PER_MILLISECOND)
    }

    private suspend fun testWithRetry(
        config: BatchTestConfig,
        model: String,
        minDelay: Long,
        maxDelay: Long,
    ): ModelTestResult {
        var lastResult: ModelTestResult? = null
        repeat(config.settings.retryCount.coerceIn(0, 5) + 1) { attempt ->
            if (attempt > 0) randomDelay(minDelay, maxDelay)
            val result = relayApi.test(
                profile = config.supplier,
                apiKey = config.apiKey,
                model = model,
                prompt = config.settings.prompt,
                maxTokens = config.settings.maxTokens,
                timeoutSeconds = config.settings.timeoutSeconds,
            )
            lastResult = result
            if (result.status == TestStatus.SUCCESS || !result.shouldRetry()) {
                return result
            }
        }
        return requireNotNull(lastResult)
    }

    private suspend fun randomDelay(minDelay: Long, maxDelay: Long) {
        if (maxDelay > 0) {
            delay(if (maxDelay == minDelay) minDelay else Random.nextLong(minDelay, maxDelay + 1))
        }
    }

    private fun ModelTestResult.shouldRetry(): Boolean = error?.kind in setOf(
        ErrorKind.NETWORK,
        ErrorKind.TIMEOUT,
        ErrorKind.RATE_LIMITED,
        ErrorKind.UPSTREAM,
    )

    private fun List<ModelTestResult>.toSummary(elapsedMs: Long): TestRunSummary {
        val succeeded = count { it.status == TestStatus.SUCCESS }
        val failed = count { it.status == TestStatus.FAILED }
        val latencies = filter { it.status == TestStatus.SUCCESS }
            .mapNotNull { it.latencyMs }
        return TestRunSummary(
            total = size,
            succeeded = succeeded,
            failed = failed,
            elapsedMs = elapsedMs,
            averageLatencyMs = latencies.takeIf(List<Long>::isNotEmpty)
                ?.average()
                ?.toLong(),
            fastestLatencyMs = latencies.minOrNull(),
            slowestLatencyMs = latencies.maxOrNull(),
            totalTokens = sumOf { it.usage?.resolvedTotal ?: 0 },
            errorCounts = filter { it.status == TestStatus.FAILED }
                .mapNotNull { it.error?.kind }
                .groupingBy { it }
                .eachCount(),
        )
    }

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
