package com.hunterr.guvenlikkamera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/** PIN ve kamera ayarları telefonda saklanır. */
object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("ayarlar", Context.MODE_PRIVATE)

    fun ensurePin(c: Context) {
        if (sp(c).getString("pin", null).isNullOrEmpty()) {
            sp(c).edit().putString("pin", "6509").apply()
        }
    }

    fun setPin(c: Context, pin: String) {
        sp(c).edit().putString("pin", pin).apply()
    }

    fun newPin(c: Context) {
        val pin = (100000 + SecureRandom().nextInt(900000)).toString()
        sp(c).edit().putString("pin", pin).apply()
    }

    fun pin(c: Context): String = sp(c).getString("pin", "") ?: ""

    fun getInt(c: Context, key: String, def: Int) = sp(c).getInt(key, def)
    fun getBool(c: Context, key: String, def: Boolean) = sp(c).getBoolean(key, def)
    fun getFloat(c: Context, key: String, def: Float) = sp(c).getFloat(key, def)
    fun getString(c: Context, key: String, def: String) = sp(c).getString(key, def) ?: def

    fun put(c: Context, values: Map<String, Any>) {
        val e = sp(c).edit()
        for ((k, v) in values) when (v) {
            is Int -> e.putInt(k, v)
            is Boolean -> e.putBoolean(k, v)
            is Float -> e.putFloat(k, v)
            is String -> e.putString(k, v)
        }
        e.apply()
    }
}

/**
 * Uygulama her nerede çökerse çöksün, tam hata metnini telefona yazar (son 8 kayıt).
 * MainActivity'deki "Hata kayıtları" düğmesi bunu ekranda gösterir —
 * böylece USB/logcat olmadan da hatayı görüp paylaşabiliriz.
 */
object CrashLog {
    private const val PREFS = "crash_log"
    private const val KEY = "entries"
    private const val SEP = "\n\n==============================\n\n"
    private const val MAX = 8

    fun install(c: Context) {
        val appCtx = c.applicationContext
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        if (prev is Handler) return  // zaten kurulu
        Thread.setDefaultUncaughtExceptionHandler(Handler(appCtx, prev))
    }

    private class Handler(
        val ctx: Context,
        val prev: Thread.UncaughtExceptionHandler?
    ) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(t: Thread, e: Throwable) {
            record(ctx, e, t.name, fatal = true)
            prev?.uncaughtException(t, e)
        }
    }

    /** Yakalanmış (uygulamayı çökertmeyen) hataları da kaydeder. */
    @Synchronized
    fun record(c: Context, throwable: Throwable, threadName: String = Thread.currentThread().name, fatal: Boolean = false) {
        try {
            val sw = java.io.StringWriter()
            throwable.printStackTrace(java.io.PrintWriter(sw))
            val kind = if (fatal) "ÇÖKME" else "Hata (uygulama çalışmaya devam etti)"
            val text = "$kind\nZaman: ${Clock.human()}\nThread: $threadName\n\n$sw"
            val sp = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val old = sp.getString(KEY, "") ?: ""
            val list = (listOf(text) + old.split(SEP).filter { it.isNotBlank() }).take(MAX)
            sp.edit().putString(KEY, list.joinToString(SEP)).commit()
        } catch (_: Throwable) {
        }
    }

    fun read(c: Context): String? =
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)?.takeIf { it.isNotBlank() }

    fun clear(c: Context) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }
}

/**
 * Bağlantı geçmişi: kim, ne zaman panele girdi; hatalı PIN denemeleri ve kilitlenmeler.
 * En fazla son 100 olay saklanır. Panelden ("/history") ve uygulamadan görülebilir.
 */
object ConnLog {
    private const val PREFS = "conn_log"
    private const val KEY = "events"
    private const val MAX = 100

    const val LOGIN = "Giriş yaptı"
    const val BAD_PIN = "Hatalı PIN"
    const val LOCKED = "Kilitlendi (çok fazla deneme)"
    const val LOGOUT = "Çıkış yaptı"

