package com.mawj.editor.engine

import android.content.Context
import android.graphics.Matrix
import android.opengl.GLES20
import androidx.annotation.OptIn
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import androidx.media3.effect.MatrixTransformation
import androidx.media3.effect.RgbMatrix
import com.mawj.editor.L
import kotlin.math.PI
import kotlin.math.sin

/** Cinematic color grades expressed as 3x3 channel mixes + offsets (rows: r', g', b'). */
object ColorGrade {

    class Preset(val id: String, val ar: String, val en: String, val m: FloatArray, val o: FloatArray = floatArrayOf(0f, 0f, 0f)) {
        val label get() = L.t(ar, en)
    }

    private fun sat(s: Float): FloatArray {
        val lr = 0.2126f; val lg = 0.7152f; val lb = 0.0722f
        return floatArrayOf(
            lr * (1 - s) + s, lg * (1 - s), lb * (1 - s),
            lr * (1 - s), lg * (1 - s) + s, lb * (1 - s),
            lr * (1 - s), lg * (1 - s), lb * (1 - s) + s,
        )
    }

    val presets = listOf(
        Preset("vivid", "حيوي", "Vivid", sat(1.35f)),
        Preset("teal", "تيل وبرتقالي", "Teal & Orange", floatArrayOf(1.12f, 0f, -0.08f, -0.02f, 1.0f, 0.02f, -0.12f, 0.04f, 1.1f), floatArrayOf(0.01f, 0f, 0.03f)),
        Preset("warm", "دافئ", "Warm", floatArrayOf(1.08f, 0f, 0f, 0f, 1.02f, 0f, 0f, 0f, 0.86f), floatArrayOf(0.02f, 0.01f, 0f)),
        Preset("cool", "بارد", "Cool", floatArrayOf(0.9f, 0f, 0f, 0f, 1.0f, 0f, 0f, 0f, 1.12f), floatArrayOf(0f, 0.01f, 0.02f)),
        Preset("golden", "ذهبي", "Golden hour", floatArrayOf(1.12f, 0.02f, 0f, 0f, 1.05f, 0f, 0f, 0f, 0.82f), floatArrayOf(0.03f, 0.02f, 0f)),
        Preset("cine", "سينمائي", "Cinematic", floatArrayOf(1.05f, 0.03f, -0.05f, 0f, 0.98f, 0.04f, -0.04f, 0.06f, 1.02f), floatArrayOf(0.02f, 0.02f, 0.04f)),
        Preset("moody", "داكن", "Moody", floatArrayOf(0.92f, 0.03f, 0f, 0.02f, 0.9f, 0.03f, 0.02f, 0.05f, 0.95f), floatArrayOf(-0.02f, -0.02f, 0f)),
        Preset("matte", "مطفي", "Matte", floatArrayOf(0.85f, 0f, 0f, 0f, 0.85f, 0f, 0f, 0f, 0.85f), floatArrayOf(0.07f, 0.07f, 0.08f)),
        Preset("vintage", "فينتج", "Vintage", floatArrayOf(0.9f, 0.1f, 0f, 0.05f, 0.85f, 0.05f, 0f, 0.1f, 0.75f), floatArrayOf(0.05f, 0.04f, 0.06f)),
        Preset("night", "ليلي", "Night", floatArrayOf(0.8f, 0f, 0f, 0f, 0.92f, 0f, 0f, 0.05f, 1.15f), floatArrayOf(0f, 0f, 0.02f)),
        Preset("green", "فيلم أخضر", "Film green", floatArrayOf(0.92f, 0.04f, 0f, 0f, 1.05f, 0f, 0f, 0.03f, 0.9f), floatArrayOf(0f, 0.02f, 0f)),
        Preset("kodak", "كوداك", "Kodak film", floatArrayOf(1.1f, 0.04f, -0.04f, 0.02f, 1.02f, 0f, -0.04f, 0.02f, 0.92f), floatArrayOf(0.02f, 0.01f, 0.03f)),
        Preset("fuji", "فوجي", "Fuji film", floatArrayOf(0.96f, 0.04f, 0f, 0f, 1.06f, 0f, 0f, 0.04f, 1.0f), floatArrayOf(0.01f, 0.01f, 0.02f)),
        Preset("bleach", "باهت سينمائي", "Bleach bypass", floatArrayOf(0.95f, 0.18f, 0.02f, 0.06f, 1.05f, 0.04f, 0.06f, 0.18f, 0.9f), floatArrayOf(-0.04f, -0.04f, -0.04f)),
        Preset("cross", "معالجة متقاطعة", "Cross process", floatArrayOf(1.1f, 0f, 0f, 0f, 1.1f, -0.05f, 0f, 0f, 0.8f), floatArrayOf(0f, 0.02f, 0.08f)),
        Preset("summer", "صيفي", "Summer", floatArrayOf(1.08f, 0.04f, 0f, 0f, 1.06f, 0f, 0f, 0f, 0.95f), floatArrayOf(0.03f, 0.03f, 0f)),
        Preset("autumn", "خريفي", "Autumn", floatArrayOf(1.12f, 0.06f, 0f, 0.04f, 0.98f, 0f, 0f, 0f, 0.8f), floatArrayOf(0.02f, 0.01f, 0f)),
        Preset("pastel", "باستيل", "Pastel", floatArrayOf(0.85f, 0.05f, 0.05f, 0.05f, 0.85f, 0.05f, 0.05f, 0.05f, 0.85f), floatArrayOf(0.1f, 0.08f, 0.1f)),
        Preset("cyber", "سايبر", "Cyberpunk", floatArrayOf(1.1f, 0f, 0.1f, 0f, 0.85f, 0.1f, 0.1f, 0f, 1.2f), floatArrayOf(0.02f, 0f, 0.04f)),
        Preset("desert", "صحراوي", "Desert", floatArrayOf(1.1f, 0.05f, 0f, 0.03f, 1.0f, 0f, 0f, 0f, 0.82f), floatArrayOf(0.04f, 0.03f, 0.01f)),
        Preset("punch", "قوي", "Punchy", sat(1.2f).mapIndexed { i, v -> if (i % 4 == 0) v * 1.06f else v }.toFloatArray(), floatArrayOf(-0.03f, -0.03f, -0.03f)),
        Preset("bw", "أبيض وأسود", "Black & white", sat(0f)),
        Preset("noir", "نوار", "Noir", sat(0f).map { it * 1.15f }.toFloatArray(), floatArrayOf(-0.06f, -0.06f, -0.06f)),
        Preset("sepia", "سيبيا", "Sepia", floatArrayOf(0.393f, 0.769f, 0.189f, 0.349f, 0.686f, 0.168f, 0.272f, 0.534f, 0.131f)),
    )

