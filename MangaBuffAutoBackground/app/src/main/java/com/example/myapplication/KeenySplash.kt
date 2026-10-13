package com.example.myapplication

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun KeenySplash(modifier: Modifier = Modifier) {
    Box(modifier.background(Color(0xFF050609)), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Brush.radialGradient(
                colors = listOf(Color(0xFF351018), Color(0xFF100A10), Color(0xFF050609)),
                center = Offset(size.width * .5f, size.height * .38f),
                radius = size.maxDimension * .78f
            ))
            val unit = (size.width / 360f).coerceAtMost(size.height * .46f / 420f)
            translate((size.width - 360f * unit) / 2f, size.height * .06f) {
                scale(unit, unit, pivot = Offset.Zero) {
                    drawCircle(
                        Brush.radialGradient(listOf(Color(0xFF6D2630), Color(0xFF24131B), Color.Transparent),
                            center = Offset(180f, 200f), radius = 205f),
                        205f, Offset(180f, 200f)
                    )
                    val face = Path().apply {
                        moveTo(48f,103f); lineTo(25f,28f); lineTo(94f,69f)
                        quadraticTo(180f,26f,266f,69f); lineTo(335f,28f); lineTo(312f,103f)
                        lineTo(300f,245f); quadraticTo(286f,337f,180f,386f)
                        quadraticTo(74f,337f,60f,245f); close()
                    }
                    drawPath(face, Brush.linearGradient(listOf(Color(0xFF3C2229),Color(0xFF100E14),Color(0xFF4A1822))))
                    drawPath(face, Color(0xFFB64A52), style = Stroke(3f))
                    val browL = Path().apply { moveTo(63f,132f); lineTo(155f,151f); lineTo(133f,179f); lineTo(70f,166f); close() }
                    val browR = Path().apply { moveTo(297f,132f); lineTo(205f,151f); lineTo(227f,179f); lineTo(290f,166f); close() }
                    drawPath(browL, Color(0xFF08080C)); drawPath(browR, Color(0xFF08080C))
                    drawOval(Brush.radialGradient(listOf(Color(0xFFFFE9D1),Color(0xFFFF4D38),Color.Transparent)), Offset(83f,157f), Size(76f,30f))
                    drawOval(Brush.radialGradient(listOf(Color(0xFFFFE9D1),Color(0xFFFF4D38),Color.Transparent)), Offset(201f,157f), Size(76f,30f))
                    drawOval(Color(0xFF09090D), Offset(107f,163f), Size(28f,14f))
                    drawOval(Color(0xFF09090D), Offset(225f,163f), Size(28f,14f))
                    val nose = Path().apply { moveTo(162f,169f); lineTo(146f,220f); lineTo(180f,237f); lineTo(214f,220f); lineTo(198f,169f); lineTo(180f,198f); close() }
                    drawPath(nose, Color(0xFF171016))
                    drawCircle(Color(0xFF050609),7f,Offset(161f,224f)); drawCircle(Color(0xFF050609),7f,Offset(199f,224f))
                    val mouth = Path().apply {
                        moveTo(83f,238f); quadraticTo(180f,268f,277f,238f); lineTo(259f,315f)
                        quadraticTo(180f,360f,101f,315f); close()
                    }
                    drawPath(mouth, Color(0xFF020204)); drawPath(mouth, Color(0xFFB83B49), style = Stroke(4f))
                    val fangs = Path().apply {
                        moveTo(103f,249f); lineTo(124f,254f); lineTo(131f,284f); close()
                        moveTo(139f,257f); lineTo(158f,260f); lineTo(161f,288f); close()
                        moveTo(199f,260f); lineTo(221f,257f); lineTo(199f,289f); close()
                        moveTo(236f,254f); lineTo(257f,249f); lineTo(229f,284f); close()
                        moveTo(119f,326f); lineTo(142f,328f); lineTo(151f,300f); close()
                        moveTo(161f,337f); lineTo(181f,339f); lineTo(181f,307f); close()
                        moveTo(201f,337f); lineTo(221f,328f); lineTo(209f,300f); close()
                        moveTo(232f,326f); lineTo(247f,317f); lineTo(228f,296f); close()
                    }
                    drawPath(fangs, Color(0xFFFFDCC5))
                    drawLine(Color(0xFFCE4B53), Offset(72f,203f), Offset(111f,216f), 3f)
                    drawLine(Color(0xFFCE4B53), Offset(288f,203f), Offset(249f,216f), 3f)
                }
            }
        }
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.weight(1f))
            Text("KEENY", color = Color(0xFFF4E9E7), fontSize = 48.sp,
                fontWeight = FontWeight.Black, fontFamily = FontFamily.SansSerif,
                letterSpacing = 8.sp, style = TextStyle(shadow = Shadow(
                    color = Color(0xFFE33343), offset = Offset.Zero, blurRadius = 24f)))
            Spacer(Modifier.height(10.dp))
            Text("DARK • SHARP • KEENY", color = Color(0xFFB7A3A6),
                fontSize = 11.sp, letterSpacing = 3.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(.7f))
        }
    }
}
