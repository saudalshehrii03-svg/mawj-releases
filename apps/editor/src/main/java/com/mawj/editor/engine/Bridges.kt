package com.mawj.editor.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import androidx.annotation.OptIn
import androidx.media3.common.VideoCompositorSettings
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.OverlaySettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItemSequence
import com.mawj.editor.data.ProjectStore
import com.mawj.editor.model.Caps
import com.mawj.editor.model.Clip
import com.mawj.editor.model.ClipType
import com.mawj.editor.model.Project
import com.mawj.editor.model.Transition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import kotlin.math.abs

/**
 * Overlapping transitions ("bridges"): the last part of clip A and the first part of clip B are rendered
 * together into a short video using Media3's multi-track compositor, then dropped into the main timeline.
 */
@OptIn(UnstableApi::class)
object Bridges {

    private fun prefs(c: Context) = c.getSharedPreferences("engine", Context.MODE_PRIVATE)

    /** Whether sequence 0 is drawn on top by the compositor (learned and verified on the device). */
    private fun topFirst(c: Context) = prefs(c).getBoolean("top_first", true)

    /** Loads learned capabilities; they are re-tested after every app update. */
    fun loadCaps(c: Context) {
        val p = prefs(c)
        val ver = runCatching { c.packageManager.getPackageInfo(c.packageName, 0).longVersionCode }.getOrDefault(0L)
        if (p.getLong("ver", -1) != ver) {
            p.edit().clear().putLong("ver", ver).apply()
            clear(c)
        }
        Caps.overlapSupported = p.getBoolean("overlap_ok", true)
    }

    private fun markUnsupported(c: Context) {
        prefs(c).edit().putBoolean("overlap_ok", false).apply()
        Caps.overlapSupported = false
    }

    fun key(project: Project, i: Int, w: Int, h: Int, forExport: Boolean, topFirst: Boolean): String {
        val a = project.clips[i]
        val b = project.clips[i + 1]
        val raw = a.toJson().toString() + b.toJson().toString() + project.overlapUs(i) + w + "x" + h + forExport + topFirst
        val d = MessageDigest.getInstance("SHA-1").digest(raw.toByteArray())
        return d.joinToString("") { "%02x".format(it) }.substring(0, 24)
    }

    private fun dir(c: Context) = File(c.cacheDir, "bridges").apply { mkdirs() }

    fun cached(c: Context, project: Project, i: Int, w: Int, h: Int, forExport: Boolean): File? {
        val f = File(dir(c), key(project, i, w, h, forExport, topFirst(c)) + ".mp4")
        return if (f.exists() && f.length() > 0) f else null
    }

    /** Boundaries that need a bridge. */
    fun needed(project: Project): List<Int> = (0 until project.clips.size - 1).filter { project.overlapUs(it) > 0 }

    /**
     * Renders (or reuses) the bridge after clip [i]. Must be called from the main thread.
     * Returns null when the device can't composite two video tracks (overlaps then fall back to a fade).
     */
    suspend fun render(
        context: Context, store: ProjectStore, project: Project, i: Int,
        w: Int, h: Int, forExport: Boolean, bitrate: Int,
    ): File? {
        if (!Caps.overlapSupported) return null
        cached(context, project, i, w, h, forExport)?.let { return it }
        val prefs = prefs(context)
        var top = topFirst(context)
        repeat(2) { attempt ->
            val out = File(dir(context), key(project, i, w, h, forExport, top) + ".mp4")
            val tmp = File(dir(context), "rendering.mp4").apply { delete() }
            try {
                val comp = composition(context, store, project, i, w, h, forExport, top)
                MediaJobs.exportVideo(context, comp, bitrate, tmp) {}
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                tmp.delete()
                if (!prefs.getBoolean("overlap_verified", false)) markUnsupported(context)
                return null
            }
            if (prefs.getBoolean("order_verified", false)) {
                tmp.renameTo(out); return out
            }
            val ok = withContext(Dispatchers.IO) { verifyOrder(context, store, project, i, tmp) }
            if (ok != false) {
                prefs.edit().putBoolean("overlap_verified", true).apply()
                if (ok == true) prefs.edit().putBoolean("order_verified", true).putBoolean("top_first", top).apply()
                tmp.renameTo(out); return out
            }
            // The compositor drew the tracks the other way round: flip and render again.
            tmp.delete()
            top = !top
            prefs.edit().putBoolean("top_first", top).apply()
            if (attempt == 1) return null
        }
        return null
    }

