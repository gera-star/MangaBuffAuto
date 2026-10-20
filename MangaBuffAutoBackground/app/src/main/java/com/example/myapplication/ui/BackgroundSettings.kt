package com.example.myapplication.ui

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.example.myapplication.network.AppProxyConfig
import com.example.myapplication.network.AppProxySettings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.myapplication.data.MangaBuffAccount
import com.example.myapplication.network.AppProxyConfig
import com.example.myapplication.network.AppProxySettings

private data class BackgroundCheck(
    val title: String,
    val description: String,
    val enabled: Boolean
)

private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}

private fun openAppInfo(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
    )
}

private fun openBatterySettings(context: Context) {
    try {
        context.startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        )
    } catch (_: Exception) {
        context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }
}

private fun canScheduleExactAlarms(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    return alarmManager.canScheduleExactAlarms()
}

private fun openExactAlarmSettings(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    try {
        context.startActivity(
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        )
    } catch (_: Exception) {
        openAppInfo(context)
    }
}

private fun openAutostartSettings(context: Context) {
    val intents = listOf(
        Intent().setClassName(
            "com.miui.securitycenter",
            "com.miui.permcenter.autostart.AutoStartManagementActivity"
        ),
        Intent().setClassName(
            "com.miui.securitycenter",
            "com.miui.permcenter.permissions.PermissionsEditorActivity"
        )
    )
    for (intent in intents) {
        try {
            context.startActivity(intent)
            return
        } catch (_: Exception) {
        }
    }
    openAppInfo(context)
}

