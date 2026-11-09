package com.hunterr.guvenlikkamera

import java.io.ByteArrayOutputStream
import java.io.File
import java.net.Socket
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLServerSocketFactory
import javax.net.ssl.X509ExtendedKeyManager

/**
 * HTTPS için telefonun kendi ürettiği (self-signed) sertifika.
 *
 * Ek kütüphane kullanmadan, standart Java şifreleme API'leriyle X.509 sertifikası üretir
 * (RSA 2048 + SHA-256). Anahtar uygulamanın özel klasöründe saklanır, her açılışta yeniden
 * üretilmez. Telefonun IP adresi değişirse sertifika yenilenir.
 *
 * Bu dosya Android'e özgü hiçbir şey kullanmaz; bu sayede bilgisayarda da test edilebilir.
 */
object Tls {

    class Material(val key: PrivateKey, val cert: X509Certificate) {
        /** Tarayıcının sertifika ekranında görünen SHA-256 parmak izi (AB:CD:...). */
        val fingerprint: String by lazy {
            MessageDigest.getInstance("SHA-256").digest(cert.encoded)
                .joinToString(":") { "%02X".format(it) }
        }
    }

    /** Var olan sertifikayı yükler; yoksa, bozuksa, süresi dolmuşsa veya IP değiştiyse yenisini üretir. */
    fun loadOrCreate(dir: File, ip: String): Material {
        val keyF = File(dir, "tls_key.der")
        val certF = File(dir, "tls_cert.der")
        val ipF = File(dir, "tls_ip.txt")
        try {
            if (keyF.exists() && certF.exists() && ipF.exists() && ipF.readText() == ip) {
                val m = Material(parseKey(keyF.readBytes()), parseCert(certF.readBytes()))
                m.cert.checkValidity(Date(System.currentTimeMillis() + 7L * 24 * 3600 * 1000))
                return m
            }
        } catch (_: Throwable) {
            // bozuk ya da süresi dolmak üzere: aşağıda yeniden üret
        }
        val g = CertGen.generate("Guvenlik Kamerasi", CertGen.ipv4Bytes(ip))
        keyF.writeBytes(g.keyPkcs8)
        certF.writeBytes(g.certDer)
        ipF.writeText(ip)
        return Material(parseKey(g.keyPkcs8), parseCert(g.certDer))
    }

    fun serverSocketFactory(m: Material): SSLServerSocketFactory {
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(arrayOf<KeyManager>(FixedKeyManager(m.key, arrayOf(m.cert))), null, SecureRandom())
        return ctx.serverSocketFactory
    }

    private fun parseKey(pkcs8: ByteArray): PrivateKey =
        KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(pkcs8))

    private fun parseCert(der: ByteArray): X509Certificate =
        CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream()) as X509Certificate

    /** Her zaman aynı anahtar/sertifikayı veren basit anahtar yöneticisi (anahtar deposu gerekmez). */
    private class FixedKeyManager(
        private val key: PrivateKey,
        private val chain: Array<X509Certificate>
    ) : X509ExtendedKeyManager() {
        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) = ALIAS
        override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?) = ALIAS
        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)
        override fun getCertificateChain(alias: String?) = chain
        override fun getPrivateKey(alias: String?) = key
        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
        override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String? = null

        companion object { const val ALIAS = "kamera" }
    }
}

/** Minimal DER kodlayıcı ile X.509 v3 sertifikası üretir. */
object CertGen {

    class Generated(val keyPkcs8: ByteArray, val certDer: ByteArray)

    fun generate(commonName: String, ip: ByteArray?, nowMs: Long = System.currentTimeMillis()): Generated {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048, SecureRandom())
        val kp = kpg.generateKeyPair()

        // sha256WithRSAEncryption, parametre: NULL
        val sigAlg = seq(oid("1.2.840.113549.1.1.11"), byteArrayOf(0x05, 0x00))

        // Pozitif, sıfırla başlamayan 16 baytlık seri numarası (DER için en kısa biçim)
        val serial = ByteArray(16).also { SecureRandom().nextBytes(it) }
        serial[0] = ((serial[0].toInt() and 0x3F) or 0x40).toByte()

        val name = seq(set(seq(oid("2.5.4.3"), tlv(0x0C, commonName.toByteArray(Charsets.UTF_8)))))
        val validity = seq(
            time(nowMs - 24L * 3600 * 1000),
            time(nowMs + 10L * 365 * 24 * 3600 * 1000)
        )

