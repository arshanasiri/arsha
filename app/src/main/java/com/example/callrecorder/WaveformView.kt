package com.example.callrecorder

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.google.android.material.color.MaterialColors

/** شکل موج با امکان کشیدن برای جلو و عقب بردن. */
class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    interface Listener {
        fun onScrub(fraction: Float)
        fun onSeek(fraction: Float)
    }

    var listener: Listener? = null
    var peaks: FloatArray = FloatArray(0)
        set(v) { field = v; invalidate() }
    var progress: Float = 0f
        set(v) { if (!dragging) { field = v.coerceIn(0f, 1f); invalidate() } }

    private var dragging = false
    private var dragFraction = 0f
    private val paintOn = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintOff = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    init {
        paintOn.color = MaterialColors.getColor(
            context, com.google.android.material.R.attr.colorPrimary, 0xFF5B3FD9.toInt()
        )
        paintOff.color = MaterialColors.getColor(
            context, com.google.android.material.R.attr.colorOnPrimaryContainer, 0xFF1E0A66.toInt()
        )
        paintOff.alpha = 70
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val n = if (peaks.isEmpty()) 60 else peaks.size
        val slot = w / n
        val barW = slot * 0.62f
        val minH = h * 0.06f
        val cy = h / 2f
        val p = if (dragging) dragFraction else progress
        for (i in 0 until n) {
            val a = if (peaks.isEmpty()) 0f else peaks[i].coerceIn(0f, 1f)
            // مقیاس غیرخطی تا صدای کم هم دیده شود
            val v = Math.sqrt(a.toDouble()).toFloat()
            val bh = minH + v * (h - minH)
            val x = i * slot + (slot - barW) / 2f
            rect.set(x, cy - bh / 2f, x + barW, cy + bh / 2f)
            val on = (i + 0.5f) / n <= p
            canvas.drawRoundRect(rect, barW / 2f, barW / 2f, if (on) paintOn else paintOff)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val f = (e.x / width.toFloat()).coerceIn(0f, 1f)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                dragging = true; dragFraction = f
                listener?.onScrub(f); invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                dragFraction = f
                listener?.onScrub(f); invalidate()
            }
            MotionEvent.ACTION_UP -> {
                dragging = false; progress = f
                listener?.onSeek(f); invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { dragging = false; invalidate() }
        }
        return true
    }
}