    @Synchronized
    fun add(c: Context, ip: String, event: String) {
        try {
            val sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val cur = sp.getString(KEY, "") ?: ""
            val line = "${Clock.human()}|${ip.replace("|", "")}|${event.replace("|", "")}"
            val lines = (cur.split("\n").filter { it.isNotBlank() } + line).takeLast(MAX)
            sp.edit().putString(KEY, lines.joinToString("\n")).apply()
        } catch (_: Throwable) {
        }
    }

    /** En yeni olay başta olacak şekilde liste. */
    fun entries(c: Context): List<Triple<String, String, String>> {
        val cur = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "") ?: ""
        return cur.split("\n").filter { it.isNotBlank() }.mapNotNull {
            val p = it.split("|")
            if (p.size >= 3) Triple(p[0], p[1], p[2]) else null
        }.asReversed()
    }

    fun readJson(c: Context): String {
        return try {
            entries(c).joinToString(",", "[", "]") { (time, ip, ev) ->
                "{\"zaman\":${Json.str(time)},\"ip\":${Json.str(ip)},\"olay\":${Json.str(ev)}}"
            }
        } catch (_: Throwable) {
            "[]"
        }
    }

    fun clear(c: Context) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }
}

/** Küçük, bağımlılıksız JSON yardımcıları (Türkçe karakterler ve virgüllü metinler güvenli). */
object Json {
    /** Metni JSON string değerine çevirir (tırnaklar dahil). */
    fun str(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (ch in s) {
            when {
                ch == '"' -> sb.append("\\\"")
                ch == '\\' -> sb.append("\\\\")
                ch == '\n' -> sb.append("\\n")
                ch == '\r' -> sb.append("\\r")
                ch == '\t' -> sb.append("\\t")
                ch < ' ' -> sb.append(String.format(Locale.US, "\\u%04x", ch.code))
                else -> sb.append(ch)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    /**
     * Düz bir JSON nesnesinden anahtarın değerini okur. Metin değerleri kaçış karakterleri
     * çözülmüş olarak döner; sayı/true/false ham metin olarak döner. Anahtar yoksa null.
     */
    fun get(body: String, key: String): String? {
        val needle = "\"" + key + "\""
        var from = 0
        while (true) {
            val k = body.indexOf(needle, from)
            if (k < 0) return null
            var i = k + needle.length
            while (i < body.length && body[i].isWhitespace()) i++
            if (i >= body.length || body[i] != ':') { from = k + 1; continue }
            i++
            while (i < body.length && body[i].isWhitespace()) i++
            if (i >= body.length) return null
            if (body[i] == '"') {
                val sb = StringBuilder()
                i++
                while (i < body.length) {
                    val ch = body[i]
                    if (ch == '"') return sb.toString()
                    if (ch == '\\' && i + 1 < body.length) {
                        val n = body[i + 1]
                        when (n) {
                            'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000C')
                            'u' -> {
                                if (i + 5 < body.length) {
                                    body.substring(i + 2, i + 6).toIntOrNull(16)?.let { sb.append(it.toChar()) }
                                    i += 4
                                }
                            }
                            else -> sb.append(n)
                        }
                        i += 2
                        continue
                    }
                    sb.append(ch)
                    i++
                }
                return sb.toString()
            }
            val start = i
            while (i < body.length && body[i] != ',' && body[i] != '}' && body[i] != ']') i++
            return body.substring(start, i).trim()
        }
    }
}

/** Basit JSON nesne oluşturucu. Sayılar yerel ayardan bağımsız yazılır (Türkçe "1,5" sorunu olmaz). */
class JObj {
    private val sb = StringBuilder("{")
    private var first = true

    fun put(key: String, value: Any?): JObj {
        if (!first) sb.append(',')
        first = false
        sb.append(Json.str(key)).append(':')
        when (value) {
            null -> sb.append("null")
            is String -> sb.append(Json.str(value))
            is Boolean, is Int, is Long -> sb.append(value.toString())
            is Float -> sb.append(if (value.isFinite()) value.toString() else "0")
            is Double -> sb.append(if (value.isFinite()) value.toString() else "0")
            is JObj -> sb.append(value.toString())
            else -> sb.append(Json.str(value.toString()))
        }
        return this
    }

    /** Değeri zaten geçerli JSON olan (ör. dizi) bir alan ekler. */
    fun putRaw(key: String, rawJson: String): JObj {
        if (!first) sb.append(',')
        first = false
        sb.append(Json.str(key)).append(':').append(rawJson)
        return this
    }

    override fun toString(): String = "$sb}"
}

object Clock {
    fun human(): String = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale("tr", "TR")).format(Date())
    fun short(): String = SimpleDateFormat("HH:mm:ss", Locale("tr", "TR")).format(Date())
}

object NetUtil {
    /** Telefonun Wi-Fi ağındaki IP adresi (önce wlan arayüzü). Bulamazsa null. */
    fun ipOrNull(): String? {
        try {
            val candidates = mutableListOf<Pair<String, String>>()
            for (ni in Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp || ni.isLoopback) continue
                for (a in Collections.list(ni.inetAddresses)) {
                    if (a is Inet4Address && a.isSiteLocalAddress) {
                        candidates += ni.name to (a.hostAddress ?: continue)
                    }
                }
            }
            candidates.firstOrNull { it.first.startsWith("wlan") }?.let { return it.second }
            candidates.firstOrNull()?.let { return it.second }
        } catch (_: Exception) {
        }
        return null
    }

