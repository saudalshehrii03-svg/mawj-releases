package com.mawj.editor.engine

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.annotation.OptIn
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
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.mawj.editor.data.ProjectStore
import com.mawj.editor.model.Clip
import com.mawj.editor.model.ClipType
import com.mawj.editor.model.Project
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Lightweight 720p "proxy" copies of heavy clips (4K, high bit-depth, HDR). The preview and transition previews
 * decode these instead of the originals, so playback stays smooth and the phone never runs out of video decoders.
 * Exports always use the full-quality originals.
 */
@OptIn(UnstableApi::class)
object Proxies {

    private const val SHORT_SIDE = 720
    private val durations = ConcurrentHashMap<String, Long>()
    private val failed = ConcurrentHashMap.newKeySet<String>()

    private fun dir(c: Context, projectId: String) = File(c.cacheDir, "proxies/$projectId").apply { mkdirs() }

    fun file(c: Context, projectId: String, media: String) = File(dir(c, projectId), media.substringBeforeLast('.') + "_p.mp4")

    /** Proxy to use for [clip] in previews, or null to use the original. */
    fun forClip(c: Context, projectId: String, clip: Clip): File? {
        if (clip.type != ClipType.VIDEO) return null
        val f = file(c, projectId, clip.file)
        return if (f.exists() && f.length() > 0) f else null
    }

    /** Whether this clip is heavy enough to deserve a proxy. */
    fun needs(store: ProjectStore, project: Project, clip: Clip): Boolean {
        if (clip.type != ClipType.VIDEO || clip.file in failed) return false
        var w = clip.srcW; var h = clip.srcH
        if (w <= 0 || h <= 0) {
            val s = runCatching { Analysis.size(store.mediaFile(project.id, clip.file), false) }.getOrDefault(0 to 0)
            w = s.first; h = s.second
            clip.srcW = w; clip.srcH = h
        }
        return minOf(w, h) > SHORT_SIDE + 80
    }

    fun missing(c: Context, store: ProjectStore, project: Project): List<Clip> =
        project.clips.filter { needs(store, project, it) && forClip(c, project.id, it) == null }.distinctBy { it.file }

    /** Duration (µs) of a media file, cached. */
    fun durationUs(f: File): Long = durations.getOrPut(f.absolutePath) {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(f.absolutePath)
            (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) * 1000
        } catch (_: Exception) { 0L } finally { r.release() }
    }

    /** Builds the proxy for [clip]. Main thread. Returns false if it failed (the original is then used). */
    suspend fun make(c: Context, store: ProjectStore, project: Project, clip: Clip): Boolean {
        val src = store.mediaFile(project.id, clip.file)
        val out = file(c, project.id, clip.file)
        val tmp = File(out.parentFile, out.name + ".tmp")
        return try {
            val w = clip.srcW.coerceAtLeast(1); val h = clip.srcH.coerceAtLeast(1)
            val k = SHORT_SIDE.toFloat() / minOf(w, h)
            val pw = ((w * k).toInt() / 2) * 2
            val ph = ((h * k).toInt() / 2) * 2
            val item = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(src)))
                .setEffects(Effects(emptyList(), listOf<Effect>(Presentation.createForWidthAndHeight(pw, ph, Presentation.LAYOUT_SCALE_TO_FIT))))
                .build()
            val comp = Composition.Builder(listOf(EditedMediaItemSequence(listOf(item))))
                .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
                .build()
            val encoder = DefaultEncoderFactory.Builder(c)
                .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(8_000_000).build())
                .setEnableFallback(true)
                .build()
            tmp.delete()
            MediaJobs.runTransformer(
                Transformer.Builder(c).setVideoMimeType(MimeTypes.VIDEO_H264).setEncoderFactory(encoder),
                { it.start(comp, tmp.absolutePath) }, {},
            )
            tmp.renameTo(out)
            true
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            tmp.delete()
            failed += clip.file
            false
        }
    }
}
