package com.hunterr.guvenlikkamera

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.LifecycleService
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Ön plan servisi: kamerayı ekran kapalıyken de çalıştırır, LAN üzerinde PIN korumalı
 * bir HTTP paneli sunar. Kamera bildirimi Android tarafından zorunlu tutulur (gizlenemez).
 */
class CameraService : LifecycleService() {

    companion object {
        const val PORT = 8080
        const val CHANNEL = "kamera"
        const val NOTIF_ID = 42
        @Volatile var running = false
            private set
    }

    // --- Kamera durumu ---
    @Volatile private var latestJpeg: ByteArray? = null
    @Volatile private var frameW = 0
    @Volatile private var frameH = 0
    @Volatile private var camera: Camera? = null
    private var provider: ProcessCameraProvider? = null
    private val main = Handler(Looper.getMainLooper())
    private val analysisExec = Executors.newSingleThreadExecutor()
    private val motion = MotionDetector()
    private var lastMotionMs = 0L

    // --- Ayarlar (çalışırken panelden değişebilir) ---
    @Volatile private var jpegQuality = 70
    @Volatile private var sensitivity = 60       // 1..100
    @Volatile private var useFront = false
    @Volatile private var torchOn = false

    // --- Sunucu ---
    private var server: ServerSocket? = null
    private val serverExec = Executors.newCachedThreadPool()
    private val stopping = AtomicBoolean(false)
    private var wakeLock: PowerManager.WakeLock? = null

    // --- Oturum / kaba kuvvet koruması ---
    private val tokens = ConcurrentHashMap<String, Long>()          // token -> son kullanım
    private val failCount = ConcurrentHashMap<String, Int>()        // ip -> yanlış deneme
    private val lockUntil = ConcurrentHashMap<String, Long>()       // ip -> kilit bitiş zamanı
    private val tokenTtl = 6 * 60 * 60 * 1000L

