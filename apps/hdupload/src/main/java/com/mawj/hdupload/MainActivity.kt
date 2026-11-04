package com.mawj.hdupload

import android.content.ContentValues
import android.content.Intent
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.mawj.hdupload.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

data class SourceInfo(
    val width: Int,
    val height: Int,
    val fps: Float,
    val bitrate: Long,
    val durationMs: Long,
    val mime: String?,
)

@OptIn(UnstableApi::class)
class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private var sourceUri: Uri? = null
    private var sourceInfo: SourceInfo? = null
    private var savedUri: Uri? = null
    private var transformer: Transformer? = null
    private var outputFile: File? = null

    private val handler = Handler(Looper.getMainLooper())
    private val progressHolder = ProgressHolder()
    private val progressPoller = object : Runnable {
        override fun run() {
            val t = transformer ?: return
            if (t.getProgress(progressHolder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                b.progress.setProgressCompat(progressHolder.progress, true)
                b.status.text = "جاري التصدير… ${progressHolder.progress}%"
            }
            handler.postDelayed(this, 250)
        }
    }

    private val pickVideo =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) onVideoSelected(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.pickButton.setOnClickListener {
            pickVideo.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
            )
        }
        b.exportButton.setOnClickListener { startExport() }
        b.cancelButton.setOnClickListener { transformer?.cancel(); finishExportUi("تم الإلغاء.") }
        b.tiktokButton.setOnClickListener { savedUri?.let(::shareToTikTok) }
        b.shareButton.setOnClickListener { savedUri?.let { share(it, null) } }

        b.resolutionGroup.setOnCheckedChangeListener { _, _ -> refreshPlan() }
        b.qualityGroup.setOnCheckedChangeListener { _, _ -> refreshPlan() }
        b.fit916Switch.setOnCheckedChangeListener { _, _ -> refreshPlan() }

        handleIncoming(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncoming(intent)
    }

    override fun onDestroy() {
        handler.removeCallbacks(progressPoller)
        transformer?.cancel()
        super.onDestroy()
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
            val info = withContext(Dispatchers.IO) { runCatching { readSourceInfo(uri) }.getOrNull() }
            if (info == null || info.width == 0 || info.height == 0) {
                sourceInfo = null
                b.exportButton.isEnabled = false
                b.status.text = "تعذّر قراءة المقطع. جرّب مقطع آخر."
                return@launch
            }
            sourceInfo = info
            b.sourceCard.visibility = View.VISIBLE
            b.exportButton.isEnabled = true
            refreshPlan()
        }
    }

    private fun readSourceInfo(uri: Uri): SourceInfo {
        var width = 0
        var height = 0
        var bitrate = 0L
        var duration = 0L
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(this, uri)
            width = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            height = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rotation = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rotation == 90 || rotation == 270) {
                val t = width; width = height; height = t
            }
            bitrate = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull() ?: 0L
            duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } finally {
            r.release()
        }

        var fps = 0f
        var mime: String? = null
        val ex = MediaExtractor()
        try {
            ex.setDataSource(this, uri, null)
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val m = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (m.startsWith("video/")) {
                    mime = m
                    if (f.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                        fps = f.getNumber(MediaFormat.KEY_FRAME_RATE)?.toFloat() ?: 0f
                    }
                    break
                }
            }
        } catch (_: Exception) {
        } finally {
            ex.release()
        }
        return SourceInfo(width, height, fps, bitrate, duration, mime)
    }

    // ---------- Plan ----------

    private fun selectedShortSide(): Int? = when (b.resolutionGroup.checkedRadioButtonId) {
        R.id.res1440 -> 1440
        R.id.res2160 -> 2160
        R.id.resOriginal -> null
        else -> 1080
    }

    private fun even(v: Float): Int = max(2, (v / 2f).roundToInt() * 2)

    private fun targetSize(src: SourceInfo): Pair<Int, Int> {
        val short = selectedShortSide()
        if (b.fit916Switch.isChecked) {
            val s = short ?: min(src.width, src.height)
            return even(s.toFloat()) to even(s * 16f / 9f)
        }
        if (short == null) return even(src.width.toFloat()) to even(src.height.toFloat())
        val scale = short.toFloat() / min(src.width, src.height)
        return even(src.width * scale) to even(src.height * scale)
    }

    /** Bits-per-pixel based bitrate, so quality stays consistent across resolutions and frame rates. */
    private fun targetBitrate(w: Int, h: Int, fps: Float): Int {
        val bpp = when (b.qualityGroup.checkedRadioButtonId) {
            R.id.qHigh -> 0.25
            R.id.qStudio -> 0.6
            else -> 0.4
        }
        val f = if (fps in 1f..240f) fps else 30f
        val bps = (w.toDouble() * h * f * bpp).roundToLong()
        return bps.coerceIn(8_000_000L, 150_000_000L).toInt()
    }

    private fun refreshPlan() {
        val src = sourceInfo ?: return
        val (w, h) = targetSize(src)
        val br = targetBitrate(w, h, src.fps)
        val fpsTxt = if (src.fps > 0) "%.0f".format(Locale.US, src.fps) else "؟"
        val srcBr = if (src.bitrate > 0) "%.1f Mbps".format(Locale.US, src.bitrate / 1e6) else "؟"
        val upscale = min(w, h) > min(src.width, src.height)
        b.sourceInfo.text = buildString {
            append("المقطع الأصلي: ${src.width}×${src.height} • $fpsTxt fps • $srcBr\n")
            append("المدة: ${formatDuration(src.durationMs)}\n\n")
            append("الناتج: ${w}×${h} • $fpsTxt fps • %.0f Mbps".format(Locale.US, br / 1e6))
            if (upscale) append("\n⚠️ تكبير الدقة ما يضيف تفاصيل حقيقية، لكنه يحافظ على الحدّة عند رفعه.")
        }
        b.status.text = ""
    }

    private fun formatDuration(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d".format(Locale.US, s / 60, s % 60)
    }

    // ---------- Export ----------

    private fun startExport() {
        val uri = sourceUri ?: return
        val src = sourceInfo ?: return
        val (w, h) = targetSize(src)
        val bitrate = targetBitrate(w, h, src.fps)
        val mime = if (b.codecGroup.checkedRadioButtonId == R.id.codecH265) MimeTypes.VIDEO_H265 else MimeTypes.VIDEO_H264

        val videoEffects = mutableListOf<Effect>()
        if (w != src.width || h != src.height || b.fit916Switch.isChecked) {
            videoEffects += Presentation.createForWidthAndHeight(w, h, Presentation.LAYOUT_SCALE_TO_FIT)
        }

        val item = EditedMediaItem.Builder(MediaItem.fromUri(uri))
            .setEffects(Effects(emptyList(), videoEffects))
            .build()
        val composition = Composition.Builder(EditedMediaItemSequence(item))
            .setHdrMode(
                if (b.toneMapSwitch.isChecked) Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL
                else Composition.HDR_MODE_KEEP_HDR
            )
            .build()

        val encoderFactory = DefaultEncoderFactory.Builder(this)
            .setRequestedVideoEncoderSettings(
                VideoEncoderSettings.Builder().setBitrate(bitrate).build()
            )
            .setEnableFallback(true)
            .build()

        val dir = File(cacheDir, "exports").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "export.mp4")
        outputFile = out

        val t = Transformer.Builder(this)
            .setVideoMimeType(mime)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .setEncoderFactory(encoderFactory)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    onExportDone(out, exportResult)
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException,
                ) {
                    finishExportUi("صار خطأ أثناء التصدير: ${exportException.errorCodeName}\n${exportException.message ?: ""}\nجرّب دقة أقل أو ترميز H.264.")
                }
            })
            .build()
        transformer = t

        setBusy(true)
        b.status.text = "جاري التصدير… 0%"
        t.start(composition, out.absolutePath)
        handler.post(progressPoller)
    }

    private fun onExportDone(file: File, result: ExportResult) {
        handler.removeCallbacks(progressPoller)
        b.progress.setProgressCompat(100, true)
        b.status.text = "جاري الحفظ في الاستديو…"
        lifecycleScope.launch {
            val name = "MawjHD_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
            val uri = withContext(Dispatchers.IO) { runCatching { saveToGallery(file, name) }.getOrNull() }
            transformer = null
            setBusy(false)
            if (uri == null) {
                b.status.text = "تم التصدير لكن تعذّر الحفظ في الاستديو."
                return@launch
            }
            savedUri = uri
            b.resultActions.visibility = View.VISIBLE
            val size = file.length().takeIf { it > 0 } ?: result.fileSizeBytes
            b.status.text = buildString {
                append("✅ تم الحفظ في Movies/Mawj HD\n")
                if (result.width > 0) append("الدقة: ${result.width}×${result.height}\n")
                if (result.averageVideoBitrate > 0) append("معدل البت: %.1f Mbps\n".format(Locale.US, result.averageVideoBitrate / 1e6))
                if (size > 0) append("الحجم: %.1f MB".format(Locale.US, size / 1e6))
            }
            file.delete()
        }
    }

    private fun finishExportUi(message: String) {
        handler.removeCallbacks(progressPoller)
        transformer = null
        outputFile?.delete()
        setBusy(false)
        b.status.text = message
    }

    private fun setBusy(busy: Boolean) {
        b.progress.visibility = if (busy) View.VISIBLE else View.GONE
        if (busy) b.progress.setProgressCompat(0, false)
        b.cancelButton.visibility = if (busy) View.VISIBLE else View.GONE
        b.exportButton.isEnabled = !busy && sourceInfo != null
        b.pickButton.isEnabled = !busy
        if (busy) {
            b.resultActions.visibility = View.GONE
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun saveToGallery(file: File, name: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Mawj HD")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = contentResolver.insert(collection, values) ?: error("insert failed")
        try {
            contentResolver.openOutputStream(uri)!!.use { out ->
                file.inputStream().use { it.copyTo(out, 1 shl 20) }
            }
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
        } catch (e: Exception) {
            contentResolver.delete(uri, null, null)
            throw e
        }
        return uri
    }

    // ---------- Share ----------

    private fun shareToTikTok(uri: Uri) {
        for (pkg in listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill")) {
            val i = buildShareIntent(uri).setPackage(pkg)
            if (i.resolveActivity(packageManager) != null) {
                startActivity(i)
                return
            }
        }
        Toast.makeText(this, "تيك توك غير مثبت — اختر التطبيق يدوياً", Toast.LENGTH_SHORT).show()
        share(uri, "ارفع المقطع عبر")
    }

    private fun share(uri: Uri, title: String?) {
        startActivity(Intent.createChooser(buildShareIntent(uri), title ?: "مشاركة المقطع"))
    }

    private fun buildShareIntent(uri: Uri) = Intent(Intent.ACTION_SEND).apply {
        type = "video/mp4"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
