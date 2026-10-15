package com.gemmail.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val GemmailColorScheme = lightColorScheme(
    primary = Color(0xFF1A73E8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD2E3FC),
    onPrimaryContainer = Color(0xFF041E49),
    secondary = Color(0xFF188038),
    onSecondary = Color.White,
    background = Color(0xFFF8FAFD),
    surface = Color.White
)

@Composable
fun GemmailTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = GemmailColorScheme,
        content = content
    )
}
