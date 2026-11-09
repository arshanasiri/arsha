package com.example.callrecorder

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.google.android.material.color.MaterialColors

/** نمودار میله‌ای زنده‌ی سطح صدا. */
class LevelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val count = 44
    private val values = FloatArray(count)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    init {
        paint.color = MaterialColors.getColor(
            context, com.google.android.material.R.attr.colorPrimary, 0xFF5B3FD9.toInt()
        )
    }

    /** v بین 0 تا 1 */
    fun push(v: Float) {
        System.arraycopy(values, 1, values, 0, count - 1)
        val prev = values[count - 2]
        values[count - 1] = maxOf(v.coerceIn(0f, 1f), prev * 0.55f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val slot = w / count
        val barW = slot * 0.56f
        val minH = h * 0.08f
        val cy = h / 2f
        for (i in 0 until count) {
            val bh = minH + values[i] * (h - minH)
            val x = i * slot + (slot - barW) / 2f
            rect.set(x, cy - bh / 2f, x + barW, cy + bh / 2f)
            paint.alpha = (70 + 185 * i / (count - 1))
            canvas.drawRoundRect(rect, barW / 2f, barW / 2f, paint)
        }
    }
}
