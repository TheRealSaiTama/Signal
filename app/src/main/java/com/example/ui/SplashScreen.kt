package com.example.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

@Composable
fun SplashScreen(onSplashFinished: () -> Unit) {
    val alpha = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        // Smoothly fade in the Logo over 1200 milliseconds
        alpha.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 1200)
        )
        // Maintain presentation level briefly to align visual pacing perfectly
        delay(800)
        onSplashFinished()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF050505)),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(id = com.example.R.drawable.app_logo),
            contentDescription = "Signal App Logo",
            modifier = Modifier
                .size(260.dp)
                .alpha(alpha.value)
        )
    }
}