private fun hasNotifications(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED

@Composable
fun BackgroundSettingsDialog(
    onDismiss: () -> Unit,
    onOpenAbout: () -> Unit = {},
    accounts: List<MangaBuffAccount> = emptyList()
) {
    val context = LocalContext.current
    var refreshKey by remember { mutableStateOf(0) }
    var showProxyPasswordDialog by remember { mutableStateOf(false) }
    var showProxySettingsDialog by remember { mutableStateOf(false) }
    var proxyPasswordEntry by remember { mutableStateOf("") }
    var proxyPasswordError by remember { mutableStateOf(false) }

    val batteryOk = remember(refreshKey) { isIgnoringBatteryOptimizations(context) }
    val notificationsOk = remember(refreshKey) { hasNotifications(context) }
    val exactAlarmsOk = remember(refreshKey) { canScheduleExactAlarms(context) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                OutlinedButton(
                    onClick = onOpenAbout,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 12.dp,
                        vertical = 6.dp
                    )
                ) {
                    Icon(Icons.Default.Info, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(1.dp)
                    ) {
                        Text(
                            "О программе",
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                        )
                        Text(
                            "(нажми)",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
                Text(
                    "Фоновая работа",
                    style = MaterialTheme.typography.headlineSmall
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.62f).dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "Для надежной работы MangaBuff при свернутом приложении на Redmi/HyperOS нужны системные настройки. Приложение не может включить их без вашего подтверждения."
                )
                HorizontalDivider()

                BackgroundStatusRow(
                    BackgroundCheck(
                        "Уведомления",
                        "Нужно для постоянного уведомления Foreground Service.",
                        notificationsOk
                    )
                )
                BackgroundStatusRow(
                    BackgroundCheck(
                        "Без ограничений батареи",
                        "Разрешает приложению работать в фоне без обычного ограничения Android.",
                        batteryOk
                    )
                )
                BackgroundStatusRow(
                    BackgroundCheck(
                        "Точные будильники для Башни",
                        "Позволяют проснуться к завершению экспедиции без удержания CPU активным во время ожидания.",
                        exactAlarmsOk
                    )
                )

                Text(
                    "Для HyperOS также рекомендуется вручную включить «Автозапуск», снять ограничения фоновой активности и при необходимости закрепить приложение в списке недавних.",
                    style = MaterialTheme.typography.bodySmall
                )

                HorizontalDivider()

                HorizontalDivider()

                OutlinedButton(
                    onClick = {
                        proxyPasswordEntry = ""
                        proxyPasswordError = false
                        showProxyPasswordDialog = true
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Lock, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Настройки прокси аккаунтов")
                }
                Text(
                    "Раздел защищён паролем 1988. Для каждого аккаунта можно сохранить отдельный HTTP-прокси.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )

                HorizontalDivider()

                Text(
                    "Что уже встроено в MangaBuff Auto",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                )

                BackgroundFeatureRow(
                    "Foreground Service",
                    "Основная фоновая работа выполняется через постоянный сервис Android."
                )
                BackgroundFeatureRow(
                    "PARTIAL_WAKE_LOCK",
                    "Удерживается только на время работающих задач аккаунта; таймер Башни не требует WakeLock."
                )
                BackgroundFeatureRow(
                    "AlarmManager для Башни",
                    "Сохраняет срок экспедиции и будит приложение по таймеру. Для точного срабатывания Android может запросить специальный доступ."
                )
                BackgroundFeatureRow(
                    "WebView Watchdog",
                    "Сервис контролирует потерю WebView и тайм-аут восстановления."
                )
                BackgroundFeatureRow(
                    "Автовосстановление",
                    "После потери renderer аккаунт может быть запущен заново без запуска всех аккаунтов."
                )

                Spacer(Modifier.height(2.dp))

                OutlinedButton(
                    onClick = { openBatterySettings(context) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 38.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Icon(Icons.Default.OpenInNew, contentDescription = null)
                    Spacer(Modifier.padding(horizontal = 3.dp))
                    Text("Открыть настройки батареи")
                }

                OutlinedButton(
                    onClick = { openExactAlarmSettings(context) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 38.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ) {
                    Icon(Icons.Default.OpenInNew, contentDescription = null)
                    Spacer(Modifier.padding(horizontal = 3.dp))
                    Text("Разрешить точные будильники")
                }

                OutlinedButton(
                    onClick = { openAutostartSettings(context) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 38.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Icon(Icons.Default.OpenInNew, contentDescription = null)
                    Spacer(Modifier.padding(horizontal = 3.dp))
                    Text("Открыть автозапуск Xiaomi")
                }

                OutlinedButton(
                    onClick = { openAppInfo(context) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 38.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Icon(Icons.Default.OpenInNew, contentDescription = null)
                    Spacer(Modifier.padding(horizontal = 3.dp))
                    Text("Открыть страницу приложения")
                }
            }
        },
        confirmButton = {
            Button(onClick = { refreshKey++ }) {
                Text("Проверить снова")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Закрыть")
            }
        }
    )

    if (showProxyPasswordDialog) {
        AlertDialog(
            onDismissRequest = { showProxyPasswordDialog = false },
            title = { Text("Пароль для настроек прокси") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Введи пароль, чтобы открыть настройки прокси аккаунтов.")
                    OutlinedTextField(
                        value = proxyPasswordEntry,
                        onValueChange = {
                            proxyPasswordEntry = it
                            proxyPasswordError = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Пароль") },
                        singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation()
                    )
                    if (proxyPasswordError) {
                        Text(
                            "Неверный пароль.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (proxyPasswordEntry == "1988") {
                            showProxyPasswordDialog = false
                            showProxySettingsDialog = true
                            proxyPasswordEntry = ""
                            proxyPasswordError = false
                        } else {
                            proxyPasswordError = true
                        }
                    }
                ) {
                    Text("Открыть")
                }
            },
            dismissButton = {
                TextButton(onClick = { showProxyPasswordDialog = false }) {
                    Text("Отмена")
                }
            }
        )
    }

    if (showProxySettingsDialog) {
        AccountProxySettingsDialog(
            accounts = accounts,
            onDismiss = { showProxySettingsDialog = false }
        )
    }
}

@Composable
private fun AccountProxySettingsDialog(
    accounts: List<MangaBuffAccount>,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Прокси по аккаунтам") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.68f).dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (accounts.isEmpty()) {
                    Text("Сначала добавь аккаунт.")
                } else {
                    Text(
                        "Прокси включается отдельно для каждого аккаунта. Перед запуском задач приложение назначает WebView прокси активного аккаунта; задачи аккаунтов выполняются по очереди.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    accounts.forEach { account ->
                        AccountProxySettingsCard(account = account)
                        if (account != accounts.last()) HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text("Готово") }
        }
    )
}

@Composable
private fun AccountProxySettingsCard(account: MangaBuffAccount) {
    val context = LocalContext.current
    val savedConfig = remember(account.id) { AppProxySettings.read(context, account.id) }
    var enabled by remember(account.id) { mutableStateOf(savedConfig.enabled) }
    var host by remember(account.id) { mutableStateOf(savedConfig.host) }
    var port by remember(account.id) { mutableStateOf(savedConfig.port.toString()) }
    var username by remember(account.id) { mutableStateOf(savedConfig.username) }
    var password by remember(account.id) { mutableStateOf(savedConfig.password) }
    var status by remember(account.id) { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    account.username.ifBlank { "Аккаунт без имени" },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                )
                Text(
                    if (enabled) "Прокси включён" else "Прямое подключение",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                )
            }
            Switch(checked = enabled, onCheckedChange = { enabled = it; status = "" })
        }

        OutlinedTextField(
            value = host,
            onValueChange = { host = it; status = "" },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Адрес прокси") },
            placeholder = { Text("IP или имя хоста") },
            singleLine = true,
            enabled = enabled
        )
        OutlinedTextField(
            value = port,
            onValueChange = { port = it.filter(Char::isDigit).take(5); status = "" },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Порт") },
            placeholder = { Text("8080") },
            singleLine = true,
            enabled = enabled
        )
        OutlinedTextField(
            value = username,
            onValueChange = { username = it; status = "" },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Логин (необязательно)") },
            singleLine = true,
            enabled = enabled
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it; status = "" },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Пароль (необязательно)") },
            singleLine = true,
            enabled = enabled,
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation()
        )
        OutlinedButton(
            onClick = {
                val portNumber = port.toIntOrNull()
                val config = AppProxyConfig(
                    enabled = enabled,
                    host = host.trim(),
                    port = portNumber ?: 8080,
                    username = username,
                    password = password
                )
                if (enabled && (
                    !config.isValid() ||
                        host.contains("://") ||
                        host.contains("/")
                    )
                ) {
                    status = "Проверь адрес прокси и порт от 1 до 65535."
                } else {
                    try {
                        AppProxySettings.save(context, account.id, config)
                        status = "Сохранено. Применится перед следующим запуском задач этого аккаунта."
                    } catch (e: Exception) {
                        status = e.message ?: "Не удалось сохранить прокси."
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Сохранить прокси этого аккаунта")
        }
        if (status.isNotBlank()) {
            Text(
                status,
                style = MaterialTheme.typography.bodySmall,
                color = if (status.startsWith("Проверь") || status.startsWith("Не удалось")) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                }
            )
        }
    }
}

@Composable
private fun BackgroundFeatureRow(
    title: String,
    description: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 10.dp)
        ) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(description, style = MaterialTheme.typography.bodySmall)
        }
        Text(
            "ВСТРОЕНО",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun BackgroundStatusRow(status: BackgroundCheck) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (status.enabled) Icons.Default.CheckCircle else Icons.Default.Warning,
            contentDescription = null,
            tint = if (status.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 10.dp)
        ) {
            Text(status.title, style = MaterialTheme.typography.bodyMedium)
            Text(status.description, style = MaterialTheme.typography.bodySmall)
        }
        Text(
            if (status.enabled) "ВКЛ" else "ВЫКЛ",
            style = MaterialTheme.typography.labelMedium,
            color = if (status.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
        )
    }
}
