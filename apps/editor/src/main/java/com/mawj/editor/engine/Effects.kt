package com.mawj.editor.engine

import android.graphics.Matrix
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.MatrixTransformation
import androidx.media3.effect.RgbMatrix
import com.mawj.editor.model.Transition
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Media3 may hand effects either composition-relative or clip-relative timestamps.
 * We learn which one from the first frame of the first non-initial clip of an export.
 */
object ClockMode {
    @Volatile var composition: Boolean? = null
}

class ClipClock(private val startUs: Long, val durationUs: Long, private val learn: Boolean) {
    @Volatile private var learned = false

    fun relative(ptsUs: Long): Long {
        if (learn && !learned && startUs > 0) {
            learned = true
            if (abs(ptsUs - startUs) < 250_000) ClockMode.composition = true
            else if (ptsUs < 250_000) ClockMode.composition = false
        }
        val asComposition = ptsUs - startUs
        return when (ClockMode.composition) {
            true -> asComposition
            false -> ptsUs
            null -> if (asComposition in -250_000..durationUs + 250_000) asComposition else ptsUs
        }
    }
}

/** Describes the transition entering (head) and leaving (tail) one clip. */
class TransitionPlan(
    val head: Transition,
    val headUs: Long,
    val tail: Transition,
    val tailUs: Long,
    val clock: ClipClock,
) {
    val active get() = (head != Transition.NONE && headUs > 0) || (tail != Transition.NONE && tailUs > 0)

    /** Returns (kind, progress 0..1, entering) for the frame, or null when no transition applies. */
    fun phase(ptsUs: Long): Triple<Transition, Float, Boolean>? {
        val r = clock.relative(ptsUs)
        if (head != Transition.NONE && headUs > 0 && r < headUs) {
            return Triple(head, (r.toFloat() / headUs).coerceIn(0f, 1f), true)
        }
        val tailStart = clock.durationUs - tailUs
        if (tail != Transition.NONE && tailUs > 0 && r > tailStart) {
            return Triple(tail, ((r - tailStart).toFloat() / tailUs).coerceIn(0f, 1f), false)
        }
        return null
    }
}

internal fun ease(p: Float): Float = if (p < 0.5f) 4 * p * p * p else 1 - (-2 * p + 2).let { it * it * it } / 2

/** Deterministic pseudo random in [-1, 1] that changes every ~40ms. */
private fun noise(ptsUs: Long, salt: Int): Float {
    val n = (ptsUs / 40_000).toInt() * 374761393 + salt * 668265263
    val h = (n xor (n ushr 13)) * 1274126177
    return ((h xor (h ushr 16)) and 0xFFFF) / 32767.5f - 1f
}

@OptIn(UnstableApi::class)
object TransitionEffects {

    /** Amount of "transition-ness": 0 at the clip body, 1 at the cut. */
    private fun strength(progress: Float, entering: Boolean) =
        if (entering) 1f - ease(progress) else ease(progress)

    fun matrix(plan: TransitionPlan): MatrixTransformation = MatrixTransformation { pts ->
        val m = Matrix()
        val ph = plan.phase(pts) ?: return@MatrixTransformation m
        val (kind, p, entering) = ph
        val s = strength(p, entering)
        when (kind) {
            Transition.ZOOM_IN -> { val k = if (entering) 1f + 0.6f * s else 1f + 0.6f * s; m.setScale(k, k) }
            Transition.ZOOM_OUT -> { val k = if (entering) 1f + 0.5f * s else 1f - 0.45f * s; m.setScale(k, k) }
            Transition.SLIDE_LEFT -> m.setTranslate(if (entering) 2f * s else -2f * s, 0f)
            Transition.SLIDE_RIGHT -> m.setTranslate(if (entering) -2f * s else 2f * s, 0f)
            Transition.SLIDE_UP -> m.setTranslate(0f, if (entering) -2f * s else 2f * s)
            Transition.SLIDE_DOWN -> m.setTranslate(0f, if (entering) 2f * s else -2f * s)
            Transition.SPIN -> {
                val k = 1f - 0.5f * s
                m.setScale(k, k)
                m.postRotate(if (entering) -180f * s else 180f * s)
            }
            Transition.SHAKE -> {
                val a = 0.07f * s
                m.setTranslate(a * noise(pts, 1), a * noise(pts, 2))
                val k = 1f + 0.08f * s
                m.preScale(k, k)
            }
            Transition.GLITCH -> {
                val a = 0.12f * s
                val k = 1f + 0.1f * s * (noise(pts, 3) + 1f) / 2f
                m.setScale(k, k)
                m.postTranslate(a * noise(pts, 4), 0.3f * a * noise(pts, 5))
            }
            else -> {}
        }
        m
    }

