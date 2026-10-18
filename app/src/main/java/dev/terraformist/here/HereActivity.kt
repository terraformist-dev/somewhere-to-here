package dev.terraformist.here

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher

/**
 * The app: one screen. The compass at the top follows the pin you tapped; the three pins sit
 * under it; under them, the note box for the tapped pin (its name, its note, and its buttons).
 */
class HereActivity : Activity() {

    private lateinit var screen: HereView
    private lateinit var tiles: List<PinTileView>
    private lateinit var panelTitle: TextView
    private lateinit var panelWhen: TextView
    private lateinit var nameInput: EditText
    private lateinit var noteInput: EditText
    private lateinit var buttonMain: TextView
    private lateinit var buttonSecond: TextView

    private lateinit var heading: Heading
    private var watcher: LocationListener? = null
    private var askedThisTime = false
    private var pinNowWhenAllowed = false
    private val main = Handler(Looper.getMainLooper())

    /** The pin whose note box is open (and the compass follows). */
    private var selected = 1

    /** Pinning: which pin, the best position so far, and the listener feeding it. */
    private var pinning: Int? = null
    private var best: Location? = null
    private var pinWatcher: LocationListener? = null

    /** Just found this pin: the screen shows the landing until Keep or Clear. */
    private var found: Int? = null

    /** True while the boxes are being filled in by code, so it isn't saved back. */
    private var filling = false

