package com.hunterr.guvenlikkamera

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/**
 * Ana ekran widget'ı: kameranın açık/kapalı durumu, adresi, kaç kişinin izlediği ve
 * tek dokunuşla Başlat/Durdur.
 *
 * Başlatma, Android'in kamera kuralları gereği uygulama ekranı üzerinden yapılır
 * (ekran bir an açılır, izin gerekiyorsa sorar). Durdurma doğrudan yapılır.
 */
class KameraWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        try {
            val v = views(context)
            for (id in appWidgetIds) appWidgetManager.updateAppWidget(id, v)
        } catch (t: Throwable) {
            CrashLog.record(context, t)
        }
    }

    companion object {
        fun updateAll(c: Context) {
            try {
                val mgr = AppWidgetManager.getInstance(c) ?: return
                val ids = mgr.getAppWidgetIds(ComponentName(c, KameraWidget::class.java))
                if (ids != null && ids.isNotEmpty()) mgr.updateAppWidget(ids, views(c))
            } catch (t: Throwable) {
                CrashLog.record(c, t)
            }
        }

        private fun views(c: Context): RemoteViews {
            val v = RemoteViews(c.packageName, R.layout.widget_kamera)
            val on = CameraService.running
            val watching = CameraService.viewers

            v.setTextViewText(
                R.id.w_status,
                when {
                    !on -> "○ KAPALI"
                    watching > 0 -> "● AÇIK • $watching izleyen"
                    else -> "● AÇIK"
                }
            )
            v.setTextColor(R.id.w_status, if (on) 0xFF3FB950.toInt() else 0xFF8B949E.toInt())
            v.setTextViewText(
                R.id.w_addr,
                if (on) "${NetUtil.ip()}:${CameraService.PORT}" else "Başlatmak için dokun"
            )
            val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            val locked = CameraService.appLockEnabled(c)
            v.setTextViewText(R.id.w_toggle, if (on) (if (locked) "Yönet (PIN)" else "Durdur") else "Başlat")
            val togglePi = if (on && locked) {
                // Uygulama kilidi açıkken durdurmak için PIN gerekir.
                PendingIntent.getActivity(
                    c, 23,
                    Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    flags
                )
            } else if (on) {
                PendingIntent.getBroadcast(c, 20, Intent(c, StopReceiver::class.java), flags)
            } else {
                val start = Intent(c, MainActivity::class.java)
                    .setAction(MainActivity.ACTION_START)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                PendingIntent.getActivity(c, 21, start, flags)
            }
            v.setOnClickPendingIntent(R.id.w_toggle, togglePi)

            val open = Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            v.setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(c, 22, open, flags))
            return v
        }
    }
}
