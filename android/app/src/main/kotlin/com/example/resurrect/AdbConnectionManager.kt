package com.example.resurrect

import android.content.Context
import android.os.Build
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import java.io.File
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec

/**
 * Concrete [AbsAdbConnectionManager] for the on-device loopback.
 *
 * libadb-android speaks the raw ADB wire protocol (Android 11 pairing/SPAKE2 + TLS 1.3)
 * directly to the local `adbd`, so no PC / adb server is involved. We only supply a
 * stable RSA identity (key pair + self-signed certificate), persisted under the app's
 * private files so pairing trust survives restarts.
 */
class AdbConnectionManager private constructor(context: Context) : AbsAdbConnectionManager() {

    private val privateKey: PrivateKey
    private val certificate: Certificate

    init {
        setApi(Build.VERSION.SDK_INT)
        val identity = loadOrCreateIdentity(context)
        privateKey = identity.first
        certificate = identity.second
    }

    override fun getPrivateKey(): PrivateKey = privateKey

    override fun getCertificate(): Certificate = certificate

    override fun getDeviceName(): String = "Resurrect"

    companion object {
        private const val KEY_FILE = "adb_private_key.pk8"
        private const val CERT_FILE = "adb_certificate.der"

        @Volatile
        private var instance: AdbConnectionManager? = null

        fun getInstance(context: Context): AdbConnectionManager {
            return instance ?: synchronized(this) {
                instance ?: AdbConnectionManager(context.applicationContext).also { instance = it }
            }
        }

        private fun loadOrCreateIdentity(context: Context): Pair<PrivateKey, X509Certificate> {
            val keyFile = File(context.filesDir, KEY_FILE)
            val certFile = File(context.filesDir, CERT_FILE)

            if (keyFile.exists() && certFile.exists()) {
                runCatching {
                    val privateKey = KeyFactory.getInstance("RSA")
                        .generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
                    val certificate = certFile.inputStream().use { input ->
                        CertificateFactory.getInstance("X.509")
                            .generateCertificate(input) as X509Certificate
                    }
                    return privateKey to certificate
                }
                // Regenerate if the stored identity is unreadable.
            }

            val (privateKey, certificate) = generateIdentity()
            keyFile.writeBytes(privateKey.encoded)
            certFile.writeBytes(certificate.encoded)
            return privateKey to certificate
        }

        private fun generateIdentity(): Pair<PrivateKey, X509Certificate> {
            val keyPairGenerator = KeyPairGenerator.getInstance("RSA").apply {
                initialize(2048, SecureRandom())
            }
            val keyPair = keyPairGenerator.generateKeyPair()
            val certificate = SelfSignedCert.create(keyPair, "CN=Resurrect")
            return keyPair.private to certificate
        }
    }
}
