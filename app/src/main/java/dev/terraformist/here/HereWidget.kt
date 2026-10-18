package dev.terraformist.here

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.SizeF
import android.widget.RemoteViews
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The home-screen widget, in two sizes ([Small] 2×2 and [Wide] 4×2) that share everything.
 * It follows the pin last tapped in the app. North is up: a widget can't turn with the phone.
 *
 * It redraws every 5 minutes with whatever position the phone last had (from the app, the
 * Glyph Toy or other apps), using a timer that never wakes the phone. With no pins, tapping it
 * opens the app straight into pinning the spot.
 */
open class HereWidget : AppWidgetProvider() {

    class Small : HereWidget()
    class Wide : HereWidget()

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        refreshAll(context)
        val pending = goAsync()
        Where.refresh(context) {
            refreshAll(context)
            pending.finish()
        }
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        draw(context, manager, id, Now.snap(context))
    }

    override fun onDisabled(context: Context) {
        alarms(context).cancel(tickIntent(context))
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_TICK) refreshAll(context) else super.onReceive(context, intent)
    }

    companion object {

        private const val ACTION_TICK = "dev.terraformist.here.TICK"
        private const val MAX_SIDE_PX = 900f
        private const val EVERY_MS = 5 * 60_000L

        private fun ids(context: Context): List<Int> {
            val manager = AppWidgetManager.getInstance(context)
            return listOf(Small::class.java, Wide::class.java).flatMap {
                manager.getAppWidgetIds(ComponentName(context, it)).toList()
            }
        }

        /** Redraw every copy of both widgets, then set the timer for the next redraw. */
        fun refreshAll(context: Context) {
            val ids = ids(context)
            if (ids.isEmpty()) return
            val manager = AppWidgetManager.getInstance(context)
            val s = Now.snap(context)
            for (id in ids) draw(context, manager, id, s)
            // RTC (not RTC_WAKEUP): never wakes the phone just to redraw.
            alarms(context).set(AlarmManager.RTC, s.now + EVERY_MS, tickIntent(context))
        }

        private fun draw(context: Context, manager: AppWidgetManager, id: Int, s: Snap) {
            val (widthDp, heightDp) = sizeDp(manager.getAppWidgetOptions(id))
            val density = context.resources.displayMetrics.density
            val scale = minOf(density, MAX_SIDE_PX / max(widthDp, heightDp))
            val picture = Art.widget(context, (widthDp * scale).roundToInt(), (heightDp * scale).roundToInt(), s)

            val views = RemoteViews(context.packageName, R.layout.widget)
            views.setImageViewBitmap(R.id.art, picture)
            views.setContentDescription(R.id.art, Art.describe(s))
            val pinNow = s.scene == Scene.NO_PINS || s.scene == Scene.EMPTY
            views.setOnClickPendingIntent(R.id.art, openApp(context, pinNow))
            manager.updateAppWidget(id, views)
        }

        private fun alarms(context: Context) = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        private fun tickIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context,
            2,
            Intent(context, Small::class.java).setAction(ACTION_TICK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        /** The widget's size on screen, in dp, as the home screen reports it (portrait). */
        private fun sizeDp(options: Bundle): Pair<Float, Float> {
            val sizes = options.getParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, SizeF::class.java)
            val first = sizes?.firstOrNull()
            if (first != null && first.width > 0 && first.height > 0) return first.width to first.height
            val w = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 170)
            val h = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 170)
            return w.coerceAtLeast(40).toFloat() to h.coerceAtLeast(40).toFloat()
        }

        private fun openApp(context: Context, pinNow: Boolean): PendingIntent = PendingIntent.getActivity(
            context,
            if (pinNow) 1 else 0,
            Intent(context, HereActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(HereActivity.EXTRA_PIN_NOW, pinNow),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
