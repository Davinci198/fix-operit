package com.ai.assistance.operit.ui.floating.ui.window.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun LoadingDotsIndicator(textColor: Color) {
    // Performanță: o singură animație infinită cu fază derivată, în loc de 3 animații separate.
    val infiniteTransition = rememberInfiniteTransition(label = "dots")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 600f,
        animationSpec =
        infiniteRepeatable(
            animation = tween(durationMillis = 600, easing = LinearEasing),
        ),
        label = "dots_phase",
    )
    Row(
        modifier = Modifier.padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val jumpHeight = -5f
        val animationDelay = 160
        (0..2).forEach { index ->
            val localPhase = (phase + index * animationDelay) % 600f
            val offsetY =
                when {
                    localPhase < 100f -> jumpHeight * (localPhase / 100f)
                    localPhase < 300f -> jumpHeight
                    localPhase < 500f -> jumpHeight * ((500f - localPhase) / 200f)
                    else -> 0f
                }
            Box(
                modifier =
                Modifier.size(6.dp)
                    .offset(y = offsetY.dp)
                    .background(
                        color = textColor.copy(alpha = 0.6f),
                        shape = CircleShape
                    )
            )
        }
    }
}
