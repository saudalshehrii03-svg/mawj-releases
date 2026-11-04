package com.mawj.editor.engine

import com.mawj.editor.model.AudioFx
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Streaming audio effects chain (pure Kotlin, float PCM, interleaved):
 * high-pass → spectral noise reduction → 3-band EQ → voice character → compressor → echo → reverb → limiter.
 * Output length always equals input length (latency of the noise reducer is compensated).
 */
class VoiceDsp(private val fx: AudioFx, private val rate: Int, private val ch: Int) {

    // ---------- Biquad ----------
    private class Biquad(var b0: Float, var b1: Float, var b2: Float, var a1: Float, var a2: Float) {
        private val z1 = FloatArray(8); private val z2 = FloatArray(8)
        fun run(x: Float, c: Int): Float {
            val y = b0 * x + z1[c]
            z1[c] = b1 * x - a1 * y + z2[c]
            z2[c] = b2 * x - a2 * y
            return y
        }
        companion object {
            private fun make(b0: Double, b1: Double, b2: Double, a0: Double, a1: Double, a2: Double) =
                Biquad((b0 / a0).toFloat(), (b1 / a0).toFloat(), (b2 / a0).toFloat(), (a1 / a0).toFloat(), (a2 / a0).toFloat())

            fun highPass(f: Double, fs: Int, q: Double = 0.707): Biquad {
                val w = 2 * PI * f / fs; val al = sin(w) / (2 * q); val c = cos(w)
                return make((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + al, -2 * c, 1 - al)
            }
            fun lowPass(f: Double, fs: Int, q: Double = 0.707): Biquad {
                val w = 2 * PI * f / fs; val al = sin(w) / (2 * q); val c = cos(w)
                return make((1 - c) / 2, 1 - c, (1 - c) / 2, 1 + al, -2 * c, 1 - al)
            }
            fun peak(f: Double, fs: Int, db: Double, q: Double = 0.9): Biquad {
                val a = 10.0.pow(db / 40); val w = 2 * PI * f / fs; val al = sin(w) / (2 * q); val c = cos(w)
                return make(1 + al * a, -2 * c, 1 - al * a, 1 + al / a, -2 * c, 1 - al / a)
            }
            fun lowShelf(f: Double, fs: Int, db: Double): Biquad {
                val a = 10.0.pow(db / 40); val w = 2 * PI * f / fs; val c = cos(w); val s = sin(w)
                val al = s / 2 * sqrt(2.0); val sa = 2 * sqrt(a) * al
                return make(a * ((a + 1) - (a - 1) * c + sa), 2 * a * ((a - 1) - (a + 1) * c), a * ((a + 1) - (a - 1) * c - sa),
                    (a + 1) + (a - 1) * c + sa, -2 * ((a - 1) + (a + 1) * c), (a + 1) + (a - 1) * c - sa)
            }
            fun highShelf(f: Double, fs: Int, db: Double): Biquad {
                val a = 10.0.pow(db / 40); val w = 2 * PI * f / fs; val c = cos(w); val s = sin(w)
                val al = s / 2 * sqrt(2.0); val sa = 2 * sqrt(a) * al
                return make(a * ((a + 1) + (a - 1) * c + sa), -2 * a * ((a - 1) + (a + 1) * c), a * ((a + 1) + (a - 1) * c - sa),
                    (a + 1) - (a - 1) * c + sa, 2 * ((a - 1) - (a + 1) * c), (a + 1) - (a - 1) * c - sa)
            }
        }
    }

    private val filters = ArrayList<Biquad>()
    private val voice = fx.voice

    init {
        val nyq = rate / 2.0 - 100
        if (fx.isolate || fx.denoise > 0f) filters += Biquad.highPass(if (fx.isolate) 100.0 else 70.0, rate)
        if (fx.isolate) filters += Biquad.lowPass(min(9000.0, nyq), rate)
        if (fx.eqLow != 0f) filters += Biquad.lowShelf(160.0, rate, fx.eqLow.toDouble())
        if (fx.eqMid != 0f) filters += Biquad.peak(1200.0, rate, fx.eqMid.toDouble())
        if (fx.eqHigh != 0f) filters += Biquad.highShelf(min(6500.0, nyq), rate, fx.eqHigh.toDouble())
        if (fx.isolate) filters += Biquad.peak(2800.0, rate, 3.0, 1.0) // speech presence
        when (voice) {
            "radio" -> { filters += Biquad.highPass(450.0, rate); filters += Biquad.lowPass(min(3200.0, nyq), rate); filters += Biquad.peak(1500.0, rate, 5.0) }
            "phone" -> { filters += Biquad.highPass(350.0, rate, 1.0); filters += Biquad.lowPass(min(3300.0, nyq), rate, 1.0) }
            "megaphone" -> { filters += Biquad.highPass(600.0, rate); filters += Biquad.lowPass(min(3800.0, nyq), rate); filters += Biquad.peak(2000.0, rate, 8.0) }
            "underwater" -> { filters += Biquad.lowPass(500.0, rate, 1.2); filters += Biquad.lowPass(700.0, rate) }
            "deep" -> filters += Biquad.lowShelf(180.0, rate, 5.0)
            "monster" -> filters += Biquad.lowShelf(200.0, rate, 7.0)
        }
    }

