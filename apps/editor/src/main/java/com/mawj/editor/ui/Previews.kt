package com.mawj.editor.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import com.mawj.editor.model.Transition
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.sin

/** A small looping tile that animates a preview (transitions, clip animations). */
class AnimTile(
    context: Context,
    private val periodMs: Long,
    private val drawer: (Canvas, Float, Int) -> Unit,
) : View(context) {

    override fun onDraw(canvas: Canvas) {
        val t = (SystemClock.uptimeMillis() % periodMs) / periodMs.toFloat()
        val s = minOf(width, height)
        if (s > 0) drawer(canvas, t, s)
        if (isShown) postInvalidateOnAnimation()
    }
}

/** Drawing helpers that mimic the real effects on two thumbnails. */
object Previews {

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val m = Matrix()

    fun square(bm: Bitmap?, size: Int = 160, fallback: Int = 0xFF3A3A40.toInt()): Bitmap {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val cv = Canvas(out)
        if (bm == null) { cv.drawColor(fallback); return out }
        val side = minOf(bm.width, bm.height)
        val src = Rect((bm.width - side) / 2, (bm.height - side) / 2, (bm.width + side) / 2, (bm.height + side) / 2)
        cv.drawBitmap(bm, src, Rect(0, 0, size, size), paint)
        return out
    }

    private fun ease(p: Float): Float = if (p < 0.5f) 4 * p * p * p else 1 - (-2 * p + 2).let { it * it * it } / 2

    private fun draw(c: Canvas, bm: Bitmap, s: Int, scale: Float = 1f, dx: Float = 0f, dy: Float = 0f, rot: Float = 0f, alpha: Float = 1f) {
        m.reset()
        m.setScale(s.toFloat() / bm.width, s.toFloat() / bm.height)
        m.postScale(scale, scale, s / 2f, s / 2f)
        m.postRotate(rot, s / 2f, s / 2f)
        m.postTranslate(dx, dy)
        paint.alpha = (alpha.coerceIn(0f, 1f) * 255).toInt()
        c.drawBitmap(bm, m, paint)
        paint.alpha = 255
    }

    /** Loop: hold A (0–25%), transition (25–75%), hold B (75–100%). */
    fun transition(c: Canvas, t: Float, s: Int, a: Bitmap, b: Bitmap, kind: Transition) {
        c.save()
        c.clipRect(0, 0, s, s)
        c.drawColor(Color.BLACK)
        val p = ((t - 0.25f) / 0.5f).coerceIn(0f, 1f)
        val e = ease(p)
        val f = s.toFloat()
        when (kind) {
            Transition.NONE -> draw(c, if (p < 0.5f) a else b, s)
            Transition.DISSOLVE -> { draw(c, a, s); draw(c, b, s, alpha = e) }
            Transition.DISSOLVE_ZOOM -> { draw(c, a, s, 1f + 0.12f * e); draw(c, b, s, 1.15f - 0.15f * e, alpha = e) }
            Transition.BLUR_DISSOLVE -> {
                val blur = sin(PI.toFloat() * e)
                draw(c, a, s, alpha = 1f); draw(c, b, s, alpha = e)
                val sa = Bitmap.createScaledBitmap(if (e < 0.5f) a else b, 12, 12, true)
                draw(c, sa, s, alpha = blur * 0.9f); sa.recycle()
            }
            Transition.DIP_WHITE -> {
                draw(c, if (e < 0.5f) a else b, s)
                val w = if (e < 0.5f) e * 2f else (1f - e) * 2f
                c.drawColor(Color.argb((w * 255).toInt(), 255, 255, 255))
            }
            Transition.WHIP_PAN -> {
                for (k in 0 until 4) {
                    val trail = k * f * 0.05f * sin(PI.toFloat() * e)
                    val al = if (k == 0) 1f else 0.3f
                    draw(c, a, s, dx = -f * e - trail, alpha = al)
                    draw(c, b, s, dx = f - f * e - trail, alpha = al)
                }
            }
            Transition.ZOOM_BLUR -> {
                for (k in 0 until 3) draw(c, a, s, 1f + e * (0.6f + k * 0.15f), alpha = (1f - e) * (if (k == 0) 1f else 0.35f))
                draw(c, b, s, 1.25f - 0.25f * e, alpha = e)
            }
            Transition.SLIDE_OVER_LEFT -> { draw(c, a, s, dx = -0.25f * f * e); draw(c, b, s, dx = f * (1 - e)) }
            Transition.SLIDE_OVER_UP -> { draw(c, a, s, dy = -0.25f * f * e); draw(c, b, s, dy = f * (1 - e)) }
            Transition.WIPE_LEFT, Transition.WIPE_RIGHT, Transition.WIPE_UP, Transition.WIPE_DOWN -> {
                draw(c, a, s)
                c.save()
                when (kind) {
                    Transition.WIPE_LEFT -> c.clipRect(f * (1 - e), 0f, f, f)
                    Transition.WIPE_RIGHT -> c.clipRect(0f, 0f, f * e, f)
                    Transition.WIPE_UP -> c.clipRect(0f, f * (1 - e), f, f)
                    else -> c.clipRect(0f, 0f, f, f * e)
                }
                draw(c, b, s); c.restore()
            }
            Transition.IRIS -> {
                draw(c, a, s); c.save()
                c.clipPath(Path().apply { addCircle(f / 2, f / 2, e * hypot(f, f) / 2, Path.Direction.CW) })
                draw(c, b, s); c.restore()
            }
            Transition.CLOCK -> {
                draw(c, a, s); c.save()
                c.clipPath(Path().apply { moveTo(f / 2, f / 2); arcTo(-f, -f, 2 * f, 2 * f, -90f, 360f * e, false); close() })
                draw(c, b, s); c.restore()
            }
            else -> {
                // Cut motions: the outgoing clip animates out, the incoming one animates in.
                val out = p < 0.5f
                val k = if (out) ease(p * 2f) else 1f - ease((p - 0.5f) * 2f) // 0 -> 1 -> 0 around the cut
                val bm = if (out) a else b
                val sign = if (out) 1f else -1f
                when (kind) {
                    Transition.FADE -> { draw(c, bm, s); c.drawColor(Color.argb((k * 255).toInt(), 0, 0, 0)) }
                    Transition.FLASH -> { draw(c, bm, s); c.drawColor(Color.argb((k * 255).toInt(), 255, 255, 255)) }
                    Transition.ZOOM_IN -> draw(c, bm, s, 1f + 0.6f * k)
                    Transition.ZOOM_OUT -> draw(c, bm, s, if (out) 1f - 0.45f * k else 1f + 0.5f * k, alpha = 1f - 0.5f * k)
                    Transition.SLIDE_LEFT -> draw(c, bm, s, dx = -sign * f * k)
                    Transition.SLIDE_RIGHT -> draw(c, bm, s, dx = sign * f * k)
                    Transition.SLIDE_UP -> draw(c, bm, s, dy = -sign * f * k)
                    Transition.SLIDE_DOWN -> draw(c, bm, s, dy = sign * f * k)
                    Transition.SPIN -> draw(c, bm, s, 1f - 0.5f * k, rot = sign * 180f * k, alpha = 1f - 0.4f * k)
                    Transition.SHAKE -> draw(c, bm, s, 1f + 0.08f * k, dx = f * 0.06f * k * sin(t * 90f), dy = f * 0.05f * k * sin(t * 70f))
                    Transition.GLITCH -> {
                        draw(c, bm, s, 1f + 0.08f * k, dx = f * 0.1f * k * sin(t * 120f))
                        if (k > 0.3f) c.drawColor(Color.argb((k * 90).toInt(), 255, 0, 120))
                    }
                    else -> draw(c, bm, s)
                }
            }
        }
        c.restore()
    }