    private fun composition(
        context: Context, store: ProjectStore, project: Project, i: Int,
        w: Int, h: Int, forExport: Boolean, topFirst: Boolean,
    ): Composition {
        val a = project.clips[i]
        val b = project.clips[i + 1]
        val d = project.overlapUs(i)
        val opt = BuildOptions(w, h, forExport)
        val aItem = CompositionFactory.clipItem(context, store, project, a, opt, a.timelineUs - d, d, null,
            GainAudioProcessor(a.volume, 0, d, d))
        val bItem = CompositionFactory.clipItem(context, store, project, b, opt, 0, d, null,
            GainAudioProcessor(b.volume, d, 0, d))
        val seqA = EditedMediaItemSequence(listOf(aItem))
        val seqB = EditedMediaItemSequence(listOf(bItem))
        val bId = if (topFirst) 0 else 1
        val kind = a.transition

        val settings = object : VideoCompositorSettings {
            @Volatile private var base = Long.MIN_VALUE

            override fun getOutputSize(inputSizes: List<Size>): Size = Size(w, h)

            override fun getOverlaySettings(inputId: Int, presentationTimeUs: Long): OverlaySettings {
                if (base == Long.MIN_VALUE || presentationTimeUs < base) base = presentationTimeUs
                val p = ((presentationTimeUs - base).toFloat() / d).coerceIn(0f, 1f)
                val e = ease(p)
                val s = OverlaySettings.Builder()
                if (inputId == bId) {
                    when (kind) {
                        Transition.DISSOLVE_ZOOM -> { s.setAlphaScale(e); val k = 1.15f - 0.15f * e; s.setScale(k, k) }
                        Transition.SLIDE_OVER_LEFT -> { s.setOverlayFrameAnchor(-1f, 0f); s.setBackgroundFrameAnchor(1f - 2f * e, 0f) }
                        Transition.SLIDE_OVER_UP -> { s.setOverlayFrameAnchor(0f, 1f); s.setBackgroundFrameAnchor(0f, -1f + 2f * e) }
                        else -> s.setAlphaScale(e)
                    }
                } else if (kind == Transition.DISSOLVE_ZOOM) {
                    val k = 1f + 0.12f * e
                    s.setScale(k, k)
                }
                return s.build()
            }
        }

        val builder = Composition.Builder(if (topFirst) listOf(seqB, seqA) else listOf(seqA, seqB))
            .setVideoCompositorSettings(settings)
            .experimentalSetForceAudioTrack(true)
        if (forExport) builder.setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
        return builder.build()
    }

    /**
     * Compares the end of the rendered bridge with clip A and clip B.
     * true = B ends on top (correct), false = A stayed on top (wrong order), null = can't tell.
     */
    private fun verifyOrder(context: Context, store: ProjectStore, project: Project, i: Int, bridge: File): Boolean? {
        val a = project.clips[i]
        val b = project.clips[i + 1]
        val d = project.overlapUs(i)
        val end = frameOf(bridge.absolutePath, (d * 0.92).toLong(), false) ?: return null
        val fa = frame(store, project, a, a.trimEndUs - (d * 0.08 * a.speed).toLong()) ?: return null
        val fb = frame(store, project, b, b.trimStartUs + (d * 0.92 * b.speed).toLong()) ?: return null
        val da = diff(end, fa)
        val db = diff(end, fb)
        return when {
            db < da * 0.85f -> true
            da < db * 0.85f -> false
            else -> null
        }
    }

    private fun frame(store: ProjectStore, project: Project, c: Clip, atUs: Long): Bitmap? {
        val path = store.mediaFile(project.id, c.file).absolutePath
        return if (c.type == ClipType.IMAGE) {
            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = 8 })
        } else frameOf(path, atUs, true)
    }

    private fun frameOf(path: String, atUs: Long, sync: Boolean): Bitmap? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(path)
            r.getScaledFrameAtTime(atUs, if (sync) MediaMetadataRetriever.OPTION_CLOSEST_SYNC else MediaMetadataRetriever.OPTION_CLOSEST, 96, 96)
        } catch (_: Exception) {
            null
        } finally {
            r.release()
        }
    }

    /** Mean color difference on a 16x16 thumbnail of the central area (ignores letterbox bars). */
    private fun diff(x: Bitmap, y: Bitmap): Float {
        fun small(bm: Bitmap): Bitmap {
            val cw = bm.width / 2
            val ch = bm.height / 2
            val crop = Bitmap.createBitmap(bm, bm.width / 4, bm.height / 4, cw.coerceAtLeast(1), ch.coerceAtLeast(1))
            return Bitmap.createScaledBitmap(crop, 16, 16, true)
        }
        val sx = small(x)
        val sy = small(y)
        var total = 0f
        for (py in 0 until 16) for (px in 0 until 16) {
            val p = sx.getPixel(px, py)
            val q = sy.getPixel(px, py)
            total += abs(((p shr 16) and 255) - ((q shr 16) and 255)) +
                abs(((p shr 8) and 255) - ((q shr 8) and 255)) +
                abs((p and 255) - (q and 255))
        }
        return total / 256f
    }

    fun clear(context: Context) = dir(context).listFiles()?.forEach { it.delete() }
}
