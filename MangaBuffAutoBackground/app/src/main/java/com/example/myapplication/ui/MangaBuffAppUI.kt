package com.example.myapplication.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.Brush
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
import kotlinx.coroutines.delay

private const val SUPPORT_PAYMENT_URL = "https://finance.ozon.ru/apps/sbp/ozonbankpay/019df8ad-96b5-7cd8-82aa-e64b36e96b93"
private const val SUPPORT_RECIPIENT = "Алексей Г."
private const val SUPPORT_TELEGRAM_URL = "https://t.me/mangabuffauto"

@SuppressLint("RestrictedApi")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MangaBuffAppUI(
    viewModel: MainViewModel,
    webViewContainer: WebView? = null,
    webViewAccountIds: List<String> = emptyList(),
    selectedWebViewAccountId: String? = null,
    onSelectWebViewAccount: (String) -> Unit = {},
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
    var showSupportDialog by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }
    var showWebViewAccountMenu by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    Scaffold(
        modifier = Modifier.statusBarsPadding(),
        containerColor = Color.Transparent,
        bottomBar = {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
                    .border(
                        BorderStroke(
                            1.dp,
                            Brush.linearGradient(
                                listOf(Color(0xFF43E8FF), Color(0xFFB85CFF))
                            )
                        ),
                        RoundedCornerShape(18.dp)
                    ),
                shape = RoundedCornerShape(18.dp),
                color = Color(0xE6171A24),
                tonalElevation = 0.dp,
                shadowElevation = 8.dp
            ) {
                NavigationBar(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .height(56.dp)
                        .padding(horizontal = 4.dp),
                    containerColor = Color.Transparent,
                    windowInsets = WindowInsets(0, 0, 0, 0)
                ) {
                    val navColors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Color(0xFF6CEBFF),
                        selectedTextColor = Color.White,
                        indicatorColor = Color(0x554A8DFF),
                        unselectedIconColor = Color(0xFFB8C0D5)
                    )
                    NavigationBarItem(
                        selected = activeTab == 0,
                        onClick = { viewModel.setActiveTab(0) },
                        icon = {
                            Icon(
                                Icons.Default.People,
                                contentDescription = "Аккаунты (${accounts.size})"
                            )
                        },
                        label = null,
                        alwaysShowLabel = false,
                        colors = navColors
                    )
                    NavigationBarItem(
                        selected = activeTab == 1,
                        onClick = { viewModel.setActiveTab(1) },
                        icon = { Icon(Icons.Default.PlayCircle, contentDescription = "Задачи") },
                        label = null,
                        alwaysShowLabel = false,
                        colors = navColors
                    )
                    NavigationBarItem(
                        selected = debugWebViewVisible,
                        onClick = {
                            if (webViewAccountIds.size > 1) {
                                showWebViewAccountMenu = true
                            } else if (webViewAccountIds.isNotEmpty()) {
                                viewModel.setDebugWebViewVisible(true)
                            }
                        },
                        enabled = webViewAccountIds.isNotEmpty(),
                        icon = {
                            Box {
                                Icon(Icons.Default.Language, contentDescription = "Открыть WEB")
                                DropdownMenu(
                                    expanded = showWebViewAccountMenu,
                                    onDismissRequest = { showWebViewAccountMenu = false }
                                ) {
                                    Column(
                                        modifier = Modifier.padding(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        webViewAccountIds.forEach { accountId ->
                                            val accountName = accounts.firstOrNull {
                                                it.id == accountId
                                            }?.username.orEmpty()
                                            val initials = accountName
                                                .trim()
                                                .replace(Regex("\\s+"), "")
                                                .take(2)
                                                .uppercase(Locale.getDefault())
                                                .ifEmpty { "?" }
                                            Box(
                                                modifier = Modifier
                                                    .size(42.dp)
                                                    .clip(CircleShape)
                                                    .background(
                                                        if (accountId == selectedWebViewAccountId) {
                                                            Color(0xFF4CAF50)
                                                        } else {
                                                            MaterialTheme.colorScheme.surfaceVariant
                                                        }
                                                    )
                                                    .clickable {
                                                        onSelectWebViewAccount(accountId)
                                                        viewModel.setDebugWebViewVisible(true)
                                                        showWebViewAccountMenu = false
                                                    },
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    initials,
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color.White
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        },
                        label = null,
                        alwaysShowLabel = false,
                        colors = navColors
                    )
                    NavigationBarItem(
                        selected = false,
                        onClick = { showSupportDialog = true },
                        icon = { Icon(Icons.Default.Favorite, contentDescription = "Поддержка") },
                        label = null,
                        alwaysShowLabel = false,
                        colors = navColors
                    )
                    NavigationBarItem(
                        selected = activeTab == 2,
                        onClick = { viewModel.setActiveTab(2) },
                        icon = {
                            Icon(
                                Icons.Default.List,
                                contentDescription = "Логи (${logs.size})"
                            )
                        },
                        label = null,
                        alwaysShowLabel = false,
                        colors = navColors
                    )
                    NavigationBarItem(
                        selected = false,
                        onClick = { viewModel.setShowAddAccountDialog(true) },
                        icon = {
                            Icon(
                                Icons.Default.PersonAdd,
                                contentDescription = "Добавить аккаунт"
                            )
                        },
                        label = null,
                        alwaysShowLabel = false,
                        colors = navColors
                    )
                    NavigationBarItem(
                        selected = false,
                        onClick = { showBackgroundSettings = true },
                        icon = {
                            Icon(
                                Icons.Default.Settings,
                                contentDescription = "Настройки фоновой работы"
                            )
                        },
                        label = null,
                        alwaysShowLabel = false,
                        colors = navColors
                    )
                }
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
                    onUpdateTasks = { account, r, q, a, m, c, d, b -> viewModel.updateAccountTasks(account, r, q, a, m, c, d, b) },
                    onToggleOffline = { account ->
                        viewModel.setAccountOffline(account.id, !account.isOffline)
                    }
                )
                1 -> TasksTab(
                    accounts = accounts,
                    settings = settings,
                    isRunning = isRunning,
                    onStopAll = { viewModel.stopAllTasks() },
                    onSaveSettings = { newSettings -> viewModel.saveSettings(newSettings) },
                    onOpenPromoDialog = { viewModel.setShowPromoDialog(true) },
                    onRunEvents = { viewModel.runTaskForAllAccounts(TaskType.EVENTS) }
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
            onDismiss = { showBackgroundSettings = false },
            onOpenAbout = { showAboutDialog = true }
        )
    }

    if (showSupportDialog) {
        AlertDialog(
            onDismissRequest = { showSupportDialog = false },
            icon = {
                Icon(
                    Icons.Default.Favorite,
                    contentDescription = null
                )
            },
            title = { Text("❤️ Поддержать проект") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Добровольное пожертвование на поддержку и развитие проекта MangaBuff Auto."
                    )
                    Text("Получатель: $SUPPORT_RECIPIENT")
                    Text(
                        "Пожертвование не является оплатой приложения, лицензии, доступа к функциям или услугам и не является обязательным."
                    )
                    Text(
                        "Вы перейдете в свой браузер.",
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        "Перевод выполняется через СБП. Сумму вы выбираете самостоятельно в приложении своего банка.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )

                    OutlinedButton(
                        onClick = {
                            try {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(SUPPORT_TELEGRAM_URL))
                                )
                                showSupportDialog = false
                            } catch (_: Exception) {
                                Toast.makeText(
                                    context,
                                    "Не удалось открыть Telegram",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Telegram · @mangabuffauto", style = MaterialTheme.typography.labelLarge)
                    }

                    Text(
                        "Мы Любим Мангу ❤️",
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        try {
                            context.startActivity(
                                Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse(SUPPORT_PAYMENT_URL)
                                )
                            )
                            showSupportDialog = false
                        } catch (_: Exception) {
                            Toast.makeText(
                                context,
                                "Не удалось открыть страницу СБП",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                ) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Открыть страницу СБП")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSupportDialog = false }) {
                    Text("Закрыть")
                }
            }
        )
    }

    if (showAboutDialog) {
        AlertDialog(
            onDismissRequest = { showAboutDialog = false },
            icon = { Icon(Icons.Default.Info, contentDescription = null) },
            title = { Text("О программе") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "MangaBuffAutoBackground",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "© 2026 2R2R. Все права защищены.",
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "Проприетарная лицензия. Копирование, изменение, публикация, распространение и перепродажа оригинальных материалов 2R2R без предварительного письменного разрешения запрещены."
                    )
                    Text(
                        "Сторонние библиотеки и материалы сохраняют собственные авторские права и условия лицензий.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Лицензия: LICENSE в корне репозитория.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showAboutDialog = false }) {
                    Text("Закрыть")
                }
            }
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
                modifier = Modifier.fillMaxSize(),
                shape = RoundedCornerShape(0.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 0.dp
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    AndroidView(
                        factory = { ctx ->
                            val profileName = "mb_${acc.id}"
                            println("PROFILE: BALANCE accountId=${acc.id} profile=$profileName")
                            val wv = WebView(ctx)

                            // Bind the same long-lived account profile used by the reader BEFORE
                            // configuring the view or navigating, so site widgets see its real session.
                            val profileCookieManager = if (
                                WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)
                            ) {
                                try {
                                    WebViewCompat.setProfile(wv, profileName)
                                    ProfileStore.getInstance()
                                        .getProfile(profileName)
                                        ?.cookieManager
                                        ?.also { it.setAcceptCookie(true) }
                                } catch (e: Exception) {
                                    println("PROFILE: BALANCE_PROFILE_FAIL accountId=${acc.id} error=${e.message}")
                                    null
                                }
                            } else {
                                null
                            }

                            wv.settings.javaScriptEnabled = true
                            wv.settings.domStorageEnabled = true
                            wv.settings.databaseEnabled = true

                            // Keep the site's native navigation behavior, but repair profile popups
                            // that the balance page positions partly above the WebView viewport.
                            wv.webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    view?.evaluateJavascript(
                                        """
                                        (function() {
                                            if (window.__mbBalancePopupGuard) return;
                                            window.__mbBalancePopupGuard = true;

                                            var popupSelectors = [
                                                '[role="menu"]',
                                                '[role="dialog"]',
                                                '[class*="dropdown"]',
                                                '[class*="popover"]',
                                                '[class*="profile-menu"]',
                                                '[class*="user-menu"]',
                                                '[class*="account-menu"]',
                                                '[class*="popup"]',
                                                '[class*="menu"]'
                                            ].join(',');

                                            function repairPopups() {
                                                try {
                                                    var nodes = document.querySelectorAll(popupSelectors);
                                                    for (var i = 0; i < nodes.length; i++) {
                                                        var el = nodes[i];
                                                        if (!el || el === document.body || el === document.documentElement) continue;
                                                        var cs = window.getComputedStyle(el);
                                                        if (cs.display === 'none' || cs.visibility === 'hidden' ||
                                                            Number(cs.opacity) === 0 || cs.pointerEvents === 'none') continue;

                                                        var r = el.getBoundingClientRect();
                                                        if (r.width < 70 || r.height < 28 ||
                                                            r.width > window.innerWidth * 0.94 ||
                                                            r.height > window.innerHeight * 0.9) continue;

                                                        // Only intervene when a floating menu is actually clipped by an edge.
                                                        var above = r.top < 8;
                                                        var below = r.bottom > window.innerHeight - 8;
                                                        var leftOff = r.left < 4;
                                                        var rightOff = r.right > window.innerWidth - 4;
                                                        if (!above && !below && !leftOff && !rightOff) continue;

                                                        var top = Math.max(8, Math.min(r.top, window.innerHeight - r.height - 8));
                                                        var left = Math.max(4, Math.min(r.left, window.innerWidth - r.width - 4));

                                                        // If an ancestor clips overflow, allow the popup to escape it.
                                                        var parent = el.parentElement;
                                                        var depth = 0;
                                                        while (parent && parent !== document.body && depth < 5) {
                                                            var ps = window.getComputedStyle(parent);
                                                            if (/(hidden|clip)/.test(ps.overflow + ' ' + ps.overflowY + ' ' + ps.overflowX)) {
                                                                parent.style.setProperty('overflow', 'visible', 'important');
                                                                parent.style.setProperty('overflow-y', 'visible', 'important');
                                                                parent.style.setProperty('overflow-x', 'visible', 'important');
                                                            }
                                                            parent = parent.parentElement;
                                                            depth++;
                                                        }

                                                        el.style.setProperty('position', 'fixed', 'important');
                                                        el.style.setProperty('top', top + 'px', 'important');
                                                        el.style.setProperty('left', left + 'px', 'important');
                                                        el.style.setProperty('right', 'auto', 'important');
                                                        el.style.setProperty('bottom', 'auto', 'important');
                                                        el.style.setProperty('transform', 'none', 'important');
                                                        el.style.setProperty('margin', '0', 'important');
                                                        el.style.setProperty('z-index', '2147483647', 'important');
                                                        el.style.setProperty('max-height', (window.innerHeight - 16) + 'px', 'important');
                                                        el.style.setProperty('overflow-y', 'auto', 'important');
                                                    }
                                                } catch (e) {}
                                            }

                                            var repairTimer = null;
                                            function scheduleRepair() {
                                                if (repairTimer) clearTimeout(repairTimer);
                                                // Menus are sometimes inserted after the avatar click animation.
                                                [0, 50, 120, 250, 500, 800].forEach(function(delay) {
                                                    setTimeout(repairPopups, delay);
                                                });
                                            }

                                            document.addEventListener('click', scheduleRepair, true);
                                            document.addEventListener('touchend', scheduleRepair, true);
                                            document.addEventListener('pointerup', scheduleRepair, true);

                                            try {
                                                new MutationObserver(scheduleRepair).observe(document.documentElement, {
                                                    childList: true, subtree: true, attributes: true,
                                                    attributeFilter: ['class', 'aria-expanded', 'aria-hidden']
                                                });
                                            } catch (e) {}

                                            window.addEventListener('resize', scheduleRepair);
                                            window.addEventListener('scroll', scheduleRepair, true);
                                        })();
                                        """.trimIndent(),
                                        null
                                    )
                                }
                            }

                            val balanceUrl = "https://mangabuff.ru/balance"
                            val existingCookies = runCatching {
                                profileCookieManager?.getCookie(balanceUrl)
                            }.getOrNull()

                            if (profileCookieManager == null || !existingCookies.isNullOrBlank()) {
                                // Keep the active profile's current cookies; they may be newer than
                                // the saved account snapshot after a reader session.
                                wv.loadUrl(balanceUrl)
                            } else {
                                // On a fresh profile, seed saved cookies one by one and only then navigate.
                                val savedCookies = acc.getSafeCookiesJson()
                                    .split(';')
                                    .map { it.trim() }
                                    .filter { it.isNotEmpty() && it.indexOf('=') > 0 }

                                if (savedCookies.isEmpty()) {
                                    wv.loadUrl(balanceUrl)
                                } else {
                                    fun setNextCookie(index: Int) {
                                        if (index >= savedCookies.size) {
                                            profileCookieManager.flush()
                                            wv.loadUrl(balanceUrl)
                                            return
                                        }
                                        profileCookieManager.setCookie(
                                            "https://mangabuff.ru",
                                            savedCookies[index]
                                        ) {
                                            setNextCookie(index + 1)
                                        }
                                    }
                                    setNextCookie(0)
                                }
                            }
                            wv
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .statusBarsPadding()
                            .navigationBarsPadding()
                    )
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .statusBarsPadding()
                            .padding(8.dp)
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(Color(0xDD171A24))
                            .clickable { selectedBalanceAccount = null },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Закрыть окно аккаунта",
                            tint = Color.White
                        )
                    }
                }
            }
        }
    }

    // Add-account flow: observe login state, but only persist the account after the user taps "Готово".
    if (showAddAccountDialog) {
        val newAccountId = remember { UUID.randomUUID().toString() }
        val tempProfileName = remember(newAccountId) { "mb_temp_login_" + newAccountId }
        val loginWebViewState = remember(newAccountId) { mutableStateOf<WebView?>(null) }
        var loginDetectedUsername by remember(newAccountId) { mutableStateOf<String?>(null) }
        var loginStatusText by remember(newAccountId) {
            mutableStateOf("Войдите в MangaBuff. После успешного входа нажмите «Готово».")
        }
        var loginSaveInProgress by remember(newAccountId) { mutableStateOf(false) }

        DisposableEffect(newAccountId) {
            onDispose {
                loginWebViewState.value?.let { wv ->
                    try { wv.removeJavascriptInterface("AndroidAddAccountBridge") } catch (_: Exception) {}
                    try { wv.stopLoading() } catch (_: Exception) {}
                    try { wv.destroy() } catch (_: Exception) {}
                }
                loginWebViewState.value = null
            }
        }

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
                            val wv = WebView(ctx)
                            loginWebViewState.value = wv
                            wv.settings.javaScriptEnabled = true
                            wv.settings.domStorageEnabled = true
                            wv.settings.databaseEnabled = true

                            if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
                                try {
                                    WebViewCompat.setProfile(wv, tempProfileName)
                                    println("PROFILE: CREATE tempAccountId=$newAccountId profile=$tempProfileName")
                                } catch (e: Exception) {
                                    loginStatusText = "Не удалось создать изолированный профиль входа: " + (e.message ?: "ошибка WebView")
                                }
                            } else {
                                loginStatusText = "Этот WebView не поддерживает изолированные профили. Обновите Android System WebView."
                            }

                            wv.webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    val host = try { Uri.parse(url.orEmpty()).host.orEmpty() } catch (_: Exception) { "" }
                                    if (host != "mangabuff.ru" && !host.endsWith(".mangabuff.ru")) return

                                    if (loginDetectedUsername == null && !loginSaveInProgress) {
                                        loginStatusText = "Страница загружена. Проверяю состояние входа…"
                                    }

                                    val script = """
                                        (function() {
                                            try {
                                                if (window.__mbAddAccountPoll) {
                                                    clearInterval(window.__mbAddAccountPoll);
                                                    window.__mbAddAccountPoll = null;
                                                }
                                                var lastSignature = '';
                                                function pollLoginState() {
                                                    var authenticated =
                                                        (window.isAuth === 1 || window.isAuth === true) &&
                                                        window.user && typeof window.user.name === 'string' &&
                                                        window.user.name.trim().length > 0;
                                                    var username = authenticated ? window.user.name.trim() : '';
                                                    var signature = authenticated ? ('AUTH:' + username) : ('WAIT:' + location.pathname);
                                                    if (signature !== lastSignature) {
                                                        lastSignature = signature;
                                                        AndroidAddAccountBridge.onLoginStatus(username, authenticated, location.href);
                                                    }
                                                }
                                                pollLoginState();
                                                window.__mbAddAccountPoll = setInterval(pollLoginState, 750);
                                            } catch (e) {
                                                try { AndroidAddAccountBridge.onLoginStatus('', false, 'POLL_ERROR'); } catch (ignored) {}
                                            }
                                        })();
                                    """.trimIndent()
                                    view?.evaluateJavascript(script, null)
                                }
                            }

                            wv.addJavascriptInterface(object {
                                @JavascriptInterface
                                fun onLoginStatus(username: String, authenticated: Boolean, url: String) {
                                    mainHandler.post {
                                        if (loginSaveInProgress) return@post
                                        if (authenticated && username.isNotBlank()) {
                                            loginDetectedUsername = username
                                            loginStatusText = "Вход распознан: " + username + ". Нажмите «Готово», чтобы сохранить аккаунт."
                                        } else if (url == "POLL_ERROR") {
                                            loginStatusText = "Не удалось проверить вход. Оставьте страницу открытой и нажмите «Готово» ещё раз."
                                        } else if (loginDetectedUsername == null) {
                                            loginStatusText = "Ожидаю успешный вход. После возврата на MangaBuff нажмите «Готово»."
                                        }
                                    }
                                }

                                @JavascriptInterface
                                fun onAccountReady(username: String, jsCookies: String, csrf: String) {
                                    mainHandler.post {
                                        if (username.isBlank()) {
                                            loginSaveInProgress = false
                                            loginStatusText = "Вход ещё не распознан. Завершите авторизацию и попробуйте «Готово» снова."
                                            return@post
                                        }

                                        val profileCookieManager = try {
                                            if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
                                                ProfileStore.getInstance().getProfile(tempProfileName)?.cookieManager
                                            } else {
                                                null
                                            }
                                        } catch (_: Exception) {
                                            null
                                        }

                                        if (profileCookieManager == null) {
                                            loginSaveInProgress = false
                                            loginStatusText = "Не удалось получить cookies из изолированного профиля. Закройте вход и попробуйте снова."
                                            return@post
                                        }

                                        val profileCookies = try {
                                            profileCookieManager.setAcceptCookie(true)
                                            profileCookieManager.flush()
                                            profileCookieManager.getCookie("https://mangabuff.ru").orEmpty()
                                        } catch (_: Exception) {
                                            ""
                                        }

                                        // Profile CookieManager includes HttpOnly session cookies; merge any
                                        // JS-visible cookies by name without logging their values.
                                        val mergedCookies = linkedMapOf<String, String>()
                                        listOf(profileCookies, jsCookies).forEach { raw ->
                                            raw.split(";").forEach { item ->
                                                val pair = item.trim()
                                                val separator = pair.indexOf('=')
                                                if (separator > 0) {
                                                    val name = pair.substring(0, separator).trim()
                                                    if (name.isNotEmpty()) mergedCookies[name] = pair
                                                }
                                            }
                                        }
                                        val cookies = mergedCookies.values.joinToString("; ")
                                        if (cookies.isBlank()) {
                                            loginSaveInProgress = false
                                            loginStatusText = "Сессия вошла, но cookies не получены. Не закрывайте страницу; нажмите «Готово» ещё раз."
                                            return@post
                                        }

                                        val userAgent = loginWebViewState.value?.settings?.userAgentString.orEmpty()
                                        viewModel.addAccountWithId(
                                            newAccountId,
                                            username,
                                            cookies,
                                            csrf,
                                            userAgent
                                        )
                                        viewModel.setShowAddAccountDialog(false)
                                        Toast.makeText(ctx, "Аккаунт " + username + " успешно добавлен!", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }, "AndroidAddAccountBridge")

                            wv.loadUrl("https://mangabuff.ru/login")
                            wv
                        },
                        modifier = Modifier.weight(1f).fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        loginStatusText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (loginDetectedUsername != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = {
                                val wv = loginWebViewState.value
                                if (wv != null) {
                                    loginDetectedUsername = null
                                    loginSaveInProgress = false
                                    loginStatusText = "Повторно проверяю страницу входа…"
                                    wv.evaluateJavascript(
                                        "(function(){try{if(window.__mbAddAccountPoll)clearInterval(window.__mbAddAccountPoll);window.__mbAddAccountPoll=null;}catch(e){} return true;})();",
                                        null
                                    )
                                    wv.reload()
                                }
                            },
                            enabled = loginWebViewState.value != null && !loginSaveInProgress
                        ) {
                            Text("Проверить снова")
                        }
                        Spacer(Modifier.weight(1f))
                        Button(
                            onClick = {
                                val wv = loginWebViewState.value
                                if (wv == null) {
                                    loginStatusText = "Страница входа ещё загружается."
                                    return@Button
                                }
                                loginSaveInProgress = true
                                loginStatusText = "Проверяю авторизацию и сохраняю сессию…"
                                wv.evaluateJavascript(
                                    """
                                    (function() {
                                        try {
                                            var authenticated =
                                                (window.isAuth === 1 || window.isAuth === true) &&
                                                window.user && typeof window.user.name === 'string' &&
                                                window.user.name.trim().length > 0;
                                            if (!authenticated) {
                                                AndroidAddAccountBridge.onAccountReady('', document.cookie || '', '');
                                                return false;
                                            }
                                            var csrfMeta = document.querySelector('meta[name="csrf-token"]');
                                            AndroidAddAccountBridge.onAccountReady(
                                                window.user.name.trim(),
                                                document.cookie || '',
                                                csrfMeta ? (csrfMeta.content || '') : ''
                                            );
                                            return true;
                                        } catch (e) {
                                            AndroidAddAccountBridge.onAccountReady('', '', '');
                                            return false;
                                        }
                                    })();
                                    """.trimIndent(),
                                    null
                                )
                            },
                            enabled = loginWebViewState.value != null && !loginSaveInProgress
                        ) {
                            Text("Готово")
                        }
                    }
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
                    val code = promoCodeText.trim()
                    if (code.isNotEmpty()) {
                        if (viewModel.runPromoCodeForAllAccounts(code)) {
                            viewModel.setShowPromoDialog(false)
                            Toast.makeText(
                                context,
                                "Задача «Промокод» запущена для аккаунтов",
                                Toast.LENGTH_SHORT
                            ).show()
                            promoCodeText = ""
                        } else {
                            Toast.makeText(
                                context,
                                "Не удалось запустить задачу промокода",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }) {
                    Text("Активировать")
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
    onUpdateTasks: (MangaBuffAccount, Boolean, Boolean, Boolean, Boolean, Boolean, Boolean, Boolean) -> Unit,
    onToggleOffline: (MangaBuffAccount) -> Unit
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
                    battleTarget = battleTarget,
                    onRunTask = { type -> onRunTask(account, type) },
                    onStopAccount = { onStopAccount(account) },
                    onDelete = { onDelete(account) },
                    onRefresh = { onRefresh(account) },
                    onOpenBalance = { onOpenBalance(account) },
                    onChangeManga = { onChangeManga(account) },
                    onUpdateTasks = { r, q, a, m, c, d, b -> onUpdateTasks(account, r, q, a, m, c, d, b) },
                    onToggleOffline = { onToggleOffline(account) }
                )
            }
        }
    }
}

