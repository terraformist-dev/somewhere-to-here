# Somewhere to Here: notes for Claude

A compass back to up to three saved pins (parked car, festival tent, meeting spot). Three places:
a home-screen widget (2×2 and 4×2), the app (one screen), and a Glyph Toy on the Phone (3)'s Glyph
Matrix. Fourth in the Somewhere family: `terraformist-dev/somewhere-to-nothing`,
`somewhere-to-my-someone` (NEAR logic, accuracy-aware `Where`, `Heading` with the "compass unsure"
flag came from here) and `somewhere-up-there` (build setup, `Art`, widget, toy service).

Owner has no coding experience and works only from a phone. Explain changes in plain English,
never ask them to run commands, and always finish by pushing to `main` and confirming the
GitHub Actions build went green (the build publishes `somewhere-to-here.apk` as the latest release).

## Project

Android app (Kotlin, no AndroidX, no Compose, no Google libraries), package `dev.terraformist.here`,
minSdk 34. Builds only in GitHub Actions (`.github/workflows/build.yml`); the sandbox cannot reach
Google's Maven or Gradle servers. Signed with `app/here.keystore` (password `here1234`, alias
`here`, committed on purpose) so updates install over the old app. Don't change the key or
applicationId. No INTERNET permission on purpose: pins never leave the phone.

Local type-check (catches most errors before a cloud build): Kotlin 2.0.21 compiler from
JetBrains' GitHub releases, an `android.jar` (API 35) from github.com/Reginer/aosp-android-jar,
`classes.jar` unzipped from the Glyph .aar, and a stub `R` object; compile all `*.kt` with
`-Xnullability-annotations=@android.annotation:ignore` (that jar marks `findViewById` nullable,
the real SDK doesn't). Pure parts (`Geo`, `Pins` data, `MatrixArt`, `Dots`) can be run with a
small `main` (build a `Snap` with `me = null`) to print the Glyph Matrix as text.

## Agreed with the owner (mockup canvas, two rounds)

- **Three pins, not categories.** Pin 1, 2, 3. Each has a **name** (shown instead of "PIN 1"
  everywhere once set) and a **note**, edited in the note box under the pin row. The owner asked
  that the three shapes be obviously different and not real objects: `Mark` in `Pins.kt`:
  SOLID (round head), SQUARE (hollow), TRIANGLE (hollow), each with a two-dot tail.
  Screen size 5×7 (`big`); Glyph Matrix 3×4 (`small`, "a third smaller" so they don't crowd).
- **Glyph Matrix shows the pin shapes, not numbers.**
- Numbers are dots (`DotFont.LARGE` 5×7, `DotFont.SMALL` 3×5). **Owner's rules: a decimal point is
  ONE dot, the same dot as the digits (the owner rejected a five-dot cross and a mismatched dot);
  a colon is two single dots; a comma is three dots swooping down-left.** Never swap in a font.
- Nothing look: black, `#1A1A1A` widget cards, white dots, ghost dots, red `#D71921` only for pins.
  On the compass ring the followed pin is red, other pins grey. Labels in Space Mono.

## Behaviour

- `Pins.kt`: SharedPreferences `pins`, keys `p{n}_placed/_lat/_lon/_acc/_at/_name/_note`, plus
  `selected` (the last-tapped pin; the widget follows it, or the first placed one).
  Clearing a pin forgets spot, name and note.
- **Pinning** (`HereActivity.startPinning`): watches every fix at 1 s; saves itself at ≤ 10 m
  (`GOOD_METERS`); PIN ANYWAY saves the best so far; CANCEL keeps the old spot. Leaving the app
  cancels. MOVE HERE re-pins a placed pin the same way. Widget with no pins → opens the app with
  `EXTRA_PIN_NOW` → pins the first empty pin.
- **NEAR** (`Reading.near`): distance < max(2 × hypot(your accuracy, pin accuracy), 25 m). No
  arrow; the pin sits in the middle of a breathing dotted circle; "WITHIN N M". Then FOUND IT →
  the landed-pin dial with CLEAR PIN / KEEP IT.
- **App** (`HereActivity`, `HereView` drawn by hand, `PinTileView` × 3, note box with real
  `EditText`s, `adjustResize` so the compass shrinks for the keyboard). Redraw every 200 ms;
  location every 2 s while open. Title tap → About card ("Pin it.").
- **Widget** (`HereWidget`): north-up; redraws every 5 min on a non-waking RTC alarm using the last
  saved position (no background location: the owner had that prompt removed from Up There). Says
  "AS OF hh:mm" when the position is over 15 min old.
- **Glyph Toy** (`HereToyService`, `MatrixArt`): starts on the MAP (you = blinking middle dot,
  pins by square-root distance, heading-up, nudged apart if they'd overlap). Long press cycles
  map → each placed pin → map; landing on a pin shows shape + distance for 3 s, then the pin on a
  log scale (edge ≥ 2 km, close 30 m) with a needle; NEAR = shape in a breathing ring.
  Live for 60 s after a touch or while the screen is on; location every 5 s while live.
- **Glyph switch-on** (`MatrixArt.intro`, 1.15 s, once each time the toy is picked, from
  `onServiceConnected`): a big solid pin drops in from the top (speeding up like gravity), lands in
  the middle with a one-dot bounce, and a flat ripple spreads out from where its tail touched down;
  then it fades into the normal frame. The owner wants each Somewhere toy to have its own switch-on
  so he can tell them apart while cycling: Nothing dissolves to a dot that shoots to the edge,
  Someone has two dots meeting in a beating heart, Up There has a dot arcing over a horizon.
  Keep this one different from those.

## Glyph Matrix SDK 2.0 (same .aar as the sibling apps)

`GlyphMatrixManager.getInstance(ctx)`, `init(Callback)`, `register(Glyph.DEVICE_23112)`,
`setMatrixFrame(IntArray)` (625 ints, 0..4095), `turnOff()`, `unInit()`. Row indents
`9,7,5,4,3,2,2,1,1,0,0,0,0,0,0,0,1,1,2,2,3,4,5,7,9` (`GlyphShape`).

## Images

Launcher icon (the landed pin), widget previews and Glyph Toy preview (the map) are PNGs in
`res/drawable-nodpi/`, drawn with PIL to match `Art.kt`.