    fun ip(): String = ipOrNull() ?: "IP bulunamadı (Wi-Fi açık mı?)"

    /**
     * Bağlanan adres ev ağından mı? (192.168/10/172.16-31, 169.254 bağlantı-yerel,
     * IPv6 bağlantı-yerel fe80::/10 ve yerel fc00::/7, telefonun kendisi.)
     * İnternetten gelen her adres için false döner.
     */
    fun isLocalNetwork(a: java.net.InetAddress): Boolean {
        if (a.isLoopbackAddress || a.isSiteLocalAddress || a.isLinkLocalAddress) return true
        if (a is java.net.Inet6Address) {
            val b = a.address
            if (b.isNotEmpty() && (b[0].toInt() and 0xFE) == 0xFC) return true
        }
        return false
    }

    /** Yerel ağ (192.168.x.x, 10.x.x.x, 172.16-31.x.x) adresi mi? */
    fun isPrivateIpv4(host: String): Boolean {
        val b = CertGen.ipv4Bytes(host) ?: return false
        val a0 = b[0].toInt() and 0xFF
        val a1 = b[1].toInt() and 0xFF
        return a0 == 10 || (a0 == 192 && a1 == 168) || (a0 == 172 && a1 in 16..31)
    }
}

object Qr {
    fun bitmap(text: String, size: Int): Bitmap {
        val hints = mapOf(
            EncodeHintType.MARGIN to 2,
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M
        )
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
        val w = m.width
        val h = m.height
        val px = IntArray(w * h)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) px[row + x] = if (m.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    fun png(text: String, size: Int): ByteArray {
        val bmp = bitmap(text, size)
        val bos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)
        bmp.recycle()
        return bos.toByteArray()
    }
}

object ImageUtils {
    /** CameraX YUV_420_888 karesini NV21 bayt dizisine çevirir. */
    fun toNv21(image: ImageProxy): ByteArray {
        val w = image.width
        val h = image.height
        val out = ByteArray(w * h * 3 / 2)

        val yPlane = image.planes[0]
        val yBuf = yPlane.buffer
        val yRow = yPlane.rowStride
        val yPix = yPlane.pixelStride
        var pos = 0
        if (yPix == 1) {
            for (r in 0 until h) {
                yBuf.position(r * yRow)
                yBuf.get(out, pos, w)
                pos += w
            }
            yBuf.rewind()
        } else {
            for (r in 0 until h) for (c in 0 until w) out[pos++] = yBuf.get(r * yRow + c * yPix)
        }

        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer
        val uRow = uPlane.rowStride
        val uPix = uPlane.pixelStride
        val vRow = vPlane.rowStride
        val vPix = vPlane.pixelStride
        for (r in 0 until h / 2) {
            val ur = r * uRow
            val vr = r * vRow
            for (c in 0 until w / 2) {
                out[pos++] = vBuf.get(vr + c * vPix)
                out[pos++] = uBuf.get(ur + c * uPix)
            }
        }
        return out
    }

