package com.relaytester.app.ui.navigation

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

enum class AppDestination(val label: String) {
    MODEL_TEST("模型测试"),
    BALANCE("余额查询"),
}

@Composable
fun AppDestinationTabs(
    selected: AppDestination,
    onSelected: (AppDestination) -> Unit,
) {
    TabRow(
        selectedTabIndex = AppDestination.entries.indexOf(selected),
        modifier = Modifier.fillMaxWidth(),
    ) {
        AppDestination.entries.forEachIndexed { index, destination ->
            Tab(
                selected = selected == destination,
                onClick = { onSelected(destination) },
                text = { Text(destination.label) },
            )
        }
    }
}
