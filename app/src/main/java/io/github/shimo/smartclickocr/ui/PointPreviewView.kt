package io.github.shimo.smartclickocr.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import io.github.shimo.smartclickocr.util.ScreenMetricsProvider

/**
 * Miniature map of the screen showing a calibrated tap point or a swipe vector.
 * Purely visual feedback — it never dispatches gestures.
 */
class PointPreviewView(context: Context) : View(context) {

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF3D00")
        style = Paint.Style.FILL
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF3D00")
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1565C0")
        strokeWidth = 5f
        strokeCap = Paint.Cap.ROUND
    }
    private val bgPaint = Paint().apply { color = Color.parseColor("#ECEFF1") }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#37474F")
        textSize = 28f
    }

    private var screenW = 1080
    private var screenH = 2400
    private var point: Pair<Float, Float>? = null
    private var swipe: FloatArray? = null

    fun setPoint(x: Float, y: Float) {
        val meta = ScreenMetricsProvider.current()
        if (meta.isValid) {
            screenW = meta.widthPx
            screenH = meta.heightPx
        }
        point = Pair(x, y)
        swipe = null
        invalidate()
    }

    fun setSwipe(x: Float, y: Float, endX: Float, endY: Float) {
        val meta = ScreenMetricsProvider.current()
        if (meta.isValid) {
            screenW = meta.widthPx
            screenH = meta.heightPx
        }
        swipe = floatArrayOf(x, y, endX, endY)
        point = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        val scale = minOf(width / screenW.toFloat(), height / screenH.toFloat())
        val drawnW = screenW * scale
        val drawnH = screenH * scale
        val offX = (width - drawnW) / 2f
        val offY = (height - drawnH) / 2f
        canvas.drawRect(offX, offY, offX + drawnW, offY + drawnH, ringPaint)

        point?.let { (x, y) ->
            val px = offX + x * scale
            val py = offY + y * scale
            canvas.drawCircle(px, py, 16f, dotPaint)
            canvas.drawCircle(px, py, 30f, ringPaint)
            canvas.drawText("(${x.toInt()}, ${y.toInt()})", px + 26f, py - 20f, textPaint)
        }

        swipe?.let { s ->
            val x1 = offX + s[0] * scale
            val y1 = offY + s[1] * scale
            val x2 = offX + s[2] * scale
            val y2 = offY + s[3] * scale
            canvas.drawLine(x1, y1, x2, y2, linePaint)
            canvas.drawCircle(x1, y1, 14f, dotPaint)
            canvas.drawCircle(x2, y2, 14f, dotPaint)
        }
    }
}