    /**
     * NV21 görüntüyü saat yönünde 90/180/270 derece döndürür.
     * Eskiden JPEG'i açıp döndürüp yeniden sıkıştırıyorduk (iki kez kayıp + yavaş);
     * şimdi ham veride döndürüp TEK sefer sıkıştırıyoruz: daha net görüntü, daha az işlemci.
     */
    fun rotateNv21(src: ByteArray, w: Int, h: Int, degrees: Int): ByteArray {
        val deg = ((degrees % 360) + 360) % 360
        if (deg == 0) return src
        val frame = w * h
        val out = ByteArray(src.size)
        val cw = w / 2
        val ch = h / 2
        var i = 0
        when (deg) {
            90 -> {
                for (x in 0 until w) for (y in h - 1 downTo 0) out[i++] = src[y * w + x]
                for (x in 0 until cw) for (y in ch - 1 downTo 0) {
                    val p = frame + y * w + x * 2
                    out[i++] = src[p]; out[i++] = src[p + 1]
                }
            }
            180 -> {
                for (j in frame - 1 downTo 0) out[i++] = src[j]
                for (k in cw * ch - 1 downTo 0) {
                    val p = frame + k * 2
                    out[i++] = src[p]; out[i++] = src[p + 1]
                }
            }
            270 -> {
                for (x in w - 1 downTo 0) for (y in 0 until h) out[i++] = src[y * w + x]
                for (x in cw - 1 downTo 0) for (y in 0 until ch) {
                    val p = frame + y * w + x * 2
                    out[i++] = src[p]; out[i++] = src[p + 1]
                }
            }
            else -> return src
        }
        return out
    }

    fun nv21ToJpeg(nv21: ByteArray, w: Int, h: Int, quality: Int): ByteArray {
        val bos = ByteArrayOutputStream(w * h / 4)
        YuvImage(nv21, ImageFormat.NV21, w, h, null).compressToJpeg(Rect(0, 0, w, h), quality, bos)
        return bos.toByteArray()
    }
}

/**
 * Basit hareket algılama: görüntüyü 32x24 ızgarada örnekler, önceki örnekle karşılaştırır.
 * Işık açılıp kapanması gibi genel parlaklık değişimlerini ortalama farkı çıkararak yok sayar.
 */
class MotionDetector {
    private var prev: IntArray? = null
    var lastScore = 0f
        private set

    fun reset() {
        prev = null
        lastScore = 0f
    }

    fun check(yBuf: ByteBuffer, rowStride: Int, pixStride: Int, w: Int, h: Int, sensitivity: Int): Boolean {
        val gw = 32
        val gh = 24
        val cur = IntArray(gw * gh)
        val limit = yBuf.limit()
        for (gy in 0 until gh) {
            val y = (gy * 2 + 1) * h / (gh * 2)
            for (gx in 0 until gw) {
                val x = (gx * 2 + 1) * w / (gw * 2)
                // 2x2 piksel ortalaması, gürültüyü azaltır
                val i = y * rowStride + x * pixStride
                val j = i + rowStride + pixStride
                if (j >= limit) { cur[gy * gw + gx] = yBuf.get(minOf(i, limit - 1)).toInt() and 0xFF; continue }
                val a = yBuf.get(i).toInt() and 0xFF
                val b = yBuf.get(i + pixStride).toInt() and 0xFF
                val c = yBuf.get(i + rowStride).toInt() and 0xFF
                val d = yBuf.get(j).toInt() and 0xFF
                cur[gy * gw + gx] = (a + b + c + d) / 4
            }
        }
        val p = prev
        prev = cur
        if (p == null) return false

        var sum = 0
        for (i in cur.indices) sum += cur[i] - p[i]
        val mean = sum / cur.size
        var changed = 0
        for (i in cur.indices) if (abs(cur[i] - p[i] - mean) > 22) changed++
        lastScore = changed.toFloat() / cur.size

        // hassasiyet 100 -> %0.5 hücre değişimi yeter, 1 -> %25
        val threshold = 0.005f + 0.245f * (100 - sensitivity.coerceIn(1, 100)) / 99f
        return lastScore > threshold
    }
}
