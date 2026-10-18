package dev.terraformist.here

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.min
import kotlin.math.roundToInt

/** One of the three pin buttons: its shape, its name, and how far it is (or EMPTY). */
class PinTileView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var pin: Pin? = null
        set(value) {
            field = value
            invalidate()
        }

    /** "1.2 KM", "NEAR", "EMPTY"… */
    var detail: String = ""
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    var chosen = false
        set(value) {
            if (field == value) return
            field = value
            isSelected = value
            invalidate()
        }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val box = RectF()

    init {
        isClickable = true
        isFocusable = true
    }

    override fun onDraw(canvas: Canvas) {
        val p = pin ?: return
        val d = resources.displayMetrics.density
        val inset = 1.5f * d
        box.set(inset, inset, width - inset, height - inset)
        val radius = 20f * d
        if (chosen) {
            fill.color = 0xFF1A1A1A.toInt()
            canvas.drawRoundRect(box, radius, radius, fill)
            border.color = Art.WHITE
            border.strokeWidth = 2f * d
            border.pathEffect = null
        } else {
            border.color = 0xFF3A3A3A.toInt()
            border.strokeWidth = 2f * d
            border.pathEffect = DashPathEffect(floatArrayOf(0.1f * d, 5f * d), 0f)
            border.strokeCap = Paint.Cap.ROUND
        }
        canvas.drawRoundRect(box, radius, radius, border)

        val cx = width / 2f
        val markSize = min(30f * d, height * 0.32f)
        Art.markInBox(canvas, p.mark, cx, height * 0.30f, markSize, if (p.placed) Art.RED else 0xFF4A4A4A.toInt())
        val room = width - 16f * d
        Art.label(context, canvas, p.label, cx, height * 0.56f, 12f * d, Art.WHITE, isBold = true, maxWidth = room, centered = true)
        Art.label(context, canvas, detail, cx, height * 0.56f + 18f * d, 10f * d, Art.LABEL_GREY, maxWidth = room, centered = true)
    }
}

/** A line of dot-matrix text, used for the "Pin it." heading on the About card. */
class DotTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var text: String = ""
        set(value) {
            field = value
            contentDescription = value
            requestLayout()
            invalidate()
        }

    private val pitch: Float = 2.8f * resources.displayMetrics.density

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = (DotFont.LARGE.width(text) * pitch).roundToInt() + paddingLeft + paddingRight
        val h = (DotFont.LARGE.rows(text) * pitch).roundToInt() + paddingTop + paddingBottom
        setMeasuredDimension(resolveSize(w, widthMeasureSpec), resolveSize(h, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        Art.dots(canvas, DotFont.LARGE, text, paddingLeft.toFloat(), paddingTop.toFloat(), pitch)
    }
}

/** The three pin shapes side by side, red, in the corner of the About card. */
class MiniPinsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    override fun onDraw(canvas: Canvas) {
        val step = width / 3f
        val size = min(step * 0.8f, height.toFloat())
        for ((i, m) in Mark.entries.withIndex()) {
            Art.markInBox(canvas, m, step * (i + 0.5f), height / 2f, size, Art.RED)
        }
    }
}