    fun find(id: String) = presets.firstOrNull { it.id == id }

    /** True when anything other than identity would be applied. */
    fun active(id: String, temperature: Float, tint: Float) =
        find(id) != null || kotlin.math.abs(temperature) > 0.01f || kotlin.math.abs(tint) > 0.01f

    /** Column-major 4x4 matrix for Media3's RgbMatrix: (r,g,b,1) -> graded color. */
    fun matrix(id: String, amount: Float, temperature: Float, tint: Float): FloatArray {
        val p = find(id)
        val a = amount.coerceIn(0f, 1f)
        val m = FloatArray(9) { i -> if (i % 4 == 0) 1f else 0f } // identity 3x3 (0,4,8)
        val o = FloatArray(3)
        if (p != null) {
            for (i in 0 until 9) m[i] = m[i] + a * (p.m[i] - m[i])
            for (i in 0 until 3) o[i] = a * p.o[i]
        }
        // White balance: temperature (blue <-> orange) and tint (green <-> magenta).
        val rs = 1f + 0.15f * temperature
        val bs = 1f - 0.15f * temperature
        val gs = 1f - 0.1f * tint
        for (c in 0 until 3) { m[c] *= rs; m[3 + c] *= gs; m[6 + c] *= bs }
        return floatArrayOf(
            m[0], m[3], m[6], 0f,
            m[1], m[4], m[7], 0f,
            m[2], m[5], m[8], 0f,
            o[0], o[1], o[2], 1f,
        )
    }
}

