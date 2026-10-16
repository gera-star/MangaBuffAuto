package com.example.myapplication

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardReturn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.example.myapplication.automation.TradeAutomation
import com.example.myapplication.ui.TradeModeEdgeButton
import com.example.myapplication.ui.TradeModeScreen
import com.example.myapplication.automation.AutomationWebViewRegistry
import com.example.myapplication.ui.MainViewModel
import com.example.myapplication.ui.MangaBuffAppUI
import com.example.myapplication.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    private var edgeBackDownX = 0f
    private var edgeBackDownY = 0f
    private var edgeBackDownAt = 0L
    private var edgeBackTracking = false

    override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
        val density = resources.displayMetrics.density.coerceAtLeast(1f)
        val edgePx = (40f * density).coerceAtLeast(28f)
        val triggerPx = (64f * density).coerceAtLeast(48f)

        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                val fromLeft = event.x <= edgePx
                val fromRight = event.x >= (resources.displayMetrics.widthPixels - edgePx)
                edgeBackTracking = fromLeft || fromRight
                if (edgeBackTracking) {
                    edgeBackDownX = event.x
                    edgeBackDownY = event.y
                    edgeBackDownAt = android.os.SystemClock.uptimeMillis()
                }
            }

            android.view.MotionEvent.ACTION_UP,
            android.view.MotionEvent.ACTION_CANCEL -> {
                if (edgeBackTracking && event.actionMasked == android.view.MotionEvent.ACTION_UP) {
                    val dx = event.x - edgeBackDownX
                    val dy = event.y - edgeBackDownY
                    val duration = android.os.SystemClock.uptimeMillis() - edgeBackDownAt
                    val fromLeft = edgeBackDownX <= edgePx
                    val fromRight = edgeBackDownX >= (resources.displayMetrics.widthPixels - edgePx)
                    val towardCenter =
                        (fromLeft && dx >= triggerPx) ||
                            (fromRight && dx <= -triggerPx)
                    val mostlyHorizontal =
                        kotlin.math.abs(dy) <= kotlin.math.abs(dx) * 0.85f
                    val shortGesture = duration <= 700L

                    if (towardCenter && mostlyHorizontal && shortGesture) {
                        val webViews = AutomationWebViewRegistry.webViewsByAccount.value.values
                        if (webViews.size == 1) {
                            val webView = webViews.first()
                            if (webView.canGoBack()) {
                                webView.goBack()
                            } else {
                                onBackPressedDispatcher.onBackPressed()
                            }
                        } else {
                            // Never guess which account owns the gesture when several
                            // WebViews are active. Fall back to Activity navigation.
                            onBackPressedDispatcher.onBackPressed()
                        }

                        edgeBackTracking = false
                        return true
                    }
                }

                edgeBackTracking = false
            }
        }

        return super.dispatchTouchEvent(event)
    }

    private val requestNotificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        setContent {
            MyApplicationTheme(darkTheme = true, dynamicColor = false) {
                val settings by viewModel.settings.collectAsState()
                val automationRunning by viewModel.isRunning.collectAsState()
                val tradeAutomation = remember { TradeAutomation(applicationContext) }
                var tradingModeOpen by remember { mutableStateOf(false) }
                var showTradePasswordDialog by remember { mutableStateOf(false) }
                var tradePassword by remember { mutableStateOf("") }
                var tradePasswordError by remember { mutableStateOf(false) }

                DisposableEffect(tradeAutomation) {
                    onDispose { tradeAutomation.close() }
                }

                DisposableEffect(settings.keepScreenOn, automationRunning) {
                    if (settings.keepScreenOn && automationRunning) {
                        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                    onDispose {
                        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.Transparent
                ) {
                    val webViewsByAccount by AutomationWebViewRegistry.webViewsByAccount.collectAsState()
                    val debugWebViewVisible by viewModel.debugWebViewVisible.collectAsState()
                    val webViewAccountIds = webViewsByAccount.keys.toList()
                    var selectedDebugAccountId by remember { mutableStateOf<String?>(null) }

                    LaunchedEffect(webViewAccountIds) {
                        if (selectedDebugAccountId !in webViewAccountIds) {
                            selectedDebugAccountId = if (webViewAccountIds.size == 1) {
                                webViewAccountIds.single()
                            } else {
                                null
                            }
                        }
                    }

                    val debugWebView = selectedDebugAccountId?.let(webViewsByAccount::get)

                    var showKeenySplash by remember { mutableStateOf(true) }
                    var splashPlaybackStarted by remember { mutableStateOf(false) }
                    var splashPlaybackFailed by remember { mutableStateOf(false) }

                    // Keep the intro visible long enough to see the animation, but never
                    // let a slow/unreachable video delay the main screen indefinitely.
                    LaunchedEffect(Unit) {
                        kotlinx.coroutines.delay(10_000L)
                        showKeenySplash = false
                    }
                    LaunchedEffect(splashPlaybackStarted, splashPlaybackFailed) {
                        when {
                            splashPlaybackStarted -> {
                                kotlinx.coroutines.delay(5_000L)
                                showKeenySplash = false
                            }
                            splashPlaybackFailed -> {
                                kotlinx.coroutines.delay(1_500L)
                                showKeenySplash = false
                            }
                        }
                    }

                    Box(modifier = Modifier.fillMaxSize()) {
                        LiquidGlassBackdrop()

                        MangaBuffAppUI(
                            viewModel = viewModel,
                            webViewContainer = debugWebView,
                            webViewAccountIds = webViewAccountIds,
                            selectedWebViewAccountId = selectedDebugAccountId,
                            onSelectWebViewAccount = { selectedDebugAccountId = it }
                        )

                        debugWebView?.let { webView ->
                            key(webView) {
                                AndroidView(
                                    factory = {
                                        FrameLayout(this@MainActivity).apply {
                                            setBackgroundColor(android.graphics.Color.TRANSPARENT)

                                            if (webView.parent is android.view.ViewGroup) {
                                                (webView.parent as android.view.ViewGroup).removeView(webView)
                                            }

                                            addView(
                                                webView,
                                                FrameLayout.LayoutParams(
                                                    FrameLayout.LayoutParams.MATCH_PARENT,
                                                    FrameLayout.LayoutParams.MATCH_PARENT
                                                )
                                            )

                                        }
                                    },
                                    // Keep the debug WebView inside the safe area so it never covers
                                    // the Android status bar (clock, battery, camera cutout) or nav bar.
                                    modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
                                    update = { container ->
                                        webView.visibility =
                                            if (debugWebViewVisible) View.VISIBLE else View.INVISIBLE
                                    }
                                )
                            }
                        }

                        if (showKeenySplash) {
                            // fillMaxSize works across Compose versions; avoid matchParentSize here.
                            MangaBuffSplash(
                                modifier = Modifier.fillMaxSize(),
                                playbackFailed = splashPlaybackFailed,
                                onPlaybackStarted = { splashPlaybackStarted = true },
                                onPlaybackFailed = { splashPlaybackFailed = true }
                            )
                        }

                        if (debugWebViewVisible && debugWebView != null) {
                            WebViewReturnOverlay(
                                modifier = Modifier.align(Alignment.BottomCenter),
                                onReturn = { viewModel.setDebugWebViewVisible(false) }
                            )
                        }

                        if (tradingModeOpen) {
                            TradeModeScreen(
                                viewModel = viewModel,
                                engine = tradeAutomation,
                                onBack = { tradingModeOpen = false }
                            )
                        } else if (!showKeenySplash && !debugWebViewVisible) {
                            TradeModeEdgeButton(
                                modifier = Modifier.align(Alignment.CenterEnd),
                                isBack = false,
                                onClick = {
                                    tradePassword = ""
                                    tradePasswordError = false
                                    showTradePasswordDialog = true
                                }
                            )
                        }

                        if (showTradePasswordDialog) {
                            AlertDialog(
                                onDismissRequest = { showTradePasswordDialog = false },
                                title = { Text("Торговый режим") },
                                text = {
                                    Column {
                                        Text("Введите пароль для открытия скрытого меню.")
                                        OutlinedTextField(
                                            value = tradePassword,
                                            onValueChange = {
                                                tradePassword = it.filter(Char::isDigit).take(8)
                                                tradePasswordError = false
                                            },
                                            label = { Text("Пароль") },
                                            singleLine = true,
                                            isError = tradePasswordError,
                                            visualTransformation = PasswordVisualTransformation(),
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                                        )
                                        if (tradePasswordError) {
                                            Text("Неверный пароль", color = Color(0xFFFF8A80))
                                        }
                                    }
                                },
                                confirmButton = {
                                    Button(
                                        onClick = {
                                            if (tradePassword == "1988") {
                                                tradingModeOpen = true
                                                showTradePasswordDialog = false
                                                tradePassword = ""
                                                tradePasswordError = false
                                            } else {
                                                tradePasswordError = true
                                            }
                                        }
                                    ) {
                                        Text("Открыть")
                                    }
                                },
                                dismissButton = {
                                    TextButton(onClick = { showTradePasswordDialog = false }) {
                                        Text("Назад")
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}


@Composable
private fun LiquidGlassBackdrop() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF292C31),
                        Color(0xFF22252A),
                        Color(0xFF191C21)
                    )
                )
            )
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0x0D42DFF5), Color.Transparent),
                    center = Offset(size.width * 0.04f, size.height * 0.18f),
                    radius = size.maxDimension * 0.75f
                )
            )
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0x0CB65CFF), Color.Transparent),
                    center = Offset(size.width * 0.98f, size.height * 0.84f),
                    radius = size.maxDimension * 0.72f
                )
            )
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0x07FFFFFF), Color.Transparent),
                    center = Offset(size.width * 0.5f, size.height * 0.42f),
                    radius = size.maxDimension * 0.9f
                )
            )
        }
    }
}



@Composable
private fun WebViewReturnOverlay(
    modifier: Modifier = Modifier,
    onReturn: () -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .padding(bottom = 56.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            repeat(7) { index ->
                Box(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    contentAlignment = Alignment.Center
                ) {
                    if (index == 2) {
                        Surface(
                            modifier = Modifier
                                .size(48.dp)
                                .border(
                                    BorderStroke(
                                        1.5.dp,
                                        Brush.linearGradient(
                                            listOf(Color(0xFF43E8FF), Color(0xFFB85CFF))
                                        )
                                    ),
                                    RoundedCornerShape(50)
                                ),
                            shape = RoundedCornerShape(50),
                            color = Color(0xF0171A24),
                            tonalElevation = 0.dp,
                            shadowElevation = 8.dp
                        ) {
                            IconButton(
                                onClick = onReturn,
                                modifier = Modifier.fillMaxSize()
                            ) {
                                Icon(
                                    Icons.Default.KeyboardReturn,
                                    contentDescription = "Вернуться в приложение",
                                    tint = Color(0xFF6CEBFF)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
