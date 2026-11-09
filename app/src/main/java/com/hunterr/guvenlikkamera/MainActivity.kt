package com.hunterr.guvenlikkamera

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat

class MainActivity : Activity() {

    companion object {
        /** Widget'taki "Başlat" bu eylemle uygulamayı açar. */
        const val ACTION_START = "com.hunterr.guvenlikkamera.START"
        /** Yeni cihaz bildirimindeki "İzin ver" bu eylemle uygulamayı açar. */
        const val ACTION_APPROVE = "com.hunterr.guvenlikkamera.APPROVE"
    }

    // --- Kilit ekranı ---
    private lateinit var lockView: LinearLayout
    private lateinit var lockInput: EditText
    private lateinit var lockMsg: TextView
    private var unlocked = false
    private var lockFails = 0
    private var lockWaitUntil = 0L

    // --- Ana ekran ---
    private lateinit var mainView: LinearLayout
    private lateinit var info: TextView
    private lateinit var toggle: Button
    private lateinit var pendingBtn: Button
    private lateinit var gateBtn: Button
    private lateinit var lockBtn: Button
    private lateinit var pinBtn: Button
    private lateinit var qrBox: LinearLayout
    private lateinit var qrView: ImageView
    private lateinit var qrLabel: TextView
    private lateinit var qrSwitch: Button
    private var qrUrl: String? = null
    private var qrHttps = true
    private var showPin = false
    private var approveRequestId: String? = null

    private val handler = Handler(Looper.getMainLooper())
    private val refresher = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLog.install(this)
        Prefs.ensurePin(this)
        // PIN ve adresler ekran görüntüsüne / son uygulamalar önizlemesine düşmesin.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

        val pad = dp(20)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        container.addView(label(26f, "#FFFFFF").apply {
            text = getString(R.string.app_name)
            setTypeface(typeface, Typeface.BOLD)
        })

