package com.mawj.editor.ui

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.RangeSlider
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.mawj.editor.R

fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

object Glass {
    const val PINK = 0xFFF26B2A.toInt()
    const val VIOLET = 0xFF8B5CF6.toInt()
    const val CYAN = 0xFF25F4EE.toInt()

    /** Frosted blur of whatever is behind a dialog window (Android 12+). */
    fun blurBehind(d: Dialog, radius: Int = 48) {
        val w = d.window ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            w.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            w.attributes = w.attributes.apply { blurBehindRadius = radius }
            w.setDimAmount(0.35f)
        }
    }

    /** Glass background + blur for a (Material) alert dialog. Call after show(). */
    fun style(d: Dialog) {
        d.window?.setBackgroundDrawableResource(R.drawable.glass_dialog)
        blurBehind(d)
        Fonts.apply(d.window?.decorView)
    }

    fun gradientText(t: TextView, vararg colors: Int = intArrayOf(PINK, VIOLET, CYAN)) {
        t.post {
            val w = t.paint.measureText(t.text.toString())
            t.paint.shader = LinearGradient(0f, 0f, w, 0f, colors, null, Shader.TileMode.CLAMP)
            t.invalidate()
        }
    }
}

/** Builder for consistent liquid-glass bottom-sheet tool panels. */
class Sheet(private val ctx: Context, title: String, subtitle: String? = null) {

