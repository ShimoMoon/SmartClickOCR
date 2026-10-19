package io.github.shimo.smartclickocr.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import io.github.shimo.smartclickocr.util.ScreenMetricsProvider

/**
 * Miniature map of the screen highlighting the selected OCR region.
 */
class RegionPreviewView(context: Context) : View(context) {

    private val bgPaint = Paint().apply { color = Color.parseColor("#ECEFF1") }
    private val screenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#B0BEC5")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val regionFill = Paint().apply { color = Color.parseColor("#5500E676") }
    private val regionStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00A050")
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#263238")
        textSize = 28f
    }

    private var screenW = 1080
    private var screenH = 2400
    private var region: IntArray? = null

    fun setRegion(left: Int, top: Int, right: Int, bottom: Int) {
        val meta = ScreenMetricsProvider.current()
        if (meta.isValid) {
            screenW = meta.widthPx
            screenH = meta.heightPx
        }
        region = intArrayOf(left, top, right, bottom)
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
        canvas.drawRect(offX, offY, offX + drawnW, offY + drawnH, screenPaint)

        region?.let { r ->
            val l = offX + r[0] * scale
            val t = offY + r[1] * scale
            val rr = offX + r[2] * scale
            val b = offY + r[3] * scale
            canvas.drawRect(l, t, rr, b, regionFill)
            canvas.drawRect(l, t, rr, b, regionStroke)
            canvas.drawText("${r[2] - r[0]} × ${r[3] - r[1]}", l, (t - 10f).coerceAtLeast(24f), textPaint)
        }
    }
}
