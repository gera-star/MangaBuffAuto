package com.example.myapplication

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext

/**
 * Offline splash artwork loaded from bundled binary fragments.
 * The previous vector/text-only KEENY splash is intentionally removed.
 */
@Composable
fun KeenySplash(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val splashBitmap = remember(context) {
        runCatching {
            val parts = (0 until 7).map { index ->
                context.assets.open("keeny_splash/part$index.bin").use { it.readBytes() }
            }
            val totalSize = parts.sumOf { it.size }
            val bytes = ByteArray(totalSize)
            var offset = 0
            parts.forEach { part ->
                System.arraycopy(part, 0, bytes, offset, part.size)
                offset += part.size
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }.getOrNull()
    }

    if (splashBitmap != null) {
        Image(
            bitmap = splashBitmap,
            contentDescription = "Keeny splash",
            modifier = modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
    } else {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Color(0xFF06152B))
        )
    }
}