    override fun onCreate() {
        super.onCreate()
        sensitivity = Prefs.getInt(this, "sensitivity", 60)
        useFront = Prefs.getBool(this, "front", false)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (running) return START_STICKY
        running = true

        try {
            startForegroundNotif()
        } catch (t: Throwable) {
            // Bildirim/ön plan servisi başlatılamadıysa çökmek yerine hatayı kaydedip
            // düzgünce dur — "Son çökmeyi göster" ile görülebilsin.
            CrashLog.record(this, t)
            running = false
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            acquireWake()
        } catch (t: Throwable) {
            CrashLog.record(this, t)
        }
        try {
            startCamera()
        } catch (t: Throwable) {
            CrashLog.record(this, t)
        }
        try {
            startServer()
        } catch (t: Throwable) {
            CrashLog.record(this, t)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    // ---------------- Bildirim ----------------

    private fun startForegroundNotif() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Kamera", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val openPi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopPi = PendingIntent.getService(
            this, 1, Intent(this, StopReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n: Notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("Güvenlik kamerası çalışıyor")
            .setContentText("http://${NetUtil.ip()}:$PORT")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(openPi)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Durdur", stopPi)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun acquireWake() {
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "kamera:wl").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    // ---------------- Kamera ----------------

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                provider = future.get()
                bindCamera()
            } catch (t: Throwable) {
                CrashLog.record(this, t)
            }
        }, androidx.core.content.ContextCompat.getMainExecutor(this))
    }

    private fun bindCamera() {
        val p = provider ?: return
        p.unbindAll()
        motion.reset()

        val analysis = ImageAnalysis.Builder()
            .setTargetResolution(Size(1280, 720))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        analysis.setAnalyzer(analysisExec) { image ->
            try {
                val w = image.width
                val h = image.height
                val rot = image.imageInfo.rotationDegrees

                // Hareket algılama (Y düzlemi üzerinden)
                val y = image.planes[0]
                val moved = motion.check(
                    y.buffer, y.rowStride, y.pixelStride, w, h, sensitivity
                )
                y.buffer.rewind()

                // JPEG üret
                val nv21 = ImageUtils.toNv21(image)
                var jpeg = ImageUtils.nv21ToJpeg(nv21, w, h, jpegQuality)
                jpeg = ImageUtils.rotateJpeg(jpeg, rot, jpegQuality)
                latestJpeg = jpeg
                if (rot % 180 == 0) { frameW = w; frameH = h } else { frameW = h; frameH = w }

                if (moved) lastMotionMs = System.currentTimeMillis()
            } catch (t: Throwable) {
                CrashLog.record(this, t)
            } finally {
                image.close()
            }
        }

        val selector = if (useFront) CameraSelector.DEFAULT_FRONT_CAMERA
        else CameraSelector.DEFAULT_BACK_CAMERA

        try {
            camera = p.bindToLifecycle(this, selector, analysis)
            applyTorch()
        } catch (t: Throwable) {
            CrashLog.record(this, t)
        }
    }

    private fun applyTorch() {
        try {
            if (camera?.cameraInfo?.hasFlashUnit() == true) {
                camera?.cameraControl?.enableTorch(torchOn)
            }
        } catch (_: Exception) {
        }
    }

    // ---------------- HTTP sunucusu ----------------

    private fun startServer() {
        serverExec.execute {
            try {
                val s = ServerSocket(PORT)
                server = s
                while (!stopping.get()) {
                    val client = try { s.accept() } catch (_: Exception) { break }
                    serverExec.execute { handle(client) }
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun handle(client: Socket) {
        try {
            client.soTimeout = 15000
            val input = BufferedReader(InputStreamReader(client.getInputStream()))
            val out = client.getOutputStream()

            val requestLine = input.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            val rawPath = parts[1]
            val path = rawPath.substringBefore("?")
            val query = rawPath.substringAfter("?", "")

            var contentLength = 0
            var cookie = ""
            while (true) {
                val line = input.readLine() ?: break
                if (line.isEmpty()) break
                val low = line.lowercase()
                if (low.startsWith("content-length:")) {
                    contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
                } else if (low.startsWith("cookie:")) {
                    cookie = line.substringAfter(":").trim()
                }
            }
            var body = ""
            if (contentLength > 0) {
                val buf = CharArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val r = input.read(buf, read, contentLength - read)
                    if (r < 0) break
                    read += r
                }
                body = String(buf, 0, read)
            }

            val ip = client.inetAddress?.hostAddress ?: "?"
            val token = cookieToken(cookie) ?: queryParam(query, "t")

            when {
                path == "/" -> serveAsset(out, "panel.html", "text/html; charset=utf-8")
                path == "/auth" && method == "POST" -> doAuth(out, ip, body)
                !authed(token) -> send(out, 401, "application/json", """{"ok":false}""".toByteArray())
                path == "/stream" -> streamMjpeg(out)
                path == "/snapshot.jpg" -> {
                    val j = latestJpeg
                    if (j == null) send(out, 503, "text/plain", "no frame".toByteArray())
                    else send(out, 200, "image/jpeg", j)
                }
                path == "/status" -> send(out, 200, "application/json", statusJson().toByteArray())
                path == "/config" && method == "POST" -> { applyConfig(body); send(out, 200, "application/json", statusJson().toByteArray()) }
                else -> send(out, 404, "text/plain", "yok".toByteArray())
            }
        } catch (_: Exception) {
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
    }

    // ---------------- Kimlik doğrulama ----------------

    private fun doAuth(out: OutputStream, ip: String, body: String) {
        val now = System.currentTimeMillis()
        val until = lockUntil[ip] ?: 0
        if (now < until) {
            val sec = (until - now) / 1000 + 1
            send(out, 429, "application/json", """{"ok":false,"locked":true,"saniye":$sec}""".toByteArray())
            return
        }
        val pin = jsonValue(body, "pin")
        if (constantEquals(pin, Prefs.pin(this))) {
            failCount.remove(ip)
            lockUntil.remove(ip)
            val tok = newToken()
            tokens[tok] = now
            val headers = "Set-Cookie: t=$tok; Path=/; Max-Age=21600; SameSite=Lax"
            send(out, 200, "application/json", """{"ok":true}""".toByteArray(), extraHeader = headers)
        } else {
            val f = (failCount[ip] ?: 0) + 1
            failCount[ip] = f
            if (f >= 5) {
                // 5 yanlıştan sonra artan süreli kilit (silme YOK, sadece bekletme)
                val steps = f - 4
                val lockMs = minOf(30_000L * (1L shl minOf(steps, 5)), 15 * 60_000L)
                lockUntil[ip] = now + lockMs
                send(out, 429, "application/json", """{"ok":false,"locked":true,"saniye":${lockMs / 1000}}""".toByteArray())
            } else {
                send(out, 401, "application/json", """{"ok":false,"kalan":${5 - f}}""".toByteArray())
            }
        }
    }

    private fun authed(token: String?): Boolean {
        if (token == null) return false
        val t = tokens[token] ?: return false
        val now = System.currentTimeMillis()
        if (now - t > tokenTtl) { tokens.remove(token); return false }
        tokens[token] = now
        return true
    }

    private fun newToken(): String {
        val b = ByteArray(24)
        SecureRandom().nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }

    private fun constantEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var r = 0
        for (i in a.indices) r = r or (a[i].code xor b[i].code)
        return r == 0
    }

    private fun cookieToken(cookie: String): String? {
        for (c in cookie.split(";")) {
            val kv = c.trim().split("=", limit = 2)
            if (kv.size == 2 && kv[0] == "t") return kv[1]
        }
        return null
    }

    // ---------------- Yayın / yanıtlar ----------------

    private fun streamMjpeg(out: OutputStream) {
        val boundary = "frame"
        val header = ("HTTP/1.1 200 OK\r\n" +
            "Cache-Control: no-cache\r\n" +
            "Pragma: no-cache\r\n" +
            "Connection: close\r\n" +
            "Content-Type: multipart/x-mixed-replace; boundary=$boundary\r\n\r\n")
        out.write(header.toByteArray())
        try {
            while (!stopping.get()) {
                val j = latestJpeg
                if (j != null) {
                    val part = "--$boundary\r\nContent-Type: image/jpeg\r\nContent-Length: ${j.size}\r\n\r\n"
                    out.write(part.toByteArray())
                    out.write(j)
                    out.write("\r\n".toByteArray())
                    out.flush()
                }
                Thread.sleep(100) // ~10 fps
            }
        } catch (_: Exception) {
            // istemci kapandı
        }
    }

    private fun statusJson(): String {
        val motionActive = System.currentTimeMillis() - lastMotionMs < 1500
        return """{"on":true,"w":$frameW,"h":$frameH,"sensitivity":$sensitivity,"front":$useFront,"torch":$torchOn,"motion":$motionActive}"""
    }

    private fun applyConfig(body: String) {
        jsonValue(body, "sensitivity").toIntOrNull()?.let { sensitivity = it.coerceIn(1, 100); Prefs.put(this, mapOf("sensitivity" to sensitivity)) }
        when (jsonValue(body, "torch")) {
            "true" -> { torchOn = true; applyTorch() }
            "false" -> { torchOn = false; applyTorch() }
        }
        when (jsonValue(body, "front")) {
            "true" -> if (!useFront) { useFront = true; Prefs.put(this, mapOf("front" to true)); main.post { bindCamera() } }
            "false" -> if (useFront) { useFront = false; Prefs.put(this, mapOf("front" to false)); main.post { bindCamera() } }
        }
    }

    private fun serveAsset(out: OutputStream, name: String, mime: String) {
        try {
            val bytes = assets.open(name).readBytes()
            send(out, 200, mime, bytes)
        } catch (_: Exception) {
            send(out, 500, "text/plain", "asset yok".toByteArray())
        }
    }

    private fun send(out: OutputStream, code: Int, mime: String, body: ByteArray, extraHeader: String? = null) {
        val status = when (code) {
            200 -> "200 OK"; 401 -> "401 Unauthorized"; 404 -> "404 Not Found"
            429 -> "429 Too Many Requests"; 503 -> "503 Unavailable"; else -> "$code"
        }
        val sb = StringBuilder()
        sb.append("HTTP/1.1 $status\r\n")
        sb.append("Content-Type: $mime\r\n")
        sb.append("Content-Length: ${body.size}\r\n")
        sb.append("Connection: close\r\n")
        if (extraHeader != null) sb.append(extraHeader).append("\r\n")
        sb.append("\r\n")
        out.write(sb.toString().toByteArray())
        out.write(body)
        out.flush()
    }

    // ---------------- Küçük JSON/query yardımcıları ----------------

    private fun jsonValue(body: String, key: String): String {
        val m = Regex("\"$key\"\\s*:\\s*\"?([^\",}]+)\"?").find(body) ?: return ""
        return m.groupValues[1].trim()
    }

    private fun queryParam(query: String, key: String): String? {
        for (p in query.split("&")) {
            val kv = p.split("=", limit = 2)
            if (kv.size == 2 && kv[0] == key) return java.net.URLDecoder.decode(kv[1], "UTF-8")
        }
        return null
    }

    // ---------------- Kapanış ----------------

    override fun onDestroy() {
        stopping.set(true)
        running = false
        try { server?.close() } catch (_: Exception) {}
        try { provider?.unbindAll() } catch (_: Exception) {}
        try { wakeLock?.release() } catch (_: Exception) {}
        serverExec.shutdownNow()
        analysisExec.shutdownNow()
        super.onDestroy()
    }
}

/** Bildirimdeki "Durdur" düğmesi için küçük servis. */
class StopReceiver : android.app.Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        stopService(Intent(this, CameraService::class.java))
        stopSelf()
        return START_NOT_STICKY
    }
}
