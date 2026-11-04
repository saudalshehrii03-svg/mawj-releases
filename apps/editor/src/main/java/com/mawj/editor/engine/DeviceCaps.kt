package com.mawj.editor.engine

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File

/** What this phone's hardware can actually do (asked from the device, not assumed). */
object DeviceCaps {

    private val encoders by lazy {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { it.isEncoder }
    }

    /** HEVC 10-bit HDR encoder (HDR10 / HLG), e.g. Galaxy S25 Ultra. */
    val hdrEncoder: Boolean by lazy {
        encoders.any { info ->
            info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, true) } &&
                runCatching {
                    info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_HEVC).profileLevels.any {
                        it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 ||
                            it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10 ||
                            it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus
                    }
                }.getOrDefault(false)
        }
    }

    /** Whether a hardware encoder of [mime] supports [w]x[h]. */
    fun canEncode(mime: String, w: Int, h: Int): Boolean = encoders.any { info ->
        info.supportedTypes.any { it.equals(mime, true) } && runCatching {
            val v = info.getCapabilitiesForType(mime).videoCapabilities
            v.isSizeSupported(w, h) || v.isSizeSupported(h, w)
        }.getOrDefault(false)
    }

    /** True if the video file is HDR (PQ / HLG transfer) or 10-bit HEVC. */
    fun isHdr(file: File): Boolean {
        val ex = MediaExtractor()
        return try {
            ex.setDataSource(file.absolutePath)
            (0 until ex.trackCount).any { t ->
                val f = ex.getTrackFormat(t)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: ""
                if (!mime.startsWith("video/")) return@any false
                val transfer = if (f.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) f.getInteger(MediaFormat.KEY_COLOR_TRANSFER) else 0
                val profile = if (f.containsKey(MediaFormat.KEY_PROFILE)) f.getInteger(MediaFormat.KEY_PROFILE) else 0
                transfer == MediaFormat.COLOR_TRANSFER_ST2084 || transfer == MediaFormat.COLOR_TRANSFER_HLG ||
                    mime == MediaFormat.MIMETYPE_VIDEO_DOLBY_VISION ||
                    (mime == MediaFormat.MIMETYPE_VIDEO_HEVC && profile != 0 && profile != MediaCodecInfo.CodecProfileLevel.HEVCProfileMain)
            }
        } catch (_: Exception) {
            false
        } finally {
            ex.release()
        }
    }
}