    fun color(plan: TransitionPlan): RgbMatrix = RgbMatrix { pts, _ ->
        val id = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
        val ph = plan.phase(pts) ?: return@RgbMatrix id
        val (kind, p, entering) = ph
        val s = strength(p, entering)
        when (kind) {
            Transition.FADE, Transition.SPIN, Transition.ZOOM_OUT -> {
                val k = if (kind == Transition.FADE) 1f - s else 1f - 0.6f * s
                floatArrayOf(k, 0f, 0f, 0f, 0f, k, 0f, 0f, 0f, 0f, k, 0f, 0f, 0f, 0f, 1f)
            }
            Transition.FLASH, Transition.ZOOM_IN -> {
                val w = if (kind == Transition.FLASH) s else 0.5f * s * s
                val k = 1f - w
                floatArrayOf(k, 0f, 0f, 0f, 0f, k, 0f, 0f, 0f, 0f, k, 0f, w, w, w, 1f)
            }
            Transition.GLITCH -> {
                if (noise(pts, 6) > 1f - 1.4f * s) {
                    // Channel swap for a digital glitch look.
                    floatArrayOf(0f, 0f, 1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f)
                } else id
            }
            else -> id
        }
    }
}

/** Volume with optional fade in / fade out, applied to 16-bit or float PCM. */
@OptIn(UnstableApi::class)
class GainAudioProcessor(
    private val gain: Float,
    private val fadeInUs: Long = 0,
    private val fadeOutUs: Long = 0,
    private val totalUs: Long = 0,
) : BaseAudioProcessor() {

    private var frames = 0L

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (abs(gain - 1f) < 0.001f && fadeInUs <= 0 && fadeOutUs <= 0) return AudioProcessor.AudioFormat.NOT_SET
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    private fun gainAt(frame: Long, sampleRate: Int): Float {
        var g = gain
        if (fadeInUs > 0 || fadeOutUs > 0) {
            val t = frame * 1_000_000L / sampleRate
            if (fadeInUs > 0) g *= min(1f, t.toFloat() / fadeInUs)
            if (fadeOutUs > 0 && totalUs > 0) g *= min(1f, ((totalUs - t).toFloat() / fadeOutUs).coerceAtLeast(0f))
        }
        return g
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val fmt = inputAudioFormat
        val out = replaceOutputBuffer(size)
        val ch = fmt.channelCount.coerceAtLeast(1)
        if (fmt.encoding == C.ENCODING_PCM_FLOAT) {
            while (inputBuffer.remaining() >= 4 * ch) {
                val g = gainAt(frames++, fmt.sampleRate)
                repeat(ch) { out.putFloat((inputBuffer.float * g).coerceIn(-1f, 1f)) }
            }
        } else {
            while (inputBuffer.remaining() >= 2 * ch) {
                val g = gainAt(frames++, fmt.sampleRate)
                repeat(ch) {
                    out.putShort((inputBuffer.short * g).roundToInt().coerceIn(-32768, 32767).toShort())
                }
            }
        }
        // Copy any trailing partial frame unchanged.
        while (inputBuffer.hasRemaining()) out.put(inputBuffer.get())
        out.flip()
    }

    override fun onFlush() { frames = 0 }
    override fun onReset() { frames = 0 }
}