    /** Redraw five times a second: the compass and the NEAR ring move. */
    private val tick = object : Runnable {
        override fun run() {
            show()
            main.postDelayed(this, 200L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        screen = findViewById(R.id.screen)
        tiles = listOf(findViewById(R.id.pin1), findViewById(R.id.pin2), findViewById(R.id.pin3))
        panelTitle = findViewById(R.id.panel_title)
        panelWhen = findViewById(R.id.panel_when)
        nameInput = findViewById(R.id.name_input)
        noteInput = findViewById(R.id.note_input)
        buttonMain = findViewById(R.id.button_main)
        buttonSecond = findViewById(R.id.button_second)

        val regular = Art.typeface(this, isBold = false)
        val bold = Art.typeface(this, isBold = true)
        for (t in listOf(panelTitle, panelWhen, nameInput, noteInput)) t.typeface = regular
        buttonMain.typeface = bold
        buttonSecond.typeface = bold

        heading = Heading(this, Facing.SCREEN) {
            screen.heading = it
            screen.invalidate()
        }

        selected = Pins.selected(this)
        for ((i, tile) in tiles.withIndex()) tile.setOnClickListener { choose(i + 1) }
        nameInput.addTextChangedListener(saver { Pins.setName(this, selected, it) })
        noteInput.addTextChangedListener(saver { Pins.setNote(this, selected, it) })
        noteInput.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) {
                hideKeyboard()
                true
            } else {
                false
            }
        }
        buttonMain.setOnClickListener { onMain() }
        buttonSecond.setOnClickListener { onSecond() }
        setUpAbout()
        fillBoxes()
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    /** From the widget with no pins: go straight into pinning the first free pin. */
    private fun handle(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_PIN_NOW, false) != true) return
        intent.removeExtra(EXTRA_PIN_NOW)
        val free = Pins.firstEmpty(this) ?: return
        choose(free)
        if (Where.hasLocation(this)) startPinning(free) else pinNowWhenAllowed = true
    }

    private fun saver(save: (String) -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) {
            if (filling) return
            save(s?.toString() ?: "")
            show()
        }
    }

    // ---------- Choosing a pin ----------

    private fun choose(number: Int) {
        if (number == selected) return
        if (pinning != null && pinning != number) stopPinning()
        if (found != null) found = null
        selected = number
        Pins.select(this, number)
        fillBoxes()
        show()
        HereWidget.refreshAll(this)
    }

    /** Put the chosen pin's name and note in the boxes. */
    private fun fillBoxes() {
        val pin = Pins.get(this, selected)
        filling = true
        nameInput.setText(pin.name)
        noteInput.setText(pin.note)
        filling = false
        nameInput.hint = "NAME IT, E.G. ${listOf("CAR", "TENT", "MEET HERE")[selected - 1]}"
    }

    // ---------- The buttons ----------

    private enum class Action { ALLOW, PIN, PIN_ANYWAY, CANCEL, MOVE, CLEAR, CLEAR_NOW, FOUND, KEEP, NONE }

    private var mainAction = Action.NONE
    private var secondAction = Action.NONE

    private fun onMain() = act(mainAction)
    private fun onSecond() = act(secondAction)

    private fun act(action: Action) {
        when (action) {
            Action.ALLOW -> askOrOpenSettings()
            Action.PIN, Action.MOVE -> startPinning(selected)
            Action.PIN_ANYWAY -> best?.let { finishPinning(it) }
            Action.CANCEL -> stopPinning()
            Action.CLEAR -> confirmClear()
            Action.CLEAR_NOW -> clear()
            Action.FOUND -> {
                found = selected
                screen.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            }
            Action.KEEP -> found = null
            Action.NONE -> {}
        }
        show()
    }

    private fun confirmClear() {
        val pin = Pins.get(this, selected)
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Clear ${pin.label}?")
            .setMessage("Its spot, name and note will be gone.")
            .setPositiveButton("Clear") { _, _ ->
                clear()
                show()
            }
            .setNegativeButton("Keep", null)
            .show()
    }

    private fun clear() {
        Pins.clear(this, selected)
        found = null
        fillBoxes()
        HereWidget.refreshAll(this)
    }

    // ---------- Pinning ----------

    /** Watch positions closely until one is sure to [GOOD_METERS], then save it. */
    private fun startPinning(number: Int) {
        if (!Where.hasLocation(this)) {
            askOrOpenSettings()
            return
        }
        stopPinning()
        found = null
        pinning = number
        best = null
        // Something to show straight away, if the phone has a recent position.
        Where.last(this)?.takeIf { System.currentTimeMillis() - it.time < 20_000L }?.let { offerFix(it) }
        pinWatcher = Where.watch(this, everyMs = 1_000L, everyFix = true) { offerFix(it) }
        show()
    }

    private fun offerFix(location: Location) {
        if (pinning == null) return
        val age = System.currentTimeMillis() - (location.time.takeIf { it > 0 } ?: System.currentTimeMillis())
        if (age > 20_000L) return
        val acc = if (location.hasAccuracy()) location.accuracy else 99f
        val b = best
        if (b == null || acc <= b.accuracy) best = location
        if (acc <= GOOD_METERS) {
            finishPinning(location)
        } else {
            show()
        }
    }

    private fun finishPinning(location: Location) {
        val number = pinning ?: return
        Pins.place(this, number, location)
        Where.offer(this, location)
        stopPinning()
        screen.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        HereWidget.refreshAll(this)
        show()
    }

    private fun stopPinning() {
        Where.stop(this, pinWatcher)
        pinWatcher = null
        pinning = null
        best = null
    }

    // ---------- Showing ----------

    private fun show() {
        val s = Now.snap(this, selected)
        screen.snap = s
        screen.compassUnsure = heading.needsCalibrating
        val p = pinning
        screen.mode = when {
            p != null -> HereView.Mode.Pinning(p, best?.accuracy, (best?.accuracy ?: 99f) <= GOOD_METERS)
            found != null -> HereView.Mode.Found(found!!)
            else -> HereView.Mode.Normal
        }
        screen.invalidate()

        for ((i, tile) in tiles.withIndex()) {
            val pin = s.pins[i]
            tile.pin = pin
            tile.chosen = pin.number == selected
            tile.detail = when {
                pinning == pin.number -> "PINNING"
                !pin.placed -> "EMPTY"
                else -> s.placed.firstOrNull { it.pin.number == pin.number }?.reading?.let {
                    if (it.near) "NEAR" else Geo.formatShort(it.meters)
                } ?: "PINNED"
            }
            tile.contentDescription = "${pin.label}, ${tile.detail}" + if (tile.chosen) ", selected" else ""
            tile.invalidate()
        }

        val pin = s.pins[selected - 1]
        setIfChanged(panelTitle, "PIN $selected · NAME AND NOTE")
        setIfChanged(panelWhen, if (pin.placed) "PINNED ${Clock.pinned(pin.at, s.now)}" else "")

        val (a, b) = when {
            s.scene == Scene.NO_PERMISSION -> Action.ALLOW to Action.NONE
            pinning == selected -> (if (best != null) Action.PIN_ANYWAY else Action.NONE) to Action.CANCEL
            found == selected -> Action.CLEAR_NOW to Action.KEEP
            !pin.placed -> Action.PIN to Action.NONE
            s.scene == Scene.NEAR -> Action.FOUND to Action.MOVE
            else -> Action.MOVE to Action.CLEAR
        }
        mainAction = a
        secondAction = b
        style(buttonMain, a)
        style(buttonSecond, b)
    }

    private fun setIfChanged(view: TextView, text: String) {
        if (view.text.toString() != text) view.text = text
    }

    private fun style(button: TextView, action: Action) {
        val text = when (action) {
            Action.ALLOW -> "ALLOW LOCATION"
            Action.PIN -> "PIN THIS SPOT"
            Action.PIN_ANYWAY -> "PIN ANYWAY · ${best?.accuracy?.toInt() ?: "?"} M"
            Action.CANCEL -> "CANCEL"
            Action.MOVE -> "MOVE HERE"
            Action.CLEAR -> "CLEAR"
            Action.CLEAR_NOW -> "CLEAR PIN"
            Action.FOUND -> "FOUND IT"
            Action.KEEP -> "KEEP IT"
            Action.NONE -> if (button === buttonMain && pinning != null) "WAITING FOR A FIX" else ""
        }
        setIfChanged(button, text)
        // The second button only shows when it has something to do.
        if (button === buttonSecond) button.visibility = if (action == Action.NONE) View.GONE else View.VISIBLE
        val white = action in setOf(Action.ALLOW, Action.PIN, Action.CLEAR_NOW, Action.FOUND)
        button.setBackgroundResource(if (white) R.drawable.button_pill else R.drawable.button_outline)
        button.setTextColor(
            when {
                white -> Art.BLACK
                action == Action.NONE -> Art.FAINT
                else -> Art.WHITE
            },
        )
        button.isEnabled = action != Action.NONE
    }

    // ---------- Life cycle ----------

    override fun onResume() {
        super.onResume()
        if (!Where.hasLocation(this) && !askedThisTime) {
            askedThisTime = true
            requestPermissions(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                ASK_LOCATION,
            )
        }
        start()
        main.removeCallbacks(tick)
        main.post(tick)
    }

    override fun onPause() {
        super.onPause()
        main.removeCallbacks(tick)
        stopPinning()
        Where.stop(this, watcher)
        watcher = null
        heading.stop()
        // Leave the widget up to date.
        HereWidget.refreshAll(this)
    }

    private fun start() {
        Where.last(this)?.let { heading.setLocation(it) }
        if (!Where.hasLocation(this)) return
        if (watcher == null) {
            // Every couple of seconds: you're probably walking back to a pin.
            watcher = Where.watch(this, everyMs = 2_000L) { moved(it) }
        }
        Where.refresh(this) { fresh -> if (fresh != null) moved(fresh) }
        heading.start()
    }

    private fun moved(location: Location) {
        heading.setLocation(location)
        show()
    }

    private fun askOrOpenSettings() {
        if (shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION) || !askedThisTime) {
            askedThisTime = true
            requestPermissions(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                ASK_LOCATION,
            )
        } else {
            // They said no before, so Android won't ask again: open this app's settings instead.
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == ASK_LOCATION && grantResults.any { it == PackageManager.PERMISSION_GRANTED }) {
            start()
            HereWidget.refreshAll(this)
            if (pinNowWhenAllowed) {
                pinNowWhenAllowed = false
                startPinning(selected)
            }
        }
        show()
    }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(window.decorView.windowToken, 0)
        nameInput.clearFocus()
        noteInput.clearFocus()
    }

    // ---------- About card (tap the title) ----------

    private lateinit var aboutScrim: View
    private val closeAboutOnBack = OnBackInvokedCallback { hideAbout() }

    private fun setUpAbout() {
        aboutScrim = findViewById(R.id.about_scrim)
        val titleTap = findViewById<View>(R.id.title_tap)

        val regular = Art.typeface(this, isBold = false)
        for (id in intArrayOf(R.id.about_line1, R.id.about_line2, R.id.about_tips, R.id.about_signoff)) {
            findViewById<TextView>(id).typeface = regular
        }
        findViewById<TextView>(R.id.about_ok).typeface = Art.typeface(this, isBold = true)
        findViewById<DotTextView>(R.id.about_headline).text = getString(R.string.about_headline)

        // Keep the invisible tap area sitting exactly on the drawn title, whatever the screen size.
        screen.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val minHeight = 48f * resources.displayMetrics.density
            val width = (screen.titleWidth() + 32f * resources.displayMetrics.density).toInt()
            val params = titleTap.layoutParams as FrameLayout.LayoutParams
            val top = (screen.titleCenterY() - minHeight / 2f).toInt().coerceAtLeast(0)
            if (params.width != width || params.topMargin != top) {
                params.width = width
                params.height = minHeight.toInt()
                params.topMargin = top
                // Not during this layout pass: on the next frame.
                titleTap.post { titleTap.layoutParams = params }
            }
        }

        titleTap.setOnClickListener { showAbout() }
        aboutScrim.setOnClickListener { hideAbout() }
        findViewById<View>(R.id.about_ok).setOnClickListener { hideAbout() }
    }

    private fun showAbout() {
        if (aboutScrim.visibility == View.VISIBLE) return
        hideKeyboard()
        aboutScrim.alpha = 0f
        aboutScrim.visibility = View.VISIBLE
        aboutScrim.animate().alpha(1f).setDuration(160L).start()
        onBackInvokedDispatcher.registerOnBackInvokedCallback(
            OnBackInvokedDispatcher.PRIORITY_DEFAULT,
            closeAboutOnBack,
        )
        findViewById<View>(R.id.about_headline).sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_FOCUSED)
    }

    private fun hideAbout() {
        if (aboutScrim.visibility != View.VISIBLE) return
        onBackInvokedDispatcher.unregisterOnBackInvokedCallback(closeAboutOnBack)
        aboutScrim.animate().alpha(0f).setDuration(120L).withEndAction {
            aboutScrim.visibility = View.GONE
        }.start()
    }

    companion object {
        const val EXTRA_PIN_NOW = "pin_now"
        private const val ASK_LOCATION = 1

        /** A position this sure is saved straight away. */
        private const val GOOD_METERS = 10f
    }
}
