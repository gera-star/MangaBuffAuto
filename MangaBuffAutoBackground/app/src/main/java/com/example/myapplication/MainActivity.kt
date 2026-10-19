package com.example.myapplication

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.webkit.WebView
import android.os.Build
import android.os.Bundle
import android.net.Uri
import android.view.View
import android.widget.FrameLayout
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.consume
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.roundToInt
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
                val tradeAutomation = remember { TradeAutomation.getInstance(applicationContext) }
                var tradingModeOpen by remember { mutableStateOf(false) }
                var showTradePasswordDialog by remember { mutableStateOf(false) }
                var tradePassword by remember { mutableStateOf("") }
                var tradePasswordError by remember { mutableStateOf(false) }

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

                    var showStartupSplash by remember { mutableStateOf(true) }

                    // The video ends naturally; this is a failsafe if decoding fails or
                    // the playback callback is never delivered.
                    LaunchedEffect(Unit) {
                        kotlinx.coroutines.delay(12_000L)
                        showStartupSplash = false
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

                        if (showStartupSplash) {
                            VideoStartupSplash(
                                modifier = Modifier.fillMaxSize(),
                                onFinished = { showStartupSplash = false }
                            )
                        }

                        if (debugWebViewVisible && debugWebView != null) {
                            MovableWebControls(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .statusBarsPadding()
                                    .navigationBarsPadding(),
                                webView = debugWebView,
                                onExitWeb = { viewModel.setDebugWebViewVisible(false) }
                            )
                        }

                        if (tradingModeOpen) {
                            TradeModeScreen(
                                viewModel = viewModel,
                                engine = tradeAutomation,
                                onBack = { tradingModeOpen = false }
                            )
                        } else if (!showStartupSplash && !debugWebViewVisible) {
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
private fun VideoStartupSplash(
    modifier: Modifier = Modifier,
    onFinished: () -> Unit
) {
    var firstVideoFrameVisible by remember { mutableStateOf(false) }
    var playbackFailed by remember { mutableStateOf(false) }

    // If the decoder or video surface fails, keep a visible branded fallback
    // briefly, then allow the main screen to open. Never leave a black splash.
    LaunchedEffect(playbackFailed) {
        if (playbackFailed) {
            kotlinx.coroutines.delay(2_200L)
            onFinished()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF070A13))
    ) {
        if (!firstVideoFrameVisible) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color(0xFF10182B),
                                Color(0xFF080D1B),
                                Color(0xFF160F1A)
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val sparks = listOf(
                        Offset(size.width * 0.12f, size.height * 0.18f),
                        Offset(size.width * 0.83f, size.height * 0.25f),
                        Offset(size.width * 0.21f, size.height * 0.73f),
                        Offset(size.width * 0.88f, size.height * 0.81f),
                        Offset(size.width * 0.66f, size.height * 0.12f)
                    )
                    sparks.forEachIndexed { index, point ->
                        drawCircle(
                            color = if (index % 2 == 0) Color(0x88FF9A45) else Color(0x6678DFFF),
                            radius = if (index % 2 == 0) 3.5f else 2.5f,
                            center = point
                        )
                    }
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "MANGA",
                        style = androidx.compose.material3.MaterialTheme.typography.displayMedium,
                        color = Color.White
                    )
                    Text(
                        text = "2R2R",
                        style = androidx.compose.material3.MaterialTheme.typography.displayMedium,
                        color = Color(0xFFFF9148)
                    )
                    Spacer(modifier = Modifier.height(22.dp))
                    Text(
                        text = "Загружаем твой мир манги…",
                        style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFD5DCEC)
                    )
                }
            }
        }

        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                android.view.TextureView(context).apply {
                    val textureView = this
                    isOpaque = false
                    alpha = 0f

                    surfaceTextureListener = object : android.view.TextureView.SurfaceTextureListener {
                        private var mediaPlayer: android.media.MediaPlayer? = null
                        private var outputSurface: android.view.Surface? = null

                        private fun revealVideoFrame() {
                            if (!playbackFailed && !firstVideoFrameVisible) {
                                firstVideoFrameVisible = true
                                textureView.alpha = 1f
                            }
                        }

                        override fun onSurfaceTextureAvailable(
                            surfaceTexture: android.graphics.SurfaceTexture,
                            width: Int,
                            height: Int
                        ) {
                            val player = android.media.MediaPlayer()
                            mediaPlayer = player
                            val surface = android.view.Surface(surfaceTexture)
                            outputSurface = surface

                            try {
                                player.setSurface(surface)
                                player.setOnPreparedListener { preparedPlayer ->
                                    preparedPlayer.isLooping = false
                                    preparedPlayer.start()
                                }
                                player.setOnInfoListener { _, what, _ ->
                                    if (what == android.media.MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                                        revealVideoFrame()
                                    }
                                    false
                                }
                                player.setOnVideoSizeChangedListener { _, videoWidth, videoHeight ->
                                    val viewWidth = textureView.width
                                    val viewHeight = textureView.height
                                    if (videoWidth > 0 && videoHeight > 0 &&
                                        viewWidth > 0 && viewHeight > 0
                                    ) {
                                        surfaceTexture.setDefaultBufferSize(videoWidth, videoHeight)
                                        val source = android.graphics.RectF(
                                            0f, 0f, videoWidth.toFloat(), videoHeight.toFloat()
                                        )
                                        val destination = android.graphics.RectF(
                                            0f, 0f, viewWidth.toFloat(), viewHeight.toFloat()
                                        )
                                        val transform = android.graphics.Matrix().apply {
                                            setRectToRect(
                                                source,
                                                destination,
                                                android.graphics.Matrix.ScaleToFit.CENTER
                                            )
                                        }
                                        textureView.setTransform(transform)
                                    }
                                }
                                player.setOnCompletionListener {
                                    onFinished()
                                }
                                player.setOnErrorListener { _, _, _ ->
                                    playbackFailed = true
                                    true
                                }
                                player.setDataSource(
                                    context,
                                    Uri.parse("android.resource://${context.packageName}/${R.raw.mangabuff_splash}")
                                )
                                player.prepareAsync()
                            } catch (_: Exception) {
                                playbackFailed = true
                                try {
                                    player.release()
                                } catch (_: Exception) {
                                    // Ignore cleanup errors during startup failure.
                                }
                                mediaPlayer = null
                                outputSurface?.release()
                                outputSurface = null
                            }
                        }

                        override fun onSurfaceTextureSizeChanged(
                            surfaceTexture: android.graphics.SurfaceTexture,
                            width: Int,
                            height: Int
                        ) = Unit

                        override fun onSurfaceTextureDestroyed(
                            surfaceTexture: android.graphics.SurfaceTexture
                        ): Boolean {
                            mediaPlayer?.let { playerToRelease ->
                                try {
                                    if (playerToRelease.isPlaying) playerToRelease.stop()
                                } catch (_: Exception) {
                                    // The player may already be in an error state.
                                }
                                try {
                                    playerToRelease.release()
                                } catch (_: Exception) {
                                    // Ignore cleanup errors while destroying the surface.
                                }
                            }
                            mediaPlayer = null
                            outputSurface?.release()
                            outputSurface = null
                            return true
                        }

                        override fun onSurfaceTextureUpdated(
                            surfaceTexture: android.graphics.SurfaceTexture
                        ) {
                            revealVideoFrame()
                        }
                    }
                }
            },
            update = { textureView ->
                textureView.alpha = if (firstVideoFrameVisible && !playbackFailed) 1f else 0f
            }
        )
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



