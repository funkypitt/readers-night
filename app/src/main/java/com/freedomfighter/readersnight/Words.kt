package com.freedomfighter.readersnight

import android.content.Context
import com.freedomfighter.readersnight.data.ThemeMode

/** What the filter does, in a few words: "grayscale · warm tint · dimmed". */
fun summary(context: Context, o: Options): String {
    if (o.nothing) return context.getString(R.string.detail_nothing)
    return listOfNotNull(
        if (o.gray) context.getString(R.string.part_gray) else null,
        if (o.warm) context.getString(R.string.part_warm) else null,
        if (o.dim > 0) context.getString(R.string.part_dim) else null
    ).joinToString(" · ")
}

/** The dimming in one word: "off", "light", "medium" or "strong". */
fun dimWord(context: Context, dim: Int): String = context.getString(when {
    dim <= 0 -> R.string.off
    dim <= Filter.DIM_LIGHT -> R.string.dim_light
    dim <= Filter.DIM_MEDIUM -> R.string.dim_medium
    else -> R.string.dim_strong
})

/** The name of a theme, as the settings row and the menu show it. */
fun themeName(context: Context, m: ThemeMode): String = context.getString(when (m) {
    ThemeMode.DARK -> R.string.theme_dark; ThemeMode.LIGHT -> R.string.theme_light; ThemeMode.SYSTEM -> R.string.theme_system
    ThemeMode.LAVANDE -> R.string.theme_lavande; ThemeMode.SAUGE -> R.string.theme_sauge; ThemeMode.NORDIQUE -> R.string.theme_nordique
    ThemeMode.TERRACOTTA -> R.string.theme_terracotta; ThemeMode.COQUETTE -> R.string.theme_coquette; ThemeMode.MILITANTE -> R.string.theme_militante
    ThemeMode.TORRIDE -> R.string.theme_torride; ThemeMode.NATURE -> R.string.theme_nature
})
