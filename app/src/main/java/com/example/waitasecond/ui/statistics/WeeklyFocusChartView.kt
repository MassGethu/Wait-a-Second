package com.example.waitasecond.ui.statistics

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.example.waitasecond.R
import com.example.waitasecond.data.repository.DayStatistics
import com.example.waitasecond.ui.components.FocusFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class WeeklyFocusChartView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val accent = ContextCompat.getColor(context, R.color.accent_blue)
    private val secondary = ContextCompat.getColor(context, R.color.text_secondary)
    private val border = ContextCompat.getColor(context, R.color.surface_border)
    private var days = emptyList<DayStatistics>()
    private var labels = emptyList<String>()
    private var values = emptyList<String>()
    fun bind(next: List<DayStatistics>) {
        if (days == next) return
        days = next
        val format = SimpleDateFormat("EEE", Locale.getDefault())
        labels = next.map { format.format(Date(it.start)) }
        values = next.map { FocusFormat.duration(it.statistics.studyMillis) }
        contentDescription = next.mapIndexed { i, day -> "${labels[i]} ${values[i]}, ${day.statistics.attempts} attempts, ${day.statistics.redirects} returns" }.joinToString("; ")
        invalidate()
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (days.isEmpty()) return
        val d = resources.displayMetrics.density
        val top = 22 * d
        val bottom = height - 24 * d
        val column = width.toFloat() / days.size
        val maximum = days.maxOf { it.statistics.studyMillis }.coerceAtLeast(60_000)
        paint.color = border; paint.strokeWidth = d
        canvas.drawLine(0f, bottom, width.toFloat(), bottom, paint)
        paint.textAlign = Paint.Align.CENTER; paint.textSize = 10 * d
        days.forEachIndexed { index, day ->
            val x = column * (index + 0.5f)
            val y = bottom - (bottom - top) * day.statistics.studyMillis.toFloat() / maximum
            paint.color = accent
            if (day.statistics.studyMillis > 0) canvas.drawRoundRect(x - column * 0.22f, y, x + column * 0.22f, bottom, 4 * d, 4 * d, paint)
            paint.color = secondary
            canvas.drawText(labels[index], x, bottom + 18 * d, paint)
            canvas.drawText(values[index], x, y - 6 * d, paint)
        }
    }
}
