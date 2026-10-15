package com.example.myapplication

import android.util.Log
import android.view.LayoutInflater
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.RawResourceDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

private const val SPLASH_TAG = "MangaBuffSplash"

/**
 * Full-screen splash video bundled in res/raw. Playback is offline.
 *
 * The intro is considered started only after ExoPlayer reports the first
 * rendered video frame, not merely when the media is prepared.
 */
@Composable
fun MangaBuffSplash(
    modifier: Modifier = Modifier,
    playbackFailed: Boolean,
    onPlaybackStarted: () -> Unit,
    onPlaybackFailed: () -> Unit
) {
    val appContext = LocalContext.current.applicationContext
    val player = remember(appContext) {
        ExoPlayer.Builder(appContext).build()
    }

    DisposableEffect(player) {
        var firstFrameReported = false
        var failureReported = false

        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                if (!firstFrameReported) {
                    firstFrameReported = true
                    Log.d(SPLASH_TAG, "First video frame rendered")
                    onPlaybackStarted()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e(
                    SPLASH_TAG,
                    "Bundled splash playback failed: ${error.errorCodeName}",
                    error
                )
                if (!failureReported) {
                    failureReported = true
                    onPlaybackFailed()
                }
            }
        }

        player.addListener(listener)
        player.repeatMode = Player.REPEAT_MODE_ONE
        player.volume = 0f
        player.setMediaItem(
            MediaItem.fromUri(
                RawResourceDataSource.buildRawResourceUri(R.raw.mangabuff_splash)
            )
        )
        player.prepare()
        player.playWhenReady = true

        onDispose {
            player.removeListener(listener)
            player.release()
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
                    (LayoutInflater.from(viewContext)
                        .inflate(R.layout.splash_player_view, null, false) as PlayerView).apply {
                        player = player
                    }
                },
                update = { playerView ->
                    if (playerView.player !== player) {
                        playerView.player = player
                    }
                }
            )
        }
    }
}
