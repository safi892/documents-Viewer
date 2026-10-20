package com.secure.reader.core.util

import java.util.Locale

/** 1_482_393 -> "1.4 MB". Compact, deterministic, locale-stable. */
fun formatFileSize(bytes: Long): String = when {
    bytes <= 0L -> "0 B"
    bytes < 1024 -> "$bytes B"
    else -> {
        val units = arrayOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble() / 1024.0
        var unit = 0
        while (value >= 1024.0 && unit < units.lastIndex) {
            value /= 1024.0
            unit++
        }
        String.format(Locale.US, "%.1f %s", value, units[unit])
    }
}

