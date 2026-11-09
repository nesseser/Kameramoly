package com.hunterr.guvenlikkamera

import android.Manifest
import android.app.Activity
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
import android.provider.Settings
import android.util.TypedValue
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat

class MainActivity : Activity() {
    private lateinit var info: TextView
    private lateinit var toggle: Button
    private val handler = Handler(Looper.getMainLooper())
    private val refresher = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.ensurePin(this)

        val pad = dp(20)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        root.addView(label(26f, "#FFFFFF").apply {
            text = "Güvenlik Kamerası"
            setTypeface(typeface, Typeface.BOLD)
        })
        info = label(18f, "#C9D1D9")
        root.addView(info)

        toggle = Button(this).apply {
            setOnClickListener { if (CameraService.running) stopCam() else startCam() }
        }
        root.addView(toggle)

        root.addView(Button(this).apply {
            text = "PIN değiştir"
            setOnClickListener { changePinDialog() }
        })

        root.addView(Button(this).apply {
            text = "Pil optimizasyonunu kapat"
            setOnClickListener { askBatteryExemption() }
        })

        root.addView(label(14f, "#8B949E").apply {
            text = """
                Nasıl kullanılır:
                1) "Kamerayı başlat"a bas.
                2) Bilgisayar telefonla aynı Wi-Fi ağındayken tarayıcıda yukarıdaki adresi aç.
                3) PIN'i gir.

                Sonra ekranı kilitleyebilir veya uygulamadan çıkabilirsin. Kamera arka planda çalışmaya devam eder.
                Uygulamayı son uygulamalar listesinden kaydırıp kapatsan bile servis çalışır. Durdurmak için bildirimdeki "Durdur"a bas.

                Bildirim çubuğundaki kamera bildirimi Android tarafından zorunludur, gizlenemez.
                Telefonu şarja takılı bırak.
            """.trimIndent()
        })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#111418"))
            addView(root)
        })
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresher)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refresher)
    }

    private fun refresh() {
        val pin = Prefs.pin(this)
        info.text = if (CameraService.running) {
            "Durum: AÇIK\n\nBilgisayarda aç:\nhttp://${NetUtil.ip()}:${CameraService.PORT}\n\nPIN: $pin\n"
        } else {
            "Durum: KAPALI\n\nPIN: $pin\n"
        }
        toggle.text = if (CameraService.running) "Kamerayı durdur" else "Kamerayı başlat"
    }

    private fun startCam() {
        val need = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            need += Manifest.permission.CAMERA
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            need += Manifest.permission.POST_NOTIFICATIONS
        }
        if (need.isNotEmpty()) {
            requestPermissions(need.toTypedArray(), 1)
            return
        }
        ContextCompat.startForegroundService(this, Intent(this, CameraService::class.java))
        handler.postDelayed({ refresh() }, 500)
    }

    private fun stopCam() {
        stopService(Intent(this, CameraService::class.java))
        handler.postDelayed({ refresh() }, 300)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCam()
        } else {
            Toast.makeText(this, "Kamera izni olmadan çalışamaz", Toast.LENGTH_LONG).show()
        }
    }

    private fun changePinDialog() {
        val input = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(Prefs.pin(this@MainActivity))
            hint = "En az 4 haneli PIN"
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("PIN değiştir")
            .setView(input)
            .setPositiveButton("Kaydet") { _, _ ->
                val v = input.text.toString().trim()
                if (v.length < 4) {
                    Toast.makeText(this, "PIN en az 4 haneli olmalı", Toast.LENGTH_SHORT).show()
                } else {
                    Prefs.setPin(this, v)
                    Toast.makeText(this, "PIN kaydedildi", Toast.LENGTH_SHORT).show()
                    refresh()
                }
            }
            .setNegativeButton("Vazgeç", null)
            .show()
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
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun label(sizeSp: Float, color: String) = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(Color.parseColor(color))
        setPadding(0, dp(8), 0, dp(8))
        setTextIsSelectable(true)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