@Composable
fun AccountCard(
    account: MangaBuffAccount,
    battleTarget: Int,
    onRunTask: (TaskType) -> Unit,
    onStopAccount: () -> Unit,
    onDelete: () -> Unit,
    onRefresh: () -> Unit,
    onOpenBalance: () -> Unit,
    onChangeManga: () -> Unit,
    onUpdateTasks: (Boolean, Boolean, Boolean, Boolean, Boolean, Boolean, Boolean) -> Unit,
    onToggleOffline: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var showDeleteConfirmation by remember(account.id) { mutableStateOf(false) }
    val accountInitials = remember(account.username) {
        account.username
            .trim()
            .replace(Regex("\\s+"), "")
            .take(2)
            .uppercase(Locale.getDefault())
            .ifEmpty { "??" }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                BorderStroke(
                    1.dp,
                    Brush.linearGradient(
                        listOf(Color(0xFF43E8FF), Color(0xFFB85CFF))
                    )
                ),
                RoundedCornerShape(18.dp)
            ),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xC91B1E29)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
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
                        color = Color.White
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.Center
                ) {
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
                                Text(
                                    text = "🍬 " + (account.eventCandies?.takeIf { it.isNotBlank() } ?: "—"),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    color = Color.White
                                )
                                Text(text = "💎 " + account.diamonds, fontSize = 10.sp, fontWeight = FontWeight.Medium, maxLines = 1, color = Color.White)
                                Text(text = "🃏 " + account.cardDrop, fontSize = 10.sp, fontWeight = FontWeight.Medium, maxLines = 1, color = Color.White)
                            }
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(text = "📖 " + account.chapterProgress, fontSize = 10.sp, fontWeight = FontWeight.Medium, maxLines = 1, color = Color.White)
                                Text(text = "💬 " + account.commentProgress, fontSize = 10.sp, fontWeight = FontWeight.Medium, maxLines = 1, color = Color.White)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(2.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Start,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = onToggleOffline,
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                            modifier = Modifier.height(30.dp),
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = if (account.isOffline) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.error
                                }
                            )
                        ) {
                            Text(
                                text = if (account.isOffline) "ONLINE" else "OFFLINE",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                        }

                        // 🎲 = сменить мангу: отметить текущую как "Прочитано"
                        // и продолжить обычный каталог с hide_read=1.
                        IconButton(
                            onClick = onChangeManga,
                            enabled = account.isRunning && !account.isOffline,
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
                            enabled = !account.isOffline,
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
                            enabled = !account.isOffline,
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
                            onClick = { showDeleteConfirmation = true },
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
                                modifier = Modifier.size(18.dp),
                                tint = Color.White
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
                    val dailyAds = if (isToday) account.dailyAds else 0
                    val mineOreBalanceLabel = if (account.mineOreBalanceKnown) {
                        "${account.mineOreBalance} 🪨 на аккаунте"
                    } else {
                        "🪨 баланс не обновлён"
                    }
                    val dailyReaderChapters = if (isToday) account.dailyReaderChapters else 0
                    val dailyComments = if (isToday) account.dailyComments else 0
                    val dailyDeckComments = if (isToday) account.dailyDeckComments else 0

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Автоматические задачи",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
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

                    TaskSwitchRow("🃏 Бои", "${dailyBattleAttempts}(боев)/${dailyBattles} победы", account.battleEnabled) { b ->
                        onUpdateTasks(account.readerEnabled, account.towerEnabled, account.advEnabled, account.mineEnabled, account.commentEnabled, account.deckCommentEnabled, b)
                    }
                    TaskSwitchRow("🗼 Башня Разлома", towerRemainingLabel(account), account.towerEnabled) { tower ->
                        onUpdateTasks(account.readerEnabled, tower, account.advEnabled, account.mineEnabled, account.commentEnabled, account.deckCommentEnabled, account.battleEnabled)
                    }
                    TaskSwitchRow("📺 Просмотр рекламы", "${dailyAds}/3", account.advEnabled) { a ->
                        onUpdateTasks(account.readerEnabled, account.towerEnabled, a, account.mineEnabled, account.commentEnabled, account.deckCommentEnabled, account.battleEnabled)
                    }
                    TaskSwitchRow("⛏️ Шахта", mineOreBalanceLabel, account.mineEnabled) { m ->
                        onUpdateTasks(account.readerEnabled, account.towerEnabled, account.advEnabled, m, account.commentEnabled, account.deckCommentEnabled, account.battleEnabled)
                    }
                    TaskSwitchRow("📖 Чтение", "${dailyReaderChapters} глав", account.readerEnabled) { r ->
                        onUpdateTasks(r, account.towerEnabled, account.advEnabled, account.mineEnabled, account.commentEnabled, account.deckCommentEnabled, account.battleEnabled)
                    }
                    TaskSwitchRow(
                        "💬 Комментарии",
                        account.commentProgress.takeIf {
                            Regex("""^\d+\s*/\s*\d+$""").matches(it.trim())
                        } ?: "${dailyComments}/13",
                        account.commentEnabled
                    ) { c ->
                        onUpdateTasks(
                            account.readerEnabled,
                            account.towerEnabled,
                            account.advEnabled,
                            account.mineEnabled,
                            c,
                            account.deckCommentEnabled,
                            account.battleEnabled
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 2.dp, top = 2.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "$dailyDeckComments/8",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                        Spacer(Modifier.width(8.dp))
                        FilterChip(
                            selected = account.deckCommentEnabled,
                            onClick = {
                                onUpdateTasks(
                                    account.readerEnabled,
                                    account.towerEnabled,
                                    account.advEnabled,
                                    account.mineEnabled,
                                    account.commentEnabled,
                                    !account.deckCommentEnabled,
                                    account.battleEnabled
                                )
                            },
                            label = {
                                Text(
                                    if (account.deckCommentEnabled) "Колоды: ВКЛ" else "Колоды: ВЫКЛ",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            },
                            modifier = Modifier.height(30.dp)
                        )
                    }
                }
            }
        }
    }

    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text("Удалить аккаунт?") },
            text = {
                Text(
                    "Этот аккаунт будет удалён из приложения. Продолжить?"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirmation = false
                        onDelete()
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Удалить")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) {
                    Text("Отмена")
                }
            }
        )
    }
}

