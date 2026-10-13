package com.example.myapplication

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun KeenySplash(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.background(Color(0xFF07070D)),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(
                Brush.radialGradient(
                    colors = listOf(Color(0xFF30102D), Color(0xFF11101E), Color(0xFF050509)),
                    center = Offset(size.width * .5f, size.height * .43f),
                    radius = size.maxDimension * .78f
                )
            )
            // Anime-style speed slashes behind the title; no external artwork or image.
            val slashColor = Color(0x55FF315B)
            for (i in 0..8) {
                val y = size.height * (.25f + i * .065f)
                val path = Path().apply {
                    moveTo(size.width * .08f, y + 24f)
                    lineTo(size.width * .42f, y - 34f)
                }
                drawPath(path, slashColor, style = Stroke(width = 2.5f))
                val opposite = Path().apply {
                    moveTo(size.width * .61f, y + 35f)
                    lineTo(size.width * .94f, y - 22f)
                }
                drawPath(opposite, Color(0x447A65FF), style = Stroke(width = 2f))
            }
            drawCircle(
                color = Color(0x33FF2458),
                radius = size.minDimension * .31f,
                center = Offset(size.width * .5f, size.height * .44f)
            )
        }

        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.weight(1f))
            Text(
                text = "KEENY",
                color = Color(0xFFFFF5FB),
                fontSize = 54.sp,
                fontWeight = FontWeight.Black,
                fontStyle = FontStyle.Italic,
                fontFamily = FontFamily.SansSerif,
                letterSpacing = 5.sp,
                style = TextStyle(
                    shadow = Shadow(
                        color = Color(0xFFFF174F),
                        offset = Offset(0f, 0f),
                        blurRadius = 30f
                    )
                )
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "キ ー ニ ー",
                color = Color(0xFFFF5479),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 4.sp
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "ANIME • ENERGY • KEENY",
                color = Color(0xFFC7B9D4),
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 2.sp
            )
            Spacer(Modifier.weight(0.8f))
        }
    }
}
