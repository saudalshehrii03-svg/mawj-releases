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

    /** Switches the app language; activities are recreated with the right direction (RTL / LTR). */
    fun set(c: Context, english: Boolean) {
        en = english
        c.getSharedPreferences("lang", Context.MODE_PRIVATE).edit().putString("lang", if (english) "en" else "ar").apply()
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(if (english) "en" else "ar"))
    }
}