/** Clip animations (in / out / loop), driven by the clip's own clock. */
@OptIn(UnstableApi::class)
object ClipAnimations {

    class Anim(val id: String, val ar: String, val en: String) {
        val label get() = L.t(ar, en)
    }

    val inAnims = listOf(
        Anim("fade", "ظهور", "Fade in"), Anim("zoom", "تقريب", "Zoom in"), Anim("pop", "قفزة", "Pop"),
        Anim("up", "صعود", "Slide up"), Anim("left", "دخول جانبي", "Slide in"), Anim("spin", "لف", "Spin in"),
    )
    val outAnims = listOf(
        Anim("fade", "اختفاء", "Fade out"), Anim("zoom", "تقريب", "Zoom out"), Anim("down", "نزول", "Slide down"),
        Anim("right", "خروج جانبي", "Slide out"),
    )
    val loopAnims = listOf(
        Anim("slowzoom", "تقريب بطيء", "Slow zoom"), Anim("kenburns", "كين بيرنز", "Ken Burns"),
        Anim("pulse", "نبض", "Pulse"), Anim("shake", "اهتزاز", "Shake"), Anim("float", "طفو", "Float"),
    )

    private const val DUR = 450_000L

    fun active(inA: String, outA: String, loop: String) = inA.isNotEmpty() || outA.isNotEmpty() || loop.isNotEmpty()

    private fun noise(t: Long, salt: Int): Float {
        val n = (t / 40_000).toInt() * 374761393 + salt * 668265263
        val h = (n xor (n ushr 13)) * 1274126177
        return ((h xor (h ushr 16)) and 0xFFFF) / 32767.5f - 1f
    }

    fun matrix(clock: ClipClock, inA: String, outA: String, loop: String): MatrixTransformation = MatrixTransformation { pts ->
        val r = clock.relative(pts).coerceAtLeast(0)
        val dur = clock.durationUs
        val m = Matrix()
        val p = (r.toFloat() / dur).coerceIn(0f, 1f)
        when (loop) {
            "slowzoom" -> { val k = 1f + 0.15f * p; m.postScale(k, k) }
            "kenburns" -> { m.postScale(1.12f, 1.12f); m.postTranslate(-0.06f + 0.12f * p, 0.03f - 0.06f * p) }
            "pulse" -> { val k = 1f + 0.035f * (0.5f + 0.5f * sin(r / 1_000_000f * 2f * PI.toFloat() * 2f)); m.postScale(k, k) }
            "shake" -> { m.postScale(1.06f, 1.06f); m.postTranslate(0.025f * noise(pts, 1), 0.025f * noise(pts, 2)) }
            "float" -> { m.postScale(1.05f, 1.05f); m.postTranslate(0f, 0.03f * sin(r / 1_000_000f * PI.toFloat())) }
        }
        val dIn = minOf(DUR, dur / 2)
        if (inA.isNotEmpty() && r < dIn) {
            val e = 1f - ease(r.toFloat() / dIn) // 1 -> 0
            when (inA) {
                "zoom" -> { val k = 1f + 0.35f * e; m.postScale(k, k) }
                "pop" -> { val k = 1f - 0.4f * e + 0.08f * sin((1f - e) * PI.toFloat()); m.postScale(k, k) }
                "up" -> m.postTranslate(0f, -2f * e)
                "left" -> m.postTranslate(2f * e, 0f)
                "spin" -> { m.postRotate(-90f * e); val k = 1f - 0.5f * e; m.postScale(k, k) }
            }
        }
        val dOut = minOf(DUR, dur / 2)
        if (outA.isNotEmpty() && r > dur - dOut) {
            val e = ease(((r - (dur - dOut)).toFloat() / dOut).coerceIn(0f, 1f)) // 0 -> 1
            when (outA) {
                "zoom" -> { val k = 1f - 0.4f * e; m.postScale(k, k) }
                "down" -> m.postTranslate(0f, -2f * e)
                "right" -> m.postTranslate(2f * e, 0f)
            }
        }
        m
    }

