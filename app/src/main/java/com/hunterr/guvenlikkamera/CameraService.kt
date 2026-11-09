package com.hunterr.guvenlikkamera

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Range
import android.util.Size
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URL
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket

/**
 * Ön plan servisi: kamerayı ekran kapalıyken de çalıştırır, yerel ağda PIN korumalı
 * bir web paneli sunar (HTTP 8080 + HTTPS 8443). Görüntü KAYDEDİLMEZ; yalnızca canlı izlenir.
 * Kamera bildirimi Android tarafından zorunlu tutulur (gizlenemez) ve kaç kişinin
 * izlediğini gösterir.
 */
class CameraService : LifecycleService() {

    companion object {
        const val PORT = 8080
        const val TLS_PORT = 8443
        const val CHANNEL = "kamera"
        const val NOTIF_ID = 42

        @Volatile var running = false
            private set
        @Volatile var httpsRunning = false
            private set
        @Volatile var fingerprint = ""
            private set
        @Volatile var viewers = 0
            private set

        private val RESOLUTIONS = setOf("480", "720", "1080")
        private const val JSON = "application/json; charset=utf-8"
        private val TOKEN_RE = Regex("^[0-9]{5,15}:[A-Za-z0-9_-]{20,64}$")
        private val CHAT_RE = Regex("^(-?[0-9]{3,20}|@[A-Za-z0-9_]{4,64})$")

        /** Açık panel oturumları. Her oturum, açıldığı IP'ye ve cihaza bağlıdır. */
        class Session(val ip: String, val dev: String?, @Volatile var last: Long)

        internal val sessions = ConcurrentHashMap<String, Session>()

        /** Uygulamadan "Tüm oturumları kapat": herkes yeniden PIN girmek zorunda kalır. */
        fun revokeAllSessions() = sessions.clear()

        fun appLockEnabled(c: Context) = Prefs.getBool(c, "app_lock", true)
    }

    // --- Kamera durumu ---
    @Volatile private var latestJpeg: ByteArray? = null
    @Volatile private var frameW = 0
    @Volatile private var frameH = 0
    @Volatile private var camera: Camera? = null
    private var provider: ProcessCameraProvider? = null
    private val main = Handler(Looper.getMainLooper())
    private val analysisExec = Executors.newSingleThreadExecutor()
    private val alertExec = Executors.newSingleThreadExecutor()
    private val motion = MotionDetector()
    @Volatile private var lastMotionMs = 0L
    private var motionStreak = 0
    @Volatile private var lastProcessMs = 0L

    // Pil tasarrufu: pahalı JPEG sıkıştırma yalnızca biri canlı izlerken yapılır.
    private val streamClients = AtomicInteger(0)

    // --- Ayarlar (panelden değişir, telefonda saklanır) ---
    @Volatile private var camName = "Kamera"
    @Volatile private var jpegQuality = 80
    @Volatile private var sensitivity = 60          // 1..100
    @Volatile private var useFront = false
    @Volatile private var torchOn = false
    @Volatile private var resolution = "720"        // 480 / 720 / 1080
    @Volatile private var userRotation = 0          // 0/90/180/270 (telefon yan durursa)
    @Volatile private var zoomRatio = 1f
    @Volatile private var exposureIdx = 0
    @Volatile private var lowLight = false
    @Volatile private var deepSave = false          // izlenmezken kamerayı tamamen kapat
    @Volatile private var httpsOnly = false

    // --- Telegram hareket bildirimi ---
    @Volatile private var tgOn = false
    @Volatile private var tgToken = ""
    @Volatile private var tgChat = ""
    @Volatile private var tgPhoto = false
    @Volatile private var tgCooldownSec = 60
    @Volatile private var lastAlertMs = 0L
    @Volatile private var lastAlertResult = ""

    // --- Sunucu ---
    private var server: ServerSocket? = null
    private var tlsServer: ServerSocket? = null
    private val serverExec = Executors.newCachedThreadPool()
    private val stopping = AtomicBoolean(false)
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val corsHeader = ThreadLocal<String?>()

    // --- Kaba kuvvet koruması ---
    private val failCount = ConcurrentHashMap<String, Int>()        // ip -> yanlış deneme
    private val lockUntil = ConcurrentHashMap<String, Long>()       // ip -> kilit bitiş zamanı
    private val globalFails = ArrayDeque<Long>()                    // tüm IP'lerden yanlış denemeler
    @Volatile private var globalLockUntil = 0L
    @Volatile private var lastSecAlertMs = 0L
    private val tokenTtl = 12 * 60 * 60 * 1000L
    private val rng = SecureRandom()

