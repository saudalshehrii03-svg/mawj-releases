package com.mawj.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

class UnsupportedAudioException(message: String) : Exception(message)

data class AudioTrackInfo(
    val mime: String,
    val sampleRate: Int,
    val channels: Int,
    val bitrate: Int,
    val durationUs: Long,
)

/** Low-level audio extraction using the platform media APIs (no re-encoding). */
object AudioEngine {

    private const val AAC = "audio/mp4a-latm"
    private const val OPUS = "audio/opus"

    private fun openAudio(context: Context, uri: Uri): Pair<MediaExtractor, Int> {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(context, uri, null)
            for (i in 0 until ex.trackCount) {
                val mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) return ex to i
            }
        } catch (e: Exception) {
            ex.release()
            throw e
        }
        ex.release()
        throw UnsupportedAudioException("المقطع ما فيه صوت")
    }

    fun probe(context: Context, uri: Uri): AudioTrackInfo? {
        val (ex, track) = try { openAudio(context, uri) } catch (_: Exception) { return null }
        try {
            val f = ex.getTrackFormat(track)
            fun int(key: String) = if (f.containsKey(key)) f.getNumber(key)?.toInt() ?: 0 else 0
            val dur = if (f.containsKey(MediaFormat.KEY_DURATION)) f.getLong(MediaFormat.KEY_DURATION) else 0L
            return AudioTrackInfo(
                mime = f.getString(MediaFormat.KEY_MIME) ?: "?",
                sampleRate = int(MediaFormat.KEY_SAMPLE_RATE),
                channels = int(MediaFormat.KEY_CHANNEL_COUNT),
                bitrate = int(MediaFormat.KEY_BIT_RATE),
                durationUs = dur,
            )
        } finally {
            ex.release()
        }
    }

    /** Extension the lossless copy will produce for this source, or null if it can't be copied as-is. */
    fun copyExtension(info: AudioTrackInfo?): String? = when (info?.mime) {
        AAC -> "m4a"
        OPUS -> "ogg"
        else -> null
    }

    /** Copies the compressed audio track bit-for-bit into a new container. */
    fun copyLossless(context: Context, uri: Uri, out: File, onProgress: (Float) -> Unit) {
        val (ex, track) = openAudio(context, uri)
        var muxer: MediaMuxer? = null
        try {
            val format = ex.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME)
            val container = when (mime) {
                AAC -> MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                OPUS -> MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG
                else -> throw UnsupportedAudioException("الصيغة $mime ما تنفع للنسخ المباشر")
            }
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            ex.selectTrack(track)

            val m = MediaMuxer(out.absolutePath, container)
            muxer = m
            val outTrack = m.addTrack(format)
            m.start()

            val size = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceAtLeast(64 * 1024)
            } else 1 shl 20
            val buffer = ByteBuffer.allocateDirect(size)
            val info = MediaCodec.BufferInfo()
            var lastReport = 0L
            while (true) {
                buffer.clear()
                val n = ex.readSampleData(buffer, 0)
                if (n < 0) break
                val t = ex.sampleTime
                if (t >= 0) {
                    info.set(0, n, t, MediaCodec.BUFFER_FLAG_KEY_FRAME)
                    m.writeSampleData(outTrack, buffer, info)
                }
                if (durationUs > 0 && t - lastReport > 250_000) {
                    lastReport = t
                    onProgress((t.toFloat() / durationUs).coerceIn(0f, 1f))
                }
                ex.advance()
            }
            m.stop()
            onProgress(1f)
        } finally {
            try { muxer?.release() } catch (_: Exception) {}
            ex.release()
        }
    }

    /** Decodes the audio track to PCM and writes an uncompressed WAV file. */
    fun decodeToWav(context: Context, uri: Uri, out: File, onProgress: (Float) -> Unit) {
        val (ex, track) = openAudio(context, uri)
        val inFormat = ex.getTrackFormat(track)
        val mime = inFormat.getString(MediaFormat.KEY_MIME)!!
        val durationUs = if (inFormat.containsKey(MediaFormat.KEY_DURATION)) inFormat.getLong(MediaFormat.KEY_DURATION) else 0L
        ex.selectTrack(track)

        val codec = try {
            MediaCodec.createDecoderByType(mime)
        } catch (e: Exception) {
            ex.release()
            throw UnsupportedAudioException("الجهاز ما يدعم فك صيغة $mime")
        }

        var sampleRate = inFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = inFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
        var dataBytes = 0L

        val stream = BufferedOutputStream(FileOutputStream(out), 1 shl 20)
        try {
            stream.write(ByteArray(44)) // header placeholder
            codec.configure(inFormat, null, null, 0)
            codec.start()

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var chunk = ByteArray(64 * 1024)
            var lastReport = 0L

            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)!!
                        val n = ex.readSampleData(inBuf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, n, ex.sampleTime.coerceAtLeast(0), 0)
                            ex.advance()
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) pcmEncoding = f.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    }
                    outIdx >= 0 -> {
                        if (info.size > 0) {
                            val outBuf = codec.getOutputBuffer(outIdx)!!
                            outBuf.position(info.offset)
                            outBuf.limit(info.offset + info.size)
                            if (chunk.size < info.size) chunk = ByteArray(info.size)
                            outBuf.get(chunk, 0, info.size)
                            stream.write(chunk, 0, info.size)
                            dataBytes += info.size
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        if (durationUs > 0 && info.presentationTimeUs - lastReport > 250_000) {
                            lastReport = info.presentationTimeUs
                            onProgress((info.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 1f))
                        }
                    }
                }
            }
        } finally {
            try { codec.stop() } catch (_: Exception) {}
            codec.release()
            ex.release()
            stream.close()
        }

        val (formatTag, bits) = when (pcmEncoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> 3 to 32
            AudioFormat.ENCODING_PCM_8BIT -> 1 to 8
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> 1 to 24
            AudioFormat.ENCODING_PCM_32BIT -> 1 to 32
            else -> 1 to 16
        }
        writeWavHeader(out, sampleRate, channels, bits, formatTag, dataBytes)
        onProgress(1f)
    }

    private fun writeWavHeader(file: File, sampleRate: Int, channels: Int, bits: Int, formatTag: Int, dataBytes: Long) {
        val blockAlign = channels * bits / 8
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray(Charsets.US_ASCII))
        h.putInt((36 + dataBytes).coerceAtMost(0xFFFFFFFFL).toInt())
        h.put("WAVE".toByteArray(Charsets.US_ASCII))
        h.put("fmt ".toByteArray(Charsets.US_ASCII))
        h.putInt(16)
        h.putShort(formatTag.toShort())
        h.putShort(channels.toShort())
        h.putInt(sampleRate)
        h.putInt(sampleRate * blockAlign)
        h.putShort(blockAlign.toShort())
        h.putShort(bits.toShort())
        h.put("data".toByteArray(Charsets.US_ASCII))
        h.putInt(dataBytes.coerceAtMost(0xFFFFFFFFL).toInt())
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(0)
            raf.write(h.array())
        }
    }
}
