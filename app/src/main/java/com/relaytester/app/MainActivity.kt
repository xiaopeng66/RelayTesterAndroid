package com.relaytester.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.relaytester.app.feature.backup.ConfigurationBackupDialog
import com.relaytester.app.feature.balance.BalanceScreen
import com.relaytester.app.feature.fingerprint.FingerprintScreen
import com.relaytester.app.feature.fingerprint.FingerprintViewModel
import com.relaytester.app.feature.tester.TesterScreen
import com.relaytester.app.feature.tester.TesterViewModel
import com.relaytester.app.feature.update.AppUpdateViewModel
import com.relaytester.app.feature.update.UpdateDialog
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
        val fingerprintViewModelFactory = remember(applicationContext) {
            FingerprintViewModel.factory(applicationContext)
        }
        val fingerprintViewModel: FingerprintViewModel = viewModel(factory = fingerprintViewModelFactory)
        val appUpdateViewModelFactory = remember(applicationContext) {
            AppUpdateViewModel.factory(applicationContext)
        }
        val appUpdateViewModel: AppUpdateViewModel = viewModel(factory = appUpdateViewModelFactory)
        var destinationName by rememberSaveable { mutableStateOf(AppDestination.MODEL_TEST.name) }
        var showConfigurationBackup by remember { mutableStateOf(false) }
        var showUpdates by remember { mutableStateOf(false) }
        val destination = AppDestination.entries.firstOrNull { it.name == destinationName }
            ?: AppDestination.MODEL_TEST
        val appUpdateState by appUpdateViewModel.uiState.collectAsStateWithLifecycle()
        val updateSnackbarHostState = remember { SnackbarHostState() }
        // The launch check: throttled inside the view model, and it stays off when the
        // switch is off. Keyed on nothing, so it runs once per activity, not per tab switch.
        LaunchedEffect(Unit) { appUpdateViewModel.checkOnLaunch() }

        // A Box only so the snackbar host below can sit at the bottom edge; the
        // screens keep their own layout.
        Box(modifier = Modifier.fillMaxSize()) {

        when (destination) {
            AppDestination.MODEL_TEST -> TesterScreen(
                viewModel = testerViewModel,
                activeDestination = destination,
                onDestinationSelected = { destinationName = it.name },
                onConfigurationBackup = { showConfigurationBackup = true },
                onOpenUpdates = { showUpdates = true },
                hasAppUpdate = appUpdateState.available != null,
                onFingerprintModel = { model ->
                    // Jump to the fingerprint panel with this model preloaded; the
                    // supplying test already proved the endpoint answers.
                    fingerprintViewModel.prefill(testerViewModel.uiState.value.activeSupplierId, model)
                    destinationName = AppDestination.FINGERPRINT.name
                },
            )

            AppDestination.BALANCE -> BalanceScreen(
                viewModel = testerViewModel,
                activeDestination = destination,
                onDestinationSelected = { destinationName = it.name },
                onConfigurationBackup = { showConfigurationBackup = true },
                onOpenUpdates = { showUpdates = true },
                hasAppUpdate = appUpdateState.available != null,
            )

            AppDestination.FINGERPRINT -> FingerprintScreen(
                viewModel = fingerprintViewModel,
                activeDestination = destination,
                onDestinationSelected = { destinationName = it.name },
                onConfigurationBackup = { showConfigurationBackup = true },
                onOpenUpdates = { showUpdates = true },
                hasAppUpdate = appUpdateState.available != null,
            )
        }

        if (showConfigurationBackup) {
            ConfigurationBackupDialog(
                viewModel = testerViewModel,
                onDismiss = { showConfigurationBackup = false },
            )
        }

        if (showUpdates) {
            // 打开这一页不再单独触发一次检查：查的时机是「打开 App 一次」加常驻的 6 小时
            // 定时器（都在视图模型里），多一个入口只会多一次与手动检查抢同一个作业的机会。
            UpdateDialog(
                appViewModel = appUpdateViewModel,
                onDismiss = { showUpdates = false },
            )
        }

        // The app-update channel talks to the user on its own schedule: the launch check
        // finishes while whatever tab happens to be open is on screen, and none of the three
        // screens knows about that feed. Without a host here its news and its failures would
        // be written into the view model and never read — which is exactly what happened
        // before this was added. While the update page is open it hosts the same messages
        // itself (the dialog is its own window, so this one would be behind it); the two are
        // exclusive on `showUpdates`, so nothing is shown twice.
        if (!showUpdates) {
            SnackbarHost(
                hostState = updateSnackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
            LaunchedEffect(appUpdateState.message) {
                val text = appUpdateState.message ?: return@LaunchedEffect
                updateSnackbarHostState.showSnackbar(text)
                appUpdateViewModel.clearMessage()
            }
        }

        }

    }
}