/**
 * Two movable controls over the visible WebView:
 * - Tap dispatches a finger-like DOWN/MOVE/UP at the center of its circle.
 * - Back navigates WebView history, returning to the app when there is no history.
 *
 * Locations are normalized against the usable WebView area and persisted in
 * SharedPreferences, so moving either control survives process/app restarts.
 */
@Composable
private fun MovableWebControls(
    modifier: Modifier = Modifier,
    webView: WebView,
    onExitWeb: () -> Unit
) {
    val context = LocalContext.current.applicationContext
    val prefs = remember(context) {
        context.getSharedPreferences("mangabuff_web_floating_controls", Context.MODE_PRIVATE)
    }
    var tapX by remember(prefs) {
        mutableFloatStateOf(prefs.getFloat("tap_left_ratio", 0.925f).coerceIn(0f, 1f))
    }
    var tapY by remember(prefs) {
        mutableFloatStateOf(prefs.getFloat("tap_top_ratio", 0.112f).coerceIn(0f, 1f))
    }
    var backX by remember(prefs) {
        mutableFloatStateOf(prefs.getFloat("back_left_ratio", 0.925f).coerceIn(0f, 1f))
    }
    var backY by remember(prefs) {
        mutableFloatStateOf(prefs.getFloat("back_top_ratio", 0.50f).coerceIn(0f, 1f))
    }
    var areaSize by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    val buttonPx = with(density) { 48.dp.roundToPx() }
    val maxLeft = (areaSize.width - buttonPx).coerceAtLeast(0)
    val maxTop = (areaSize.height - buttonPx).coerceAtLeast(0)

    Box(
        modifier = modifier.onSizeChanged { areaSize = it }
    ) {
        MovableWebCircle(
            description = "Тап по центру кружка",
            glyph = "×",
            areaSize = areaSize,
            leftRatio = tapX,
            topRatio = tapY,
            onMoved = { x, y -> tapX = x; tapY = y },
            onMoveFinished = {
                prefs.edit()
                    .putFloat("tap_left_ratio", tapX)
                    .putFloat("tap_top_ratio", tapY)
                    .apply()
            },
            onClick = {
                val x = tapX * maxLeft + buttonPx / 2f
                val y = tapY * maxTop + buttonPx / 2f
                dispatchMovableFingerTap(webView, x, y)
            }
        )

        MovableWebCircle(
            description = "Назад в WebView",
            glyph = "↶",
            areaSize = areaSize,
            leftRatio = backX,
            topRatio = backY,
            onMoved = { x, y -> backX = x; backY = y },
            onMoveFinished = {
                prefs.edit()
                    .putFloat("back_left_ratio", backX)
                    .putFloat("back_top_ratio", backY)
                    .apply()
            },
            onClick = {
                if (webView.isAttachedToWindow && webView.canGoBack()) {
                    webView.goBack()
                } else {
                    onExitWeb()
                }
            }
        )
    }
}

