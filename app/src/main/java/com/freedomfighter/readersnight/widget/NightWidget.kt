package com.freedomfighter.readersnight.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.widget.RemoteViews
import com.freedomfighter.readersnight.Filter
import com.freedomfighter.readersnight.MainActivity
import com.freedomfighter.readersnight.R
import com.freedomfighter.readersnight.summary

/**
 * The home-screen widget: the state in words on the left, a switch on the right.
 * Rendering only reads the state; every change of state calls [refresh].
 */
object NightWidget {
    /** (background, foreground, dim) following the app's theme setting. */
    private fun colors(context: Context): Triple<Int, Int, Int> {
        val theme = context.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("theme", "DARK")
        val systemDark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val dark = when (theme) { "LIGHT" -> false; "SYSTEM" -> systemDark; else -> true }
        return if (dark) Triple(Color.BLACK, Color.WHITE, Color.argb(140, 255, 255, 255))
        else Triple(Color.WHITE, Color.BLACK, Color.argb(140, 0, 0, 0))
    }

    fun render(context: Context, mgr: AppWidgetManager, id: Int) {
        val on = Filter.isOn(context)
        val v = RemoteViews(context.packageName, R.layout.widget_night)
        val (bg, fg, dim) = colors(context)
        v.setInt(R.id.widget_root, "setBackgroundColor", bg)
        v.setTextColor(R.id.widget_title, fg)
        v.setTextColor(R.id.widget_sub, dim)
        v.setTextViewText(R.id.widget_title, context.getString(if (on) R.string.state_on else R.string.state_off))
        v.setTextViewText(R.id.widget_sub, when {
            !Filter.allowed(context) -> context.getString(R.string.setup_title)
            else -> summary(context, Filter.options(context))
        })
        // the switch: filled while on, a hairline frame when not
        v.setTextViewText(R.id.widget_switch, context.getString(if (on) R.string.w_on else R.string.w_off))
        if (on) v.setInt(R.id.widget_switch, "setBackgroundColor", fg)
        else v.setInt(R.id.widget_switch, "setBackgroundResource", if (fg == Color.WHITE) R.drawable.widget_frame_white else R.drawable.widget_frame_black)
        v.setTextColor(R.id.widget_switch, if (on) bg else fg)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val open = PendingIntent.getActivity(context, 1, Intent(context, MainActivity::class.java).setAction(Intent.ACTION_MAIN).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), flags)
        v.setOnClickPendingIntent(R.id.widget_body, open)
        // without the permission the switch can only lead to the setup page
        v.setOnClickPendingIntent(R.id.widget_switch, if (Filter.allowed(context)) PendingIntent.getBroadcast(context, 2, Intent(context, ToggleReceiver::class.java), flags) else open)
        mgr.updateAppWidget(id, v)
    }

    fun refresh(context: Context) {
        val ctx = context.applicationContext
        val mgr = AppWidgetManager.getInstance(ctx) ?: return
        mgr.getAppWidgetIds(ComponentName(ctx, NightWidgetProvider::class.java)).forEach { render(ctx, mgr, it) }
    }
}

class NightWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) = NightWidget.refresh(context)
}

class ToggleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Filter.toggle(context)
        NightWidget.refresh(context)
    }
}
