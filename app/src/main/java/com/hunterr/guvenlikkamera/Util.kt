package com.hunterr.guvenlikkamera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream
import java.io.File
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
    fun put(c: Context, values: Map<String, Any>) {
        val e = sp(c).edit()
        for ((k, v) in values) when (v) {
            is Int -> e.putInt(k, v)
            is Boolean -> e.putBoolean(k, v)
        }
        e.apply()
    }
}

/**
 * Uygulama her nerede çökerse çöksün, tam hata metnini SharedPreferences'a yazar.
 * MainActivity'deki "Son çökmeyi göster" düğmesi bunu okuyup ekranda gösterir —
 * böylece USB/logcat olmadan da hatayı görüp paylaşabiliriz.
 */
object CrashLog {
    private const val PREFS = "crash_log"
    private const val KEY = "last"

    fun install(c: Context) {
        val appCtx = c.applicationContext
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            record(appCtx, throwable, thread.name)
            prev?.uncaughtException(thread, throwable)
        }
    }

    /** Yakalanmış (uygulamayı çökertmeyen) hataları da aynı yere kaydetmek için. */
    fun record(c: Context, throwable: Throwable, threadName: String = Thread.currentThread().name) {
        try {
            val sw = java.io.StringWriter()
            throwable.printStackTrace(java.io.PrintWriter(sw))
            val text = "Zaman: ${stamp()}\nThread: $threadName\n\n$sw"
            c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, text).commit()
        } catch (_: Throwable) {
        }
    }

    fun read(c: Context): String? =
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)

    fun clear(c: Context) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }
}

object NetUtil {
    /** Telefonun Wi-Fi ağındaki IP adresi (önce wlan arayüzü). */
    fun ip(): String {
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
        return "IP bulunamadı (Wi-Fi açık mı?)"
    }
}

fun stamp(): String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

/** Klasörde en fazla maxFiles dosya ve maxBytes boyut kalacak şekilde en eskileri siler. */
fun pruneDir(dir: File, maxFiles: Int, maxBytes: Long, keep: File? = null) {
    val files = dir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() } ?: return
    var total = 0L
    files.forEachIndexed { i, f ->
        total += f.length()
        if (f != keep && (i >= maxFiles || total > maxBytes)) f.delete()
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
            for (c in 0 until w / 2) {
                out[pos++] = vBuf.get(r * vRow + c * vPix)
                out[pos++] = uBuf.get(r * uRow + c * uPix)
            }
        }
        return out
    }

    fun nv21ToJpeg(nv21: ByteArray, w: Int, h: Int, quality: Int): ByteArray {
        val bos = ByteArrayOutputStream()
        YuvImage(nv21, ImageFormat.NV21, w, h, null).compressToJpeg(Rect(0, 0, w, h), quality, bos)
        return bos.toByteArray()
    }

    fun rotateJpeg(jpeg: ByteArray, degrees: Int, quality: Int): ByteArray {
        if (degrees % 360 == 0) return jpeg
        val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return jpeg
        val m = Matrix().apply { postRotate(degrees.toFloat()) }
        val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        val bos = ByteArrayOutputStream()
        rotated.compress(Bitmap.CompressFormat.JPEG, quality, bos)
        if (rotated !== bmp) rotated.recycle()
        bmp.recycle()
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
        for (gy in 0 until gh) {
            val y = (gy * 2 + 1) * h / (gh * 2)
            for (gx in 0 until gw) {
                val x = (gx * 2 + 1) * w / (gw * 2)
                // 2x2 piksel ortalaması, gürültüyü azaltır
                val i = y * rowStride + x * pixStride
                val a = yBuf.get(i).toInt() and 0xFF
                val b = yBuf.get(i + pixStride).toInt() and 0xFF
                val c = yBuf.get(i + rowStride).toInt() and 0xFF
                val d = yBuf.get(i + rowStride + pixStride).toInt() and 0xFF
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
