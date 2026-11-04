package com.mawj.editor.engine

import com.mawj.editor.L

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.nio.ByteBuffer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@OptIn(UnstableApi::class)
object MediaJobs {

    private val main = Handler(Looper.getMainLooper())

    /** Runs a Transformer export on the main thread, reporting progress 0..100. */
    suspend fun runTransformer(
        transformer: Transformer.Builder,
        start: (Transformer) -> Unit,
        onProgress: (Int) -> Unit,
    ): ExportResult = suspendCancellableCoroutine { cont ->
        val holder = ProgressHolder()
        lateinit var t: Transformer
        val poll = object : Runnable {
            override fun run() {
                if (t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress)
                main.postDelayed(this, 250)
            }
        }
        t = transformer.addListener(object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                main.removeCallbacks(poll)
                if (cont.isActive) cont.resume(exportResult)
            }

            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                main.removeCallbacks(poll)
                if (cont.isActive) cont.resumeWithException(exportException)
            }
        }).build()
        start(t)
        main.post(poll)
        cont.invokeOnCancellation {
            main.post {
                main.removeCallbacks(poll)
                t.cancel()
            }
        }
    }

    suspend fun exportVideo(
        context: Context,
        composition: Composition,
        bitrate: Int,
        out: File,
        hevc: Boolean = false,
        onProgress: (Int) -> Unit,
    ): ExportResult {
        val encoder = DefaultEncoderFactory.Builder(context)
            .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(bitrate).build())
            .setEnableFallback(true)
            .build()
        val builder = Transformer.Builder(context)
            .setVideoMimeType(if (hevc) MimeTypes.VIDEO_H265 else MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .setEncoderFactory(encoder)
        return runTransformer(builder, { it.start(composition, out.absolutePath) }, onProgress)
    }

    /** Extracts the audio track of a video. Copies AAC as-is, otherwise re-encodes to AAC. */
    suspend fun extractAudio(context: Context, video: File, out: File, onProgress: (Int) -> Unit) {
        val copied = runCatching { copyAac(video, out) }.getOrDefault(false)
        if (copied) { onProgress(100); return }
        out.delete()
        val item = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(video))).setRemoveVideo(true).build()
        runTransformer(
            Transformer.Builder(context).setAudioMimeType(MimeTypes.AUDIO_AAC),
            { it.start(item, out.absolutePath) },
            onProgress,
        )
    }

    fun hasAudio(file: File): Boolean {
        val ex = MediaExtractor()
        return try {
            ex.setDataSource(file.absolutePath)
            (0 until ex.trackCount).any { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
        } catch (_: Exception) {
            false
        } finally {
            ex.release()
        }
    }

    private fun copyAac(video: File, out: File): Boolean {
        val ex = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            ex.setDataSource(video.absolutePath)
            val track = (0 until ex.trackCount).firstOrNull {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == MimeTypes.AUDIO_AAC
            } ?: return false
            val format = ex.getTrackFormat(track)
            ex.selectTrack(track)
            val m = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = m
            val outTrack = m.addTrack(format)
            m.start()
            val buf = ByteBuffer.allocateDirect(1 shl 20)
            val info = MediaCodec.BufferInfo()
            while (true) {
                buf.clear()
                val n = ex.readSampleData(buf, 0)
                if (n < 0) break
                val ts = ex.sampleTime
                if (ts >= 0) {
                    info.set(0, n, ts, MediaCodec.BUFFER_FLAG_KEY_FRAME)
                    m.writeSampleData(outTrack, buf, info)
                }
                ex.advance()
            }
            m.stop()
            return true
        } finally {
            try { muxer?.release() } catch (_: Exception) {}
            ex.release()
        }
    }

    fun saveToGallery(context: Context, file: File, name: String): Uri {
        val cr = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Mawj Edit")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = cr.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: error(L.t("تعذّر الحفظ", "Couldn't save"))
        try {
            cr.openOutputStream(uri)!!.use { o -> file.inputStream().use { it.copyTo(o, 1 shl 20) } }
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            cr.update(uri, values, null, null)
        } catch (e: Exception) {
            cr.delete(uri, null, null)
            throw e
        }
        return uri
    }
}
