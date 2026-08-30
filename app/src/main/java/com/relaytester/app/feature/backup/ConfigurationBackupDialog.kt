package com.relaytester.app.feature.backup

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.relaytester.app.core.backup.ConfigurationBackupCodec
import com.relaytester.app.core.backup.ConfigurationBackupPreview
import com.relaytester.app.feature.tester.ConfigurationBackupExportPayload
import com.relaytester.app.feature.tester.ConfigurationBackupUiState
import com.relaytester.app.feature.tester.TesterViewModel
import java.io.ByteArrayOutputStream
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Shared full-configuration migration UI, hosted by MainActivity for both top-level pages. */
@Composable
fun ConfigurationBackupDialog(
    viewModel: TesterViewModel,
    onDismiss: () -> Unit,
) {
    val state by viewModel.configurationBackupUiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var exportPassword by remember { mutableStateOf("") }
    var exportPasswordConfirmation by remember { mutableStateOf("") }
    var showExportPassword by remember { mutableStateOf(false) }
    var importPassword by remember { mutableStateOf("") }
    var confirmationPassword by remember { mutableStateOf("") }

    val createDocument = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        val payload = state.exportPayload
        if (uri == null || payload == null) {
            viewModel.discardConfigurationBackupExport()
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val written = runCatching {
                writeBackupFile(context, uri, payload)
            }
            viewModel.discardConfigurationBackupExport()
            written.onSuccess {
                Toast.makeText(context, "配置备份已导出", Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(context, "配置备份导出失败", Toast.LENGTH_LONG).show()
            }
        }
    }
    val openDocument = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching { readBackupFile(context, uri) }
                .onSuccess(viewModel::selectConfigurationImportFile)
                .onFailure {
                    Toast.makeText(context, "无法读取配置备份文件", Toast.LENGTH_LONG).show()
                }
        }
    }

    LaunchedEffect(state.exportPayload?.fileName) {
        state.exportPayload?.let { payload ->
            exportPassword = ""
            exportPasswordConfirmation = ""
            showExportPassword = false
            createDocument.launch(payload.fileName)
        }
    }
    LaunchedEffect(state.importPreview) {
        if (state.importPreview != null) {
            // The confirmation step asks for the password again so the first
            // password is not retained in the dialog after previewing.
            importPassword = ""
        }
    }

    when {
        state.exportPayload != null -> ConfigurationBackupBusyDialog(
            title = "等待保存位置",
            description = "请在系统文件选择器中保存加密备份。取消后不会改变现有配置。",
        )

        state.isBusy -> ConfigurationBackupBusyDialog(
            title = "正在处理配置备份",
            description = "正在本机加密、验证或恢复配置，不会访问任何站点。",
        )

        state.importPreview != null -> ConfigurationBackupImportConfirmationDialog(
            preview = requireNotNull(state.importPreview),
            password = confirmationPassword,
            onPasswordChange = { confirmationPassword = it },
            errorMessage = state.message.takeIf { state.isMessageError },
            onConfirm = { viewModel.confirmConfigurationImport(confirmationPassword) },
            onRechoose = {
                confirmationPassword = ""
                viewModel.clearPendingConfigurationImport()
                openDocument.launch(BACKUP_MIME_TYPES)
            },
            onDismiss = {
                confirmationPassword = ""
                viewModel.clearPendingConfigurationImport()
                onDismiss()
            },
        )

        state.importFileSelected -> ConfigurationBackupImportPasswordDialog(
            password = importPassword,
            onPasswordChange = { importPassword = it },
            errorMessage = state.message.takeIf { state.isMessageError },
            onPreview = { viewModel.previewConfigurationImport(importPassword) },
            onRechoose = {
                importPassword = ""
                viewModel.clearPendingConfigurationImport()
                openDocument.launch(BACKUP_MIME_TYPES)
            },
            onDismiss = {
                importPassword = ""
                viewModel.clearPendingConfigurationImport()
                onDismiss()
            },
        )

        showExportPassword -> ConfigurationBackupExportPasswordDialog(
            password = exportPassword,
            confirmation = exportPasswordConfirmation,
            onPasswordChange = { exportPassword = it },
            onConfirmationChange = { exportPasswordConfirmation = it },
            errorMessage = state.message.takeIf { state.isMessageError },
            onExport = { viewModel.createConfigurationBackup(exportPassword) },
            onDismiss = {
                exportPassword = ""
                exportPasswordConfirmation = ""
                showExportPassword = false
                viewModel.clearConfigurationBackupMessage()
            },
        )

        else -> ConfigurationBackupHomeDialog(
            state = state,
            onExport = {
                viewModel.clearConfigurationBackupMessage()
                showExportPassword = true
            },
            onImport = {
                viewModel.clearConfigurationBackupMessage()
                openDocument.launch(BACKUP_MIME_TYPES)
            },
            onDismiss = {
                viewModel.clearPendingConfigurationImport()
                onDismiss()
            },
        )
    }
}

// Some Android document providers report a custom `.rtbackup` extension as an
// unknown MIME type. Accepting the picker result broadly keeps user-created
// backups selectable; the ViewModel/codec still enforce size, envelope,
// version, KDF, AES-GCM and field validation before any configuration changes.
private val BACKUP_MIME_TYPES = arrayOf("*/*")

