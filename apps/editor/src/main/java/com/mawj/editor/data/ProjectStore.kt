package com.mawj.editor.data

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.mawj.editor.model.Project
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Stores each project as projects/<id>/project.json plus a private media/ folder. */
class ProjectStore(context: Context) {

    private val appContext = context.applicationContext
    val root = File(appContext.filesDir, "projects").apply { mkdirs() }

    fun dir(id: String) = File(root, id)
    fun mediaDir(id: String) = File(dir(id), "media").apply { mkdirs() }
    fun mediaFile(projectId: String, name: String) = File(mediaDir(projectId), name)
    private fun jsonFile(id: String) = File(dir(id), "project.json")

    fun list(): List<Project> =
        root.listFiles()?.mapNotNull { d -> runCatching { load(d.name) }.getOrNull() }
            ?.sortedByDescending { it.updatedAt } ?: emptyList()

    fun load(id: String): Project = Project.fromJson(JSONObject(jsonFile(id).readText()))

    fun exists(id: String) = jsonFile(id).exists()

    @Synchronized
    fun save(p: Project, touch: Boolean = true) {
        if (touch) p.updatedAt = System.currentTimeMillis()
        dir(p.id).mkdirs()
        val tmp = File(dir(p.id), "project.json.tmp")
        tmp.writeText(p.toJson().toString())
        tmp.renameTo(jsonFile(p.id))
    }

    /** Writes raw json (used by cloud restore). */
    fun saveRaw(id: String, json: String) {
        dir(id).mkdirs()
        jsonFile(id).writeText(json)
    }

    fun delete(id: String) = dir(id).deleteRecursively()

    fun create(name: String): Project = Project(name = name).also { save(it) }

    fun duplicate(id: String): Project {
        val p = load(id)
        val copy = Project(name = p.name + " (نسخة)", aspect = p.aspect)
        copy.clips += p.clips.map { it.copy() }
        copy.audios += p.audios.map { it.copy() }
        mediaDir(id).copyRecursively(mediaDir(copy.id), overwrite = true)
        save(copy)
        return copy
    }

    fun rename(id: String, name: String) {
        val p = load(id); p.name = name; save(p)
    }

    // ---------- Import ----------

    data class Imported(
        val file: String,
        val kind: Kind,
        val durationUs: Long,
        val label: String,
    ) {
        enum class Kind { VIDEO, IMAGE, AUDIO }
    }

    /** Copies a picked file into the project's private media folder and probes it. */
    fun importMedia(projectId: String, uri: Uri): Imported {
        val cr = appContext.contentResolver
        val mime = cr.getType(uri) ?: ""
        val display = runCatching {
            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: "media"
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
            ?: display.substringAfterLast('.', "").ifBlank { "bin" }
        val name = UUID.randomUUID().toString().substring(0, 12) + "." + ext
        val out = mediaFile(projectId, name)
        cr.openInputStream(uri)!!.use { input -> out.outputStream().use { input.copyTo(it, 1 shl 20) } }

        val kind = when {
            mime.startsWith("image/") -> Imported.Kind.IMAGE
            mime.startsWith("audio/") -> Imported.Kind.AUDIO
            else -> Imported.Kind.VIDEO
        }
        if (kind == Imported.Kind.IMAGE) {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(out.absolutePath, o)
            if (o.outWidth <= 0) { out.delete(); error("صورة غير صالحة") }
            return Imported(name, kind, 3_000_000, display)
        }
        val r = MediaMetadataRetriever()
        val durMs = try {
            r.setDataSource(out.absolutePath)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } finally { r.release() }
        if (durMs <= 0) { out.delete(); error("ملف غير مدعوم") }
        return Imported(name, kind, durMs * 1000, display.substringBeforeLast('.'))
    }

    /** Removes media files no longer referenced by the project. */
    fun cleanupMedia(p: Project) {
        val used = (p.clips.map { it.file } + p.audios.map { it.file }).toSet()
        mediaDir(p.id).listFiles()?.forEach { if (it.name !in used) it.delete() }
    }
}
