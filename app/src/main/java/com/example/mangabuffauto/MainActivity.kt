package com.example.mangabuffauto

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.webkit.WebView
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.example.mangabuffauto.automation.*
import com.example.mangabuffauto.task.*
import kotlinx.coroutines.launch


@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {

    private var engine: AutomationEngine? = null
    private var webView: WebView? = null
    private lateinit var profileManager: MultiProfileManager
    private var showHyperOsGuide by mutableStateOf(false)
    private var showProfileDialog by mutableStateOf(false)
    private lateinit var cardStatisticsController: CardStatisticsController

    // Keep Back inside the WebView whenever browser history exists.
    // This callback is also used by Android's predictive/edge Back gesture.
    // IMPORTANT: never call finish()/onBackPressed() here. If there is no WebView
    // history, we simply consume the gesture so the Activity cannot be closed by
    // an accidental edge swipe.
    private val webViewBackCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            handleWebViewBackGesture()
        }
    }

    private fun handleWebViewBackGesture() {
        val wv = webView ?: AutomationRuntime.webView
        if (wv != null && wv.canGoBack()) {
            Log.i("MangaBuffAuto", "BACK: WEBVIEW_GO_BACK url=${wv.url}")
            AndroidBotLog.log("BACK: WEBVIEW_GO_BACK")
            wv.goBack()
        } else {
            Log.i("MangaBuffAuto", "BACK: NO_WEBVIEW_HISTORY")
            AndroidBotLog.log("BACK: NO_WEBVIEW_HISTORY")
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, webViewBackCallback)
        // Back is always handled by the callback. It only calls WebView.goBack()
        // when history exists and never exits the Activity.
        AutomationRuntime.onBackStateChanged = { _ -> }
        profileManager = MultiProfileManager(applicationContext)
        cardStatisticsController = CardStatisticsController(applicationContext)
        AutomationRuntime.onPageStarted = { view, _ ->
            cardStatisticsController.onPageStarted(view)
        }
        AutomationRuntime.onPageFinished = { view, _ ->
            cardStatisticsController.onPageFinished(view)
        }
        profileManager.ensureProfile(profileManager.activeProfile())
        AutomationRuntime.ensure(applicationContext, profileManager.activeProfile())

        engine = AutomationRuntime.engine?.apply {
            onTaskFinished = { task, result ->
                Log.i("MangaBuffAuto", "TASK FINISHED: ${task.spec.id} -> $result")
                AndroidBotLog.log("TASK FINISHED: ${task.spec.id} -> $result")
                AndroidBotLog.log("[BG] Foreground service remains active until manual STOP")
            }
        }

        setContent {
            val webViewVersion = AutomationRuntime.webViewVersion
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFFBB86FC),
                    background = Color(0xFF121212),
                    surface = Color(0xFF1E1E1E),
                    onBackground = Color.White,
                    onSurface = Color.White
                )
            ) {
                var journal by remember { mutableStateOf(listOf<String>()) }
                var isLogVisible by remember { mutableStateOf(false) }
                var activeProfile by remember { mutableStateOf(profileManager.activeProfile()) }
                var profileVersion by remember { mutableIntStateOf(0) }
                val lazyListState = rememberLazyListState()
                
                val engineState by (engine?.state ?: remember { mutableStateOf(AutomationState.IDLE) })
                val engineStats by (engine?.stats ?: remember { mutableStateOf(MangaBuffStats()) })
                var showStats by remember { mutableStateOf(false) }
                var autoModeEnabled by remember { mutableStateOf(AutomationRuntime.isAutoModeEnabled()) }
                var cardStatsEnabled by remember { mutableStateOf(cardStatisticsController.enabled) }

                val profileNames = remember(profileVersion) { profileManager.profiles() }

                fun addJournal(message: String) {
                    journal = (journal + message).takeLast(300)
                }

                LaunchedEffect(journal.size) {
                    if (journal.isNotEmpty() && isLogVisible) {
                        lazyListState.animateScrollToItem(journal.size - 1)
                    }
                }

                DisposableEffect(isLogVisible) {
                    if (isLogVisible) {
                        AndroidBotLog.setListener { message: String ->
                            runOnUiThread { addJournal(message) }
                        }
                    } else {
                        AndroidBotLog.setListener(null)
                    }
                    onDispose { AndroidBotLog.setListener(null) }
                }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    // Профиль убран из верхней части экрана: там остаётся только WebView.
                    // Управление профилями открывается из нижней панели.
                    bottomBar = {
                        val speed by (engine?.readingSpeed ?: remember { mutableStateOf(1.0f) })
                        CompactBottomBar(
                            onMine = { 
                                startForegroundService()
                                runTask(MineTask) 
                            },
                            onAds = { 
                                startForegroundService()
                                runTask(WatchAdsTask) 
                            },
                            onChat = {
                                startForegroundService()
                                runTask(ChatDiamondTask)
                            },
                            onRead = {
                                Log.i("MangaBuffAuto", "READ: READ_CLICKED")
                                AndroidBotLog.log("READ: READ_CLICKED")
                                startForegroundService()
                                if (AutomationRuntime.hasTaskInFlight()) {
                                    AndroidBotLog.log("READ: MANUAL_RESTART_ACTIVE_TASK")
                                    AutomationRuntime.restartTask(ReadTask)
                                } else {
                                    runTask(ReadTask)
                                }
                            },
                            readingSpeed = speed,
                            onToggleSpeed = { engine?.toggleReadingSpeed() },
                            onStop = { 
                                engine?.stop()
                                stopForegroundService()
                            },
                            onLogToggle = { isLogVisible = !isLogVisible },
                            onProfile = { showProfileDialog = true },
                            cardStatsEnabled = cardStatsEnabled,
                            onToggleCardStats = {
                                cardStatsEnabled = !cardStatsEnabled
                                cardStatisticsController.setEnabled(cardStatsEnabled, AutomationRuntime.webView ?: webView)
                                AndroidBotLog.log("CARD_STATS: ${if (cardStatsEnabled) "ON" else "OFF"}")
                            }
                        )
                    }
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        key(activeProfile, webViewVersion) {
                            AndroidView(
                                modifier = Modifier.fillMaxSize(),
                                factory = {
                                    lateinit var edgeContainer: EdgeBackGestureLayout
                                    edgeContainer = EdgeBackGestureLayout(this@MainActivity).apply {
                                        onEdgeBackGesture = {
                                            val wv = AutomationRuntime.webView ?: webView
                                            if (wv != null && wv.canGoBack()) {
                                                Log.i("MangaBuffAuto", "BACK: EDGE_SWIPE_GO_BACK url=${wv.url}")
                                                AndroidBotLog.log("BACK: EDGE_SWIPE_GO_BACK")
                                                wv.goBack()
                                                true
                                            } else {
                                                Log.i("MangaBuffAuto", "BACK: EDGE_SWIPE_NO_HISTORY")
                                                AndroidBotLog.log("BACK: EDGE_SWIPE_NO_HISTORY")
                                                true
                                            }
                                        }
                                        setOnChildScrollUpCallback { _, _ ->
                                            (AutomationRuntime.webView?.scrollY ?: 0) > 0
                                        }
                                        setOnRefreshListener {
                                            if (AutomationRuntime.hasTaskInFlight()) {
                                                edgeContainer.isRefreshing = false
                                                AndroidBotLog.log("UI: pull-to-refresh ignored while task is active")
                                            } else if (AutomationRuntime.manualReload()) {
                                                edgeContainer.isRefreshing = false
                                                AndroidBotLog.log("UI: PULL_TO_REFRESH")
                                            } else {
                                                edgeContainer.isRefreshing = false
                                            }
                                        }
                                    }
                                    val current = AutomationRuntime.webView ?: AutomationRuntime.ensure(
                                        applicationContext,
                                        activeProfile
                                    )
                                    edgeContainer.addView(current, ViewGroup.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT
                                    ))
                                    webView = current
                                    edgeContainer
                                },
                                update = { container ->
                                    webView = container.getChildAt(0) as? WebView
                                }
                            )
                        }

                        // Stats Overlay (Top Center)
                        if (showStats) {
                            StatsOverlay(
                                stats = engineStats,
                                state = engineState,
                                onToggle = { showStats = false },
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .padding(top = 4.dp)
                            )
                        } else {
                            IconButton(
                                onClick = {
                                    showStats = true
                                    engine?.requestStatsUpdate()
                                    AndroidBotLog.log("STATS: manual refresh")
                                },
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .padding(top = 4.dp)
                                    .background(Color.Black.copy(0.4f), CircleShape)
                                    .size(24.dp)
                            ) {
                                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Show Stats", tint = Color.White, modifier = Modifier.size(14.dp))
                            }
                        }

                        // Log Panel (Overlay)
                        AnimatedVisibility(
                            visible = isLogVisible,
                            enter = slideInVertically(initialOffsetY = { it }),
                            exit = slideOutVertically(targetOffsetY = { it }),
                            modifier = Modifier.align(Alignment.BottomCenter)
                        ) {
                            LogPanel(
                                journal = journal,
                                listState = lazyListState,
                                onClose = { isLogVisible = false },
                                onClear = { journal = emptyList() },
                                onCopy = {
                                    val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                                    val clip = ClipData.newPlainText("MangaBuff Log", journal.joinToString("\n"))
                                    clipboard.setPrimaryClip(clip)
                                    addJournal("LOG COPIED")
                                }
                            )
                        }

                        // Profile dialog
                        if (showProfileDialog) {
                            ProfileDialog(
                                activeProfile = activeProfile,
                                profiles = profileNames,
                                supported = profileManager.isSupported(),
                                canSwitch = engine?.state?.value == AutomationState.IDLE || engine?.state?.value == AutomationState.STOPPED,
                                onDismiss = { showProfileDialog = false },
                                onSelect = { selected ->
                                    if (selected != activeProfile) {
                                        val state = engine?.state?.value
                                        if (state != AutomationState.IDLE && state != AutomationState.STOPPED) {
                                            AndroidBotLog.log("PROFILE: switch blocked while task is running")
                                        } else {
                                            AndroidBotLog.log("PROFILE: switching $activeProfile -> $selected")
                                            if (AutomationRuntime.switchProfile(selected)) {
                                                profileManager.setActiveProfile(selected)
                                                activeProfile = selected
                                                webView = AutomationRuntime.webView
                                            }
                                        }
                                    }
                                },
                                autoModeEnabled = autoModeEnabled,
                                onAutoModeChanged = { enabled ->
                                    if (enabled) startForegroundService()
                                    AutomationRuntime.setAutoModeEnabled(enabled)
                                    autoModeEnabled = AutomationRuntime.isAutoModeEnabled()
                                },
                                onCreate = {
                                    val base = "account_"
                                    var n = 1
                                    while (profileManager.profiles().contains(base + n)) n++
                                    val name = base + n
                                    if (profileManager.createProfile(name)) {
                                        profileVersion++
                                        AndroidBotLog.log("PROFILE: created $name")
                                    }
                                }
                            )
                        }

                        // HyperOS / Redmi background guide dialog
                        if (showHyperOsGuide) {
                            AlertDialog(
                                onDismissRequest = { showHyperOsGuide = false },
                                title = { Text("Настройки фоновой работы (HyperOS / Redmi)") },
                                text = {
                                    Column(modifier = Modifier.fillMaxWidth()) {
                                        Text("Чтобы приложение не выгружалось в фоне и при блокировке экрана (особенно на Redmi 15 Pro / HyperOS 3), выполните следующие настройки:", fontSize = 13.sp)
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text("1. Автозапуск: Перейдите в О приложении → Автозапуск → ВКЛ.", fontSize = 12.sp)
                                        Text("2. Экономия заряда: О приложении → Контроль активности → 'Нет ограничений'.", fontSize = 12.sp)
                                        Text("3. Фоновая активность: Разрешить работу в фоне.", fontSize = 12.sp)
                                        Text("4. Закрепление: Откройте список недавних приложений (Recents), зажмите MangaBuff и нажмите иконку замка (Закрепить).", fontSize = 12.sp)
                                    }
                                },
                                confirmButton = {
                                    TextButton(onClick = { showHyperOsGuide = false }) {
                                        Text("Понятно")
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    private fun startForegroundService() {
        try {
            Log.i("MangaBuffAuto", "[BG] AUTOMATION_STARTED")
            AndroidBotLog.log("[BG] AUTOMATION_STARTED")
            val intent = Intent(this, AutomationForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            Log.e("MangaBuffAuto", "[BG] startForegroundService error: ${e.message}")
        }
    }

    private fun stopForegroundService() {
        try {
            Log.i("MangaBuffAuto", "[BG] AUTOMATION_FINISHED")
            AndroidBotLog.log("[BG] AUTOMATION_FINISHED")
            val intent = Intent(this, AutomationForegroundService::class.java).apply {
                action = AutomationForegroundService.ACTION_STOP
            }
            startService(intent)
        } catch (e: Exception) {
            Log.e("MangaBuffAuto", "[BG] stopForegroundService error: ${e.message}")
        }
    }

    override fun onStart() {
        super.onStart()
        Log.i("MangaBuffAuto", "[BG] APP_FOREGROUND")
        AndroidBotLog.log("[BG] APP_FOREGROUND")
        AutomationRuntime.setAppVisible(true)
        webView?.let {
            Log.i("MangaBuffAuto", "[BG] WEBVIEW_ALIVE")
            AndroidBotLog.log("[BG] WEBVIEW_ALIVE")
        }
    }

    override fun onStop() {
        AutomationRuntime.onBackStateChanged = null
        super.onStop()
        Log.i("MangaBuffAuto", "[BG] APP_BACKGROUND")
        AndroidBotLog.log("[BG] APP_BACKGROUND")
        AutomationRuntime.setAppVisible(false)
    }

    override fun onDestroy() {
        // OnBackPressedCallback is lifecycle-aware and is removed automatically.
        // Не останавливаем Foreground Service и Engine здесь:
        // при блокировке/пересоздании Activity автоматизация должна продолжать работать.
        AutomationRuntime.onPageStarted = null
        AutomationRuntime.onPageFinished = null
        cardStatisticsController.clear(AutomationRuntime.webView)
        Log.i("MangaBuffAuto", "[BG] ACTIVITY_DESTROYED - runtime preserved")
        AndroidBotLog.log("[BG] ACTIVITY_DESTROYED - runtime preserved")
        super.onDestroy()
    }

    private fun runTask(task: AutomationTask) {
        Log.i("MangaBuffAuto", "ENGINE: independent task requested id=${task.spec.id}")
        AndroidBotLog.log("ENGINE: independent task requested id=${task.spec.id}")
        AutomationRuntime.startTask(task)
    }


}

@Composable
fun CompactBottomBar(
    onMine: () -> Unit,
    onAds: () -> Unit,
    onChat: () -> Unit,
    onRead: () -> Unit,
    readingSpeed: Float,
    onToggleSpeed: () -> Unit,
    onStop: () -> Unit,
    onLogToggle: () -> Unit,
    onProfile: () -> Unit,
    cardStatsEnabled: Boolean,
    onToggleCardStats: () -> Unit
) {
    BottomAppBar(
        containerColor = Color(0xFF1E1E1E),
        contentPadding = PaddingValues(horizontal = 4.dp),
        modifier = Modifier.height(56.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onMine, modifier = Modifier.size(40.dp)) {
                Text("⛏️", fontSize = 24.sp)
            }
            IconButton(onClick = onAds, modifier = Modifier.size(40.dp)) {
                Text("📺", fontSize = 23.sp)
            }
            IconButton(onClick = onChat, modifier = Modifier.size(40.dp)) {
                Text("💎", fontSize = 22.sp)
            }
            IconButton(onClick = onToggleCardStats, modifier = Modifier.size(40.dp)) {
                Text(
                    "🃏",
                    fontSize = 22.sp,
                    color = if (cardStatsEnabled) Color(0xFF4ADE80) else Color.White
                )
            }
            IconButton(onClick = onRead, modifier = Modifier.size(40.dp)) {
                Text("📖", fontSize = 23.sp)
            }
            TextButton(
                onClick = onToggleSpeed,
                modifier = Modifier.width(48.dp),
                contentPadding = PaddingValues(0.dp)
            ) {
                Text(
                    "${readingSpeed.toInt()}x",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            IconButton(onClick = onStop, modifier = Modifier.size(40.dp)) {
                Text("❌", fontSize = 21.sp)
            }
            IconButton(onClick = onLogToggle, modifier = Modifier.size(40.dp)) {
                Text("📋", fontSize = 23.sp)
            }
            IconButton(onClick = onProfile, modifier = Modifier.size(40.dp)) {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = "Профиль",
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

@Composable
fun StatsOverlay(
    stats: MangaBuffStats,
    state: AutomationState,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.clickable { onToggle() },
        color = Color.Black.copy(alpha = 0.6f),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(state.name, color = Color.Yellow, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            StatsItem("💎", stats.diamonds?.toString() ?: "—")
            StatsItem("⛏️", stats.mineOre?.toString() ?: "—")
            StatsItem("🃏", if (stats.cardsCurrent != null) "${stats.cardsCurrent}/${stats.cardsMax ?: 10}" else "—")
            StatsItem("📺", if (stats.adsCurrent != null) "${stats.adsCurrent}/${stats.adsMax ?: 3}" else "—")
            StatsItem("💬", if (stats.commentsCurrent != null) "${stats.commentsCurrent}/${stats.commentsMax ?: 10}" else "—")
        }
    }
}

@Composable
fun StatsItem(icon: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(icon, fontSize = 10.sp)
        Spacer(modifier = Modifier.width(2.dp))
        Text(value, color = Color.White, fontSize = 10.sp)
    }
}

@Composable
fun LogPanel(
    journal: List<String>,
    listState: LazyListState,
    onClose: () -> Unit,
    onClear: () -> Unit,
    onCopy: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.5f),
        color = Color.Black.copy(alpha = 0.9f)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Logs", color = Color.White, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onCopy) {
                        Icon(Icons.Default.Share, contentDescription = "Copy", tint = Color.White)
                    }
                    IconButton(onClick = onClear) {
                        Icon(Icons.Default.Delete, contentDescription = "Clear", tint = Color.Red)
                    }
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                    }
                }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(8.dp)
            ) {
                items(journal) { line ->
                    Text(
                        text = line,
                        color = if (line.contains("ERROR") || line.contains("FAILED")) Color.Red else Color.LightGray,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(vertical = 1.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun ProfileDialog(
    activeProfile: String,
    profiles: List<String>,
    supported: Boolean,
    canSwitch: Boolean,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
    autoModeEnabled: Boolean,
    onAutoModeChanged: (Boolean) -> Unit,
    onCreate: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text("Профили")
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "Текущий профиль: $activeProfile",
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )

                if (!supported) {
                    Text(
                        "Multi-Profile WebView недоступен в текущем WebView. Используется основной профиль.",
                        color = Color.Gray,
                        fontSize = 12.sp
                    )
                }

                Text(
                    if (canSwitch) "Выберите профиль для переключения:" else "Переключение заблокировано: сейчас выполняется задача.",
                    color = if (canSwitch) Color.LightGray else Color(0xFFFFCC66),
                    fontSize = 12.sp
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Автоматическая работа", color = Color.White, fontWeight = FontWeight.Bold)
                        Text(
                            "Чат 15:30 · чтение 1 ч · шахта/реклама 1 раз в сутки",
                            color = Color.Gray,
                            fontSize = 11.sp
                        )
                    }
                    Switch(
                        checked = autoModeEnabled,
                        onCheckedChange = onAutoModeChanged
                    )
                }

                profiles.forEach { profile ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = supported && canSwitch) { onSelect(profile); onDismiss() },
                        color = if (profile == activeProfile) Color(0xFF303030) else Color(0xFF242424),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (profile == activeProfile) Icons.Default.CheckCircle else Icons.Default.AccountCircle,
                                contentDescription = null,
                                tint = if (profile == activeProfile) Color(0xFFBB86FC) else Color.LightGray,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(profile, color = Color.White)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row {
                if (supported) {
                    TextButton(
                        enabled = canSwitch,
                        onClick = { onCreate() }
                    ) {
                        Text("+ Новый профиль")
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("Закрыть")
                }
            }
        }
    )

}