@Composable
private fun MovableWebCircle(
    description: String,
    glyph: String,
    areaSize: IntSize,
    leftRatio: Float,
    topRatio: Float,
    onMoved: (Float, Float) -> Unit,
    onMoveFinished: () -> Unit,
    onClick: () -> Unit
) {
    val density = LocalDensity.current
    val buttonPx = with(density) { 48.dp.roundToPx() }
    val maxLeft = (areaSize.width - buttonPx).coerceAtLeast(0)
    val maxTop = (areaSize.height - buttonPx).coerceAtLeast(0)
    val currentLeftRatio = rememberUpdatedState(leftRatio)
    val currentTopRatio = rememberUpdatedState(topRatio)
    val currentOnMoved = rememberUpdatedState(onMoved)
    val currentOnMoveFinished = rememberUpdatedState(onMoveFinished)

    Box(
        modifier = Modifier
            .offset {
                IntOffset(
                    (leftRatio * maxLeft).roundToInt(),
                    (topRatio * maxTop).roundToInt()
                )
            }
            .size(48.dp)
            .border(
                BorderStroke(
                    1.5.dp,
                    Brush.linearGradient(
                        listOf(Color(0xFF43E8FF), Color(0xFFB85CFF))
                    )
                ),
                CircleShape
            )
            .clip(CircleShape)
            .background(Color(0xF0171A24))
            .pointerInput(areaSize) {
                var initialLeft = 0f
                var initialTop = 0f
                var totalX = 0f
                var totalY = 0f
                detectDragGestures(
                    onDragStart = {
                        initialLeft = currentLeftRatio.value * maxLeft
                        initialTop = currentTopRatio.value * maxTop
                        totalX = 0f
                        totalY = 0f
                    },
                    onDragEnd = { currentOnMoveFinished.value() },
                    onDragCancel = { currentOnMoveFinished.value() },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        totalX += dragAmount.x
                        totalY += dragAmount.y
                        val left = (initialLeft + totalX).coerceIn(0f, maxLeft.toFloat())
                        val top = (initialTop + totalY).coerceIn(0f, maxTop.toFloat())
                        currentOnMoved.value(
                            if (maxLeft > 0) left / maxLeft else 0f,
                            if (maxTop > 0) top / maxTop else 0f
                        )
                    }
                )
            }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = glyph,
            color = Color(0xFF6CEBFF),
            fontSize = if (glyph == "×") 28.sp else 27.sp,
            lineHeight = 28.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
        )
    }
}

