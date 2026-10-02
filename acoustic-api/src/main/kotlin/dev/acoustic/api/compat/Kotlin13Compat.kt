package dev.acoustic.api.compat

import java.util.Locale

@Suppress("DEPRECATION")
internal fun String.lowercaseCompat(locale: Locale): String = toLowerCase(locale)
