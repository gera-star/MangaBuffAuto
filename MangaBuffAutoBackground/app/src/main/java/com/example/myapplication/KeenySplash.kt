package com.example.myapplication

import android.media.MediaPlayer
import android.net.Uri
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

private const val SPLASH_VIDEO_URL =
    "https://mangabuff.ru/img/cards-video/card_a5adc92f-f09f-47fa-8155-6d14dbe3db4e.mp4"

/**
 * Branded splash backed by the MangaBuff card animation.
 *
 * The video is streamed from the supplied HTTPS URL, muted and looped for the
 * lifetime of this composable. The splash has a branded fallback if streaming fails.
 */
@Composable
fun MangaBuffSplash(
    modifier: Modifier = Modifier,
    playbackFailed: Boolean,
    onPlaybackStarted: () -> Unit,
    onPlaybackFailed: () -> Unit
) {
    val videoViewRef = remember { mutableStateOf<VideoView?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            videoViewRef.value?.let { view ->
                runCatching { view.stopPlayback() }
            }
            videoViewRef.value = null
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF08070B))
    ) {
        if (!playbackFailed) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    VideoView(context).apply {
                        setBackgroundColor(android.graphics.Color.BLACK)
                        setOnPreparedListener { player: MediaPlayer ->
                            player.isLooping = true
                            player.setVolume(0f, 0f)
                            runCatching {
                                player.setVideoScalingMode(
                                    MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
                                )
                            }
                            start()
                            onPlaybackStarted()
                        }
                        setOnErrorListener { _, _, _ ->
                            onPlaybackFailed()
                            true
                        }
                        videoViewRef.value = this
                        setVideoURI(Uri.parse(SPLASH_VIDEO_URL))
                    }
                },
                update = { view ->
                    if (!playbackFailed && !view.isPlaying) {
                        // VideoView starts when its asynchronous prepare callback fires.
                        // Avoid calling start() here, which can race with preparation.
                    }
                }
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xB808070B),
                            Color(0x0008070B),
                            Color(0x1508070B),
                            Color(0xF208070B)
                        )
                    )
                )
        )

        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .padding(start = 24.dp, top = 52.dp, end = 24.dp)
        ) {
            Text(
                text = "MangaBuff",
                fontSize = 36.sp,
                lineHeight = 40.sp,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "AUTO",
                fontSize = 17.sp,
                letterSpacing = 5.sp,
                color = Color(0xFFFF4657),
                fontWeight = FontWeight.ExtraBold
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "Автоматизация. Комфорт. Больше времени на любимое.",
                fontSize = 14.sp,
                lineHeight = 20.sp,
                color = Color.White.copy(alpha = 0.9f),
                fontWeight = FontWeight.Medium
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, bottom = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = if (playbackFailed) {
                    "Анимация недоступна"
                } else {
                    "Загружаем мир автоматизации…"
                },
                fontSize = 14.sp,
                color = Color.White.copy(alpha = 0.95f),
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(14.dp))
            if (playbackFailed) {
                Box(
                    modifier = Modifier
                        .width(120.dp)
                        .height(3.dp)
                        .background(Color(0xFFFF4657), RoundedCornerShape(4.dp))
                )
            } else {
                CircularProgressIndicator(
                    modifier = Modifier.size(26.dp),
                    color = Color(0xFFFF4657),
                    strokeWidth = 2.dp
                )
            }
        }
    }
}
