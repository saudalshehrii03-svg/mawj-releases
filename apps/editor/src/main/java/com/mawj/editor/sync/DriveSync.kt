package com.mawj.editor.sync

import com.mawj.editor.L

import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.mawj.editor.data.ProjectStore
import com.mawj.editor.model.Project
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object SyncPrefs {
    private fun prefs(c: Context) = c.getSharedPreferences("sync", Context.MODE_PRIVATE)
    fun email(c: Context): String? = prefs(c).getString("email", null)
    fun setEmail(c: Context, v: String?) = prefs(c).edit().putString("email", v).apply()
    fun auto(c: Context): Boolean = prefs(c).getBoolean("auto", true)
    fun setAuto(c: Context, v: Boolean) = prefs(c).edit().putBoolean("auto", v).apply()
    fun lastSync(c: Context): Long = prefs(c).getLong("last", 0)
    fun setLastSync(c: Context, v: Long) = prefs(c).edit().putLong("last", v).apply()
}

object GoogleAuth {
    private const val SCOPE_APPDATA = "https://www.googleapis.com/auth/drive.appdata"
    private const val SCOPE_EMAIL = "email"

    fun request(): AuthorizationRequest = AuthorizationRequest.builder()
        .setRequestedScopes(listOf(Scope(SCOPE_APPDATA), Scope(SCOPE_EMAIL)))
        .build()

    /** Asks for authorization; the result may need a UI resolution (consent / account picker). */
    suspend fun authorize(context: Context): AuthorizationResult = suspendCancellableCoroutine { cont ->
        Identity.getAuthorizationClient(context).authorize(request())
            .addOnSuccessListener { cont.resume(it) }
            .addOnFailureListener { cont.resumeWithException(it) }
    }

    /** Access token without any UI, or null when the user needs to sign in again. */
    suspend fun silentToken(context: Context): String? {
        val r = authorize(context)
        return if (r.hasResolution()) null else r.accessToken
    }

    fun friendlyError(e: Throwable): String {
        val msg = e.message ?: e.javaClass.simpleName
        return if (msg.contains("10:") || msg.contains("DEVELOPER_ERROR") || msg.contains("16:")) {
            L.t("تسجيل الدخول بجوجل يحتاج إعداد مرة وحدة في Google Cloud (شوف ملف apps/editor/GOOGLE_SETUP.md).\n($msg)", "Google sign-in needs a one-time setup in Google Cloud (see apps/editor/GOOGLE_SETUP.md).\n($msg)")
        } else msg
    }
}

/** Backs up projects (json + media) to the app's private Google Drive folder (appDataFolder). */
class DriveSync(private val store: ProjectStore) {

    data class RemoteFile(val id: String, val name: String, val size: Long, val project: String, val rel: String)

    private val api = "https://www.googleapis.com/drive/v3"
    private val upload = "https://www.googleapis.com/upload/drive/v3"

