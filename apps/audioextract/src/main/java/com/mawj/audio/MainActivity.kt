package com.mawj.audio

import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.mawj.audio.databinding.ActivityMainBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@OptIn(UnstableApi::class)
class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private var sourceUri: Uri? = null
    private var trackInfo: AudioTrackInfo? = null
    private var savedUri: Uri? = null
    private var savedMime: String = "audio/*"
    private val handler = Handler(Looper.getMainLooper())

    private val pickGallery =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) onVideoSelected(uri)
        }
    private val pickFile =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) onVideoSelected(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.pickGalleryButton.setOnClickListener {
            pickGallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
        }
        b.pickFilesButton.setOnClickListener { pickFile.launch(arrayOf("video/*", "audio/*")) }
        b.convertButton.setOnClickListener { convert() }
        b.playButton.setOnClickListener { savedUri?.let(::play) }
        b.shareButton.setOnClickListener { savedUri?.let(::share) }

        handleIncoming(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncoming(intent)
    }

    private fun handleIncoming(intent: Intent?) {
        val uri = when (intent?.action) {
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            Intent.ACTION_VIEW -> intent.data
            else -> null
        }
        if (uri != null) onVideoSelected(uri)
    }

    // ---------- Source ----------

    private fun onVideoSelected(uri: Uri) {
        sourceUri = uri
        savedUri = null
        b.resultActions.visibility = View.GONE
        b.status.text = "جاري قراءة المقطع…"
        lifecycleScope.launch {
            val (info, name) = withContext(Dispatchers.IO) {
                AudioEngine.probe(this@MainActivity, uri) to displayName(uri)
            }
            trackInfo = info
            b.sourceCard.visibility = View.VISIBLE
            if (info == null) {
                b.sourceInfo.text = "⚠️ ما لقينا صوت في هذا المقطع."
                b.convertButton.isEnabled = false
                b.status.text = ""
                return@launch
            }
            b.convertButton.isEnabled = true
            b.nameInput.setText(name)
            b.sourceInfo.text = buildString {
                append("الصوت: ${codecName(info.mime)}")
                if (info.sampleRate > 0) append(" • %.1f kHz".format(Locale.US, info.sampleRate / 1000f))
                if (info.channels > 0) append(" • ${if (info.channels == 1) "مونو" else if (info.channels == 2) "ستيريو" else "${info.channels} قنوات"}")
                if (info.bitrate > 0) append(" • ${info.bitrate / 1000} kbps")
                if (info.durationUs > 0) {
                    val s = info.durationUs / 1_000_000
                    append("\nالمدة: %d:%02d".format(Locale.US, s / 60, s % 60))
                }
                if (AudioEngine.copyExtension(info) == null) {
                    append("\nℹ️ هذه الصيغة ما تنسخ مباشرة؛ بنحوّلها تلقائياً إلى AAC عالي الجودة.")
                }
            }
            b.status.text = ""
        }
    }

    private fun codecName(mime: String) = when (mime) {
        "audio/mp4a-latm" -> "AAC"
        "audio/opus" -> "Opus"
        "audio/mpeg" -> "MP3"
        "audio/vorbis" -> "Vorbis"
        "audio/ac3" -> "AC-3"
        "audio/eac3" -> "E-AC-3"
        "audio/flac" -> "FLAC"
        "audio/raw" -> "PCM"
        else -> mime.removePrefix("audio/").uppercase(Locale.US)
    }

    private fun displayName(uri: Uri): String {
        val raw = runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: "audio"
        return raw.substringBeforeLast('.').ifBlank { "audio" }
    }

    // ---------- Convert ----------

    private enum class Mode { COPY, WAV, AAC }

    private fun convert() {
        val uri = sourceUri ?: return
        val requested = when (b.formatGroup.checkedRadioButtonId) {
            R.id.fmtWav -> Mode.WAV
            R.id.fmtAac -> Mode.AAC
            else -> Mode.COPY
        }
        val baseName = (b.nameInput.text?.toString()?.trim().orEmpty().ifBlank { "audio" })
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")

        setBusy(true)
        lifecycleScope.launch {
            val dir = File(cacheDir, "audio").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
            try {
                var mode = requested
                if (mode == Mode.COPY && AudioEngine.copyExtension(trackInfo) == null) mode = Mode.AAC

                val (file, ext) = when (mode) {
                    Mode.COPY -> {
                        val ext = AudioEngine.copyExtension(trackInfo)!!
                        val f = File(dir, "out.$ext")
                        try {
                            withContext(Dispatchers.IO) { AudioEngine.copyLossless(this@MainActivity, uri, f, ::postProgress) }
                            f to ext
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            // Fall back to re-encoding if the container rejects the stream.
                            File(dir, "out.m4a").also { exportAac(uri, it) } to "m4a"
                        }
                    }
                    Mode.WAV -> {
                        val f = File(dir, "out.wav")
                        withContext(Dispatchers.IO) { AudioEngine.decodeToWav(this@MainActivity, uri, f, ::postProgress) }
                        f to "wav"
                    }
                    Mode.AAC -> File(dir, "out.m4a").also { exportAac(uri, it) } to "m4a"
                }

                b.status.text = "جاري الحفظ…"
                val mime = when (ext) {
                    "wav" -> "audio/x-wav"
                    "ogg" -> "audio/ogg"
                    else -> "audio/mp4"
                }
                val name = "$baseName.$ext"
                val saved = withContext(Dispatchers.IO) { saveToMusic(file, name, mime) }
                val size = file.length()
                file.delete()
                savedUri = saved
                savedMime = mime
                setBusy(false)
                b.resultActions.visibility = View.VISIBLE
                b.status.text = "✅ تم الحفظ: Music/Mawj Audio/$name\nالحجم: %.1f MB".format(Locale.US, size / 1e6)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setBusy(false)
                b.status.text = "صار خطأ: ${e.message ?: e.javaClass.simpleName}\nجرّب صيغة ثانية."
            }
        }
    }

    private fun postProgress(p: Float) {
        handler.post {
            val pct = (p * 100).toInt()
            b.progress.setProgressCompat(pct, true)
            b.status.text = "جاري التحويل… $pct%"
        }
    }

    /** Re-encodes the audio to AAC (.m4a) with Media3 Transformer. Must be called from the main thread. */
    private suspend fun exportAac(uri: Uri, out: File) {
        val holder = ProgressHolder()
        suspendCancellableCoroutine { cont ->
            lateinit var poll: Runnable
            val transformer = Transformer.Builder(this)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        handler.removeCallbacks(poll)
                        if (cont.isActive) cont.resume(Unit)
                    }

                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        handler.removeCallbacks(poll)
                        if (cont.isActive) cont.resumeWithException(exportException)
                    }
                })
                .build()
            poll = Runnable {
                if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                    postProgress(holder.progress / 100f)
                }
                handler.postDelayed(poll, 250)
            }
            val item = EditedMediaItem.Builder(MediaItem.fromUri(uri)).setRemoveVideo(true).build()
            transformer.start(item, out.absolutePath)
            handler.post(poll)
            cont.invokeOnCancellation {
                handler.post {
                    handler.removeCallbacks(poll)
                    transformer.cancel()
                }
            }
        }
    }

    private fun saveToMusic(file: File, name: String, mime: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, name)
            put(MediaStore.Audio.Media.MIME_TYPE, mime)
            put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/Mawj Audio")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = contentResolver.insert(collection, values) ?: error("تعذّر إنشاء الملف")
        try {
            contentResolver.openOutputStream(uri)!!.use { out ->
                file.inputStream().use { it.copyTo(out, 1 shl 20) }
            }
            values.clear()
            values.put(MediaStore.Audio.Media.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
        } catch (e: Exception) {
            contentResolver.delete(uri, null, null)
            throw e
        }
        return uri
    }

    private fun setBusy(busy: Boolean) {
        b.progress.visibility = if (busy) View.VISIBLE else View.GONE
        if (busy) {
            b.progress.setProgressCompat(0, false)
            b.status.text = "جاري التحويل… 0%"
            b.resultActions.visibility = View.GONE
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        b.convertButton.isEnabled = !busy && trackInfo != null
        b.pickGalleryButton.isEnabled = !busy
        b.pickFilesButton.isEnabled = !busy
    }

    // ---------- Result ----------

    private fun play(uri: Uri) {
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, savedMime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(i)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "ما فيه مشغّل صوت في الجهاز", Toast.LENGTH_SHORT).show()
        }
    }

    private fun share(uri: Uri) {
        val i = Intent(Intent.ACTION_SEND).apply {
            type = savedMime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(i, "مشاركة الملف الصوتي"))
    }
}
