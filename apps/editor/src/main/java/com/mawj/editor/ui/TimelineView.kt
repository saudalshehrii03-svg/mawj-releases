package com.mawj.editor.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.OverScroller
import com.mawj.editor.model.Transition
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/** Scrubbable multi-track timeline: video row + audio lanes, playhead fixed in the center. */
class TimelineView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    interface Listener {
        fun onScrub(us: Long)
        fun onScrubEnd()
        fun onClipTap(index: Int)
        fun onAudioTap(index: Int)
        fun onTransitionTap(index: Int)
        fun onEmptyTap()
        fun onAudioMoved(index: Int, newStartUs: Long)
    }

    data class VBlock(val startUs: Long, val durUs: Long, val key: String, val isImage: Boolean, val transition: Transition, val hasText: Boolean)
    data class ABlock(val startUs: Long, val durUs: Long, val label: String, var lane: Int = 0)

    var listener: Listener? = null
    var thumbProvider: (String) -> Bitmap? = { null }

    private var blocks: List<VBlock> = emptyList()
    private var audios: List<ABlock> = emptyList()
    private var totalUs = 0L
    private var lanes = 1
    var selectedClip = -1; set(v) { field = v; invalidate() }
    var selectedAudio = -1; set(v) { field = v; invalidate() }

    var positionUs = 0L
        private set
    var isUserScrubbing = false
        private set

    private val d = resources.displayMetrics.density
    private var pxPerSec = 64 * d
    private val rulerH = 22 * d
    private val videoTop = rulerH + 6 * d
    private val videoH = 58 * d
    private val audioTop get() = videoTop + videoH + 12 * d
    private val laneH = 30 * d
    private val laneGap = 5 * d
    private val trSize = 26 * d

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 11 * d }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3 * d; color = Color.WHITE }
    private val path = Path()
    private val rect = RectF()
    private val src = Rect()
    private val dst = RectF()

    private val scroller = OverScroller(context)
    private var flinging = false
    private var dragAudio = -1
    private var dragOffsetUs = 0L
    private var dragStartUs = 0L

    init {
        layoutDirection = LAYOUT_DIRECTION_LTR
    }

    fun setData(v: List<VBlock>, a: List<ABlock>) {
        blocks = v
        totalUs = v.maxOfOrNull { it.startUs + it.durUs } ?: 0L
        // Greedy lane assignment so overlapping audio clips stack.
        val laneEnds = mutableListOf<Long>()
        a.sortedBy { it.startUs }.forEach { b ->
            val lane = laneEnds.indexOfFirst { it <= b.startUs }
            if (lane >= 0) { b.lane = lane; laneEnds[lane] = b.startUs + b.durUs }
            else { b.lane = laneEnds.size; laneEnds += b.startUs + b.durUs }
        }
        audios = a
        val newLanes = max(1, laneEnds.size)
        if (newLanes != lanes) { lanes = newLanes; requestLayout() }
        positionUs = positionUs.coerceIn(0, totalUs)
        invalidate()
    }

    /** External position updates (playback). Ignored while the user is touching. */
    fun setPosition(us: Long) {
        if (isUserScrubbing || flinging || dragAudio >= 0) return
        positionUs = us.coerceIn(0, totalUs)
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = (audioTop + lanes * (laneH + laneGap) + 10 * d).toInt()
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), resolveSize(h, heightMeasureSpec))
    }

    private fun x(us: Long) = width / 2f + (us - positionUs) / 1_000_000f * pxPerSec
    private fun usAt(x: Float) = positionUs + ((x - width / 2f) / pxPerSec * 1_000_000f).toLong()

    // ---------- Drawing ----------

    override fun onDraw(canvas: Canvas) {
        drawRuler(canvas)

        blocks.forEachIndexed { i, b ->
            val l = x(b.startUs) + 1 * d
            val r = x(b.startUs + b.durUs) - 1 * d
            if (r < 0 || l > width) return@forEachIndexed
            rect.set(l, videoTop, r, videoTop + videoH)
            canvas.save()
            path.reset(); path.addRoundRect(rect, 8 * d, 8 * d, Path.Direction.CW)
            canvas.clipPath(path)
            paint.color = 0xFF221F33.toInt()
            canvas.drawRect(rect, paint)
            val thumb = thumbProvider(b.key)
            if (thumb != null) {
                val tile = videoH
                var tx = l
                val side = min(thumb.width, thumb.height)
                src.set((thumb.width - side) / 2, (thumb.height - side) / 2, (thumb.width + side) / 2, (thumb.height + side) / 2)
                while (tx < r) {
                    if (tx + tile > 0 && tx < width) {
                        dst.set(tx, videoTop, tx + tile, videoTop + videoH)
                        canvas.drawBitmap(thumb, src, dst, null)
                    }
                    tx += tile
                }
            }
            // Duration badge
            val label = fmt(b.durUs) + if (b.hasText) "  T" else ""
            paint.color = 0x99000000.toInt()
            val tw = text.measureText(label)
            canvas.drawRoundRect(l + 4 * d, videoTop + videoH - 18 * d, l + 12 * d + tw, videoTop + videoH - 3 * d, 4 * d, 4 * d, paint)
            canvas.drawText(label, l + 8 * d, videoTop + videoH - 7 * d, text)
            canvas.restore()
            stroke.color = if (i == selectedClip) 0xFFFF2D7A.toInt() else 0x40FFFFFF
            stroke.strokeWidth = if (i == selectedClip) 3 * d else 1 * d
            canvas.drawRoundRect(rect, 8 * d, 8 * d, stroke)
        }

        // Transition markers between clips
        for (i in 0 until blocks.size - 1) {
            val cx = markerX(i)
            if (cx < -trSize || cx > width + trSize) continue
            transitionRect(cx, rect)
            val tr = blocks[i].transition
            val has = tr != Transition.NONE
            paint.color = if (has) 0xFFFF2D7A.toInt() else 0xE6262338.toInt()
            canvas.drawOval(rect, paint)
            stroke.color = 0x80FFFFFF.toInt(); stroke.strokeWidth = 1.5f * d
            canvas.drawOval(rect, stroke)
            text.color = Color.WHITE
            val s = if (!has) "+" else if (tr.overlap) "✦" else "⇄"
            canvas.drawText(s, cx - text.measureText(s) / 2, rect.centerY() + 4 * d, text)
        }

        // Audio lanes
        audios.forEachIndexed { i, a ->
            val start = if (i == dragAudio) dragStartUs else a.startUs
            val top = audioTop + a.lane * (laneH + laneGap)
            rect.set(x(start), top, x(start + a.durUs), top + laneH)
            if (rect.right < 0 || rect.left > width) return@forEachIndexed
            paint.shader = android.graphics.LinearGradient(rect.left, 0f, rect.right, 0f,
                if (i == dragAudio) 0xFFC4A8FF.toInt() else 0xFF7C4DFF.toInt(), 0xFFFF2D7A.toInt(), android.graphics.Shader.TileMode.CLAMP)
            paint.alpha = if (i == dragAudio) 255 else 210
            canvas.drawRoundRect(rect, 8 * d, 8 * d, paint)
            paint.shader = null
            paint.alpha = 255
            canvas.save()
            canvas.clipRect(rect)
            canvas.drawText("♪ " + a.label, rect.left + 6 * d, rect.centerY() + 4 * d, text)
            canvas.restore()
            if (i == selectedAudio) {
                stroke.color = Color.WHITE; stroke.strokeWidth = 2.5f * d
                canvas.drawRoundRect(rect, 8 * d, 8 * d, stroke)
            }
        }
        if (audios.isEmpty()) {
            text.color = 0x80FFFFFF.toInt()
            canvas.drawText("♪ مسار الصوت — أضف صوت أو استخرجه من مقطع", width / 2f + 10 * d, audioTop + laneH / 2 + 4 * d, text)
            text.color = Color.WHITE
        }

        // Playhead with soft glow
        paint.color = 0x55FF2D7A
        canvas.drawRect(width / 2f - 3 * d, rulerH - 4 * d, width / 2f + 3 * d, height.toFloat(), paint)
        paint.color = Color.WHITE
        canvas.drawRect(width / 2f - 1 * d, rulerH - 4 * d, width / 2f + 1 * d, height.toFloat(), paint)
        canvas.drawCircle(width / 2f, rulerH - 4 * d, 5 * d, paint)
    }

    private fun drawRuler(canvas: Canvas) {
        val minLabelPx = 56 * d
        val steps = longArrayOf(1, 2, 5, 10, 15, 30, 60)
        val step = steps.firstOrNull { it * pxPerSec >= minLabelPx } ?: 60
        val firstSec = max(0L, usAt(0f) / 1_000_000 / step * step)
        val lastSec = min(totalUs / 1_000_000 + 1, usAt(width.toFloat()) / 1_000_000 + step)
        text.color = 0x80FFFFFF.toInt()
        paint.color = 0x55FFFFFF
        var s = firstSec
        while (s <= lastSec) {
            val px = x(s * 1_000_000)
            canvas.drawRect(px, rulerH - 8 * d, px + 1 * d, rulerH - 2 * d, paint)
            canvas.drawText(String.format(Locale.US, "%d:%02d", s / 60, s % 60), px + 3 * d, rulerH - 8 * d, text)
            s += step
        }
        text.color = Color.WHITE
    }

    /** Marker sits in the middle of the overlap (or on the cut). */
    private fun markerX(i: Int): Float {
        val a = blocks[i]
        val b = blocks[i + 1]
        val end = a.startUs + a.durUs
        return x((b.startUs + end) / 2)
    }

    private fun transitionRect(cx: Float, out: RectF) {
        val cy = videoTop + videoH / 2
        out.set(cx - trSize / 2, cy - trSize / 2, cx + trSize / 2, cy + trSize / 2)
    }

    private fun fmt(us: Long): String {
        val tenths = us / 100_000
        return String.format(Locale.US, "%d.%ds", tenths / 10, tenths % 10)
    }

    // ---------- Touch ----------

    private val scale = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            pxPerSec = (pxPerSec * detector.scaleFactor).coerceIn(8 * d, 400 * d)
            invalidate()
            return true
        }
    })

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            if (!scroller.isFinished) { scroller.forceFinished(true); flinging = false }
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (scale.isInProgress || dragAudio >= 0) return false
            isUserScrubbing = true
            moveTo(positionUs + (distanceX / pxPerSec * 1_000_000f).toLong())
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            if (dragAudio >= 0) return false
            val px = (positionUs / 1_000_000f * pxPerSec).toInt()
            val maxPx = (totalUs / 1_000_000f * pxPerSec).toInt()
            scroller.fling(px, 0, -velocityX.toInt(), 0, 0, maxPx, 0, 0)
            flinging = true
            postInvalidateOnAnimation()
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            hitTest(e.x, e.y)
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            val i = audioAt(e.x, e.y)
            if (i >= 0) {
                dragAudio = i
                dragStartUs = audios[i].startUs
                dragOffsetUs = usAt(e.x) - audios[i].startUs
                performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                selectedAudio = i
                invalidate()
            }
        }
    })

    private fun moveTo(us: Long) {
        positionUs = us.coerceIn(0, totalUs)
        listener?.onScrub(positionUs)
        invalidate()
    }

    override fun computeScroll() {
        if (flinging) {
            if (scroller.computeScrollOffset()) {
                moveTo((scroller.currX / pxPerSec * 1_000_000f).toLong())
                postInvalidateOnAnimation()
            } else {
                flinging = false
                listener?.onScrubEnd()
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        scale.onTouchEvent(event)
        if (dragAudio >= 0) {
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    dragStartUs = (usAt(event.x) - dragOffsetUs).coerceIn(0, max(0, totalUs - 100_000))
                    invalidate()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val i = dragAudio
                    dragAudio = -1
                    listener?.onAudioMoved(i, dragStartUs)
                }
            }
            return true
        }
        gestures.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            if (isUserScrubbing && !flinging) listener?.onScrubEnd()
            isUserScrubbing = false
        }
        return true
    }

    private fun audioAt(px: Float, py: Float): Int {
        audios.forEachIndexed { i, a ->
            val top = audioTop + a.lane * (laneH + laneGap)
            if (py in top..(top + laneH) && px in x(a.startUs)..x(a.startUs + a.durUs)) return i
        }
        return -1
    }

    private fun hitTest(px: Float, py: Float) {
        val l = listener ?: return
        // Transition markers first (they sit on top of clips).
        for (i in 0 until blocks.size - 1) {
            transitionRect(markerX(i), rect)
            rect.inset(-6 * d, -6 * d)
            if (rect.contains(px, py)) { l.onTransitionTap(i); return }
        }
        if (py in videoTop..(videoTop + videoH)) {
            for (i in blocks.indices.reversed()) {
                val b = blocks[i]
                if (px >= x(b.startUs) && px <= x(b.startUs + b.durUs)) { l.onClipTap(i); return }
            }
        }
        val a = audioAt(px, py)
        if (a >= 0) { l.onAudioTap(a); return }
        l.onEmptyTap()
    }
}