    private fun open(token: String, url: String, method: String = "GET"): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20_000
        c.readTimeout = 60_000
        if (method == "PATCH") {
            c.requestMethod = "POST"
            c.setRequestProperty("X-HTTP-Method-Override", "PATCH")
        } else c.requestMethod = method
        c.setRequestProperty("Authorization", "Bearer $token")
        return c
    }

    private fun HttpURLConnection.ensureOk(): HttpURLConnection {
        val code = responseCode
        if (code !in 200..299) {
            val body = runCatching { errorStream?.bufferedReader()?.readText() }.getOrNull()
            throw IOException("Drive $code: ${body?.take(300)}")
        }
        return this
    }

    fun email(token: String): String? {
        val c = open(token, "$api/about?fields=user(emailAddress)").ensureOk()
        val o = JSONObject(c.inputStream.bufferedReader().readText())
        return o.optJSONObject("user")?.optString("emailAddress")
    }

    fun listAll(token: String): List<RemoteFile> {
        val out = ArrayList<RemoteFile>()
        var page: String? = null
        val fields = URLEncoder.encode("nextPageToken,files(id,name,size,appProperties)", "UTF-8")
        do {
            var url = "$api/files?spaces=appDataFolder&pageSize=1000&fields=$fields"
            if (page != null) url += "&pageToken=" + URLEncoder.encode(page, "UTF-8")
            val o = JSONObject(open(token, url).ensureOk().inputStream.bufferedReader().readText())
            val files = o.optJSONArray("files")
            if (files != null) for (i in 0 until files.length()) {
                val f = files.getJSONObject(i)
                val props = f.optJSONObject("appProperties") ?: continue
                out += RemoteFile(
                    f.getString("id"), f.optString("name"), f.optString("size", "0").toLongOrNull() ?: 0,
                    props.optString("p"), props.optString("r"),
                )
            }
            page = o.optString("nextPageToken").ifBlank { null }
        } while (page != null)
        return out
    }

    private fun uploadFile(token: String, file: File, project: String, rel: String, mime: String, existingId: String?) {
        val meta = if (existingId == null) JSONObject().apply {
            put("name", "$project/$rel")
            put("parents", org.json.JSONArray().put("appDataFolder"))
            put("appProperties", JSONObject().put("p", project).put("r", rel))
        } else JSONObject()
        val init = if (existingId == null) open(token, "$upload/files?uploadType=resumable", "POST")
        else open(token, "$upload/files/$existingId?uploadType=resumable", "PATCH")
        init.doOutput = true
        init.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        init.setRequestProperty("X-Upload-Content-Type", mime)
        init.setRequestProperty("X-Upload-Content-Length", file.length().toString())
        init.outputStream.use { it.write(meta.toString().toByteArray()) }
        init.ensureOk()
        val location = init.getHeaderField("Location") ?: throw IOException("no upload url")

        val put = open(token, location, "PUT")
        put.doOutput = true
        put.readTimeout = 300_000
        put.setRequestProperty("Content-Type", mime)
        put.setFixedLengthStreamingMode(file.length())
        put.outputStream.use { o -> file.inputStream().use { it.copyTo(o, 256 * 1024) } }
        put.ensureOk()
    }

    private fun download(token: String, id: String, dest: File) {
        val c = open(token, "$api/files/$id?alt=media").ensureOk()
        val tmp = File(dest.parentFile, dest.name + ".part")
        c.inputStream.use { i -> tmp.outputStream().use { i.copyTo(it, 256 * 1024) } }
        tmp.renameTo(dest)
    }

    /** Uploads new media and the latest project.json for every (or one) project. */
    fun backup(token: String, onlyProject: String? = null, progress: (String) -> Unit) {
        val remote = listAll(token).associateBy { it.project + "/" + it.rel }
        val projects = if (onlyProject != null) listOf(store.load(onlyProject)) else store.list()
        projects.forEachIndexed { pi, p ->
            val used = (p.clips.map { it.file } + p.clips.map { it.textFont } + p.audios.map { it.file }).toSet()
            val media = store.mediaDir(p.id).listFiles()?.filter { it.name in used } ?: emptyList()
            media.forEachIndexed { mi, f ->
                val rel = "media/" + f.name
                val r = remote[p.id + "/" + rel]
                if (r == null || r.size != f.length()) {
                    progress(L.t("رفع «${p.name}» (${pi + 1}/${projects.size}) — ملف ${mi + 1}/${media.size}", "Uploading “${p.name}” (${pi + 1}/${projects.size}) — file ${mi + 1}/${media.size}"))
                    uploadFile(token, f, p.id, rel, "application/octet-stream", r?.id)
                }
            }
            val json = File(store.dir(p.id), "project.json")
            uploadFile(token, json, p.id, "project.json", "application/json", remote[p.id + "/project.json"]?.id)
        }
        progress(L.t("تم النسخ الاحتياطي ✅", "Backup complete ✅"))
    }

    /** Downloads projects that are missing locally or newer in the cloud. Returns how many were restored. */
    fun restore(token: String, progress: (String) -> Unit): Int {
        val byProject = listAll(token).groupBy { it.project }
        var count = 0
        byProject.entries.forEachIndexed { i, (pid, files) ->
            if (pid.isBlank() || pid.contains("/") || pid.contains("..")) return@forEachIndexed
            val jsonRemote = files.firstOrNull { it.rel == "project.json" } ?: return@forEachIndexed
            val tmp = File(store.root, "restore_$pid.json")
            download(token, jsonRemote.id, tmp)
            val remoteProject = runCatching { Project.fromJson(JSONObject(tmp.readText())) }.getOrNull()
            if (remoteProject == null) { tmp.delete(); return@forEachIndexed }
            val local = runCatching { store.load(pid) }.getOrNull()
            if (local != null && local.updatedAt >= remoteProject.updatedAt) { tmp.delete(); return@forEachIndexed }
            progress(L.t("تنزيل «${remoteProject.name}» (${i + 1}/${byProject.size})", "Downloading “${remoteProject.name}” (${i + 1}/${byProject.size})"))
            files.filter { it.rel.startsWith("media/") }.forEach { f ->
                val name = f.rel.removePrefix("media/")
                if (name.contains("/") || name.contains("..")) return@forEach
                val dest = store.mediaFile(pid, name)
                if (!dest.exists() || dest.length() != f.size) download(token, f.id, dest)
            }
            store.saveRaw(pid, tmp.readText())
            tmp.delete()
            count++
        }
        progress(if (count > 0) L.t("تمت استعادة $count مشروع ✅", "Restored $count project(s) ✅") else L.t("مشاريعك محدّثة، ما فيه شي جديد", "Your projects are up to date"))
        return count
    }
}
