package dev.terraformist.here

import android.content.Context
import android.location.Location

/**
 * One of the three pins. An empty pin still keeps its name and note, so you can write them
 * before (or after) pinning the spot.
 */
data class Pin(
    /** 1, 2 or 3. */
    val number: Int,
    val placed: Boolean,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    /** How sure the phone was of the spot when it was pinned, in metres. */
    val accuracy: Float = 0f,
    /** When it was pinned. */
    val at: Long = 0L,
    val name: String = "",
    val note: String = "",
) {
    /** The pin's name if it has one, else "PIN 1". Always upper case, like everything on screen. */
    val label: String get() = name.trim().ifEmpty { "PIN $number" }.uppercase()

    val hasName: Boolean get() = name.isNotBlank()

    /** The note in capitals, or empty. */
    val noteText: String get() = note.trim().uppercase()

    val mark: Mark get() = Mark.of(number)
}

/** Saved pins. Small and local: nothing leaves the phone. */
object Pins {

    const val COUNT = 3
    const val NAME_MAX = 16
    const val NOTE_MAX = 48

    private const val PREFS = "pins"
    private const val KEY_SELECTED = "selected"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun get(context: Context, number: Int): Pin {
        val p = prefs(context)
        val k = "p$number"
        return Pin(
            number = number,
            placed = p.getBoolean("${k}_placed", false),
            latitude = Double.fromBits(p.getLong("${k}_lat", 0L)),
            longitude = Double.fromBits(p.getLong("${k}_lon", 0L)),
            accuracy = p.getFloat("${k}_acc", 0f),
            at = p.getLong("${k}_at", 0L),
            name = p.getString("${k}_name", "") ?: "",
            note = p.getString("${k}_note", "") ?: "",
        )
    }

    fun all(context: Context): List<Pin> = (1..COUNT).map { get(context, it) }

    fun placed(context: Context): List<Pin> = all(context).filter { it.placed }

    /** Save the spot for pin [number], keeping its name and note. */
    fun place(context: Context, number: Int, location: Location) {
        val k = "p$number"
        prefs(context).edit()
            .putBoolean("${k}_placed", true)
            .putLong("${k}_lat", location.latitude.toBits())
            .putLong("${k}_lon", location.longitude.toBits())
            .putFloat("${k}_acc", if (location.hasAccuracy()) location.accuracy else 20f)
            .putLong("${k}_at", System.currentTimeMillis())
            .apply()
    }

    /** Forget the spot, the name and the note. */
    fun clear(context: Context, number: Int) {
        val k = "p$number"
        prefs(context).edit()
            .remove("${k}_placed").remove("${k}_lat").remove("${k}_lon").remove("${k}_acc")
            .remove("${k}_at").remove("${k}_name").remove("${k}_note")
            .apply()
    }

    fun setName(context: Context, number: Int, name: String) {
        prefs(context).edit().putString("p${number}_name", name.take(NAME_MAX)).apply()
    }

    fun setNote(context: Context, number: Int, note: String) {
        prefs(context).edit().putString("p${number}_note", note.take(NOTE_MAX)).apply()
    }

    /** The pin the app, widget and compass follow: the one last tapped. */
    fun selected(context: Context): Int = prefs(context).getInt(KEY_SELECTED, 1).coerceIn(1, COUNT)

    fun select(context: Context, number: Int) {
        prefs(context).edit().putInt(KEY_SELECTED, number.coerceIn(1, COUNT)).apply()
    }

    /** The selected pin if it's placed, else the first placed one, else null. */
    fun followed(context: Context): Pin? {
        val chosen = get(context, selected(context))
        return if (chosen.placed) chosen else placed(context).firstOrNull()
    }

    /** The first empty pin, or null if all three are in use. */
    fun firstEmpty(context: Context): Int? = all(context).firstOrNull { !it.placed }?.number
}

/**
 * The three pin shapes, made so they can't be confused even tiny:
 * 1 is a solid round head, 2 a hollow square, 3 a hollow triangle. Each has the same two-dot tail.
 */
enum class Mark(
    /** For the screen: 5 wide, 7 tall. */
    val big: Sprite,
    /** For the Glyph Matrix, about a third smaller: 3 wide, 4 tall. */
    val small: Sprite,
) {
    SOLID(
        Sprite(".###.", "#####", "#####", "#####", ".###.", "..#..", "..#.."),
        Sprite("###", "###", "###", ".#."),
    ),
    SQUARE(
        Sprite("#####", "#...#", "#...#", "#...#", "#####", "..#..", "..#.."),
        Sprite("###", "#.#", "###", ".#."),
    ),
    TRIANGLE(
        Sprite("..#..", ".#.#.", ".#.#.", "#...#", "#####", "..#..", "..#.."),
        Sprite(".#.", "#.#", "###", ".#."),
    ),
    ;

    companion object {
        fun of(number: Int): Mark = entries[(number - 1).coerceIn(0, entries.size - 1)]
    }
}
