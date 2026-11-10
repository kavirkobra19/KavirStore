package com.kavir.store.ui

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColors = lightColorScheme(
    primary = Color(0xFF0B7A75),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB7F0EA),
    onPrimaryContainer = Color(0xFF00201E),
    secondary = Color(0xFF4A6361),
    secondaryContainer = Color(0xFFCCE8E5),
    background = Color(0xFFFBFDFC),
    surface = Color(0xFFFBFDFC),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF4DD0C4),
    onPrimary = Color(0xFF003733),
    primaryContainer = Color(0xFF00504B),
    onPrimaryContainer = Color(0xFFB7F0EA),
    secondary = Color(0xFFB0CCC9),
    secondaryContainer = Color(0xFF324B49),
    background = Color(0xFF121414),
    surface = Color(0xFF121414),
)

@Composable
fun KavirTheme(mode: String, content: @Composable () -> Unit) {
    val dark = when (mode) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    val view = LocalView.current
    SideEffect {
        val window = (view.context as? Activity)?.window
        if (window != null) {
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !dark
            controller.isAppearanceLightNavigationBars = !dark
        }
    }
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, content = content)
}