    /** In / out / loop clip animations on one thumbnail. */
    fun animation(c: Canvas, t: Float, s: Int, bm: Bitmap, kind: String, id: String) {
        c.save()
        c.clipRect(0, 0, s, s)
        c.drawColor(Color.BLACK)
        val f = s.toFloat()
        when (kind) {
            "in" -> {
                val e = 1f - ease((t / 0.45f).coerceIn(0f, 1f)) // 1 -> 0, then hold
                when (id) {
                    "fade" -> draw(c, bm, s, alpha = 1f - e)
                    "zoom" -> draw(c, bm, s, 1f + 0.35f * e)
                    "pop" -> draw(c, bm, s, 1f - 0.4f * e + 0.08f * sin((1f - e) * PI.toFloat()))
                    "up" -> draw(c, bm, s, dy = f * e)
                    "left" -> draw(c, bm, s, dx = -f * e)
                    "spin" -> draw(c, bm, s, 1f - 0.5f * e, rot = -90f * e)
                    else -> draw(c, bm, s)
                }
            }
            "out" -> {
                val e = ease(((t - 0.55f) / 0.45f).coerceIn(0f, 1f)) // hold, then 0 -> 1
                when (id) {
                    "fade" -> draw(c, bm, s, alpha = 1f - e)
                    "zoom" -> draw(c, bm, s, 1f - 0.4f * e)
                    "down" -> draw(c, bm, s, dy = f * e)
                    "right" -> draw(c, bm, s, dx = f * e)
                    else -> draw(c, bm, s)
                }
            }
            else -> when (id) {
                "slowzoom" -> draw(c, bm, s, 1f + 0.2f * t)
                "kenburns" -> draw(c, bm, s, 1.15f, dx = f * (-0.06f + 0.12f * t), dy = f * (0.03f - 0.06f * t))
                "pulse" -> draw(c, bm, s, 1f + 0.05f * (0.5f + 0.5f * sin(t * 4f * PI.toFloat())))
                "shake" -> draw(c, bm, s, 1.08f, dx = f * 0.03f * sin(t * 97f), dy = f * 0.03f * sin(t * 71f))
                "float" -> draw(c, bm, s, 1.06f, dy = f * 0.04f * sin(t * 2f * PI.toFloat()))
                else -> draw(c, bm, s)
            }
        }
        c.restore()
    }
}
