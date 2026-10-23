package com.example.myapplication.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.myapplication.slot.SlotLauncher
import com.example.myapplication.slot.SlotProxyType
import com.example.myapplication.slot.SlotRoute

@Composable
fun SlotContainerDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var slot by remember { mutableIntStateOf(0) }
    var url by remember { mutableStateOf("https://mangabuff.ru/") }
    var proxyType by remember { mutableStateOf(SlotProxyType.NONE) }
    var proxyHost by remember { mutableStateOf("") }
    var proxyPort by remember { mutableStateOf("8080") }
    var proxyUsername by remember { mutableStateOf("") }
    var proxyPassword by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Изолированная вкладка") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "Доступны ровно 3 слота. У каждого отдельный процесс и профиль WebView. " +
                        "Первый маршрут слота закрепляется до завершения процесса; закрытия Activity недостаточно, чтобы безопасно сменить прокси."
                )
                Text("Выбери слот")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (0 until SlotLauncher.SLOT_COUNT).forEach { index ->
                        FilterChip(
                            selected = slot == index,
                            onClick = { slot = index },
                            label = { Text("Слот " + (index + 1)) }
                        )
                    }
                }
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("URL сайта") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                )
                Text("Маршрут сети")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = proxyType == SlotProxyType.NONE,
                        onClick = { proxyType = SlotProxyType.NONE },
                        label = { Text("Без прокси") }
                    )
                    FilterChip(
                        selected = proxyType == SlotProxyType.HTTP,
                        onClick = { proxyType = SlotProxyType.HTTP },
                        label = { Text("HTTP") }
                    )
                    FilterChip(
                        selected = proxyType == SlotProxyType.SOCKS5,
                        onClick = { proxyType = SlotProxyType.SOCKS5 },
                        label = { Text("SOCKS5") }
                    )
                }
                if (proxyType != SlotProxyType.NONE) {
                    OutlinedTextField(
                        value = proxyHost,
                        onValueChange = { proxyHost = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Хост прокси") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = proxyPort,
                        onValueChange = { proxyPort = it.filter { ch -> ch.isDigit() }.take(5) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Порт") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    OutlinedTextField(
                        value = proxyUsername,
                        onValueChange = { proxyUsername = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Логин прокси (если нужен)") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = proxyPassword,
                        onValueChange = { proxyPassword = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Пароль прокси (если нужен)") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation()
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val route = SlotRoute(
                        type = proxyType,
                        host = proxyHost.trim(),
                        port = if (proxyType == SlotProxyType.NONE) 0 else proxyPort.toIntOrNull() ?: 0,
                        username = proxyUsername,
                        password = proxyPassword
                    )
                    try {
                        val result = SlotLauncher.launch(
                            context = context,
                            slot = slot,
                            tabId = "slot" + slot,
                            url = url,
                            route = route
                        )
                        result.fold(
                            onSuccess = {
                                Toast.makeText(
                                    context,
                                    "Запрос передан в слот " + (slot + 1) + "/" + SlotLauncher.SLOT_COUNT +
                                        ". Окно слота покажет результат применения маршрута.",
                                    Toast.LENGTH_LONG
                                ).show()
                                onDismiss()
                            },
                            onFailure = { error ->
                                Toast.makeText(
                                    context,
                                    error.message ?: "Не удалось отправить запрос в слот.",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        )
                    } catch (_: Exception) {
                        Toast.makeText(
                            context,
                            "Не удалось открыть слот. Проверь поддержку изолированных процессов WebView.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            ) { Text("Открыть") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        }
    )
}