    private val unbindLater = Runnable { updateCameraBinding() }

    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        loadSettings()
    }

    private fun loadSettings() {
        camName = Prefs.getString(this, "name", "Kamera")
        sensitivity = Prefs.getInt(this, "sensitivity", 60)
        useFront = Prefs.getBool(this, "front", false)
        jpegQuality = Prefs.getInt(this, "quality", 80)
        resolution = Prefs.getString(this, "resolution", "720").takeIf { it in RESOLUTIONS } ?: "720"
        userRotation = Prefs.getInt(this, "rotate", 0)
        zoomRatio = Prefs.getFloat(this, "zoom", 1f)
        exposureIdx = Prefs.getInt(this, "exposure", 0)
        lowLight = Prefs.getBool(this, "lowlight", false)
        deepSave = Prefs.getBool(this, "deepsave", false)
        httpsOnly = Prefs.getBool(this, "httpsonly", true)
        tgOn = Prefs.getBool(this, "tg_on", false)
        tgToken = Prefs.getString(this, "tg_token", "")
        tgChat = Prefs.getString(this, "tg_chat", "")
        tgPhoto = Prefs.getBool(this, "tg_photo", false)
        tgCooldownSec = Prefs.getInt(this, "tg_cooldown", 60)
    }

    private fun save(key: String, value: Any) = Prefs.put(this, mapOf(key to value))

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (running) return START_STICKY
        running = true
        stopping.set(false)

        try {
            startForegroundNotif()
        } catch (t: Throwable) {
            // Bildirim/ön plan servisi başlatılamadıysa çökmek yerine hatayı kaydedip
            // düzgünce dur — "Hata kayıtları" ile görülebilsin.
            CrashLog.record(this, t)
            running = false
            stopSelf()
            return START_NOT_STICKY
        }
        try { acquireWake() } catch (t: Throwable) { CrashLog.record(this, t) }
        try { startCamera() } catch (t: Throwable) { CrashLog.record(this, t) }
        try { startServers() } catch (t: Throwable) { CrashLog.record(this, t) }
        KameraWidget.updateAll(this)
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    // ---------------- Bildirim ----------------

    private fun buildNotification(): Notification {
        val openPi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        // Uygulama kilidi açıksa bildirimden doğrudan durdurulamaz (telefonu eline alan biri
        // kamerayı kapatamasın); "Yönet" uygulamayı açar, orada PIN istenir.
        val locked = appLockEnabled(this)
        val actionPi = if (locked) openPi else PendingIntent.getBroadcast(
            this, 1, Intent(this, StopReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val ip = NetUtil.ip()
        val v = streamClients.get()
        val title = if (v > 0) "Güvenlik kamerası: $v kişi izliyor" else "Güvenlik kamerası çalışıyor"
        val text = (if (httpsRunning) "https://$ip:$TLS_PORT" else "http://$ip:$PORT") +
            if (httpsRunning && !httpsOnly) "  •  http://$ip:$PORT" else ""
        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(openPi)
            .addAction(0, if (locked) "Yönet" else "Durdur", actionPi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun startForegroundNotif() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Kamera", NotificationManager.IMPORTANCE_LOW)
            )
        }
        // Kamera izni yoksa camera-tipli ön plan servisi Android 14'te hata verir;
        // o durumda tipsiz başlat (çökmez).
        val hasCam = checkSelfPermission(android.Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        val type = if (Build.VERSION.SDK_INT >= 29 && hasCam)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), type)
    }

    private fun refreshNotification() {
        if (!running) return
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
        } catch (t: Throwable) {
            CrashLog.record(this, t)
        }
    }

    private fun acquireWake() {
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "kamera:wl").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    /** Biri izlerken Wi-Fi'yi tam performansta tut (akıcı görüntü); kimse yokken bırak (pil). */
    @Synchronized
    private fun updateWifiLock() {
        try {
            val need = streamClients.get() > 0 && running
            if (need) {
                if (wifiLock == null) {
                    val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                    @Suppress("DEPRECATION")
                    val mode = WifiManager.WIFI_MODE_FULL_HIGH_PERF
                    wifiLock = wm.createWifiLock(mode, "kamera:wifi").apply { setReferenceCounted(false) }
                }
                if (wifiLock?.isHeld == false) wifiLock?.acquire()
            } else if (wifiLock?.isHeld == true) {
                wifiLock?.release()
            }
        } catch (t: Throwable) {
            CrashLog.record(this, t)
        }
    }

    // ---------------- Kamera ----------------

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                provider = future.get()
                updateCameraBinding()
            } catch (t: Throwable) {
                CrashLog.record(this, t)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun alertsReady() = tgOn && tgToken.isNotBlank() && tgChat.isNotBlank()

    /** Derin tasarruf açıkken kamera yalnızca biri izlerken veya bildirim açıkken çalışır. */
    private fun cameraNeeded() = !deepSave || streamClients.get() > 0 || alertsReady()

    /** Ana thread'de çağrılır: kameranın açık/kapalı olması gereken duruma geçirir. */
    private fun updateCameraBinding() {
        if (stopping.get()) return
        val p = provider ?: return
        val need = cameraNeeded()
        if (need && camera == null) {
            bindCamera()
        } else if (!need && camera != null) {
            try { p.unbindAll() } catch (t: Throwable) { CrashLog.record(this, t) }
            camera = null
            latestJpeg = null
            motion.reset()
        }
    }

    /** Ayar değişince (ön/arka, çözünürlük, düşük ışık) kamerayı yeniden kur. */
    private fun rebindCamera() = main.post {
        if (stopping.get() || provider == null) return@post
        if (cameraNeeded()) bindCamera()
    }

    private fun targetSize(): Pair<Size, AspectRatioStrategy> = when (resolution) {
        // Boyutlar sensör yönündedir (yatay), telefon nasıl durursa dursun.
        "480" -> Size(640, 480) to AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
        "1080" -> Size(1920, 1080) to AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
        else -> Size(1280, 720) to AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
    }

    /**
     * Sensörün kare hızını düşürmek pil ve ısıyı ciddi azaltır. Telefonun desteklediği
     * aralıklardan seçilir: normalde en düşük üst sınır (pil), düşük ışık modunda en
     * düşük alt sınır (daha uzun pozlama = karanlıkta daha parlak görüntü).
     */
    @OptIn(markerClass = [ExperimentalCamera2Interop::class])
    private fun pickFpsRange(p: ProcessCameraProvider, selector: CameraSelector): Range<Int>? {
        return try {
            val info = selector.filter(p.availableCameraInfos).firstOrNull() ?: return null
            val ranges = Camera2CameraInfo.from(info)
                .getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                ?: return null
            val usable = ranges.filter { it.upper >= 10 }
            if (lowLight) usable.minWithOrNull(compareBy<Range<Int>>({ it.lower }, { it.upper }))
            else usable.minWithOrNull(compareBy<Range<Int>>({ it.upper }, { it.lower }))
        } catch (t: Throwable) {
            null
        }
    }

    @OptIn(markerClass = [ExperimentalCamera2Interop::class])
    private fun buildAnalysis(p: ProcessCameraProvider, selector: CameraSelector, withFps: Boolean): ImageAnalysis {
        val (size, aspect) = targetSize()
        val rs = ResolutionSelector.Builder()
            .setAspectRatioStrategy(aspect)
            .setResolutionStrategy(
                ResolutionStrategy(size, ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
            )
            .build()
        val builder = ImageAnalysis.Builder()
            .setResolutionSelector(rs)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        if (withFps) {
            pickFpsRange(p, selector)?.let { range ->
                Camera2Interop.Extender(builder)
                    .setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range)
            }
        }
        val analysis = builder.build()
        analysis.setAnalyzer(analysisExec) { image -> analyze(image) }
        return analysis
    }

    private fun bindCamera() {
        val p = provider ?: return
        try { p.unbindAll() } catch (_: Throwable) {}
        camera = null
        motion.reset()
        motionStreak = 0

        val wanted = if (useFront) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        // Sırayla dene: istenen kamera + kare hızı ayarı, istenen kamera düz, sonra arka kamera düz.
        val attempts = listOf(wanted to true, wanted to false, CameraSelector.DEFAULT_BACK_CAMERA to false)
        for ((selector, withFps) in attempts) {
            try {
                camera = p.bindToLifecycle(this, selector, buildAnalysis(p, selector, withFps))
                if (selector === CameraSelector.DEFAULT_BACK_CAMERA && useFront) {
                    useFront = false
                    save("front", false)
                }
                applyTorch()
                applyZoom()
                applyExposure()
                return
            } catch (t: Throwable) {
                CrashLog.record(this, t)
                try { p.unbindAll() } catch (_: Throwable) {}
            }
        }
    }

    private fun analyze(image: ImageProxy) {
        try {
            val watching = streamClients.get() > 0
            val alerting = alertsReady()
            // Kimse izlemiyorsa ve bildirim kapalıysa hiçbir şey yapma — pil yeme.
            if (!watching && !alerting) return

            // İzlenirken ~10 fps; yalnızca bildirim için ~2.5 fps hareket kontrolü yeter.
            val now = System.currentTimeMillis()
            val interval = if (watching) 100L else 400L
            if (now - lastProcessMs < interval) return
            lastProcessMs = now

            val yb = image.planes[0]
            val moved = motion.check(yb.buffer, yb.rowStride, yb.pixelStride, image.width, image.height, sensitivity)
            if (moved) {
                lastMotionMs = now
                motionStreak++
            } else {
                motionStreak = 0
            }

            var jpeg: ByteArray? = null
            if (watching) {
                jpeg = encode(image)
                latestJpeg = jpeg
            }

            // Art arda en az 2 kontrolde hareket olursa bildir (tek karelik parazitleri ele).
            if (alerting && motionStreak >= 2 && now - lastAlertMs >= tgCooldownSec * 1000L) {
                lastAlertMs = now
                val photo = if (tgPhoto) (jpeg ?: encode(image)) else null
                alertExec.execute { sendMotionAlert(photo) }
            }
        } catch (t: Throwable) {
            CrashLog.record(this, t)
        } finally {
            image.close()
        }
    }

    private fun encode(image: ImageProxy): ByteArray {
        val w = image.width
        val h = image.height
        val rot = (image.imageInfo.rotationDegrees + userRotation) % 360
        val nv21 = ImageUtils.rotateNv21(ImageUtils.toNv21(image), w, h, rot)
        val ow = if (rot % 180 == 0) w else h
        val oh = if (rot % 180 == 0) h else w
        frameW = ow
        frameH = oh
        return ImageUtils.nv21ToJpeg(nv21, ow, oh, jpegQuality)
    }

    private fun applyTorch() = main.post {
        try {
            val cam = camera ?: return@post
            if (cam.cameraInfo.hasFlashUnit()) cam.cameraControl.enableTorch(torchOn)
        } catch (t: Throwable) {
            CrashLog.record(this, t)
        }
    }

    private fun applyZoom() = main.post {
        try {
            val cam = camera ?: return@post
            val zs = cam.cameraInfo.zoomState.value
            val z = if (zs != null) zoomRatio.coerceIn(zs.minZoomRatio, zs.maxZoomRatio) else zoomRatio
            cam.cameraControl.setZoomRatio(z)
        } catch (t: Throwable) {
            CrashLog.record(this, t)
        }
    }

    private fun applyExposure() = main.post {
        try {
            val cam = camera ?: return@post
            val es = cam.cameraInfo.exposureState
            if (!es.isExposureCompensationSupported) return@post
            val r = es.exposureCompensationRange
            cam.cameraControl.setExposureCompensationIndex(exposureIdx.coerceIn(r.lower, r.upper))
        } catch (t: Throwable) {
            CrashLog.record(this, t)
        }
    }

    // ---------------- Telegram ----------------

    private fun sendMotionAlert(photo: ByteArray?) {
        val caption = "🚨 Hareket algılandı\n📷 $camName\n🕒 ${Clock.human()}"
        lastAlertResult = try {
            val r = if (photo != null) {
                telegram(tgToken, "sendPhoto", mapOf("chat_id" to tgChat, "caption" to caption), photo)
            } else {
                telegram(tgToken, "sendMessage", mapOf("chat_id" to tgChat, "text" to caption), null)
            }
            if (r.first in 200..299) "Son bildirim gönderildi: ${Clock.short()}"
            else "Telegram hatası (${r.first}): ${telegramDesc(r.second)}"
        } catch (t: Throwable) {
            "Gönderilemedi (internet var mı?): ${t.javaClass.simpleName}"
        }
    }

    /** Telegram Bot API çağrısı. fields boş ve fotoğraf yoksa GET yapılır. */
    private fun telegram(token: String, method: String, fields: Map<String, String>, photo: ByteArray?): Pair<Int, String> {
        if (!TOKEN_RE.matches(token)) return 400 to """{"description":"Bot token biçimi hatalı"}"""
        val conn = URL("https://api.telegram.org/bot$token/$method").openConnection() as HttpsURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 20_000
            if (fields.isNotEmpty() || photo != null) {
                val boundary = "----kamera" + System.nanoTime()
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                conn.outputStream.use { os ->
                    for ((k, v) in fields) {
                        os.write("--$boundary\r\nContent-Disposition: form-data; name=\"$k\"\r\n\r\n".toByteArray())
                        os.write(v.toByteArray(Charsets.UTF_8))
                        os.write("\r\n".toByteArray())
                    }
                    if (photo != null) {
                        os.write(
                            ("--$boundary\r\nContent-Disposition: form-data; name=\"photo\"; filename=\"hareket.jpg\"\r\n" +
                                "Content-Type: image/jpeg\r\n\r\n").toByteArray()
                        )
                        os.write(photo)
                        os.write("\r\n".toByteArray())
                    }
                    os.write("--$boundary--\r\n".toByteArray())
                }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            return code to body
        } finally {
            conn.disconnect()
        }
    }

    private fun telegramDesc(body: String): String =
        Json.get(body, "description") ?: body.take(160)

    private fun telegramTest(body: String): String {
        val token = Json.get(body, "tgToken")?.trim()?.takeIf { it.isNotEmpty() } ?: tgToken
        val chat = Json.get(body, "tgChat")?.trim()?.takeIf { it.isNotEmpty() } ?: tgChat
        if (!TOKEN_RE.matches(token)) return JObj().put("ok", false).put("msg", "Önce geçerli bir bot token gir.").toString()
        if (!CHAT_RE.matches(chat)) return JObj().put("ok", false).put("msg", "Önce Chat ID'yi bul veya gir.").toString()
        return try {
            val r = telegram(token, "sendMessage", mapOf("chat_id" to chat, "text" to "✅ Test: $camName bildirimleri çalışıyor."), null)
            if (r.first in 200..299) JObj().put("ok", true).put("msg", "Test mesajı gönderildi, Telegram'a bak.").toString()
            else JObj().put("ok", false).put("msg", "Telegram hatası: " + telegramDesc(r.second)).toString()
        } catch (t: Throwable) {
            JObj().put("ok", false).put("msg", "Telegram'a ulaşılamadı. Telefonda internet var mı? (${t.javaClass.simpleName})").toString()
        }
    }

    /** Bota en son mesaj atan sohbetin kimliğini bulur (kullanıcı Chat ID aramak zorunda kalmasın). */
    private fun telegramDetect(body: String): String {
        val token = Json.get(body, "tgToken")?.trim()?.takeIf { it.isNotEmpty() } ?: tgToken
        if (!TOKEN_RE.matches(token)) return JObj().put("ok", false).put("msg", "Bot token biçimi hatalı.").toString()
        return try {
            val r = telegram(token, "getUpdates", emptyMap(), null)
            if (r.first !in 200..299) {
                return JObj().put("ok", false).put("msg", "Telegram hatası: " + telegramDesc(r.second)).toString()
            }
            val m = Regex("\"chat\":\\{\"id\":(-?[0-9]+)").findAll(r.second).lastOrNull()
                ?: return JObj().put("ok", false)
                    .put("msg", "Henüz mesaj yok. Telegram'da botuna bir mesaj (ör. merhaba) gönder, sonra tekrar dene.")
                    .toString()
            val chatId = m.groupValues[1]
            val tail = r.second.substring(m.range.first + 7)
            val objEnd = tail.indexOf('}')
            val chatObj = if (objEnd > 0) tail.substring(0, objEnd + 1) else ""
            val name = Json.get(chatObj, "first_name") ?: Json.get(chatObj, "title") ?: ""
            JObj().put("ok", true).put("chat", chatId).put("name", name).toString()
        } catch (t: Throwable) {
            JObj().put("ok", false).put("msg", "Telegram'a ulaşılamadı. Telefonda internet var mı? (${t.javaClass.simpleName})").toString()
        }
    }

    // ---------------- HTTP / HTTPS sunucusu ----------------

    private fun startServers() {
        serverExec.execute {
            try {
                val s = ServerSocket()
                s.reuseAddress = true
                s.bind(InetSocketAddress(PORT))
                server = s
                acceptLoop(s)
            } catch (t: Throwable) {
                if (!stopping.get()) CrashLog.record(this, t)
            }
        }
        serverExec.execute {
            try {
                // İlk açılışta sertifika üretimi 1-3 sn sürebilir; HTTP bu arada çalışır.
                val m = Tls.loadOrCreate(filesDir, NetUtil.ipOrNull() ?: "0.0.0.0")
                fingerprint = m.fingerprint
                val s = Tls.serverSocketFactory(m).createServerSocket()
                s.reuseAddress = true
                s.bind(InetSocketAddress(TLS_PORT))
                tlsServer = s
                httpsRunning = true
                main.post { refreshNotification() }
                acceptLoop(s)
            } catch (t: Throwable) {
                if (!stopping.get()) CrashLog.record(this, t)
            } finally {
                httpsRunning = false
            }
        }
    }

    private fun acceptLoop(s: ServerSocket) {
        while (!stopping.get()) {
            val client = try { s.accept() } catch (_: Exception) { break }
            try {
                serverExec.execute { handle(client) }
            } catch (_: Throwable) {
                try { client.close() } catch (_: Exception) {}
            }
        }
    }

    private fun readLine(ins: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = ins.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString()
            if (b != '\r'.code) sb.append(b.toChar())
            if (sb.length > 8192) return null
        }
    }

    private fun readBody(ins: InputStream, len: Int): ByteArray {
        val buf = ByteArray(len)
        var off = 0
        while (off < len) {
            val r = ins.read(buf, off, len - off)
            if (r < 0) break
            off += r
        }
        return if (off == len) buf else buf.copyOf(off)
    }

    private fun handle(client: Socket) {
        try {
            // Yalnızca ev ağından (yerel adreslerden) gelen bağlantılar kabul edilir.
            // İnternetten (ör. mobil veri / dışa açık IPv6) gelen her şey kapıda reddedilir.
            val remote = client.inetAddress
            if (remote == null || !NetUtil.isLocalNetwork(remote)) return

            client.soTimeout = 15000
            val tls = client is SSLSocket
            val ins = BufferedInputStream(client.getInputStream())
            val out = BufferedOutputStream(client.getOutputStream(), 64 * 1024)

            val requestLine = readLine(ins) ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0].uppercase()
            val rawPath = parts[1]
            val path = rawPath.substringBefore("?")
            val query = rawPath.substringAfter("?", "")

            val headers = HashMap<String, String>()
            var count = 0
            while (true) {
                val line = readLine(ins) ?: break
                if (line.isEmpty()) break
                if (++count > 100) return
                val idx = line.indexOf(':')
                if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
            }
            val len = headers["content-length"]?.toIntOrNull() ?: 0
            if (len < 0 || len > 64 * 1024) {
                send(out, 413, JSON, """{"ok":false}""".toByteArray())
                return
            }
            val body = if (len > 0) String(readBody(ins, len), Charsets.UTF_8) else ""

            val ip = client.inetAddress?.hostAddress ?: "?"
            corsHeader.set(corsFor(headers["origin"]))

            if (method == "OPTIONS") {
                send(
                    out, 204, "text/plain", ByteArray(0),
                    listOf(
                        "Access-Control-Allow-Methods: GET, POST",
                        "Access-Control-Allow-Headers: Content-Type",
                        "Access-Control-Allow-Private-Network: true"
                    )
                )
                return
            }

            // "Yalnızca HTTPS" açıksa şifresiz bağlantıları HTTPS'e yönlendir.
            if (!tls && httpsOnly && httpsRunning) {
                if (path == "/") {
                    val host = (headers["host"] ?: NetUtil.ip()).substringBefore(":")
                    send(out, 302, "text/plain", ByteArray(0), listOf("Location: https://$host:$TLS_PORT/"))
                } else {
                    send(out, 403, JSON, """{"ok":false,"httpsOnly":true}""".toByteArray())
                }
                return
            }

            val cookies = headers["cookie"] ?: ""
            val token = listOfNotNull(cookieValue(cookies, "t"), queryParam(query, "t"))
                .firstOrNull { authed(it, ip) }

            when {
                path == "/" || path == "/index.html" -> serveAsset(out, "panel.html", "text/html; charset=utf-8")
                path == "/auth" && method == "POST" -> doAuth(out, ip, body, tls, cookieValue(cookies, "d"))
                path == "/auth/wait" -> authWait(out, ip, queryParam(query, "req"), tls)
                token == null -> send(out, 401, JSON, """{"ok":false}""".toByteArray())
                path == "/logout" && method == "POST" -> {
                    sessions.remove(token)
                    ConnLog.add(this, ip, ConnLog.LOGOUT)
                    send(out, 200, JSON, """{"ok":true}""".toByteArray(), listOf("Set-Cookie: t=; Path=/; Max-Age=0"))
                }
                path == "/sessions/revoke" && method == "POST" -> {
                    sessions.clear()
                    ConnLog.add(this, ip, "Tüm oturumlar kapatıldı")
                    send(out, 200, JSON, """{"ok":true}""".toByteArray(), listOf("Set-Cookie: t=; Path=/; Max-Age=0"))
                }
                path == "/stream" -> streamMjpeg(out)
                path == "/status" -> send(out, 200, JSON, statusJson(tls).toByteArray())
                path == "/config" && method == "POST" -> {
                    val err = applyConfig(body)
                    val st = statusJson(tls)
                    if (err == null) send(out, 200, JSON, st.toByteArray())
                    else send(out, 400, JSON, JObj().put("ok", false).put("msg", err).toString().toByteArray())
                }
                path == "/history" -> send(out, 200, JSON, ConnLog.readJson(this).toByteArray())
                path == "/qr.png" -> {
                    val https = queryParam(query, "s") == "https" && httpsRunning
                    val url = if (https) "https://${NetUtil.ip()}:$TLS_PORT/" else "http://${NetUtil.ip()}:$PORT/"
                    send(out, 200, "image/png", Qr.png(url, 360), listOf("Cache-Control: no-store"))
                }
                path == "/telegram/test" && method == "POST" -> send(out, 200, JSON, telegramTest(body).toByteArray())
                path == "/telegram/detect" && method == "POST" -> send(out, 200, JSON, telegramDetect(body).toByteArray())
                else -> send(out, 404, "text/plain; charset=utf-8", "yok".toByteArray())
            }
        } catch (_: Exception) {
            // istemci koptu / zaman aşımı / TLS el sıkışması reddedildi (sertifika onaylanmadı)
        } catch (t: Throwable) {
            CrashLog.record(this, t)
        } finally {
            corsHeader.remove()
            try { client.close() } catch (_: Exception) {}
        }
    }

    /** Başka bir kameranın paneli (yalnızca yerel ağdaki adresler) bu kameraya erişebilsin. */
    private fun corsFor(origin: String?): String? {
        if (origin.isNullOrBlank()) return null
        return try {
            val host = URI(origin).host ?: return null
            if (NetUtil.isPrivateIpv4(host)) origin else null
        } catch (_: Exception) {
            null
        }
    }

    // ---------------- Kimlik doğrulama ----------------

    private fun deviceCookie(dev: String, tls: Boolean) =
        "Set-Cookie: d=$dev; Path=/; Max-Age=63072000; HttpOnly; SameSite=Lax" + (if (tls) "; Secure" else "")

    private fun sessionCookie(tok: String, tls: Boolean) =
        "Set-Cookie: t=$tok; Path=/; Max-Age=43200; HttpOnly; SameSite=Lax" + (if (tls) "; Secure" else "")

    private fun openSession(ip: String, dev: String?): String {
        val now = System.currentTimeMillis()
        pruneSessions(now)
        val tok = newToken()
        sessions[tok] = Session(ip, dev, now)
        return tok
    }

    private fun doAuth(out: OutputStream, ip: String, body: String, tls: Boolean, cookieDev: String?) {
        val now = System.currentTimeMillis()
        if (now < globalLockUntil) {
            val sec = (globalLockUntil - now) / 1000 + 1
            send(out, 429, JSON, """{"ok":false,"locked":true,"global":true,"saniye":$sec}""".toByteArray())
            return
        }
        val until = lockUntil[ip] ?: 0
        if (now < until) {
            val sec = (until - now) / 1000 + 1
            send(out, 429, JSON, """{"ok":false,"locked":true,"saniye":$sec}""".toByteArray())
            return
        }
        val pin = Json.get(body, "pin") ?: ""
        val real = Prefs.pin(this)
        if (pin.isEmpty() || real.isEmpty() || !constantEquals(pin, real)) {
            onBadPin(out, ip, now)
            return
        }

        failCount.remove(ip)
        lockUntil.remove(ip)

        // Cihaz kimliği: aynı bilgisayarda http ve https'te ortak olsun diye çerezde tutulur;
        // başka kameranın panelinden gelen isteklerde (çerez gitmez) gövdeden okunur.
        val dev = cookieDev?.takeIf { DeviceGate.validId(it) }
            ?: Json.get(body, "dev")?.takeIf { DeviceGate.validId(it) }
            ?: DeviceGate.newDeviceId()
        val devName = DeviceGate.clean(Json.get(body, "name") ?: "Bilinmeyen cihaz")

        if (DeviceGate.enabled(this) && !DeviceGate.isApproved(this, dev)) {
            val req = try {
                DeviceGate.requestApproval(this, dev, devName, ip)
            } catch (_: DeviceGate.TooManyRequests) {
                send(out, 429, JSON, JObj().put("ok", false).put("msg", "Çok fazla bekleyen onay isteği var. Biraz sonra tekrar dene.").toString().toByteArray())
                return
            }
            ConnLog.add(this, ip, "PIN doğru, telefondan onay bekleniyor: $devName")
            securityAlert("🔐 Yeni bir cihaz doğru PIN ile panele girmek istiyor:\n$devName ($ip)\n\nSen değilsen telefondan REDDET ve PIN'i değiştir.", force = true)
            send(
                out, 200, JSON,
                JObj().put("ok", false).put("pending", true).put("req", req.id).put("dev", dev)
                    .put("saniye", req.secondsLeft()).toString().toByteArray(),
                listOf(deviceCookie(dev, tls))
            )
            return
        }

        val tok = openSession(ip, dev)
        ConnLog.add(this, ip, "${ConnLog.LOGIN} ($devName)")
        send(
            out, 200, JSON,
            JObj().put("ok", true).put("t", tok).put("dev", dev).toString().toByteArray(),
            listOf(sessionCookie(tok, tls), deviceCookie(dev, tls))
        )
    }

    /** Panel, telefondan onay gelene kadar bunu birkaç saniyede bir sorar. */
    private fun authWait(out: OutputStream, ip: String, reqId: String?, tls: Boolean) {
        val r = DeviceGate.get(reqId)
        // İstek yalnızca onu başlatan IP'ye cevap verir.
        if (r == null || r.ip != ip) {
            send(out, 200, JSON, """{"state":"expired"}""".toByteArray())
            return
        }
        if (r.state == "pending" && r.secondsLeft() <= 0) r.state = "expired"
        when (r.state) {
            "pending" -> send(out, 200, JSON, """{"state":"pending","saniye":${r.secondsLeft()}}""".toByteArray())
            "approved" -> {
                synchronized(r) {
                    if (r.claimed) {
                        send(out, 200, JSON, """{"state":"expired"}""".toByteArray())
                        return
                    }
                    r.claimed = true
                }
                val tok = openSession(ip, r.dev)
                ConnLog.add(this, ip, "${ConnLog.LOGIN} (${r.name})")
                send(
                    out, 200, JSON,
                    JObj().put("state", "approved").put("ok", true).put("t", tok).put("dev", r.dev).toString().toByteArray(),
                    listOf(sessionCookie(tok, tls), deviceCookie(r.dev, tls))
                )
            }
            else -> send(out, 200, JSON, JObj().put("state", r.state).toString().toByteArray())
        }
    }

    private fun onBadPin(out: OutputStream, ip: String, now: Long) {
        // Tüm IP'lerden toplam deneme sınırı: ağdaki biri IP değiştirerek deneme yapamasın.
        val globalLocked = synchronized(globalFails) {
            globalFails.addLast(now)
            while (globalFails.isNotEmpty() && now - globalFails.first() > 15 * 60_000L) globalFails.removeFirst()
            if (globalFails.size >= 20) {
                globalLockUntil = now + 5 * 60_000L
                globalFails.clear()
                true
            } else false
        }
        if (globalLocked) {
            ConnLog.add(this, ip, "Panel 5 dk kilitlendi (toplu yanlış deneme)")
            securityAlert("⚠️ Panele çok sayıda yanlış PIN denendi. Giriş 5 dakika kilitlendi. Son deneme: $ip", force = true)
            send(out, 429, JSON, """{"ok":false,"locked":true,"global":true,"saniye":300}""".toByteArray())
            return
        }

        val f = (failCount[ip] ?: 0) + 1
        failCount[ip] = f
        if (f >= 5) {
            // 5 yanlıştan sonra artan süreli kilit (silme YOK, sadece bekletme)
            val steps = f - 4
            val lockMs = minOf(30_000L * (1L shl minOf(steps, 5)), 15 * 60_000L)
            lockUntil[ip] = now + lockMs
            ConnLog.add(this, ip, ConnLog.LOCKED)
            securityAlert("⚠️ $ip adresinden art arda yanlış PIN denendi; bu adres ${lockMs / 1000} sn kilitlendi.")
            send(out, 429, JSON, """{"ok":false,"locked":true,"saniye":${lockMs / 1000}}""".toByteArray())
        } else {
            ConnLog.add(this, ip, ConnLog.BAD_PIN)
            send(out, 401, JSON, """{"ok":false,"kalan":${5 - f}}""".toByteArray())
        }
    }

    /** Telegram ayarlıysa güvenlik olaylarını bildirir (hareket bildirimi kapalı olsa bile). */
    private fun securityAlert(text: String, force: Boolean = false) {
        val token = tgToken
        val chat = tgChat
        if (!TOKEN_RE.matches(token) || !CHAT_RE.matches(chat)) return
        if (!Prefs.getBool(this, "tg_sec", true)) return
        val now = System.currentTimeMillis()
        if (!force && now - lastSecAlertMs < 60_000) return
        lastSecAlertMs = now
        try {
            alertExec.execute {
                try {
                    telegram(token, "sendMessage", mapOf("chat_id" to chat, "text" to "$text\n📷 $camName • ${Clock.human()}"), null)
                } catch (_: Throwable) {
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun authed(token: String, ip: String): Boolean {
        val s = sessions[token] ?: return false
        val now = System.currentTimeMillis()
        // Oturum yalnızca açıldığı IP'den kullanılabilir; çalınan anahtar başka yerde işe yaramaz.
        if (s.ip != ip || now - s.last > tokenTtl) {
            if (now - s.last > tokenTtl) sessions.remove(token)
            return false
        }
        // Cihaz telefondan kaldırıldıysa oturumu da anında geçersiz.
        if (DeviceGate.enabled(this) && !DeviceGate.isApproved(this, s.dev)) {
            sessions.remove(token)
            return false
        }
        s.last = now
        return true
    }

    private fun pruneSessions(now: Long) {
        sessions.entries.removeIf { now - it.value.last > tokenTtl }
        if (sessions.size > 50) {
            sessions.entries.sortedBy { it.value.last }.take(sessions.size - 50).forEach { sessions.remove(it.key) }
        }
    }

    private fun newToken(): String {
        val b = ByteArray(24)
        rng.nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }

    private fun constantEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var r = 0
        for (i in a.indices) r = r or (a[i].code xor b[i].code)
        return r == 0
    }

    private fun cookieValue(cookie: String, name: String): String? {
        for (c in cookie.split(";")) {
            val kv = c.trim().split("=", limit = 2)
            if (kv.size == 2 && kv[0] == name && kv[1].isNotEmpty()) return kv[1]
        }
        return null
    }

    // ---------------- Canlı yayın ----------------

    private fun onViewerJoin() {
        viewers = streamClients.incrementAndGet()
        main.removeCallbacks(unbindLater)
        main.post {
            updateCameraBinding()
            refreshNotification()
            KameraWidget.updateAll(this)
        }
        updateWifiLock()
    }

    private fun onViewerLeave() {
        viewers = streamClients.decrementAndGet().coerceAtLeast(0)
        if (viewers == 0) {
            // Panel yenilenirse kamera kapanıp açılmasın diye biraz bekle.
            main.postDelayed(unbindLater, 15_000)
        }
        main.post {
            refreshNotification()
            KameraWidget.updateAll(this)
        }
        updateWifiLock()
    }

    private fun streamMjpeg(out: OutputStream) {
        val boundary = "frame"
        val sb = StringBuilder()
        sb.append("HTTP/1.1 200 OK\r\n")
        sb.append("Cache-Control: no-cache, no-store\r\n")
        sb.append("Pragma: no-cache\r\n")
        sb.append("Connection: close\r\n")
        sb.append("X-Content-Type-Options: nosniff\r\n")
        corsHeader.get()?.let { sb.append("Access-Control-Allow-Origin: ").append(it).append("\r\nVary: Origin\r\n") }
        sb.append("Content-Type: multipart/x-mixed-replace; boundary=$boundary\r\n\r\n")
        out.write(sb.toString().toByteArray())
        out.flush()

        onViewerJoin()
        var lastSent: ByteArray? = null
        val started = System.currentTimeMillis()
        var lastWrite = started
        try {
            while (!stopping.get()) {
                val now = System.currentTimeMillis()
                val j = latestJpeg
                // Yeni kare varsa gönder; uzun süre yeni kare yoksa son kareyi tekrar gönder
                // (kopmuş bağlantılar böylece fark edilir ve kamera boşuna açık kalmaz).
                val frame = when {
                    j != null && j !== lastSent -> j
                    lastSent != null && now - lastWrite > 5000 -> lastSent
                    else -> null
                }
                if (frame != null) {
                    lastSent = frame
                    out.write("--$boundary\r\nContent-Type: image/jpeg\r\nContent-Length: ${frame.size}\r\n\r\n".toByteArray())
                    out.write(frame)
                    out.write("\r\n".toByteArray())
                    out.flush()
                    lastWrite = now
                } else if (lastSent == null && now - started > 30_000) {
                    break // 30 sn hiç görüntü gelmediyse bırak; panel yeniden bağlanır
                }
                Thread.sleep(40)
            }
        } catch (_: Exception) {
            // izleyici kapattı
        } finally {
            onViewerLeave()
        }
    }

    private fun statusJson(tls: Boolean): String {
        val cam = camera
        var zMin = 1f
        var zMax = 1f
        var eMin = 0
        var eMax = 0
        var hasTorch = false
        try {
            cam?.cameraInfo?.zoomState?.value?.let { zMin = it.minZoomRatio; zMax = it.maxZoomRatio }
            cam?.cameraInfo?.exposureState?.let {
                if (it.isExposureCompensationSupported) {
                    eMin = it.exposureCompensationRange.lower
                    eMax = it.exposureCompensationRange.upper
                }
            }
            hasTorch = cam?.cameraInfo?.hasFlashUnit() == true
        } catch (_: Throwable) {
        }
        return JObj()
            .put("on", true)
            .put("name", camName)
            .put("camActive", cam != null)
            .put("w", frameW).put("h", frameH)
            .put("motion", System.currentTimeMillis() - lastMotionMs < 2000)
            .put("viewers", streamClients.get())
            .put("sensitivity", sensitivity)
            .put("quality", jpegQuality)
            .put("resolution", resolution)
            .put("rotate", userRotation)
            .put("front", useFront)
            .put("torch", torchOn).put("hasTorch", hasTorch)
            .put("zoom", zoomRatio).put("zoomMin", zMin).put("zoomMax", zMax)
            .put("exposure", exposureIdx).put("expMin", eMin).put("expMax", eMax)
            .put("lowLight", lowLight)
            .put("deepSave", deepSave)
            .put("tgOn", tgOn)
            .put("tgTokenSet", tgToken.isNotBlank())
            .put("tgChat", tgChat)
            .put("tgPhoto", tgPhoto)
            .put("tgCooldown", tgCooldownSec)
            .put("tgLast", lastAlertResult)
            .put("tgSec", Prefs.getBool(this, "tg_sec", true))
            .put("deviceGate", DeviceGate.enabled(this))
            .put("sessions", sessions.size)
            .put("https", httpsRunning)
            .put("tlsPort", TLS_PORT)
            .put("httpPort", PORT)
            .put("httpsOnly", httpsOnly)
            .put("secure", tls)
            .put("fp", fingerprint)
            .put("ip", NetUtil.ip())
            .toString()
    }

    private fun bool(body: String, key: String): Boolean? = when (Json.get(body, key)) {
        "true" -> true
        "false" -> false
        else -> null
    }

    /** Panelden gelen ayarları uygular. Hata varsa açıklamasını döndürür. */
    private fun applyConfig(body: String): String? {
        var rebind = false
        var bindingCheck = false

        Json.get(body, "name")?.trim()?.take(32)?.let { if (it.isNotEmpty()) { camName = it; save("name", it) } }
        Json.get(body, "sensitivity")?.toIntOrNull()?.let { sensitivity = it.coerceIn(1, 100); save("sensitivity", sensitivity) }
        Json.get(body, "quality")?.toIntOrNull()?.let { jpegQuality = it.coerceIn(30, 95); save("quality", jpegQuality) }
        Json.get(body, "resolution")?.let {
            if (it in RESOLUTIONS && it != resolution) { resolution = it; save("resolution", it); rebind = true }
        }
        Json.get(body, "rotate")?.toIntOrNull()?.let {
            if (it in listOf(0, 90, 180, 270)) { userRotation = it; save("rotate", it) }
        }
        Json.get(body, "zoom")?.toFloatOrNull()?.let {
            if (it.isFinite()) { zoomRatio = it.coerceIn(1f, 30f); save("zoom", zoomRatio); applyZoom() }
        }
        Json.get(body, "exposure")?.toIntOrNull()?.let { exposureIdx = it.coerceIn(-100, 100); save("exposure", exposureIdx); applyExposure() }
        bool(body, "lowLight")?.let { if (it != lowLight) { lowLight = it; save("lowlight", it); rebind = true } }
        bool(body, "torch")?.let { torchOn = it; applyTorch() }
        bool(body, "front")?.let { if (it != useFront) { useFront = it; save("front", it); rebind = true } }
        bool(body, "deepSave")?.let { deepSave = it; save("deepsave", it); bindingCheck = true }
        bool(body, "httpsOnly")?.let { httpsOnly = it; save("httpsonly", it) }

        // Telegram
        if (bool(body, "tgClear") == true) {
            tgToken = ""; tgChat = ""; tgOn = false
            save("tg_token", ""); save("tg_chat", ""); save("tg_on", false)
            bindingCheck = true
        }
        Json.get(body, "tgToken")?.trim()?.let {
            if (it.isNotEmpty()) {
                if (!TOKEN_RE.matches(it)) return "Bot token biçimi hatalı. BotFather'ın verdiği 123456789:ABC... biçimindeki metni olduğu gibi yapıştır."
                tgToken = it; save("tg_token", it)
            }
        }
        Json.get(body, "tgChat")?.trim()?.let {
            if (it.isNotEmpty()) {
                if (!CHAT_RE.matches(it)) return "Chat ID biçimi hatalı (yalnızca rakamlar olmalı)."
                tgChat = it; save("tg_chat", it)
            }
        }
        bool(body, "tgOn")?.let {
            if (it && (tgToken.isBlank() || tgChat.isBlank())) return "Bildirimi açmadan önce bot token ve Chat ID gerekli."
            tgOn = it; save("tg_on", it); bindingCheck = true
        }
        bool(body, "tgPhoto")?.let { tgPhoto = it; save("tg_photo", it) }
        bool(body, "tgSec")?.let { save("tg_sec", it) }
        Json.get(body, "tgCooldown")?.toIntOrNull()?.let { tgCooldownSec = it.coerceIn(10, 3600); save("tg_cooldown", tgCooldownSec) }

        if (rebind) rebindCamera()
        else if (bindingCheck) main.post { updateCameraBinding() }
        return null
    }

    private fun serveAsset(out: OutputStream, name: String, mime: String) {
        try {
            val bytes = assets.open(name).use { it.readBytes() }
            send(
                out, 200, mime, bytes,
                listOf("Cache-Control: no-store", "X-Frame-Options: DENY", "Referrer-Policy: no-referrer", "X-Content-Type-Options: nosniff")
            )
        } catch (_: Exception) {
            send(out, 500, "text/plain", "asset yok".toByteArray())
        }
    }

    private fun send(out: OutputStream, code: Int, mime: String, body: ByteArray, extraHeaders: List<String> = emptyList()) {
        val status = when (code) {
            200 -> "200 OK"; 204 -> "204 No Content"; 302 -> "302 Found"
            400 -> "400 Bad Request"; 401 -> "401 Unauthorized"; 403 -> "403 Forbidden"
            404 -> "404 Not Found"; 413 -> "413 Payload Too Large"; 429 -> "429 Too Many Requests"
            500 -> "500 Internal Server Error"; 503 -> "503 Service Unavailable"; else -> "$code"
        }
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(status).append("\r\n")
        sb.append("Content-Type: ").append(mime).append("\r\n")
        sb.append("Content-Length: ").append(body.size).append("\r\n")
        sb.append("Connection: close\r\n")
        corsHeader.get()?.let { sb.append("Access-Control-Allow-Origin: ").append(it).append("\r\nVary: Origin\r\n") }
        for (h in extraHeaders) sb.append(h).append("\r\n")
        sb.append("\r\n")
        out.write(sb.toString().toByteArray())
        out.write(body)
        out.flush()
    }

    private fun queryParam(query: String, key: String): String? {
        if (query.isEmpty()) return null
        for (p in query.split("&")) {
            val kv = p.split("=", limit = 2)
            if (kv.size == 2 && kv[0] == key) {
                return try { java.net.URLDecoder.decode(kv[1], "UTF-8") } catch (_: Exception) { null }
            }
        }
        return null
    }

    // ---------------- Kapanış ----------------

    override fun onDestroy() {
        stopping.set(true)
        running = false
        httpsRunning = false
        viewers = 0
        main.removeCallbacksAndMessages(null)
        try { server?.close() } catch (_: Exception) {}
        try { tlsServer?.close() } catch (_: Exception) {}
        try { provider?.unbindAll() } catch (_: Throwable) {}
        camera = null
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Throwable) {}
        try { if (wifiLock?.isHeld == true) wifiLock?.release() } catch (_: Throwable) {}
        serverExec.shutdownNow()
        analysisExec.shutdownNow()
        alertExec.shutdownNow()
        KameraWidget.updateAll(this)
        super.onDestroy()
    }
}

/** Bildirimdeki ve widget'taki "Durdur" düğmesi için yayın alıcısı. */
class StopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        context.stopService(Intent(context, CameraService::class.java))
    }
}
