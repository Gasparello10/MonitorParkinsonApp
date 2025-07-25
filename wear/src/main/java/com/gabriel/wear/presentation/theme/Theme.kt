package com.gabriel.wear.presentation.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material.Colors
import androidx.wear.compose.material.MaterialTheme

// MUDANÇA 1: Definimos nossa paleta de cores personalizada para o modo escuro.
private val wearColorPalette: Colors = Colors(
    primary = Teal200,
    primaryVariant = Teal200,
    secondary = Color.White,
    error = Red400,
    onPrimary = Color.Black,
    onSecondary = Color.Black,
    onError = Color.Black,
    background = Color.Black,      // <-- A COR DE FUNDO AGORA É PRETA
    onBackground = Color.White,
    surface = DarkSurface,         // Cor para cards e outros elementos de superfície
    onSurface = Color.White,
    onSurfaceVariant = Color.Gray
)


@Composable
fun MonitorParkinsonAppTheme(
    content: @Composable () -> Unit
) {
    // MUDANÇA 2: Aplicamos nossa paleta de cores ao MaterialTheme.
    MaterialTheme(
        colors = wearColorPalette,
        content = content
    )
}
