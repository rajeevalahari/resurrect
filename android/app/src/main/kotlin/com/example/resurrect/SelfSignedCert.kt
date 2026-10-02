package com.example.resurrect

import java.io.ByteArrayInputStream
import java.security.KeyPair
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Generates a self-signed X.509 (v3) RSA certificate using ONLY java.security.
 *
 * BouncyCastle is intentionally avoided: instantiating its provider on some
 * Android/OEM runtimes throws `NoClassDefFoundError` for
 * `org.bouncycastle.asn1.edec.EdECObjectIdentifiers` because a partial copy of
 * BouncyCastle is baked into the platform and collides with the bundled one.
 *
 * adbd only needs a structurally valid certificate that wraps our public key and is
 * signed by our private key, so a minimal hand-rolled DER cert is sufficient. The
 * result is parsed + verified before returning, so a malformed encoding fails fast.
 */
object SelfSignedCert {

    fun create(keyPair: KeyPair, commonName: String): X509Certificate {
        val now = System.currentTimeMillis()
        val notBefore = Date(now - 24L * 60 * 60 * 1000)            // yesterday (clock skew)
        val notAfter = Date(now + 20L * 365 * 24 * 60 * 60 * 1000)  // ~20 years (stays < 2050 for UTCTime)

        val sigAlgId = Der.seq(Der.oid("1.2.840.113549.1.1.11"), Der.nullValue()) // SHA256withRSA

        val name = Der.seq(
            Der.set(
                Der.seq(Der.oid("2.5.4.3"), Der.utf8(commonName)), // CN
            ),
        )

        val validity = Der.seq(Der.utcTime(notBefore), Der.utcTime(notAfter))
        val spki = keyPair.public.encoded // X.509 SubjectPublicKeyInfo

        val serial = ByteArray(12).also {
            SecureRandom().nextBytes(it)
            it[0] = (it[0].toInt() and 0x7F).toByte()
        }

        val tbsCertificate = Der.seq(
            Der.explicit(0, Der.integer(byteArrayOf(2))), // version v3
            Der.integer(serial),
            sigAlgId,
            name, // issuer
            validity,
            name, // subject (== issuer; self-signed)
            spki,
        )

        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(keyPair.private)
            update(tbsCertificate)
            sign()
        }

        val certDer = Der.seq(tbsCertificate, sigAlgId, Der.bitString(signature))

        val certificate = CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(certDer)) as X509Certificate

        certificate.verify(keyPair.public) // fail fast on a bad encoding/signature
        return certificate
    }
}

/** Minimal DER (ASN.1) encoder — just the pieces an X.509 certificate needs. */
private object Der {

    fun seq(vararg parts: ByteArray): ByteArray = tlv(0x30, parts.concat())

    fun set(content: ByteArray): ByteArray = tlv(0x31, content)

    fun integer(value: ByteArray): ByteArray {
        var v = if (value.isEmpty()) byteArrayOf(0) else value
        if (v[0].toInt() and 0x80 != 0) v = byteArrayOf(0) + v // keep positive
        return tlv(0x02, v)
    }

    fun nullValue(): ByteArray = byteArrayOf(0x05, 0x00)

    fun bitString(data: ByteArray): ByteArray = tlv(0x03, byteArrayOf(0x00) + data)

    fun utf8(text: String): ByteArray = tlv(0x0C, text.toByteArray(Charsets.UTF_8))

    fun utcTime(date: Date): ByteArray {
        val format = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return tlv(0x17, format.format(date).toByteArray(Charsets.US_ASCII))
    }

    fun explicit(tagNo: Int, content: ByteArray): ByteArray = tlv(0xA0 or tagNo, content)

    fun oid(dotted: String): ByteArray {
        val parts = dotted.split(".").map { it.toLong() }
        val body = ArrayList<Byte>()
        body.add((40 * parts[0] + parts[1]).toByte())
        for (i in 2 until parts.size) body.addAll(base128(parts[i]).toList())
        return tlv(0x06, body.toByteArray())
    }

    private fun base128(value: Long): ByteArray {
        if (value == 0L) return byteArrayOf(0)
        val out = ArrayList<Byte>()
        var v = value
        var isLast = true
        while (v > 0) {
            val seven = (v and 0x7F).toInt()
            out.add(0, (if (isLast) seven else (seven or 0x80)).toByte())
            isLast = false
            v = v shr 7
        }
        return out.toByteArray()
    }

    private fun tlv(tag: Int, content: ByteArray): ByteArray =
        byteArrayOf(tag.toByte()) + encodeLength(content.size) + content

    private fun encodeLength(length: Int): ByteArray {
        if (length < 0x80) return byteArrayOf(length.toByte())
        val bytes = ArrayList<Byte>()
        var l = length
        while (l > 0) {
            bytes.add(0, (l and 0xFF).toByte())
            l = l ushr 8
        }
        return byteArrayOf((0x80 or bytes.size).toByte()) + bytes.toByteArray()
    }

    private fun Array<out ByteArray>.concat(): ByteArray {
        val result = ByteArray(sumOf { it.size })
        var offset = 0
        for (part in this) {
            part.copyInto(result, offset)
            offset += part.size
        }
        return result
    }
}
