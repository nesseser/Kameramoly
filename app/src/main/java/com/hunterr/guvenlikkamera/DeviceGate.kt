package com.hunterr.guvenlikkamera

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * Yeni cihaz onayı: PIN doğru olsa bile, daha önce onaylanmamış bir tarayıcı panele
 * giremez. Telefonda bildirim çıkar; sahibi uygulamayı (uygulama kilidi PIN'iyle) açıp
 * "İzin ver" demedikçe bağlantı kurulmaz.
 *
 * Onaylanan cihazlar telefonda saklanır ve uygulamadan tek tek kaldırılabilir.
 * Kaldırılan cihazın açık oturumları anında geçersiz olur.
 */
object DeviceGate {
    const val CHANNEL = "guvenlik"
    const val EXTRA_REQ = "req"
    private const val TTL_MS = 3 * 60 * 1000L
    private const val PREF_DEVICES = "devices"
    private val DEV_RE = Regex("^[a-f0-9]{32}$")

    class Request(val id: String, val dev: String, val name: String, val ip: String, val created: Long) {
        @Volatile var state = "pending"          // pending / approved / denied / expired
        @Volatile var claimed = false
        fun secondsLeft(): Long = ((created + TTL_MS - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
    }

    private val pending = ConcurrentHashMap<String, Request>()
    private val rng = SecureRandom()

    /** Uygulama ekranı açıksa yenilensin diye. */
    @Volatile var onChange: (() -> Unit)? = null

    fun enabled(c: Context) = Prefs.getBool(c, "device_gate", true)
    fun setEnabled(c: Context, v: Boolean) = Prefs.put(c, mapOf("device_gate" to v))

    fun validId(dev: String?) = dev != null && DEV_RE.matches(dev)
    fun newDeviceId(): String = hex(16)

    private fun hex(n: Int): String {
        val b = ByteArray(n)
        rng.nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }

    // ---------------- Onaylı cihazlar ----------------

    data class Device(val id: String, val name: String, val added: String)

    fun devices(c: Context): List<Device> =
        Prefs.getString(c, PREF_DEVICES, "").split("\n").filter { it.isNotBlank() }.mapNotNull {
            val p = it.split("|")
            if (p.size >= 3 && validId(p[0])) Device(p[0], p[1], p[2]) else null
        }

    fun isApproved(c: Context, dev: String?): Boolean =
        validId(dev) && devices(c).any { it.id == dev }

    @Synchronized
    fun approve(c: Context, dev: String, name: String) {
        if (!validId(dev)) return
        val list = devices(c).filter { it.id != dev } + Device(dev, clean(name), Clock.human())
        saveDevices(c, list.takeLast(30))
    }

    @Synchronized
    fun revoke(c: Context, dev: String) {
        saveDevices(c, devices(c).filter { it.id != dev })
        onChange?.invoke()
    }

    @Synchronized
    fun revokeAll(c: Context) {
        saveDevices(c, emptyList())
        onChange?.invoke()
    }

    private fun saveDevices(c: Context, list: List<Device>) {
        Prefs.put(c, mapOf(PREF_DEVICES to list.joinToString("\n") { "${it.id}|${it.name}|${it.added}" }))
    }

    fun clean(name: String): String =
        name.filter { !it.isISOControl() && it != '|' }.trim().take(40).ifEmpty { "Bilinmeyen cihaz" }

    // ---------------- Bekleyen istekler ----------------

    class TooManyRequests : Exception()

    @Synchronized
    fun requestApproval(c: Context, dev: String, name: String, ip: String): Request {
        cleanup()
        pending.values.firstOrNull { it.dev == dev && it.ip == ip && it.state == "pending" }?.let { return it }
        if (pending.values.count { it.state == "pending" } >= 5) throw TooManyRequests()
        val r = Request(hex(12), dev, clean(name), ip, System.currentTimeMillis())
        pending[r.id] = r
        showNotification(c, r)
        onChange?.invoke()
        return r
    }

    fun get(id: String?): Request? {
        if (id == null) return null
        cleanup()
        return pending[id]
    }

    fun pendingList(): List<Request> {
        cleanup()
        return pending.values.filter { it.state == "pending" }.sortedBy { it.created }
    }

    @Synchronized
    fun resolve(c: Context, id: String, allow: Boolean): Boolean {
        val r = pending[id] ?: return false
        if (r.state != "pending") return false
        if (r.secondsLeft() <= 0) {
            r.state = "expired"
            return false
        }
        r.state = if (allow) "approved" else "denied"
        if (allow) approve(c, r.dev, r.name)
        ConnLog.add(c, r.ip, if (allow) "Cihaz onaylandı: ${r.name}" else "Cihaz reddedildi: ${r.name}")
        cancelNotification(c, r)
        onChange?.invoke()
        return true
    }

    private fun cleanup() {
        val now = System.currentTimeMillis()
        for (r in pending.values) {
            if (r.state == "pending" && now - r.created > TTL_MS) r.state = "expired"
            if (now - r.created > 10 * TTL_MS) pending.remove(r.id)
        }
    }

    // ---------------- Bildirim ----------------

    private fun notifId(r: Request) = 2000 + (r.id.hashCode() and 0x3FF)

    private fun showNotification(c: Context, r: Request) {
        try {
            val nm = c.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, "Güvenlik uyarıları", NotificationManager.IMPORTANCE_HIGH)
                )
            }
            val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            // "İzin ver" her zaman uygulamayı açar: onay için uygulama kilidi PIN'i gerekir.
            val approveIntent = Intent(c, MainActivity::class.java)
                .setAction(MainActivity.ACTION_APPROVE)
                .putExtra(EXTRA_REQ, r.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val approvePi = PendingIntent.getActivity(c, notifId(r), approveIntent, flags)
            val denyPi = PendingIntent.getBroadcast(
                c, notifId(r) + 1,
                Intent(c, DenyReceiver::class.java).putExtra(EXTRA_REQ, r.id),
                flags
            )
            val n = NotificationCompat.Builder(c, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentTitle("Yeni cihaz bağlanmak istiyor")
                .setContentText("${r.name} • ${r.ip}")
                .setStyle(
                    NotificationCompat.BigTextStyle().bigText(
                        "${r.name}\nIP: ${r.ip}\n\nBu sen değilsen REDDET. Onay 3 dakika geçerli."
                    )
                )
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setContentIntent(approvePi)
                .addAction(0, "İzin ver", approvePi)
                .addAction(0, "Reddet", denyPi)
                .setAutoCancel(true)
                .setTimeoutAfter(TTL_MS)
                .build()
            nm.notify(notifId(r), n)
        } catch (t: Throwable) {
            CrashLog.record(c, t)
        }
    }

    private fun cancelNotification(c: Context, r: Request) {
        try {
            c.getSystemService(NotificationManager::class.java).cancel(notifId(r))
        } catch (_: Throwable) {
        }
    }
}

/** Bildirimdeki "Reddet" düğmesi (reddetmek her zaman güvenli olduğu için PIN istemez). */
class DenyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val id = intent?.getStringExtra(DeviceGate.EXTRA_REQ) ?: return
        DeviceGate.resolve(context, id, false)
    }
}
