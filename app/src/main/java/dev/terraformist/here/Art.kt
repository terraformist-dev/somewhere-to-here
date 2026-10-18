package dev.terraformist.here

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** A pin shown on the compass ring: which shape, which way (degrees clockwise from the top), what colour. */
data class RingMark(val mark: Mark, val angle: Double, val color: Int)

/**
 * Everything drawn on screen: the dotted compass with its pins, the NEAR dial, the dot-matrix
 * numbers and the small Space Mono labels. Shared by the widget and the app so they always match.
 */
object Art {

    const val BLACK = 0xFF000000.toInt()
    const val WHITE = 0xFFF2F2F2.toInt()
    const val GREY = 0xFF9A9A9A.toInt()
    const val LABEL_GREY = 0xFF8A8A8A.toInt()
    const val FAINT = 0xFF6A6A6A.toInt()
    const val PIN_GREY = 0xFF5A5A5A.toInt()
    const val DIM = 0xFF3A3A3A.toInt()
    const val GHOST = 0xFF333333.toInt()
    const val RING = 0xFF3A3A3A.toInt()
    const val RED = 0xFFD71921.toInt()

    private var regular: Typeface? = null
    private var bold: Typeface? = null

    fun typeface(context: Context, isBold: Boolean): Typeface {
        val cached = if (isBold) bold else regular
        if (cached != null) return cached
        val loaded = try {
            Typeface.createFromAsset(context.assets, if (isBold) "SpaceMono-Bold.ttf" else "SpaceMono-Regular.ttf")
        } catch (e: RuntimeException) {
            Typeface.create(Typeface.MONOSPACE, if (isBold) Typeface.BOLD else Typeface.NORMAL)
        }
        if (isBold) bold = loaded else regular = loaded
        return loaded
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // ---------- Pins ----------

    /**
     * Draws a pin shape with the middle of its head at ([x], [y]). [pitch] is the distance
     * between dot centres. The tail points down (rotate the canvas to point it elsewhere).
     */
    fun mark(canvas: Canvas, mark: Mark, x: Float, y: Float, pitch: Float, color: Int) {
        dotPaint.color = color
        val r = pitch * 0.38f
        val left = x - 2 * pitch
        val top = y - 2 * pitch
        mark.big.forEachDot { gx, gy -> canvas.drawCircle(left + gx * pitch, top + gy * pitch, r, dotPaint) }
    }

    /** The pin shape filling a box ([size] wide) centred on ([cx], [cy]), tail included. */
    fun markInBox(canvas: Canvas, mark: Mark, cx: Float, cy: Float, size: Float, color: Int) {
        val pitch = size / 7f
        // Head centre is row 2 of 0..6: shift up so the whole 7-row shape is centred.
        mark(canvas, mark, cx, cy - pitch, pitch, color)
    }

    // ---------- The compass ----------

    // Designed in a 120-unit box: a ring of 48 dots, a dotted disc, a dotted arrow.
    private val ARROW = arrayOf(
        60 to 20, 50 to 30, 60 to 30, 70 to 30, 40 to 40, 60 to 40, 80 to 40,
        30 to 50, 60 to 50, 90 to 50, 60 to 60, 60 to 70, 60 to 80, 60 to 90, 60 to 100,
    )

    private fun ring(canvas: Canvas) {
        dotPaint.color = RING
        for (i in 0 until 48) {
            val a = Math.PI * 2 * i / 48
            canvas.drawCircle((60 + 54 * cos(a)).toFloat(), (60 + 54 * sin(a)).toFloat(), 1.6f, dotPaint)
        }
    }

    private fun ghostDisc(canvas: Canvas, radius: Float) {
        dotPaint.color = GHOST
        for (gy in 0..12) for (gx in 0..12) {
            val x = gx * 10f
            val y = gy * 10f
            val dx = x - 60f
            val dy = y - 60f
            if (sqrt(dx * dx + dy * dy) <= radius) canvas.drawCircle(x, y, 2f, dotPaint)
        }
    }

    /** A dotted circle around the middle. */
    private fun dottedCircle(canvas: Canvas, radius: Float, color: Int, dot: Float = 1.2f, spacing: Float = 6f) {
        dotPaint.color = color
        val n = maxOf(8, (2 * Math.PI * radius / spacing).toInt())
        for (i in 0 until n) {
            val a = Math.PI * 2 * i / n
            canvas.drawCircle((60 + radius * cos(a)).toFloat(), (60 + radius * sin(a)).toFloat(), dot, dotPaint)
        }
    }

    private fun ringMarks(canvas: Canvas, marks: List<RingMark>) {
        // Grey ones first, so the red one sits on top if two overlap.
        for (m in marks.sortedBy { if (it.color == RED) 1 else 0 }) {
            canvas.save()
            canvas.rotate(m.angle.toFloat(), 60f, 60f)
            mark(canvas, m.mark, 60f, 5f, 2.6f, m.color)
            canvas.restore()
        }
    }

    private fun begin(canvas: Canvas, cx: Float, cy: Float, size: Float) {
        canvas.save()
        canvas.translate(cx - size / 2f, cy - size / 2f)
        canvas.scale(size / 120f, size / 120f)
    }

    /**
     * The compass. [arrow]: which way to point, degrees clockwise from the top (null: no arrow).
     * [marks]: pins on the ring. [north]: a small white dot on the ring marks north.
     */
    fun compass(canvas: Canvas, cx: Float, cy: Float, size: Float, arrow: Double?, marks: List<RingMark>, north: Double? = null) {
        begin(canvas, cx, cy, size)
        ring(canvas)
        north?.let { northDot(canvas, it) }
        canvas.save()
        if (arrow != null) canvas.rotate(arrow.toFloat(), 60f, 60f)
        ghostDisc(canvas, 44f)
        if (arrow != null) {
            dotPaint.color = WHITE
            for ((x, y) in ARROW) canvas.drawCircle(x.toFloat(), y.toFloat(), 3.4f, dotPaint)
        }
        canvas.restore()
        ringMarks(canvas, marks)
        canvas.restore()
    }

    private fun northDot(canvas: Canvas, angle: Double) {
        val a = Math.toRadians(angle)
        dotPaint.color = WHITE
        canvas.drawCircle((60 + 54 * sin(a)).toFloat(), (60 - 54 * cos(a)).toFloat(), 2.2f, dotPaint)
    }

    /** NEAR: no arrow, the pin in the middle inside a dotted circle. [beat] gently pulses the circle. */
    fun nearDial(canvas: Canvas, cx: Float, cy: Float, size: Float, mark: Mark, others: List<RingMark>, north: Double? = null, beat: Float = 1f) {
        begin(canvas, cx, cy, size)
        ring(canvas)
        north?.let { northDot(canvas, it) }
        ghostDisc(canvas, 44f)
        dottedCircle(canvas, 26f + 3f * beat, GREY)
        markInBox(canvas, mark, 60f, 60f, 26f, RED)
        ringMarks(canvas, others)
        canvas.restore()
    }

    /** An empty pin (or no pins): the shape in grey in the middle. */
    fun emptyDial(canvas: Canvas, cx: Float, cy: Float, size: Float, mark: Mark, others: List<RingMark>, north: Double? = null) {
        begin(canvas, cx, cy, size)
        ring(canvas)
        north?.let { northDot(canvas, it) }
        ghostDisc(canvas, 44f)
        markInBox(canvas, mark, 60f, 60f, 34f, PIN_GREY)
        ringMarks(canvas, others)
        canvas.restore()
    }

    /** Pinning: dotted circles closing in as the phone gets surer, the pin in the middle. */
    fun pinningDial(canvas: Canvas, cx: Float, cy: Float, size: Float, mark: Mark, accuracy: Float?) {
        begin(canvas, cx, cy, size)
        ring(canvas)
        ghostDisc(canvas, 44f)
        dottedCircle(canvas, 40f, DIM)
        dottedCircle(canvas, 30f, DIM)
        val r = if (accuracy == null) 44f else (8f + accuracy * 1.1f).coerceIn(10f, 44f)
        dottedCircle(canvas, r, GREY)
        markInBox(canvas, mark, 60f, 60f, 22f, RED)
        canvas.restore()
    }

    /** Found it: the pin landed in the middle, with dotted ripples. */
    fun foundDial(canvas: Canvas, cx: Float, cy: Float, size: Float, mark: Mark) {
        begin(canvas, cx, cy, size)
        ring(canvas)
        for ((rx, color) in listOf(44f to 0xFF2A2A2A.toInt(), 30f to 0xFF4A4A4A.toInt(), 16f to LABEL_GREY)) {
            dotPaint.color = color
            val ry = rx * 0.3f
            val n = (rx * 1.2f).toInt()
            for (i in 0 until n) {
                val a = Math.PI * 2 * i / n
                canvas.drawCircle((60 + rx * cos(a)).toFloat(), (80 + ry * sin(a)).toFloat(), 1.2f, dotPaint)
            }
        }
        // Tail tip resting on the ripples' centre.
        mark(canvas, mark, 60f, 80f - 4 * 7.5f, 7.5f, RED)
        canvas.restore()
    }

    // ---------- Dot-matrix text ----------

    /** Draws [text] in dots, top-left at ([left], [top]), [pitch] pixels between dot centres. Returns its width. */
    fun dots(canvas: Canvas, font: DotFont, text: String, left: Float, top: Float, pitch: Float, color: Int = WHITE): Float {
        dotPaint.color = color
        val r = pitch * 0.42f
        font.forEachDot(text) { x, y ->
            canvas.drawCircle(left + x * pitch + pitch / 2f, top + y * pitch + pitch / 2f, r, dotPaint)
        }
        return font.width(text) * pitch
    }

    fun dotsWidth(font: DotFont, text: String, pitch: Float): Float = font.width(text) * pitch

    // ---------- Plain labels ----------

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bounds = Rect()

    private fun setUpText(context: Context, size: Float, isBold: Boolean, color: Int) {
        textPaint.typeface = typeface(context, isBold)
        textPaint.textSize = size
        textPaint.color = color
        textPaint.letterSpacing = 0.08f
    }

    fun textWidth(context: Context, text: String, size: Float, isBold: Boolean = false): Float {
        setUpText(context, size, isBold, WHITE)
        return textPaint.measureText(text)
    }

    fun capHeight(context: Context, size: Float, isBold: Boolean = false): Float {
        setUpText(context, size, isBold, WHITE)
        textPaint.getTextBounds("H", 0, 1, bounds)
        return bounds.height().toFloat()
    }

    /** Draws [text] with its capital letters' top at [top]. Shrinks it to fit [maxWidth]. Returns the cap height. */
    fun label(
        context: Context,
        canvas: Canvas,
        text: String,
        left: Float,
        top: Float,
        size: Float,
        color: Int = GREY,
        isBold: Boolean = false,
        maxWidth: Float = Float.MAX_VALUE,
        centered: Boolean = false,
        right: Boolean = false,
    ): Float {
        setUpText(context, size, isBold, color)
        var w = textPaint.measureText(text)
        if (w > maxWidth) {
            textPaint.textSize = size * maxWidth / w
            w = maxWidth
        }
        textPaint.getTextBounds("H", 0, 1, bounds)
        val cap = bounds.height().toFloat()
        val x = when {
            centered -> left - w / 2f
            right -> left - w
            else -> left
        }
        canvas.drawText(text, x, top + cap, textPaint)
        return cap
    }

    /** A small filled white status dot. */
    fun statusDot(canvas: Canvas, centerX: Float, centerY: Float, size: Float) {
        dotPaint.color = WHITE
        canvas.drawCircle(centerX, centerY, size / 2f, dotPaint)
    }

    /** A big dotted number with a small bold unit after it, bottoms lined up. Returns the total width. */
    fun numberWithUnit(context: Context, canvas: Canvas, number: String, unit: String, left: Float, top: Float, pitch: Float, unitSize: Float, gap: Float): Float {
        val numberW = dots(canvas, DotFont.LARGE, number, left, top, pitch)
        if (unit.isEmpty()) return numberW
        val cap = capHeight(context, unitSize, isBold = true)
        label(context, canvas, unit, left + numberW + gap, top + 7 * pitch - cap, unitSize, WHITE, isBold = true)
        return numberW + gap + textWidth(context, unit, unitSize, isBold = true)
    }

    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** A white pill button drawn into a picture (the widget can't hold real buttons over its art). */
    fun pill(context: Context, canvas: Canvas, text: String, left: Float, top: Float, width: Float, height: Float, size: Float) {
        pillPaint.color = WHITE
        canvas.drawRoundRect(RectF(left, top, left + width, top + height), height / 2f, height / 2f, pillPaint)
        val cap = capHeight(context, size, isBold = true)
        label(context, canvas, text, left + width / 2f, top + (height - cap) / 2f, size, BLACK, isBold = true, maxWidth = width - height * 0.6f, centered = true)
    }

    // ---------- Shared bits ----------

    /** All placed pins on the ring, the [chosen] one red. [up] = which way the top faces (0 = north). */
    fun ringMarksFor(s: Snap, chosen: Int?, up: Double, skipChosen: Boolean = false): List<RingMark> =
        s.placed.mapNotNull { pr ->
            val r = pr.reading ?: return@mapNotNull null
            if (skipChosen && pr.pin.number == chosen) return@mapNotNull null
            RingMark(pr.pin.mark, r.bearing - up, if (pr.pin.number == chosen) RED else PIN_GREY)
        }

    // ---------- The home-screen widget ----------

    /** What the widget says. */
    private class Card(
        val big: String,
        val unit: String,
        val line2: String,
        val line3: String?,
        val dot: Boolean,
        /** The headline is words, drawn smaller (like "NO PINS"). */
        val words: Boolean = false,
    )

    private fun card(s: Snap): Card {
        val pin = s.pin
        val r = s.reading
        return when (s.scene) {
            Scene.NO_PERMISSION -> Card("--", "", "TAP TO START", null, false)
            Scene.NO_PINS, Scene.EMPTY -> Card("NO PINS", "", "", null, false, words = true)
            Scene.LOOKING -> Card("--", "", "${pin!!.label} · FINDING YOU", null, false)
            Scene.FAR -> {
                val d = r!!.distance
                val walk = r.walk ?: Geo.cardinal(r.bearing)
                val asOf = if (s.stale) " · AS OF ${Clock.clock(s.me!!.time)}" else ""
                Card(d.number, d.unit, "${pin!!.label} · PINNED ${Clock.pinned(pin.at, s.now)}", walk + asOf, true)
            }
            Scene.NEAR -> Card(
                "NEAR", "",
                "${pin!!.label} · WITHIN ${r!!.nearWithin} M",
                pin.noteText.ifEmpty { "LOOK AROUND" }, true, words = true,
            )
        }
    }

    /** The picture: north is up, because a widget can't follow the phone. */
    private fun picture(canvas: Canvas, s: Snap, cx: Float, cy: Float, size: Float) {
        val chosen = s.pin?.number
        when (s.scene) {
            Scene.FAR -> compass(canvas, cx, cy, size, s.reading!!.bearing, ringMarksFor(s, chosen, 0.0), north = 0.0)
            Scene.NEAR -> nearDial(canvas, cx, cy, size, s.pin!!.mark, ringMarksFor(s, chosen, 0.0, skipChosen = true), north = 0.0)
            Scene.LOOKING -> compass(canvas, cx, cy, size, null, emptyList(), north = 0.0)
            else -> emptyDial(canvas, cx, cy, size, Mark.SOLID, emptyList())
        }
    }

    /** Spoken description of the widget, for screen readers. */
    fun describe(s: Snap): String {
        val pin = s.pin
        val r = s.reading
        return when (s.scene) {
            Scene.NO_PERMISSION -> "Somewhere to Here. Location is off. Tap to start."
            Scene.NO_PINS, Scene.EMPTY -> "Somewhere to Here. No pins. Tap to pin this spot."
            Scene.LOOKING -> "Somewhere to Here. Finding where you are."
            Scene.FAR -> "${pin!!.label} is ${Geo.formatShort(r!!.meters).lowercase()} away, ${Geo.cardinal(r.bearing)}."
            Scene.NEAR -> "${pin!!.label} is near, within about ${r!!.nearWithin} metres."
        }
    }

    /**
     * Draws the widget at its real size in pixels. Wide (4×2): the picture on the left, the
     * details on the right. Square (2×2): the picture over the headline.
     */
    fun widget(context: Context, width: Int, height: Int, s: Snap): Bitmap {
        val bmp = Bitmap.createBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        if (width >= height * 1.6f) wide(context, canvas, width.toFloat(), height.toFloat(), s)
        else square(context, canvas, width.toFloat(), height.toFloat(), s)
        return bmp
    }

    private fun wide(context: Context, canvas: Canvas, w: Float, h: Float, s: Snap) {
        val u = h / 170f
        val pad = 20f * u
        val size = min(h - 2 * pad, w * 0.4f)
        picture(canvas, s, pad + size / 2f, h / 2f, size)

        val c = card(s)
        val left = pad + size + 18f * u
        val room = w - left - pad

        if (s.scene == Scene.NO_PINS || s.scene == Scene.EMPTY) {
            val pitch = min(4.2f * u, room / DotFont.LARGE.width(c.big))
            val pillH = 44f * u
            val gap = 14f * u
            val top = (h - (7 * pitch + gap + pillH)) / 2f
            dots(canvas, DotFont.LARGE, c.big, left, top, pitch)
            pill(context, canvas, "PIN THIS SPOT", left, top + 7 * pitch + gap, room, pillH, 11f * u)
            return
        }

        val line2Size = 12f * u
        val line3Size = 11f * u
        val unitSize = 14f * u
        val unitGap = 6f * u
        val pitch = if (c.words) {
            min(6f * u, room / DotFont.LARGE.width(c.big))
        } else {
            val unitW = if (c.unit.isEmpty()) 0f else textWidth(context, c.unit, unitSize, isBold = true) + unitGap
            min(6f * u, (room - unitW) / DotFont.LARGE.width(c.big))
        }
        val bigH = 7 * pitch
        val gap1 = 12f * u
        val gap2 = 8f * u
        val block = bigH + gap1 + line2Size + (if (c.line3 != null) gap2 + line3Size else 0f)
        val top = (h - block) / 2f

        numberWithUnit(context, canvas, c.big, c.unit, left, top, pitch, unitSize, unitGap)
        val line2Top = top + bigH + gap1
        label(context, canvas, c.line2, left, line2Top, line2Size, GREY, maxWidth = room)

        val line3 = c.line3 ?: return
        val line3Top = line2Top + line2Size + gap2
        var textLeft = left
        if (c.dot) {
            val dotSize = 6f * u
            statusDot(canvas, left + dotSize / 2f, line3Top + capHeight(context, line3Size) / 2f, dotSize)
            textLeft += dotSize + 8f * u
        }
        label(context, canvas, line3, textLeft, line3Top, line3Size, WHITE, maxWidth = w - pad - textLeft)
    }

    private fun square(context: Context, canvas: Canvas, w: Float, h: Float, s: Snap) {
        val u = min(w, h) / 170f
        val pad = 14f * u
        val room = w - 2 * pad

        if (s.scene == Scene.NO_PINS || s.scene == Scene.EMPTY) {
            val size = min(84f * u, h * 0.5f)
            val pillH = 44f * u
            val pictureTop = pad + 2f * u
            picture(canvas, s, w / 2f, pictureTop + size / 2f, size)
            pill(context, canvas, "PIN HERE", pad, h - pad - pillH, room, pillH, 11f * u)
            return
        }

        val size = min(92f * u, h * 0.56f)
        val pictureTop = pad + 2f * u
        picture(canvas, s, w / 2f, pictureTop + size / 2f, size)

        val areaTop = pictureTop + size
        val areaHeight = h - pad - areaTop
        val c = card(s)

        when (s.scene) {
            Scene.NO_PERMISSION, Scene.LOOKING -> {
                val size2 = 11f * u
                val words = if (s.scene == Scene.LOOKING) "FINDING YOU" else c.line2
                label(context, canvas, words, w / 2f, areaTop + (areaHeight - size2 * 0.7f) / 2f, size2, GREY, maxWidth = room, centered = true)
            }
            else -> {
                val unitSize = 12f * u
                val gap = 5f * u
                val unitW = if (c.unit.isEmpty()) 0f else textWidth(context, c.unit, unitSize, isBold = true) + gap
                val pitch = min(4.2f * u, (room - unitW) / DotFont.LARGE.width(c.big))
                val total = dotsWidth(DotFont.LARGE, c.big, pitch) + unitW
                numberWithUnit(context, canvas, c.big, c.unit, (w - total) / 2f, areaTop + (areaHeight - 7 * pitch) / 2f, pitch, unitSize, gap)
            }
        }
    }
}
