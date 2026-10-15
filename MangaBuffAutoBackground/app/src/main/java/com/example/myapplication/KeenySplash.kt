package com.example.myapplication

import android.media.MediaPlayer
import android.net.Uri
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Full-screen splash using the MP4 bundled in res/raw.
 * Playback does not depend on network access and shows no fallback image or text.
 */
@Composable
fun MangaBuffSplash(
    modifier: Modifier = Modifier,
    playbackFailed: Boolean,
    onPlaybackStarted: () -> Unit,
    onPlaybackFailed: () -> Unit
) {
    val context = LocalContext.current
    val videoViewRef = remember { mutableStateOf<VideoView?>(null) }
    val failureReported = remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            videoViewRef.value?.let { view -> runCatching { view.stopPlayback() } }
            videoViewRef.value = null
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (!playbackFailed) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { viewContext ->
                    VideoView(viewContext).apply {
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
                            if (!failureReported.value) {
                                failureReported.value = true
                                onPlaybackFailed()
                            }
                            true
                        }
                        videoViewRef.value = this
                        val videoUri = Uri.parse(
                            "android.resource://${viewContext.packageName}/${R.raw.mangabuff_splash}"
                        )
                        setVideoURI(videoUri)
                    }
                },
                update = { view ->
                    // Playback starts from onPrepared; don't restart while recomposing.
                    if (view.tag == null) view.tag = true
                }
            )
        }
    }
}
