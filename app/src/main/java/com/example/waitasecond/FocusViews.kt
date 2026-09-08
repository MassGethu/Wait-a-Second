package com.example.waitasecond

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import java.util.Locale

object FocusFormat {
    fun timer(millis: Long): String {
        val seconds = (millis.coerceAtLeast(0) + 999) / 1000
        return String.format(Locale.getDefault(), "%02d:%02d", seconds / 60, seconds % 60)
    }
    fun duration(millis: Long): String {
        val minutes = millis.coerceAtLeast(0) / 60_000
        return if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"
    }
    fun topic(goal: String) = goal.removePrefix("Study ").trim()
}

/** Drawn timer ring, bound to the monotonic session deadline rather than its own clock. */
class FocusTimerView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val density = resources.displayMetrics.density
    private var remaining = 25 * 60_000L
    private var total = remaining
    private var running = false
    fun bind(remainingMillis: Long, totalMillis: Long, active: Boolean) {
        if (remaining == remainingMillis && total == totalMillis && running == active) return
        remaining = remainingMillis; total = totalMillis; running = active
        contentDescription = "${FocusFormat.timer(remaining)} ${if (active) "remaining" else "focus timer"}"
        invalidate()
    }
    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val desired = (292 * density).toInt()
        val size = minOf(resolveSize(desired, widthSpec), resolveSize(desired, heightSpec))
        setMeasuredDimension(size, size)
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f; val cy = height / 2f; val r = width * .41f
        paint.style = Paint.Style.FILL
        paint.shader = RadialGradient(cx, cy, r * 1.2f, intArrayOf(Color.parseColor("#172642"), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, r * 1.2f, paint); paint.shader = null
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 1 * density; paint.color = Color.parseColor("#233653")
        canvas.drawCircle(cx, cy, r + 13 * density, paint)
        paint.strokeWidth = 7 * density; paint.color = Color.parseColor("#1D2A43")
        canvas.drawCircle(cx, cy, r, paint)
        paint.strokeCap = Paint.Cap.ROUND
        paint.shader = LinearGradient(cx - r, cy - r, cx + r, cy + r,
            Color.parseColor("#679CFF"), Color.parseColor("#A08AFF"), Shader.TileMode.CLAMP)
        val sweep = if (total > 0) 360f * remaining.toFloat() / total else 0f
        canvas.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), -90f, sweep.coerceIn(0f, 360f), false, paint)
        paint.shader = null; paint.strokeCap = Paint.Cap.BUTT
        paint.style = Paint.Style.FILL
        val angle = Math.toRadians((sweep - 90).toDouble())
        val dx = cx + r * kotlin.math.cos(angle).toFloat()
        val dy = cy + r * kotlin.math.sin(angle).toFloat()
        paint.color = Color.parseColor("#B3C8FF"); canvas.drawCircle(dx, dy, 4.5f * density, paint)
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        paint.textSize = width * .19f; paint.color = Color.parseColor("#F3F6FF")
        canvas.drawText(FocusFormat.timer(remaining), cx, cy + 10 * density, paint)
        paint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        paint.textSize = 11 * density; paint.color = Color.parseColor("#92A5C7")
        canvas.drawText(if (running) "TIME TO FOCUS" else "MAKE SPACE TO FOCUS", cx, cy + 38 * density, paint)
        paint.textSize = 10 * density; paint.color = Color.parseColor("#B3C8FF")
        canvas.drawText(if (running) "SESSION ACTIVE" else "YOUR NEXT SESSION", cx, cy - 42 * density, paint)
    }
}

/** Two honest bars: accumulated YouTube/Instagram foreground time in this session. */
class AppTimeChartView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var values = listOf(0L, 0L)
    fun bind(youtube: Long, instagram: Long) {
        val next = listOf(youtube, instagram)
        if (next == values) return
        values = next
        contentDescription = "YouTube ${FocusFormat.duration(youtube)}, Instagram ${FocusFormat.duration(instagram)}"
        invalidate()
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val d = resources.displayMetrics.density
        val left = 14 * d; val right = width - left
        val top = 24 * d; val bottom = height - 30 * d
        paint.strokeWidth = d; paint.color = Color.parseColor("#24334D")
        repeat(4) { val y = top + (bottom - top) * it / 3; canvas.drawLine(left, y, right, y, paint) }
        val max = (values.maxOrNull() ?: 0).coerceAtLeast(60_000)
        values.forEachIndexed { index, value ->
            val x = width * (if (index == 0) .30f else .70f)
            val barWidth = minOf(52 * d, width * .16f)
            val barTop = bottom - (bottom - top) * value.toFloat() / max
            paint.shader = LinearGradient(x, top, x, bottom,
                Color.parseColor(if (index == 0) "#779FFF" else "#B198FF"),
                Color.parseColor(if (index == 0) "#365AB1" else "#644FA3"), Shader.TileMode.CLAMP)
            if (value > 0) canvas.drawRoundRect(x - barWidth / 2, barTop, x + barWidth / 2, bottom, 7 * d, 7 * d, paint)
            paint.shader = null; paint.textAlign = Paint.Align.CENTER; paint.textSize = 11 * d
            paint.color = Color.parseColor("#A5B2CC")
            canvas.drawText(if (index == 0) "YouTube" else "Instagram", x, bottom + 23 * d, paint)
            paint.color = Color.parseColor("#D5DFF4")
            canvas.drawText(FocusFormat.duration(value), x, barTop - 8 * d, paint)
        }
    }
}

/** Shared row for the monitored-app list and per-app statistics. */
class TrackedAppView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {
    private val icon = ImageView(context)
    private val title = TextView(context)
    private val detail = TextView(context)
    private val trailing = TextView(context)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    init {
        orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(72); setPadding(dp(4), dp(10), dp(4), dp(10))
        icon.setBackgroundResource(R.drawable.bg_inset); icon.setPadding(dp(10), dp(10), dp(10), dp(10))
        addView(icon, LayoutParams(dp(46), dp(46)))
        val labels = LinearLayout(context).apply { orientation = VERTICAL; setPadding(dp(14), 0, dp(8), 0) }
        title.textSize = 15f; title.setTextColor(ContextCompat.getColor(context, R.color.text_primary))
        title.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        detail.textSize = 12f; detail.setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
        detail.setPadding(0, dp(4), 0, 0)
        labels.addView(title); labels.addView(detail)
        addView(labels, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        trailing.textSize = 12f; trailing.setTextColor(ContextCompat.getColor(context, R.color.accent_blue))
        addView(trailing)
    }
    fun bind(app: String, description: String, caption: String) {
        title.text = app; detail.text = description; trailing.text = caption
        icon.setImageResource(if (app == "YouTube") R.drawable.ic_youtube else R.drawable.ic_instagram)
        icon.contentDescription = null
    }
}
