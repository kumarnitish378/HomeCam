package com.example.core

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.spec.ECGenParameterSpec
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.security.auth.x500.X500Principal

/**
 * The camera's HTTPS identity for internet (IPv6) access.
 *
 * An EC P-256 key is created once inside the Android Keystore (the private key never leaves
 * it) together with a self-signed certificate. Viewers don't trust it through a CA: they pin
 * the certificate's SHA-256 fingerprint, which they get from the pairing QR code.
 */
object TlsIdentity {
    private const val ALIAS = "homecam_tls"
    private const val TWENTY_YEARS_MS = 20L * 365 * 24 * 60 * 60 * 1000

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    @Synchronized
    private fun ensureKey(ks: KeyStore) {
        if (ks.containsAlias(ALIAS)) return
        val now = System.currentTimeMillis()
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(
                KeyProperties.DIGEST_NONE,
                KeyProperties.DIGEST_SHA256,
                KeyProperties.DIGEST_SHA384,
                KeyProperties.DIGEST_SHA512
            )
            .setCertificateSubject(X500Principal("CN=HomeCam"))
            .setCertificateSerialNumber(BigInteger.valueOf(now))
            .setCertificateNotBefore(Date(now - 24L * 60 * 60 * 1000))
            .setCertificateNotAfter(Date(now + TWENTY_YEARS_MS))
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
            initialize(spec)
            generateKeyPair()
        }
    }

    /** SHA-256 of the certificate, lowercase hex. Creates the key on first use. */
    fun fingerprint(): String {
        val ks = keyStore()
        ensureKey(ks)
        val cert = ks.getCertificate(ALIAS)
        return sha256Hex(cert.encoded)
    }

    /** TLS server context backed by the keystore key. */
    fun serverContext(): SSLContext {
        val ks = keyStore()
        ensureKey(ks)
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, null)
        return SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
    }

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
