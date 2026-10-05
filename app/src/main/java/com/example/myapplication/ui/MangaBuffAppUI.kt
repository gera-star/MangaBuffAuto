package com.example.myapplication.ui

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.example.myapplication.data.GlobalSettings
import com.example.myapplication.data.LogEntry
import com.example.myapplication.data.MangaBuffAccount
import com.example.myapplication.data.TaskType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

@SuppressLint("RestrictedApi")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MangaBuffAppUI(
    viewModel: MainViewModel,
    webViewContainer: WebView? = null,
    onOpenAddAccountWebView: () -> Unit = {}
) {
    val accounts by viewModel.accounts.collectAsState()
    val logs by viewModel.logs.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val isRunning by viewModel.isRunning.collectAsState()
    val activeTab by viewModel.activeTab.collectAsState()
    val showAddAccountDialog by viewModel.showAddAccountDialog.collectAsState()
    val showPromoDialog by viewModel.showPromoDialog.collectAsState()

    var selectedBalanceAccount by remember { mutableStateOf<MangaBuffAccount?>(null) }
    var promoCodeText by remember { mutableStateOf("") }
    val context = LocalContext.current
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("MangaBuff Automation", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        },
        floatingActionButton = {
            if (activeTab == 0) {
                FloatingActionButton(
                    onClick = { viewModel.setShowAddAccountDialog(true) },
                    containerColor = MaterialTheme.colorScheme.primary
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Добавить аккаунт")
                }
            }
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = activeTab == 0,
                    onClick = { viewModel.setActiveTab(0) },
                    icon = { Icon(Icons.Default.People, contentDescription = null) },
                    label = { Text("Аккаунты (${accounts.size})") }
                )
                NavigationBarItem(
                    selected = activeTab == 1,
                    onClick = { viewModel.setActiveTab(1) },
                    icon = { Icon(Icons.Default.PlayCircle, contentDescription = null) },
                    label = { Text("Задачи") }
                )
                NavigationBarItem(
                    selected = activeTab == 2,
                    onClick = { viewModel.setActiveTab(2) },
                    icon = { Icon(Icons.Default.List, contentDescription = null) },
                    label = { Text("Логи (${logs.size})") }
                )
            }
        }
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues).fillMaxSize()) {
            when (activeTab) {
                0 -> AccountsTab(
                    accounts = accounts,
                    onRunTask = { account, type -> viewModel.runTaskForAccount(account, type) },
                    onStopAccount = { account -> viewModel.stopAccountTask(account.id) },
                    onStopAll = { viewModel.stopAllTasks() },
                    onDelete = { account -> viewModel.deleteAccount(account.id) },
                    onRefresh = { account -> viewModel.reloadAccount(account) },
                    onOpenBalance = { account -> selectedBalanceAccount = account },
                    onOpenAddAccount = { viewModel.setShowAddAccountDialog(true) },
                    onUpdateTasks = { account, r, q, a, m, c, b -> viewModel.updateAccountTasks(account, r, q, a, m, c, b) }
                )
                1 -> TasksTab(
                    accounts = accounts,
                    settings = settings,
                    isRunning = isRunning,
                    onRunAll = { type -> viewModel.runTaskForAllAccounts(type) },
                    onStopAll = { viewModel.stopAllTasks() },
                    onSaveSettings = { newSettings -> viewModel.saveSettings(newSettings) },
                    onOpenPromoDialog = { viewModel.setShowPromoDialog(true) }
                )
                2 -> LogsTab(logs = logs, onClearLogs = { viewModel.clearLogs() })
            }
        }
    }

    // ДИАЛОГ ПРОСМОТРА БАЛАНСА НА САЙТЕ ПО КЛИКУ НА НИКНЕЙМ
    if (selectedBalanceAccount != null) {
        val acc = selectedBalanceAccount!!
        Dialog(
            onDismissRequest = { selectedBalanceAccount = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp
            ) {
                Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(acc.username.ifEmpty { "Аккаунт" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text("https://mangabuff.ru/balance", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                        IconButton(onClick = { selectedBalanceAccount = null }) {
                            Icon(Icons.Default.Close, contentDescription = "Закрыть")
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    AndroidView(
                        factory = { ctx ->
                            val profileName = "mb_${acc.id}"
                            println("PROFILE: BALANCE accountId=${acc.id} profile=$profileName")
                            val wv = WebView(ctx)
                            wv.settings.javaScriptEnabled = true
                            wv.settings.domStorageEnabled = true
                            if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
                                try {
                                    WebViewCompat.setProfile(wv, profileName)
                                    val profileStore = ProfileStore.getInstance()
                                    val profile = profileStore.getProfile(profileName)
                                    val cm = profile?.cookieManager
                                    if (cm != null) {
                                        cm.setAcceptCookie(true)
                                        val cookies = acc.getSafeCookiesJson()
                                        if (cookies.isNotEmpty()) {
                                            for (item in cookies.split(";", ",")) {
                                                if (item.contains("=")) {
                                                    cm.setCookie("https://mangabuff.ru", item.trim())
                                                }
                                            }
                                            cm.flush()
                                        }
                                    }
                                } catch (e: Exception) {
                                    println("PROFILE: COOKIE_PROFILE_FAIL accountId=${acc.id} error=${e.message}")
                                }
                            }
                            wv.loadUrl("https://mangabuff.ru/balance")
                            wv
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }

    // ДИАЛОГ ДОБАВЛЕНИЯ НОВОГО АККАУНТА ЧЕРЕЗ WEBVIEW ВХОД
    if (showAddAccountDialog) {
        val newAccountId = remember { UUID.randomUUID().toString() }

        Dialog(
            onDismissRequest = { viewModel.setShowAddAccountDialog(false) },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp
            ) {
                Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Вход в аккаунт MangaBuff", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        IconButton(onClick = { viewModel.setShowAddAccountDialog(false) }) {
                            Icon(Icons.Default.Close, contentDescription = "Закрыть")
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    AndroidView(
                        factory = { ctx ->
                            val tempProfileName = "mb_temp_login_$newAccountId"
                            println("PROFILE: CREATE tempAccountId=$newAccountId profile=$tempProfileName")
                            val wv = WebView(ctx)
                            wv.settings.javaScriptEnabled = true
                            wv.settings.domStorageEnabled = true
                            if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
                                try {
                                    WebViewCompat.setProfile(wv, tempProfileName)
                                } catch (e: Exception) {}
                            }
                            wv.webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView?, url: String?) {
                                    val script = """
                                        (function() {
                                            if ((window.isAuth === 1 || window.isAuth === true) && window.user && window.user.name) {
                                                var username = window.user.name;
                                                var cookies = document.cookie;
                                                var csrfMeta = document.querySelector('meta[name="csrf-token"]');
                                                var csrf = csrfMeta ? csrfMeta.content : '';
                                                AndroidAddAccountBridge.onAccountCaptured(username, cookies, csrf);
                                            }
                                        })();
                                    """.trimIndent()
                                    view?.evaluateJavascript(script, null)
                                }
                            }
                            wv.addJavascriptInterface(object {
                                @JavascriptInterface
                                fun onAccountCaptured(username: String, cookies: String, csrf: String) {
                                    mainHandler.post {
                                        viewModel.addAccountWithId(newAccountId, username, cookies, csrf)
                                        viewModel.setShowAddAccountDialog(false)
                                        Toast.makeText(ctx, "Аккаунт $username успешно добавлен!", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }, "AndroidAddAccountBridge")
                            wv.loadUrl("https://mangabuff.ru/login")
                            wv
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }

    if (showPromoDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.setShowPromoDialog(false) },
            title = { Text("Применить Промокод") },
            text = {
                OutlinedTextField(
                    value = promoCodeText,
                    onValueChange = { promoCodeText = it },
                    label = { Text("Введите промокод") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    if (promoCodeText.isNotBlank()) {
                        viewModel.setShowPromoDialog(false)
                        Toast.makeText(context, "Промокод отправлен в обработку", Toast.LENGTH_SHORT).show()
                        promoCodeText = ""
                    }
                }) {
                    Text("Применить")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.setShowPromoDialog(false) }) {
                    Text("Отмена")
                }
            }
        )
    }
}

@Composable
fun AccountsTab(
    accounts: List<MangaBuffAccount>,
    onRunTask: (MangaBuffAccount, TaskType) -> Unit,
    onStopAccount: (MangaBuffAccount) -> Unit,
    onStopAll: () -> Unit,
    onDelete: (MangaBuffAccount) -> Unit,
    onRefresh: (MangaBuffAccount) -> Unit,
    onOpenBalance: (MangaBuffAccount) -> Unit,
    onOpenAddAccount: () -> Unit,
    onUpdateTasks: (MangaBuffAccount, Boolean, Boolean, Boolean, Boolean, Boolean, Boolean) -> Unit
) {
    if (accounts.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable { onOpenAddAccount() },
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Default.PersonAdd,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text("Нет добавленных аккаунтов", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Нажмите здесь или на '+' чтобы войти в аккаунт MangaBuff",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(accounts, key = { it.id }) { account ->
                AccountCard(
                    account = account,
                    onRunTask = { type -> onRunTask(account, type) },
                    onStopAccount = { onStopAccount(account) },
                    onDelete = { onDelete(account) },
                    onRefresh = { onRefresh(account) },
                    onOpenBalance = { onOpenBalance(account) },
                    onUpdateTasks = { r, q, a, m, c, b -> onUpdateTasks(account, r, q, a, m, c, b) }
                )
            }
        }
    }
}

@Composable
fun AccountCard(
    account: MangaBuffAccount,
    onRunTask: (TaskType) -> Unit,
    onStopAccount: () -> Unit,
    onDelete: () -> Unit,
    onRefresh: () -> Unit,
    onOpenBalance: () -> Unit,
    onUpdateTasks: (Boolean, Boolean, Boolean, Boolean, Boolean, Boolean) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // ВЕРХНЯЯ ПАНЕЛЬ СТАТИСТИКИ АКТИВНОСТИ АККАУНТА (💎 578     🃏 1/10     📖 13/75     💬 0/13)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "💎 ${account.diamonds}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "🃏 ${account.cardDrop}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "📖 ${account.chapterProgress}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "💬 ${account.commentProgress}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            HorizontalDivider(
                modifier = Modifier.padding(bottom = 12.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )

            // ОСНОВНОЙ РЯД АККАУНТА (Аватар, Имя, Статус и Кнопки управления)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(
                                if (account.isRunning) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.secondaryContainer
                            )
                            .clickable { onOpenBalance() },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = account.username.ifEmpty { "U" }.take(1).uppercase(),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (account.isRunning) MaterialTheme.colorScheme.onPrimaryContainer
                                    else MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onOpenBalance() }
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = account.username.ifEmpty { "Пользователь" },
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = "Открыть баланс",
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (account.isRunning) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(12.dp).padding(end = 4.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Text(
                                text = account.getSafeStatusMessage(),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (account.isRunning) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.outline,
                                fontWeight = if (account.isRunning) FontWeight.Medium else FontWeight.Normal
                            )
                        }
                    }
                }
                Row {
                    if (account.isRunning) {
                        IconButton(onClick = onStopAccount) {
                            Icon(Icons.Default.Stop, contentDescription = "Стоп", tint = MaterialTheme.colorScheme.error)
                        }
                    } else {
                        IconButton(onClick = { onRunTask(TaskType.ALL) }) {
                            Icon(
                                Icons.Default.PlayArrow,
                                contentDescription = "Запустить все",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "Обновить и на главную", tint = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, contentDescription = "Удалить", tint = MaterialTheme.colorScheme.error)
                    }
                    IconButton(onClick = { expanded = !expanded }) {
                        Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = "Настройки")
                    }
                }
            }

            if (account.isRunning) {
                Spacer(modifier = Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { account.taskProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 12.dp)) {
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Автоматические задачи:", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)

                    TaskSwitchRow("🃏 Карточные бои", account.battleEnabled) { b ->
                        onUpdateTasks(account.readerEnabled, account.quizEnabled, account.advEnabled, account.mineEnabled, account.commentEnabled, b)
                    }
                    TaskSwitchRow("Квиз / Викторина", account.quizEnabled) { q ->
                        onUpdateTasks(account.readerEnabled, q, account.advEnabled, account.mineEnabled, account.commentEnabled, account.battleEnabled)
                    }
                    TaskSwitchRow("Просмотр рекламы", account.advEnabled) { a ->
                        onUpdateTasks(account.readerEnabled, account.quizEnabled, a, account.mineEnabled, account.commentEnabled, account.battleEnabled)
                    }
                    TaskSwitchRow("Шахта (Авто-тапы)", account.mineEnabled) { m ->
                        onUpdateTasks(account.readerEnabled, account.quizEnabled, account.advEnabled, m, account.commentEnabled, account.battleEnabled)
                    }
                    TaskSwitchRow("Ежедневное чтение", account.readerEnabled) { r ->
                        onUpdateTasks(r, account.quizEnabled, account.advEnabled, account.mineEnabled, account.commentEnabled, account.battleEnabled)
                    }
                    TaskSwitchRow("Комментарии", account.commentEnabled) { c ->
                        onUpdateTasks(account.readerEnabled, account.quizEnabled, account.advEnabled, account.mineEnabled, c, account.battleEnabled)
                    }
                }
            }
        }
    }
}

