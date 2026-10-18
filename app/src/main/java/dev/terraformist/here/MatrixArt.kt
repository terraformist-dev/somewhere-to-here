package dev.terraformist.here

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * What the 25×25 Glyph Matrix shows, as agreed in the mockup. The pins are their own shapes
 * (solid, square, triangle), drawn 3×4: about a third smaller than on screen, so they don't crowd.
 *
 *  - MAP: you are the blinking dot in the middle; each pin sits where it is, relative to you.
 *    The map turns with you, so the top is the way you're facing.
 *  - One pin: the pin sits in its direction, on the edge when it's far, sliding in as you get
 *    closer, with a faint needle from the middle. NEAR: the pin in the middle in a breathing ring.
 *  - Just after switching to a pin: its shape and the distance, in numbers, for a few seconds.
 */
object MatrixArt {

    private const val C = GlyphShape.CENTER.toDouble()

    /** Where the pin sits from the middle when it's far / close, in dots. */
    private const val FAR_RADIUS = 9.4
    private const val CLOSE_RADIUS = 3.6

    /** Distances (m) that count as "far" and "close" for the pin view's sliding scale. */
    private const val FAR_METERS = 2_000.0
    private const val CLOSE_METERS = 30.0

    /** Largest radius on the map, in dots. */
    private const val MAP_RADIUS = 8.4

    /**
     * @param view 0 for the map, else the pin number to show.
     * @param up which way the top of the matrix faces, degrees from north (null: north is up).
     * @param showDistance show the pin's distance instead (just after switching to it).
     * @param still true for a frame that will stay on the matrix a while: no pulsing.
     */
    fun render(s: Snap, view: Int, up: Double?, showDistance: Boolean, still: Boolean, dim: Boolean): DotGrid {
        val grid = DotGrid(GlyphShape.SIZE, GlyphShape.SIZE)
        val facing = up ?: 0.0
        when {
            s.scene == Scene.NO_PERMISSION -> grid.set(12, 12, (255 * pulse(s.now, 2_400L, still)).toInt())
            s.placed.isEmpty() -> noPins(grid, s.now, still)
            s.placed.all { it.reading == null } -> grid.set(12, 12, (255 * pulse(s.now, 2_400L, still)).toInt())
            view == 0 -> map(grid, s, facing, still)
            else -> {
                val pr = s.placed.firstOrNull { it.pin.number == view }
                when {
                    pr?.reading == null -> map(grid, s, facing, still)
                    showDistance -> distance(grid, pr)
                    else -> onePin(grid, pr, facing, s.now, still)
                }
            }
        }
        if (dim) for (i in grid.levels.indices) grid.levels[i] = grid.levels[i] / 2
        return grid
    }

    /** The three shapes, faint, in a row: nothing pinned yet. */
    private fun noPins(grid: DotGrid, now: Long, still: Boolean) {
        val level = (110 * pulse(now, 3_000L, still)).toInt()
        var left = 6
        for (m in Mark.entries) {
            grid.sprite(m.small, left, 10, level)
            left += 5
        }
    }

    // ---------- The map ----------

    private class Spot(val pin: Pin, var x: Double, var y: Double, val level: Int)

    private fun map(grid: DotGrid, s: Snap, up: Double, still: Boolean) {
        // A faint dot at the top: the way you're facing.
        grid.set(12, 0, 40)
        // You, in the middle.
        grid.set(12, 12, (255 * pulse(s.now, 900L, still)).toInt())

        val readable = s.placed.filter { it.reading != null }
        if (readable.isEmpty()) return
        // Square-root scale, so a close pin isn't squashed onto you while a far one is on screen.
        val farthest = maxOf(readable.maxOf { it.reading!!.meters }, 100.0)
        val chosen = s.pin?.number
        val spots = readable.map { pr ->
            val r = pr.reading!!
            val radius = if (r.near) 0.0 else MAP_RADIUS * sqrt(r.meters / farthest)
            val a = Math.toRadians(r.bearing - up)
            Spot(pr.pin, C + radius * sin(a), C - radius * cos(a), if (pr.pin.number == chosen) 255 else 170)
        }
        spread(spots)
        for (spot in spots) drawSmall(grid, spot.pin.mark, spot.x, spot.y, spot.level)
    }

