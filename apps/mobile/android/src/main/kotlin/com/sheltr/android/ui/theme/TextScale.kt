package com.sheltr.android.ui.theme

import com.sheltr.shared.settings.TextScale

/**
 * The font-scale multiplier [SheltrTheme] applies for each [TextScale] setting, layered on top of (not replacing)
 * the OS accessibility font scale already in [androidx.compose.ui.platform.LocalDensity]. This is a presentation
 * detail, not a domain concern, so it lives in the Android UI layer rather than alongside [TextScale] itself.
 */
val TextScale.multiplier: Float
    get() = when (this) {
        TextScale.NORMAL -> 1.0f
        TextScale.LARGE -> 1.25f
        TextScale.EXTRA_LARGE -> 1.6f
    }