        val ext = ArrayList<ByteArray>()
        if (ip != null && ip.size == 4) {
            // subjectAltName: iPAddress [7]
            ext += seq(oid("2.5.29.17"), tlv(0x04, seq(tlv(0x87, ip))))
        }
        // basicConstraints: CA değil
        ext += seq(oid("2.5.29.19"), tlv(0x04, seq()))
        // keyUsage (kritik): digitalSignature + keyEncipherment
        ext += seq(
            oid("2.5.29.15"),
            byteArrayOf(0x01, 0x01, 0xFF.toByte()),
            tlv(0x04, byteArrayOf(0x03, 0x02, 0x05, 0xA0.toByte()))
        )
        // extendedKeyUsage: serverAuth
        ext += seq(oid("2.5.29.37"), tlv(0x04, seq(oid("1.3.6.1.5.5.7.3.1"))))

        val tbs = seq(
            tlv(0xA0, tlv(0x02, byteArrayOf(0x02))),   // sürüm: v3
            tlv(0x02, serial),
            sigAlg,
            name,                                     // düzenleyen
            validity,
            name,                                     // konu (kendinden imzalı)
            kp.public.encoded,                        // SubjectPublicKeyInfo (zaten DER)
            tlv(0xA3, seq(*ext.toTypedArray()))
        )

        val signer = Signature.getInstance("SHA256withRSA")
        signer.initSign(kp.private)
        signer.update(tbs)
        val signature = signer.sign()

        val cert = seq(tbs, sigAlg, tlv(0x03, byteArrayOf(0x00) + signature))
        return Generated(kp.private.encoded, cert)
    }

    /** "192.168.1.5" -> 4 bayt; geçersizse null. */
    fun ipv4Bytes(ip: String): ByteArray? {
        val parts = ip.trim().split(".")
        if (parts.size != 4) return null
        val out = ByteArray(4)
        for (i in 0 until 4) {
            val v = parts[i].toIntOrNull() ?: return null
            if (v !in 0..255) return null
            out[i] = v.toByte()
        }
        return out
    }

    // ---------------- DER yardımcıları ----------------

    private fun length(n: Int): ByteArray = when {
        n < 0x80 -> byteArrayOf(n.toByte())
        n < 0x100 -> byteArrayOf(0x81.toByte(), n.toByte())
        n < 0x10000 -> byteArrayOf(0x82.toByte(), (n shr 8).toByte(), n.toByte())
        else -> byteArrayOf(0x83.toByte(), (n shr 16).toByte(), (n shr 8).toByte(), n.toByte())
    }

    fun tlv(tag: Int, value: ByteArray): ByteArray = byteArrayOf(tag.toByte()) + length(value.size) + value

    private fun concat(parts: Array<out ByteArray>): ByteArray {
        val bos = ByteArrayOutputStream()
        for (p in parts) bos.write(p)
        return bos.toByteArray()
    }

    fun seq(vararg parts: ByteArray): ByteArray = tlv(0x30, concat(parts))

    private fun set(vararg parts: ByteArray): ByteArray = tlv(0x31, concat(parts))

    fun oid(dotted: String): ByteArray {
        val p = dotted.split(".").map { it.toLong() }
        val bos = ByteArrayOutputStream()
        bos.write((p[0] * 40 + p[1]).toInt())
        for (i in 2 until p.size) {
            var v = p[i]
            val groups = ArrayList<Int>()
            groups.add((v and 0x7F).toInt())
            v = v shr 7
            while (v > 0) {
                groups.add(((v and 0x7F) or 0x80).toInt())
                v = v shr 7
            }
            for (j in groups.indices.reversed()) bos.write(groups[j])
        }
        return tlv(0x06, bos.toByteArray())
    }

    /** 2050'den önce UTCTime, sonrası GeneralizedTime (RFC 5280). */
    private fun time(ms: Long): ByteArray {
        val utc = TimeZone.getTimeZone("UTC")
        val cal = Calendar.getInstance(utc).apply { timeInMillis = ms }
        return if (cal.get(Calendar.YEAR) < 2050) {
            val f = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US).apply { timeZone = utc }
            tlv(0x17, f.format(Date(ms)).toByteArray(Charsets.US_ASCII))
        } else {
            val f = SimpleDateFormat("yyyyMMddHHmmss'Z'", Locale.US).apply { timeZone = utc }
            tlv(0x18, f.format(Date(ms)).toByteArray(Charsets.US_ASCII))
        }
    }
}