    /** Nudge pins apart so their shapes don't overlap: the map stays readable rather than exact. */
    private fun spread(spots: List<Spot>) {
        repeat(30) {
            var moved = false
            for (i in spots.indices) for (j in i + 1 until spots.size) {
                val a = spots[i]
                val b = spots[j]
                val dx = b.x - a.x
                val dy = b.y - a.y
                if (abs(dx) >= 4.0 || abs(dy) >= 5.0) continue
                val len = hypot(dx, dy)
                // On top of each other: push them apart sideways.
                val ux = if (len < 0.01) 1.0 else dx / len
                val uy = if (len < 0.01) 0.0 else dy / len
                a.x -= ux * 0.6
                a.y -= uy * 0.6
                b.x += ux * 0.6
                b.y += uy * 0.6
                moved = true
            }
            if (!moved) return
        }
    }

    /**
     * A small pin shape with the middle of its head at ([x], [y]), moved in toward the middle
     * just enough to sit wholly on the round matrix.
     */
    private fun drawSmall(grid: DotGrid, mark: Mark, x: Double, y: Double, level: Int) {
        var cx = x
        var cy = y
        for (step in 0 until 20) {
            val left = cx.roundToInt() - 1
            val top = cy.roundToInt() - 1
            var fits = true
            mark.small.forEachDot { gx, gy -> if (!GlyphShape.contains(left + gx, top + gy)) fits = false }
            if (fits) {
                grid.sprite(mark.small, left, top, level)
                return
            }
            cx += (C - cx) * 0.15
            cy += (C - cy) * 0.15
        }
        grid.sprite(mark.small, 11, 11, level)
    }

    // ---------- One pin ----------

    private fun onePin(grid: DotGrid, pr: PinReading, up: Double, now: Long, still: Boolean) {
        val r = pr.reading ?: return
        val mark = pr.pin.mark
        if (r.near) {
            val beat = pulse(now, 2_000L, still)
            for (y in 0 until GlyphShape.SIZE) for (x in 0 until GlyphShape.SIZE) {
                val d = hypot(x - C, y - C)
                if (abs(d - 6.0) < 0.5) grid.lift(x, y, (190 * beat).toInt())
            }
            grid.sprite(mark.small, 11, 11, 255)
            return
        }
        // Far: on the edge. Close: slid in. A log scale between, so the last few hundred metres show.
        val t = (log10(r.meters / CLOSE_METERS) / log10(FAR_METERS / CLOSE_METERS)).coerceIn(0.0, 1.0)
        val radius = CLOSE_RADIUS + (FAR_RADIUS - CLOSE_RADIUS) * t
        val a = Math.toRadians(r.bearing - up)
        val dirX = sin(a)
        val dirY = -cos(a)
        // The needle, brighter toward the pin.
        val needleEnd = radius - 2.6
        if (needleEnd > 1.4) {
            for (y in 0 until GlyphShape.SIZE) for (x in 0 until GlyphShape.SIZE) {
                val dx = x - C
                val dy = y - C
                val along = dx * dirX + dy * dirY
                val across = dx * dirY - dy * dirX
                if (along in 1.4..needleEnd && abs(across) <= 0.6) grid.lift(x, y, (50 + 110 * along / needleEnd).toInt())
            }
        }
        grid.lift(12, 12, 110)
        val level = (255 * (0.55 + 0.45 * pulse(now, 2_400L, still))).toInt()
        drawSmall(grid, mark, C + radius * dirX, C + radius * dirY, level)
    }