private fun dispatchMovableFingerTap(
    webView: WebView,
    x: Float,
    y: Float
) {
    val mainLooper = android.os.Looper.getMainLooper()
    if (android.os.Looper.myLooper() != mainLooper) {
        android.os.Handler(mainLooper).post { dispatchMovableFingerTap(webView, x, y) }
        return
    }
    if (!webView.isAttachedToWindow || webView.width <= 0 || webView.height <= 0) return

    val px = x.coerceIn(1f, (webView.width - 2).coerceAtLeast(1).toFloat())
    val py = y.coerceIn(1f, (webView.height - 2).coerceAtLeast(1).toFloat())
    val downTime = android.os.SystemClock.uptimeMillis()

    fun fingerEvent(eventTime: Long, action: Int): android.view.MotionEvent {
        val properties = android.view.MotionEvent.PointerProperties().apply {
            id = 0
            toolType = android.view.MotionEvent.TOOL_TYPE_FINGER
        }
        val coordinates = android.view.MotionEvent.PointerCoords().apply {
            this.x = px
            this.y = py
            pressure = 0.85f
            size = 0.18f
            touchMajor = 22f
            touchMinor = 18f
            orientation = 0f
        }
        return android.view.MotionEvent.obtain(
            downTime, eventTime, action, 1,
            arrayOf(properties), arrayOf(coordinates),
            0, 0, 1f, 1f, 0, 0,
            android.view.InputDevice.SOURCE_TOUCHSCREEN, 0
        )
    }

    val down = fingerEvent(downTime, android.view.MotionEvent.ACTION_DOWN)
    try {
        webView.dispatchTouchEvent(down)
    } finally {
        down.recycle()
    }

    val handler = android.os.Handler(mainLooper)
    handler.postDelayed({
        if (!webView.isAttachedToWindow) return@postDelayed
        val moveTime = android.os.SystemClock.uptimeMillis()
        val move = fingerEvent(moveTime, android.view.MotionEvent.ACTION_MOVE)
        try {
            webView.dispatchTouchEvent(move)
        } finally {
            move.recycle()
        }
        handler.postDelayed({
            if (!webView.isAttachedToWindow) return@postDelayed
            val upTime = android.os.SystemClock.uptimeMillis()
            val up = fingerEvent(upTime, android.view.MotionEvent.ACTION_UP)
            try {
                webView.dispatchTouchEvent(up)
            } finally {
                up.recycle()
            }
        }, 90L)
    }, 70L)
}