@Composable
private fun towerRemainingLabel(account: MangaBuffAccount): String {
    val endsAtMillis = account.towerExpeditionEndsAtMillis
    var nowMillis by remember(account.id, endsAtMillis) { mutableStateOf(System.currentTimeMillis()) }

    LaunchedEffect(account.id, endsAtMillis) {
        if (endsAtMillis <= 0L) return@LaunchedEffect
        while (System.currentTimeMillis() < endsAtMillis) {
            nowMillis = System.currentTimeMillis()
            delay(1_000L)
        }
        nowMillis = System.currentTimeMillis()
    }

    if (!account.towerExpeditionStateKnown) return "Время появится после проверки"
    if (endsAtMillis <= 0L) return "Нет активной экспедиции"

    val remaining = ((endsAtMillis - nowMillis).coerceAtLeast(0L) / 1_000L)
    if (remaining <= 0L) return "Время вышло · проверь награды"
    val hours = remaining / 3_600L
    val minutes = (remaining % 3_600L) / 60L
    val seconds = remaining % 60L
    return "Осталось %02d:%02d:%02d".format(hours, minutes, seconds)
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
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Color.White, maxLines = 1, modifier = Modifier.weight(1f))
        Text(
            dailyStat,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
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
    onStopAll: () -> Unit,
    onSaveSettings: (GlobalSettings) -> Unit,
    onOpenPromoDialog: () -> Unit,
    onRunEvents: () -> Unit
) {

    var readerChapters by remember { mutableStateOf(settings.readerChapters.toString()) }
    var battleTarget by remember { mutableStateOf(settings.battleTargetCount.toString()) }
    var showTowerUpgrades by remember { mutableStateOf(false) }

    LaunchedEffect(settings.readerChapters, settings.battleTargetCount) {
        readerChapters = settings.readerChapters.toString()
        battleTarget = settings.battleTargetCount.toString()
    }

    val actionEnabled = !isRunning && accounts.isNotEmpty()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
        contentPadding = PaddingValues(bottom = 4.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Column(modifier = Modifier.padding(5.dp)) {
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

                        FilledTonalButton(
                            onClick = {
                                onSaveSettings(
                                    settings.copy(keepScreenOn = !settings.keepScreenOn)
                                )
                            },
                            contentPadding = PaddingValues(horizontal = 7.dp, vertical = 0.dp),
                            modifier = Modifier.height(32.dp),
                            shape = RoundedCornerShape(9.dp)
                        ) {
                            Icon(
                                if (settings.keepScreenOn) Icons.Default.LightMode else Icons.Default.Brightness4,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(3.dp))
                            Text(
                                if (settings.keepScreenOn) "Экран ВКЛ" else "Экран ВЫКЛ",
                                fontSize = 10.sp,
                                maxLines = 1
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

                    Spacer(Modifier.height(2.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Row(
                            modifier = Modifier.weight(0.9f),
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TaskActionButton(
                                "⛏️ Шахта",
                                {},
                                false,
                                Modifier.weight(1f)
                            )

                            Box(
                                modifier = Modifier
                                    .size(32.dp)
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

                        Row(
                            modifier = Modifier.weight(1.25f),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TaskActionButton(
                                "📖 Чтение",
                                {},
                                false,
                                Modifier.weight(1f)
                            )

                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (settings.readerSiteAutoscroll) {
                                            Color(0xFF4CAF50)
                                        } else {
                                            MaterialTheme.colorScheme.surfaceVariant
                                        }
                                    )
                                    .clickable(enabled = actionEnabled) {
                                        onSaveSettings(
                                            settings.copy(
                                                readerSiteAutoscroll = !settings.readerSiteAutoscroll
                                            )
                                        )
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.PlayArrow,
                                    contentDescription = if (settings.readerSiteAutoscroll) {
                                        "Автоскролл сайта включен"
                                    } else {
                                        "Автоскролл сайта выключен"
                                    },
                                    tint = if (settings.readerSiteAutoscroll) {
                                        Color.White
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier.size(21.dp)
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (settings.readerSiteAutoscrollHalfSpeed) {
                                            Color(0xFF4CAF50)
                                        } else {
                                            MaterialTheme.colorScheme.surfaceVariant
                                        }
                                    )
                                    .clickable(enabled = actionEnabled) {
                                        onSaveSettings(
                                            settings.copy(
                                                readerSiteAutoscrollHalfSpeed = !settings.readerSiteAutoscrollHalfSpeed
                                            )
                                        )
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "−Х2",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (settings.readerSiteAutoscrollHalfSpeed) {
                                        Color.White
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    maxLines = 1
                                )
                            }
                        }
                        TaskActionButton(
                            "🎟️ Промокод",
                            onOpenPromoDialog,
                            actionEnabled,
                            Modifier.weight(0.85f),
                            compactText = true
                        )
                    }
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                TaskActionButton(
                    "🎁 Награды",
                    onRunEvents,
                    actionEnabled,
                    Modifier.weight(1f)
                )
                TaskActionButton(
                    if (settings.eventsEnabled) "🎃 Events: ВКЛ" else "🎃 Events: ВЫКЛ",
                    { onSaveSettings(settings.copy(eventsEnabled = !settings.eventsEnabled)) },
                    accounts.isNotEmpty(),
                    Modifier.weight(1f)
                )
            }
        }

        // A dedicated, compact card keeps Tower options separated from the general task controls.
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 5.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { showTowerUpgrades = !showTowerUpgrades }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Shield,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(19.dp)
                        )
                        Spacer(Modifier.width(7.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Башня Разлома · усиления",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "Один навык — «На все»; несколько — +1 по очереди",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                        Icon(
                            if (showTowerUpgrades) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = if (showTowerUpgrades) {
                                "Свернуть настройки Башни Разлома"
                            } else {
                                "Открыть настройки Башни Разлома"
                            },
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    AnimatedVisibility(visible = showTowerUpgrades) {
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    val selectedUpgradeChipColors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFF2E7D32),
                        selectedLabelColor = Color.White,
                        selectedLeadingIconColor = Color.White,
                        selectedTrailingIconColor = Color.White
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        FilterChip(
                            selected = settings.towerUpgradeAssault,
                            colors = selectedUpgradeChipColors,
                            onClick = { onSaveSettings(settings.copy(towerUpgradeAssault = !settings.towerUpgradeAssault)) },
                            enabled = !isRunning,
                            modifier = Modifier.weight(1f),
                            label = { Text("Натиск", maxLines = 1) }
                        )
                        FilterChip(
                            selected = settings.towerUpgradeFortitude,
                            colors = selectedUpgradeChipColors,
                            onClick = { onSaveSettings(settings.copy(towerUpgradeFortitude = !settings.towerUpgradeFortitude)) },
                            enabled = !isRunning,
                            modifier = Modifier.weight(1f),
                            label = { Text("Оплот", maxLines = 1) }
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        FilterChip(
                            selected = settings.towerUpgradeHunt,
                            colors = selectedUpgradeChipColors,
                            onClick = { onSaveSettings(settings.copy(towerUpgradeHunt = !settings.towerUpgradeHunt)) },
                            enabled = !isRunning,
                            modifier = Modifier.weight(1f),
                            label = { Text("Знание Хранителей", maxLines = 1) }
                        )
                        FilterChip(
                            selected = settings.towerUpgradeMentorship,
                            colors = selectedUpgradeChipColors,
                            onClick = { onSaveSettings(settings.copy(towerUpgradeMentorship = !settings.towerUpgradeMentorship)) },
                            enabled = !isRunning,
                            modifier = Modifier.weight(1f),
                            label = { Text("Наставление", maxLines = 1) }
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        FilterChip(
                            selected = settings.towerUpgradeSupply,
                            colors = selectedUpgradeChipColors,
                            onClick = { onSaveSettings(settings.copy(towerUpgradeSupply = !settings.towerUpgradeSupply)) },
                            enabled = !isRunning,
                            modifier = Modifier.weight(1f),
                            label = { Text("Снабжение", maxLines = 1) }
                        )
                        Spacer(Modifier.weight(1f))
                    }

                    Text(
                        "При нескольких выбранных навыках уровни покупаются по очереди. " +
                            "Отключённые навыки пропускаются.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                        }
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
                Column(modifier = Modifier.padding(7.dp)) {
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

                    Spacer(Modifier.height(4.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        CompactNumberField(
                            battleTarget,
                            { battleTarget = it },
                            "⚔️ Бои",
                            Modifier.weight(1f)
                        )
                        CompactNumberField(
                            readerChapters,
                            { readerChapters = it },
                            "📖 Главы 1–100",
                            Modifier.weight(1f)
                        )
                    }

                    Spacer(Modifier.height(3.dp))

                    OutlinedButton(
                        onClick = {
                            val selectedReaderChapters = (
                                readerChapters.toIntOrNull() ?: settings.readerChapters
                            ).coerceIn(1, GlobalSettings.MAX_READER_CHAPTERS)
                            readerChapters = selectedReaderChapters.toString()
                            onSaveSettings(
                                settings.copy(
                                    battleTargetCount = battleTarget.toIntOrNull() ?: settings.battleTargetCount,
                                    readerChapters = selectedReaderChapters
                                )
                            )
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        modifier = Modifier.fillMaxWidth().height(29.dp),
                        shape = RoundedCornerShape(9.dp)
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Сохранить")
                    }

                    Spacer(Modifier.height(2.dp))

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
    modifier: Modifier = Modifier,
    compactText: Boolean = false
) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 5.dp, vertical = 4.dp),
        modifier = modifier.height(32.dp),
        shape = RoundedCornerShape(10.dp)
    ) {
        Text(
            text,
            style = if (compactText) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelLarge,
            maxLines = 1
        )
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
        modifier = modifier.height(60.dp),
        textStyle = MaterialTheme.typography.bodyMedium,
        shape = RoundedCornerShape(10.dp)
    )
}

@Composable
fun LogsTab(
    logs: List<LogEntry>,
    onClearLogs: () -> Unit
) {
    val visibleLogs = logs

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
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            Text(
                "Логи (" + visibleLogs.size + ")",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
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
                            "[" + timeStr + "] [" + levelStr + "] [" + accountStr + "] [" + componentStr + "] " + log.message
                        }
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        val clip = android.content.ClipData.newPlainText(
                            "MangaBuff Logs",
                            fullText
                        )
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
                Text(
                    "Логов пока нет",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(visibleLogs) { log ->
                    val timeStr = dateFormat.format(Date(log.timestamp))
                    val levelStr = if (log.isError) "ERROR" else "INFO"
                    val accountStr = if (log.username.isNotBlank()) log.username else "SYS"
                    val componentStr = if (log.component.isNotBlank()) log.component else "APP"
                    Text(
                        text = "[" + timeStr + "] [" + levelStr + "] [" + accountStr + "] [" + componentStr + "] " + log.message,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                        color = if (log.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}