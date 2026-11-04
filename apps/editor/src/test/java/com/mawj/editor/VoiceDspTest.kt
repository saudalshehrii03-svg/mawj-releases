package com.mawj.editor

import com.mawj.editor.engine.VoiceDsp
import com.mawj.editor.model.AudioFx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class VoiceDspTest {

    private val rate = 48000
    private val ch = 2

    /** Runs the DSP in uneven chunks (like a real pipeline) and returns the output. */
    private fun run(fx: AudioFx, input: FloatArray): FloatArray {
        val dsp = VoiceDsp(fx, rate, ch)
        val out = input.copyOf()
        val rnd = Random(7)
        var pos = 0
        val frames = input.size / ch
        while (pos < frames) {
            val n = minOf(frames - pos, 200 + rnd.nextInt(3000))
            val chunk = out.copyOfRange(pos * ch, (pos + n) * ch)
            dsp.process(chunk, n)
            System.arraycopy(chunk, 0, out, pos * ch, n * ch)
            pos += n
        }
        return out
    }

    private fun rms(a: FloatArray, from: Int = 0, to: Int = a.size): Double {
        var s = 0.0
        for (i in from until to) s += a[i] * a[i]
        return sqrt(s / (to - from))
    }

    private fun tone(freq: Double, amp: Float, secs: Double, noise: Float = 0f): FloatArray {
        val n = (rate * secs).toInt()
        val r = Random(1)
        return FloatArray(n * ch) { i ->
            val t = (i / ch).toDouble() / rate
            (amp * sin(2 * PI * freq * t)).toFloat() + noise * (r.nextFloat() * 2 - 1)
        }
    }

    @Test
    fun everyEffectKeepsLengthAndStaysFinite() {
        val input = tone(440.0, 0.4f, 1.5, 0.05f)
        val presets = listOf(
            AudioFx(denoise = 1f), AudioFx(isolate = true), AudioFx(eqLow = 12f, eqMid = -6f, eqHigh = 9f),
            AudioFx(compress = 1f), AudioFx(echo = 1f), AudioFx(reverb = 1f),
            AudioFx(voice = "robot"), AudioFx(voice = "radio"), AudioFx(voice = "phone"), AudioFx(voice = "megaphone"),
            AudioFx(voice = "underwater"), AudioFx(voice = "monster"), AudioFx(voice = "cave"), AudioFx(voice = "echo"),
            AudioFx(denoise = 0.7f, isolate = true, compress = 0.5f, eqMid = 4f, echo = 0.3f, reverb = 0.4f),
        )
        for (fx in presets) {
            val out = run(fx, input)
            assertEquals("length changed for $fx", input.size, out.size)
            assertTrue("NaN/Inf for $fx", out.all { it.isFinite() })
            assertTrue("clipping for $fx", out.all { it in -1.0001f..1.0001f })
            assertTrue("silence for $fx", rms(out, out.size / 3) > 0.005)
        }
    }

    @Test
    fun noiseReductionLowersNoiseFloor() {
        val noise = FloatArray(rate * 3 * ch) { (Random(3).nextFloat() * 2 - 1) * 0.08f }.let { arr ->
            val r = Random(3); FloatArray(arr.size) { (r.nextFloat() * 2 - 1) * 0.08f }
        }
        val out = run(AudioFx(denoise = 1f), noise)
        val tail = noise.size * 2 / 3
        val before = rms(noise, tail)
        val after = rms(out, tail)
        assertTrue("noise not reduced: $before -> $after", after < before * 0.6)
    }

    @Test
    fun noiseReductionKeepsSpeechLikeTone() {
        val input = tone(300.0, 0.4f, 2.0, 0.01f)
        val out = run(AudioFx(denoise = 0.8f), input)
        val from = input.size / 4
        val ratio = rms(out, from) / rms(input, from)
        assertTrue("tone level changed too much: $ratio", ratio in 0.6..1.4)
    }

    @Test
    fun bassBoostRaisesLowFrequencies() {
        val input = tone(80.0, 0.2f, 1.0)
        val out = run(AudioFx(eqLow = 12f), input)
        assertTrue(rms(out, input.size / 2) > rms(input, input.size / 2) * 1.8)
    }

    @Test
    fun fftRoundTrip() {
        val re = FloatArray(1024) { sin(it * 0.1f) }
        val im = FloatArray(1024)
        val copy = re.copyOf()
        VoiceDsp.fft(re, im, false)
        VoiceDsp.fft(re, im, true)
        for (i in re.indices) assertEquals(copy[i], re[i], 1e-3f)
    }
}
