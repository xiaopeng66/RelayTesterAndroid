package com.relaytester.app.feature.fingerprint

/**
 * How many model rows the picker shows before the list starts scrolling.
 *
 * The list sits inside a page that is itself scrollable, so an unbounded list would
 * push the run button arbitrarily far down and bury the challenge cards.
 */
internal const val VISIBLE_MODEL_ROWS = 5

/**
 * Models whose name contains [keyword], case-insensitively.
 *
 * A blank keyword keeps the whole list so the picker still shows everything when the
 * user has not typed anything. Order is preserved: it is the supplier's own order.
 */
internal fun filterModels(models: List<String>, keyword: String): List<String> {
    val needle = keyword.trim()
    if (needle.isEmpty()) return models
    return models.filter { it.contains(needle, ignoreCase = true) }
}

/**
 * Adds [model] to the end of [selected], or removes it.
 *
 * Appending is what makes "test them in order" mean the order the user ticked the
 * boxes rather than the supplier's alphabetical order, which the user never chose.
 */
internal fun toggleModel(selected: List<String>, model: String): List<String> =
    if (model in selected) selected.filterNot { it == model } else selected + model

/**
 * A keyword the supplier's catalogue has nothing to show for, offered as a model name.
 *
 * The field used to accept any typed name, and a supplier whose models were never
 * pulled has an empty list; without this the picker would have no way to reach such a
 * model at all. It is offered only when the filter matches nothing: while matches are
 * on screen the field is doing its documented job, and a second row for the bare
 * keyword reads as a duplicate of the model the user was looking for.
 */
internal fun unmatchedKeyword(models: List<String>, keyword: String): String? {
    val needle = keyword.trim()
    if (needle.isEmpty()) return null
    if (filterModels(models, needle).isNotEmpty()) return null
    return needle
}

/**
 * How many rows have reached a verdict.
 *
 * A failure is a result too: the row prints its reason right below this counter, so
 * counting only successes would show "已出结果 1/3" while three settled rows sit on
 * screen. Queued and in-flight rows are the ones still missing an outcome.
 */
internal fun settledModelCount(results: List<ModelFingerprintResult>): Int =
    results.count {
        it.status == ModelDetectionStatus.DONE || it.status == ModelDetectionStatus.FAILED
    }
