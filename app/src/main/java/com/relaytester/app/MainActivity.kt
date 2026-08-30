package com.relaytester.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.relaytester.app.feature.backup.ConfigurationBackupDialog
import com.relaytester.app.feature.balance.BalanceScreen
import com.relaytester.app.feature.tester.TesterScreen
import com.relaytester.app.feature.tester.TesterViewModel
import com.relaytester.app.ui.navigation.AppDestination
import com.relaytester.app.ui.theme.RelayTesterTheme

/**
 * The platform Splash is the only launch surface. Android 12+ retains it until the
 * first Compose frame is rendered; adding a separate in-app loading screen causes a
 * visibly duplicated launch sequence.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_RelayTester)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RelayTesterApp()
        }
    }
}

@androidx.compose.runtime.Composable
private fun RelayTesterApp() {
    RelayTesterTheme {
        val applicationContext = LocalContext.current.applicationContext
        // This factory is remembered across recompositions and does not initialise
        // network clients; the platform Splash is free to stay visible for the first frame.
        val testerViewModelFactory = remember(applicationContext) {
            TesterViewModel.factory(applicationContext)
        }
        val testerViewModel: TesterViewModel = viewModel(factory = testerViewModelFactory)
        var destinationName by rememberSaveable { mutableStateOf(AppDestination.MODEL_TEST.name) }
        var showConfigurationBackup by remember { mutableStateOf(false) }
        val destination = AppDestination.entries.firstOrNull { it.name == destinationName }
            ?: AppDestination.MODEL_TEST

        when (destination) {
            AppDestination.MODEL_TEST -> TesterScreen(
                viewModel = testerViewModel,
                activeDestination = destination,
                onDestinationSelected = { destinationName = it.name },
                onConfigurationBackup = { showConfigurationBackup = true },
            )

            AppDestination.BALANCE -> BalanceScreen(
                viewModel = testerViewModel,
                activeDestination = destination,
                onDestinationSelected = { destinationName = it.name },
                onConfigurationBackup = { showConfigurationBackup = true },
            )
        }

        if (showConfigurationBackup) {
            ConfigurationBackupDialog(
                viewModel = testerViewModel,
                onDismiss = { showConfigurationBackup = false },
            )
        }
    }
}