    // ---------- Spectral noise reduction (STFT, sqrt-Hann, 50% overlap) ----------
    private val n = 1024
    private val hop = n / 2
    private val win = FloatArray(n) { sqrt(0.5f - 0.5f * cos(2 * PI.toFloat() * it / n)) }
    private val nr = fx.denoise.coerceIn(0f, 1f).let { if (fx.isolate) max(it, 0.6f) else it }
    private inner class Nr {
        val inBuf = FloatArray(n)
        var fill = n - hop
        val ola = FloatArray(n)
        val noise = FloatArray(n / 2 + 1)
        val gain = FloatArray(n / 2 + 1) { 1f }
        var frames = 0
        val re = FloatArray(n); val im = FloatArray(n)
        val out = ArrayDeque<Float>().apply { repeat(hop) { addLast(0f) } }
        var skip = hop

        fun push(x: Float) {
            inBuf[fill++] = x
            if (fill == n) {
                frame()
                System.arraycopy(inBuf, hop, inBuf, 0, n - hop)
                fill = n - hop
            }
        }

        private fun frame() {
            for (i in 0 until n) { re[i] = inBuf[i] * win[i]; im[i] = 0f }
            fft(re, im, false)
            val floor = 1f - 0.85f * nr
            for (k in 0..n / 2) {
                val mag = sqrt(re[k] * re[k] + im[k] * im[k]) + 1e-9f
                // Minimum-statistics style noise tracker: falls fast, rises slowly.
                noise[k] = if (frames < 6) (if (frames == 0) mag else min(noise[k], mag) * 0.6f + noise[k] * 0.4f)
                else if (mag < noise[k]) noise[k] * 0.85f + mag * 0.15f else noise[k] * 1.0035f
                val g = max(floor, 1f - nr * 2.2f * noise[k] / mag)
                gain[k] = gain[k] * 0.55f + g * 0.45f
                re[k] *= gain[k]; im[k] *= gain[k]
                if (k in 1 until n / 2) { re[n - k] = re[k]; im[n - k] = -im[k] }
            }
            frames++
            fft(re, im, true)
            for (i in 0 until n) ola[i] += re[i] * win[i]
            for (i in 0 until hop) { if (skip > 0) skip-- else out.addLast(ola[i]) }
            System.arraycopy(ola, hop, ola, 0, n - hop)
            java.util.Arrays.fill(ola, n - hop, n, 0f)
        }
    }
    private val nrs = if (nr > 0f) Array(ch) { Nr() } else null

    // ---------- Dynamics / time effects ----------
    private val comp = fx.compress.coerceIn(0f, 1f).let { if (voice == "radio") max(it, 0.5f) else it }
    private var env = 0f
    private val atk = exp(-1f / (0.005f * rate)); private val rel = exp(-1f / (0.15f * rate))
    private val threshDb = -12f - 18f * comp
    private val makeup = 10f.pow((-threshDb * (1f - 1f / 4f) * 0.5f) / 20f).let { if (comp > 0f) it else 1f }

    private val echoAmt = fx.echo.coerceIn(0f, 1f).let { if (voice == "echo") max(it, 0.6f) else it }
    private val echoLen = (0.28f * rate).toInt()
    private val echoBuf = if (echoAmt > 0f) Array(ch) { FloatArray(echoLen) } else null
    private var echoPos = 0

    private val revAmt = fx.reverb.coerceIn(0f, 1f).let { if (voice == "cave") max(it, 0.75f) else it }
    private val combLens = intArrayOf(1116, 1188, 1277, 1356).map { (it * rate / 44100f).toInt() }
    private val apLens = intArrayOf(556, 441).map { (it * rate / 44100f).toInt() }
    private val combs = if (revAmt > 0f) Array(ch) { combLens.map { FloatArray(it) } } else null
    private val combPos = IntArray(4)
    private val combStore = Array(ch) { FloatArray(4) }
    private val aps = if (revAmt > 0f) Array(ch) { apLens.map { FloatArray(it) } } else null
    private val apPos = IntArray(2)

    private var phase = 0.0

