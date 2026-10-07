package com.example.myapplication

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
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
                        val webView = AutomationWebViewRegistry.webViewsByAccount.value.values
                            .firstOrNull()

                        if (webView != null && webView.canGoBack()) {
                            webView.goBack()
                        } else {
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
            MyApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val webViewsByAccount by AutomationWebViewRegistry.webViewsByAccount.collectAsState()
                    val debugWebViewVisible by viewModel.debugWebViewVisible.collectAsState()
                    val debugWebView = webViewsByAccount.values.firstOrNull()

                    Box(modifier = Modifier.fillMaxSize()) {
                        MangaBuffAppUI(
                            viewModel = viewModel,
                            webViewContainer = debugWebView
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

                                            val returnButton = Button(this@MainActivity).apply {
                                                text = "Вернуться в UI"
                                                isAllCaps = false
                                                setOnClickListener {
                                                    viewModel.setDebugWebViewVisible(false)
                                                }
                                                visibility = if (debugWebViewVisible) {
                                                    View.VISIBLE
                                                } else {
                                                    View.GONE
                                                }
                                            }

                                            addView(
                                                returnButton,
                                                FrameLayout.LayoutParams(
                                                    FrameLayout.LayoutParams.WRAP_CONTENT,
                                                    FrameLayout.LayoutParams.WRAP_CONTENT
                                                ).apply {
                                                    gravity = Gravity.TOP or Gravity.END
                                                    topMargin = (12 * resources.displayMetrics.density).toInt()
                                                    marginEnd = (12 * resources.displayMetrics.density).toInt()
                                                }
                                            )

                                            tag = returnButton
                                        }
                                    },
                                    modifier = Modifier.fillMaxSize(),
                                    update = { container ->
                                        (container.tag as? Button)?.visibility =
                                            if (debugWebViewVisible) View.VISIBLE else View.GONE

                                        webView.visibility =
                                            if (debugWebViewVisible) View.VISIBLE else View.INVISIBLE
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
