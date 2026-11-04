package com.mawj.editor.ui

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.RangeSlider
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

/** Small builder for consistent bottom-sheet tool panels. */
class Sheet(private val ctx: Context, title: String) {

    val dialog = BottomSheetDialog(ctx)
    private val body = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(ctx.dp(20), ctx.dp(8), ctx.dp(20), ctx.dp(24))
    }

    init {
        body.addView(TextView(ctx).apply {
            text = title
            textSize = 18f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, ctx.dp(8), 0, ctx.dp(12))
        })
        val scroll = android.widget.ScrollView(ctx).apply { addView(body) }
        dialog.setContentView(scroll)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
    }

    private fun label(t: String) = TextView(ctx).apply {
        text = t
        textSize = 14f
        setTextColor(0xFFC8C8D4.toInt())
        setPadding(0, ctx.dp(10), 0, ctx.dp(2))
    }.also { body.addView(it) }

    fun text(t: String) = label(t)

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
            addOnChangeListener { sl, _, _ -> l.text = "$title: ${format(sl.values[0])} → ${format(sl.values[1])}" }
            addOnSliderTouchListener(object : RangeSlider.OnSliderTouchListener {
                override fun onStartTrackingTouch(slider: RangeSlider) {}
                override fun onStopTrackingTouch(slider: RangeSlider) = onCommit(slider.values[0], slider.values[1])
            })
        }
        body.addView(s)
        return s
    }

    fun chips(title: String?, options: List<String>, selected: Int, onSelect: (Int) -> Unit): ChipGroup {
        if (title != null) label(title)
        val g = ChipGroup(ctx).apply { isSingleSelection = true; isSelectionRequired = true }
        options.forEachIndexed { i, o ->
            g.addView(Chip(ctx).apply {
                id = View.generateViewId()
                text = o
                isCheckable = true
                isChecked = i == selected
                setOnClickListener { onSelect(i) }
            })
        }
        body.addView(g)
        return g
    }

    fun toggle(t: String, checked: Boolean, onChange: (Boolean) -> Unit): MaterialSwitch {
        val s = MaterialSwitch(ctx).apply {
            text = t
            isChecked = checked
            setOnCheckedChangeListener { _, v -> onChange(v) }
        }
        body.addView(s)
        return s
    }

    fun input(hint: String, value: String): TextInputEditText {
        val layout = TextInputLayout(ctx, null, com.google.android.material.R.attr.textInputOutlinedStyle)
        val e = TextInputEditText(layout.context).apply { setText(value); this.hint = hint }
        layout.addView(e)
        body.addView(layout, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return e
    }

    fun button(t: String, outlined: Boolean = false, onClick: () -> Unit): MaterialButton {
        val style = if (outlined) com.google.android.material.R.attr.materialButtonOutlinedStyle
        else com.google.android.material.R.attr.materialButtonStyle
        val b = MaterialButton(ctx, null, style).apply {
            text = t
            setOnClickListener { onClick() }
        }
        body.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(52)).apply { topMargin = ctx.dp(10) })
        return b
    }

    fun colors(selected: Int, options: IntArray, onSelect: (Int) -> Unit) {
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        options.forEach { c ->
            row.addView(View(ctx).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(c)
                    setStroke(ctx.dp(if (c == selected) 3 else 1), if (c == selected) 0xFFFF2D55.toInt() else 0x55FFFFFF)
                }
                setOnClickListener { onSelect(c) }
            }, LinearLayout.LayoutParams(ctx.dp(34), ctx.dp(34)).apply { marginEnd = ctx.dp(10); topMargin = ctx.dp(6) })
        }
        body.addView(row)
    }

    fun show() = dialog.show()
    fun dismiss() = dialog.dismiss()
}