    /** Processes interleaved float samples in place (same length out). */
    fun process(buf: FloatArray, frames: Int) {
        if (nrs != null) denoiseStage(buf, frames)
        chain(buf, frames)
    }

    private fun denoiseStage(buf: FloatArray, frames: Int) {
        val nrs = nrs ?: return
        for (f in 0 until frames) for (c in 0 until ch) nrs[c].push(buf[f * ch + c])
        // Each reducer keeps a fixed ~hop-sample delay, so there is always enough output (no gaps, same length).
        for (f in 0 until frames) for (c in 0 until ch) buf[f * ch + c] = nrs[c].out.removeFirstOrNull() ?: 0f
    }

    private fun chain(buf: FloatArray, frames: Int) {
        for (f in 0 until frames) {
            var level = 0f
            for (c in 0 until ch) {
                var x = buf[f * ch + c]
                for (q in filters) x = q.run(x, c)
                buf[f * ch + c] = x
                level = max(level, abs(x))
            }
            // Compressor (linked across channels)
            var cg = 1f
            if (comp > 0f) {
                env = if (level > env) atk * env + (1 - atk) * level else rel * env + (1 - rel) * level
                val db = 20f * ln(max(env, 1e-6f)) / ln(10f)
                if (db > threshDb) cg = 10f.pow(((threshDb + (db - threshDb) / 4f) - db) / 20f)
                cg *= makeup
            }
            val ring = if (voice == "robot") sin(phase).toFloat() else 1f
            if (voice == "robot") phase += 2 * PI * 55.0 / rate
            for (c in 0 until ch) {
                var x = buf[f * ch + c] * cg * ring
                when (voice) {
                    "megaphone" -> x = tanh(x * 3.5f) / tanh(3.5f)
                    "monster" -> x = tanh(x * 1.8f)
                    "phone" -> x = tanh(x * 1.6f)
                }
                if (echoBuf != null) {
                    val d = echoBuf[c][echoPos]
                    val y = x + d * echoAmt
                    echoBuf[c][echoPos] = x + d * 0.38f
                    x = y
                }
                if (combs != null && aps != null) {
                    var r = 0f
                    for (k in 0 until 4) {
                        val line = combs[c][k]; val p = combPos[k] % line.size
                        val o = line[p]
                        combStore[c][k] = o * 0.8f + combStore[c][k] * 0.2f
                        line[p] = x + combStore[c][k] * 0.84f
                        r += o
                    }
                    r *= 0.25f
                    for (k in 0 until 2) {
                        val line = aps[c][k]; val p = apPos[k] % line.size
                        val o = line[p]
                        line[p] = r + o * 0.5f
                        r = o - r * 0.5f
                    }
                    x = x * (1f - revAmt * 0.35f) + r * revAmt * 0.9f
                }
                // Soft limiter
                if (abs(x) > 0.9f) x = (if (x > 0) 1f else -1f) * (0.9f + 0.1f * tanh((abs(x) - 0.9f) * 10f))
                buf[f * ch + c] = x
            }
            if (echoBuf != null) echoPos = (echoPos + 1) % echoLen
            if (combs != null) for (k in 0 until 4) combPos[k] = (combPos[k] + 1) % combLens[k]
            if (aps != null) for (k in 0 until 2) apPos[k] = (apPos[k] + 1) % apLens[k]
        }
    }

    /** Nothing is owed: the output always matches the input length. */
    fun flush(): FloatArray = FloatArray(0)

    companion object {
        /** In-place iterative radix-2 FFT (n must be a power of two). */
        fun fft(re: FloatArray, im: FloatArray, inverse: Boolean) {
            val n = re.size
            var j = 0
            for (i in 1 until n) {
                var bit = n shr 1
                while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
                j = j xor bit
                if (i < j) {
                    val tr = re[i]; re[i] = re[j]; re[j] = tr
                    val ti = im[i]; im[i] = im[j]; im[j] = ti
                }
            }
            var len = 2
            while (len <= n) {
                val ang = 2 * PI / len * (if (inverse) 1 else -1)
                val wr = cos(ang).toFloat(); val wi = sin(ang).toFloat()
                var i = 0
                while (i < n) {
                    var cr = 1f; var ci = 0f
                    for (k in 0 until len / 2) {
                        val ar = re[i + k]; val ai = im[i + k]
                        val br = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                        val bi = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                        re[i + k] = ar + br; im[i + k] = ai + bi
                        re[i + k + len / 2] = ar - br; im[i + k + len / 2] = ai - bi
                        val ncr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = ncr
                    }
                    i += len
                }
                len = len shl 1
            }
            if (inverse) for (i in 0 until n) { re[i] /= n; im[i] /= n }
        }
    }
}