        buildLockView()
        buildMainView()
        container.addView(lockView)
        container.addView(mainView)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#111418"))
            addView(container)
        })

        if (savedInstanceState == null) handleIntent(intent)
    }

    // ---------------- Kilit ----------------

    private fun buildLockView() {
        lockMsg = label(15f, "#C9D1D9").apply { text = "Uygulamayı açmak için PIN gir." }
        lockInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "PIN"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#8B949E"))
            imeOptions = EditorInfo.IME_ACTION_DONE
            setOnEditorActionListener { _, _, _ -> tryUnlock(); true }
        }
        lockView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(24), 0, dp(24))
            addView(lockMsg)
            addView(lockInput)
            addView(button("Kilidi aç") { tryUnlock() })
            addView(label(13f, "#8B949E").apply {
                text = "Kamera arka planda çalışmaya devam eder. PIN'i unutursan: Ayarlar > Uygulamalar > " +
                    "Güvenlik Kamerası > Depolama > Verileri temizle (PIN 6509'a döner, onaylı cihazlar silinir)."
            })
        }
    }

    private fun tryUnlock() {
        val now = SystemClock.elapsedRealtime()
        if (now < lockWaitUntil) {
            lockMsg.text = "Çok fazla yanlış deneme. ${(lockWaitUntil - now) / 1000 + 1} sn bekle."
            return
        }
        val entered = lockInput.text.toString().trim()
        lockInput.setText("")
        if (entered.isNotEmpty() && entered == Prefs.pin(this)) {
            lockFails = 0
            unlocked = true
            applyLockState()
        } else {
            lockFails++
            if (lockFails >= 5) {
                lockWaitUntil = now + 30_000L * (lockFails - 4)
                lockMsg.text = "Yanlış PIN. ${(lockWaitUntil - now) / 1000} sn bekle."
            } else {
                lockMsg.text = "Yanlış PIN. Kalan deneme: ${5 - lockFails}"
            }
        }
    }

    private fun applyLockState() {
        val needLock = CameraService.appLockEnabled(this) && !unlocked
        lockView.visibility = if (needLock) View.VISIBLE else View.GONE
        mainView.visibility = if (needLock) View.GONE else View.VISIBLE
        if (!needLock) {
            refresh()
            approveRequestId?.let { id ->
                approveRequestId = null
                askApprove(id)
            }
        }
    }

    // ---------------- Ana ekran ----------------

    private fun buildMainView() {
        mainView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        pendingBtn = button("") { showPendingRequests() }.apply {
            setBackgroundColor(Color.parseColor("#D29922"))
            setTextColor(Color.BLACK)
            visibility = View.GONE
        }
        mainView.addView(pendingBtn)

        info = label(17f, "#C9D1D9")
        mainView.addView(info)

        toggle = button("") { if (CameraService.running) stopCam() else startCam() }
        mainView.addView(toggle)

        // --- QR kod: başka bir telefonla okutup paneli açmak için ---
        qrLabel = label(14f, "#8B949E").apply { gravity = Gravity.CENTER_HORIZONTAL }
        qrView = ImageView(this).apply {
            adjustViewBounds = true
            setBackgroundColor(Color.WHITE)
            val p = dp(6)
            setPadding(p, p, p, p)
        }
        qrSwitch = button("") {
            qrHttps = !qrHttps
            refresh()
        }
        qrBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(12), 0, dp(12))
            addView(qrLabel)
            addView(qrView, LinearLayout.LayoutParams(dp(230), dp(230)).apply { topMargin = dp(6) })
            addView(qrSwitch, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) })
            visibility = View.GONE
        }
        mainView.addView(qrBox)

        mainView.addView(section("Güvenlik"))
        pinBtn = button("") { showPin = !showPin; refresh() }
        mainView.addView(pinBtn)
        mainView.addView(button("PIN değiştir") { changePinDialog() })
        mainView.addView(button("Onaylı cihazlar") { showDevices() })
        mainView.addView(button("Tüm oturumları kapat (herkesi çıkar)") {
            CameraService.revokeAllSessions()
            Toast.makeText(this, "Tüm panel oturumları kapatıldı", Toast.LENGTH_SHORT).show()
        })
        gateBtn = button("") { toggleDeviceGate() }
        mainView.addView(gateBtn)
        lockBtn = button("") { toggleAppLock() }
        mainView.addView(lockBtn)
        mainView.addView(button("Bağlantı geçmişi") { showHistory() })

        mainView.addView(section("Diğer"))
        mainView.addView(button("Pil optimizasyonunu kapat") { askBatteryExemption() })
        mainView.addView(button("Hata kayıtları") { showCrashLog() })

        mainView.addView(label(14f, "#8B949E").apply {
            text = """
                Nasıl kullanılır:
                1) "Kamerayı başlat"a bas.
                2) Bilgisayar telefonla aynı Wi-Fi ağındayken tarayıcıda yukarıdaki https adresini aç (veya başka bir telefonla QR kodu okut).
                3) Tarayıcı "Bağlantınız gizli değil" der: bu normaldir, sertifikayı telefon kendisi üretti. "Gelişmiş" > "Devam et".
                4) PIN'i gir. İlk kez bağlanan her cihaz için telefonda bildirim çıkar: "İzin ver"e bas, uygulama PIN'ini gir, onayla.

                Güvenlik:
                • Doğru PIN'i bilen biri bile, sen telefondan onay vermedikçe bağlanamaz.
                • Yalnızca ev ağından bağlanılabilir; internetten gelen bağlantılar reddedilir.
                • Bağlantı şifrelidir (HTTPS); PIN ve görüntü ağda okunamaz.
                • Yanlış PIN denemeleri kilitlenir ve "Bağlantı geçmişi"ne yazılır; Telegram ayarlıysa sana bildirilir.
                • Sertifika parmak izini tarayıcıdaki (kilit simgesi > sertifika) ile karşılaştırabilirsin.

                Ana ekran widget'ı: Ana ekranda boş bir yere basılı tut > Widget'lar > Güvenlik Kamerası.

                Hareket bildirimi (Telegram), kalite, zoom, netleştirme ve diğer kameralar bilgisayardaki panelde.

                Pil: Kimse izlemezken görüntü işlenmez. Panelde "Derin pil tasarrufu" açılırsa kamera da tamamen kapanır.

                ÖNEMLİ (Xiaomi/Redmi/POCO): Uygulamanın kendi kendine kapanmaması için:
                • Ayarlar > Uygulamalar > Güvenlik Kamerası > "Otomatik başlatma"yı AÇ.
                • Aynı yerde "Pil tasarrufu"nu "Kısıtlama yok" yap.
                • Son uygulamalar ekranında uygulamayı aşağı çekip kilit simgesine bas.

                Bu uygulama görüntü kaydetmez; yalnızca canlı izleme sağlar. Kamera bildirimi Android tarafından zorunludur, gizlenemez.
            """.trimIndent()
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        when (i?.action) {
            ACTION_START -> {
                setIntent(Intent(this, MainActivity::class.java))
                if (!CameraService.running) handler.post { startCam() }
            }
            ACTION_APPROVE -> {
                setIntent(Intent(this, MainActivity::class.java))
                approveRequestId = i.getStringExtra(DeviceGate.EXTRA_REQ)
                if (::mainView.isInitialized && mainView.visibility == View.VISIBLE) applyLockState()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        DeviceGate.onChange = { runOnUiThread { refresh() } }
        applyLockState()
        handler.post(refresher)
    }

    override fun onPause() {
        super.onPause()
        DeviceGate.onChange = null
        handler.removeCallbacks(refresher)
    }

    override fun onStop() {
        super.onStop()
        // Uygulamadan çıkınca kilitlenir.
        unlocked = false
    }

    private fun refresh() {
        if (mainView.visibility != View.VISIBLE) return
        val pin = Prefs.pin(this)
        val ip = NetUtil.ipOrNull()
        val running = CameraService.running
        val https = CameraService.httpsRunning

        val pend = DeviceGate.pendingList()
        pendingBtn.visibility = if (pend.isEmpty()) View.GONE else View.VISIBLE
        pendingBtn.text = "⚠ Bağlanmak isteyen cihaz var (${pend.size}) — dokun"

        info.text = if (running) {
            val sb = StringBuilder("Durum: AÇIK")
            val v = CameraService.viewers
            if (v > 0) sb.append("  •  $v kişi izliyor")
            sb.append("\n\nBilgisayarda aç:\n")
            if (ip == null) {
                sb.append("IP bulunamadı — Wi-Fi'ye bağlı mısın?\n")
            } else {
                if (https) sb.append("https://$ip:${CameraService.TLS_PORT}\n")
                else sb.append("(şifreli adres hazırlanıyor…)\n")
                sb.append("http://$ip:${CameraService.PORT}  (şifresiz; 'Yalnızca HTTPS' açıksa https'e yönlendirir)\n")
            }
            val fp = CameraService.fingerprint
            if (fp.isNotEmpty()) sb.append("\nSertifika parmak izi (SHA-256):\n$fp\n")
            sb.toString()
        } else {
            "Durum: KAPALI\n"
        }
        toggle.text = if (running) "Kamerayı durdur" else "Kamerayı başlat"
        pinBtn.text = if (showPin) "PIN: $pin  (gizle)" else "PIN: ${"•".repeat(pin.length)}  (göster)"
        if (pin.length < 6) pinBtn.text = "${pinBtn.text}  — 6+ hane daha güvenli"
        gateBtn.text = "Yeni cihaz onayı: " + if (DeviceGate.enabled(this)) "AÇIK ✓" else "KAPALI ✗ (önerilmez)"
        lockBtn.text = "Uygulama kilidi: " + if (CameraService.appLockEnabled(this)) "AÇIK ✓" else "KAPALI ✗"

        // QR kod yalnızca URL değişince yeniden üretilir.
        if (running && ip != null) {
            val useHttps = qrHttps && https
            val url = if (useHttps) "https://$ip:${CameraService.TLS_PORT}/" else "http://$ip:${CameraService.PORT}/"
            if (url != qrUrl) {
                try {
                    qrView.setImageBitmap(Qr.bitmap(url, dp(220)))
                    qrUrl = url
                } catch (t: Throwable) {
                    CrashLog.record(this, t)
                }
            }
            qrLabel.text = "Başka bir telefonla okut:\n$url"
            qrSwitch.text = if (useHttps) "QR'ı şifresiz (http) yap" else "QR'ı şifreli (https) yap"
            qrSwitch.visibility = if (https) View.VISIBLE else View.GONE
            qrBox.visibility = View.VISIBLE
        } else {
            qrBox.visibility = View.GONE
        }
    }

    // ---------------- Cihaz onayı ----------------

    private fun askApprove(id: String) {
        val r = DeviceGate.get(id)
        if (r == null || r.state != "pending") {
            Toast.makeText(this, "Bu istek artık geçerli değil (süresi doldu veya cevaplandı).", Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Cihaza izin verilsin mi?")
            .setMessage(
                "${r.name}\nIP: ${r.ip}\nKalan süre: ${r.secondsLeft()} sn\n\n" +
                    "Bu cihaz SENİN değilse Reddet'e bas ve PIN'i hemen değiştir."
            )
            .setPositiveButton("İzin ver") { _, _ ->
                val ok = DeviceGate.resolve(this, r.id, true)
                Toast.makeText(this, if (ok) "Cihaz onaylandı" else "İstek süresi dolmuş", Toast.LENGTH_SHORT).show()
                refresh()
            }
            .setNegativeButton("Reddet") { _, _ ->
                DeviceGate.resolve(this, r.id, false)
                refresh()
            }
            .setNeutralButton("Kapat", null)
            .show()
    }

    private fun showPendingRequests() {
        val list = DeviceGate.pendingList()
        if (list.isEmpty()) {
            refresh()
            return
        }
        if (list.size == 1) {
            askApprove(list[0].id)
            return
        }
        val labels = list.map { "${it.name} • ${it.ip}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Bağlanmak isteyen cihazlar")
            .setItems(labels) { _, which -> askApprove(list[which].id) }
            .setNegativeButton("Kapat", null)
            .show()
    }

    private fun showDevices() {
        val list = DeviceGate.devices(this)
        if (list.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Onaylı cihazlar")
                .setMessage("Henüz onaylı cihaz yok. Bir bilgisayar panele ilk girdiğinde burada onaylayacaksın.")
                .setPositiveButton("Tamam", null)
                .show()
            return
        }
        val labels = list.map { "${it.name}\n   eklendi: ${it.added}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Onaylı cihazlar (kaldırmak için dokun)")
            .setItems(labels) { _, which ->
                val d = list[which]
                AlertDialog.Builder(this)
                    .setTitle("Cihaz kaldırılsın mı?")
                    .setMessage("${d.name}\n\nBu cihaz bir daha bağlanmak isterse yeniden onay gerekir. Açık oturumu hemen kapanır.")
                    .setPositiveButton("Kaldır") { _, _ ->
                        DeviceGate.revoke(this, d.id)
                        Toast.makeText(this, "Kaldırıldı", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Vazgeç", null)
                    .show()
            }
            .setNeutralButton("Hepsini kaldır") { _, _ ->
                DeviceGate.revokeAll(this)
                CameraService.revokeAllSessions()
                Toast.makeText(this, "Tüm cihazlar kaldırıldı", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Kapat", null)
            .show()
    }

    private fun toggleDeviceGate() {
        if (DeviceGate.enabled(this)) {
            AlertDialog.Builder(this)
                .setTitle("Yeni cihaz onayı kapatılsın mı?")
                .setMessage("Kapatırsan PIN'i bilen herkes, telefondan onay gerekmeden panele girebilir. Önerilmez.")
                .setPositiveButton("Yine de kapat") { _, _ -> DeviceGate.setEnabled(this, false); refresh() }
                .setNegativeButton("Açık kalsın", null)
                .show()
        } else {
            DeviceGate.setEnabled(this, true)
            CameraService.revokeAllSessions()
            Toast.makeText(this, "Açıldı. Onaysız cihazların oturumları kapatıldı.", Toast.LENGTH_LONG).show()
            refresh()
        }
    }

    private fun toggleAppLock() {
        val on = CameraService.appLockEnabled(this)
        Prefs.put(this, mapOf("app_lock" to !on))
        KameraWidget.updateAll(this)
        Toast.makeText(
            this,
            if (on) "Uygulama kilidi kapatıldı. Bildirim/widget'tan doğrudan durdurulabilir."
            else "Uygulama kilidi açıldı. Durdurmak ve ayarlar için PIN gerekecek.",
            Toast.LENGTH_LONG
        ).show()
        refresh()
    }

    // ---------------- Kamera ----------------

    private fun startCam() {
        // Sadece KAMERA zorunlu. Bildirim izni birlikte istenir (cihaz onayı bildirimleri için
        // önemli) ama servisi engellemez.
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            val perms = if (Build.VERSION.SDK_INT >= 33)
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.POST_NOTIFICATIONS)
            else
                arrayOf(Manifest.permission.CAMERA)
            requestPermissions(perms, 1)
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, "Bildirim izni kapalı: cihaz onay istekleri yalnızca bu ekranda görünür.", Toast.LENGTH_LONG).show()
        }
        try {
            ContextCompat.startForegroundService(this, Intent(this, CameraService::class.java))
        } catch (t: Throwable) {
            CrashLog.record(this, t)
            Toast.makeText(this, "Servis başlatılamadı, 'Hata kayıtları'na bak", Toast.LENGTH_LONG).show()
        }
        handler.postDelayed({ refresh() }, 500)

        // Uzun süre çalışabilmesi için pil optimizasyonundan muaf tut (ilk seferde sorar).
        val pm = getSystemService(PowerManager::class.java)
        if (pm != null && !pm.isIgnoringBatteryOptimizations(packageName)) {
            handler.postDelayed({ askBatteryExemption() }, 800)
        }
    }

    private fun stopCam() {
        stopService(Intent(this, CameraService::class.java))
        handler.postDelayed({ refresh() }, 300)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != 1) return
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCam()
        } else {
            Toast.makeText(this, "Kamera izni olmadan çalışamaz", Toast.LENGTH_LONG).show()
        }
    }

    private fun changePinDialog() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "Yeni PIN (en az 4, önerilen 6+ hane)"
        }
        AlertDialog.Builder(this)
            .setTitle("PIN değiştir")
            .setView(input)
            .setPositiveButton("Kaydet") { _, _ ->
                val v = input.text.toString().trim()
                if (v.length < 4 || !v.all { it.isDigit() }) {
                    Toast.makeText(this, "PIN en az 4 haneli ve sadece rakam olmalı", Toast.LENGTH_SHORT).show()
                } else {
                    Prefs.setPin(this, v)
                    // PIN değişince açık tüm panel oturumları kapanır.
                    CameraService.revokeAllSessions()
                    Toast.makeText(this, "PIN kaydedildi, tüm oturumlar kapatıldı", Toast.LENGTH_SHORT).show()
                    refresh()
                }
            }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    private fun showHistory() {
        val list = ConnLog.entries(this)
        val body = if (list.isEmpty()) "Henüz kayıt yok." else list.joinToString("\n") { (time, ip, ev) -> "$time\n   $ip — $ev" }
        AlertDialog.Builder(this)
            .setTitle("Bağlantı geçmişi (son ${list.size})")
            .setView(scrollText(body))
            .setPositiveButton("Kapat", null)
            .setNeutralButton("Temizle") { _, _ ->
                ConnLog.clear(this)
                Toast.makeText(this, "Geçmiş temizlendi", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun showCrashLog() {
        val log = CrashLog.read(this)
        val body = if (log.isNullOrBlank()) "Kayıtlı bir hata yok." else log
        AlertDialog.Builder(this)
            .setTitle("Hata kayıtları")
            .setView(scrollText(body))
            .setPositiveButton("Kopyala") { _, _ ->
                val cm = getSystemService(ClipboardManager::class.java)
                cm.setPrimaryClip(ClipData.newPlainText("hata", body))
                Toast.makeText(this, "Kopyalandı", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Kapat", null)
            .setNeutralButton("Temizle") { _, _ -> CrashLog.clear(this) }
            .show()
    }

    private fun scrollText(body: String) = ScrollView(this).apply {
        addView(TextView(this@MainActivity).apply {
            text = body
            setTextIsSelectable(true)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            textSize = 13f
        })
    }

    private fun askBatteryExemption() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, "Pil optimizasyonu zaten kapalı", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {
            }
        }
    }

    private fun section(title: String) = label(18f, "#FFFFFF").apply {
        text = title
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(20), 0, dp(4))
    }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun label(sizeSp: Float, color: String) = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(Color.parseColor(color))
        setPadding(0, dp(8), 0, dp(8))
        setTextIsSelectable(true)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
