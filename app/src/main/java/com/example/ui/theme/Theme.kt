package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private val WhatacallDarkColorScheme = darkColorScheme(
    primary = DarkGreenPrimary,
    onPrimary = Color(0xFF003919),
    primaryContainer = DarkGreenContainer,
    onPrimaryContainer = DarkOnGreenContainer,
    secondary = WhatacallTealAccent,
    onSecondary = Color(0xFF00201C),
    secondaryContainer = Color(0xFF173832),
    onSecondaryContainer = Color(0xFFB5EFE5),
    background = DarkBackground,
    onBackground = DarkOnSurface,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    surfaceContainerLow = DarkSurface,
    surfaceContainer = DarkSurfaceElevated,
    surfaceContainerHigh = DarkSurfaceVariant,
    outline = DarkOutline,
    outlineVariant = Color(0xFF202E36),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF420000)
)

private val WhatacallLightColorScheme = lightColorScheme(
    primary = WhatacallGreen,
    onPrimary = Color.White,
    primaryContainer = WhatacallGreenContainer,
    onPrimaryContainer = WhatacallOnGreenContainer,
    secondary = WhatacallTealAccent,
    onSecondary = Color.White,
    secondaryContainer = WhatacallTealContainer,
    onSecondaryContainer = WhatacallOnTealContainer,
    background = LightBackground,
    onBackground = LightOnSurface,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    surfaceContainerLow = Color(0xFFF3F8F4),
    surfaceContainer = Color(0xFFECF4EE),
    surfaceContainerHigh = Color(0xFFE3EFE7),
    outline = LightOutline,
    outlineVariant = Color(0xFFE3ECE6),
    error = CallRejectRed,
    onError = Color.White
)

val WhatacallShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun WhatacallTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> WhatacallDarkColorScheme
        else -> WhatacallLightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = WhatacallShapes,
        content = content
    )
}
