package com.example.myapplication

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

private val SplashNight = Color(0xFF070B16)
private val SplashNavy = Color(0xFF101831)
private val SplashOrange = Color(0xFFFF982B)
private val SplashCyan = Color(0xFF5CE8FF)
private val SplashWhite = Color(0xFFF6F7FF)

/**
 * Original Compose splash for MangaBuff. It uses custom-drawn manga panels,
 * a pulsing book-cat emblem, animated particles and a short synthesized intro chime.
 * No network access or external media file is required.
 */
@Composable
fun MangaBuffSplash(
    modifier: Modifier = Modifier,
    onPlaybackStarted: () -> Unit
) {
    val transition = rememberInfiniteTransition(label = "mangabuff-splash")
    val ringRotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(13_000, easing = LinearEasing)),
        label = "ring-rotation"
    )
    val pulse by transition.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.045f,
        animationSpec = infiniteRepeatable(
            tween(1_350, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "mascot-pulse"
    )
    val particlePhase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(7_000, easing = LinearEasing)),
        label = "particle-phase"
    )
    val progress = remember { Animatable(0.04f) }
    var contentVisible by remember { mutableStateOf(false) }
    var soundEnabled by remember { mutableStateOf(true) }
    var introTrack by remember { mutableStateOf<AudioTrack?>(null) }
    val particles = remember {
        val random = Random(2026)
        List(56) {
            SplashParticle(
                x = random.nextFloat(),
                y = random.nextFloat(),
                radius = 0.8f + random.nextFloat() * 2.0f,
                phase = random.nextFloat(),
                warm = random.nextBoolean()
            )
        }
    }

    LaunchedEffect(Unit) {
        contentVisible = true
        onPlaybackStarted()
        progress.animateTo(
            targetValue = 0.94f,
            animationSpec = tween(durationMillis = 4_700, easing = FastOutSlowInEasing)
        )
    }

    LaunchedEffect(soundEnabled) {
        val previousTrack = introTrack
        introTrack = null
        if (previousTrack != null) {
            runCatching { previousTrack.pause() }
            runCatching { previousTrack.flush() }
            runCatching { previousTrack.stop() }
            runCatching { previousTrack.release() }
        }
        if (soundEnabled) {
            val track = withContext(Dispatchers.IO) { MangaBuffIntroAudio.createTrack() }
            introTrack = track
            if (track != null) {
                runCatching { track.play() }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            introTrack?.let { track ->
                runCatching { track.pause() }
                runCatching { track.stop() }
                runCatching { track.release() }
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(SplashNight)
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(Color(0xFF050812), Color(0xFF11182E), Color(0xFF080B16)),
                    startY = 0f,
                    endY = h
                )
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0x44FF8D2B), Color(0x14FF8D2B), Color.Transparent),
                    center = Offset(w * 0.5f, h * 0.43f),
                    radius = w * 0.78f
                ),
                radius = w * 0.78f,
                center = Offset(w * 0.5f, h * 0.43f)
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0x1D42CFFF), Color.Transparent),
                    center = Offset(w * 0.10f, h * 0.16f),
                    radius = w * 0.58f
                ),
                radius = w * 0.58f,
                center = Offset(w * 0.10f, h * 0.16f)
            )

            // Subtle halftone dots, like ink texture on manga paper.
            for (row in 0..29) {
                for (column in 0..4) {
                    val y = h * 0.12f + row * h * 0.021f
                    val leftX = w * 0.035f + column * w * 0.024f
                    val rightX = w * 0.84f + column * w * 0.024f
                    val alpha = (0.035f + (row % 4) * 0.006f)
                    drawCircle(Color.White.copy(alpha = alpha), 1.2.dp.toPx(), Offset(leftX, y))
                    drawCircle(Color.White.copy(alpha = alpha), 1.2.dp.toPx(), Offset(rightX, y))
                }
            }

            // Angled comic panels stay near the edges so the title remains legible.
            rotate(-13f, pivot = Offset(w * 0.09f, h * 0.25f)) {
                drawRoundRect(
                    color = SplashCyan.copy(alpha = 0.22f),
                    topLeft = Offset(-w * 0.07f, h * 0.16f),
                    size = Size(w * 0.30f, h * 0.13f),
                    cornerRadius = CornerRadius(12.dp.toPx()),
                    style = Stroke(1.4.dp.toPx())
                )
                drawLine(
                    color = Color.White.copy(alpha = 0.09f),
                    start = Offset(-w * 0.02f, h * 0.19f),
                    end = Offset(w * 0.17f, h * 0.26f),
                    strokeWidth = 1.dp.toPx()
                )
                drawLine(
                    color = SplashOrange.copy(alpha = 0.18f),
                    start = Offset(0f, h * 0.27f),
                    end = Offset(w * 0.18f, h * 0.22f),
                    strokeWidth = 2.dp.toPx()
                )
            }
            rotate(14f, pivot = Offset(w * 0.91f, h * 0.57f)) {
                drawRoundRect(
                    color = SplashOrange.copy(alpha = 0.23f),
                    topLeft = Offset(w * 0.80f, h * 0.48f),
                    size = Size(w * 0.31f, h * 0.16f),
                    cornerRadius = CornerRadius(12.dp.toPx()),
                    style = Stroke(1.5.dp.toPx())
                )
                drawLine(
                    color = Color.White.copy(alpha = 0.10f),
                    start = Offset(w * 0.84f, h * 0.52f),
                    end = Offset(w * 1.08f, h * 0.58f),
                    strokeWidth = 1.dp.toPx()
                )
            }

            // Speed lines add movement without flashing the screen.
            for (i in 0..17) {
                val fraction = (i / 18f + particlePhase * 0.33f) % 1f
                val y = h * (0.17f + fraction * 0.59f)
                val length = w * (0.035f + (i % 4) * 0.018f)
                val x = if (i % 2 == 0) w * 0.025f else w * 0.975f
                val direction = if (i % 2 == 0) 1f else -1f
                drawLine(
                    color = (if (i % 3 == 0) SplashOrange else SplashCyan)
                        .copy(alpha = 0.12f + 0.08f * sin((fraction * PI.toFloat()).toDouble()).toFloat()),
                    start = Offset(x, y),
                    end = Offset(x + length * direction, y - length * 0.26f),
                    strokeWidth = (i % 3 + 1).dp.toPx(),
                    cap = StrokeCap.Round
                )
            }

            particles.forEachIndexed { index, p ->
                val drift = sin(((particlePhase + p.phase) * 2f * PI.toFloat()).toDouble()).toFloat()
                val x = p.x * w + drift * w * 0.012f
                val y = (p.y + particlePhase * (0.05f + p.phase * 0.08f)) % 1f * h
                val color = if (p.warm) SplashOrange else SplashCyan
                val alpha = 0.16f + (0.24f * (0.5f + 0.5f * sin(((particlePhase + p.phase) * 2f * PI.toFloat()).toDouble()).toFloat()))
                drawCircle(color.copy(alpha = alpha), p.radius.dp.toPx(), Offset(x, y))
                if (index % 9 == 0) {
                    drawLine(
                        color.copy(alpha = alpha * 0.60f),
                        Offset(x - 4.dp.toPx(), y),
                        Offset(x + 4.dp.toPx(), y),
                        strokeWidth = 1.dp.toPx()
                    )
                    drawLine(
                        color.copy(alpha = alpha * 0.60f),
                        Offset(x, y - 4.dp.toPx()),
                        Offset(x, y + 4.dp.toPx()),
                        strokeWidth = 1.dp.toPx()
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 27.dp, vertical = 30.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Surface(
                    color = Color(0xAA11182A),
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.border(
                        1.dp,
                        Brush.horizontalGradient(listOf(SplashCyan.copy(alpha = 0.40f), SplashOrange.copy(alpha = 0.40f))),
                        RoundedCornerShape(50)
                    )
                ) {
                    Text(
                        "MANGABUFF  •  AUTO",
                        modifier = Modifier.padding(horizontal = 13.dp, vertical = 9.dp),
                        color = Color(0xFFC6D0EA),
                        fontSize = 10.sp,
                        letterSpacing = 1.4.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Surface(
                    modifier = Modifier
                        .size(44.dp)
                        .border(
                            1.dp,
                            Brush.linearGradient(listOf(SplashCyan, SplashOrange)),
                            CircleShape
                        ),
                    shape = CircleShape,
                    color = Color(0xD910172A)
                ) {
                    IconButton(
                        onClick = { soundEnabled = !soundEnabled },
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Icon(
                            imageVector = if (soundEnabled) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                            contentDescription = if (soundEnabled) "Выключить звук заставки" else "Включить звук заставки",
                            tint = if (soundEnabled) SplashCyan else Color(0xFF8994B3),
                            modifier = Modifier.size(21.dp)
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = contentVisible,
                enter = fadeIn(tween(900)) + slideInVertically(
                    animationSpec = tween(750, easing = FastOutSlowInEasing),
                    initialOffsetY = { it / 18 }
                ),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(63.dp)
                                .clip(RoundedCornerShape(20.dp))
                                .background(
                                    Brush.linearGradient(
                                        colors = listOf(SplashOrange, Color(0xFFFFC15C), Color(0xFFFF772A))
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Book,
                                contentDescription = null,
                                tint = Color(0xFF11182A),
                                modifier = Modifier.size(37.dp)
                            )
                        }
                        Spacer(Modifier.size(14.dp))
                        Column(horizontalAlignment = Alignment.Start) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "MANGA",
                                    fontSize = 32.sp,
                                    lineHeight = 34.sp,
                                    letterSpacing = 1.0.sp,
                                    color = SplashWhite,
                                    fontWeight = FontWeight.Black
                                )
                            }
                            Text(
                                "BUFF",
                                fontSize = 32.sp,
                                lineHeight = 33.sp,
                                letterSpacing = 3.2.sp,
                                color = SplashOrange,
                                fontWeight = FontWeight.Black
                            )
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Больше, чем просто манга",
                        color = Color(0xFFD1D8EB),
                        fontSize = 14.sp,
                        letterSpacing = 0.15.sp,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(17.dp))

                    MascotPortal(
                        modifier = Modifier
                            .size(222.dp)
                            .scale(pulse),
                        rotation = ringRotation
                    )

                    Spacer(Modifier.height(13.dp))
                    Text(
                        "ЧИТАЙ  •  ОТКРЫВАЙ  •  ПОГРУЖАЙСЯ",
                        color = SplashCyan.copy(alpha = 0.92f),
                        fontSize = 10.sp,
                        letterSpacing = 1.6.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "ЗАГРУЖАЕМ ТВОЙ МИР МАНГИ",
                    color = Color(0xFFCAD3E9),
                    fontSize = 11.sp,
                    letterSpacing = 1.6.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(14.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.76f)
                        .height(6.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF252D48))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progress.value)
                            .fillMaxHeight()
                            .clip(CircleShape)
                            .background(
                                Brush.horizontalGradient(
                                    colors = listOf(SplashCyan, Color(0xFF7C9CFF), SplashOrange)
                                )
                            )
                    )
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    "MangaBuff Auto  •  Background",
                    color = Color(0xFF7986A8),
                    fontSize = 10.sp,
                    letterSpacing = 0.4.sp,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

private data class SplashParticle(
    val x: Float,
    val y: Float,
    val radius: Float,
    val phase: Float,
    val warm: Boolean
)

@Composable
private fun MascotPortal(
    modifier: Modifier,
    rotation: Float
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val center = Offset(w / 2f, h / 2f)
        val radius = min(w, h) * 0.46f

        drawCircle(
            brush = Brush.radialGradient(
                listOf(Color(0x55FF982B), Color(0x1BFF982B), Color.Transparent),
                center = center,
                radius = radius * 1.14f
            ),
            radius = radius * 1.14f,
            center = center
        )

        rotate(rotation, pivot = center) {
            drawCircle(
                color = SplashCyan.copy(alpha = 0.44f),
                radius = radius * 0.90f,
                center = center,
                style = Stroke(width = 1.3.dp.toPx())
            )
            drawArc(
                color = SplashOrange.copy(alpha = 0.93f),
                startAngle = 205f,
                sweepAngle = 108f,
                useCenter = false,
                topLeft = Offset(center.x - radius, center.y - radius),
                size = Size(radius * 2f, radius * 2f),
                style = Stroke(width = 3.2.dp.toPx(), cap = StrokeCap.Round)
            )
            drawArc(
                color = SplashCyan.copy(alpha = 0.85f),
                startAngle = 25f,
                sweepAngle = 54f,
                useCenter = false,
                topLeft = Offset(center.x - radius * 0.98f, center.y - radius * 0.98f),
                size = Size(radius * 1.96f, radius * 1.96f),
                style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round)
            )
            for (i in 0..7) {
                val angle = (i * 45f + rotation) * PI.toFloat() / 180f
                val dotCenter = Offset(
                    center.x + kotlin.math.cos(angle.toDouble()).toFloat() * radius * 1.02f,
                    center.y + kotlin.math.sin(angle.toDouble()).toFloat() * radius * 1.02f
                )
                drawCircle(
                    color = if (i % 2 == 0) SplashOrange else SplashCyan,
                    radius = if (i % 2 == 0) 2.2.dp.toPx() else 1.5.dp.toPx(),
                    center = dotCenter
                )
            }
        }

        // A little cat-mascot drawn from vector shapes, inside the glowing circle.
        val head = Path().apply {
            moveTo(center.x - w * 0.22f, center.y - h * 0.04f)
            lineTo(center.x - w * 0.25f, center.y - h * 0.28f)
            lineTo(center.x - w * 0.07f, center.y - h * 0.19f)
            quadraticTo(center.x, center.y - h * 0.24f, center.x + w * 0.07f, center.y - h * 0.19f)
            lineTo(center.x + w * 0.25f, center.y - h * 0.28f)
            lineTo(center.x + w * 0.22f, center.y - h * 0.04f)
            quadraticTo(center.x + w * 0.20f, center.y + h * 0.19f, center.x, center.y + h * 0.19f)
            quadraticTo(center.x - w * 0.20f, center.y + h * 0.19f, center.x - w * 0.22f, center.y - h * 0.04f)
            close()
        }
        drawPath(
            path = head,
            brush = Brush.linearGradient(
                colors = listOf(Color(0xFF1B2947), Color(0xFF0B1020)),
                start = Offset(center.x, center.y - h * 0.24f),
                end = Offset(center.x, center.y + h * 0.19f)
            )
        )
        drawPath(
            path = head,
            color = SplashOrange.copy(alpha = 0.98f),
            style = Stroke(width = 2.4.dp.toPx())
        )

        // Tiny inner ear triangles.
        val leftEar = Path().apply {
            moveTo(center.x - w * 0.205f, center.y - h * 0.105f)
            lineTo(center.x - w * 0.215f, center.y - h * 0.225f)
            lineTo(center.x - w * 0.115f, center.y - h * 0.167f)
            close()
        }
        val rightEar = Path().apply {
            moveTo(center.x + w * 0.205f, center.y - h * 0.105f)
            lineTo(center.x + w * 0.215f, center.y - h * 0.225f)
            lineTo(center.x + w * 0.115f, center.y - h * 0.167f)
            close()
        }
        drawPath(leftEar, SplashOrange.copy(alpha = 0.85f))
        drawPath(rightEar, SplashOrange.copy(alpha = 0.85f))

        drawCircle(SplashWhite, 3.2.dp.toPx(), Offset(center.x - w * 0.085f, center.y - h * 0.005f))
        drawCircle(SplashWhite, 3.2.dp.toPx(), Offset(center.x + w * 0.085f, center.y - h * 0.005f))
        drawCircle(SplashOrange, 1.8.dp.toPx(), Offset(center.x, center.y + h * 0.055f))
        drawArc(
            color = SplashWhite.copy(alpha = 0.9f),
            startAngle = 15f,
            sweepAngle = 150f,
            useCenter = false,
            topLeft = Offset(center.x - w * 0.055f, center.y + h * 0.045f),
            size = Size(w * 0.11f, h * 0.075f),
            style = Stroke(width = 1.7.dp.toPx(), cap = StrokeCap.Round)
        )

        // Open-book outline under the mascot.
        val book = Path().apply {
            moveTo(center.x, center.y + h * 0.29f)
            quadraticTo(center.x - w * 0.15f, center.y + h * 0.22f, center.x - w * 0.25f, center.y + h * 0.27f)
            lineTo(center.x - w * 0.25f, center.y + h * 0.42f)
            quadraticTo(center.x - w * 0.12f, center.y + h * 0.37f, center.x, center.y + h * 0.43f)
            moveTo(center.x, center.y + h * 0.29f)
            quadraticTo(center.x + w * 0.15f, center.y + h * 0.22f, center.x + w * 0.25f, center.y + h * 0.27f)
            lineTo(center.x + w * 0.25f, center.y + h * 0.42f)
            quadraticTo(center.x + w * 0.12f, center.y + h * 0.37f, center.x, center.y + h * 0.43f)
        }
        drawPath(
            path = book,
            color = SplashCyan.copy(alpha = 0.95f),
            style = Stroke(width = 2.4.dp.toPx(), cap = StrokeCap.Round)
        )
        drawLine(
            color = SplashOrange.copy(alpha = 0.7f),
            start = Offset(center.x, center.y + h * 0.29f),
            end = Offset(center.x, center.y + h * 0.43f),
            strokeWidth = 1.4.dp.toPx()
        )

        // Sparkles.
        val sparkles = listOf(
            Offset(center.x - w * 0.34f, center.y - h * 0.10f),
            Offset(center.x + w * 0.35f, center.y + h * 0.06f),
            Offset(center.x + w * 0.26f, center.y - h * 0.36f),
            Offset(center.x - w * 0.29f, center.y + h * 0.35f)
        )
        sparkles.forEachIndexed { index, point ->
            val color = if (index % 2 == 0) SplashOrange else SplashCyan
            drawLine(color.copy(alpha = 0.85f), Offset(point.x - 4.dp.toPx(), point.y), Offset(point.x + 4.dp.toPx(), point.y), 1.5.dp.toPx())
            drawLine(color.copy(alpha = 0.85f), Offset(point.x, point.y - 4.dp.toPx()), Offset(point.x, point.y + 4.dp.toPx()), 1.5.dp.toPx())
        }
    }
}

private object MangaBuffIntroAudio {
    private data class Note(
        val start: Double,
        val frequency: Double,
        val length: Double,
        val gain: Double
    )

    /**
     * Synthesizes a short, soft electronic arpeggio locally. The sound is bundled
     * in code, so the splash remains fully offline and does not need audio files.
     */
    fun createTrack(): AudioTrack? {
        return try {
            val sampleRate = 22_050
            val durationSeconds = 2.15
            val sampleCount = (sampleRate * durationSeconds).toInt()
            val pcm = ByteArray(sampleCount * 2)
            val notes = arrayOf(
                Note(0.00, 392.00, 0.58, 0.52),
                Note(0.14, 523.25, 0.62, 0.48),
                Note(0.30, 659.25, 0.64, 0.45),
                Note(0.48, 783.99, 0.72, 0.42),
                Note(0.76, 1_046.50, 0.76, 0.35),
                Note(1.05, 1_318.51, 0.86, 0.28)
            )
            val twoPi = 2.0 * PI

            for (i in 0 until sampleCount) {
                val t = i.toDouble() / sampleRate
                var sample = 0.0
                notes.forEach { note ->
                    val age = t - note.start
                    if (age >= 0.0 && age <= note.length) {
                        val attack = (age / 0.018).coerceIn(0.0, 1.0)
                        val decay = exp(-3.4 * age / note.length)
                        val tone = sin(twoPi * note.frequency * age) +
                            0.19 * sin(twoPi * note.frequency * 2.0 * age)
                        sample += note.gain * attack * decay * tone
                    }
                }
                val whoosh = sin(twoPi * (92.0 * t - 7.5 * t * t)) * exp(-4.6 * t) * 0.12
                val fade = ((durationSeconds - t) / 0.22).coerceIn(0.0, 1.0)
                val value = ( ((sample + whoosh) * 0.34 * fade).coerceIn(-0.88, 0.88) * 32767.0).toInt()
                pcm[i * 2] = (value and 0xFF).toByte()
                pcm[i * 2 + 1] = ((value shr 8) and 0xFF).toByte()
            }

            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val audioFormat = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
            val track = AudioTrack.Builder()
                .setAudioAttributes(audioAttributes)
                .setAudioFormat(audioFormat)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size)
                .build()

            var writtenTotal = 0
            while (writtenTotal < pcm.size) {
                val written = track.write(
                    pcm,
                    writtenTotal,
                    pcm.size - writtenTotal,
                    AudioTrack.WRITE_BLOCKING
                )
                if (written <= 0) {
                    track.release()
                    return null
                }
                writtenTotal += written
            }
            track
        } catch (_: Throwable) {
            null
        }
    }
}