    /** Brightness ramp for fade in / fade out. */
    fun fade(clock: ClipClock, inA: String, outA: String): RgbMatrix = RgbMatrix { pts, _ ->
        val r = clock.relative(pts).coerceAtLeast(0)
        val dur = clock.durationUs
        var k = 1f
        val d = minOf(DUR, dur / 2)
        if (inA == "fade" && r < d) k *= ease(r.toFloat() / d)
        if (outA == "fade" && r > dur - d) k *= 1f - ease(((r - (dur - d)).toFloat() / d).coerceIn(0f, 1f))
        floatArrayOf(k, 0f, 0f, 0f, 0f, k, 0f, 0f, 0f, 0f, k, 0f, 0f, 0f, 0f, 1f)
    }
}

/** Detail enhancer: an unsharp-mask shader that keeps upscaled exports crisp. */
@OptIn(UnstableApi::class)
class SharpenEffect(private val amount: Float) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = SharpenProgram(amount, useHdr)
}

@OptIn(UnstableApi::class)
private class SharpenProgram(private val amount: Float, useHdr: Boolean) : BaseGlShaderProgram(useHdr, 1) {

    private val program = GlProgram(VERTEX, FRAGMENT).apply {
        setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
        setFloatsUniform("uTransformationMatrix", GlUtil.create4x4IdentityMatrix())
        setFloatsUniform("uTexTransformationMatrix", GlUtil.create4x4IdentityMatrix())
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        program.setFloatsUniform("uTexelSize", floatArrayOf(1f / inputWidth, 1f / inputHeight))
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        program.use()
        program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
        program.setFloatUniform("uAmount", amount)
        program.bindAttributesAndUniforms()
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    override fun release() {
        super.release()
        program.delete()
    }

    companion object {
        const val VERTEX = """
attribute vec4 aFramePosition;
uniform mat4 uTransformationMatrix;
uniform mat4 uTexTransformationMatrix;
varying vec2 vTexSamplingCoord;
void main() {
  gl_Position = uTransformationMatrix * aFramePosition;
  vec4 texturePosition = vec4(aFramePosition.x * 0.5 + 0.5, aFramePosition.y * 0.5 + 0.5, 0.0, 1.0);
  vTexSamplingCoord = (uTexTransformationMatrix * texturePosition).xy;
}
"""
        const val FRAGMENT = """
precision mediump float;
uniform sampler2D uTexSampler;
uniform vec2 uTexelSize;
uniform float uAmount;
varying vec2 vTexSamplingCoord;
void main() {
  vec4 c = texture2D(uTexSampler, vTexSamplingCoord);
  vec3 n = texture2D(uTexSampler, vTexSamplingCoord + vec2(uTexelSize.x, 0.0)).rgb
         + texture2D(uTexSampler, vTexSamplingCoord - vec2(uTexelSize.x, 0.0)).rgb
         + texture2D(uTexSampler, vTexSamplingCoord + vec2(0.0, uTexelSize.y)).rgb
         + texture2D(uTexSampler, vTexSamplingCoord - vec2(0.0, uTexelSize.y)).rgb;
  vec3 d = c.rgb - n * 0.25;
  gl_FragColor = vec4(clamp(c.rgb + uAmount * 1.6 * d, 0.0, 1.0), c.a);
}
"""
    }
}

/**
 * Places a clip on the canvas in one GPU pass: fit (or fill) + manual zoom/offset for the clip, and the empty
 * area filled with black, a solid color, or a blurred, zoomed copy of the clip itself (TikTok-style).
 */
@OptIn(UnstableApi::class)
class CanvasEffect(
    private val outW: Int, private val outH: Int,
    private val fill: Boolean, private val zoom: Float, private val offX: Float, private val offY: Float,
    private val bg: String,
) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        CanvasProgram(outW, outH, fill, zoom, offX, offY, bg, useHdr)
}

