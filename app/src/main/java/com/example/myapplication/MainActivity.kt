package com.example.myapplication

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.webkit.WebView
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
import com.example.myapplication.ui.MainViewModel
import com.example.myapplication.ui.MangaBuffAppUI
import com.example.myapplication.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    private val requestNotificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val activeWebView by viewModel.activeWebView.collectAsState()
                    val debugWebViewVisible by viewModel.debugWebViewVisible.collectAsState()

                    Box(modifier = Modifier.fillMaxSize()) {
                        // Normal UI is rendered first.
                        MangaBuffAppUI(
                            viewModel = viewModel,
                            webViewContainer = activeWebView
                        )

                        // Keep exactly one automation WebView attached at full size so its
                        // viewport never collapses. Normally it is INVISIBLE; in temporary
                        // DEBUG mode it is rendered above the Compose UI so the real ad can
                        // be seen and its X can be pressed manually.
                        activeWebView?.let { webView ->
                            key(webView) {
                                AndroidView(
                                    factory = { webView },
                                    modifier = Modifier.fillMaxSize(),
                                    update = {
                                        it.visibility = if (debugWebViewVisible) {
                                            android.view.View.VISIBLE
                                        } else {
                                            android.view.View.INVISIBLE
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
}