@Composable
fun TaskSwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
fun TasksTab(
    accounts: List<MangaBuffAccount>,
    settings: GlobalSettings,
    isRunning: Boolean,
    onRunAll: (TaskType) -> Unit,
    onStopAll: () -> Unit,
    onSaveSettings: (GlobalSettings) -> Unit,
    onOpenPromoDialog: () -> Unit
) {
    var quizMax by remember { mutableStateOf(settings.quizMaxClicks.toString()) }
    var adsCount by remember { mutableStateOf(settings.adsCount.toString()) }
    var readerChapters by remember { mutableStateOf(settings.readerChapters.toString()) }
    var battleTarget by remember { mutableStateOf(settings.battleTargetCount.toString()) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Запуск задач на ВСЕХ аккаунтах (${accounts.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(12.dp))

                    if (isRunning) {
                        Button(
                            onClick = onStopAll,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("ОСТАНОВИТЬ ВСЕ ЗАДАЧИ", fontWeight = FontWeight.Bold)
                        }
                    } else {
                        Button(
                            onClick = { onRunAll(TaskType.ALL) },
                            enabled = accounts.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.PlayCircle, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("ЗАПУСТИТЬ ВСЕ ЗАДАЧИ")
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { onRunAll(TaskType.BATTLE) },
                            enabled = !isRunning && accounts.isNotEmpty(),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Бои")
                        }
                        OutlinedButton(
                            onClick = { onRunAll(TaskType.QUIZ) },
                            enabled = !isRunning && accounts.isNotEmpty(),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Квиз")
                        }
                    }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { onRunAll(TaskType.ADS) },
                            enabled = !isRunning && accounts.isNotEmpty(),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Реклама")
                        }
                        OutlinedButton(
                            onClick = { onRunAll(TaskType.MINE) },
                            enabled = !isRunning && accounts.isNotEmpty(),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Шахта")
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedButton(
                        onClick = { onRunAll(TaskType.READER) },
                        enabled = !isRunning && accounts.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Чтение")
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedButton(
                        onClick = onOpenPromoDialog,
                        enabled = !isRunning && accounts.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.ConfirmationNumber, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Активировать Промокод")
                    }
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Параметры выполнения", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Примечание: Количество ударов в Шахте считывается автоматически с сайта (.main-mine__game-hits-left)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = battleTarget,
                        onValueChange = { battleTarget = it },
                        label = { Text("Бои: Цель завершенных боев") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = quizMax,
                        onValueChange = { quizMax = it },
                        label = { Text("Квиз: Макс вопросов") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = adsCount,
                        onValueChange = { adsCount = it },
                        label = { Text("Реклама: Количество просмотров") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = readerChapters,
                        onValueChange = { readerChapters = it },
                        label = { Text("Чтение: Количество глав") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = {
                            onSaveSettings(
                                settings.copy(
                                    battleTargetCount = battleTarget.toIntOrNull() ?: settings.battleTargetCount,
                                    quizMaxClicks = quizMax.toIntOrNull() ?: settings.quizMaxClicks,
                                    adsCount = adsCount.toIntOrNull() ?: settings.adsCount,
                                    readerChapters = readerChapters.toIntOrNull() ?: settings.readerChapters
                                )
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Сохранить настройки")
                    }
                }
            }
        }
    }
}

@Composable
fun LogsTab(logs: List<LogEntry>, onClearLogs: () -> Unit, onSkipManga: () -> Unit = {}) {
    val dateFormat = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()) }
    val context = LocalContext.current
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()

    val isAtBottom = remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val totalItems = layoutInfo.totalItemsCount
            if (totalItems == 0) true
            else {
                val lastVisibleItemIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                lastVisibleItemIndex >= totalItems - 2
            }
        }
    }

    LaunchedEffect(logs.size) {
        if (isAtBottom.value && logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.size - 1)
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Логи (${logs.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onSkipManga,
                    enabled = logs.any { it.message.contains("CURRENT_MANGA") || it.message.contains("READER:") }
                ) {
                    Icon(Icons.Default.SkipNext, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Скипнуть мангу")
                }
                OutlinedButton(
                    onClick = onClearLogs,
                    enabled = logs.isNotEmpty()
                ) {
                    Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Очистить")
                }
                OutlinedButton(
                    onClick = {
                        val fullText = logs.joinToString("\n") { log ->
                            val timeStr = dateFormat.format(Date(log.timestamp))
                            val levelStr = if (log.isError) "ERROR" else "INFO"
                            val accountStr = if (log.username.isNotBlank()) log.username else "SYS"
                            val componentStr = if (log.component.isNotBlank()) log.component else "APP"
                            "[$timeStr] [$levelStr] [$accountStr] [$componentStr] ${log.message}"
                        }
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        val clip = android.content.ClipData.newPlainText("MangaBuff Logs", fullText)
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(context, "Лог скопирован", Toast.LENGTH_SHORT).show()
                    },
                    enabled = logs.isNotEmpty()
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Копировать")
                }
            }
        }

        if (logs.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Логи пока пусты", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(logs, key = { it.timestamp.toString() + it.message.hashCode() }) { log ->
                    val timeStr = dateFormat.format(Date(log.timestamp))
                    val levelStr = if (log.isError) "ERROR" else "INFO"
                    val accountStr = if (log.username.isNotBlank()) log.username else "SYS"
                    val componentStr = if (log.component.isNotBlank()) log.component else "APP"
                    Text(
                        text = "[$timeStr] [$levelStr] [$accountStr] [$componentStr] ${log.message}",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                        color = if (log.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}
