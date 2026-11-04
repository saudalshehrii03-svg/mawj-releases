package com.mawj.editor.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.Transformer
import com.mawj.editor.data.ProjectStore
import com.mawj.editor.model.Caps
import com.mawj.editor.model.ClipType
import com.mawj.editor.model.Project
import com.mawj.editor.model.Transition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Overlapping transitions ("bridges"). The end of clip A and the start of clip B are exported as two short
 * segments (with all their looks applied), blended frame by frame on the GPU, encoded with the hardware
 * encoder, and muxed with a cross-faded audio mix. The result is dropped into the main timeline.
 */
@OptIn(UnstableApi::class)
object Bridges {

    private const val VERSION = 3

    @Volatile var lastError: String? = null
        private set

    private fun prefs(c: Context) = c.getSharedPreferences("engine", Context.MODE_PRIVATE)

    /** Learned state is reset on every app update. */
    fun loadCaps(c: Context) {
        val p = prefs(c)
        val ver = runCatching { c.packageManager.getPackageInfo(c.packageName, 0).longVersionCode }.getOrDefault(0L)
        if (p.getLong("ver", -1) != ver) {
            p.edit().clear().putLong("ver", ver).apply()
            clear(c)
        }
        Caps.overlapSupported = true
    }

    fun key(project: Project, i: Int, w: Int, h: Int, forExport: Boolean): String {
        val a = project.clips[i]
        val b = project.clips[i + 1]
        val raw = "$VERSION" + a.toJson().toString() + b.toJson().toString() + project.overlapUs(i) + w + "x" + h + forExport
        val d = MessageDigest.getInstance("SHA-1").digest(raw.toByteArray())
        return d.joinToString("") { "%02x".format(it) }.substring(0, 24)
    }

    private fun dir(c: Context) = File(c.cacheDir, "bridges").apply { mkdirs() }

    fun cached(c: Context, project: Project, i: Int, w: Int, h: Int, forExport: Boolean): File? {
        val f = File(dir(c), key(project, i, w, h, forExport) + ".mp4")
        return if (f.exists() && f.length() > 0) f else null
    }

    /** Boundaries that need a bridge. */
    fun needed(project: Project): List<Int> = (0 until project.clips.size - 1).filter { project.overlapUs(it) > 0 }

    /** Renders (or reuses) the bridge after clip [i]. Main thread. Returns null on failure (see [lastError]). */
    suspend fun render(
        context: Context, store: ProjectStore, project: Project, i: Int,
        w: Int, h: Int, forExport: Boolean, bitrate: Int,
    ): File? {
        cached(context, project, i, w, h, forExport)?.let { return it }
        val work = File(dir(context), "work").apply { deleteRecursively(); mkdirs() }
        val out = File(dir(context), key(project, i, w, h, forExport) + ".mp4")
        try {
            val a = project.clips[i]
            val b = project.clips[i + 1]
            val d = project.overlapUs(i)
            val opt = BuildOptions(w, h, forExport)
            val segBitrate = (bitrate * 1.5).toInt().coerceAtMost(160_000_000)

            // 1) The two overlapping segments, each with its own looks, at canvas size (video only).
            val segA = File(work, "a.mp4")
            val segB = File(work, "b.mp4")
            exportSegment(context, CompositionFactory.clipItem(context, store, project, a, opt, a.timelineUs - d, d, null,
                GainAudioProcessor(1f), removeAudio = true), segA, segBitrate, forExport)
            exportSegment(context, CompositionFactory.clipItem(context, store, project, b, opt, 0, d, null,
                GainAudioProcessor(1f), removeAudio = true), segB, segBitrate, forExport)

            // 2) Cross-faded audio of both clips.
            val audio = File(work, "mix.m4a")
            mixAudio(context, store, project, i, d, opt, audio)

            // 3) Blend frames and encode.
            val video = File(work, "v.mp4")
            withContext(Dispatchers.Default) { blend(segA, segB, video, w, h, d, a.transition, bitrate) }

            // 4) Mux.
            val tmp = File(work, "bridge.mp4")
            withContext(Dispatchers.IO) { mux(video, audio, tmp) }
            tmp.renameTo(out)
            lastError = null
            return out
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            lastError = e.message ?: e.javaClass.simpleName
            out.delete()
            return null
        } finally {
            work.deleteRecursively()
        }
    }

