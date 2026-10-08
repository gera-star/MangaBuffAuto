package com.example.myapplication.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.example.myapplication.data.GlobalSettings
import com.example.myapplication.data.NetworkResetMode
import rikka.shizuku.Shizuku
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

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

private const val SHIZUKU_REQUEST_CODE = 4107

private fun isShizukuReady(): Boolean =
    try {
        Shizuku.pingBinder() &&
            !Shizuku.isPreV11() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) {
        false
    }

private fun requestShizukuPermission() {
    try {
        if (Shizuku.pingBinder() &&
            !Shizuku.isPreV11() &&
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED
        ) {
            Shizuku.requestPermission(SHIZUKU_REQUEST_CODE)
        }
    } catch (_: Throwable) {
    }
}

private fun hasNotifications(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED

@Composable
fun BackgroundSettingsDialog(
    settings: GlobalSettings,
    onSaveSettings: (GlobalSettings) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var refreshKey by remember { mutableStateOf(0) }

    val batteryOk = remember(refreshKey) { isIgnoringBatteryOptimizations(context) }
    val notificationsOk = remember(refreshKey) { hasNotifications(context) }
    val shizukuReady = remember(refreshKey) { isShizukuReady() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Фоновая работа") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Для надежной работы MangaBuff при свернутом приложении на Redmi/HyperOS нужны системные настройки. Приложение не может включить их без вашего подтверждения."
                )
                HorizontalDivider()

                Text(
                    "Аккаунты: ПО ОЧЕРЕДИ",
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    "Следующий аккаунт запускается только после полного cleanup предыдущего.",
                    style = MaterialTheme.typography.bodySmall
                )

                SettingSwitchRow(
                    title = "Не выключать экран во время автоматизации",
                    description = "Экран остаётся включённым на протяжении всей последовательной сессии.",
                    checked = settings.keepScreenOn,
                    onCheckedChange = {
                        onSaveSettings(settings.copy(keepScreenOn = it))
                    }
                )

                SettingSwitchRow(
                    title = "Сетевой reset между аккаунтами",
                    description = "Перед следующим аккаунтом можно выполнить привилегированный Airplane Mode reset и дождаться валидной сети.",
                    checked = settings.networkResetEnabled,
                    onCheckedChange = {
                        onSaveSettings(
                            settings.copy(
                                networkResetEnabled = it,
                                networkResetMode = if (it) {
                                    NetworkResetMode.AIRPLANE_MODE
                                } else {
                                    NetworkResetMode.NONE
                                }
                            )
                        )
                    }
                )

                if (settings.networkResetEnabled) {
                    BackgroundStatusRow(
                        BackgroundCheck(
                            "Shizuku",
                            "Нужен для автоматического Airplane Mode без root: Shizuku через Wireless Debugging даёт UserService shell-уровень.",
                            shizukuReady
                        )
                    )

                    if (!shizukuReady) {
                        OutlinedButton(
                            onClick = {
                                requestShizukuPermission()
                                refreshKey++
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Разрешить Shizuku")
                        }
                    } else {
                        Text(
                            "Shizuku готов. Перед следующим аккаунтом приложение сможет выполнить network reset через privileged UserService.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }

                    Text(
                        "Если Shizuku не запущен/не разрешён, reset не должен запускать следующий аккаунт — приложение остановит последовательную сессию безопасно.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }

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

                Text(
                    "Для HyperOS также рекомендуется вручную включить «Автозапуск», снять ограничения фоновой активности и при необходимости закрепить приложение в списке недавних.",
                    style = MaterialTheme.typography.bodySmall
                )

                Spacer(Modifier.height(2.dp))

                OutlinedButton(
                    onClick = { openBatterySettings(context) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.OpenInNew, contentDescription = null)
                    Spacer(Modifier.padding(horizontal = 3.dp))
                    Text("Открыть настройки батареи")
                }

                OutlinedButton(
                    onClick = { openAutostartSettings(context) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.OpenInNew, contentDescription = null)
                    Spacer(Modifier.padding(horizontal = 3.dp))
                    Text("Открыть автозапуск Xiaomi")
                }

                OutlinedButton(
                    onClick = { openAppInfo(context) },
                    modifier = Modifier.fillMaxWidth()
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
}

@Composable
private fun SettingSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(description, style = MaterialTheme.typography.bodySmall)
        }
        androidx.compose.material3.Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
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