    /** The pin's shape at the top, its distance in the middle, the unit underneath. */
    private fun distance(grid: DotGrid, pr: PinReading) {
        val r = pr.reading ?: return
        grid.sprite(pr.pin.mark.small, 11, 2, 255)
        val font = DotFont.SMALL
        val d = r.distance
        val number = if (d.number.length > 5) d.number.replace(",", "") else d.number
        grid.text(font, number, (GlyphShape.SIZE - font.width(number)) / 2, 9)
        grid.text(font, d.unit, (GlyphShape.SIZE - font.width(d.unit)) / 2, 16, 120)
    }

    // ---------- Switch-on ----------

    /** How long the switch-on lasts. */
    const val INTRO_MS = 1_150L
    private const val FALL_MS = 420L
    private const val BOUNCE_MS = 560L
    private const val RIPPLE_MS = 980L
    private const val FADE_FROM_MS = 900L

    /** Where the dropped pin comes to rest: its top row, and the row its tail tip touches. */
    private const val REST_TOP = 9
    private const val GROUND = 16

    /**
     * The switch-on, played once when this toy is picked, so it's easy to tell apart from the
     * other Somewhere toys (Nothing's dissolve-and-shoot, Someone's heartbeat, Up There's arcing
     * pass): a big pin drops in from the top, lands in the middle with a little bounce, and a
     * ripple spreads out flat from where it landed. Then the pin and ripple fade as the normal
     * picture ([after]) fades in.
     */
    fun intro(elapsed: Long, after: DotGrid): DotGrid {
        val grid = DotGrid(GlyphShape.SIZE, GlyphShape.SIZE)
        val t = elapsed.coerceAtLeast(0L)
        val fade = ((t - FADE_FROM_MS).toDouble() / (INTRO_MS - FADE_FROM_MS)).coerceIn(0.0, 1.0)
        val keep = 1.0 - fade
        val pin = Mark.SOLID.big

        // The drop: falling faster and faster (like gravity), then a one-dot bounce.
        val top = when {
            t < FALL_MS -> {
                val p = t.toDouble() / FALL_MS
                (-pin.height + (REST_TOP + pin.height) * p * p).roundToInt()
            }
            t < BOUNCE_MS -> REST_TOP - (sin(PI * (t - FALL_MS) / (BOUNCE_MS - FALL_MS)) * 1.4).roundToInt()
            else -> REST_TOP
        }
        // A faint smear above it while it falls.
        if (t in 60 until FALL_MS) grid.sprite(pin, 10, top - 2, (50 * keep).toInt())
        pin.forEachDot { x, y -> grid.set(10 + x, top + y, (255 * keep).toInt()) }

        // The ripple: a flat ring spreading out from where the tail touched down.
        if (t in FALL_MS until RIPPLE_MS) {
            val q = (t - FALL_MS).toDouble() / (RIPPLE_MS - FALL_MS)
            val rx = 1.5 + q * 10.5
            val ry = rx * 0.3
            val level = (230 * (1 - q) * keep).toInt()
            var a = 0.0
            while (a < 360.0) {
                val r = Math.toRadians(a)
                val x = (C + rx * cos(r)).roundToInt()
                val y = (GROUND + ry * sin(r)).roundToInt()
                if (GlyphShape.contains(x, y)) grid.lift(x, y, level)
                a += 4.0
            }
        }

        if (fade > 0) {
            for (k in after.levels.indices) {
                grid.lift(k % GlyphShape.SIZE, k / GlyphShape.SIZE, (after.levels[k] * fade).toInt())
            }
        }
        return grid
    }

    /** 0.3..1, smoothly, once per [period]. Always 1 for a still frame. */
    private fun pulse(now: Long, period: Long, still: Boolean): Double {
        if (still) return 1.0
        val phase = (now % period).toDouble() / period
        return 0.3 + 0.7 * (0.5 + 0.5 * cos(2 * PI * phase))
    }
}