    private suspend fun exportSegment(context: Context, item: EditedMediaItem, out: File, bitrate: Int, forExport: Boolean) {
        val builder = Composition.Builder(listOf(EditedMediaItemSequence(listOf(item))))
        if (forExport) builder.setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
        MediaJobs.exportVideo(context, builder.build(), bitrate, out) {}
    }

    private suspend fun mixAudio(context: Context, store: ProjectStore, project: Project, i: Int, d: Long, opt: BuildOptions, out: File) {
        val a = project.clips[i]
        val b = project.clips[i + 1]
        val seqs = ArrayList<EditedMediaItemSequence>()
        fun audioOf(clipIdx: Int): Boolean {
            val c = project.clips[clipIdx]
            return c.type == ClipType.VIDEO && c.volume > 0f && MediaJobs.hasAudio(store.mediaFile(project.id, c.file))
        }
        if (audioOf(i)) {
            val item = CompositionFactory.clipItem(context, store, project, a, opt, a.timelineUs - d, d, null,
                GainAudioProcessor(a.volume, 0, d, d), removeVideo = true)
            seqs += EditedMediaItemSequence(listOf(item))
        }
        if (audioOf(i + 1)) {
            val item = CompositionFactory.clipItem(context, store, project, b, opt, 0, d, null,
                GainAudioProcessor(b.volume, d, 0, d), removeVideo = true)
            seqs += EditedMediaItemSequence(listOf(item))
        }
        if (seqs.isEmpty()) {
            val silence = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(Silence.file(context, d))))
                .setDurationUs(d)
                .setEffects(Effects(listOf<AudioProcessor>(), emptyList()))
                .build()
            seqs += EditedMediaItemSequence(listOf(silence))
        }
        val comp = Composition.Builder(seqs).build()
        MediaJobs.runTransformer(
            Transformer.Builder(context).setAudioMimeType(MimeTypes.AUDIO_AAC),
            { it.start(comp, out.absolutePath) }, {},
        )
    }

    // ---------- Frame blending + hardware encoding ----------

    private class FrameSource(file: File) {
        private val r = MediaMetadataRetriever().apply { setDataSource(file.absolutePath) }
        val count = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toIntOrNull() ?: 0
        private val durUs = (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0) * 1000
        val fps: Float get() = if (durUs > 0) count * 1_000_000f / durUs else 30f
        private var cacheStart = -1
        private var cache: List<Bitmap> = emptyList()
        private var batch = 4

        fun setBatch(n: Int) { batch = n.coerceAtLeast(1) }

        /** Frame at [progress] 0..1 of the segment (sequential access is cheap thanks to batching). */
        fun at(progress: Float): Bitmap? {
            if (count <= 0) return null
            val idx = (progress * (count - 1)).roundToInt().coerceIn(0, count - 1)
            if (idx < cacheStart || idx >= cacheStart + cache.size) {
                cache.forEach { it.recycle() }
                val n = minOf(batch, count - idx)
                cache = r.getFramesAtIndex(idx, n)
                cacheStart = idx
            }
            return cache.getOrNull(idx - cacheStart)
        }

        fun release() {
            cache.forEach { it.recycle() }
            r.release()
        }
    }

    private fun blend(segA: File, segB: File, out: File, w: Int, h: Int, durUs: Long, kind: Transition, bitrate: Int) {
        val srcA = FrameSource(segA)
        val srcB = FrameSource(segB)
        val frameBytes = w.toLong() * h * 4
        val batch = (48L * 1024 * 1024 / frameBytes).toInt().coerceIn(1, 8)
        srcA.setBatch(batch); srcB.setBatch(batch)
        val fps = max(srcA.fps, srcB.fps).roundToInt().coerceIn(24, 60)
        val frames = max(2, (durUs * fps / 1_000_000).toInt())

        val format = MediaFormat.createVideoFormat(MimeTypes.VIDEO_H264, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codec = MediaCodec.createEncoderByType(MimeTypes.VIDEO_H264)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = codec.createInputSurface()
        codec.start()
        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        var written = 0
        val info = MediaCodec.BufferInfo()
        val frameUs = 1_000_000L / fps

        fun drain(endOfStream: Boolean) {
            while (true) {
                val idx = codec.dequeueOutputBuffer(info, if (endOfStream) 10_000 else 0)
                when {
                    idx == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!endOfStream) return
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { track = muxer.addTrack(codec.outputFormat); muxer.start() }
                    idx >= 0 -> {
                        val buf = codec.getOutputBuffer(idx)!!
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0 && track >= 0) {
                            // Our own monotonic timestamps (canvas frames carry wall-clock times).
                            info.presentationTimeUs = written * frameUs
                            buf.position(info.offset); buf.limit(info.offset + info.size)
                            muxer.writeSampleData(track, buf, info)
                            written++
                        }
                        codec.releaseOutputBuffer(idx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
            }
        }

        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        val m = Matrix()
        try {
            for (f in 0 until frames) {
                val p = f / (frames - 1f)
                val e = ease(p)
                val fa = srcA.at(p)
                val fb = srcB.at(p)
                val canvas = surface.lockHardwareCanvas()
                try {
                    canvas.drawColor(Color.BLACK)
                    fun draw(bm: Bitmap?, scale: Float, dx: Float, dy: Float, alpha: Float) {
                        bm ?: return
                        m.reset()
                        m.setScale(w.toFloat() / bm.width, h.toFloat() / bm.height)
                        m.postScale(scale, scale, w / 2f, h / 2f)
                        m.postTranslate(dx, dy)
                        paint.alpha = (alpha * 255).roundToInt().coerceIn(0, 255)
                        canvas.drawBitmap(bm, m, paint)
                    }
                    when (kind) {
                        Transition.DISSOLVE_ZOOM -> {
                            draw(fa, 1f + 0.12f * e, 0f, 0f, 1f)
                            draw(fb, 1.15f - 0.15f * e, 0f, 0f, e)
                        }
                        Transition.SLIDE_OVER_LEFT -> {
                            draw(fa, 1f, -0.25f * w * e, 0f, 1f)
                            draw(fb, 1f, w * (1f - e), 0f, 1f)
                        }
                        Transition.SLIDE_OVER_UP -> {
                            draw(fa, 1f, 0f, -0.25f * h * e, 1f)
                            draw(fb, 1f, 0f, h * (1f - e), 1f)
                        }
                        else -> {
                            draw(fa, 1f, 0f, 0f, 1f)
                            draw(fb, 1f, 0f, 0f, e)
                        }
                    }
                } finally {
                    surface.unlockCanvasAndPost(canvas)
                }
                drain(false)
            }
            codec.signalEndOfInputStream()
            drain(true)
            if (written == 0) error("encoder produced no frames")
        } finally {
            runCatching { codec.stop() }
            codec.release()
            surface.release()
            runCatching { muxer.stop() }
            muxer.release()
            srcA.release(); srcB.release()
        }
    }

    /** Combines the blended video and the mixed audio into one MP4. */
    private fun mux(video: File, audio: File, out: File) {
        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val sources = listOf(video, audio).filter { it.exists() && it.length() > 0 }.map { f ->
            MediaExtractor().apply { setDataSource(f.absolutePath) }
        }
        try {
            val tracks = sources.map { ex ->
                ex.selectTrack(0)
                muxer.addTrack(ex.getTrackFormat(0))
            }
            muxer.start()
            val buf = ByteBuffer.allocateDirect(4 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            sources.forEachIndexed { k, ex ->
                while (true) {
                    buf.clear()
                    val n = ex.readSampleData(buf, 0)
                    if (n < 0) break
                    val sync = ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0
                    info.set(0, n, ex.sampleTime.coerceAtLeast(0), if (sync) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                    muxer.writeSampleData(tracks[k], buf, info)
                    ex.advance()
                }
            }
            muxer.stop()
        } finally {
            muxer.release()
            sources.forEach { it.release() }
        }
    }

    fun clear(context: Context) = dir(context).listFiles()?.forEach { it.deleteRecursively() }
}
