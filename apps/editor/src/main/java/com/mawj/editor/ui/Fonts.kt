package com.mawj.editor.ui

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.io.File
import java.util.UUID

/** User-imported fonts (TTF / OTF) for the app UI and for on-video text. */
object Fonts {

    /** MIME types the system picker should offer for font files. */
    val PICK_TYPES = arrayOf("font/ttf", "font/otf", "font/*", "application/x-font-ttf", "application/x-font-otf", "application/font-sfnt", "application/octet-stream")

    private val cache = HashMap<String, Typeface?>()
    private var appFace: Typeface? = null
    private var appFaceKey: String? = null

    fun dir(c: Context) = File(c.filesDir, "fonts").apply { mkdirs() }

    private fun prefs(c: Context) = c.getSharedPreferences("fonts", Context.MODE_PRIVATE)

    data class Font(val file: String, val name: String)

    fun list(c: Context): List<Font> = dir(c).listFiles()
        ?.filter { it.isFile && load(it) != null }
        ?.sortedBy { it.lastModified() }
        ?.map { Font(it.name, displayName(c, it.name)) } ?: emptyList()

    private fun displayName(c: Context, file: String) = prefs(c).getString("name_$file", null) ?: file.substringBeforeLast('.')

    fun load(f: File): Typeface? = synchronized(cache) {
        cache.getOrPut(f.absolutePath) {
            runCatching { Typeface.Builder(f).build() }.getOrNull()
        }
    }

    /** Copies a picked font into the app. Returns null if the file is not a valid font. */
    fun import(c: Context, uri: Uri): Font? {
        val cr = c.contentResolver
        val display = runCatching {
            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cur ->
                if (cur.moveToFirst()) cur.getString(0) else null
            }
        }.getOrNull() ?: "font.ttf"
        val ext = display.substringAfterLast('.', "ttf").lowercase().takeIf { it == "ttf" || it == "otf" || it == "ttc" } ?: "ttf"
        val name = UUID.randomUUID().toString().substring(0, 10) + "." + ext
        val out = File(dir(c), name)
        runCatching { cr.openInputStream(uri)!!.use { i -> out.outputStream().use { i.copyTo(it) } } }.onFailure { out.delete(); return null }
        // Typeface.Builder silently falls back to the default font for invalid files; validate explicitly.
        val ok = runCatching { Typeface.Builder(out).build(); android.graphics.fonts.Font.Builder(out).build() }.isSuccess
        if (!ok) { out.delete(); return null }
        prefs(c).edit().putString("name_$name", display.substringBeforeLast('.')).apply()
        return Font(name, display.substringBeforeLast('.'))
    }

    fun delete(c: Context, file: String) {
        File(dir(c), file).delete()
        if (appFontFile(c) == file) setAppFont(c, null)
    }

    // ---------- App UI font ----------

    fun appFontFile(c: Context): String? = prefs(c).getString("app_font", null)

    fun setAppFont(c: Context, file: String?) {
        prefs(c).edit().putString("app_font", file).apply()
        appFace = null; appFaceKey = null
    }

    fun appTypeface(c: Context): Typeface? {
        val f = appFontFile(c) ?: return null
        if (appFaceKey != f) {
            appFace = load(File(dir(c), f))
            appFaceKey = f
        }
        return appFace
    }

    /** Applies the chosen app font to every TextView under [root], keeping bold styles. */
    fun apply(root: View?) {
        root ?: return
        val face = appTypeface(root.context) ?: return
        fun walk(v: View) {
            if (v is TextView && v.tag != "keepFont") {
                val style = v.typeface?.style ?: Typeface.NORMAL
                v.typeface = Typeface.create(face, style)
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
    }
}