@Composable
private fun ConfigurationBackupHomeDialog(
    state: ConfigurationBackupUiState,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Lock, contentDescription = null) },
        title = { Text("配置导入与导出") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "加密备份会迁移供应商、模型、测试参数和余额模板。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "其中包含 API Key、PAT 和用户 ID；测试结果 JSON 不包含这些凭据。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "密码不会保存。遗失后无法恢复备份内容。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                state.message?.let { message ->
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.isMessageError) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.tertiary
                        },
                    )
                }
            }
        },
        confirmButton = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onExport,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) { Text("导出") }
                OutlinedButton(
                    onClick = onImport,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) { Text("导入") }
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) { Text("关闭") }
            }
        },
    )
}

@Composable
private fun ConfigurationBackupExportPasswordDialog(
    password: String,
    confirmation: String,
    onPasswordChange: (String) -> Unit,
    onConfirmationChange: (String) -> Unit,
    errorMessage: String?,
    onExport: () -> Unit,
    onDismiss: () -> Unit,
) {
    val passwordsMatch = password == confirmation
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设置备份密码") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "请使用至少 ${ConfigurationBackupCodec.MIN_PASSWORD_LENGTH} 个字符的独立密码。备份中包含敏感凭据。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                BackupPasswordField(
                    value = password,
                    onValueChange = onPasswordChange,
                    label = "备份密码",
                )
                BackupPasswordField(
                    value = confirmation,
                    onValueChange = onConfirmationChange,
                    label = "再次输入备份密码",
                    isError = confirmation.isNotEmpty() && !passwordsMatch,
                )
                if (confirmation.isNotEmpty() && !passwordsMatch) {
                    BackupInlineError("两次输入的密码不一致")
                }
                errorMessage?.let { message -> BackupInlineError(message) }
            }
        },
        confirmButton = {
            Button(
                onClick = onExport,
                enabled = password.length >= ConfigurationBackupCodec.MIN_PASSWORD_LENGTH && passwordsMatch,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text("创建加密备份")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("取消")
            }
        },
    )
}

@Composable
private fun ConfigurationBackupImportPasswordDialog(
    password: String,
    onPasswordChange: (String) -> Unit,
    errorMessage: String?,
    onPreview: () -> Unit,
    onRechoose: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("验证配置备份") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "输入创建此备份时的密码。验证成功后会先显示摘要，尚不会覆盖当前配置。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                BackupPasswordField(
                    value = password,
                    onValueChange = onPasswordChange,
                    label = "备份密码",
                )
                errorMessage?.let { message -> BackupInlineError(message) }
            }
        },
        confirmButton = {
            Button(
                onClick = onPreview,
                enabled = password.length >= ConfigurationBackupCodec.MIN_PASSWORD_LENGTH,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("验证并预览") }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onRechoose, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("重选文件")
                }
                OutlinedButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("取消")
                }
            }
        },
    )
}

@Composable
private fun ConfigurationBackupImportConfirmationDialog(
    preview: ConfigurationBackupPreview,
    password: String,
    onPasswordChange: (String) -> Unit,
    errorMessage: String?,
    onConfirm: () -> Unit,
    onRechoose: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("确认覆盖当前配置") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "备份创建于 ${formatBackupTime(preview.createdAt)}，包含：${preview.supplierCount} 个供应商、${preview.customTemplateCount} 个自定义余额模板、${preview.apiKeyCount} 个 API Key、${preview.balanceTokenCount} 个余额令牌。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "继续会替换当前全部配置，并清空本次运行的测试结果与余额快照；不会自动请求站点。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                BackupPasswordField(
                    value = password,
                    onValueChange = onPasswordChange,
                    label = "再次输入备份密码以确认",
                )
                errorMessage?.let { message -> BackupInlineError(message) }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = password.length >= ConfigurationBackupCodec.MIN_PASSWORD_LENGTH,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("确认覆盖") }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onRechoose, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("重选文件")
                }
                OutlinedButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("取消")
                }
            }
        },
    )
}

@Composable
private fun ConfigurationBackupBusyDialog(title: String, description: String) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(title) },
        text = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.padding(4.dp))
                Text(description, style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = {},
    )
}

@Composable
private fun BackupPasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    isError: Boolean = false,
) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = true,
        isError = isError,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Password),
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    imageVector = if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    contentDescription = if (visible) "隐藏备份密码" else "显示备份密码",
                )
            }
        },
    )
}

@Composable
private fun BackupInlineError(message: String) {
    Text(
        message,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
    )
}

private suspend fun writeBackupFile(
    context: Context,
    uri: Uri,
    payload: ConfigurationBackupExportPayload,
) = withContext(Dispatchers.IO) {
    context.contentResolver.openOutputStream(uri)?.use { stream ->
        stream.write(payload.encryptedBytes)
        stream.flush()
    } ?: error("无法打开备份文件")
}

private suspend fun readBackupFile(context: Context, uri: Uri): ByteArray = withContext(Dispatchers.IO) {
    context.contentResolver.openInputStream(uri)?.use { stream ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            total += read
            if (total > ConfigurationBackupCodec.MAX_BACKUP_BYTES) {
                throw IllegalArgumentException("备份文件超过大小限制")
            }
            output.write(buffer, 0, read)
        }
        output.toByteArray()
    } ?: error("无法打开备份文件")
}

private fun formatBackupTime(timestamp: Long): String = if (timestamp > 0) {
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(timestamp))
} else {
    "未知时间"
}
