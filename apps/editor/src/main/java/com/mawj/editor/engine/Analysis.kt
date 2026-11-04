package com.mawj.editor.engine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PointF
import android.media.AudioFormat
import android.media.FaceDetector
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import java.io.File
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * On-device "smart" analysis used by the AI tools: audio loudness envelope (silence removal, beat detection),
 * face-aware reframing and automatic color correction. Everything runs offline on the phone.
 */
object Analysis {

    /** RMS loudness per [windowUs] window of the first audio track between [startUs] and [endUs] (source time). */
    fun envelope(file: File, startUs: Long, endUs: Long, windowUs: Long): FloatArray {
        val ex = MediaExtractor()
        ex.setDataSource(file.absolutePath)
        val track = (0 until ex.trackCount).firstOrNull {
            ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: run { ex.release(); return FloatArray(0) }
        val fmt = ex.getTrackFormat(track)
        ex.selectTrack(track)
        ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(fmt, null, null, 0)
        codec.start()

        var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var floatPcm = false
        val windows = max(1, ((endUs - startUs) / windowUs).toInt())
        val sum = DoubleArray(windows)
        val cnt = IntArray(windows)
        val info = MediaCodec.BufferInfo()
        var inDone = false
        var outDone = false
        try {
            while (!outDone) {
                if (!inDone) {
                    val i = codec.dequeueInputBuffer(5_000)
                    if (i >= 0) {
                        val n = ex.readSampleData(codec.getInputBuffer(i)!!, 0)
                        if (n < 0 || ex.sampleTime > endUs + 200_000) {
                            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true
                        } else {
                            codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance()
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 5_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = codec.outputFormat
                    if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    floatPcm = f.containsKey(MediaFormat.KEY_PCM_ENCODING) && f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                } else if (o >= 0) {
                    val buf = codec.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder())
                    buf.position(info.offset); buf.limit(info.offset + info.size)
                    val bytesPerFrame = channels * (if (floatPcm) 4 else 2)
                    var frame = 0L
                    while (buf.remaining() >= bytesPerFrame) {
                        var v = 0f
                        repeat(channels) { v += if (floatPcm) buf.float else buf.short / 32768f }
                        v /= channels
                        val t = info.presentationTimeUs + frame * 1_000_000L / rate
                        val w = ((t - startUs) / windowUs).toInt()
                        if (w in 0 until windows) { sum[w] += (v * v).toDouble(); cnt[w]++ }
                        frame++
                    }
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
            ex.release()
        }
        return FloatArray(windows) { if (cnt[it] > 0) sqrt(sum[it] / cnt[it]).toFloat() else 0f }
    }

    /**
     * Speech/sound segments to keep (source time) after removing pauses longer than [minSilenceUs].
     * The threshold adapts to the clip's own loudness.
     */
    fun keepSegments(file: File, startUs: Long, endUs: Long, minSilenceUs: Long = 500_000, padUs: Long = 120_000): List<LongRange> {
        val win = 20_000L
        val env = envelope(file, startUs, endUs, win)
        if (env.isEmpty()) return listOf(startUs..endUs)
        val sorted = env.sorted()
        val loud = sorted[(sorted.size * 0.9).toInt().coerceAtMost(sorted.lastIndex)]
        val quiet = sorted[(sorted.size * 0.1).toInt()]
        val threshold = max(quiet * 2f, loud * 0.12f).coerceAtLeast(0.006f)
        val silent = BooleanArray(env.size) { env[it] < threshold }
        val keep = ArrayList<LongRange>()
        var i = 0
        var segStart = 0
        while (i < env.size) {
            if (silent[i]) {
                var j = i
                while (j < env.size && silent[j]) j++
                if ((j - i) * win >= minSilenceUs) {
                    if (i > segStart) keep += (startUs + segStart * win)..(startUs + i * win)
                    segStart = j
                }
                i = j
            } else i++
        }
        if (segStart < env.size) keep += (startUs + segStart * win)..endUs
        // Pad and merge
        val padded = keep.map { (it.first - padUs).coerceAtLeast(startUs)..(it.last + padUs).coerceAtMost(endUs) }
        val merged = ArrayList<LongRange>()
        for (r in padded) {
            val last = merged.lastOrNull()
            if (last != null && r.first <= last.last) merged[merged.lastIndex] = last.first..max(last.last, r.last)
            else merged += r
        }
        return merged.filter { it.last - it.first > 150_000 }
    }

    /** Beat positions (source time) using onset strength peaks on a 10 ms loudness envelope. */
    fun beats(file: File, startUs: Long, endUs: Long): List<Long> {
        val win = 10_000L
        val env = envelope(file, startUs, endUs, win)
        if (env.size < 10) return emptyList()
        val onset = FloatArray(env.size)
        for (i in 1 until env.size) onset[i] = max(0f, env[i] - env[i - 1])
        // Local adaptive threshold (mean + 1.4 * std over ~1s)
        val out = ArrayList<Long>()
        val half = 50
        var lastBeat = -1000
        for (i in 1 until onset.size - 1) {
            if (onset[i] <= onset[i - 1] || onset[i] < onset[i + 1]) continue
            val a = max(0, i - half); val b = minOf(onset.size, i + half)
            var mean = 0f; for (k in a until b) mean += onset[k]; mean /= (b - a)
            var v = 0f; for (k in a until b) v += (onset[k] - mean) * (onset[k] - mean); val std = sqrt(v / (b - a))
            if (onset[i] > mean + 1.4f * std && onset[i] > 0.01f && i - lastBeat >= 25) {
                out += startUs + i * win
                lastBeat = i
            }
        }
        return out
    }

    // ---------- Vision ----------

    private fun frames(file: File, isImage: Boolean, fromUs: Long, toUs: Long, n: Int, maxSide: Int): List<Bitmap> {
        if (isImage) {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, o)
            var s = 1
            while (max(o.outWidth, o.outHeight) / (s * 2) >= maxSide) s *= 2
            return listOfNotNull(BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = s }))
        }
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(file.absolutePath)
            (0 until n).mapNotNull { k ->
                val t = fromUs + (toUs - fromUs) * (k + 1) / (n + 1)
                r.getScaledFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, maxSide, maxSide)
            }
        } finally { r.release() }
    }

    /** Display size of a media file (rotation applied). */
    fun size(file: File, isImage: Boolean): Pair<Int, Int> {
        if (isImage) {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, o)
            return o.outWidth to o.outHeight
        }
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(file.absolutePath)
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rot == 90 || rot == 270) h to w else w to h
        } finally { r.release() }
    }

    /** Average face position (0..1) across a few frames, or null if no face was found. */
    fun faceFocus(file: File, isImage: Boolean, fromUs: Long, toUs: Long): PointF? {
        var sx = 0f; var sy = 0f; var n = 0
        for (bm in frames(file, isImage, fromUs, toUs, 6, 480)) {
            val w = bm.width and 1.inv()
            val h = bm.height
            val rgb = Bitmap.createScaledBitmap(bm, w, h, true).copy(Bitmap.Config.RGB_565, false)
            val faces = arrayOfNulls<FaceDetector.Face>(4)
            val found = runCatching { FaceDetector(w, h, 4).findFaces(rgb, faces) }.getOrDefault(0)
            // Weight by face size so the main subject wins.
            var bestW = 0f; val p = PointF()
            for (k in 0 until found) {
                val f = faces[k] ?: continue
                val e = f.eyesDistance()
                if (e > bestW) { bestW = e; f.getMidPoint(p) }
            }
            if (bestW > 0f) { sx += p.x / w; sy += (p.y + bestW * 0.4f) / h; n++ }
        }
        return if (n == 0) null else PointF(sx / n, sy / n)
    }

    /** Suggested brightness / contrast / saturation from the clip's luminance and color statistics. */
    fun autoEnhance(file: File, isImage: Boolean, fromUs: Long, toUs: Long): FloatArray {
        var lumSum = 0.0; var lumSq = 0.0; var satSum = 0.0; var count = 0
        for (bm in frames(file, isImage, fromUs, toUs, 4, 96)) {
            for (y in 0 until bm.height step 2) for (x in 0 until bm.width step 2) {
                val c = bm.getPixel(x, y)
                val r = Color.red(c) / 255f; val g = Color.green(c) / 255f; val b = Color.blue(c) / 255f
                val l = 0.2126f * r + 0.7152f * g + 0.0722f * b
                val mx = maxOf(r, g, b); val mn = minOf(r, g, b)
                lumSum += l; lumSq += l * l; satSum += if (mx > 0f) (mx - mn) / mx else 0f; count++
            }
        }
        if (count == 0) return floatArrayOf(0f, 0f, 0f)
        val mean = (lumSum / count).toFloat()
        val std = sqrt(max(0.0, lumSq / count - mean * mean)).toFloat()
        val sat = (satSum / count).toFloat()
        val brightness = ((0.48f - mean) * 0.6f).coerceIn(-0.2f, 0.25f)
        val contrast = ((0.22f - std) * 1.2f).coerceIn(-0.1f, 0.3f)
        val saturation = ((0.38f - sat) * 0.9f).coerceIn(-0.15f, 0.35f)
        return floatArrayOf(brightness, contrast, saturation)
    }

    @Suppress("unused")
    private fun diff(a: Float, b: Float) = abs(a - b)
}
