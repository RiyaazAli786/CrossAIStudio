package com.pintoinsta.app.ui.analytics

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.pintoinsta.app.R
import kotlin.math.max

class AnalyticsBarChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    data class BarGroup(
        val label: String,
        val published: Int,
        val failed: Int
    )

    private var items: List<BarGroup> = emptyList()

    private val publishedColor = Color.parseColor("#10B981") // Emerald Green
    private val failedColor = Color.parseColor("#EF4444")    // Rose Red
    private val textSecondaryColor = Color.parseColor("#94A3B8") // Slate 400
    private val gridLineColor = Color.parseColor("#334155") // Slate 700 / Dark border

    private val publishedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = publishedColor
        style = Paint.Style.FILL
    }

    private val failedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = failedColor
        style = Paint.Style.FILL
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = gridLineColor
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textSecondaryColor
        textSize = 28f
        textAlign = Paint.Align.CENTER
    }

    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 26f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    private val legendTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textSecondaryColor
        textSize = 30f
        textAlign = Paint.Align.LEFT
    }

    fun setData(newItems: List<BarGroup>) {
        items = newItems
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()

        if (w <= 0 || h <= 0) return

        val paddingLeft = 40f
        val paddingRight = 40f
        val paddingTop = 70f
        val paddingBottom = 60f

        // Draw Legend at the top
        drawLegend(canvas, paddingLeft, 35f)

        if (items.isEmpty()) {
            textPaint.textSize = 32f
            canvas.drawText("No activity data to display yet", w / 2f, h / 2f, textPaint)
            return
        }

        val plotLeft = paddingLeft
        val plotRight = w - paddingRight
        val plotTop = paddingTop
        val plotBottom = h - paddingBottom
        val plotHeight = plotBottom - plotTop
        val plotWidth = plotRight - plotLeft

        // Find max value for scale (at least 5 for nice visual ceiling)
        val maxVal = max(5, items.maxOfOrNull { max(it.published, it.failed) } ?: 5)

        // Draw horizontal grid lines (0, 50%, 100%)
        for (i in 0..2) {
            val ratio = i / 2f
            val y = plotBottom - (plotHeight * ratio)
            canvas.drawLine(plotLeft, y, plotRight, y, gridPaint)
        }

        // Draw Bars
        val groupCount = items.size
        val groupWidth = plotWidth / groupCount
        val barWidth = max(16f, minOf(40f, groupWidth * 0.28f))
        val barSpacing = 6f
        val cornerRadius = 8f

        for ((index, item) in items.withIndex()) {
            val groupCenterX = plotLeft + (index * groupWidth) + (groupWidth / 2f)

            // Published bar (left)
            val pubHeight = (item.published.toFloat() / maxVal.toFloat()) * (plotHeight - 30f)
            val pubLeft = groupCenterX - barWidth - (barSpacing / 2f)
            val pubRight = groupCenterX - (barSpacing / 2f)
            val pubTop = plotBottom - pubHeight

            if (item.published > 0) {
                val pubRect = RectF(pubLeft, pubTop, pubRight, plotBottom)
                canvas.drawRoundRect(pubRect, cornerRadius, cornerRadius, publishedPaint)
                // Draw value on top of bar
                canvas.drawText("${item.published}", (pubLeft + pubRight) / 2f, max(plotTop + 15f, pubTop - 8f), valuePaint)
            } else {
                // Draw zero baseline indicator
                val baselineRect = RectF(pubLeft, plotBottom - 4f, pubRight, plotBottom)
                canvas.drawRoundRect(baselineRect, 2f, 2f, publishedPaint)
            }

            // Failed bar (right)
            val failHeight = (item.failed.toFloat() / maxVal.toFloat()) * (plotHeight - 30f)
            val failLeft = groupCenterX + (barSpacing / 2f)
            val failRight = groupCenterX + barWidth + (barSpacing / 2f)
            val failTop = plotBottom - failHeight

            if (item.failed > 0) {
                val failRect = RectF(failLeft, failTop, failRight, plotBottom)
                canvas.drawRoundRect(failRect, cornerRadius, cornerRadius, failedPaint)
                // Draw value on top of bar
                canvas.drawText("${item.failed}", (failLeft + failRight) / 2f, max(plotTop + 15f, failTop - 8f), valuePaint)
            } else {
                val baselineRect = RectF(failLeft, plotBottom - 4f, failRight, plotBottom)
                canvas.drawRoundRect(baselineRect, 2f, 2f, failedPaint)
            }

            // Draw label below bars
            textPaint.textSize = if (groupCount > 5) 24f else 28f
            val formattedLabel = if (item.label.length > 9) "${item.label.take(7)}…" else item.label
            canvas.drawText(formattedLabel, groupCenterX, plotBottom + 35f, textPaint)
        }
    }

    private fun drawLegend(canvas: Canvas, startX: Float, y: Float) {
        val dotRadius = 8f
        // Published Dot + Text
        canvas.drawCircle(startX + 10f, y - 8f, dotRadius, publishedPaint)
        canvas.drawText("Published", startX + 26f, y, legendTextPaint)

        // Failed Dot + Text
        val failedStartX = startX + 200f
        canvas.drawCircle(failedStartX + 10f, y - 8f, dotRadius, failedPaint)
        canvas.drawText("Failed", failedStartX + 26f, y, legendTextPaint)
    }
}