    val dialog = BottomSheetDialog(ctx)
    private val body = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(ctx.dp(18), ctx.dp(8), ctx.dp(18), ctx.dp(24))
    }

    init {
        // Grab handle
        body.addView(View(ctx).apply {
            background = GradientDrawable().apply { cornerRadius = ctx.dp(3).toFloat(); setColor(0x55FFFFFF) }
        }, LinearLayout.LayoutParams(ctx.dp(36), ctx.dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = ctx.dp(10) })
        body.addView(TextView(ctx).apply {
            text = title
            textSize = 17f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        if (subtitle != null) body.addView(TextView(ctx).apply {
            text = subtitle
            textSize = 13f
            setTextColor(0x99FFFFFF.toInt())
            setPadding(0, ctx.dp(2), 0, 0)
        })
        val scroll = android.widget.ScrollView(ctx).apply {
            setBackgroundResource(R.drawable.glass_sheet)
            addView(body)
        }
        dialog.setContentView(scroll)
        // Compact tool panel in the lower part of the screen; the video stays visible (no dim) above it.
        val maxH = (ctx.resources.displayMetrics.heightPixels * 0.46f).toInt()
        dialog.behavior.maxHeight = maxH
        dialog.behavior.peekHeight = maxH
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        dialog.window?.setDimAmount(0f)
    }

    private fun label(t: String) = TextView(ctx).apply {
        text = t
        textSize = 13f
        setTextColor(0x99FFFFFF.toInt())
        setPadding(0, ctx.dp(12), 0, ctx.dp(2))
    }.also { body.addView(it) }

    fun text(t: String) = label(t)

    /** A tip line inside a glass card. */
    fun note(t: String): TextView = TextView(ctx).apply {
        text = t
        textSize = 13f
        setLineSpacing(ctx.dp(3).toFloat(), 1f)
        setTextColor(0xCCFFFFFF.toInt())
        setBackgroundResource(R.drawable.glass_panel)
        setPadding(ctx.dp(14), ctx.dp(12), ctx.dp(14), ctx.dp(12))
    }.also {
        body.addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.dp(12) })
    }

    fun slider(
        title: String, from: Float, to: Float, value: Float,
        format: (Float) -> String, step: Float = 0f, onCommit: (Float) -> Unit,
    ): Slider {
        val l = label("$title: ${format(value)}")
        val s = Slider(ctx).apply {
            valueFrom = from
            valueTo = to
            if (step > 0) stepSize = step
            this.value = if (step > 0) {
                val n = Math.round((value.coerceIn(from, to) - from) / step)
                (from + n * step).coerceIn(from, to)
            } else value.coerceIn(from, to)
            setLabelFormatter { format(it) }
            trackActiveTintList = ColorStateList.valueOf(Glass.PINK)
            isTickVisible = false
            trackHeight = ctx.dp(4)
            thumbRadius = ctx.dp(9)
            haloRadius = ctx.dp(16)
            thumbTintList = ColorStateList.valueOf(Color.WHITE)
            trackInactiveTintList = ColorStateList.valueOf(0x33FFFFFF)
            addOnChangeListener { _, v, _ -> l.text = "$title: ${format(v)}" }
            addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(slider: Slider) {}
                override fun onStopTrackingTouch(slider: Slider) = onCommit(slider.value)
            })
        }
        body.addView(s)
        return s
    }

    fun range(
        title: String, from: Float, to: Float, start: Float, end: Float,
        format: (Float) -> String, onCommit: (Float, Float) -> Unit,
    ): RangeSlider {
        val l = label("$title: ${format(start)} → ${format(end)}")
        val s = RangeSlider(ctx).apply {
            valueFrom = from
            valueTo = to
            values = listOf(start.coerceIn(from, to), end.coerceIn(from, to))
            minSeparation = 0f
            setLabelFormatter { format(it) }
            trackActiveTintList = ColorStateList.valueOf(Glass.PINK)
            isTickVisible = false
            trackHeight = ctx.dp(4)
            thumbRadius = ctx.dp(9)
            haloRadius = ctx.dp(16)
            thumbTintList = ColorStateList.valueOf(Color.WHITE)
            trackInactiveTintList = ColorStateList.valueOf(0x33FFFFFF)
            addOnChangeListener { sl, _, _ -> l.text = "$title: ${format(sl.values[0])} → ${format(sl.values[1])}" }
            addOnSliderTouchListener(object : RangeSlider.OnSliderTouchListener {
                override fun onStartTrackingTouch(slider: RangeSlider) {}
                override fun onStopTrackingTouch(slider: RangeSlider) = onCommit(slider.values[0], slider.values[1])
            })
        }
        body.addView(s)
        return s
    }

    fun chips(title: String?, options: List<String>, selected: Int, required: Boolean = true, onSelect: (Int) -> Unit): ChipGroup {
        if (title != null) label(title)
        val g = ChipGroup(ctx).apply {
            isSingleSelection = true; isSelectionRequired = required; isSingleLine = true
            chipSpacingHorizontal = ctx.dp(6)
        }
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        options.forEachIndexed { i, o ->
            g.addView(Chip(ctx).apply {
                id = View.generateViewId()
                text = o
                isCheckable = true
                isCheckedIconVisible = false
                isChecked = i == selected
                chipMinHeight = ctx.dp(34).toFloat()
                chipBackgroundColor = ColorStateList(states, intArrayOf(Glass.PINK, 0xFF26262B.toInt()))
                chipStrokeWidth = 0f
                setTextColor(ColorStateList(states, intArrayOf(0xFF111111.toInt(), 0xE6FFFFFF.toInt())))
                setEnsureMinTouchTargetSize(false)
                setOnClickListener { onSelect(i) }
            })
        }
        val scroll = android.widget.HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            addView(g)
        }
        body.addView(scroll, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.dp(4) })
        return g
    }

    /** Horizontal row of square previews (filters, transitions...). */
    fun thumbs(title: String?, items: List<Pair<String, Bitmap?>>, selected: Int, onSelect: (Int) -> Unit): (Int) -> Unit {
        if (title != null) label(title)
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val frames = ArrayList<ImageView>()
        fun mark(sel: Int) = frames.forEachIndexed { k, v ->
            v.background = GradientDrawable().apply {
                cornerRadius = ctx.dp(12).toFloat(); setColor(0xFF26262B.toInt())
                if (k == sel) setStroke(ctx.dp(2), Glass.PINK)
            }
        }
        items.forEachIndexed { i, (name, bm) ->
            val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
            val img = ImageView(ctx).apply {
                setImageBitmap(bm)
                scaleType = ImageView.ScaleType.CENTER_CROP
                clipToOutline = true
                setPadding(ctx.dp(2), ctx.dp(2), ctx.dp(2), ctx.dp(2))
                setOnClickListener { mark(i); onSelect(i) }
            }
            frames += img
            col.addView(img, LinearLayout.LayoutParams(ctx.dp(64), ctx.dp(64)))
            col.addView(TextView(ctx).apply {
                text = name; textSize = 11f; setTextColor(0xCCFFFFFF.toInt()); maxLines = 1
                gravity = Gravity.CENTER; setPadding(0, ctx.dp(4), 0, 0)
            }, LinearLayout.LayoutParams(ctx.dp(72), -2))
            row.addView(col, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = ctx.dp(6) })
        }
        mark(selected)
        val scroll = android.widget.HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(row) }
        body.addView(scroll, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.dp(6) })
        return { sel -> mark(sel) }
    }

    fun toggle(t: String, checked: Boolean, onChange: (Boolean) -> Unit): MaterialSwitch {
        val s = MaterialSwitch(ctx).apply {
            text = t
            isChecked = checked
            setTextColor(Color.WHITE)
            setOnCheckedChangeListener { _, v -> onChange(v) }
        }
        body.addView(s, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.dp(8) })
        return s
    }

    fun input(hint: String, value: String): TextInputEditText {
        val layout = TextInputLayout(ctx, null, com.google.android.material.R.attr.textInputOutlinedStyle).apply {
            boxStrokeColor = Glass.PINK
            setBoxCornerRadii(ctx.dp(16).toFloat(), ctx.dp(16).toFloat(), ctx.dp(16).toFloat(), ctx.dp(16).toFloat())
            boxBackgroundColor = 0x14FFFFFF
        }
        val e = TextInputEditText(layout.context).apply { setText(value); this.hint = hint }
        layout.addView(e)
        body.addView(layout, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ctx.dp(12) })
        return e
    }

    /** Primary (gradient) or secondary (glass) pill button. */
    fun button(t: String, outlined: Boolean = false, onClick: () -> Unit): TextView {
        val b = TextView(ctx).apply {
            text = t
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setBackgroundResource(if (outlined) R.drawable.glass_pill else R.drawable.accent_gradient)
            if (outlined) foreground = ctx.getDrawable(android.R.drawable.list_selector_background)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
        body.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(46)).apply { topMargin = ctx.dp(10) })
        return b
    }

    /** Large selectable glass option card (icon, title, description). */
    fun option(icon: Int, title: String, desc: String, selected: Boolean, face: android.graphics.Typeface? = null, onClick: () -> Unit): View {
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ctx.dp(14), ctx.dp(14), ctx.dp(14), ctx.dp(14))
            background = GradientDrawable().apply {
                cornerRadius = ctx.dp(20).toFloat()
                setColor(if (selected) 0x33FF2D7A else 0x14FFFFFF)
                setStroke(ctx.dp(if (selected) 2 else 1), if (selected) Glass.PINK else 0x33FFFFFF)
            }
            isClickable = true
            setOnClickListener { onClick() }
        }
        card.addView(ImageView(ctx).apply {
            setImageResource(icon)
            setBackgroundResource(R.drawable.glass_circle)
            setPadding(ctx.dp(10), ctx.dp(10), ctx.dp(10), ctx.dp(10))
        }, LinearLayout.LayoutParams(ctx.dp(44), ctx.dp(44)))
        val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(ctx.dp(12), 0, 0, 0) }
        texts.addView(TextView(ctx).apply {
            text = title; textSize = if (face != null) 18f else 15f; setTextColor(Color.WHITE)
            if (face != null) { typeface = face; tag = "keepFont" } else setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        texts.addView(TextView(ctx).apply { text = desc; textSize = 12f; setTextColor(0xA6FFFFFF.toInt()) })
        card.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        body.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.dp(10) })
        return card
    }

    fun colors(selected: Int, options: IntArray, onSelect: (Int) -> Unit) {
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val views = ArrayList<View>()
        fun paint(v: View, c: Int, sel: Boolean) {
            v.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(c)
                setStroke(ctx.dp(if (sel) 3 else 1), if (sel) Glass.PINK else 0x55FFFFFF)
            }
        }
        options.forEach { c ->
            val v = View(ctx)
            paint(v, c, c == selected)
            v.setOnClickListener {
                options.forEachIndexed { i, oc -> paint(views[i], oc, oc == c) }
                onSelect(c)
            }
            views += v
            row.addView(v, LinearLayout.LayoutParams(ctx.dp(36), ctx.dp(36)).apply { marginEnd = ctx.dp(10); topMargin = ctx.dp(6) })
        }
        body.addView(row)
    }

    fun show() {
        Fonts.apply(body)
        dialog.show()
    }

    fun dismiss() = dialog.dismiss()
}
