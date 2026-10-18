package dev.terraformist.here

import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/** A distance split into the dotted number and its unit: "1.2" + "KM", "340" + "M". */
data class Distance(val number: String, val unit: String)

/** How far away a pin is and which way, from where you are. No Android code, so it's easy to check. */
data class Reading(
    val meters: Double,
    /** Compass direction to the pin, in degrees clockwise from true north. */
    val bearing: Double,
    /** How far off your position and the pin's could be, together, in metres. */
    val spread: Double = 0.0,
) {
    /**
     * Closer than the positions can tell apart, so the distance and direction would be noise.
     * Shown as "NEAR", with no arrow. (Learnt from Somewhere to my Someone: indoors, GPS is
     * 6–12 m out, so the arrow needs the distance to be at least twice the combined error.)
     */
    val near: Boolean get() = meters < nearLimit

    private val nearLimit: Double get() = maxOf(2 * spread, NEAR_MIN_METERS)

    /** The rough radius to show with "NEAR", rounded up to 10 m. */
    val nearWithin: Int get() = (Math.ceil(nearLimit / 10.0) * 10).toInt()

    val distance: Distance get() = Geo.format(meters)

    /** Walking minutes at an easy 4.8 km/h, at least 1. */
    val walkMinutes: Int get() = (meters / 80.0).roundToInt().coerceAtLeast(1)

    /** "15 MIN WALK", "2 H 10 WALK", or null when it's too far to walk. */
    val walk: String?
        get() {
            val m = walkMinutes
            return when {
                meters > 30_000 -> null
                m < 60 -> "$m MIN WALK"
                else -> "${m / 60} H ${m % 60} WALK"
            }
        }

    companion object {
        /** "NEAR" never means less than this. */
        const val NEAR_MIN_METERS = 25.0
    }
}

object Geo {

    private const val EARTH_RADIUS_M = 6_371_000.0

    fun reading(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double, spread: Double = 0.0): Reading = Reading(
        meters = distanceMeters(fromLat, fromLon, toLat, toLon),
        bearing = bearingDegrees(fromLat, fromLon, toLat, toLon),
        spread = spread,
    )

    /** From you (with how sure your position is) to a pin (with how sure it was). */
    fun reading(
        fromLat: Double,
        fromLon: Double,
        fromAccuracy: Float,
        pin: Pin,
    ): Reading = reading(
        fromLat, fromLon, pin.latitude, pin.longitude,
        spread = hypot(fromAccuracy.toDouble(), pin.accuracy.toDouble()),
    )

    /** Great-circle distance in metres. */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = Math.toRadians(lat2 - lat1)
        val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * EARTH_RADIUS_M * atan2(sqrt(a), sqrt(1 - a))
    }

    /** Starting direction of the shortest path, degrees clockwise from true north (0..360). */
    fun bearingDegrees(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dl = Math.toRadians(lon2 - lon1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /**
     * Metres to the nearest 10 under 1 km ("340" M), one decimal under 10 km ("1.2" KM),
     * then whole kilometres with commas ("5,570" KM). The point is drawn as one dot.
     */
    fun format(meters: Double): Distance = when {
        meters < 995 -> Distance((((meters / 10).roundToInt()) * 10).toString(), "M")
        meters < 9_950 -> Distance(String.format(Locale.US, "%.1f", meters / 1000), "KM")
        else -> Distance(NumberFormat.getIntegerInstance(Locale.US).format((meters / 1000).roundToLong()), "KM")
    }

    /** "1.2 KM" */
    fun formatShort(meters: Double): String = format(meters).let { "${it.number} ${it.unit}" }

    /** Sixteen compass points: N, NNE, NE … NNW. */
    fun cardinal(degrees: Double): String {
        val names = arrayOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW")
        return names[((((degrees % 360) + 360) % 360) / 22.5).roundToInt() % 16]
    }

    /** "STRAIGHT AHEAD", "TURN RIGHT 38°", "TURN LEFT 120°", for an angle relative to the way you face. */
    fun turn(relative: Double): String {
        val r = angleDiff(relative, 0.0).roundToInt()
        return when {
            r in -10..10 -> "STRAIGHT AHEAD"
            r > 0 -> "TURN RIGHT $r°"
            else -> "TURN LEFT ${-r}°"
        }
    }

    /** The smallest signed difference a - b, in degrees (-180..180). */
    fun angleDiff(a: Double, b: Double): Double = ((a - b) % 360 + 540) % 360 - 180
}

/** Times, in one place. 24-hour, UK style. */
object Clock {
    private val CLOCK = DateTimeFormatter.ofPattern("HH:mm", Locale.UK)
    private val DAY = DateTimeFormatter.ofPattern("EEE HH:mm", Locale.UK)
    private val DATE = DateTimeFormatter.ofPattern("d MMM", Locale.UK)

    /** "14:20" today, "TUE 14:20" this week, "26 OCT" before that. */
    fun pinned(millis: Long, now: Long = System.currentTimeMillis()): String {
        val zone = ZoneId.systemDefault()
        val at = Instant.ofEpochMilli(millis).atZone(zone)
        val days = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toEpochDay() - at.toLocalDate().toEpochDay()
        return when {
            days <= 0L -> CLOCK.format(at)
            days < 7L -> DAY.format(at).uppercase(Locale.UK)
            else -> DATE.format(at).uppercase(Locale.UK)
        }
    }

    fun clock(millis: Long): String = CLOCK.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))
}
