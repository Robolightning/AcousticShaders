package dev.acoustic.core.compat

import java.util.Locale

@Suppress("DEPRECATION")
internal fun String.lowercaseCompat(locale: Locale): String = toLowerCase(locale)

@Suppress("DEPRECATION")
internal fun String.uppercaseCompat(locale: Locale): String = toUpperCase(locale)

@Suppress("DEPRECATION")
internal fun Char.codeCompat(): Int = toInt()
