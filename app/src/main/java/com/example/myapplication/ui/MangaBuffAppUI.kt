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
import com.example.myapplication.data.currentStatsDay
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
    val debugWebViewVisible by viewModel.debugWebViewVisible.collectAsState()
    val showAddAccountDialog by viewModel.showAddAccountDialog.collectAsState()
    val showPromoDialog by viewModel.showPromoDialog.collectAsState()

    var selectedBalanceAccount by remember { mutableStateOf<MangaBuffAccount?>(null) }
    var promoCodeText by remember { mutableStateOf("") }
    var showBackgroundSettings by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("MangaBuff Automation", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = { showBackgroundSettings = true }) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = "Фоновая работа"
                        )
                    }
                    if (webViewContainer != null) {
                        TextButton(
                            onClick = { viewModel.setDebugWebViewVisible(!debugWebViewVisible) }
                        ) {
                            Text(if (debugWebViewVisible) "UI" else "WEB")
                        }
                    }
                },
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
                    battleTarget = settings.battleTargetCount,
                    onRunTask = { account, type -> viewModel.runTaskForAccount(account, type) },
                    onStopAccount = { account -> viewModel.stopAccountTask(account.id) },
                    onStopAll = { viewModel.stopAllTasks() },
                    onDelete = { account -> viewModel.deleteAccount(account.id) },
                    onRefresh = { account -> viewModel.reloadAccount(account) },
                    onOpenBalance = { account -> selectedBalanceAccount = account },
                    onOpenAddAccount = { viewModel.setShowAddAccountDialog(true) },
                    onChangeManga = { account -> viewModel.changeCurrentManga(account.id) },
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
                2 -> LogsTab(
                    logs = logs,
                    onClearLogs = { viewModel.clearLogs() }
                )
            }
        }
    }

    if (showBackgroundSettings) {
        BackgroundSettingsDialog(
            settings = settings,
            onSaveSettings = { viewModel.saveSettings(it) },
            onDismiss = { showBackgroundSettings = false }
        )
    }

    // ДИАЛОГ ПРОСМОТРА БАЛАНСА НА САЙТЕ ПО КЛИКУ НА АВАТАР
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

                    if (acc.isRunning) {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                "Аккаунт выполняет задачу в фоне.",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "Чтобы не создавать второй WebView того же профиля и не вмешиваться в текущую автоматизацию, просмотр /balance доступен после остановки задачи.",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    } else {
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
    battleTarget: Int,
    onRunTask: (MangaBuffAccount, TaskType) -> Unit,
    onStopAccount: (MangaBuffAccount) -> Unit,
    onStopAll: () -> Unit,
    onDelete: (MangaBuffAccount) -> Unit,
    onRefresh: (MangaBuffAccount) -> Unit,
    onOpenBalance: (MangaBuffAccount) -> Unit,
    onOpenAddAccount: () -> Unit,
    onChangeManga: (MangaBuffAccount) -> Unit,
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
                    onChangeManga = { onChangeManga(account) },
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
    onChangeManga: () -> Unit,
    onUpdateTasks: (Boolean, Boolean, Boolean, Boolean, Boolean, Boolean) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val accountInitials = remember(account.username) {
        account.username
            .trim()
            .replace(Regex("\\s+"), "")
            .take(2)
            .uppercase(Locale.getDefault())
            .ifEmpty { "??" }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(
                            if (account.isRunning) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.secondaryContainer
                            }
                        )
                        .clickable { onOpenBalance() },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = accountInitials,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (account.isRunning) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        }
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${account.getSafeStatusMessage()}",
                            fontSize = 11.sp,
                            color = if (account.isRunning) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outline
                            },
                            fontWeight = if (account.isRunning) {
                                FontWeight.Medium
                            } else {
                                FontWeight.Normal
                            },
                            maxLines = 1,
                            modifier = Modifier.weight(1f)
                        )

                        Spacer(modifier = Modifier.width(6.dp))

                        Column(
                            modifier = Modifier
                                .wrapContentWidth()
                                .align(Alignment.CenterVertically),
                            horizontalAlignment = Alignment.End,
                            verticalArrangement = Arrangement.spacedBy(1.dp)
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(text = "💎 " + account.diamonds, fontSize = 10.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                                Text(text = "🃏 " + account.cardDrop, fontSize = 10.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                            }
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(text = "📖 " + account.chapterProgress, fontSize = 10.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                                Text(text = "💬 " + account.commentProgress, fontSize = 10.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(2.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Start,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 🎲 = сменить мангу: отметить текущую как "Прочитано"
                        // и продолжить обычный каталог с hide_read=1.
                        IconButton(
                            onClick = onChangeManga,
                            enabled = account.isRunning,
                            modifier = Modifier.size(30.dp)
                        ) {
                            Text(
                                text = "🎲",
                                fontSize = 15.sp
                            )
                        }

                        IconButton(
                            onClick = {
                                if (account.isRunning) onStopAccount()
                                else onRunTask(TaskType.ALL)
                            },
                            modifier = Modifier.size(30.dp)
                        ) {
                            Icon(
                                if (account.isRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                                contentDescription = if (account.isRunning) "Стоп" else "Запустить все",
                                modifier = Modifier.size(18.dp),
                                tint = if (account.isRunning) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.primary
                                }
                            )
                        }

                        IconButton(
                            onClick = onRefresh,
                            modifier = Modifier.size(30.dp)
                        ) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = "Обновить",
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }

                        IconButton(
                            onClick = onDelete,
                            modifier = Modifier.size(30.dp)
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Удалить",
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }

                        IconButton(
                            onClick = { expanded = !expanded },
                            modifier = Modifier.size(30.dp)
                        ) {
                            Icon(
                                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = "Настройки",
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }

            if (account.isRunning) {
                Spacer(modifier = Modifier.height(5.dp))
                LinearProgressIndicator(
                    progress = { account.taskProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(6.dp))
                    val today = currentStatsDay()
                    val isToday = account.dailyStatsDay == today
                    val dailyBattles = if (isToday) account.dailyBattles else 0
                    val dailyBattleAttempts = if (isToday) account.dailyBattleAttempts else 0
                    val dailyQuiz = if (isToday) account.dailyQuiz else 0
                    val dailyAds = if (isToday) account.dailyAds else 0
                    val dailyMineOre = if (isToday) account.dailyMineOre else 0
                    val dailyMineDiamonds = if (isToday) account.dailyMineDiamonds else 0
                    val dailyReaderChapters = if (isToday) account.dailyReaderChapters else 0
                    val dailyComments = if (isToday) account.dailyComments else 0

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Автоматические задачи",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            Text(
                                "СЕГОДНЯ",
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }

                    TaskSwitchRow("🃏 Бои", "$dailyBattleAttempts боев / $dailyBattles победы", account.battleEnabled) { b ->
                        onUpdateTasks(account.readerEnabled, account.quizEnabled, account.advEnabled, account.mineEnabled, account.commentEnabled, b)
                    }
                    TaskSwitchRow("🧠 Квиз", "${dailyQuiz}", account.quizEnabled) { q ->
                        onUpdateTasks(account.readerEnabled, q, account.advEnabled, account.mineEnabled, account.commentEnabled, account.battleEnabled)
                    }
                    TaskSwitchRow("📺 Просмотр рекламы", "${dailyAds}/3", account.advEnabled) { a ->
                        onUpdateTasks(account.readerEnabled, account.quizEnabled, a, account.mineEnabled, account.commentEnabled, account.battleEnabled)
                    }
                    TaskSwitchRow("⛏️ Шахта", "${dailyMineOre} 🪨 → 💎${dailyMineDiamonds}", account.mineEnabled) { m ->
                        onUpdateTasks(account.readerEnabled, account.quizEnabled, account.advEnabled, m, account.commentEnabled, account.battleEnabled)
                    }
                    TaskSwitchRow("📖 Чтение", "${dailyReaderChapters} глав", account.readerEnabled) { r ->
                        onUpdateTasks(r, account.quizEnabled, account.advEnabled, account.mineEnabled, account.commentEnabled, account.battleEnabled)
                    }
                    TaskSwitchRow("💬 Комментарии", "${dailyComments}", account.commentEnabled) { c ->
                        onUpdateTasks(account.readerEnabled, account.quizEnabled, account.advEnabled, account.mineEnabled, c, account.battleEnabled)
                    }
                }
            }
        }
    }
}

@Composable
fun TaskSwitchRow(
    label: String,
    dailyStat: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(horizontal = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, modifier = Modifier.weight(1f))
        Text(
            dailyStat,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            maxLines = 1
        )
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange, modifier = Modifier.height(32.dp))
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

    val actionEnabled = !isRunning && accounts.isNotEmpty()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
        contentPadding = PaddingValues(bottom = 8.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Column(modifier = Modifier.padding(9.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = RoundedCornerShape(9.dp),
                            color = if (isRunning) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Icon(
                                if (isRunning) Icons.Default.PlayArrow else Icons.Default.AutoAwesome,
                                contentDescription = null,
                                modifier = Modifier.padding(6.dp).size(17.dp),
                                tint = if (isRunning) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Spacer(Modifier.width(8.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Задачи",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                accounts.size.toString() + " аккаунтов • " +
                                    if (isRunning) "выполняются" else "готово",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }

                        if (isRunning) {
                            FilledTonalButton(
                                onClick = onStopAll,
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer,
                                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                                ),
                                contentPadding = PaddingValues(horizontal = 9.dp),
                                modifier = Modifier.height(34.dp),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(15.dp))
                                Spacer(Modifier.width(3.dp))
                                Text("Стоп", fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    Spacer(Modifier.height(7.dp))

                    if (!isRunning) {
                        Button(
                            onClick = { onRunAll(TaskType.ALL) },
                            enabled = accounts.isNotEmpty(),
                            contentPadding = PaddingValues(horizontal = 8.dp),
                            modifier = Modifier.fillMaxWidth().height(38.dp),
                            shape = RoundedCornerShape(11.dp)
                        ) {
                            Icon(Icons.Default.PlayCircle, contentDescription = null, modifier = Modifier.size(17.dp))
                            Spacer(Modifier.width(5.dp))
                            Text("Запустить все", fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.height(6.dp))
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        TaskActionButton("⚔️ Бои", { onRunAll(TaskType.BATTLE) }, actionEnabled, Modifier.weight(1f))
                        TaskActionButton("🧠 Квиз", { onRunAll(TaskType.QUIZ) }, actionEnabled, Modifier.weight(1f))
                        TaskActionButton("📺 Реклама", { onRunAll(TaskType.ADS) }, actionEnabled, Modifier.weight(1f))
                    }

                    Spacer(Modifier.height(5.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TaskActionButton(
                                "⛏️ Шахта",
                                { onRunAll(TaskType.MINE) },
                                actionEnabled,
                                Modifier.weight(1f)
                            )

                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (settings.mineAutoExchange) {
                                            Color(0xFF4CAF50)
                                        } else {
                                            MaterialTheme.colorScheme.surfaceVariant
                                        }
                                    )
                                    .clickable(enabled = actionEnabled) {
                                        onSaveSettings(
                                            settings.copy(
                                                mineAutoExchange = !settings.mineAutoExchange
                                            )
                                        )
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.Sync,
                                    contentDescription = if (settings.mineAutoExchange) {
                                        "Автообмен руды включен"
                                    } else {
                                        "Автообмен руды выключен"
                                    },
                                    tint = if (settings.mineAutoExchange) {
                                        Color.White
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        TaskActionButton(
                            "📖 Чтение",
                            { onRunAll(TaskType.READER) },
                            actionEnabled,
                            Modifier.weight(1f)
                        )
                        TaskActionButton(
                            "🎟️ Промокод",
                            onOpenPromoDialog,
                            actionEnabled,
                            Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Column(modifier = Modifier.padding(9.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Параметры",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "Количество запусков",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Text(
                                "АВТО",
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }

                    Spacer(Modifier.height(7.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        CompactNumberField(
                            battleTarget,
                            { battleTarget = it },
                            "⚔️ Бои",
                            Modifier.weight(1f)
                        )
                        CompactNumberField(
                            quizMax,
                            { quizMax = it },
                            "🧠 Квиз",
                            Modifier.weight(1f)
                        )
                    }

                    Spacer(Modifier.height(5.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        CompactNumberField(
                            adsCount,
                            { adsCount = it },
                            "📺 Реклама",
                            Modifier.weight(1f)
                        )
                        CompactNumberField(
                            readerChapters,
                            { readerChapters = it },
                            "📖 Главы",
                            Modifier.weight(1f)
                        )
                    }

                    Spacer(Modifier.height(7.dp))

                    OutlinedButton(
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
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        modifier = Modifier.fillMaxWidth().height(36.dp),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Сохранить")
                    }

                    Spacer(Modifier.height(3.dp))

                    Text(
                        if (settings.mineAutoExchange) {
                            "⛏️ Шахта • автообмен руды в кристаллы ВКЛ"
                        } else {
                            "⛏️ Шахта • обмен руды в кристаллы вручную"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (settings.mineAutoExchange) {
                            Color(0xFF4CAF50)
                        } else {
                            MaterialTheme.colorScheme.outline
                        },
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
private fun TaskActionButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 5.dp, vertical = 4.dp),
        modifier = modifier.height(38.dp),
        shape = RoundedCornerShape(10.dp)
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

@Composable
private fun CompactNumberField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = { valueText ->
            if (valueText.length <= 5 && valueText.all { it.isDigit() }) onValueChange(valueText)
        },
        label = { Text(label, maxLines = 1) },
        singleLine = true,
        modifier = modifier.height(56.dp),
        textStyle = MaterialTheme.typography.bodyMedium,
        shape = RoundedCornerShape(10.dp)
    )
}

@Composable
fun LogsTab(
    logs: List<LogEntry>,
    onClearLogs: () -> Unit
) {
    val visibleLogs = remember(logs) {
        logs.filter { entry ->
            val m = entry.message
            entry.component == "BG" ||
                m.startsWith("BG:") ||
                m.contains("[BG]") ||
                m.startsWith("HEARTBEAT") ||
                m.startsWith("BACKGROUND_") ||
                m.startsWith("SCROLL_PROGRESS") ||
                m.startsWith("END_CANDIDATE") ||
                m.startsWith("BOTTOM_STABILIZATION") ||
                m.startsWith("SCREEN_OFF") ||
                m.startsWith("SCREEN_ON") ||
                m.startsWith("RENDERER_") ||
                m.contains("WAKELOCK") ||
                m.contains("SERVICE_")
        }
    }
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

    LaunchedEffect(visibleLogs.size) {
        if (isAtBottom.value && visibleLogs.isNotEmpty()) {
            listState.animateScrollToItem(visibleLogs.size - 1)
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Логи (${visibleLogs.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onClearLogs,
                    enabled = visibleLogs.isNotEmpty()
                ) {
                    Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Очистить")
                }
                OutlinedButton(
                    onClick = {
                        val fullText = visibleLogs.joinToString("\n") { log ->
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
                    enabled = visibleLogs.isNotEmpty()
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Копировать")
                }
            }
        }

        if (visibleLogs.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Логи пока пусты", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(visibleLogs, key = { it.timestamp.toString() + it.message.hashCode() }) { log ->
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