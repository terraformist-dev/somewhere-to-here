package dev.terraformist.here

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.View
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The top of the app's screen, drawn by hand so it matches the widget dot for dot: the title,
 * the compass (or the NEAR dial, the pinning dial, the found-it dial) and the words under it.
 * Unlike the widget, the compass turns with the phone. It shrinks when the keyboard is up.
 */
class HereView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** What the screen is doing. */
    sealed class Mode {
        /** Following the selected pin (or showing it's empty). */
        data object Normal : Mode()

        /** Saving pin [number]; [accuracy] is how sure the best position so far is (null: none yet). */
        data class Pinning(val number: Int, val accuracy: Float?, val good: Boolean) : Mode()

        /** Just found pin [number]. */
        data class Found(val number: Int) : Mode()
    }

    var snap: Snap? = null
    var mode: Mode = Mode.Normal

    /** Which way the top of the screen faces (degrees from north), or null if unknown. */
    var heading: Double? = null
    var compassUnsure = false

    /** Where the title sits, so a tap area can be put on it. */
    fun titleCenterY(): Float = TITLE_TOP * unit() + Art.capHeight(context, 11f * unit()) / 2f

    fun titleWidth(): Float = Art.textWidth(context, TITLE, 11f * unit())

    private fun unit(): Float = width / 390f

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Art.BLACK)
        val s = snap ?: return
        val u = unit()
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val room = w - 48f * u

        Art.label(context, canvas, TITLE, cx, TITLE_TOP * u, 11f * u, Art.LABEL_GREY, centered = true)
        var y = TITLE_TOP * u + 11f * u + 22f * u

        // What goes under the picture, so the picture can take what's left.
        val pitch = 10f * u
        val words = words(s)
        val below = 18f * u + 7 * pitch + 14f * u + 12f * u + (if (words.second != null) 10f * u + 12f * u else 0f) + 12f * u
        val hint = if (compassUnsure && mode == Mode.Normal && s.scene == Scene.FAR) "COMPASS UNSURE: WAVE IN A FIGURE OF 8" else null
        val hintRoom = if (hint != null) 24f * u else 0f
        val size = min(230f * u, h - y - below - hintRoom)
        if (size < 70f * u) {
            // Keyboard up on a small screen: just the headline.
            headline(canvas, s, cx, y, min(pitch, (h - y - 8f * u) / 7f), room)
            return
        }
        picture(canvas, s, cx, y + size / 2f, size)
        y += size + 18f * u

        headline(canvas, s, cx, y, pitch, room)
        y += 7 * pitch + 14f * u
        twoTone(canvas, words.first.first, words.first.second, cx, y, 12f * u, room)
        words.second?.let {
            y += 12f * u + 10f * u
            Art.label(context, canvas, it, cx, y, 11f * u, Art.LABEL_GREY, maxWidth = room, centered = true)
        }
        if (hint != null) Art.label(context, canvas, hint, cx, h - 18f * u, 10f * u, Art.FAINT, maxWidth = room, centered = true)
    }

    private fun picture(canvas: Canvas, s: Snap, cx: Float, cy: Float, size: Float) {
        val h = heading
        val up = h ?: 0.0
        val north = h?.let { -it } ?: 0.0
        when (val m = mode) {
            is Mode.Pinning -> Art.pinningDial(canvas, cx, cy, size, Mark.of(m.number), m.accuracy)
            is Mode.Found -> Art.foundDial(canvas, cx, cy, size, Mark.of(m.number))
            Mode.Normal -> {
                val chosen = s.pin?.number
                when (s.scene) {
                    Scene.FAR -> Art.compass(canvas, cx, cy, size, s.reading!!.bearing - up, Art.ringMarksFor(s, chosen, up), north)
                    Scene.NEAR -> {
                        val beat = (0.5 + 0.5 * sin(2 * PI * (s.now % 2_000L) / 2_000.0)).toFloat()
                        Art.nearDial(canvas, cx, cy, size, s.pin!!.mark, Art.ringMarksFor(s, chosen, up, skipChosen = true), north, beat)
                    }
                    Scene.EMPTY, Scene.NO_PINS ->
                        Art.emptyDial(canvas, cx, cy, size, s.pin?.mark ?: Mark.SOLID, Art.ringMarksFor(s, chosen, up), north)
                    else -> Art.compass(canvas, cx, cy, size, null, emptyList(), north)
                }
            }
        }
    }

    /** The dotted headline: a distance with its unit, or a word. */
    private fun headline(canvas: Canvas, s: Snap, cx: Float, top: Float, pitch: Float, room: Float) {
        val (number, unit) = when (val m = mode) {
            is Mode.Pinning -> (m.accuracy?.roundToInt()?.toString() ?: "--") to "M"
            is Mode.Found -> "FOUND IT" to ""
            Mode.Normal -> when (s.scene) {
                Scene.FAR -> s.reading!!.distance.let { it.number to it.unit }
                Scene.NEAR -> "NEAR" to ""
                Scene.EMPTY, Scene.NO_PINS -> "EMPTY" to ""
                else -> "--" to ""
            }
        }
        val unitSize = 18f * unit()
        val gap = 10f * unit()
        val unitW = if (unit.isEmpty()) 0f else Art.textWidth(context, unit, unitSize, isBold = true) + gap
        val p = min(pitch, (room - unitW) / DotFont.LARGE.width(number))
        val total = Art.dotsWidth(DotFont.LARGE, number, p) + unitW
        Art.numberWithUnit(context, canvas, number, unit, cx - total / 2f, top + 7 * (pitch - p), p, unitSize, gap)
    }

    /** ("grey part", "white part") for the first line, and an optional quieter second line. */
    private fun words(s: Snap): Pair<Pair<String, String>, String?> {
        val pin = s.pin
        val label = pin?.label ?: "PIN"
        return when (val m = mode) {
            is Mode.Pinning -> {
                val p = s.pins[m.number - 1]
                (p.label to if (m.good) "GOT IT" else "HOLD STILL") to "SAVES ITSELF WHEN IT'S SURE TO 10 M"
            }
            is Mode.Found -> {
                val p = s.pins[m.number - 1]
                (p.label to "PINNED ${Clock.pinned(p.at, s.now)}") to null
            }
            Mode.Normal -> when (s.scene) {
                Scene.NO_PERMISSION -> ("LOCATION" to "IS OFF") to null
                Scene.EMPTY, Scene.NO_PINS -> (label to "IS FREE") to "TAP PIN THIS SPOT TO SAVE WHERE YOU ARE"
                Scene.LOOKING -> (label to "FINDING YOU") to null
                Scene.FAR -> {
                    val r = s.reading!!
                    val turn = heading?.let { Geo.turn(r.bearing - it) } ?: Geo.cardinal(r.bearing)
                    (label to turn) to (r.walk ?: "PINNED ${Clock.pinned(pin!!.at, s.now)}")
                }
                Scene.NEAR -> (label to "WITHIN ${s.reading!!.nearWithin} M") to "LOOK AROUND"
            }
        }
    }

    /** "grey part  /  white part", centred. */
    private fun twoTone(canvas: Canvas, first: String, second: String, cx: Float, top: Float, size: Float, room: Float) {
        val slash = "  /  "
        val w1 = Art.textWidth(context, first, size)
        val w2 = Art.textWidth(context, slash, size)
        val w3 = Art.textWidth(context, second, size)
        val scale = min(1f, room / (w1 + w2 + w3))
        val sz = size * scale
        var x = cx - (w1 + w2 + w3) * scale / 2f
        Art.label(context, canvas, first, x, top, sz, Art.GREY)
        x += w1 * scale
        Art.label(context, canvas, slash, x, top, sz, Art.PIN_GREY)
        x += w2 * scale
        Art.label(context, canvas, second, x, top, sz, Art.WHITE)
    }

    private companion object {
        const val TITLE = "SOMEWHERE TO HERE"
        const val TITLE_TOP = 28f
    }
}
