package com.mawj.editor

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/** Tiny bilingual helper: every UI string is written as t("عربي", "English"). */
object L {
    @Volatile var en: Boolean = false
        private set

    fun t(ar: String, en: String): String = if (this.en) en else ar

    /** Reads the per-app language chosen in Settings (Arabic by default). */
    fun init(c: Context) {
        val tags = AppCompatDelegate.getApplicationLocales().toLanguageTags()
        en = if (tags.isNotEmpty()) tags.startsWith("en")
        else c.getSharedPreferences("lang", Context.MODE_PRIVATE).getString("lang", "ar") == "en"
    }

    // ---------- Appearance (follow system / light / dark) ----------

    private fun ui(c: Context) = c.getSharedPreferences("ui", Context.MODE_PRIVATE)

    /** 0 = follow the device, 1 = light, 2 = dark. */
    fun theme(c: Context): Int = ui(c).getInt("theme", 0)

    fun applyTheme(c: Context, mode: Int = theme(c)) {
        ui(c).edit().putInt("theme", mode).apply()
        AppCompatDelegate.setDefaultNightMode(
            when (mode) {
                1 -> AppCompatDelegate.MODE_NIGHT_NO
                2 -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }

    /** Switches the app language; activities are recreated with the right direction (RTL / LTR). */
    fun set(c: Context, english: Boolean) {
        en = english
        c.getSharedPreferences("lang", Context.MODE_PRIVATE).edit().putString("lang", if (english) "en" else "ar").apply()
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(if (english) "en" else "ar"))
    }
}