@OptIn(UnstableApi::class)
private class CanvasProgram(
    private val outW: Int, private val outH: Int,
    private val fill: Boolean, private val zoom: Float, private val offX: Float, private val offY: Float,
    bg: String, useHdr: Boolean,
) : BaseGlShaderProgram(useHdr, 1) {

    private val mode = when { bg == "blur" -> 2f; bg.startsWith("#") -> 1f; else -> 0f }
    private val color: FloatArray = if (bg.startsWith("#")) {
        val c = android.graphics.Color.parseColor(bg)
        floatArrayOf(android.graphics.Color.red(c) / 255f, android.graphics.Color.green(c) / 255f, android.graphics.Color.blue(c) / 255f)
    } else floatArrayOf(0f, 0f, 0f)

    private val program = GlProgram(SharpenProgramShaders.VERTEX, FRAGMENT).apply {
        setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
        setFloatsUniform("uTransformationMatrix", GlUtil.create4x4IdentityMatrix())
        setFloatsUniform("uTexTransformationMatrix", GlUtil.create4x4IdentityMatrix())
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        val ow = outW.toFloat(); val oh = outH.toFloat()
        val iw = inputWidth.toFloat(); val ih = inputHeight.toFloat()
        val s = (if (fill) maxOf(ow / iw, oh / ih) else minOf(ow / iw, oh / ih)) * zoom
        // Foreground rectangle in output UV space (0..1), y up.
        val fw = iw * s / ow; val fh = ih * s / oh
        val cx = 0.5f + offX; val cy = 0.5f - offY
        program.setFloatsUniform("uFg", floatArrayOf(cx - fw / 2, cy - fh / 2, fw, fh))
        // Background: fill-crop of the input, slightly enlarged.
        val sb = maxOf(ow / iw, oh / ih) * 1.08f
        val bw = iw * sb / ow; val bh = ih * sb / oh
        program.setFloatsUniform("uBg", floatArrayOf(0.5f - bw / 2, 0.5f - bh / 2, bw, bh))
        program.setFloatUniform("uMode", mode)
        program.setFloatsUniform("uColor", color)
        return Size(outW, outH)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        program.use()
        program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
        program.bindAttributesAndUniforms()
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    override fun release() {
        super.release()
        program.delete()
    }

    companion object {
        const val FRAGMENT = """
precision mediump float;
uniform sampler2D uTexSampler;
uniform vec4 uFg;
uniform vec4 uBg;
uniform float uMode;
uniform vec3 uColor;

varying vec2 vTexSamplingCoord;
void main() {
  vec2 uv = vTexSamplingCoord;
  vec2 f = (uv - uFg.xy) / uFg.zw;
  if (f.x >= 0.0 && f.x <= 1.0 && f.y >= 0.0 && f.y <= 1.0) {
    gl_FragColor = texture2D(uTexSampler, f);
    return;
  }
  if (uMode < 0.5) { gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0); return; }
  if (uMode < 1.5) { gl_FragColor = vec4(uColor, 1.0); return; }
  vec2 b = clamp((uv - uBg.xy) / uBg.zw, 0.0, 1.0);
  vec3 acc = vec3(0.0);
  float r = 0.045;
  for (int i = -2; i <= 2; i++) {
    for (int j = -2; j <= 2; j++) {
      vec2 o = vec2(float(i), float(j)) * r * 0.5;
      acc += texture2D(uTexSampler, clamp(b + o, 0.0, 1.0)).rgb;
    }
  }
  gl_FragColor = vec4(acc / 25.0 * 0.62, 1.0);
}
"""
    }
}

/** Shared vertex shader. */
internal object SharpenProgramShaders {
    const val VERTEX = """
attribute vec4 aFramePosition;
uniform mat4 uTransformationMatrix;
uniform mat4 uTexTransformationMatrix;
varying vec2 vTexSamplingCoord;
void main() {
  gl_Position = uTransformationMatrix * aFramePosition;
  vec4 texturePosition = vec4(aFramePosition.x * 0.5 + 0.5, aFramePosition.y * 0.5 + 0.5, 0.0, 1.0);
  vTexSamplingCoord = (uTexTransformationMatrix * texturePosition).xy;
}
"""
}
