package dev.terraformist.here

import android.content.Context
import android.location.Location

/** What to show. */
enum class Scene {
    /** Location permission not given yet. */
    NO_PERMISSION,

    /** No pins at all. */
    NO_PINS,

    /** The pin being looked at is empty (the app only: other pins may be in use). */
    EMPTY,

    /** Pins exist but we don't know where you are yet. */
    LOOKING,

    /** Far enough for an arrow and a distance. */
    FAR,

    /** Closer than GPS can tell apart: "NEAR", no arrow. */
    NEAR,
}

/** A placed pin and how to get to it from here (null if we don't know where you are). */
data class PinReading(val pin: Pin, val reading: Reading?)

/** Everything the widget, app and Glyph Toy need, worked out once. */
data class Snap(
    val scene: Scene,
    /** The pin being followed or looked at. */
    val pin: Pin?,
    val reading: Reading?,
    /** Every pin, placed or not, in order. */
    val pins: List<Pin>,
    /** Every placed pin with its reading. */
    val placed: List<PinReading>,
    val me: Location?,
    val now: Long,
) {
    /** Our position is old enough that the widget should say when it's from. */
    val stale: Boolean get() = me != null && now - me.time > 15 * 60_000L
}

object Now {

    /**
     * @param number the pin to look at; null means the one the widget follows
     *   (the selected pin if placed, else the first placed one).
     */
    fun snap(context: Context, number: Int? = null, me: Location? = Where.last(context)): Snap {
        val now = System.currentTimeMillis()
        val pins = Pins.all(context)
        val pin = if (number != null) pins[number - 1] else Pins.followed(context)
        val placed = pins.filter { it.placed }.map { p ->
            PinReading(p, me?.let { Geo.reading(it.latitude, it.longitude, it.accuracy, p) })
        }
        val reading = placed.firstOrNull { it.pin.number == pin?.number }?.reading
        val scene = when {
            !Where.hasLocation(context) -> Scene.NO_PERMISSION
            pin == null || placed.isEmpty() -> if (number != null) Scene.EMPTY else Scene.NO_PINS
            !pin.placed -> Scene.EMPTY
            reading == null -> Scene.LOOKING
            reading.near -> Scene.NEAR
            else -> Scene.FAR
        }
        return Snap(scene, pin, reading, pins, placed, me, now)
    }
}
